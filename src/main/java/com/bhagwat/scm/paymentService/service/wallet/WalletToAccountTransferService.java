package com.bhagwat.scm.paymentService.service.wallet;

import com.bhagwat.scm.paymentService.common.*;
import com.bhagwat.scm.paymentService.dto.cashfree.payout.CfPayoutTransferRequest;
import com.bhagwat.scm.paymentService.dto.cashfree.payout.CfPayoutTransferResponse;
import com.bhagwat.scm.paymentService.entity.wallet.WalletTransaction;
import com.bhagwat.scm.paymentService.entity.wallet.WalletTransfer;
import com.bhagwat.scm.paymentService.repository.WalletTransactionRepository;
import com.bhagwat.scm.paymentService.repository.WalletTransferRepository;
import com.bhagwat.scm.paymentService.rest.CashfreePayoutClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Debit a wallet and initiate a Cashfree Payout to a bank account.
 *
 * Flow:
 *   1. Debit wallet (optimistic locking, synchronous)
 *   2. Record DEBIT ledger entry
 *   3. Call Cashfree Payout API
 *   4. On payout accepted → status = PAYOUT_INITIATED (returns to client)
 *   5. On payout webhook SUCCESS → status = COMPLETED (via WalletWebhookHandler)
 *   6. On payout webhook FAILED → reverse debit → status = REVERSED
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletToAccountTransferService {

    private final WalletBalanceService        balanceService;
    private final WalletTransferRepository    transferRepo;
    private final WalletTransactionRepository txnRepo;
    private final WalletAuditService          auditService;
    private final CashfreePayoutClient        payoutClient;

    @Transactional
    public WalletTransfer execute(WalletTransfer transfer) {
        log.info("W2A transfer: transferId={} srcWallet={} dstAccount={} amount={}",
                transfer.getTransferId(), transfer.getSourceWalletId(),
                transfer.getDestinationAccountNumber(), transfer.getAmount());

        // ── Step 1: Debit wallet ───────────────────────────────────────────────
        BigDecimal balanceBefore = balanceService.debit(
                transfer.getSourceWalletId(),
                transfer.getSourceWalletType(),
                transfer.getAmount());

        transfer.setStatus(WalletTransferStatus.DEBITED);
        transferRepo.save(transfer);

        WalletTransaction debitTxn = WalletTransaction.builder()
                .transactionId(UUID.randomUUID().toString())
                .transferId(transfer.getTransferId())
                .walletId(transfer.getSourceWalletId())
                .walletType(transfer.getSourceWalletType())
                .transactionType(WalletTransactionType.DEBIT)
                .amount(transfer.getAmount())
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceBefore.subtract(transfer.getAmount()))
                .currency(transfer.getCurrency())
                .description("W2A debit → " + transfer.getDestinationAccountNumber())
                .build();
        txnRepo.save(debitTxn);

        auditService.record(AuditEventType.BALANCE_DEBITED,
                transfer.getSourceWalletId(), transfer.getSourceWalletType(),
                transfer.getTransferId(),
                balanceBefore.toString(), balanceBefore.subtract(transfer.getAmount()).toString(),
                transfer.getInitiatedBy(),
                "W2A debit: " + transfer.getAmount());

        // ── Step 2: Initiate Cashfree Payout ──────────────────────────────────
        CfPayoutTransferRequest cfRequest = buildPayoutRequest(transfer);
        try {
            CfPayoutTransferResponse cfResponse = payoutClient.initiateTransfer(cfRequest);
            transfer.setCfPayoutId(cfResponse.getCfTransferId());
            transfer.setStatus(WalletTransferStatus.PAYOUT_INITIATED);
            transfer = transferRepo.save(transfer);
            auditService.recordTransferEvent(AuditEventType.TRANSFER_PAYOUT_INITIATED,
                    transfer.getTransferId(), WalletTransferStatus.DEBITED.name(),
                    WalletTransferStatus.PAYOUT_INITIATED.name(),
                    "Payout initiated: cfTransferId=" + cfResponse.getCfTransferId());
            log.info("W2A payout initiated: transferId={} cfTransferId={}",
                    transfer.getTransferId(), cfResponse.getCfTransferId());
        } catch (Exception e) {
            // Payout failed — reverse the debit
            log.error("W2A payout API call failed, reversing debit: transferId={}", transfer.getTransferId(), e);
            reverseDebit(transfer, balanceBefore.subtract(transfer.getAmount()));
            transfer.setStatus(WalletTransferStatus.REVERSED);
            transfer.setFailureReason("Payout initiation failed: " + e.getMessage());
            transferRepo.save(transfer);
            auditService.recordTransferEvent(AuditEventType.TRANSFER_REVERSED,
                    transfer.getTransferId(), WalletTransferStatus.DEBITED.name(),
                    WalletTransferStatus.REVERSED.name(), "Payout failed, debit reversed: " + e.getMessage());
            throw e;
        }
        return transfer;
    }

    private void reverseDebit(WalletTransfer transfer, BigDecimal currentBalance) {
        BigDecimal before = balanceService.credit(
                transfer.getSourceWalletId(), transfer.getSourceWalletType(), transfer.getAmount());
        WalletTransaction reversalTxn = WalletTransaction.builder()
                .transactionId(UUID.randomUUID().toString())
                .transferId(transfer.getTransferId())
                .walletId(transfer.getSourceWalletId())
                .walletType(transfer.getSourceWalletType())
                .transactionType(WalletTransactionType.CREDIT)
                .amount(transfer.getAmount())
                .balanceBefore(before)
                .balanceAfter(before.add(transfer.getAmount()))
                .currency(transfer.getCurrency())
                .description("W2A reversal — payout failed")
                .build();
        txnRepo.save(reversalTxn);
    }

    private CfPayoutTransferRequest buildPayoutRequest(WalletTransfer transfer) {
        return CfPayoutTransferRequest.builder()
                .transferId(transfer.getTransferId())
                .transferAmount(transfer.getNetAmount())
                .transferRemarks(transfer.getDescription())
                .beneficiary(CfPayoutTransferRequest.CfPayoutBeneficiary.builder()
                        .beneficiaryName(transfer.getDestinationAccountName())
                        .bankAccount(CfPayoutTransferRequest.CfPayoutBeneficiary.BankAccount.builder()
                                .accountNumber(transfer.getDestinationAccountNumber())
                                .accountIfsc(transfer.getDestinationIfsc())
                                .build())
                        .build())
                .build();
    }
}

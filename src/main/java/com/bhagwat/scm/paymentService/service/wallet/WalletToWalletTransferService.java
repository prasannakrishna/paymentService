package com.bhagwat.scm.paymentService.service.wallet;

import com.bhagwat.scm.paymentService.common.*;
import com.bhagwat.scm.paymentService.dto.wallet.WalletToWalletTransferRequest;
import com.bhagwat.scm.paymentService.entity.wallet.WalletTransaction;
import com.bhagwat.scm.paymentService.entity.wallet.WalletTransfer;
import com.bhagwat.scm.paymentService.repository.WalletTransactionRepository;
import com.bhagwat.scm.paymentService.repository.WalletTransferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Executes an atomic wallet-to-wallet transfer.
 *
 * Debit and credit happen in a single @Transactional block.
 * If either fails, the entire transfer is rolled back.
 * Optimistic locking retries are handled in WalletBalanceService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletToWalletTransferService {

    private final WalletBalanceService       balanceService;
    private final WalletTransferRepository   transferRepo;
    private final WalletTransactionRepository txnRepo;
    private final WalletAuditService         auditService;

    @Transactional
    public WalletTransfer execute(WalletTransfer transfer, WalletToWalletTransferRequest req) {
        log.info("W2W transfer: transferId={} src={} dst={} amount={}",
                transfer.getTransferId(), transfer.getSourceWalletId(),
                transfer.getDestinationWalletId(), transfer.getAmount());

        try {
            // ── Debit source ───────────────────────────────────────────────────
            BigDecimal srcBefore = balanceService.debit(
                    transfer.getSourceWalletId(),
                    transfer.getSourceWalletType(),
                    transfer.getAmount());

            WalletTransaction debitTxn = WalletTransaction.builder()
                    .transactionId(UUID.randomUUID().toString())
                    .transferId(transfer.getTransferId())
                    .walletId(transfer.getSourceWalletId())
                    .walletType(transfer.getSourceWalletType())
                    .transactionType(WalletTransactionType.DEBIT)
                    .amount(transfer.getAmount())
                    .balanceBefore(srcBefore)
                    .balanceAfter(srcBefore.subtract(transfer.getAmount()))
                    .currency(transfer.getCurrency())
                    .description("W2W debit → " + transfer.getDestinationWalletId())
                    .build();
            txnRepo.save(debitTxn);

            // ── Credit destination ─────────────────────────────────────────────
            BigDecimal dstBefore = balanceService.credit(
                    transfer.getDestinationWalletId(),
                    transfer.getDestinationWalletType(),
                    transfer.getNetAmount());

            WalletTransaction creditTxn = WalletTransaction.builder()
                    .transactionId(UUID.randomUUID().toString())
                    .transferId(transfer.getTransferId())
                    .walletId(transfer.getDestinationWalletId())
                    .walletType(transfer.getDestinationWalletType())
                    .transactionType(WalletTransactionType.CREDIT)
                    .amount(transfer.getNetAmount())
                    .balanceBefore(dstBefore)
                    .balanceAfter(dstBefore.add(transfer.getNetAmount()))
                    .currency(transfer.getCurrency())
                    .description("W2W credit ← " + transfer.getSourceWalletId())
                    .build();
            txnRepo.save(creditTxn);

            // ── Mark transfer complete ─────────────────────────────────────────
            transfer.setStatus(WalletTransferStatus.COMPLETED);
            transfer = transferRepo.save(transfer);

            auditService.recordTransferEvent(AuditEventType.TRANSFER_COMPLETED,
                    transfer.getTransferId(), WalletTransferStatus.DEBITED.name(),
                    WalletTransferStatus.COMPLETED.name(),
                    "W2W completed: " + transfer.getAmount() + " " + transfer.getCurrency());

            log.info("W2W transfer completed: transferId={}", transfer.getTransferId());
            return transfer;

        } catch (Exception e) {
            transfer.setStatus(WalletTransferStatus.FAILED);
            transfer.setFailureReason(e.getMessage());
            transferRepo.save(transfer);
            auditService.recordTransferEvent(AuditEventType.TRANSFER_FAILED,
                    transfer.getTransferId(), WalletTransferStatus.INITIATED.name(),
                    WalletTransferStatus.FAILED.name(), "W2W failed: " + e.getMessage());
            throw e;
        }
    }
}

package com.bhagwat.scm.paymentService.service.wallet;

import com.bhagwat.scm.paymentService.common.*;
import com.bhagwat.scm.paymentService.dto.wallet.*;
import com.bhagwat.scm.paymentService.entity.wallet.WalletTransfer;
import com.bhagwat.scm.paymentService.exception.DuplicateTransferException;
import com.bhagwat.scm.paymentService.repository.WalletTransferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Single entry point for all fund transfer initiations.
 *
 * Responsibilities:
 *   1. Idempotency — return existing transfer if the key already exists
 *   2. Build a WalletTransfer skeleton and persist it (INITIATED)
 *   3. Delegate to the correct strategy service
 *   4. Map result to WalletTransferResponse
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletTransferOrchestrator {

    private final WalletTransferRepository         transferRepo;
    private final WalletToWalletTransferService    w2wService;
    private final WalletToAccountTransferService   w2aService;
    private final AccountToWalletTopupService      a2wService;
    private final AccountToAccountTransferService  a2aService;
    private final WalletAuditService               auditService;

    // ── Wallet → Wallet ───────────────────────────────────────────────────────

    @Transactional
    public WalletTransferResponse walletToWallet(WalletToWalletTransferRequest req) {
        WalletTransfer transfer = resolveIdempotency(req.getIdempotencyKey());
        if (transfer != null) return toResponse(transfer);

        transfer = WalletTransfer.builder()
                .transferId(UUID.randomUUID().toString())
                .idempotencyKey(req.getIdempotencyKey())
                .transferType(TransferType.WALLET_TO_WALLET)
                .sourceWalletId(req.getSourceWalletId())
                .sourceWalletType(req.getSourceWalletType())
                .destinationWalletId(req.getDestinationWalletId())
                .destinationWalletType(req.getDestinationWalletType())
                .amount(req.getAmount())
                .fees(java.math.BigDecimal.ZERO)
                .netAmount(req.getAmount())
                .currency("INR")
                .description(req.getDescription())
                .initiatedBy(req.getInitiatedBy())
                .status(WalletTransferStatus.INITIATED)
                .build();
        transfer = transferRepo.save(transfer);
        auditService.recordTransferEvent(AuditEventType.TRANSFER_INITIATED,
                transfer.getTransferId(), null, WalletTransferStatus.INITIATED.name(),
                "W2W: " + req.getAmount() + " from " + req.getSourceWalletId() + " to " + req.getDestinationWalletId());

        transfer = w2wService.execute(transfer, req);
        return toResponse(transfer);
    }

    // ── Wallet → Account ──────────────────────────────────────────────────────

    @Transactional
    public WalletTransferResponse walletToAccount(WalletToAccountTransferRequest req) {
        WalletTransfer transfer = resolveIdempotency(req.getIdempotencyKey());
        if (transfer != null) return toResponse(transfer);

        transfer = WalletTransfer.builder()
                .transferId(UUID.randomUUID().toString())
                .idempotencyKey(req.getIdempotencyKey())
                .transferType(TransferType.WALLET_TO_ACCOUNT)
                .sourceWalletId(req.getSourceWalletId())
                .sourceWalletType(req.getSourceWalletType())
                .destinationAccountNumber(req.getAccountNumber())
                .destinationIfsc(req.getIfscCode())
                .destinationAccountName(req.getBeneficiaryName())
                .amount(req.getAmount())
                .fees(java.math.BigDecimal.ZERO)
                .netAmount(req.getAmount())
                .currency("INR")
                .description(req.getDescription())
                .initiatedBy(req.getInitiatedBy())
                .status(WalletTransferStatus.INITIATED)
                .build();
        transfer = transferRepo.save(transfer);
        auditService.recordTransferEvent(AuditEventType.TRANSFER_INITIATED,
                transfer.getTransferId(), null, WalletTransferStatus.INITIATED.name(),
                "W2A: " + req.getAmount() + " from wallet " + req.getSourceWalletId() + " to account " + req.getAccountNumber());

        transfer = w2aService.execute(transfer);
        return toResponse(transfer);
    }

    // ── Account → Wallet ──────────────────────────────────────────────────────

    @Transactional
    public WalletTransferResponse accountToWallet(AccountToWalletTopupRequest req) {
        WalletTransfer transfer = resolveIdempotency(req.getIdempotencyKey());
        if (transfer != null) return toResponse(transfer);

        transfer = WalletTransfer.builder()
                .transferId(UUID.randomUUID().toString())
                .idempotencyKey(req.getIdempotencyKey())
                .transferType(TransferType.ACCOUNT_TO_WALLET)
                .sourceAccountName(req.getPayerName())
                .destinationWalletId(req.getDestinationWalletId())
                .destinationWalletType(req.getDestinationWalletType())
                .amount(req.getAmount())
                .fees(java.math.BigDecimal.ZERO)
                .netAmount(req.getAmount())
                .currency("INR")
                .description(req.getDescription())
                .status(WalletTransferStatus.INITIATED)
                .build();
        transfer = transferRepo.save(transfer);
        auditService.recordTransferEvent(AuditEventType.TRANSFER_INITIATED,
                transfer.getTransferId(), null, WalletTransferStatus.INITIATED.name(),
                "A2W: " + req.getAmount() + " to wallet " + req.getDestinationWalletId());

        transfer = a2wService.initiate(transfer, req);
        return toResponse(transfer);
    }

    // ── Account → Account ─────────────────────────────────────────────────────

    @Transactional
    public WalletTransferResponse accountToAccount(AccountToAccountTransferRequest req) {
        WalletTransfer transfer = resolveIdempotency(req.getIdempotencyKey());
        if (transfer != null) return toResponse(transfer);

        transfer = WalletTransfer.builder()
                .transferId(UUID.randomUUID().toString())
                .idempotencyKey(req.getIdempotencyKey())
                .transferType(TransferType.ACCOUNT_TO_ACCOUNT)
                .sourceAccountName(req.getPayerName())
                .destinationAccountNumber(req.getBeneficiaryAccountNumber())
                .destinationIfsc(req.getBeneficiaryIfsc())
                .destinationAccountName(req.getBeneficiaryName())
                .amount(req.getAmount())
                .fees(java.math.BigDecimal.ZERO)
                .netAmount(req.getAmount())
                .currency("INR")
                .description(req.getDescription())
                .status(WalletTransferStatus.INITIATED)
                .build();
        transfer = transferRepo.save(transfer);
        auditService.recordTransferEvent(AuditEventType.TRANSFER_INITIATED,
                transfer.getTransferId(), null, WalletTransferStatus.INITIATED.name(),
                "A2A: " + req.getAmount() + " to account " + req.getBeneficiaryAccountNumber());

        transfer = a2aService.initiate(transfer, req);
        return toResponse(transfer);
    }

    // ── Get status ────────────────────────────────────────────────────────────

    public WalletTransferResponse getTransfer(String transferId) {
        WalletTransfer transfer = transferRepo.findById(transferId)
                .orElseThrow(() -> new IllegalArgumentException("Transfer not found: " + transferId));
        return toResponse(transfer);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private WalletTransfer resolveIdempotency(String key) {
        return transferRepo.findByIdempotencyKey(key)
                .map(existing -> {
                    log.info("Idempotent transfer returned: key={} transferId={} status={}",
                            key, existing.getTransferId(), existing.getStatus());
                    return existing;
                })
                .orElse(null);
    }

    public WalletTransferResponse toResponse(WalletTransfer t) {
        return WalletTransferResponse.builder()
                .transferId(t.getTransferId())
                .transferType(t.getTransferType())
                .status(t.getStatus())
                .amount(t.getAmount())
                .fees(t.getFees())
                .netAmount(t.getNetAmount())
                .currency(t.getCurrency())
                .sourceWalletId(t.getSourceWalletId())
                .sourceAccountNumber(t.getSourceAccountNumber())
                .destinationWalletId(t.getDestinationWalletId())
                .destinationAccountNumber(t.getDestinationAccountNumber())
                .pgOrderId(t.getPgOrderId())
                .paymentLink(t.getPaymentLink())
                .paymentSessionId(t.getPaymentSessionId())
                .cfPayoutId(t.getCfPayoutId())
                .bankReference(t.getBankReference())
                .failureReason(t.getFailureReason())
                .description(t.getDescription())
                .initiatedAt(t.getInitiatedAt())
                .updatedAt(t.getUpdatedAt())
                .message(describeStatus(t.getStatus()))
                .build();
    }

    private String describeStatus(WalletTransferStatus status) {
        return switch (status) {
            case INITIATED        -> "Transfer initiated";
            case PENDING_PAYMENT  -> "Awaiting payment — redirect user to paymentLink";
            case COLLECTING       -> "Payment received, processing payout";
            case DEBITED          -> "Source wallet debited, payout pending";
            case PAYOUT_INITIATED -> "Payout initiated, awaiting bank settlement";
            case COMPLETED        -> "Transfer completed successfully";
            case FAILED           -> "Transfer failed";
            case REVERSED         -> "Transfer reversed — funds returned to source";
        };
    }
}

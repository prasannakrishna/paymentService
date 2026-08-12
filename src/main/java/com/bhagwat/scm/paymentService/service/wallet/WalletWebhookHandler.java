package com.bhagwat.scm.paymentService.service.wallet;

import com.bhagwat.scm.paymentService.common.AuditEventType;
import com.bhagwat.scm.paymentService.common.TransferType;
import com.bhagwat.scm.paymentService.common.WalletTransferStatus;
import com.bhagwat.scm.paymentService.dto.cashfree.CfWebhookPayload;
import com.bhagwat.scm.paymentService.dto.cashfree.payout.CfPayoutWebhookPayload;
import com.bhagwat.scm.paymentService.entity.wallet.WalletTransfer;
import com.bhagwat.scm.paymentService.exception.PaymentGatewayException;
import com.bhagwat.scm.paymentService.repository.WalletTransferRepository;
import com.bhagwat.scm.paymentService.service.WebhookSignatureVerifier;
import com.bhagwat.scm.paymentService.streaming.producer.PaymentEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Routes and processes Cashfree webhooks for wallet transfer flows.
 *
 * Two types of webhooks are handled:
 *
 *   1. PG Webhooks (payment collection events):
 *      - PAYMENT_SUCCESS_WEBHOOK → credit wallet (A2W) or trigger payout (A2A)
 *      - PAYMENT_FAILED_WEBHOOK  → mark FAILED
 *      - PAYMENT_USER_DROPPED_WEBHOOK → mark FAILED
 *
 *   2. Payout Webhooks (disbursement settlement events):
 *      - TRANSFER_SUCCESS  → mark COMPLETED, store bankReference
 *      - TRANSFER_FAILED   → reverse debit (W2A) or mark FAILED (A2A)
 *      - TRANSFER_REVERSED → mark REVERSED
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletWebhookHandler {

    private final WalletTransferRepository        transferRepo;
    private final WalletBalanceService            balanceService;
    private final AccountToWalletTopupService     a2wService;
    private final AccountToAccountTransferService a2aService;
    private final WalletToAccountTransferService  w2aService;
    private final WalletAuditService              auditService;
    private final WebhookSignatureVerifier        signatureVerifier;
    private final ObjectMapper                    objectMapper;
    private final PaymentEventPublisher           paymentEventPublisher;

    // ── PG Webhook ────────────────────────────────────────────────────────────

    /**
     * Handles a Cashfree PG payment webhook.
     * Called for both wallet transfer flows (A2W, A2A) AND standalone gateway payments.
     *
     * Returns true if this was a wallet-transfer webhook (caller can skip standalone processing).
     * Returns false if the orderId does not match any WalletTransfer (caller handles as standalone).
     */
    @Transactional
    public boolean handlePgWebhook(String rawBody, String signature, String timestamp) {
        if (!signatureVerifier.verify(timestamp, rawBody, signature)) {
            auditService.recordAsync(AuditEventType.WEBHOOK_SIGNATURE_FAILED, null,
                    "PG webhook signature verification failed");
            throw new PaymentGatewayException("Invalid webhook signature", HttpStatus.UNAUTHORIZED);
        }

        CfWebhookPayload payload = parsePgWebhook(rawBody);
        String orderId    = payload.getData().getOrder().getOrderId();
        String eventType  = payload.getType();

        // Check if this orderId belongs to a WalletTransfer
        Optional<WalletTransfer> opt = transferRepo.findByIdempotencyKey(orderId);
        if (opt.isEmpty()) {
            // Not a wallet transfer — let the caller handle it as standalone PG payment
            return false;
        }

        WalletTransfer transfer = opt.get();

        if (isTerminal(transfer.getStatus())) {
            log.info("Duplicate PG webhook ignored: transferId={} status={}", transfer.getTransferId(), transfer.getStatus());
            return true;
        }

        auditService.recordAsync(AuditEventType.WEBHOOK_RECEIVED, transfer.getTransferId(),
                "PG webhook: event=" + eventType + " orderId=" + orderId);

        String cfPaymentId = payload.getData().getPayment().getCfPaymentId();

        switch (eventType) {
            case "PAYMENT_SUCCESS_WEBHOOK" -> {
                if (transfer.getTransferType() == TransferType.ACCOUNT_TO_WALLET) {
                    a2wService.onPaymentSuccess(transfer, cfPaymentId, balanceService);
                    // A2W completes here — wallet credited; publish success event
                    paymentEventPublisher.publishWalletPaymentSuccess(
                            transferRepo.findById(transfer.getTransferId()).orElse(transfer));
                } else if (transfer.getTransferType() == TransferType.ACCOUNT_TO_ACCOUNT) {
                    a2aService.onPaymentCollected(transfer, cfPaymentId);
                    // A2A: collection done, payout triggered — final success on TRANSFER_SUCCESS webhook
                }
            }
            case "PAYMENT_FAILED_WEBHOOK", "PAYMENT_USER_DROPPED_WEBHOOK" -> {
                transfer.setStatus(WalletTransferStatus.FAILED);
                CfWebhookPayload.CfWebhookPayment pmt = payload.getData().getPayment();
                transfer.setFailureReason(pmt.getPaymentMessage());
                transferRepo.save(transfer);
                auditService.recordTransferEvent(AuditEventType.TRANSFER_FAILED,
                        transfer.getTransferId(), WalletTransferStatus.PENDING_PAYMENT.name(),
                        WalletTransferStatus.FAILED.name(), "PG payment failed: " + pmt.getPaymentMessage());
            }
            default -> log.warn("Unhandled PG webhook event for wallet transfer: {} transferId={}",
                    eventType, transfer.getTransferId());
        }
        return true;
    }

    // ── Payout Webhook ────────────────────────────────────────────────────────

    @Transactional
    public void handlePayoutWebhook(String rawBody, String signature, String timestamp) {
        if (!signatureVerifier.verify(timestamp, rawBody, signature)) {
            auditService.recordAsync(AuditEventType.WEBHOOK_SIGNATURE_FAILED, null,
                    "Payout webhook signature verification failed");
            throw new PaymentGatewayException("Invalid payout webhook signature", HttpStatus.UNAUTHORIZED);
        }

        CfPayoutWebhookPayload payload = parsePayoutWebhook(rawBody);
        String cfTransferId  = payload.getData().getTransfer().getCfTransferId();
        String ourTransferId = payload.getData().getTransfer().getTransferId();
        String eventType     = payload.getType();

        // ourTransferId for A2A payouts has "PAYOUT-" prefix — strip it
        String lookupKey = ourTransferId.startsWith("PAYOUT-")
                ? ourTransferId.substring(7)
                : ourTransferId;

        WalletTransfer transfer = transferRepo.findById(lookupKey)
                .or(() -> transferRepo.findByCfPayoutId(cfTransferId))
                .orElse(null);

        if (transfer == null) {
            log.error("No WalletTransfer found for payout webhook: cfTransferId={}", cfTransferId);
            return; // Return 200 to Cashfree to stop retries
        }

        if (isTerminal(transfer.getStatus())) {
            log.info("Duplicate payout webhook ignored: transferId={} status={}",
                    transfer.getTransferId(), transfer.getStatus());
            return;
        }

        auditService.recordAsync(AuditEventType.WEBHOOK_RECEIVED, transfer.getTransferId(),
                "Payout webhook: event=" + eventType + " cfTransferId=" + cfTransferId);

        CfPayoutWebhookPayload.CfPayoutTransferEvent evt = payload.getData().getTransfer();

        switch (eventType) {
            case "TRANSFER_SUCCESS" -> {
                transfer.setStatus(WalletTransferStatus.COMPLETED);
                transfer.setBankReference(evt.getBankReference());
                transferRepo.save(transfer);
                auditService.recordTransferEvent(AuditEventType.TRANSFER_COMPLETED,
                        transfer.getTransferId(), WalletTransferStatus.PAYOUT_INITIATED.name(),
                        WalletTransferStatus.COMPLETED.name(),
                        "Payout settled: utr=" + evt.getBankReference());
                log.info("Transfer COMPLETED: transferId={} utr={}", transfer.getTransferId(), evt.getBankReference());
                // Publish to Kafka Streams pipeline — routes to customer or org table
                paymentEventPublisher.publishWalletPaymentSuccess(transfer);
            }
            case "TRANSFER_FAILED" -> {
                if (transfer.getTransferType() == TransferType.WALLET_TO_ACCOUNT) {
                    // Reverse the wallet debit
                    reverseWalletDebit(transfer, "Payout failed: " + evt.getFailureReason());
                } else {
                    transfer.setStatus(WalletTransferStatus.FAILED);
                    transfer.setFailureReason("Payout failed: " + evt.getFailureReason());
                    transferRepo.save(transfer);
                    auditService.recordTransferEvent(AuditEventType.TRANSFER_FAILED,
                            transfer.getTransferId(), WalletTransferStatus.PAYOUT_INITIATED.name(),
                            WalletTransferStatus.FAILED.name(), evt.getFailureReason());
                }
            }
            case "TRANSFER_REVERSED" -> {
                transfer.setStatus(WalletTransferStatus.REVERSED);
                transfer.setFailureReason("Payout reversed by bank");
                transferRepo.save(transfer);
                auditService.recordTransferEvent(AuditEventType.TRANSFER_REVERSED,
                        transfer.getTransferId(), WalletTransferStatus.PAYOUT_INITIATED.name(),
                        WalletTransferStatus.REVERSED.name(), "Payout reversed");
            }
            default -> log.warn("Unhandled payout webhook event: {} transferId={}", eventType, transfer.getTransferId());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void reverseWalletDebit(WalletTransfer transfer, String reason) {
        try {
            balanceService.credit(transfer.getSourceWalletId(),
                    transfer.getSourceWalletType(), transfer.getAmount());
            transfer.setStatus(WalletTransferStatus.REVERSED);
            transfer.setFailureReason(reason);
            transferRepo.save(transfer);
            auditService.recordTransferEvent(AuditEventType.TRANSFER_REVERSED,
                    transfer.getTransferId(), WalletTransferStatus.PAYOUT_INITIATED.name(),
                    WalletTransferStatus.REVERSED.name(),
                    "Debit reversed — payout failed: " + reason);
            log.info("W2A debit reversed: transferId={}", transfer.getTransferId());
        } catch (Exception e) {
            log.error("CRITICAL: Failed to reverse debit for transferId={}. Manual intervention required.",
                    transfer.getTransferId(), e);
        }
    }

    private boolean isTerminal(WalletTransferStatus status) {
        return status == WalletTransferStatus.COMPLETED
                || status == WalletTransferStatus.FAILED
                || status == WalletTransferStatus.REVERSED;
    }

    private CfWebhookPayload parsePgWebhook(String raw) {
        try { return objectMapper.readValue(raw, CfWebhookPayload.class); }
        catch (Exception e) { throw new PaymentGatewayException("Malformed PG webhook", HttpStatus.BAD_REQUEST, e); }
    }

    private CfPayoutWebhookPayload parsePayoutWebhook(String raw) {
        try { return objectMapper.readValue(raw, CfPayoutWebhookPayload.class); }
        catch (Exception e) { throw new PaymentGatewayException("Malformed payout webhook", HttpStatus.BAD_REQUEST, e); }
    }
}

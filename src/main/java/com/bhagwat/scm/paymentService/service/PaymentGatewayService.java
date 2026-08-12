package com.bhagwat.scm.paymentService.service;

import com.bhagwat.scm.paymentService.common.TransactionStatus;
import com.bhagwat.scm.paymentService.config.CashfreeProperties;
import com.bhagwat.scm.paymentService.dto.*;
import com.bhagwat.scm.paymentService.dto.cashfree.*;
import com.bhagwat.scm.paymentService.entity.PaymentTransaction;
import com.bhagwat.scm.paymentService.exception.DuplicatePaymentException;
import com.bhagwat.scm.paymentService.exception.PaymentGatewayException;
import com.bhagwat.scm.paymentService.repository.PaymentTransactionRepository;
import com.bhagwat.scm.paymentService.rest.CashfreeGatewayClient;
import com.bhagwat.scm.paymentService.streaming.producer.PaymentEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the complete payment lifecycle with the Cashfree PG gateway.
 *
 * Flow:
 *   1. Validate request + idempotency check
 *   2. Persist PaymentTransaction (status=INITIATED)
 *   3. Call Cashfree to create order
 *   4. Update transaction (status=PENDING_PAYMENT, store cfOrderId, paymentLink)
 *   5. Return payment link to client
 *   6. On webhook: verify signature, update status, handle idempotency
 *   7. Status endpoint: merge DB record with live Cashfree order status
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentGatewayService {

    private final CashfreeGatewayClient cashfreeClient;
    private final PaymentTransactionRepository transactionRepo;
    private final CashfreeProperties cashfreeProps;
    private final WebhookSignatureVerifier signatureVerifier;
    private final PaymentEventPublisher paymentEventPublisher;

    // ── Initiate Payment ──────────────────────────────────────────────────────

    /**
     * Initiates a payment by creating a Cashfree order and returning a hosted checkout link.
     *
     * Idempotency: if a transaction for the same {@code idempotencyKey} (defaults to orderId)
     * already exists in PENDING_PAYMENT, PROCESSING, or SUCCESS state, the existing
     * transaction details are returned without creating a new Cashfree order.
     */
    @Transactional
    public InitiatePaymentResponse initiatePayment(InitiatePaymentRequest request) {
        String idempotencyKey = resolveIdempotencyKey(request);

        // ── Idempotency check ──────────────────────────────────────────────────
        return transactionRepo.findByIdempotencyKey(idempotencyKey)
                .map(existing -> {
                    if (isTerminalOrActive(existing.getStatus())) {
                        log.info("Idempotent return for existing transaction: transactionId={} status={}",
                                existing.getTransactionId(), existing.getStatus());
                        return buildInitiateResponse(existing, "Existing payment returned (idempotent)");
                    }
                    // Was INITIATED but Cashfree call failed previously — retry below
                    return null;
                })
                .orElseGet(() -> createNewPayment(request, idempotencyKey));
    }

    private InitiatePaymentResponse createNewPayment(InitiatePaymentRequest request, String idempotencyKey) {
        // ── Persist INITIATED record ───────────────────────────────────────────
        PaymentTransaction txn = buildInitialTransaction(request, idempotencyKey);
        txn = transactionRepo.save(txn);
        log.info("Payment transaction created: transactionId={} orderId={} amount={}",
                txn.getTransactionId(), txn.getOrderId(), txn.getAmount());

        // ── Create Cashfree order ──────────────────────────────────────────────
        CfCreateOrderRequest cfRequest = buildCfOrderRequest(request, txn.getTransactionId());
        CfCreateOrderResponse cfResponse;
        try {
            cfResponse = cashfreeClient.createOrder(cfRequest);
        } catch (PaymentGatewayException e) {
            // Mark as FAILED and re-throw so the client gets an error
            txn.setStatus(TransactionStatus.FAILED);
            txn.setFailureReason("Gateway error during order creation: " + e.getMessage());
            transactionRepo.save(txn);
            throw e;
        }

        // ── Update transaction with Cashfree details ───────────────────────────
        txn.setStatus(TransactionStatus.PENDING_PAYMENT);
        txn.setCfOrderId(cfResponse.getCfOrderId());
        txn.setPaymentSessionId(cfResponse.getPaymentSessionId());
        txn.setPaymentLink(cfResponse.getHostedCheckoutUrl());
        txn.setOrderExpiryTime(cfResponse.getOrderExpiryTime());
        txn.setGatewayOrderStatus(cfResponse.getOrderStatus());
        txn = transactionRepo.save(txn);

        log.info("Cashfree order created and transaction updated: transactionId={} cfOrderId={} status={}",
                txn.getTransactionId(), txn.getCfOrderId(), txn.getStatus());

        return buildInitiateResponse(txn, "Payment initiated successfully");
    }

    // ── Get Status ────────────────────────────────────────────────────────────

    /**
     * Returns the current status of a payment transaction.
     * Merges locally stored data with a live Cashfree order status poll.
     */
    @Transactional(readOnly = true)
    public PaymentStatusResponse getStatus(String transactionId) {
        PaymentTransaction txn = transactionRepo.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));

        // For terminal states, return stored data without hitting Cashfree
        if (isTerminalState(txn.getStatus())) {
            return buildStatusResponse(txn, null);
        }

        // For active states, check Cashfree for live status
        CfOrderStatusResponse liveStatus = null;
        try {
            if (txn.getCfOrderId() != null) {
                liveStatus = cashfreeClient.getOrderStatus(txn.getOrderId());
            }
        } catch (PaymentGatewayException e) {
            log.warn("Could not fetch live Cashfree status for transactionId={}: {}",
                    transactionId, e.getMessage());
        }

        return buildStatusResponse(txn, liveStatus);
    }

    // ── Process Webhook ───────────────────────────────────────────────────────

    /**
     * Processes a Cashfree payment webhook.
     *
     * Steps:
     *   1. Verify HMAC-SHA256 signature
     *   2. Parse payload
     *   3. Look up transaction by orderId
     *   4. Idempotency: skip if already in terminal state from a previous webhook
     *   5. Update status based on event type
     */
    @Transactional
    public void processWebhook(String rawBody, String signature, String timestamp) {
        // ── Signature verification ─────────────────────────────────────────────
        if (!signatureVerifier.verify(timestamp, rawBody, signature)) {
            log.error("Webhook signature verification FAILED. timestamp={}", timestamp);
            throw new PaymentGatewayException(
                    "Invalid webhook signature", HttpStatus.UNAUTHORIZED);
        }

        CfWebhookPayload payload;
        try {
            payload = parseWebhookPayload(rawBody);
        } catch (Exception e) {
            log.error("Failed to parse webhook payload: {}", e.getMessage());
            throw new PaymentGatewayException(
                    "Malformed webhook payload", HttpStatus.BAD_REQUEST);
        }

        String cfOrderId    = payload.getData().getOrder().getOrderId();   // actually our orderId
        String eventType    = payload.getType();
        String paymentStatus = payload.getData().getPayment().getPaymentStatus();

        log.info("Webhook received: event={} orderId={} paymentStatus={}",
                eventType, cfOrderId, paymentStatus);

        PaymentTransaction txn = transactionRepo.findByOrderId(cfOrderId)
                .orElseGet(() -> transactionRepo.findByCfOrderId(cfOrderId).orElse(null));

        if (txn == null) {
            log.error("No transaction found for webhook orderId={}", cfOrderId);
            // Return 200 to Cashfree to prevent retries for truly unknown orders
            return;
        }

        // Idempotency: don't overwrite a terminal state with a duplicate webhook
        if (isTerminalState(txn.getStatus())) {
            log.info("Ignoring duplicate webhook for terminal transaction: transactionId={} status={}",
                    txn.getTransactionId(), txn.getStatus());
            return;
        }

        // ── Apply status transition ────────────────────────────────────────────
        applyWebhookStatus(txn, payload);
        transactionRepo.save(txn);

        log.info("Transaction updated from webhook: transactionId={} newStatus={} paymentStatus={}",
                txn.getTransactionId(), txn.getStatus(), paymentStatus);
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    private PaymentTransaction buildInitialTransaction(InitiatePaymentRequest req, String idempotencyKey) {
        return PaymentTransaction.builder()
                .transactionId(UUID.randomUUID().toString())
                .orderId(req.getOrderId())
                .idempotencyKey(idempotencyKey)
                .status(TransactionStatus.INITIATED)
                .amount(req.getAmount())
                .currency(req.getCurrency())
                .paymentMethod(req.getPreferredPaymentMethod())
                // Debitor
                .debitorId(req.getDebitor().getDebitorId())
                .debitorName(req.getDebitor().getName())
                .debitorEmail(req.getDebitor().getEmail())
                .debitorPhone(req.getDebitor().getPhone())
                // Creditor
                .creditorId(req.getCreditor().getCreditorId())
                .creditorName(req.getCreditor().getName())
                .creditorAccount(req.getCreditor().getAccountNumber())
                .creditorIfsc(req.getCreditor().getIfscCode())
                .build();
    }

    private CfCreateOrderRequest buildCfOrderRequest(InitiatePaymentRequest req, String transactionId) {
        String expiryTime = ZonedDateTime.now()
                .plusMinutes(cashfreeProps.getOrderExpiryMinutes())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        return CfCreateOrderRequest.builder()
                .orderId(req.getOrderId())
                .orderAmount(req.getAmount())
                .orderCurrency(req.getCurrency())
                .orderNote(req.getDescription())
                .orderExpiryTime(expiryTime)
                .customerDetails(CfCustomerDetails.builder()
                        .customerId(req.getDebitor().getDebitorId())
                        .customerName(req.getDebitor().getName())
                        .customerEmail(req.getDebitor().getEmail())
                        .customerPhone(req.getDebitor().getPhone())
                        .build())
                .orderMeta(CfOrderMeta.builder()
                        .returnUrl(cashfreeProps.getReturnUrl() + "?order_id={order_id}&txn_id=" + transactionId)
                        .notifyUrl(cashfreeProps.getWebhookUrl())
                        .build())
                .build();
    }

    private InitiatePaymentResponse buildInitiateResponse(PaymentTransaction txn, String message) {
        return InitiatePaymentResponse.builder()
                .transactionId(txn.getTransactionId())
                .orderId(txn.getOrderId())
                .cfOrderId(txn.getCfOrderId())
                .status(txn.getStatus())
                .amount(txn.getAmount())
                .currency(txn.getCurrency())
                .paymentLink(txn.getPaymentLink())
                .paymentSessionId(txn.getPaymentSessionId())
                .expiresAt(txn.getOrderExpiryTime())
                .initiatedAt(txn.getInitiatedAt())
                .message(message)
                .build();
    }

    private PaymentStatusResponse buildStatusResponse(PaymentTransaction txn, CfOrderStatusResponse live) {
        String gatewayPaymentStatus = null;
        if (live != null && live.getPayments() != null && !live.getPayments().isEmpty()) {
            gatewayPaymentStatus = live.getPayments().get(0).getPaymentStatus();
        }

        return PaymentStatusResponse.builder()
                .transactionId(txn.getTransactionId())
                .orderId(txn.getOrderId())
                .cfOrderId(txn.getCfOrderId())
                .status(txn.getStatus())
                .gatewayOrderStatus(live != null ? live.getOrderStatus() : txn.getGatewayOrderStatus())
                .gatewayPaymentStatus(gatewayPaymentStatus != null ? gatewayPaymentStatus : txn.getGatewayPaymentStatus())
                .amount(txn.getAmount())
                .currency(txn.getCurrency())
                .debitorId(txn.getDebitorId())
                .creditorId(txn.getCreditorId())
                .bankReference(txn.getBankReference())
                .failureReason(txn.getFailureReason())
                .initiatedAt(txn.getInitiatedAt())
                .updatedAt(txn.getUpdatedAt())
                .message(describeStatus(txn.getStatus()))
                .build();
    }

    // ── Webhook helpers ───────────────────────────────────────────────────────

    private void applyWebhookStatus(PaymentTransaction txn, CfWebhookPayload payload) {
        String eventType     = payload.getType();
        CfWebhookPayload.CfWebhookPayment payment = payload.getData().getPayment();

        txn.setWebhookReceived(true);
        txn.setWebhookEventType(eventType);
        txn.setCfPaymentId(payment.getCfPaymentId());
        txn.setGatewayPaymentStatus(payment.getPaymentStatus());

        switch (eventType) {
            case "PAYMENT_SUCCESS_WEBHOOK" -> {
                txn.setStatus(TransactionStatus.SUCCESS);
                txn.setBankReference(payment.getBankReference());
                // Publish asynchronously after transaction commits
                paymentEventPublisher.publishStandalonePaymentSuccess(txn);
            }
            case "PAYMENT_FAILED_WEBHOOK" -> {
                txn.setStatus(TransactionStatus.FAILED);
                CfPaymentDetail.CfErrorDetails err = payload.getData().getErrorDetails();
                txn.setFailureReason(err != null
                        ? err.getErrorDescription() + " [" + err.getErrorCode() + "]"
                        : payment.getPaymentMessage());
            }
            case "PAYMENT_USER_DROPPED_WEBHOOK" -> {
                txn.setStatus(TransactionStatus.CANCELLED);
                txn.setFailureReason("User dropped payment on checkout page");
            }
            default -> {
                log.warn("Unhandled Cashfree webhook event type: {} for transactionId={}",
                        eventType, txn.getTransactionId());
                txn.setStatus(TransactionStatus.PROCESSING);
            }
        }
    }

    private CfWebhookPayload parseWebhookPayload(String rawBody) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(rawBody, CfWebhookPayload.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse webhook JSON", e);
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private String resolveIdempotencyKey(InitiatePaymentRequest request) {
        return request.getIdempotencyKey() != null
                ? request.getIdempotencyKey()
                : request.getOrderId();
    }

    private boolean isTerminalOrActive(TransactionStatus status) {
        return status == TransactionStatus.PENDING_PAYMENT
                || status == TransactionStatus.PROCESSING
                || isTerminalState(status);
    }

    private boolean isTerminalState(TransactionStatus status) {
        return status == TransactionStatus.SUCCESS
                || status == TransactionStatus.FAILED
                || status == TransactionStatus.EXPIRED
                || status == TransactionStatus.CANCELLED
                || status == TransactionStatus.REFUNDED;
    }

    private String describeStatus(TransactionStatus status) {
        return switch (status) {
            case INITIATED        -> "Payment initiated";
            case PENDING_PAYMENT  -> "Awaiting user payment";
            case PROCESSING       -> "Payment processing";
            case SUCCESS          -> "Payment successful";
            case FAILED           -> "Payment failed";
            case EXPIRED          -> "Payment order expired";
            case CANCELLED        -> "Payment cancelled by user";
            case REFUNDED         -> "Payment refunded";
            default               -> status.name();
        };
    }
}

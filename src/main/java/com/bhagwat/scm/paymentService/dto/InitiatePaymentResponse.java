package com.bhagwat.scm.paymentService.dto;

import com.bhagwat.scm.paymentService.common.TransactionStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response returned after initiating a payment via Cashfree.
 *
 * The client should redirect the user to {@code paymentLink}.
 * After payment, Cashfree redirects back to your {@code return_url}
 * and also sends a webhook to your {@code notify_url}.
 *
 * Poll {@code GET /api/v1/payment/gateway/status/{transactionId}} to check status.
 */
@Data
@Builder
public class InitiatePaymentResponse {

    /** Internal transaction ID — use this for status polling. */
    private String transactionId;

    /** Your order ID echoed back. */
    private String orderId;

    /** Cashfree's internal order ID. */
    private String cfOrderId;

    /** Current status (will be PENDING_PAYMENT immediately after initiation). */
    private TransactionStatus status;

    /** Amount to be charged. */
    private BigDecimal amount;

    private String currency;

    /**
     * Hosted checkout URL — redirect the user here to complete payment.
     * Format: https://payments.cashfree.com/order/#<payment_session_id>
     */
    private String paymentLink;

    /** Payment session ID (for Cashfree JS SDK integration). */
    private String paymentSessionId;

    /** When this payment link / order expires. */
    private String expiresAt;

    /** Timestamp when the payment was initiated. */
    private LocalDateTime initiatedAt;

    /** Human-readable message. */
    private String message;
}

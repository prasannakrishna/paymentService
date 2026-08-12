package com.bhagwat.scm.paymentService.exception;

/**
 * Thrown when a payment initiation is attempted for an order that already has
 * an active (PENDING_PAYMENT or PROCESSING) or completed (SUCCESS) transaction.
 */
public class DuplicatePaymentException extends RuntimeException {

    private final String transactionId;

    public DuplicatePaymentException(String orderId, String transactionId) {
        super("Payment already initiated for orderId=" + orderId + " (transactionId=" + transactionId + ")");
        this.transactionId = transactionId;
    }

    public String getTransactionId() { return transactionId; }
}

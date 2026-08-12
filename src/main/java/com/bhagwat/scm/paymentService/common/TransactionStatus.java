package com.bhagwat.scm.paymentService.common;

/**
 * Lifecycle status for a PaymentTransaction record.
 *
 * Gateway flow:
 *   INITIATED → PENDING_PAYMENT → PROCESSING → SUCCESS | FAILED | EXPIRED | CANCELLED
 *
 * Wallet flow (existing):
 *   INITIATED → IN_PROGRESS → COMPLETED | ERROR
 */
public enum TransactionStatus {
    // ── Gateway flow ─────────────────────────────────────────────────────────
    INITIATED,
    PENDING_PAYMENT,
    PROCESSING,
    SUCCESS,
    FAILED,
    EXPIRED,
    CANCELLED,
    REFUNDED,
    // ── Legacy wallet flow ────────────────────────────────────────────────────
    IN_PROGRESS,
    ERROR,
    COMPLETED
}

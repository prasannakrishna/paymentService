package com.bhagwat.scm.paymentService.common;

/** Business event types recorded in the immutable {@code WalletAuditLog}. */
public enum AuditEventType {
    // ── Wallet lifecycle ──────────────────────────────────────────────────────
    WALLET_CREATED,
    WALLET_FROZEN,
    WALLET_UNFROZEN,
    WALLET_CLOSED,

    // ── Balance movements ─────────────────────────────────────────────────────
    BALANCE_CREDITED,
    BALANCE_DEBITED,
    BALANCE_RESERVED,        // amount moved from available → on-hold (future use)
    BALANCE_RELEASED,        // on-hold amount released back to available

    // ── Transfer lifecycle ────────────────────────────────────────────────────
    TRANSFER_INITIATED,
    TRANSFER_PAYMENT_LINK_CREATED,
    TRANSFER_COLLECTION_COMPLETED,
    TRANSFER_PAYOUT_INITIATED,
    TRANSFER_COMPLETED,
    TRANSFER_FAILED,
    TRANSFER_REVERSED,

    // ── Webhook events ────────────────────────────────────────────────────────
    WEBHOOK_RECEIVED,
    WEBHOOK_SIGNATURE_FAILED
}

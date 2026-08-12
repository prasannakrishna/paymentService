package com.bhagwat.scm.paymentService.common;

/**
 * Complete lifecycle for a {@code WalletTransfer}.
 *
 * State machine:
 *
 * W2W:
 *   INITIATED → COMPLETED | FAILED
 *
 * W2A:
 *   INITIATED → DEBITED → PAYOUT_INITIATED → COMPLETED | FAILED | REVERSED
 *
 * A2W:
 *   INITIATED → PENDING_PAYMENT → COLLECTING → COMPLETED | FAILED
 *
 * A2A:
 *   INITIATED → PENDING_PAYMENT → COLLECTING → PAYOUT_INITIATED → COMPLETED | FAILED | REVERSED
 */
public enum WalletTransferStatus {
    /** DB record created; no action taken yet. */
    INITIATED,
    /** Cashfree PG order created; user has not yet paid (A2W, A2A). */
    PENDING_PAYMENT,
    /** User submitted payment; awaiting Cashfree confirmation (A2W, A2A). */
    COLLECTING,
    /** Source wallet debited; awaiting payout dispatch (W2A, A2A). */
    DEBITED,
    /** Cashfree payout initiated; awaiting bank settlement (W2A, A2A). */
    PAYOUT_INITIATED,
    /** Both legs complete — funds fully transferred. */
    COMPLETED,
    /** Transfer failed at any stage; no funds moved (or reversed). */
    FAILED,
    /**
     * Debit reversed after payout failure — source wallet re-credited.
     * Used when the payout leg of W2A or A2A fails after the wallet was debited.
     */
    REVERSED
}

package com.bhagwat.scm.paymentService.common;

public enum WalletStatus {
    /** Normal operating state — debits and credits allowed. */
    ACTIVE,
    /** All transactions blocked pending review (e.g. fraud hold). */
    FROZEN,
    /** Permanently closed — no new transactions accepted. */
    CLOSED
}

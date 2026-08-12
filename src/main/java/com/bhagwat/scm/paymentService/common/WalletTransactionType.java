package com.bhagwat.scm.paymentService.common;

/** Direction of a single ledger entry on a wallet. */
public enum WalletTransactionType {
    /** Funds added to the wallet balance. */
    CREDIT,
    /** Funds removed from the wallet balance. */
    DEBIT
}

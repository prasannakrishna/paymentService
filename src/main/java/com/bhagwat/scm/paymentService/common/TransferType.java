package com.bhagwat.scm.paymentService.common;

/**
 * The four fund movement patterns the system supports.
 * Each type maps to a dedicated service that handles its specific lifecycle.
 */
public enum TransferType {
    /** Debit one wallet, credit another — fully internal, no gateway. */
    WALLET_TO_WALLET,
    /** Debit a wallet, credit a bank account via Cashfree Payout. */
    WALLET_TO_ACCOUNT,
    /** Collect from a bank account via Cashfree PG, credit a wallet on success. */
    ACCOUNT_TO_WALLET,
    /** Collect from a bank account via Cashfree PG, pay out to another bank account via Cashfree Payout. */
    ACCOUNT_TO_ACCOUNT
}

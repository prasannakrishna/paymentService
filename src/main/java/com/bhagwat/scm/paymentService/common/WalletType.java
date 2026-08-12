package com.bhagwat.scm.paymentService.common;

/** Discriminator for polymorphic wallet references across the system. */
public enum WalletType {
    /** Wallet owned by an organisation or one of its divisions. */
    ENTERPRISE,
    /** Wallet owned by an individual customer (Paytm / Amazon Pay style). */
    INDIVIDUAL
}

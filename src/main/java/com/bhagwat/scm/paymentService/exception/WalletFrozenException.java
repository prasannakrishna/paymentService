package com.bhagwat.scm.paymentService.exception;

public class WalletFrozenException extends RuntimeException {
    public WalletFrozenException(String walletId) {
        super("Wallet is frozen and cannot process transactions: " + walletId);
    }
}

package com.bhagwat.scm.paymentService.exception;

public class WalletNotFoundException extends RuntimeException {
    public WalletNotFoundException(String walletId) {
        super("Wallet not found: " + walletId);
    }
    public WalletNotFoundException(String orgId, String divisionId) {
        super("Enterprise wallet not found for orgId=" + orgId + " divisionId=" + divisionId);
    }
}

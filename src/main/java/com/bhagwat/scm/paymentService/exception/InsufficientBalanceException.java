package com.bhagwat.scm.paymentService.exception;

import java.math.BigDecimal;

public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException(String walletId, BigDecimal available, BigDecimal requested) {
        super(String.format("Insufficient balance in wallet %s: available=%.2f, requested=%.2f",
                walletId, available, requested));
    }
}

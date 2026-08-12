package com.bhagwat.scm.paymentService.dto;

import lombok.Data;

@Data
public class CardDetails {
    private String cardNumber;
    private String cardHolderName;
    private String expiryDate;
    private String cvv;
}

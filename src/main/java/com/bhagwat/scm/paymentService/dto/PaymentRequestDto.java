package com.bhagwat.scm.paymentService.dto;

import com.bhagwat.scm.paymentService.common.PaymentMethod;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PaymentRequestDto {
    private String sourceAddress;
    private String targetAddress;
    private double amount;
    private PaymentMethod paymentMethod;
    private UpiDetails upiDetails;
    private CardDetails cardDetails;
    private AccountDetails accountDetails;
}

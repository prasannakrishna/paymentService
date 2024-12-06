package com.bhagwat.scm.paymentService.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MakePaymentRequest {
    private Long customerId;
    private Long orderId;
    private Double orderValue;
    private Long sellerId;
}


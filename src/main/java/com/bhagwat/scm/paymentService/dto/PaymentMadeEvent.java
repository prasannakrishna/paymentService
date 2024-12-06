package com.bhagwat.scm.paymentService.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor(force = true)
public class PaymentMadeEvent {
    private final Long customerId;
    private final Long sellerId;
    private final Double orderValue;
    private final Long orderId;

    // Constructor, Getters
}


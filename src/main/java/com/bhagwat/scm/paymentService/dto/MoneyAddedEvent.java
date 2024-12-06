package com.bhagwat.scm.paymentService.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
public class MoneyAddedEvent {
    private final Long customerId;
    private final Double amount;

    // Constructor, Getters
}


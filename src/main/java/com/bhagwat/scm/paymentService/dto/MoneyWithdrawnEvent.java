package com.bhagwat.scm.paymentService.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
public class MoneyWithdrawnEvent {
    private final Long partyId;
    private final Double amount;

    // Constructor, Getters
}


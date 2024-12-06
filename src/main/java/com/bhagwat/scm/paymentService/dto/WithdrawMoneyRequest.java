package com.bhagwat.scm.paymentService.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class WithdrawMoneyRequest {
    private Long partyId;
    private Double amount;
}


package com.bhagwat.scm.paymentService.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PayoutRequestDto {
    private String targetAddress;
    private double amount;
    private String beneficiaryAccount;
    private String beneficiaryIfsc;
}

package com.bhagwat.scm.paymentService.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PaymentResponseDto {
    private String status;
    private String message;
}

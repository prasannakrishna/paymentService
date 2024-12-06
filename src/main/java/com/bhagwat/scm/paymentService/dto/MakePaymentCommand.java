package com.bhagwat.scm.paymentService.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.axonframework.modelling.command.TargetAggregateIdentifier;

@Data
@AllArgsConstructor
public class MakePaymentCommand {
    @TargetAggregateIdentifier
    private final Long customerId;
    private final Long sellerId;
    private final Double orderValue;
    private final Long orderId;

    // Constructor, Getters
}


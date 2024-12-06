package com.bhagwat.scm.paymentService.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.axonframework.modelling.command.TargetAggregateIdentifier;

@Data
@AllArgsConstructor
public class AddMoneyCommand {
    @TargetAggregateIdentifier
    private final Long customerId;
    private final Double amount;

    // Constructor, Getters
}

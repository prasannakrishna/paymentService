package com.bhagwat.scm.paymentService.aggregate;

import com.bhagwat.scm.paymentService.dto.AddMoneyCommand;
import com.bhagwat.scm.paymentService.dto.MakePaymentCommand;
import com.bhagwat.scm.paymentService.dto.MoneyAddedEvent;
import com.bhagwat.scm.paymentService.dto.PaymentMadeEvent;
import org.axonframework.commandhandling.CommandHandler;
import org.axonframework.eventsourcing.EventSourcingHandler;
import org.axonframework.modelling.command.AggregateIdentifier;
import org.axonframework.spring.stereotype.Aggregate;

import static org.axonframework.modelling.command.AggregateLifecycle.apply;

@Aggregate
public class CustomerWalletAggregate {

    @AggregateIdentifier
    private Long customerId;
    private Double totalBalanceAvailable;

    public CustomerWalletAggregate() {
        // Default constructor for Axon
    }

    @CommandHandler
    public CustomerWalletAggregate(AddMoneyCommand command) {
        apply(new MoneyAddedEvent(command.getCustomerId(), command.getAmount()));
    }

    @EventSourcingHandler
    public void on(MoneyAddedEvent event) {
        this.customerId = event.getCustomerId();
        this.totalBalanceAvailable += event.getAmount();
    }

    @CommandHandler
    public void handle(MakePaymentCommand command) {
        if (this.totalBalanceAvailable < command.getOrderValue()) {
            throw new RuntimeException("Insufficient funds");
        }
        apply(new PaymentMadeEvent(
                command.getCustomerId(),
                command.getSellerId(),
                command.getOrderValue(),
                command.getOrderId()
        ));
    }

    @EventSourcingHandler
    public void on(PaymentMadeEvent event) {
        this.totalBalanceAvailable -= event.getOrderValue();
    }
}


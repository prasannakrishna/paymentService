package com.bhagwat.scm.paymentService.aggregate;

import com.bhagwat.scm.paymentService.dto.MoneyWithdrawnEvent;
import com.bhagwat.scm.paymentService.dto.PaymentMadeEvent;
import com.bhagwat.scm.paymentService.dto.WithdrawMoneyCommand;
import org.axonframework.commandhandling.CommandHandler;
import org.axonframework.eventsourcing.EventSourcingHandler;
import org.axonframework.modelling.command.AggregateIdentifier;
import org.axonframework.spring.stereotype.Aggregate;

import static org.axonframework.modelling.command.AggregateLifecycle.apply;

@Aggregate
public class OrgWalletAggregate {

    @AggregateIdentifier
    private Long partyId;
    private Double totalBalanceAvailable;

    public OrgWalletAggregate() {
        // Default constructor for Axon
    }

    @CommandHandler
    public void handle(WithdrawMoneyCommand command) {
        if (this.totalBalanceAvailable < command.getAmount()) {
            throw new RuntimeException("Insufficient funds");
        }
        apply(new MoneyWithdrawnEvent(command.getPartyId(), command.getAmount()));
    }

    @EventSourcingHandler
    public void on(MoneyWithdrawnEvent event) {
        this.totalBalanceAvailable -= event.getAmount();
    }

    @EventSourcingHandler
    public void on(PaymentMadeEvent event) {
        this.totalBalanceAvailable += event.getOrderValue();
    }
}


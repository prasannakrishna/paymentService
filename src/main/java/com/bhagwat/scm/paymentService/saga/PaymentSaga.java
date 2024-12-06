package com.bhagwat.scm.paymentService.saga;

import com.bhagwat.scm.paymentService.dto.MoneyWithdrawnEvent;
import com.bhagwat.scm.paymentService.dto.PaymentMadeEvent;
import com.bhagwat.scm.paymentService.dto.WithdrawMoneyCommand;
import org.axonframework.commandhandling.gateway.CommandGateway;
import org.axonframework.modelling.saga.EndSaga;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.spring.stereotype.Saga;
import org.springframework.beans.factory.annotation.Autowired;

@Saga
public class PaymentSaga {

    @Autowired
    private transient CommandGateway commandGateway;

    @StartSaga
    @SagaEventHandler(associationProperty = "customerId")
    public void handle(PaymentMadeEvent event) {
        commandGateway.send(new WithdrawMoneyCommand(event.getSellerId(), event.getOrderValue()));
    }

    @EndSaga
    @SagaEventHandler(associationProperty = "partyId")
    public void on(MoneyWithdrawnEvent event) {
        // Log or perform additional actions if required
    }
}


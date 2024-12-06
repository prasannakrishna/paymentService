package com.bhagwat.scm.paymentService.controller;

import com.bhagwat.scm.paymentService.dto.*;
import org.axonframework.commandhandling.gateway.CommandGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payment")
public class PaymentController {

    @Autowired
    private CommandGateway commandGateway;

    @PostMapping("/add-money")
    public ResponseEntity<String> addMoneyToWallet(@RequestBody AddMoneyRequest request) {
        commandGateway.send(new AddMoneyCommand(request.getCustomerId(), request.getAmount()));
        return ResponseEntity.ok("Money addition initiated");
    }

    @PostMapping("/withdraw-money")
    public ResponseEntity<String> withdrawMoneyFromWallet(@RequestBody WithdrawMoneyRequest request) {
        commandGateway.send(new WithdrawMoneyCommand(request.getPartyId(), request.getAmount()));
        return ResponseEntity.ok("Withdrawal initiated");
    }

    @PostMapping("/make-payment")
    public ResponseEntity<String> makePayment(@RequestBody MakePaymentRequest request) {
        commandGateway.send(new MakePaymentCommand(
                request.getCustomerId(),
                request.getSellerId(),
                request.getOrderValue(),
                request.getOrderId()
        ));
        return ResponseEntity.ok("Payment initiated");
    }
}


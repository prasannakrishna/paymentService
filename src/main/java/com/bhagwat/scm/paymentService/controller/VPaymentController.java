package com.bhagwat.scm.paymentService.controller;

import com.bhagwat.scm.paymentService.dto.AddMoneyRequest;
import com.bhagwat.scm.paymentService.dto.MakePaymentRequest;
import com.bhagwat.scm.paymentService.dto.WithdrawMoneyRequest;
import com.bhagwat.scm.paymentService.service.PaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payment")
public class VPaymentController {

    @Autowired
    private PaymentService paymentService;

    @PostMapping("/add-money")
    public ResponseEntity<String> addMoneyToWallet(@RequestBody AddMoneyRequest request) {
        return ResponseEntity.ok(paymentService.addMoneyToWallet(request));
    }

    @PostMapping("/withdraw-money")
    public ResponseEntity<String> withdrawMoneyFromWallet(@RequestBody WithdrawMoneyRequest request) {
        return ResponseEntity.ok(paymentService.withdrawMoneyFromWallet(request));
    }

    @PostMapping("/make-payment")
    public ResponseEntity<String> makePaymentToOrder(@RequestBody MakePaymentRequest request) {
        return ResponseEntity.ok(paymentService.makePaymentToOrder(request));
    }
}


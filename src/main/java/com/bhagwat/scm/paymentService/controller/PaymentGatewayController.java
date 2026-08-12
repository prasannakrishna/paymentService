package com.bhagwat.scm.paymentService.controller;

import com.bhagwat.scm.paymentService.dto.InitiatePaymentRequest;
import com.bhagwat.scm.paymentService.dto.InitiatePaymentResponse;
import com.bhagwat.scm.paymentService.dto.PaymentStatusResponse;
import com.bhagwat.scm.paymentService.service.PaymentGatewayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for Cashfree Payment Gateway operations.
 *
 * ┌─────────────────────────────────────────────────────────────────────────────┐
 * │  POST   /api/v1/payment/gateway/initiate     Initiate a new payment         │
 * │  GET    /api/v1/payment/gateway/status/{id}  Poll transaction status        │
 * │  GET    /api/v1/payment/gateway/return        Cashfree return URL handler   │
 * └─────────────────────────────────────────────────────────────────────────────┘
 *
 * Full payment flow:
 *   1. Client calls POST /initiate → receives { paymentLink, transactionId }
 *   2. Client redirects user to paymentLink (Cashfree hosted checkout)
 *   3. User pays → Cashfree sends webhook to POST /webhook/cashfree
 *   4. Client polls GET /status/{transactionId} to confirm
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payment/gateway")
@RequiredArgsConstructor
public class PaymentGatewayController {

    private final PaymentGatewayService paymentGatewayService;

    /**
     * Initiates a payment via Cashfree.
     *
     * Returns a {@code paymentLink} the frontend must redirect the user to.
     * The {@code transactionId} in the response is used for status polling.
     *
     * Idempotent: sending the same {@code orderId} / {@code idempotencyKey} twice
     * returns the existing transaction without creating a new Cashfree order.
     */
    @PostMapping("/initiate")
    public ResponseEntity<InitiatePaymentResponse> initiatePayment(
            @Valid @RequestBody InitiatePaymentRequest request) {

        log.info("Payment initiation request: orderId={} amount={} debitorId={} creditorId={}",
                request.getOrderId(), request.getAmount(),
                request.getDebitor().getDebitorId(), request.getCreditor().getCreditorId());

        InitiatePaymentResponse response = paymentGatewayService.initiatePayment(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Polls the current status of a payment transaction.
     *
     * For active (PENDING_PAYMENT / PROCESSING) transactions this also makes
     * a live call to Cashfree to get the latest order status.
     */
    @GetMapping("/status/{transactionId}")
    public ResponseEntity<PaymentStatusResponse> getStatus(
            @PathVariable String transactionId) {

        log.debug("Status poll: transactionId={}", transactionId);
        PaymentStatusResponse response = paymentGatewayService.getStatus(transactionId);
        return ResponseEntity.ok(response);
    }

    /**
     * Return URL endpoint — Cashfree redirects the user here after checkout.
     *
     * Cashfree appends {@code ?order_id=<order_id>} to the return URL.
     * We use this to redirect the user to the appropriate page in your frontend.
     *
     * In production, replace the redirect with your actual frontend URL.
     */
    @GetMapping("/return")
    public ResponseEntity<PaymentStatusResponse> handleReturn(
            @RequestParam("order_id") String orderId,
            @RequestParam(value = "txn_id", required = false) String transactionId) {

        log.info("Cashfree return redirect: orderId={} transactionId={}", orderId, transactionId);

        if (transactionId != null) {
            PaymentStatusResponse response = paymentGatewayService.getStatus(transactionId);
            return ResponseEntity.ok(response);
        }
        // Fallback: look up by orderId
        return ResponseEntity.ok(
                paymentGatewayService.getStatus(
                        "order:" + orderId   // will 404 gracefully via exception handler
                )
        );
    }
}

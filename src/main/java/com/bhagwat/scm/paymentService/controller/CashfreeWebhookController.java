package com.bhagwat.scm.paymentService.controller;

import com.bhagwat.scm.paymentService.service.PaymentGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Receives and processes Cashfree payment webhook events.
 *
 * Register this URL in your Cashfree dashboard:
 *   https://<your-domain>/api/v1/payment/webhook/cashfree
 *
 * Cashfree webhook events handled:
 *   PAYMENT_SUCCESS_WEBHOOK      → TransactionStatus.SUCCESS
 *   PAYMENT_FAILED_WEBHOOK       → TransactionStatus.FAILED
 *   PAYMENT_USER_DROPPED_WEBHOOK → TransactionStatus.CANCELLED
 *
 * Cashfree retries the webhook up to 5 times if it doesn't receive a 2xx.
 * We return 200 immediately after signature verification to acknowledge receipt,
 * even if downstream processing is incomplete — idempotency handles duplicates.
 *
 * Security:
 *   - HMAC-SHA256 signature is verified using x-webhook-signature + x-webhook-timestamp
 *   - @RequestBody is raw String so we compute the signature on the unaltered body
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payment/webhook")
@RequiredArgsConstructor
public class CashfreeWebhookController {

    private final PaymentGatewayService paymentGatewayService;

    /**
     * Cashfree posts payment status events here.
     *
     * Headers expected:
     *   x-webhook-signature  — Base64(HMAC-SHA256(timestamp + body, clientSecret))
     *   x-webhook-timestamp  — Unix timestamp (seconds) when the webhook was sent
     */
    @PostMapping("/cashfree")
    public ResponseEntity<Void> handleCashfreeWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "x-webhook-signature",  required = false) String signature,
            @RequestHeader(value = "x-webhook-timestamp",  required = false) String timestamp) {

        log.info("Cashfree webhook received: timestamp={}", timestamp);

        paymentGatewayService.processWebhook(rawBody, signature, timestamp);

        // Always return 200 so Cashfree stops retrying
        return ResponseEntity.ok().build();
    }
}

package com.bhagwat.scm.paymentService.controller;

import com.bhagwat.scm.paymentService.service.PaymentGatewayService;
import com.bhagwat.scm.paymentService.service.wallet.WalletWebhookHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Cashfree webhook endpoints for the wallet transfer flows.
 *
 * ┌────────────────────────────────────────────────────────────────────────────────┐
 * │  POST  /api/v1/wallet-transfers/webhook/pg-payment   PG payment events        │
 * │  POST  /api/v1/wallet-transfers/webhook/payout       Payout settlement events │
 * └────────────────────────────────────────────────────────────────────────────────┘
 *
 * PG payment webhook ({@code /webhook/pg-payment}):
 *   - If the orderId matches a WalletTransfer: handled here (A2W credit / A2A payout trigger)
 *   - If no match: forwarded to PaymentGatewayService (standalone PG payment)
 *
 * Payout webhook ({@code /webhook/payout}):
 *   - Only handled here; Cashfree is configured to post to this URL for payouts.
 *
 * Signature verification:
 *   Both use x-webhook-signature (HMAC-SHA256) verified by WebhookSignatureVerifier.
 *
 * Always returns 200 to stop Cashfree retries — idempotency is handled inside the handler.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/wallet-transfers/webhook")
@RequiredArgsConstructor
public class WalletWebhookController {

    private final WalletWebhookHandler  walletWebhookHandler;
    private final PaymentGatewayService paymentGatewayService;

    /**
     * Receives Cashfree PG payment events.
     * Routes to wallet handler first; falls back to standalone payment handler.
     */
    @PostMapping("/pg-payment")
    public ResponseEntity<Void> handlePgPayment(
            @RequestBody String rawBody,
            @RequestHeader(value = "x-webhook-signature", required = false) String signature,
            @RequestHeader(value = "x-webhook-timestamp", required = false) String timestamp) {

        log.info("PG payment webhook received: timestamp={}", timestamp);

        boolean handledByWallet = walletWebhookHandler.handlePgWebhook(rawBody, signature, timestamp);
        if (!handledByWallet) {
            // Standalone gateway payment — delegate to existing handler
            paymentGatewayService.processWebhook(rawBody, signature, timestamp);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Receives Cashfree Payout settlement events.
     * Register this URL in your Cashfree Payouts dashboard as the webhook URL.
     */
    @PostMapping("/payout")
    public ResponseEntity<Void> handlePayout(
            @RequestBody String rawBody,
            @RequestHeader(value = "x-webhook-signature", required = false) String signature,
            @RequestHeader(value = "x-webhook-timestamp", required = false) String timestamp) {

        log.info("Payout webhook received: timestamp={}", timestamp);
        walletWebhookHandler.handlePayoutWebhook(rawBody, signature, timestamp);
        return ResponseEntity.ok().build();
    }
}

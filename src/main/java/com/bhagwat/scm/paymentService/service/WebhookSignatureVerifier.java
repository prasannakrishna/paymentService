package com.bhagwat.scm.paymentService.service;

import com.bhagwat.scm.paymentService.config.CashfreeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Verifies the HMAC-SHA256 signature on Cashfree webhooks.
 *
 * Cashfree signs each webhook as:
 *   signature = Base64( HMAC-SHA256( timestamp + rawBody, clientSecret ) )
 *
 * The signature is sent in the {@code x-webhook-signature} header and
 * the timestamp in {@code x-webhook-timestamp}.
 *
 * Reference: https://docs.cashfree.com/docs/webhook-signature-verification
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookSignatureVerifier {

    private final CashfreeProperties cashfreeProps;

    /**
     * @param timestamp  value of the {@code x-webhook-timestamp} header
     * @param rawBody    raw (un-parsed) request body string
     * @param signature  value of the {@code x-webhook-signature} header
     * @return true if the computed signature matches the provided one
     */
    public boolean verify(String timestamp, String rawBody, String signature) {
        if (timestamp == null || rawBody == null || signature == null) {
            log.warn("Webhook verification skipped — missing timestamp, body, or signature header");
            return false;
        }

        try {
            String message = timestamp + rawBody;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    cashfreeProps.getClientSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));
            byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            String computed = Base64.getEncoder().encodeToString(hash);

            boolean valid = computed.equals(signature);
            if (!valid) {
                log.warn("Webhook signature mismatch: expected={} received={}", computed, signature);
            }
            return valid;

        } catch (Exception e) {
            log.error("Error computing webhook signature: {}", e.getMessage(), e);
            return false;
        }
    }
}

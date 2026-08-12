package com.bhagwat.scm.paymentService.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Cashfree Payouts API configuration — bound from {@code cashfree.payout.*}.
 *
 * The Payouts API has a different base URL from the PG API but uses the same credentials.
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "cashfree.payout")
public class CashfreePayoutProperties {

    /** Payout API base URL. Sandbox: https://sandbox.cashfree.com/payout/v1 */
    @NotBlank
    private String baseUrl = "https://sandbox.cashfree.com/payout/v1";

    /** Reuse PG client ID (same Cashfree account). */
    @NotBlank
    private String clientId;

    /** Reuse PG client secret. */
    @NotBlank
    private String clientSecret;

    /** Webhook URL Cashfree posts payout events to. */
    @NotBlank
    private String webhookUrl;

    @Positive
    private int timeoutSeconds = 30;
}

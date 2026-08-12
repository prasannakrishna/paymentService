package com.bhagwat.scm.paymentService.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Cashfree gateway configuration — bound from application.properties prefix "cashfree".
 *
 * Production tip: set CASHFREE_CLIENT_ID and CASHFREE_CLIENT_SECRET as environment
 * variables (or pull from Vault) so secrets never live in property files.
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "cashfree")
public class CashfreeProperties {

    /** Cashfree PG base URL. Sandbox: https://sandbox.cashfree.com/pg */
    @NotBlank
    private String baseUrl = "https://sandbox.cashfree.com/pg";

    /** App ID / client ID from Cashfree dashboard. */
    @NotBlank
    private String clientId;

    /** Client secret from Cashfree dashboard. */
    @NotBlank
    private String clientSecret;

    /** API version header sent with every request. */
    @NotBlank
    private String apiVersion = "2023-08-01";

    /** URL Cashfree redirects the user to after checkout. */
    @NotBlank
    private String returnUrl;

    /** Webhook URL Cashfree POSTs payment events to. */
    @NotBlank
    private String webhookUrl;

    /** HTTP connect/read timeout for Cashfree calls (seconds). */
    @Positive
    private int timeoutSeconds = 30;

    /**
     * How long (minutes) before a Cashfree order expires.
     * Cashfree accepts 1–1440 minutes.
     */
    @Positive
    private int orderExpiryMinutes = 30;
}

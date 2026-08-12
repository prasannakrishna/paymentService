package com.bhagwat.scm.paymentService.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Configures a {@link RestClient} pre-wired for the Cashfree Payouts API.
 * Separate bean from {@code cashfreeRestClient} (which is for PG).
 */
@Configuration
@RequiredArgsConstructor
public class CashfreePayoutRestClientConfig {

    private final CashfreePayoutProperties props;

    @Bean("cashfreePayoutRestClient")
    public RestClient cashfreePayoutRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = (int) Duration.ofSeconds(props.getTimeoutSeconds()).toMillis();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);

        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader("x-client-id",    props.getClientId())
                .defaultHeader("x-client-secret", props.getClientSecret())
                .defaultHeader("x-api-version",   "2023-08-01")
                .defaultHeader("Content-Type",     "application/json")
                .defaultHeader("Accept",           "application/json")
                .build();
    }
}

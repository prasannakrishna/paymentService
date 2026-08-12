package com.bhagwat.scm.paymentService.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Configures a {@link RestClient} pre-wired with Cashfree's base URL
 * and the common authentication headers.
 *
 * The bean is named "cashfreeRestClient" so it can be injected by name
 * without clashing with any other RestClient beans in the context.
 */
@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final CashfreeProperties props;

    @Bean("cashfreeRestClient")
    public RestClient cashfreeRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = (int) Duration.ofSeconds(props.getTimeoutSeconds()).toMillis();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);

        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader("x-client-id",     props.getClientId())
                .defaultHeader("x-client-secret",  props.getClientSecret())
                .defaultHeader("x-api-version",    props.getApiVersion())
                .defaultHeader("Content-Type",      "application/json")
                .defaultHeader("Accept",            "application/json")
                .build();
    }
}

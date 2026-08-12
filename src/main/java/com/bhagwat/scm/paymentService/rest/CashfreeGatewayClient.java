package com.bhagwat.scm.paymentService.rest;

import com.bhagwat.scm.paymentService.dto.cashfree.CfCreateOrderRequest;
import com.bhagwat.scm.paymentService.dto.cashfree.CfCreateOrderResponse;
import com.bhagwat.scm.paymentService.dto.cashfree.CfOrderStatusResponse;
import com.bhagwat.scm.paymentService.exception.PaymentGatewayException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Low-level HTTP client for the Cashfree PG REST API.
 *
 * All methods throw {@link PaymentGatewayException} on:
 *   - 4xx / 5xx responses from Cashfree
 *   - Network / timeout errors
 *
 * This class has no business logic — it purely translates between our DTOs
 * and the Cashfree HTTP API.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CashfreeGatewayClient {

    @Qualifier("cashfreeRestClient")
    private final RestClient restClient;

    // ── Create Order ──────────────────────────────────────────────────────────

    /**
     * Creates a new payment order on Cashfree.
     *
     * @param request fully built {@link CfCreateOrderRequest}
     * @return Cashfree's response including {@code payment_session_id} and {@code cf_order_id}
     * @throws PaymentGatewayException on any error
     */
    public CfCreateOrderResponse createOrder(CfCreateOrderRequest request) {
        log.info("Creating Cashfree order: orderId={} amount={} currency={}",
                request.getOrderId(), request.getOrderAmount(), request.getOrderCurrency());

        try {
            CfCreateOrderResponse response = restClient.post()
                    .uri("/orders")
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        String body = readBodySafely(resp);
                        log.error("Cashfree 4xx creating order: orderId={} status={} body={}",
                                request.getOrderId(), resp.getStatusCode(), body);
                        throw new PaymentGatewayException(
                                "Cashfree rejected order creation: " + body,
                                HttpStatus.valueOf(resp.getStatusCode().value()));
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        log.error("Cashfree 5xx creating order: orderId={} status={}",
                                request.getOrderId(), resp.getStatusCode());
                        throw new PaymentGatewayException(
                                "Cashfree server error — please retry",
                                HttpStatus.BAD_GATEWAY);
                    })
                    .body(CfCreateOrderResponse.class);

            log.info("Cashfree order created: orderId={} cfOrderId={} sessionId={}",
                    request.getOrderId(), response.getCfOrderId(), response.getPaymentSessionId());
            return response;

        } catch (PaymentGatewayException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("Network error calling Cashfree createOrder: orderId={}", request.getOrderId(), e);
            throw new PaymentGatewayException(
                    "Failed to reach Cashfree payment gateway: " + e.getMessage(),
                    HttpStatus.SERVICE_UNAVAILABLE, e);
        }
    }

    // ── Get Order Status ──────────────────────────────────────────────────────

    /**
     * Fetches the current status of a Cashfree order.
     * Use for polling when no webhook has been received.
     *
     * @param orderId your order ID (not Cashfree's cf_order_id)
     * @return order status response including payment details
     */
    public CfOrderStatusResponse getOrderStatus(String orderId) {
        log.debug("Fetching Cashfree order status: orderId={}", orderId);

        try {
            CfOrderStatusResponse response = restClient.get()
                    .uri("/orders/{orderId}", orderId)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (req, resp) -> {
                        throw new PaymentGatewayException(
                                "Cashfree order not found: " + orderId, HttpStatus.NOT_FOUND);
                    })
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        throw new PaymentGatewayException(
                                "Cashfree error fetching order: " + orderId,
                                HttpStatus.valueOf(resp.getStatusCode().value()));
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        throw new PaymentGatewayException(
                                "Cashfree server error fetching order status", HttpStatus.BAD_GATEWAY);
                    })
                    .body(CfOrderStatusResponse.class);

            log.debug("Cashfree order status: orderId={} status={}",
                    orderId, response != null ? response.getOrderStatus() : null);
            return response;

        } catch (PaymentGatewayException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("Network error fetching Cashfree order status: orderId={}", orderId, e);
            throw new PaymentGatewayException(
                    "Failed to fetch order status from Cashfree", HttpStatus.SERVICE_UNAVAILABLE, e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String readBodySafely(org.springframework.http.client.ClientHttpResponse resp) {
        try {
            return new String(resp.getBody().readAllBytes());
        } catch (Exception e) {
            return "(could not read response body)";
        }
    }
}

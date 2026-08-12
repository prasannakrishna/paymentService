package com.bhagwat.scm.paymentService.dto.cashfree;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Response body from {@code POST /orders} on the Cashfree PG API.
 *
 * Only the fields we use are mapped; unknown fields are safely ignored.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CfCreateOrderResponse {

    /** Cashfree's internal order ID (numeric string). */
    @JsonProperty("cf_order_id")
    private String cfOrderId;

    /** Our order ID echoed back. */
    @JsonProperty("order_id")
    private String orderId;

    /**
     * Order status: ACTIVE | PAID | EXPIRED | TERMINATED | CANCELLED
     * ACTIVE means the order is live and the user can pay.
     */
    @JsonProperty("order_status")
    private String orderStatus;

    @JsonProperty("order_amount")
    private BigDecimal orderAmount;

    @JsonProperty("order_currency")
    private String orderCurrency;

    /**
     * Session ID used to render Cashfree's JS SDK checkout.
     * Also used to construct the hosted checkout URL.
     */
    @JsonProperty("payment_session_id")
    private String paymentSessionId;

    /** ISO-8601 timestamp when this order expires. */
    @JsonProperty("order_expiry_time")
    private String orderExpiryTime;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("order_note")
    private String orderNote;

    /**
     * Convenience: the hosted checkout URL to redirect the user to.
     * Cashfree format: https://payments.cashfree.com/order/#<payment_session_id>
     */
    public String getHostedCheckoutUrl() {
        if (paymentSessionId == null) return null;
        return "https://payments.cashfree.com/order/#" + paymentSessionId;
    }
}

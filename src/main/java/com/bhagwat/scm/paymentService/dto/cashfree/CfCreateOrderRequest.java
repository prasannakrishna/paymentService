package com.bhagwat.scm.paymentService.dto.cashfree;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Request body for {@code POST /orders} on the Cashfree PG API.
 *
 * Cashfree docs: https://docs.cashfree.com/reference/pgcreateorder
 */
@Data
@Builder
public class CfCreateOrderRequest {

    /** Your unique order ID — max 50 chars, alphanumeric + underscore/hyphen. */
    @JsonProperty("order_id")
    private String orderId;

    /** Amount to charge. Min 1.00 INR. */
    @JsonProperty("order_amount")
    private BigDecimal orderAmount;

    /** ISO 4217 currency code. Currently only "INR" is supported for PG. */
    @JsonProperty("order_currency")
    @Builder.Default
    private String orderCurrency = "INR";

    /** Customer details (required). */
    @JsonProperty("customer_details")
    private CfCustomerDetails customerDetails;

    /** Redirect and webhook URLs. */
    @JsonProperty("order_meta")
    private CfOrderMeta orderMeta;

    /** Optional free-text note visible in Cashfree dashboard. */
    @JsonProperty("order_note")
    private String orderNote;

    /**
     * Order expiry time in ISO-8601 format.
     * Computed by the client from {@code cashfree.order-expiry-minutes}.
     */
    @JsonProperty("order_expiry_time")
    private String orderExpiryTime;
}

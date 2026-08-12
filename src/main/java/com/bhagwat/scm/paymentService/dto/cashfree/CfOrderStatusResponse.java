package com.bhagwat.scm.paymentService.dto.cashfree;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response from {@code GET /orders/{order_id}} — current order status.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CfOrderStatusResponse {

    @JsonProperty("cf_order_id")
    private String cfOrderId;

    @JsonProperty("order_id")
    private String orderId;

    /** ACTIVE | PAID | EXPIRED | TERMINATED | CANCELLED */
    @JsonProperty("order_status")
    private String orderStatus;

    @JsonProperty("order_amount")
    private BigDecimal orderAmount;

    @JsonProperty("order_currency")
    private String orderCurrency;

    @JsonProperty("payment_session_id")
    private String paymentSessionId;

    @JsonProperty("order_expiry_time")
    private String orderExpiryTime;

    @JsonProperty("payments")
    private List<CfPaymentDetail> payments;
}

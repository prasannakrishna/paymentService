package com.bhagwat.scm.paymentService.dto.cashfree;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Cashfree webhook payload structure (API version 2023-08-01).
 *
 * Cashfree sends this to the {@code notify_url} for payment events:
 *   PAYMENT_SUCCESS_WEBHOOK | PAYMENT_FAILED_WEBHOOK | PAYMENT_USER_DROPPED_WEBHOOK
 *
 * Signature verification:
 *   x-webhook-timestamp header + raw body → HMAC-SHA256 with client secret → base64
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CfWebhookPayload {

    /** Event type: PAYMENT_SUCCESS_WEBHOOK, PAYMENT_FAILED_WEBHOOK, etc. */
    @JsonProperty("type")
    private String type;

    @JsonProperty("event_time")
    private String eventTime;

    @JsonProperty("data")
    private CfWebhookData data;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CfWebhookData {

        @JsonProperty("order")
        private CfWebhookOrder order;

        @JsonProperty("payment")
        private CfWebhookPayment payment;

        @JsonProperty("customer_details")
        private CfCustomerDetails customerDetails;

        @JsonProperty("error_details")
        private CfPaymentDetail.CfErrorDetails errorDetails;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CfWebhookOrder {

        @JsonProperty("order_id")
        private String orderId;

        @JsonProperty("order_amount")
        private BigDecimal orderAmount;

        @JsonProperty("order_currency")
        private String orderCurrency;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CfWebhookPayment {

        @JsonProperty("cf_payment_id")
        private String cfPaymentId;

        /** SUCCESS | FAILED | USER_DROPPED | CANCELLED | PENDING */
        @JsonProperty("payment_status")
        private String paymentStatus;

        @JsonProperty("payment_amount")
        private BigDecimal paymentAmount;

        @JsonProperty("payment_currency")
        private String paymentCurrency;

        @JsonProperty("payment_message")
        private String paymentMessage;

        @JsonProperty("payment_time")
        private String paymentTime;

        @JsonProperty("bank_reference")
        private String bankReference;
    }
}

package com.bhagwat.scm.paymentService.dto.cashfree;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Individual payment attempt detail from Cashfree.
 * Used in both order-status polling and webhooks.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CfPaymentDetail {

    @JsonProperty("cf_payment_id")
    private String cfPaymentId;

    /**
     * Payment status: SUCCESS | NOT_ATTEMPTED | FAILED | USER_DROPPED |
     *                 VOID | CANCELLED | PENDING
     */
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

    /** Bank reference number — available after SUCCESS. */
    @JsonProperty("bank_reference")
    private String bankReference;

    /** Auth code for card payments. */
    @JsonProperty("auth_id")
    private String authId;

    @JsonProperty("error_details")
    private CfErrorDetails errorDetails;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CfErrorDetails {
        @JsonProperty("error_code")
        private String errorCode;
        @JsonProperty("error_description")
        private String errorDescription;
        @JsonProperty("error_reason")
        private String errorReason;
        @JsonProperty("error_source")
        private String errorSource;
    }
}

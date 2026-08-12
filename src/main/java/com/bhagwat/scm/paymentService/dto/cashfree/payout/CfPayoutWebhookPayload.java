package com.bhagwat.scm.paymentService.dto.cashfree.payout;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Cashfree Payout webhook event payload.
 *
 * Cashfree posts to {@code cashfree.payout.webhook-url} when a payout settles.
 *
 * Event types:
 *   TRANSFER_SUCCESS  — bank credit confirmed
 *   TRANSFER_FAILED   — bank rejected or timed out
 *   TRANSFER_REVERSED — previously credited amount reversed
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CfPayoutWebhookPayload {

    /** TRANSFER_SUCCESS | TRANSFER_FAILED | TRANSFER_REVERSED */
    @JsonProperty("type")
    private String type;

    @JsonProperty("event_time")
    private String eventTime;

    @JsonProperty("data")
    private CfPayoutData data;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CfPayoutData {

        @JsonProperty("transfer")
        private CfPayoutTransferEvent transfer;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CfPayoutTransferEvent {

        @JsonProperty("transfer_id")
        private String transferId;

        @JsonProperty("cf_transfer_id")
        private String cfTransferId;

        /** SUCCESS | FAILED | REVERSED */
        @JsonProperty("transfer_status")
        private String transferStatus;

        @JsonProperty("transfer_amount")
        private BigDecimal transferAmount;

        /** UTR / bank reference number — available for SUCCESS. */
        @JsonProperty("bank_reference")
        private String bankReference;

        @JsonProperty("failure_reason")
        private String failureReason;
    }
}

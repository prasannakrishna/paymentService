package com.bhagwat.scm.paymentService.dto.cashfree.payout;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Response from Cashfree Payouts {@code POST /payout/v1/standard-transfer}.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CfPayoutTransferResponse {

    /** Cashfree's internal transfer ID. */
    @JsonProperty("cf_transfer_id")
    private String cfTransferId;

    @JsonProperty("transfer_id")
    private String transferId;

    /**
     * Transfer status: RECEIVED | PENDING | SUCCESS | REVERSED | FAILED
     * RECEIVED = accepted for processing.
     */
    @JsonProperty("transfer_status")
    private String transferStatus;

    @JsonProperty("transfer_amount")
    private BigDecimal transferAmount;

    @JsonProperty("transfer_currency")
    private String transferCurrency;

    @JsonProperty("transfer_utr")
    private String transferUtr;

    @JsonProperty("message")
    private String message;
}

package com.bhagwat.scm.paymentService.dto.cashfree.payout;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Request body for {@code POST /payout/v1/standard-transfer} on Cashfree Payouts API.
 *
 * Cashfree Payouts docs: https://docs.cashfree.com/docs/payout-transfer-api
 */
@Data
@Builder
public class CfPayoutTransferRequest {

    /** Your unique transfer reference ID. Max 40 chars. */
    @JsonProperty("transfer_id")
    private String transferId;

    @JsonProperty("transfer_amount")
    private BigDecimal transferAmount;

    @JsonProperty("transfer_currency")
    @Builder.Default
    private String transferCurrency = "INR";

    /**
     * Transfer mode: NEFT | RTGS | IMPS | UPI | CARD
     * Use IMPS for near-instant settlement.
     */
    @JsonProperty("transfer_mode")
    @Builder.Default
    private String transferMode = "IMPS";

    @JsonProperty("transfer_remarks")
    private String transferRemarks;

    @JsonProperty("beneficiary")
    private CfPayoutBeneficiary beneficiary;

    @Data
    @Builder
    public static class CfPayoutBeneficiary {

        @JsonProperty("beneficiary_name")
        private String beneficiaryName;

        @JsonProperty("beneficiary_phone")
        private String beneficiaryPhone;

        @JsonProperty("bank_account")
        private BankAccount bankAccount;

        @Data
        @Builder
        public static class BankAccount {
            @JsonProperty("account_number")
            private String accountNumber;

            @JsonProperty("account_ifsc")
            private String accountIfsc;
        }
    }
}

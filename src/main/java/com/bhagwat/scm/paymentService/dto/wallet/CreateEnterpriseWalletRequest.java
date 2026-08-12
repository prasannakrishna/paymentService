package com.bhagwat.scm.paymentService.dto.wallet;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateEnterpriseWalletRequest {

    @NotBlank(message = "orgId is required")
    private String orgId;

    /**
     * Optional — null creates an org-level wallet.
     * Provide a value to create a division-level wallet.
     * The combination (orgId, divisionId) must be unique.
     */
    private String divisionId;

    @NotBlank(message = "walletName is required")
    private String walletName;

    /** ISO 4217 currency. Defaults to INR. */
    private String currency = "INR";

    /** Who is creating this wallet (userId / service ID). */
    private String createdBy;
}

package com.bhagwat.scm.paymentService.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Represents the payee (creditor) — the entity that will receive the funds.
 *
 * For a marketplace order: the creditor is the seller / merchant.
 * For a direct payment: the creditor is the beneficiary account.
 */
@Data
public class CreditorInfo {

    /** Unique identifier of the merchant / seller in your system. */
    @NotBlank(message = "Creditor ID is required")
    private String creditorId;

    @NotBlank(message = "Creditor name is required")
    private String name;

    /** Bank account number for direct transfers (optional for gateway payments). */
    private String accountNumber;

    /** IFSC code for direct bank transfers (optional for gateway payments). */
    private String ifscCode;

    /** Bank name (informational). */
    private String bankName;
}

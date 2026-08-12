package com.bhagwat.scm.paymentService.dto.wallet;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Collect from a payer's bank account via Cashfree PG, then immediately pay out
 * to a beneficiary's bank account via Cashfree Payout.
 *
 * No wallet is involved — this is a pure account-to-account channel.
 * Returns a paymentLink. Payout is triggered automatically on collection success.
 */
@Data
public class AccountToAccountTransferRequest {

    // ── Payer (source) ────────────────────────────────────────────────────────
    @NotBlank(message = "payerName is required")
    private String payerName;

    @NotBlank(message = "payerPhone is required")
    private String payerPhone;

    @Email(message = "payerEmail must be valid")
    private String payerEmail;

    @NotBlank(message = "payerId is required")
    private String payerId;

    // ── Beneficiary (destination) ─────────────────────────────────────────────
    @NotBlank(message = "beneficiaryName is required")
    private String beneficiaryName;

    @NotBlank(message = "beneficiaryAccountNumber is required")
    private String beneficiaryAccountNumber;

    @NotBlank(message = "beneficiaryIfsc is required")
    private String beneficiaryIfsc;

    private String beneficiaryPhone;

    // ── Transfer details ──────────────────────────────────────────────────────
    @NotNull(message = "amount is required")
    @DecimalMin(value = "1.00", message = "amount must be at least 1.00")
    private BigDecimal amount;

    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    private String description;
}

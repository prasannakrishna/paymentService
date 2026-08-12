package com.bhagwat.scm.paymentService.dto.wallet;

import com.bhagwat.scm.paymentService.common.WalletType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Top-up a wallet by collecting funds from a bank account/card/UPI via Cashfree PG.
 *
 * Returns a paymentLink. On successful payment (webhook), the wallet is credited.
 */
@Data
public class AccountToWalletTopupRequest {

    @NotBlank(message = "destinationWalletId is required")
    private String destinationWalletId;

    @NotNull(message = "destinationWalletType is required")
    private WalletType destinationWalletType;

    /** Name of the person making the payment. */
    @NotBlank(message = "payerName is required")
    private String payerName;

    @NotBlank(message = "payerPhone is required")
    private String payerPhone;

    @Email(message = "payerEmail must be valid")
    private String payerEmail;

    /** Unique payer ID in your system (customerId / userId). */
    @NotBlank(message = "payerId is required")
    private String payerId;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "1.00", message = "amount must be at least 1.00")
    private BigDecimal amount;

    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    private String description;
}

package com.bhagwat.scm.paymentService.dto.wallet;

import com.bhagwat.scm.paymentService.common.WalletType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Withdraw from a wallet and transfer to a bank account via Cashfree Payout.
 * The wallet is debited immediately; the bank credit happens asynchronously.
 */
@Data
public class WalletToAccountTransferRequest {

    @NotBlank(message = "sourceWalletId is required")
    private String sourceWalletId;

    @NotNull(message = "sourceWalletType is required")
    private WalletType sourceWalletType;

    @NotBlank(message = "beneficiaryName is required")
    private String beneficiaryName;

    @NotBlank(message = "accountNumber is required")
    private String accountNumber;

    @NotBlank(message = "ifscCode is required")
    private String ifscCode;

    private String beneficiaryPhone;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "1.00", message = "amount must be at least 1.00")
    private BigDecimal amount;

    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    private String description;
    private String initiatedBy;
}

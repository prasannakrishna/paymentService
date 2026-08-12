package com.bhagwat.scm.paymentService.dto.wallet;

import com.bhagwat.scm.paymentService.common.WalletType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Transfer funds between any two wallets.
 *
 * Works for all combinations:
 *   - INDIVIDUAL → INDIVIDUAL (customer pays customer)
 *   - INDIVIDUAL → ENTERPRISE (customer pays merchant)
 *   - ENTERPRISE → INDIVIDUAL (merchant refunds customer)
 *   - ENTERPRISE → ENTERPRISE (inter-org settlement)
 */
@Data
public class WalletToWalletTransferRequest {

    @NotBlank(message = "sourceWalletId is required")
    private String sourceWalletId;

    @NotNull(message = "sourceWalletType is required")
    private WalletType sourceWalletType;

    @NotBlank(message = "destinationWalletId is required")
    private String destinationWalletId;

    @NotNull(message = "destinationWalletType is required")
    private WalletType destinationWalletType;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be greater than 0")
    private BigDecimal amount;

    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    private String description;
    private String initiatedBy;
}

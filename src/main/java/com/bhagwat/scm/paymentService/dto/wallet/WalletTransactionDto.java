package com.bhagwat.scm.paymentService.dto.wallet;

import com.bhagwat.scm.paymentService.common.WalletTransactionType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A single ledger entry in a wallet statement. */
@Data
@Builder
public class WalletTransactionDto {
    private String transactionId;
    private String transferId;
    private WalletTransactionType transactionType;
    private BigDecimal amount;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private String currency;
    private String description;
    private LocalDateTime createdAt;
}

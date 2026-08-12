package com.bhagwat.scm.paymentService.dto.history;

import com.bhagwat.scm.paymentService.common.TransferType;
import com.bhagwat.scm.paymentService.common.WalletTransferStatus;
import com.bhagwat.scm.paymentService.common.WalletType;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single row in the transaction history view.
 */
@Value
@Builder
public class TransactionHistoryItem {

    String transferId;
    TransferType transferType;
    WalletTransferStatus status;

    // Source
    String sourceWalletId;
    WalletType sourceWalletType;
    String sourceAccountNumber;
    String sourceAccountName;

    // Destination
    String destinationWalletId;
    WalletType destinationWalletType;
    String destinationAccountNumber;
    String destinationAccountName;

    BigDecimal amount;
    BigDecimal fees;
    BigDecimal netAmount;
    String currency;

    String description;
    String failureReason;
    String bankReference;

    LocalDateTime initiatedAt;
    LocalDateTime updatedAt;
}

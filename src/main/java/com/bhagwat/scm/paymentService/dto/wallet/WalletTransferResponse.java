package com.bhagwat.scm.paymentService.dto.wallet;

import com.bhagwat.scm.paymentService.common.TransferType;
import com.bhagwat.scm.paymentService.common.WalletTransferStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Full view of a WalletTransfer — returned for all four transfer types. */
@Data
@Builder
public class WalletTransferResponse {

    private String transferId;
    private TransferType transferType;
    private WalletTransferStatus status;
    private BigDecimal amount;
    private BigDecimal fees;
    private BigDecimal netAmount;
    private String currency;

    // Source
    private String sourceWalletId;
    private String sourceAccountNumber;

    // Destination
    private String destinationWalletId;
    private String destinationAccountNumber;

    // Gateway references
    private String pgOrderId;
    private String paymentLink;         // null for W2W and W2A
    private String paymentSessionId;
    private String cfPayoutId;
    private String bankReference;

    private String failureReason;
    private String description;

    private LocalDateTime initiatedAt;
    private LocalDateTime updatedAt;

    /** Human-readable status message. */
    private String message;
}

package com.bhagwat.scm.paymentService.dto.wallet;

import com.bhagwat.scm.paymentService.common.WalletStatus;
import com.bhagwat.scm.paymentService.common.WalletType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Common wallet view returned for both Enterprise and Individual wallets. */
@Data
@Builder
public class WalletResponse {
    private String walletId;
    private WalletType walletType;
    private String ownerName;        // orgName or customerName
    private String ownerId;          // orgId or customerId
    private String divisionId;       // null for individual/org-level
    private BigDecimal balance;
    private String currency;
    private WalletStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

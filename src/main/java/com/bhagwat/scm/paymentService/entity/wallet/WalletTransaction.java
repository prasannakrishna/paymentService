package com.bhagwat.scm.paymentService.entity.wallet;

import com.bhagwat.scm.paymentService.common.WalletTransactionType;
import com.bhagwat.scm.paymentService.common.WalletType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable double-entry ledger record.
 *
 * Every balance change produces exactly one WalletTransaction row.
 * A wallet-to-wallet transfer produces TWO rows: one DEBIT on the source
 * and one CREDIT on the destination, both linked to the same transferId.
 *
 * All fields are {@code updatable = false} — this table is append-only.
 */
@Entity
@Table(
    name = "wallet_transactions",
    indexes = {
        @Index(name = "idx_wtxn_wallet",    columnList = "walletId, walletType"),
        @Index(name = "idx_wtxn_transfer",  columnList = "transferId"),
        @Index(name = "idx_wtxn_created",   columnList = "createdAt")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletTransaction {

    @Id
    @Column(length = 36, updatable = false)
    private String transactionId;                  // UUID

    /** The transfer that caused this ledger entry. */
    @Column(nullable = false, length = 36, updatable = false)
    private String transferId;

    /** Which wallet this entry belongs to. */
    @Column(nullable = false, length = 36, updatable = false)
    private String walletId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private WalletType walletType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private WalletTransactionType transactionType;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    /** Balance on this wallet immediately before this transaction. */
    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal balanceBefore;

    /** Balance on this wallet immediately after this transaction. */
    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal balanceAfter;

    @Column(nullable = false, length = 3, updatable = false)
    @Builder.Default
    private String currency = "INR";

    @Column(length = 500, updatable = false)
    private String description;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    private void onPersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}

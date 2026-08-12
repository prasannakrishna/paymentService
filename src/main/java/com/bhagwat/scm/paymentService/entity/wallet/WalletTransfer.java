package com.bhagwat.scm.paymentService.entity.wallet;

import com.bhagwat.scm.paymentService.common.TransferType;
import com.bhagwat.scm.paymentService.common.WalletTransferStatus;
import com.bhagwat.scm.paymentService.common.WalletType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Master record for every end-to-end fund transfer.
 * One row per business operation regardless of transfer type.
 *
 * Two WalletTransaction rows (debit + credit) are created for W2W.
 * One WalletTransaction row is created per wallet involved for mixed types.
 *
 * For gateway-assisted transfers (A2W, A2A, W2A):
 *   - {@code pgOrderId}    links to Cashfree PG order (collection leg)
 *   - {@code cfPayoutId}   links to Cashfree Payout transfer (payout leg)
 */
@Entity
@Table(
    name = "wallet_transfers",
    indexes = {
        @Index(name = "idx_wtx_idempotency",   columnList = "idempotencyKey",  unique = true),
        @Index(name = "idx_wtx_source_wallet", columnList = "sourceWalletId"),
        @Index(name = "idx_wtx_dest_wallet",   columnList = "destinationWalletId"),
        @Index(name = "idx_wtx_status",        columnList = "status"),
        @Index(name = "idx_wtx_pg_order",      columnList = "pgOrderId"),
        @Index(name = "idx_wtx_payout",        columnList = "cfPayoutId")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletTransfer {

    // ── Identity ───────────────────────────────────────────────────────────────
    @Id
    @Column(length = 36)
    private String transferId;                     // UUID

    @Column(nullable = false, length = 36, unique = true)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TransferType transferType;

    // ── Source ─────────────────────────────────────────────────────────────────
    /** Null for ACCOUNT_TO_* transfers. */
    @Column(length = 36)
    private String sourceWalletId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private WalletType sourceWalletType;

    /** Bank account number — set for ACCOUNT_TO_* transfers. */
    @Column(length = 30)
    private String sourceAccountNumber;

    @Column(length = 20)
    private String sourceIfsc;

    @Column(length = 200)
    private String sourceAccountName;

    // ── Destination ────────────────────────────────────────────────────────────
    /** Null for *_TO_ACCOUNT transfers. */
    @Column(length = 36)
    private String destinationWalletId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private WalletType destinationWalletType;

    @Column(length = 30)
    private String destinationAccountNumber;

    @Column(length = 20)
    private String destinationIfsc;

    @Column(length = 200)
    private String destinationAccountName;

    // ── Amounts ────────────────────────────────────────────────────────────────
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    /** Platform fee deducted from the transfer. Default 0. */
    @Column(nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal fees = BigDecimal.ZERO;

    /** Amount actually credited to destination = amount − fees. */
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal netAmount;

    // ── Status ─────────────────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private WalletTransferStatus status = WalletTransferStatus.INITIATED;

    @Column(length = 500)
    private String failureReason;

    // ── Cashfree references ────────────────────────────────────────────────────
    /** Cashfree PG order ID — set for A2W, A2A collection leg. */
    @Column(length = 50)
    private String pgOrderId;

    /** Cashfree payment session ID for hosted checkout. */
    @Column(length = 255)
    private String paymentSessionId;

    /** Hosted checkout URL returned to the client. */
    @Column(length = 500)
    private String paymentLink;

    /** Cashfree PG payment ID — set after collection succeeds. */
    @Column(length = 50)
    private String cfPaymentId;

    /** Cashfree Payout transfer ID — set for W2A, A2A payout leg. */
    @Column(length = 50)
    private String cfPayoutId;

    /** UTR / bank reference number — set after payout settles. */
    @Column(length = 100)
    private String bankReference;

    // ── Metadata ───────────────────────────────────────────────────────────────
    @Column(length = 500)
    private String description;

    @Column(length = 100)
    private String initiatedBy;

    // ── Timestamps ─────────────────────────────────────────────────────────────
    @Column(nullable = false, updatable = false)
    private LocalDateTime initiatedAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    @PrePersist
    private void onPersist() {
        if (initiatedAt == null) initiatedAt = LocalDateTime.now();
        if (netAmount   == null) netAmount   = amount.subtract(fees);
    }
}

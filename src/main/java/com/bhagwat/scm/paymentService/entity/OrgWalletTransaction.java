package com.bhagwat.scm.paymentService.entity;

import com.bhagwat.scm.paymentService.common.TransferType;
import com.bhagwat.scm.paymentService.common.WalletTransferStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Denormalised read-model for every payment made BY an enterprise org or division.
 *
 * Written once via the Kafka Streams pipeline; never updated.
 * Designed for high-throughput analytics queries:
 *
 *   "All payments by org X in the last year, grouped by destination"
 *   "Total outflow by org X division Y in last quarter"
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * MySQL PARTITION BY RANGE (same pattern as customer_wallet_transactions)
 * ─────────────────────────────────────────────────────────────────────────────
 *   ALTER TABLE org_wallet_transactions
 *     PARTITION BY RANGE (txn_year_month) (
 *       PARTITION p_before_2020 VALUES LESS THAN (202001),
 *       PARTITION p_2020        VALUES LESS THAN (202101),
 *       PARTITION p_2021        VALUES LESS THAN (202201),
 *       PARTITION p_2022        VALUES LESS THAN (202301),
 *       PARTITION p_2023        VALUES LESS THAN (202401),
 *       PARTITION p_2024        VALUES LESS THAN (202501),
 *       PARTITION p_2025        VALUES LESS THAN (202601),
 *       PARTITION p_future      VALUES LESS THAN MAXVALUE
 *     );
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Entity
@Table(
    name = "org_wallet_transactions",
    indexes = {
        // Primary pattern: all transactions for an org, newest first
        @Index(name = "idx_owt_org_date",       columnList = "orgId, txnDate DESC"),
        // Division-level query
        @Index(name = "idx_owt_org_div_date",   columnList = "orgId, divisionId, txnDate DESC"),
        // Partition-pruning support
        @Index(name = "idx_owt_org_ym",         columnList = "orgId, txnYearMonth"),
        // Status filter
        @Index(name = "idx_owt_org_status",     columnList = "orgId, status"),
        // Destination grouping
        @Index(name = "idx_owt_destination",    columnList = "counterpartyId"),
        // Transfer cross-reference
        @Index(name = "idx_owt_transfer",       columnList = "transferId"),
        // Amount sort
        @Index(name = "idx_owt_org_amount",     columnList = "orgId, amount DESC")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgWalletTransaction {

    // ── Identity ───────────────────────────────────────────────────────────────
    @Id
    @Column(length = 36, updatable = false)
    private String txnId;                          // UUID set by the Kafka Streams sink

    // ── Org / source ───────────────────────────────────────────────────────────
    /** Matches EnterpriseWallet.orgId. */
    @Column(nullable = false, length = 100, updatable = false)
    private String orgId;

    /** Null for org-level wallets; set for division wallets. */
    @Column(length = 100, updatable = false)
    private String divisionId;

    /** The EnterpriseWallet that was debited. */
    @Column(nullable = false, length = 36, updatable = false)
    private String sourceWalletId;

    // ── Transfer reference ─────────────────────────────────────────────────────
    @Column(nullable = false, length = 36, updatable = false)
    private String transferId;

    @Column(length = 50, updatable = false)
    private String pgOrderId;

    @Column(length = 50, updatable = false)
    private String cfPaymentId;

    @Column(length = 100, updatable = false)
    private String bankReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private TransferType transferType;

    // ── Amounts ────────────────────────────────────────────────────────────────
    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    @Builder.Default
    private BigDecimal fees = BigDecimal.ZERO;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal netAmount;

    @Column(nullable = false, length = 3, updatable = false)
    @Builder.Default
    private String currency = "INR";

    // ── Counterparty (destination) ─────────────────────────────────────────────
    @Column(length = 100, updatable = false)
    private String counterpartyId;

    @Column(length = 200, updatable = false)
    private String counterpartyName;

    /** "WALLET" or "BANK_ACCOUNT" */
    @Column(length = 20, updatable = false)
    private String counterpartyType;

    // ── Status ─────────────────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private WalletTransferStatus status;

    // ── Time ───────────────────────────────────────────────────────────────────
    @Column(nullable = false, updatable = false)
    private LocalDateTime txnDate;

    /** Integer YYYYMM — partition key for MySQL RANGE partitioning. */
    @Column(nullable = false, updatable = false)
    private int txnYearMonth;

    @Column(length = 500, updatable = false)
    private String description;

    @PrePersist
    private void onPersist() {
        if (txnDate == null)       txnDate = LocalDateTime.now();
        if (txnYearMonth <= 0)    txnYearMonth = computeYearMonth(txnDate);
        if (fees == null)          fees = java.math.BigDecimal.ZERO;
        if (netAmount == null)     netAmount = amount.subtract(fees);
    }

    private static int computeYearMonth(LocalDateTime dt) {
        return dt.getYear() * 100 + dt.getMonthValue();
    }
}

package com.bhagwat.scm.paymentService.entity;

import com.bhagwat.scm.paymentService.common.TransferType;
import com.bhagwat.scm.paymentService.common.WalletTransferStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Denormalised read-model for every payment made BY an individual customer.
 *
 * Written once via the Kafka Streams pipeline; never updated.
 * Designed for high-throughput analytics queries:
 *
 *   "All payments by customer X in the last 3 months"
 *   "Total spent by customer X per destination account"
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * MySQL PARTITION BY RANGE
 * ─────────────────────────────────────────────────────────────────────────────
 * Partition the table on {@code txnYearMonth} (INT, e.g. 202412 for Dec 2024)
 * so that time-range queries prune to 1-2 partitions instead of a full scan.
 *
 * Run this DDL once — JPA cannot auto-create partitions:
 *
 *   ALTER TABLE customer_wallet_transactions
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
 *
 * Add a new partition at the start of each year.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Entity
@Table(
    name = "customer_wallet_transactions",
    indexes = {
        // Primary query pattern: all transactions for a customer, newest first
        @Index(name = "idx_cwt_customer_date",    columnList = "customerId, txnDate DESC"),
        // Partition-pruning support
        @Index(name = "idx_cwt_customer_ym",      columnList = "customerId, txnYearMonth"),
        // Status filter
        @Index(name = "idx_cwt_customer_status",  columnList = "customerId, status"),
        // Destination grouping / bilateral lookups
        @Index(name = "idx_cwt_destination",      columnList = "counterpartyId"),
        // Transfer cross-reference
        @Index(name = "idx_cwt_transfer",         columnList = "transferId"),
        // Amount descending sort
        @Index(name = "idx_cwt_customer_amount",  columnList = "customerId, amount DESC")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerWalletTransaction {

    // ── Identity ───────────────────────────────────────────────────────────────
    @Id
    @Column(length = 36, updatable = false)
    private String txnId;                          // UUID set by the Kafka Streams sink

    // ── Customer / source ──────────────────────────────────────────────────────
    /** Matches IndividualWallet.customerId. */
    @Column(nullable = false, length = 100, updatable = false)
    private String customerId;

    /** Matches IndividualWallet.walletId — the source wallet. */
    @Column(nullable = false, length = 36, updatable = false)
    private String sourceWalletId;

    // ── Transfer reference ─────────────────────────────────────────────────────
    /** WalletTransfer.transferId or PaymentTransaction.transactionId. */
    @Column(nullable = false, length = 36, updatable = false)
    private String transferId;

    /** Cashfree PG order ID. */
    @Column(length = 50, updatable = false)
    private String pgOrderId;

    /** Cashfree payment ID (set after collection). */
    @Column(length = 50, updatable = false)
    private String cfPaymentId;

    /** UTR / bank reference number (set after payout settles). */
    @Column(length = 100, updatable = false)
    private String bankReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private TransferType transferType;

    // ── Amounts (BigDecimal — financial precision) ─────────────────────────────
    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    @Builder.Default
    private BigDecimal fees = BigDecimal.ZERO;

    /** Amount that reached the destination = amount − fees. */
    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal netAmount;

    @Column(nullable = false, length = 3, updatable = false)
    @Builder.Default
    private String currency = "INR";

    // ── Counterparty (destination) ─────────────────────────────────────────────
    /** Wallet ID or bank account number — used for GROUP BY queries. */
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

    /**
     * Integer YYYYMM, e.g. 202412 for Dec 2024.
     * Used as the partition key for MySQL RANGE partitioning.
     * Must be set before insert.
     */
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

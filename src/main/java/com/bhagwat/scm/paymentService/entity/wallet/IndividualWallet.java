package com.bhagwat.scm.paymentService.entity.wallet;

import com.bhagwat.scm.paymentService.common.WalletStatus;
import com.bhagwat.scm.paymentService.common.WalletType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Personal digital wallet — one per customer.
 * Equivalent to a Paytm or Amazon Pay wallet.
 *
 * Constraints:
 *   - One wallet per customerId (unique).
 *   - Balance is never negative.
 *   - All balance mutations go through {@code WalletBalanceService}.
 */
@Entity
@Table(
    name = "individual_wallets",
    indexes = {
        @Index(name = "idx_iw_customer_id", columnList = "customerId", unique = true),
        @Index(name = "idx_iw_status",      columnList = "status")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndividualWallet {

    @Id
    @Column(length = 36)
    private String walletId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    @Builder.Default
    private WalletType walletType = WalletType.INDIVIDUAL;

    @Column(nullable = false, length = 100, unique = true)
    private String customerId;

    @Column(nullable = false, length = 200)
    private String customerName;

    @Column(length = 200)
    private String customerEmail;

    @Column(length = 20)
    private String customerPhone;

    /** Current available balance — never negative. */
    @Column(nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    /** ISO 4217 currency code. */
    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private WalletStatus status = WalletStatus.ACTIVE;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** Optimistic locking — prevents concurrent balance races. */
    @Version
    private Long version;

    @PrePersist
    private void onPersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}

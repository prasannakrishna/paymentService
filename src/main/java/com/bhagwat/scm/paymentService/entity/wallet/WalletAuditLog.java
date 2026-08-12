package com.bhagwat.scm.paymentService.entity.wallet;

import com.bhagwat.scm.paymentService.common.AuditEventType;
import com.bhagwat.scm.paymentService.common.WalletType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Immutable, append-only audit trail for every business event in the wallet system.
 *
 * Never updated or deleted. All columns are {@code updatable = false}.
 * New rows are added by {@code WalletAuditService} for every state change,
 * balance movement, and webhook event.
 *
 * This table is the source of truth for compliance and incident investigation.
 */
@Entity
@Table(
    name = "wallet_audit_logs",
    indexes = {
        @Index(name = "idx_wal_wallet",   columnList = "walletId"),
        @Index(name = "idx_wal_transfer", columnList = "transferId"),
        @Index(name = "idx_wal_event",    columnList = "eventType"),
        @Index(name = "idx_wal_created",  columnList = "createdAt")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletAuditLog {

    @Id
    @Column(length = 36, updatable = false)
    private String auditId;                        // UUID

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50, updatable = false)
    private AuditEventType eventType;

    /** ID of the wallet this event relates to (null for non-wallet events). */
    @Column(length = 36, updatable = false)
    private String walletId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, updatable = false)
    private WalletType walletType;

    /** ID of the WalletTransfer that triggered this event. */
    @Column(length = 36, updatable = false)
    private String transferId;

    /**
     * JSON snapshot of the entity state BEFORE the event.
     * Null for creation events.
     */
    @Column(columnDefinition = "TEXT", updatable = false)
    private String previousValue;

    /** JSON snapshot of the entity state AFTER the event. */
    @Column(columnDefinition = "TEXT", updatable = false)
    private String newValue;

    /** userId / serviceId that triggered the action. */
    @Column(length = 100, updatable = false)
    private String actorId;

    /** Inbound request ID / correlation ID for tracing. */
    @Column(length = 100, updatable = false)
    private String correlationId;

    /** Human-readable summary (e.g. "Balance changed from 500.00 to 400.00"). */
    @Column(length = 500, updatable = false)
    private String summary;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    private void onPersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}

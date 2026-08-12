package com.bhagwat.scm.paymentService.service.wallet;

import com.bhagwat.scm.paymentService.common.AuditEventType;
import com.bhagwat.scm.paymentService.common.WalletType;
import com.bhagwat.scm.paymentService.entity.wallet.WalletAuditLog;
import com.bhagwat.scm.paymentService.repository.WalletAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Append-only writer for the immutable {@code WalletAuditLog} table.
 *
 * Uses {@code REQUIRES_NEW} propagation so audit records are committed even if
 * the caller's transaction rolls back (except for W2W where both must succeed together).
 *
 * For non-critical audits (webhooks received, gateway calls), writes are async.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletAuditService {

    private final WalletAuditLogRepository auditLogRepository;

    /**
     * Records a business event synchronously in its own transaction.
     * Use this for critical state-change events.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditEventType eventType,
                       String walletId, WalletType walletType,
                       String transferId,
                       String previousValue, String newValue,
                       String actorId, String summary) {
        WalletAuditLog entry = WalletAuditLog.builder()
                .auditId(UUID.randomUUID().toString())
                .eventType(eventType)
                .walletId(walletId)
                .walletType(walletType)
                .transferId(transferId)
                .previousValue(previousValue)
                .newValue(newValue)
                .actorId(actorId)
                .summary(summary)
                .build();
        auditLogRepository.save(entry);
    }

    /**
     * Records a transfer-level event (no specific wallet) synchronously.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTransferEvent(AuditEventType eventType,
                                    String transferId,
                                    String previousStatus, String newStatus,
                                    String summary) {
        WalletAuditLog entry = WalletAuditLog.builder()
                .auditId(UUID.randomUUID().toString())
                .eventType(eventType)
                .transferId(transferId)
                .previousValue(previousStatus)
                .newValue(newStatus)
                .summary(summary)
                .build();
        auditLogRepository.save(entry);
    }

    /**
     * Records non-critical events asynchronously to avoid adding latency to the hot path.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAsync(AuditEventType eventType, String transferId, String summary) {
        WalletAuditLog entry = WalletAuditLog.builder()
                .auditId(UUID.randomUUID().toString())
                .eventType(eventType)
                .transferId(transferId)
                .summary(summary)
                .build();
        auditLogRepository.save(entry);
        log.debug("Async audit recorded: eventType={} transferId={}", eventType, transferId);
    }
}

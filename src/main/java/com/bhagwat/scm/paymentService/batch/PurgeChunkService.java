package com.bhagwat.scm.paymentService.batch;

import com.bhagwat.scm.paymentService.repository.WalletAuditLogRepository;
import com.bhagwat.scm.paymentService.repository.WalletTransactionRepository;
import com.bhagwat.scm.paymentService.repository.WalletTransferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Handles deletion of a single chunk of expired WalletTransfer records and
 * their child rows (WalletTransaction, WalletAuditLog).
 *
 * Separated from {@link TransactionPurgeJob} into its own Spring bean because
 * {@code @Transactional(propagation = REQUIRES_NEW)} requires the call to go
 * through a Spring proxy — self-invocation on the same bean would bypass the
 * proxy and the transaction would not be created.
 *
 * Deletion order matters (child → parent):
 *   1. wallet_audit_logs   (references transferId)
 *   2. wallet_transactions (references transferId)
 *   3. wallet_transfers    (the master record)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurgeChunkService {

    private final WalletAuditLogRepository   auditLogRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final WalletTransferRepository   walletTransferRepository;

    /**
     * Deletes one chunk of expired transfers and all their child records.
     * Runs in its own independent transaction — if this chunk fails it is
     * rolled back without affecting other chunks that already committed.
     *
     * @param transferIds  IDs of expired WalletTransfer rows to delete
     * @return             result with counts of deleted rows per table
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChunkResult purgeChunk(List<String> transferIds) {
        if (transferIds.isEmpty()) {
            return ChunkResult.empty();
        }

        int auditDeleted   = auditLogRepository.deleteByTransferIdIn(transferIds);
        int txnDeleted     = walletTransactionRepository.deleteByTransferIdIn(transferIds);
        int transferDeleted = walletTransferRepository.deleteByTransferIdIn(transferIds);

        log.debug("Purge chunk: transferIds={} auditLogs={} transactions={} transfers={}",
                transferIds.size(), auditDeleted, txnDeleted, transferDeleted);

        return new ChunkResult(auditDeleted, txnDeleted, transferDeleted);
    }

    // ── Result record ─────────────────────────────────────────────────────────

    public record ChunkResult(int auditLogsDeleted, int walletTxnsDeleted, int transfersDeleted) {

        static ChunkResult empty() {
            return new ChunkResult(0, 0, 0);
        }

        ChunkResult add(ChunkResult other) {
            return new ChunkResult(
                    this.auditLogsDeleted + other.auditLogsDeleted,
                    this.walletTxnsDeleted + other.walletTxnsDeleted,
                    this.transfersDeleted + other.transfersDeleted
            );
        }
    }
}

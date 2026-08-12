package com.bhagwat.scm.paymentService.repository;

import com.bhagwat.scm.paymentService.common.WalletTransferStatus;
import com.bhagwat.scm.paymentService.entity.wallet.WalletTransfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface WalletTransferRepository
        extends JpaRepository<WalletTransfer, String>,
                JpaSpecificationExecutor<WalletTransfer> {

    Optional<WalletTransfer> findByIdempotencyKey(String idempotencyKey);

    Optional<WalletTransfer> findByPgOrderId(String pgOrderId);

    Optional<WalletTransfer> findByCfPayoutId(String cfPayoutId);

    List<WalletTransfer> findBySourceWalletIdOrderByInitiatedAtDesc(String walletId);

    List<WalletTransfer> findByDestinationWalletIdOrderByInitiatedAtDesc(String walletId);

    Page<WalletTransfer> findByStatusOrderByInitiatedAtDesc(WalletTransferStatus status, Pageable pageable);

    // ── Purge support ─────────────────────────────────────────────────────────

    /**
     * Fetches up to {@code limit} transfer IDs that are older than the cutoff,
     * ordered by initiatedAt ascending so the oldest records go first.
     * Always starts from offset 0 — after deletion the next call naturally
     * returns the next oldest batch.
     */
    @Query("""
            SELECT t.transferId FROM WalletTransfer t
            WHERE t.initiatedAt < :cutoff
            ORDER BY t.initiatedAt ASC
            LIMIT :limit
            """)
    List<String> findExpiredTransferIds(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("limit") int limit);

    /** Counts how many transfers are eligible for purge — used for dry-run reporting. */
    @Query("SELECT COUNT(t) FROM WalletTransfer t WHERE t.initiatedAt < :cutoff")
    long countExpiredTransfers(@Param("cutoff") LocalDateTime cutoff);

    /** Bulk-deletes a specific set of transfers by ID. */
    @Modifying
    @Query("DELETE FROM WalletTransfer t WHERE t.transferId IN :ids")
    int deleteByTransferIdIn(@Param("ids") List<String> ids);

    // ── Destination group-by queries ──────────────────────────────────────────

    /**
     * Groups outgoing transfers from a wallet by the destination wallet.
     * Returns rows: [destinationWalletId, destinationWalletType, destinationAccountName,
     *                totalAmount, totalNetAmount, transactionCount, completedAmount, completedCount]
     */
    @Query("""
            SELECT t.destinationWalletId,
                   t.destinationWalletType,
                   t.destinationAccountName,
                   SUM(t.amount),
                   SUM(t.netAmount),
                   COUNT(t),
                   SUM(CASE WHEN t.status = 'COMPLETED' THEN t.amount ELSE 0 END),
                   SUM(CASE WHEN t.status = 'COMPLETED' THEN 1 ELSE 0 END)
            FROM WalletTransfer t
            WHERE t.sourceWalletId = :walletId
              AND t.initiatedAt >= :from
              AND t.initiatedAt <= :to
            GROUP BY t.destinationWalletId, t.destinationWalletType, t.destinationAccountName
            ORDER BY SUM(t.amount) DESC
            """)
    List<Object[]> groupByDestinationWallet(
            @Param("walletId") String walletId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Groups outgoing transfers from a wallet by destination bank account.
     */
    @Query("""
            SELECT t.destinationAccountNumber,
                   t.destinationAccountName,
                   t.destinationIfsc,
                   SUM(t.amount),
                   SUM(t.netAmount),
                   COUNT(t),
                   SUM(CASE WHEN t.status = 'COMPLETED' THEN t.amount ELSE 0 END),
                   SUM(CASE WHEN t.status = 'COMPLETED' THEN 1 ELSE 0 END)
            FROM WalletTransfer t
            WHERE t.sourceWalletId = :walletId
              AND t.initiatedAt >= :from
              AND t.initiatedAt <= :to
            GROUP BY t.destinationAccountNumber, t.destinationAccountName, t.destinationIfsc
            ORDER BY SUM(t.amount) DESC
            """)
    List<Object[]> groupByDestinationAccount(
            @Param("walletId") String walletId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Groups all outgoing transfers from an org's wallets by destination.
     * Matches wallets whose IDs are in the provided list (org's wallet IDs).
     */
    @Query("""
            SELECT t.destinationWalletId,
                   t.destinationWalletType,
                   t.destinationAccountNumber,
                   t.destinationAccountName,
                   SUM(t.amount),
                   SUM(t.netAmount),
                   COUNT(t),
                   SUM(CASE WHEN t.status = 'COMPLETED' THEN t.amount ELSE 0 END),
                   SUM(CASE WHEN t.status = 'COMPLETED' THEN 1 ELSE 0 END)
            FROM WalletTransfer t
            WHERE t.sourceWalletId IN :walletIds
              AND t.initiatedAt >= :from
              AND t.initiatedAt <= :to
            GROUP BY t.destinationWalletId, t.destinationWalletType,
                     t.destinationAccountNumber, t.destinationAccountName
            ORDER BY SUM(t.amount) DESC
            """)
    List<Object[]> groupByDestinationForOrg(
            @Param("walletIds") List<String> walletIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}

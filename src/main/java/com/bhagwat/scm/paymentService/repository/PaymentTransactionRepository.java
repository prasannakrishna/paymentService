package com.bhagwat.scm.paymentService.repository;

import com.bhagwat.scm.paymentService.common.TransactionStatus;
import com.bhagwat.scm.paymentService.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, String> {

    Optional<PaymentTransaction> findByOrderId(String orderId);

    Optional<PaymentTransaction> findByCfOrderId(String cfOrderId);

    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    boolean existsByOrderIdAndStatusIn(String orderId, Iterable<TransactionStatus> statuses);

    /** Counts standalone PG transactions eligible for purge. */
    @Query("SELECT COUNT(p) FROM PaymentTransaction p WHERE p.initiatedAt < :cutoff")
    long countByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Bulk-deletes standalone PG transactions older than the cutoff.
     * Returns the number of deleted rows.
     */
    @Modifying
    @Query("DELETE FROM PaymentTransaction p WHERE p.initiatedAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}

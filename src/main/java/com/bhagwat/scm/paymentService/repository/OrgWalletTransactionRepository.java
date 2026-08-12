package com.bhagwat.scm.paymentService.repository;

import com.bhagwat.scm.paymentService.entity.OrgWalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrgWalletTransactionRepository
        extends JpaRepository<OrgWalletTransaction, String> {

    /** Deduplication check before inserting. */
    Optional<OrgWalletTransaction> findByTransferId(String transferId);

    boolean existsByTransferId(String transferId);
}

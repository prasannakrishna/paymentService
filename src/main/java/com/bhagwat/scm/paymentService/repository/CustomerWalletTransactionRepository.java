package com.bhagwat.scm.paymentService.repository;

import com.bhagwat.scm.paymentService.entity.CustomerWalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CustomerWalletTransactionRepository
        extends JpaRepository<CustomerWalletTransaction, String> {

    /** Deduplication check before inserting. */
    Optional<CustomerWalletTransaction> findByTransferId(String transferId);

    boolean existsByTransferId(String transferId);
}

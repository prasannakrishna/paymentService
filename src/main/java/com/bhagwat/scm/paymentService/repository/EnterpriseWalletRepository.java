package com.bhagwat.scm.paymentService.repository;

import com.bhagwat.scm.paymentService.entity.wallet.EnterpriseWallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EnterpriseWalletRepository extends JpaRepository<EnterpriseWallet, String> {

    /** All wallets for an org (org-level + all division wallets). */
    List<EnterpriseWallet> findByOrgId(String orgId);

    /** All wallets for a specific division within an org. */
    List<EnterpriseWallet> findByOrgIdAndDivisionIdNotNull(String orgId);

    Optional<EnterpriseWallet> findByOrgIdAndDivisionId(String orgId, String divisionId);

    Optional<EnterpriseWallet> findByOrgIdAndDivisionIdIsNull(String orgId);

    boolean existsByOrgIdAndDivisionId(String orgId, String divisionId);

    /**
     * Loads the wallet row with a pessimistic write lock.
     * Use within a @Transactional block when you need to read-then-update balance
     * and the optimistic locking retry cost is unacceptably high (high-contention wallets).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM EnterpriseWallet w WHERE w.walletId = :walletId")
    Optional<EnterpriseWallet> findByIdWithLock(String walletId);
}

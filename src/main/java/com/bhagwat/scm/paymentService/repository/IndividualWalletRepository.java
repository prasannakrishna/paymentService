package com.bhagwat.scm.paymentService.repository;

import com.bhagwat.scm.paymentService.entity.wallet.IndividualWallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IndividualWalletRepository extends JpaRepository<IndividualWallet, String> {

    Optional<IndividualWallet> findByCustomerId(String customerId);

    boolean existsByCustomerId(String customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM IndividualWallet w WHERE w.walletId = :walletId")
    Optional<IndividualWallet> findByIdWithLock(String walletId);
}

package com.bhagwat.scm.paymentService.repository;

import com.bhagwat.scm.paymentService.entity.CustomerWallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CustomerWalletRepository extends JpaRepository<CustomerWallet, Long> {
    Optional<CustomerWallet> findByCustomerId(Long customerId);
}

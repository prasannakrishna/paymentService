package com.bhagwat.scm.paymentService.repository;

import com.bhagwat.scm.paymentService.entity.CustomerWalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CustomerWalletTransactionRepository extends JpaRepository<CustomerWalletTransaction, Long> {
}

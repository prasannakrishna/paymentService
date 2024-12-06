package com.bhagwat.scm.paymentService.repository;

import com.bhagwat.scm.paymentService.entity.OrgWalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrgWalletTransactionRepository extends JpaRepository<OrgWalletTransaction, Long> {
}

package com.bhagwat.scm.paymentService.repository;

import com.bhagwat.scm.paymentService.entity.OrgWallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrgWalletRepository extends JpaRepository<OrgWallet, Long> {
    Optional<OrgWallet> findByPartyId(Long partyId);
}
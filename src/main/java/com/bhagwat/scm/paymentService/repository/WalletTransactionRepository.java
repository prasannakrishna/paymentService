package com.bhagwat.scm.paymentService.repository;

import com.bhagwat.scm.paymentService.common.WalletType;
import com.bhagwat.scm.paymentService.entity.wallet.WalletTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, String> {

    Page<WalletTransaction> findByWalletIdAndWalletTypeOrderByCreatedAtDesc(
            String walletId, WalletType walletType, Pageable pageable);

    List<WalletTransaction> findByTransferIdOrderByCreatedAtAsc(String transferId);

    /** Bulk-deletes all ledger entries linked to the given transfer IDs. */
    @Modifying
    @Query("DELETE FROM WalletTransaction t WHERE t.transferId IN :transferIds")
    int deleteByTransferIdIn(@Param("transferIds") List<String> transferIds);
}

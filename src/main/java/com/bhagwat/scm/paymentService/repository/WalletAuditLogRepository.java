package com.bhagwat.scm.paymentService.repository;

import com.bhagwat.scm.paymentService.entity.wallet.WalletAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WalletAuditLogRepository extends JpaRepository<WalletAuditLog, String> {

    Page<WalletAuditLog> findByWalletIdOrderByCreatedAtDesc(String walletId, Pageable pageable);

    List<WalletAuditLog> findByTransferIdOrderByCreatedAtAsc(String transferId);

    /** Bulk-deletes all audit entries linked to the given transfer IDs. */
    @Modifying
    @Query("DELETE FROM WalletAuditLog a WHERE a.transferId IN :transferIds")
    int deleteByTransferIdIn(@Param("transferIds") List<String> transferIds);
}

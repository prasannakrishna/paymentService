package com.bhagwat.scm.paymentService.service.wallet;

import com.bhagwat.scm.paymentService.common.AuditEventType;
import com.bhagwat.scm.paymentService.common.WalletStatus;
import com.bhagwat.scm.paymentService.common.WalletType;
import com.bhagwat.scm.paymentService.dto.wallet.CreateEnterpriseWalletRequest;
import com.bhagwat.scm.paymentService.dto.wallet.CreateIndividualWalletRequest;
import com.bhagwat.scm.paymentService.dto.wallet.WalletResponse;
import com.bhagwat.scm.paymentService.entity.wallet.EnterpriseWallet;
import com.bhagwat.scm.paymentService.entity.wallet.IndividualWallet;
import com.bhagwat.scm.paymentService.exception.WalletNotFoundException;
import com.bhagwat.scm.paymentService.repository.EnterpriseWalletRepository;
import com.bhagwat.scm.paymentService.repository.IndividualWalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Wallet lifecycle management — create, freeze, unfreeze, close, and query.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final EnterpriseWalletRepository enterpriseRepo;
    private final IndividualWalletRepository  individualRepo;
    private final WalletAuditService          auditService;

    // ── Enterprise wallet ─────────────────────────────────────────────────────

    @Transactional
    public WalletResponse createEnterpriseWallet(CreateEnterpriseWalletRequest req) {
        if (req.getDivisionId() != null
                ? enterpriseRepo.existsByOrgIdAndDivisionId(req.getOrgId(), req.getDivisionId())
                : enterpriseRepo.existsByOrgIdAndDivisionId(req.getOrgId(), null)) {
            throw new IllegalArgumentException("Enterprise wallet already exists for orgId="
                    + req.getOrgId() + " divisionId=" + req.getDivisionId());
        }
        EnterpriseWallet wallet = EnterpriseWallet.builder()
                .walletId(UUID.randomUUID().toString())
                .orgId(req.getOrgId())
                .divisionId(req.getDivisionId())
                .walletName(req.getWalletName())
                .currency(req.getCurrency() != null ? req.getCurrency() : "INR")
                .status(WalletStatus.ACTIVE)
                .createdBy(req.getCreatedBy())
                .build();
        wallet = enterpriseRepo.save(wallet);
        auditService.record(AuditEventType.WALLET_CREATED, wallet.getWalletId(),
                WalletType.ENTERPRISE, null, null, wallet.getWalletId(), req.getCreatedBy(),
                "Enterprise wallet created for org=" + req.getOrgId());
        log.info("Enterprise wallet created: walletId={} orgId={} divisionId={}",
                wallet.getWalletId(), req.getOrgId(), req.getDivisionId());
        return toResponse(wallet);
    }

    public WalletResponse getEnterpriseWallet(String walletId) {
        return toResponse(enterpriseRepo.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId)));
    }

    public WalletResponse getEnterpriseWalletByOrgDivision(String orgId, String divisionId) {
        EnterpriseWallet wallet = divisionId != null
                ? enterpriseRepo.findByOrgIdAndDivisionId(orgId, divisionId)
                        .orElseThrow(() -> new WalletNotFoundException(orgId, divisionId))
                : enterpriseRepo.findByOrgIdAndDivisionIdIsNull(orgId)
                        .orElseThrow(() -> new WalletNotFoundException(orgId, null));
        return toResponse(wallet);
    }

    // ── Individual wallet ─────────────────────────────────────────────────────

    @Transactional
    public WalletResponse createIndividualWallet(CreateIndividualWalletRequest req) {
        if (individualRepo.existsByCustomerId(req.getCustomerId())) {
            throw new IllegalArgumentException("Wallet already exists for customerId=" + req.getCustomerId());
        }
        IndividualWallet wallet = IndividualWallet.builder()
                .walletId(UUID.randomUUID().toString())
                .customerId(req.getCustomerId())
                .customerName(req.getCustomerName())
                .customerEmail(req.getCustomerEmail())
                .customerPhone(req.getCustomerPhone())
                .currency(req.getCurrency() != null ? req.getCurrency() : "INR")
                .status(WalletStatus.ACTIVE)
                .build();
        wallet = individualRepo.save(wallet);
        auditService.record(AuditEventType.WALLET_CREATED, wallet.getWalletId(),
                WalletType.INDIVIDUAL, null, null, wallet.getWalletId(), req.getCustomerId(),
                "Individual wallet created for customer=" + req.getCustomerId());
        log.info("Individual wallet created: walletId={} customerId={}", wallet.getWalletId(), req.getCustomerId());
        return toResponse(wallet);
    }

    public WalletResponse getIndividualWallet(String walletId) {
        return toResponse(individualRepo.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId)));
    }

    public WalletResponse getIndividualWalletByCustomer(String customerId) {
        return toResponse(individualRepo.findByCustomerId(customerId)
                .orElseThrow(() -> new WalletNotFoundException(customerId)));
    }

    // ── Status management ─────────────────────────────────────────────────────

    @Transactional
    public WalletResponse freezeWallet(String walletId, WalletType walletType) {
        return setStatus(walletId, walletType, WalletStatus.FROZEN, AuditEventType.WALLET_FROZEN);
    }

    @Transactional
    public WalletResponse unfreezeWallet(String walletId, WalletType walletType) {
        return setStatus(walletId, walletType, WalletStatus.ACTIVE, AuditEventType.WALLET_UNFROZEN);
    }

    @Transactional
    public WalletResponse closeWallet(String walletId, WalletType walletType) {
        return setStatus(walletId, walletType, WalletStatus.CLOSED, AuditEventType.WALLET_CLOSED);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private WalletResponse setStatus(String walletId, WalletType type,
                                     WalletStatus newStatus, AuditEventType event) {
        if (type == WalletType.ENTERPRISE) {
            EnterpriseWallet w = enterpriseRepo.findById(walletId)
                    .orElseThrow(() -> new WalletNotFoundException(walletId));
            String old = w.getStatus().name();
            w.setStatus(newStatus);
            enterpriseRepo.save(w);
            auditService.record(event, walletId, type, null, old, newStatus.name(), null,
                    "Status changed: " + old + " → " + newStatus);
            return toResponse(w);
        } else {
            IndividualWallet w = individualRepo.findById(walletId)
                    .orElseThrow(() -> new WalletNotFoundException(walletId));
            String old = w.getStatus().name();
            w.setStatus(newStatus);
            individualRepo.save(w);
            auditService.record(event, walletId, type, null, old, newStatus.name(), null,
                    "Status changed: " + old + " → " + newStatus);
            return toResponse(w);
        }
    }

    private WalletResponse toResponse(EnterpriseWallet w) {
        return WalletResponse.builder()
                .walletId(w.getWalletId())
                .walletType(WalletType.ENTERPRISE)
                .ownerName(w.getWalletName())
                .ownerId(w.getOrgId())
                .divisionId(w.getDivisionId())
                .balance(w.getBalance())
                .currency(w.getCurrency())
                .status(w.getStatus())
                .createdAt(w.getCreatedAt())
                .updatedAt(w.getUpdatedAt())
                .build();
    }

    private WalletResponse toResponse(IndividualWallet w) {
        return WalletResponse.builder()
                .walletId(w.getWalletId())
                .walletType(WalletType.INDIVIDUAL)
                .ownerName(w.getCustomerName())
                .ownerId(w.getCustomerId())
                .balance(w.getBalance())
                .currency(w.getCurrency())
                .status(w.getStatus())
                .createdAt(w.getCreatedAt())
                .updatedAt(w.getUpdatedAt())
                .build();
    }
}

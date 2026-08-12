package com.bhagwat.scm.paymentService.controller;

import com.bhagwat.scm.paymentService.common.WalletType;
import com.bhagwat.scm.paymentService.dto.wallet.*;
import com.bhagwat.scm.paymentService.entity.wallet.WalletTransaction;
import com.bhagwat.scm.paymentService.repository.WalletTransactionRepository;
import com.bhagwat.scm.paymentService.service.wallet.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for wallet lifecycle management.
 *
 * ┌────────────────────────────────────────────────────────────────────────────┐
 * │  POST  /api/v1/wallets/enterprise                Create enterprise wallet  │
 * │  POST  /api/v1/wallets/individual                Create individual wallet  │
 * │  GET   /api/v1/wallets/enterprise/{walletId}     Get enterprise wallet     │
 * │  GET   /api/v1/wallets/enterprise/lookup         Lookup by orgId+divId     │
 * │  GET   /api/v1/wallets/individual/{walletId}     Get individual wallet     │
 * │  GET   /api/v1/wallets/individual/by-customer    Lookup by customerId      │
 * │  PATCH /api/v1/wallets/{type}/{walletId}/freeze  Freeze wallet             │
 * │  PATCH /api/v1/wallets/{type}/{walletId}/unfreeze Unfreeze wallet          │
 * │  DELETE /api/v1/wallets/{type}/{walletId}        Close wallet              │
 * │  GET   /api/v1/wallets/{type}/{walletId}/statement Paginated statement     │
 * └────────────────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final WalletTransactionRepository txnRepo;

    // ── Enterprise wallet ─────────────────────────────────────────────────────

    @PostMapping("/enterprise")
    public ResponseEntity<WalletResponse> createEnterpriseWallet(
            @Valid @RequestBody CreateEnterpriseWalletRequest request) {
        log.info("Create enterprise wallet: orgId={} divisionId={}", request.getOrgId(), request.getDivisionId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(walletService.createEnterpriseWallet(request));
    }

    @GetMapping("/enterprise/{walletId}")
    public ResponseEntity<WalletResponse> getEnterpriseWallet(@PathVariable String walletId) {
        return ResponseEntity.ok(walletService.getEnterpriseWallet(walletId));
    }

    @GetMapping("/enterprise/lookup")
    public ResponseEntity<WalletResponse> lookupEnterpriseWallet(
            @RequestParam String orgId,
            @RequestParam(required = false) String divisionId) {
        return ResponseEntity.ok(walletService.getEnterpriseWalletByOrgDivision(orgId, divisionId));
    }

    // ── Individual wallet ─────────────────────────────────────────────────────

    @PostMapping("/individual")
    public ResponseEntity<WalletResponse> createIndividualWallet(
            @Valid @RequestBody CreateIndividualWalletRequest request) {
        log.info("Create individual wallet: customerId={}", request.getCustomerId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(walletService.createIndividualWallet(request));
    }

    @GetMapping("/individual/{walletId}")
    public ResponseEntity<WalletResponse> getIndividualWallet(@PathVariable String walletId) {
        return ResponseEntity.ok(walletService.getIndividualWallet(walletId));
    }

    @GetMapping("/individual/by-customer")
    public ResponseEntity<WalletResponse> getIndividualWalletByCustomer(
            @RequestParam String customerId) {
        return ResponseEntity.ok(walletService.getIndividualWalletByCustomer(customerId));
    }

    // ── Status management ─────────────────────────────────────────────────────

    @PatchMapping("/{walletType}/{walletId}/freeze")
    public ResponseEntity<WalletResponse> freezeWallet(
            @PathVariable WalletType walletType,
            @PathVariable String walletId) {
        return ResponseEntity.ok(walletService.freezeWallet(walletId, walletType));
    }

    @PatchMapping("/{walletType}/{walletId}/unfreeze")
    public ResponseEntity<WalletResponse> unfreezeWallet(
            @PathVariable WalletType walletType,
            @PathVariable String walletId) {
        return ResponseEntity.ok(walletService.unfreezeWallet(walletId, walletType));
    }

    @DeleteMapping("/{walletType}/{walletId}")
    public ResponseEntity<WalletResponse> closeWallet(
            @PathVariable WalletType walletType,
            @PathVariable String walletId) {
        return ResponseEntity.ok(walletService.closeWallet(walletId, walletType));
    }

    // ── Statement ─────────────────────────────────────────────────────────────

    @GetMapping("/{walletType}/{walletId}/statement")
    public ResponseEntity<WalletStatementResponse> getStatement(
            @PathVariable WalletType walletType,
            @PathVariable String walletId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<WalletTransaction> txnPage =
                txnRepo.findByWalletIdAndWalletTypeOrderByCreatedAtDesc(walletId, walletType, pageable);

        List<WalletTransactionDto> dtos = txnPage.getContent().stream()
                .map(t -> WalletTransactionDto.builder()
                        .transactionId(t.getTransactionId())
                        .transferId(t.getTransferId())
                        .transactionType(t.getTransactionType())
                        .amount(t.getAmount())
                        .balanceBefore(t.getBalanceBefore())
                        .balanceAfter(t.getBalanceAfter())
                        .currency(t.getCurrency())
                        .description(t.getDescription())
                        .createdAt(t.getCreatedAt())
                        .build())
                .toList();

        WalletStatementResponse response = WalletStatementResponse.builder()
                .walletId(walletId)
                .currency("INR")
                .transactions(dtos)
                .page(page)
                .pageSize(size)
                .totalElements(txnPage.getTotalElements())
                .totalPages(txnPage.getTotalPages())
                .build();

        return ResponseEntity.ok(response);
    }
}

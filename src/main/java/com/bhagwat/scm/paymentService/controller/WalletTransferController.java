package com.bhagwat.scm.paymentService.controller;

import com.bhagwat.scm.paymentService.dto.wallet.*;
import com.bhagwat.scm.paymentService.service.wallet.WalletTransferOrchestrator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for all four fund transfer types.
 *
 * ┌──────────────────────────────────────────────────────────────────────────────────┐
 * │  POST  /api/v1/wallet-transfers/wallet-to-wallet     W2W (internal, instant)    │
 * │  POST  /api/v1/wallet-transfers/wallet-to-account    W2A (payout to bank)       │
 * │  POST  /api/v1/wallet-transfers/account-to-wallet    A2W (topup from bank/UPI)  │
 * │  POST  /api/v1/wallet-transfers/account-to-account   A2A (PG + payout)         │
 * │  GET   /api/v1/wallet-transfers/{transferId}         Transfer status            │
 * └──────────────────────────────────────────────────────────────────────────────────┘
 *
 * All POST endpoints are idempotent — resending the same idempotencyKey returns the
 * existing transfer without creating a new one.
 *
 * For A2W and A2A: the response contains a {@code paymentLink}.
 * Redirect the user to this URL to complete the payment on Cashfree's hosted checkout.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/wallet-transfers")
@RequiredArgsConstructor
public class WalletTransferController {

    private final WalletTransferOrchestrator orchestrator;

    /**
     * Transfer funds between any two wallets (INDIVIDUAL or ENTERPRISE).
     * Synchronous — completes within the HTTP response.
     */
    @PostMapping("/wallet-to-wallet")
    public ResponseEntity<WalletTransferResponse> walletToWallet(
            @Valid @RequestBody WalletToWalletTransferRequest request) {
        log.info("W2W request: src={} dst={} amount={} key={}",
                request.getSourceWalletId(), request.getDestinationWalletId(),
                request.getAmount(), request.getIdempotencyKey());
        return ResponseEntity.ok(orchestrator.walletToWallet(request));
    }

    /**
     * Withdraw from a wallet and pay out to a bank account via Cashfree.
     * Returns immediately with status PAYOUT_INITIATED.
     * Final status arrives via Cashfree Payout webhook.
     */
    @PostMapping("/wallet-to-account")
    public ResponseEntity<WalletTransferResponse> walletToAccount(
            @Valid @RequestBody WalletToAccountTransferRequest request) {
        log.info("W2A request: srcWallet={} dstAccount={} amount={} key={}",
                request.getSourceWalletId(), request.getAccountNumber(),
                request.getAmount(), request.getIdempotencyKey());
        return ResponseEntity.ok(orchestrator.walletToAccount(request));
    }

    /**
     * Top-up a wallet by collecting from a bank account/card/UPI via Cashfree PG.
     * Returns a {@code paymentLink} — redirect the user to complete payment.
     * Wallet is credited on Cashfree payment webhook.
     */
    @PostMapping("/account-to-wallet")
    public ResponseEntity<WalletTransferResponse> accountToWallet(
            @Valid @RequestBody AccountToWalletTopupRequest request) {
        log.info("A2W request: dstWallet={} amount={} payer={} key={}",
                request.getDestinationWalletId(), request.getAmount(),
                request.getPayerId(), request.getIdempotencyKey());
        return ResponseEntity.ok(orchestrator.accountToWallet(request));
    }

    /**
     * Collect from a bank account via Cashfree PG and pay out to another bank account.
     * Returns a {@code paymentLink}. Payout is triggered automatically on collection.
     */
    @PostMapping("/account-to-account")
    public ResponseEntity<WalletTransferResponse> accountToAccount(
            @Valid @RequestBody AccountToAccountTransferRequest request) {
        log.info("A2A request: payer={} dstAccount={} amount={} key={}",
                request.getPayerId(), request.getBeneficiaryAccountNumber(),
                request.getAmount(), request.getIdempotencyKey());
        return ResponseEntity.ok(orchestrator.accountToAccount(request));
    }

    /** Get current status of any transfer. */
    @GetMapping("/{transferId}")
    public ResponseEntity<WalletTransferResponse> getTransfer(@PathVariable String transferId) {
        return ResponseEntity.ok(orchestrator.getTransfer(transferId));
    }
}

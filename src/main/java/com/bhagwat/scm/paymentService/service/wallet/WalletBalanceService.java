package com.bhagwat.scm.paymentService.service.wallet;

import com.bhagwat.scm.paymentService.common.WalletStatus;
import com.bhagwat.scm.paymentService.common.WalletType;
import com.bhagwat.scm.paymentService.entity.wallet.EnterpriseWallet;
import com.bhagwat.scm.paymentService.entity.wallet.IndividualWallet;
import com.bhagwat.scm.paymentService.exception.InsufficientBalanceException;
import com.bhagwat.scm.paymentService.exception.WalletFrozenException;
import com.bhagwat.scm.paymentService.exception.WalletNotFoundException;
import com.bhagwat.scm.paymentService.repository.EnterpriseWalletRepository;
import com.bhagwat.scm.paymentService.repository.IndividualWalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Low-level balance mutation service.
 *
 * ALL balance changes in the system must go through {@code debit()} or {@code credit()}.
 * Both methods use optimistic locking with up to 3 retries and exponential back-off.
 *
 * Returns the balance BEFORE the change (needed for WalletTransaction ledger entries).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletBalanceService {

    private static final int MAX_RETRIES  = 3;
    private static final long BASE_DELAY_MS = 50;

    private final EnterpriseWalletRepository enterpriseRepo;
    private final IndividualWalletRepository  individualRepo;

    // ── Debit ─────────────────────────────────────────────────────────────────

    /**
     * Debit {@code amount} from a wallet. Returns balance BEFORE debit.
     * Validates ACTIVE status and sufficient funds before applying.
     */
    @Transactional
    public BigDecimal debit(String walletId, WalletType walletType, BigDecimal amount) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return applyDebit(walletId, walletType, amount);
            } catch (ObjectOptimisticLockingFailureException e) {
                log.warn("Optimistic lock conflict on debit: walletId={} attempt={}", walletId, attempt);
                if (attempt == MAX_RETRIES) {
                    throw new IllegalStateException(
                            "Wallet is under high contention. Debit failed after " + MAX_RETRIES + " retries: " + walletId, e);
                }
                sleepExponential(attempt);
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    /**
     * Credit {@code amount} to a wallet. Returns balance BEFORE credit.
     * Validates ACTIVE status before applying.
     */
    @Transactional
    public BigDecimal credit(String walletId, WalletType walletType, BigDecimal amount) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return applyCredit(walletId, walletType, amount);
            } catch (ObjectOptimisticLockingFailureException e) {
                log.warn("Optimistic lock conflict on credit: walletId={} attempt={}", walletId, attempt);
                if (attempt == MAX_RETRIES) {
                    throw new IllegalStateException(
                            "Wallet is under high contention. Credit failed after " + MAX_RETRIES + " retries: " + walletId, e);
                }
                sleepExponential(attempt);
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private BigDecimal applyDebit(String walletId, WalletType walletType, BigDecimal amount) {
        if (walletType == WalletType.ENTERPRISE) {
            EnterpriseWallet wallet = enterpriseRepo.findById(walletId)
                    .orElseThrow(() -> new WalletNotFoundException(walletId));
            assertActive(wallet.getStatus(), walletId);
            if (wallet.getBalance().compareTo(amount) < 0) {
                throw new InsufficientBalanceException(walletId, wallet.getBalance(), amount);
            }
            BigDecimal before = wallet.getBalance();
            wallet.setBalance(before.subtract(amount));
            enterpriseRepo.save(wallet);
            log.debug("DEBIT enterprise wallet: walletId={} amount={} newBalance={}", walletId, amount, wallet.getBalance());
            return before;
        } else {
            IndividualWallet wallet = individualRepo.findById(walletId)
                    .orElseThrow(() -> new WalletNotFoundException(walletId));
            assertActive(wallet.getStatus(), walletId);
            if (wallet.getBalance().compareTo(amount) < 0) {
                throw new InsufficientBalanceException(walletId, wallet.getBalance(), amount);
            }
            BigDecimal before = wallet.getBalance();
            wallet.setBalance(before.subtract(amount));
            individualRepo.save(wallet);
            log.debug("DEBIT individual wallet: walletId={} amount={} newBalance={}", walletId, amount, wallet.getBalance());
            return before;
        }
    }

    private BigDecimal applyCredit(String walletId, WalletType walletType, BigDecimal amount) {
        if (walletType == WalletType.ENTERPRISE) {
            EnterpriseWallet wallet = enterpriseRepo.findById(walletId)
                    .orElseThrow(() -> new WalletNotFoundException(walletId));
            assertActive(wallet.getStatus(), walletId);
            BigDecimal before = wallet.getBalance();
            wallet.setBalance(before.add(amount));
            enterpriseRepo.save(wallet);
            log.debug("CREDIT enterprise wallet: walletId={} amount={} newBalance={}", walletId, amount, wallet.getBalance());
            return before;
        } else {
            IndividualWallet wallet = individualRepo.findById(walletId)
                    .orElseThrow(() -> new WalletNotFoundException(walletId));
            assertActive(wallet.getStatus(), walletId);
            BigDecimal before = wallet.getBalance();
            wallet.setBalance(before.add(amount));
            individualRepo.save(wallet);
            log.debug("CREDIT individual wallet: walletId={} amount={} newBalance={}", walletId, amount, wallet.getBalance());
            return before;
        }
    }

    private void assertActive(WalletStatus status, String walletId) {
        if (status == WalletStatus.FROZEN || status == WalletStatus.CLOSED) {
            throw new WalletFrozenException(walletId);
        }
    }

    private void sleepExponential(int attempt) {
        try {
            Thread.sleep(BASE_DELAY_MS * (1L << attempt));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.bhagwat.scm.paymentService.repository;

import com.bhagwat.scm.paymentService.common.WalletTransferStatus;
import com.bhagwat.scm.paymentService.entity.wallet.WalletTransfer;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable JPA Specifications for dynamic WalletTransfer queries.
 * Compose with Specification.and() / .or().
 */
public final class WalletTransferSpecification {

    private WalletTransferSpecification() {}

    // ── Time window ───────────────────────────────────────────────────────────

    public static Specification<WalletTransfer> initiatedAfter(LocalDateTime from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("initiatedAt"), from);
    }

    public static Specification<WalletTransfer> initiatedBefore(LocalDateTime to) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("initiatedAt"), to);
    }

    public static Specification<WalletTransfer> withinWindow(LocalDateTime from, LocalDateTime to) {
        return initiatedAfter(from).and(initiatedBefore(to));
    }

    // ── Status ────────────────────────────────────────────────────────────────

    public static Specification<WalletTransfer> hasStatuses(List<WalletTransferStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) return Specification.where(null);
        return (root, query, cb) -> root.get("status").in(statuses);
    }

    // ── Source ────────────────────────────────────────────────────────────────

    public static Specification<WalletTransfer> sourceWalletId(String walletId) {
        return (root, query, cb) -> cb.equal(root.get("sourceWalletId"), walletId);
    }

    public static Specification<WalletTransfer> sourceWalletIds(List<String> walletIds) {
        return (root, query, cb) -> root.get("sourceWalletId").in(walletIds);
    }

    public static Specification<WalletTransfer> sourceAccountNumber(String accountNumber) {
        return (root, query, cb) -> cb.equal(root.get("sourceAccountNumber"), accountNumber);
    }

    // ── Destination ───────────────────────────────────────────────────────────

    public static Specification<WalletTransfer> destinationWalletId(String walletId) {
        return (root, query, cb) -> cb.equal(root.get("destinationWalletId"), walletId);
    }

    public static Specification<WalletTransfer> destinationWalletIds(List<String> walletIds) {
        return (root, query, cb) -> root.get("destinationWalletId").in(walletIds);
    }

    public static Specification<WalletTransfer> destinationAccountNumber(String accountNumber) {
        return (root, query, cb) -> cb.equal(root.get("destinationAccountNumber"), accountNumber);
    }

    // ── Composite: between two parties ────────────────────────────────────────

    /**
     * Transfers where fromId is either source wallet or source account,
     * AND toId is either destination wallet or destination account.
     */
    public static Specification<WalletTransfer> between(String fromId, String toId) {
        return (root, query, cb) -> {
            Predicate fromWallet  = cb.equal(root.get("sourceWalletId"),          fromId);
            Predicate fromAccount = cb.equal(root.get("sourceAccountNumber"),      fromId);
            Predicate toWallet    = cb.equal(root.get("destinationWalletId"),      toId);
            Predicate toAccount   = cb.equal(root.get("destinationAccountNumber"), toId);
            return cb.and(
                cb.or(fromWallet, fromAccount),
                cb.or(toWallet, toAccount)
            );
        };
    }

    /**
     * All transfers where the given ID appears on either side (useful for bilateral view).
     */
    public static Specification<WalletTransfer> involving(String id) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("sourceWalletId"),          id));
            predicates.add(cb.equal(root.get("sourceAccountNumber"),      id));
            predicates.add(cb.equal(root.get("destinationWalletId"),      id));
            predicates.add(cb.equal(root.get("destinationAccountNumber"), id));
            return cb.or(predicates.toArray(new Predicate[0]));
        };
    }

    // ── Convenience: user history (source = individual wallet) ────────────────

    /**
     * All outbound transfers from a specific wallet,
     * optionally filtered by status list and time window.
     */
    public static Specification<WalletTransfer> userHistory(
            String sourceWalletId,
            LocalDateTime from,
            LocalDateTime to,
            List<WalletTransferStatus> statuses) {

        Specification<WalletTransfer> spec = sourceWalletId(sourceWalletId)
                .and(withinWindow(from, to));
        if (statuses != null && !statuses.isEmpty()) {
            spec = spec.and(hasStatuses(statuses));
        }
        return spec;
    }

    /**
     * All transfers involving any of the org's wallet IDs (as source),
     * optionally narrowed by from/to account.
     */
    public static Specification<WalletTransfer> orgHistory(
            List<String> orgWalletIds,
            String fromAccountOrWallet,
            String toAccountOrWallet,
            LocalDateTime from,
            LocalDateTime to,
            List<WalletTransferStatus> statuses) {

        Specification<WalletTransfer> spec = sourceWalletIds(orgWalletIds)
                .and(withinWindow(from, to));

        if (fromAccountOrWallet != null && !fromAccountOrWallet.isBlank()) {
            spec = spec.and(sourceAccountNumber(fromAccountOrWallet)
                    .or(sourceWalletId(fromAccountOrWallet)));
        }
        if (toAccountOrWallet != null && !toAccountOrWallet.isBlank()) {
            spec = spec.and(destinationAccountNumber(toAccountOrWallet)
                    .or(destinationWalletId(toAccountOrWallet)));
        }
        if (statuses != null && !statuses.isEmpty()) {
            spec = spec.and(hasStatuses(statuses));
        }
        return spec;
    }
}

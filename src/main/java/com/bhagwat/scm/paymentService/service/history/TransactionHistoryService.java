package com.bhagwat.scm.paymentService.service.history;

import com.bhagwat.scm.paymentService.common.TimePeriod;
import com.bhagwat.scm.paymentService.common.TransactionSortOrder;
import com.bhagwat.scm.paymentService.common.WalletTransferStatus;
import com.bhagwat.scm.paymentService.dto.history.*;
import com.bhagwat.scm.paymentService.entity.wallet.EnterpriseWallet;
import com.bhagwat.scm.paymentService.entity.wallet.IndividualWallet;
import com.bhagwat.scm.paymentService.entity.wallet.WalletTransfer;
import com.bhagwat.scm.paymentService.exception.WalletNotFoundException;
import com.bhagwat.scm.paymentService.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionHistoryService {

    private final WalletTransferRepository    walletTransferRepository;
    private final IndividualWalletRepository  individualWalletRepository;
    private final EnterpriseWalletRepository  enterpriseWalletRepository;

    // ── Max history window enforced server-side ────────────────────────────────
    private static final int MAX_YEARS = 7;

    // =========================================================================
    // User history
    // =========================================================================

    /**
     * Returns paginated outbound transfer history for a given customer.
     *
     * @param customerId      the customer whose individual wallet is queried
     * @param filter          time/status/sort/page filter
     * @param groupByDest     if true, also builds destination group summaries
     */
    public TransactionHistoryResponse getUserHistory(
            String customerId,
            TransactionHistoryFilter filter,
            boolean groupByDest) {

        IndividualWallet wallet = individualWalletRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new WalletNotFoundException(
                        "No wallet found for customer: " + customerId));

        LocalDateTime[] window = resolveWindow(filter);
        LocalDateTime from = window[0];
        LocalDateTime to   = window[1];

        Specification<WalletTransfer> spec = WalletTransferSpecification.userHistory(
                wallet.getWalletId(), from, to, filter.getStatuses());

        Page<WalletTransfer> page = walletTransferRepository.findAll(
                spec, buildPageable(filter));

        List<DestinationGroupSummary> groups = groupByDest
                ? buildGroupSummariesForWallet(wallet.getWalletId(), from, to)
                : List.of();

        return buildResponse(page, from, to, filter, groups);
    }

    // =========================================================================
    // Org history
    // =========================================================================

    /**
     * Returns paginated transfer history for all wallets under an org.
     * If divisionId is supplied, only that division's wallet is included.
     *
     * @param orgId          organisation identifier
     * @param divisionId     optional — restricts to a specific division wallet
     * @param fromId         optional — filter where source matches this ID
     * @param toId           optional — filter where destination matches this ID
     * @param filter         time/status/sort/page filter
     * @param groupByDest    if true, also builds destination group summaries
     */
    public TransactionHistoryResponse getOrgHistory(
            String orgId,
            String divisionId,
            String fromId,
            String toId,
            TransactionHistoryFilter filter,
            boolean groupByDest) {

        List<EnterpriseWallet> wallets = resolveOrgWallets(orgId, divisionId);
        List<String> walletIds = wallets.stream()
                .map(EnterpriseWallet::getWalletId)
                .collect(Collectors.toList());

        if (walletIds.isEmpty()) {
            throw new WalletNotFoundException("No wallets found for org: " + orgId
                    + (divisionId != null ? ", division: " + divisionId : ""));
        }

        LocalDateTime[] window = resolveWindow(filter);
        LocalDateTime from = window[0];
        LocalDateTime to   = window[1];

        Specification<WalletTransfer> spec = WalletTransferSpecification.orgHistory(
                walletIds, fromId, toId, from, to, filter.getStatuses());

        Page<WalletTransfer> page = walletTransferRepository.findAll(
                spec, buildPageable(filter));

        List<DestinationGroupSummary> groups = groupByDest
                ? buildGroupSummariesForOrg(walletIds, from, to)
                : List.of();

        return buildResponse(page, from, to, filter, groups);
    }

    // =========================================================================
    // Between two accounts / wallets
    // =========================================================================

    /**
     * Returns all transfers from {@code fromId} to {@code toId}, paginated.
     * IDs can be wallet IDs or bank account numbers — the spec handles both.
     */
    public TransactionHistoryResponse getBetweenHistory(
            String fromId,
            String toId,
            TransactionHistoryFilter filter) {

        LocalDateTime[] window = resolveWindow(filter);
        LocalDateTime from = window[0];
        LocalDateTime to   = window[1];

        Specification<WalletTransfer> spec = WalletTransferSpecification.between(fromId, toId)
                .and(WalletTransferSpecification.withinWindow(from, to));

        if (filter.getStatuses() != null && !filter.getStatuses().isEmpty()) {
            spec = spec.and(WalletTransferSpecification.hasStatuses(filter.getStatuses()));
        }

        Page<WalletTransfer> page = walletTransferRepository.findAll(
                spec, buildPageable(filter));

        return buildResponse(page, from, to, filter, List.of());
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Resolves the time window from the filter, enforcing the 7-year cap. */
    private LocalDateTime[] resolveWindow(TransactionHistoryFilter filter) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime hardFloor = now.minusYears(MAX_YEARS);

        LocalDateTime to = (filter.getPeriod() == TimePeriod.CUSTOM && filter.getToDate() != null)
                ? filter.getToDate()
                : now;

        LocalDateTime from;
        if (filter.getPeriod() == TimePeriod.CUSTOM) {
            from = (filter.getFromDate() != null) ? filter.getFromDate() : hardFloor;
        } else {
            from = filter.getPeriod().startDateTime();
        }

        // Never exceed 7-year hard limit
        if (from.isBefore(hardFloor)) from = hardFloor;

        return new LocalDateTime[]{from, to};
    }

    private PageRequest buildPageable(TransactionHistoryFilter filter) {
        Sort sort = switch (filter.getSortOrder()) {
            case DATE_ASC    -> Sort.by("initiatedAt").ascending();
            case AMOUNT_DESC -> Sort.by("amount").descending();
            case AMOUNT_ASC  -> Sort.by("amount").ascending();
            default          -> Sort.by("initiatedAt").descending();  // DATE_DESC
        };
        return PageRequest.of(filter.getPage(), filter.getSize(), sort);
    }

    private List<EnterpriseWallet> resolveOrgWallets(String orgId, String divisionId) {
        if (divisionId != null && !divisionId.isBlank()) {
            return enterpriseWalletRepository
                    .findByOrgIdAndDivisionId(orgId, divisionId)
                    .map(List::of)
                    .orElse(List.of());
        }
        return enterpriseWalletRepository.findByOrgId(orgId);
    }

    private List<DestinationGroupSummary> buildGroupSummariesForWallet(
            String walletId, LocalDateTime from, LocalDateTime to) {

        List<DestinationGroupSummary> result = new ArrayList<>();

        // Destination wallets
        walletTransferRepository.groupByDestinationWallet(walletId, from, to)
                .forEach(row -> result.add(DestinationGroupSummary.builder()
                        .destinationId((String) row[0])
                        .destinationType("WALLET")
                        .destinationName(safeStr(row[2]))
                        .totalAmount(safeDec(row[3]))
                        .totalNetAmount(safeDec(row[4]))
                        .transactionCount(safeLong(row[5]))
                        .completedAmount(safeDec(row[6]))
                        .completedCount(safeLong(row[7]))
                        .build()));

        // Destination bank accounts
        walletTransferRepository.groupByDestinationAccount(walletId, from, to)
                .forEach(row -> result.add(DestinationGroupSummary.builder()
                        .destinationId((String) row[0])
                        .destinationType("BANK_ACCOUNT")
                        .destinationName(safeStr(row[1]))
                        .totalAmount(safeDec(row[3]))
                        .totalNetAmount(safeDec(row[4]))
                        .transactionCount(safeLong(row[5]))
                        .completedAmount(safeDec(row[6]))
                        .completedCount(safeLong(row[7]))
                        .build()));

        return result;
    }

    private List<DestinationGroupSummary> buildGroupSummariesForOrg(
            List<String> walletIds, LocalDateTime from, LocalDateTime to) {

        List<DestinationGroupSummary> result = new ArrayList<>();

        walletTransferRepository.groupByDestinationForOrg(walletIds, from, to)
                .forEach(row -> {
                    boolean isWallet = row[0] != null;
                    result.add(DestinationGroupSummary.builder()
                            .destinationId(isWallet ? (String) row[0] : (String) row[2])
                            .destinationType(isWallet ? "WALLET" : "BANK_ACCOUNT")
                            .destinationName(safeStr(row[3]))
                            .totalAmount(safeDec(row[4]))
                            .totalNetAmount(safeDec(row[5]))
                            .transactionCount(safeLong(row[6]))
                            .completedAmount(safeDec(row[7]))
                            .completedCount(safeLong(row[8]))
                            .build());
                });

        return result;
    }

    private TransactionHistoryResponse buildResponse(
            Page<WalletTransfer> page,
            LocalDateTime from,
            LocalDateTime to,
            TransactionHistoryFilter filter,
            List<DestinationGroupSummary> groups) {

        List<TransactionHistoryItem> items = page.getContent().stream()
                .map(this::toItem)
                .collect(Collectors.toList());

        // Compute summary stats over this page's content
        BigDecimal totalSpent    = BigDecimal.ZERO;
        BigDecimal totalReceived = BigDecimal.ZERO;
        long successCount = 0, failedCount = 0, pendingCount = 0;

        for (WalletTransfer t : page.getContent()) {
            if (t.getStatus() == WalletTransferStatus.COMPLETED) {
                successCount++;
                totalSpent = totalSpent.add(t.getAmount());
            } else if (t.getStatus() == WalletTransferStatus.FAILED
                    || t.getStatus() == WalletTransferStatus.REVERSED) {
                failedCount++;
            } else {
                pendingCount++;
            }
        }

        return TransactionHistoryResponse.builder()
                .page(filter.getPage())
                .size(filter.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .totalAmountSpent(totalSpent)
                .totalAmountReceived(totalReceived)
                .successCount(successCount)
                .failedCount(failedCount)
                .pendingCount(pendingCount)
                .windowStart(from)
                .windowEnd(to)
                .transactions(items)
                .destinationSummaries(groups)
                .build();
    }

    private TransactionHistoryItem toItem(WalletTransfer t) {
        return TransactionHistoryItem.builder()
                .transferId(t.getTransferId())
                .transferType(t.getTransferType())
                .status(t.getStatus())
                .sourceWalletId(t.getSourceWalletId())
                .sourceWalletType(t.getSourceWalletType())
                .sourceAccountNumber(t.getSourceAccountNumber())
                .sourceAccountName(t.getSourceAccountName())
                .destinationWalletId(t.getDestinationWalletId())
                .destinationWalletType(t.getDestinationWalletType())
                .destinationAccountNumber(t.getDestinationAccountNumber())
                .destinationAccountName(t.getDestinationAccountName())
                .amount(t.getAmount())
                .fees(t.getFees())
                .netAmount(t.getNetAmount())
                .currency(t.getCurrency())
                .description(t.getDescription())
                .failureReason(t.getFailureReason())
                .bankReference(t.getBankReference())
                .initiatedAt(t.getInitiatedAt())
                .updatedAt(t.getUpdatedAt())
                .build();
    }

    // ── Null-safe converters for native query Object[] rows ───────────────────

    private String safeStr(Object o) {
        return o == null ? null : o.toString();
    }

    private BigDecimal safeDec(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        return new BigDecimal(o.toString());
    }

    private long safeLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }
}

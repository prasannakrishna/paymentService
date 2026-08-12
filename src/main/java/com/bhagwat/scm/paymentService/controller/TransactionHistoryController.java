package com.bhagwat.scm.paymentService.controller;

import com.bhagwat.scm.paymentService.dto.history.TransactionHistoryFilter;
import com.bhagwat.scm.paymentService.dto.history.TransactionHistoryResponse;
import com.bhagwat.scm.paymentService.service.history.TransactionHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Transaction history analytics endpoints.
 *
 * ┌──────────────────────────────────────────────────────────────────────────────────┐
 * │  GET  /api/v1/history/user/{customerId}          Individual spending history     │
 * │  GET  /api/v1/history/org/{orgId}                Org-level transfer history      │
 * │  GET  /api/v1/history/between?fromId=&toId=      Bilateral transfer history      │
 * └──────────────────────────────────────────────────────────────────────────────────┘
 *
 * Common query parameters (via {@link TransactionHistoryFilter}):
 *   period        = LAST_MONTH | LAST_3_MONTHS | LAST_6_MONTHS | LAST_YEAR |
 *                   LAST_2_YEARS | LAST_3_YEARS | LAST_5_YEARS | LAST_7_YEARS | CUSTOM
 *   fromDate      = ISO datetime — used only when period=CUSTOM
 *   toDate        = ISO datetime — used only when period=CUSTOM
 *   statuses      = comma-separated WalletTransferStatus values
 *   sortOrder     = DATE_DESC | DATE_ASC | AMOUNT_DESC | AMOUNT_ASC
 *   page          = 0-based page number (default 0)
 *   size          = page size (default 20, max 100)
 *
 * Extra parameters per endpoint:
 *   /user/{customerId}   groupByDest=true  — includes destination group summary
 *   /org/{orgId}         divisionId=       — restricts to a specific division wallet
 *                        fromId=           — filter by source account/wallet
 *                        toId=             — filter by destination account/wallet
 *                        groupByDest=true  — includes destination group summary
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/history")
@RequiredArgsConstructor
public class TransactionHistoryController {

    private final TransactionHistoryService historyService;

    /**
     * Spending history for an individual customer.
     *
     * Example:
     *   GET /api/v1/history/user/CUST-001?period=LAST_3_MONTHS&groupByDest=true&sortOrder=AMOUNT_DESC
     */
    @GetMapping("/user/{customerId}")
    public ResponseEntity<TransactionHistoryResponse> getUserHistory(
            @PathVariable String customerId,
            @ModelAttribute TransactionHistoryFilter filter,
            @RequestParam(defaultValue = "false") boolean groupByDest) {

        log.info("User history request: customerId={} period={} statuses={} page={} size={}",
                customerId, filter.getPeriod(), filter.getStatuses(),
                filter.getPage(), filter.getSize());

        return ResponseEntity.ok(historyService.getUserHistory(customerId, filter, groupByDest));
    }

    /**
     * Transfer history for all wallets under an organisation.
     * Optionally filtered to a specific division via {@code divisionId}.
     *
     * Example:
     *   GET /api/v1/history/org/ORG-001?divisionId=DIV-SALES&period=LAST_YEAR&groupByDest=true
     *   GET /api/v1/history/org/ORG-001?fromId=ACC123&toId=ACC456&statuses=COMPLETED
     */
    @GetMapping("/org/{orgId}")
    public ResponseEntity<TransactionHistoryResponse> getOrgHistory(
            @PathVariable String orgId,
            @RequestParam(required = false) String divisionId,
            @RequestParam(required = false) String fromId,
            @RequestParam(required = false) String toId,
            @ModelAttribute TransactionHistoryFilter filter,
            @RequestParam(defaultValue = "false") boolean groupByDest) {

        log.info("Org history request: orgId={} divisionId={} fromId={} toId={} period={}",
                orgId, divisionId, fromId, toId, filter.getPeriod());

        return ResponseEntity.ok(
                historyService.getOrgHistory(orgId, divisionId, fromId, toId, filter, groupByDest));
    }

    /**
     * All transfers between two specific parties, newest first.
     * {@code fromId} and {@code toId} can each be a wallet ID or a bank account number.
     *
     * Example:
     *   GET /api/v1/history/between?fromId=WALLET-001&toId=WALLET-002&period=LAST_6_MONTHS
     *   GET /api/v1/history/between?fromId=9876543210&toId=1234567890&statuses=COMPLETED,FAILED
     */
    @GetMapping("/between")
    public ResponseEntity<TransactionHistoryResponse> getBetweenHistory(
            @RequestParam String fromId,
            @RequestParam String toId,
            @ModelAttribute TransactionHistoryFilter filter) {

        log.info("Between history request: fromId={} toId={} period={}", fromId, toId, filter.getPeriod());

        return ResponseEntity.ok(historyService.getBetweenHistory(fromId, toId, filter));
    }
}

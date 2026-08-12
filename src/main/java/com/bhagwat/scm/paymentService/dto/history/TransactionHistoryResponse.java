package com.bhagwat.scm.paymentService.dto.history;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Paginated history response with summary stats and optional destination grouping.
 */
@Value
@Builder
public class TransactionHistoryResponse {

    // ── Pagination metadata ────────────────────────────────────────────────────
    int page;
    int size;
    long totalElements;
    int totalPages;

    // ── Summary stats for the filtered window ─────────────────────────────────
    BigDecimal totalAmountSpent;
    BigDecimal totalAmountReceived;
    long successCount;
    long failedCount;
    long pendingCount;

    LocalDateTime windowStart;
    LocalDateTime windowEnd;

    // ── Transaction rows ──────────────────────────────────────────────────────
    List<TransactionHistoryItem> transactions;

    // ── Destination group summary (populated when groupByDestination=true) ────
    List<DestinationGroupSummary> destinationSummaries;
}

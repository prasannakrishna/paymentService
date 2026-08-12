package com.bhagwat.scm.paymentService.dto.history;

import com.bhagwat.scm.paymentService.common.TimePeriod;
import com.bhagwat.scm.paymentService.common.TransactionSortOrder;
import com.bhagwat.scm.paymentService.common.WalletTransferStatus;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Bindable via @ModelAttribute on all history endpoints.
 *
 * Usage:
 *   GET /api/v1/history/user/{id}?period=LAST_3_MONTHS&statuses=COMPLETED,FAILED&sortOrder=AMOUNT_DESC&page=0&size=20
 *   GET /api/v1/history/user/{id}?period=CUSTOM&fromDate=2024-01-01T00:00:00&toDate=2024-12-31T23:59:59
 */
@Data
public class TransactionHistoryFilter {

    /** Predefined window. Defaults to LAST_3_MONTHS when not supplied. */
    private TimePeriod period = TimePeriod.LAST_3_MONTHS;

    /** Used only when period=CUSTOM. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime fromDate;

    /** Used only when period=CUSTOM. Defaults to now when omitted. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime toDate;

    /** Filter by one or more statuses. Empty = all statuses. */
    private List<WalletTransferStatus> statuses;

    private TransactionSortOrder sortOrder = TransactionSortOrder.DATE_DESC;

    private int page = 0;
    private int size = 20;
}

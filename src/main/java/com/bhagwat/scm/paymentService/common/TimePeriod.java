package com.bhagwat.scm.paymentService.common;

import java.time.LocalDateTime;

/**
 * Predefined time windows for transaction history queries.
 * CUSTOM means the caller supplies explicit fromDate / toDate.
 * All periods are bounded to a maximum of 7 years.
 */
public enum TimePeriod {

    LAST_MONTH(1),
    LAST_3_MONTHS(3),
    LAST_6_MONTHS(6),
    LAST_YEAR(12),
    LAST_2_YEARS(24),
    LAST_3_YEARS(36),
    LAST_5_YEARS(60),
    LAST_7_YEARS(84),
    CUSTOM(0);

    private final int months;

    TimePeriod(int months) {
        this.months = months;
    }

    /** Returns the start of the window from now; returns null for CUSTOM. */
    public LocalDateTime startDateTime() {
        if (this == CUSTOM) return null;
        return LocalDateTime.now().minusMonths(months);
    }
}

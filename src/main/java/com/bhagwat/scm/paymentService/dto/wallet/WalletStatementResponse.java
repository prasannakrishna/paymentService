package com.bhagwat.scm.paymentService.dto.wallet;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** Paginated wallet statement with current balance. */
@Data
@Builder
public class WalletStatementResponse {
    private String walletId;
    private BigDecimal currentBalance;
    private String currency;
    private List<WalletTransactionDto> transactions;
    private int page;
    private int pageSize;
    private long totalElements;
    private int totalPages;
}

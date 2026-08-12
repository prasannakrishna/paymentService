package com.bhagwat.scm.paymentService.dto.history;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * One row in the "group by destination" view.
 * Shows how much a source (user/org) has paid to each counterparty.
 */
@Value
@Builder
public class DestinationGroupSummary {

    /** Wallet ID if destination is a wallet, or account number if bank account. */
    String destinationId;

    /** Human-readable name of the destination account or wallet. */
    String destinationName;

    /** Whether destination is a wallet or a bank account. */
    String destinationType;   // "WALLET" | "BANK_ACCOUNT"

    /** Total gross amount sent to this destination. */
    BigDecimal totalAmount;

    /** Total net amount (after fees) received by destination. */
    BigDecimal totalNetAmount;

    /** Number of individual transfers. */
    long transactionCount;

    /** Sum of amounts for COMPLETED transfers only. */
    BigDecimal completedAmount;

    /** Number of COMPLETED transfers. */
    long completedCount;
}

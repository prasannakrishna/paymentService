package com.bhagwat.scm.paymentService.streaming.event;

import com.bhagwat.scm.paymentService.common.TransferType;
import com.bhagwat.scm.paymentService.common.WalletTransferStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Kafka event emitted every time a payment reaches a terminal SUCCESS state.
 *
 * Sources:
 *   - WalletWebhookHandler: A2W credit, W2A/A2A payout TRANSFER_SUCCESS, W2W completion
 *   - PaymentGatewayService: standalone Cashfree PG payment success
 *
 * Routing key (Kafka message key) = sourceId (customerId or orgId).
 * This ensures all events for the same payer land on the same partition,
 * preserving per-payer ordering in the Kafka Streams topology.
 *
 * sourceType values:
 *   INDIVIDUAL    — event originated from an IndividualWallet (→ customer table)
 *   ENTERPRISE    — event originated from an EnterpriseWallet  (→ org table)
 *   STANDALONE_PG — direct Cashfree PG payment (no wallet)    (→ customer table)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaymentSuccessEvent {

    /** UUID for consumer-side deduplication. */
    private String eventId;

    // ── Source type (drives routing) ──────────────────────────────────────────
    /** "INDIVIDUAL" | "ENTERPRISE" | "STANDALONE_PG" */
    private String sourceType;

    // ── Individual fields (set when sourceType = INDIVIDUAL | STANDALONE_PG) ──
    private String customerId;
    private String sourceWalletId;

    // ── Enterprise fields (set when sourceType = ENTERPRISE) ─────────────────
    private String orgId;
    private String divisionId;
    private String orgWalletId;

    // ── Transfer references ───────────────────────────────────────────────────
    /** WalletTransfer.transferId or PaymentTransaction.transactionId. */
    private String transferId;
    private String pgOrderId;
    private String cfPaymentId;
    private String bankReference;
    private TransferType transferType;
    private WalletTransferStatus status;

    // ── Amounts ───────────────────────────────────────────────────────────────
    private BigDecimal amount;
    private BigDecimal fees;
    private BigDecimal netAmount;
    private String currency;

    // ── Counterparty (destination) ────────────────────────────────────────────
    private String counterpartyId;
    private String counterpartyName;
    /** "WALLET" or "BANK_ACCOUNT" */
    private String counterpartyType;

    private String description;
    private LocalDateTime succeededAt;
}

package com.bhagwat.scm.paymentService.streaming.producer;

import com.bhagwat.scm.paymentService.common.WalletTransferStatus;
import com.bhagwat.scm.paymentService.common.WalletType;
import com.bhagwat.scm.paymentService.entity.PaymentTransaction;
import com.bhagwat.scm.paymentService.entity.wallet.EnterpriseWallet;
import com.bhagwat.scm.paymentService.entity.wallet.IndividualWallet;
import com.bhagwat.scm.paymentService.entity.wallet.WalletTransfer;
import com.bhagwat.scm.paymentService.repository.EnterpriseWalletRepository;
import com.bhagwat.scm.paymentService.repository.IndividualWalletRepository;
import com.bhagwat.scm.kafka.producer.KafkaMessageProducer;
import com.bhagwat.scm.paymentService.streaming.event.PaymentSuccessEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Publishes {@link PaymentSuccessEvent} to the Kafka source topic.
 *
 * The Kafka Streams topology then routes each event to either:
 *   payment.customer.transactions  — for INDIVIDUAL and STANDALONE_PG events
 *   payment.org.transactions       — for ENTERPRISE events
 *
 * Message key = sourceId (customerId or orgId).
 * All events from the same payer go to the same Kafka partition, preserving order.
 *
 * Call sites:
 *   - WalletWebhookHandler  — wallet-based payments (A2W, A2A, W2A, W2W)
 *   - PaymentGatewayService — standalone Cashfree PG payments
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {

    private final KafkaMessageProducer kafkaProducer;
    private final IndividualWalletRepository individualWalletRepository;
    private final EnterpriseWalletRepository enterpriseWalletRepository;

    @Value("${payment.topics.success:payment.success.events}")
    private String successTopic;

    // =========================================================================
    // Wallet-based payments
    // =========================================================================

    /**
     * Publishes a success event for a completed WalletTransfer.
     * Determines sourceType by looking up the source wallet.
     *
     * @param transfer the completed WalletTransfer
     */
    public void publishWalletPaymentSuccess(WalletTransfer transfer) {
        try {
            PaymentSuccessEvent event = buildWalletEvent(transfer);
            String routingKey = resolveRoutingKey(event);
            sendAsync(routingKey, event);
            log.info("PaymentSuccessEvent published: transferId={} sourceType={} key={}",
                    transfer.getTransferId(), event.getSourceType(), routingKey);
        } catch (Exception e) {
            // Non-critical: the primary transaction already committed.
            // The purge job or reconciliation process can replay missed events.
            log.error("Failed to publish PaymentSuccessEvent for transferId={}: {}",
                    transfer.getTransferId(), e.getMessage(), e);
        }
    }

    // =========================================================================
    // Standalone PG payments
    // =========================================================================

    /**
     * Publishes a success event for a standalone Cashfree PG payment.
     * Always routed to the INDIVIDUAL / customer table.
     *
     * @param txn the succeeded PaymentTransaction
     */
    public void publishStandalonePaymentSuccess(PaymentTransaction txn) {
        try {
            PaymentSuccessEvent event = buildStandaloneEvent(txn);
            String routingKey = txn.getDebitorId() != null ? txn.getDebitorId() : txn.getTransactionId();
            sendAsync(routingKey, event);
            log.info("Standalone PaymentSuccessEvent published: transactionId={} debitorId={}",
                    txn.getTransactionId(), txn.getDebitorId());
        } catch (Exception e) {
            log.error("Failed to publish standalone PaymentSuccessEvent for transactionId={}: {}",
                    txn.getTransactionId(), e.getMessage(), e);
        }
    }

    // =========================================================================
    // Builders
    // =========================================================================

    private PaymentSuccessEvent buildWalletEvent(WalletTransfer transfer) {
        PaymentSuccessEvent.PaymentSuccessEventBuilder b = PaymentSuccessEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .transferId(transfer.getTransferId())
                .transferType(transfer.getTransferType())
                .pgOrderId(transfer.getPgOrderId())
                .cfPaymentId(transfer.getCfPaymentId())
                .bankReference(transfer.getBankReference())
                .status(WalletTransferStatus.COMPLETED)
                .amount(transfer.getAmount())
                .fees(transfer.getFees())
                .netAmount(transfer.getNetAmount())
                .currency(transfer.getCurrency())
                .description(transfer.getDescription())
                .succeededAt(LocalDateTime.now());

        // Destination counterparty
        if (transfer.getDestinationWalletId() != null) {
            b.counterpartyId(transfer.getDestinationWalletId())
             .counterpartyName(transfer.getDestinationAccountName())
             .counterpartyType("WALLET");
        } else {
            b.counterpartyId(transfer.getDestinationAccountNumber())
             .counterpartyName(transfer.getDestinationAccountName())
             .counterpartyType("BANK_ACCOUNT");
        }

        // Source type resolution
        if (transfer.getSourceWalletId() != null) {
            if (transfer.getSourceWalletType() == WalletType.INDIVIDUAL) {
                enrichIndividualSource(b, transfer.getSourceWalletId());
            } else if (transfer.getSourceWalletType() == WalletType.ENTERPRISE) {
                enrichEnterpriseSource(b, transfer.getSourceWalletId());
            }
        } else {
            // ACCOUNT_TO_* transfers — no source wallet; treat as individual by default
            b.sourceType("INDIVIDUAL")
             .customerId(transfer.getSourceAccountNumber());
        }

        return b.build();
    }

    private PaymentSuccessEvent buildStandaloneEvent(PaymentTransaction txn) {
        return PaymentSuccessEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .sourceType("STANDALONE_PG")
                .customerId(txn.getDebitorId())
                .transferId(txn.getTransactionId())
                .pgOrderId(txn.getOrderId())
                .cfPaymentId(txn.getCfPaymentId())
                .bankReference(txn.getBankReference())
                .status(WalletTransferStatus.COMPLETED)
                .amount(txn.getAmount())
                .fees(java.math.BigDecimal.ZERO)  // standalone PG does not track platform fees
                .netAmount(txn.getAmount())
                .currency(txn.getCurrency())
                .counterpartyId(txn.getCreditorAccount())
                .counterpartyName(txn.getCreditorName())
                .counterpartyType("BANK_ACCOUNT")
                .succeededAt(LocalDateTime.now())
                .build();
    }

    private void enrichIndividualSource(
            PaymentSuccessEvent.PaymentSuccessEventBuilder b, String walletId) {
        b.sourceType("INDIVIDUAL").sourceWalletId(walletId);
        individualWalletRepository.findById(walletId)
                .ifPresentOrElse(
                        w -> b.customerId(w.getCustomerId()),
                        () -> {
                            log.warn("IndividualWallet not found for walletId={}", walletId);
                            b.customerId(walletId);  // fallback to walletId
                        });
    }

    private void enrichEnterpriseSource(
            PaymentSuccessEvent.PaymentSuccessEventBuilder b, String walletId) {
        b.sourceType("ENTERPRISE").orgWalletId(walletId);
        enterpriseWalletRepository.findById(walletId)
                .ifPresentOrElse(
                        w -> b.orgId(w.getOrgId()).divisionId(w.getDivisionId()),
                        () -> {
                            log.warn("EnterpriseWallet not found for walletId={}", walletId);
                            b.orgId(walletId);
                        });
    }

    private String resolveRoutingKey(PaymentSuccessEvent event) {
        if (event.getCustomerId() != null) return event.getCustomerId();
        if (event.getOrgId()      != null) return event.getOrgId();
        return event.getTransferId();
    }

    private void sendAsync(String key, PaymentSuccessEvent event) {
        CompletableFuture<SendResult<String, String>> future =
                kafkaProducer.sendAsync(successTopic, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Kafka send failed for eventId={} key={}: {}",
                        event.getEventId(), key, ex.getMessage());
            } else {
                log.debug("Kafka send success: eventId={} partition={} offset={}",
                        event.getEventId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}

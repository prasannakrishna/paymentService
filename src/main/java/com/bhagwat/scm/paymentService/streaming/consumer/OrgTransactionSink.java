package com.bhagwat.scm.paymentService.streaming.consumer;

import com.bhagwat.scm.paymentService.entity.OrgWalletTransaction;
import com.bhagwat.scm.paymentService.repository.OrgWalletTransactionRepository;
import com.bhagwat.scm.paymentService.streaming.event.PaymentSuccessEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Kafka Streams sink — persists enterprise org/division payment records.
 *
 * Consumes from: {@code payment.org.transactions}
 * Written to:    {@code org_wallet_transactions} table
 *
 * Idempotency:
 *   Checks {@code transferId} uniqueness before inserting.
 *   Safe to re-process on Kafka offset replay.
 *
 * Group ID: {@code payment-org-sink}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrgTransactionSink {

    private final OrgWalletTransactionRepository repository;

    @KafkaListener(
            topics      = "${payment.topics.org-tx:payment.org.transactions}",
            groupId     = "payment-org-sink",
            concurrency = "${payment.sink.org.concurrency:3}"
    )
    @Transactional
    public void onOrgPayment(
            @Payload PaymentSuccessEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("OrgTransactionSink: eventId={} transferId={} orgId={} partition={} offset={}",
                event.getEventId(), event.getTransferId(), event.getOrgId(), partition, offset);

        if (repository.existsByTransferId(event.getTransferId())) {
            log.info("Duplicate OrgWalletTransaction skipped: transferId={}", event.getTransferId());
            return;
        }

        OrgWalletTransaction txn = OrgWalletTransaction.builder()
                .txnId(UUID.randomUUID().toString())
                .orgId(event.getOrgId())
                .divisionId(event.getDivisionId())
                .sourceWalletId(event.getOrgWalletId())
                .transferId(event.getTransferId())
                .pgOrderId(event.getPgOrderId())
                .cfPaymentId(event.getCfPaymentId())
                .bankReference(event.getBankReference())
                .transferType(event.getTransferType())
                .amount(event.getAmount())
                .fees(event.getFees() != null ? event.getFees() : java.math.BigDecimal.ZERO)
                .netAmount(event.getNetAmount())
                .currency(event.getCurrency() != null ? event.getCurrency() : "INR")
                .counterpartyId(event.getCounterpartyId())
                .counterpartyName(event.getCounterpartyName())
                .counterpartyType(event.getCounterpartyType())
                .status(event.getStatus())
                .txnDate(event.getSucceededAt())
                .description(event.getDescription())
                .build();

        repository.save(txn);
        log.info("OrgWalletTransaction saved: txnId={} orgId={} divisionId={} amount={}",
                txn.getTxnId(), txn.getOrgId(), txn.getDivisionId(), txn.getAmount());
    }
}

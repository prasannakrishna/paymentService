package com.bhagwat.scm.paymentService.streaming.consumer;

import com.bhagwat.scm.paymentService.entity.CustomerWalletTransaction;
import com.bhagwat.scm.paymentService.repository.CustomerWalletTransactionRepository;
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
 * Kafka Streams sink — persists individual customer payment records.
 *
 * Consumes from: {@code payment.customer.transactions}
 * Written to:    {@code customer_wallet_transactions} table
 *
 * Idempotency:
 *   Checks {@code transferId} uniqueness before inserting.
 *   Safe to re-process on Kafka offset replay (at-least-once delivery).
 *
 * Group ID: {@code payment-customer-sink}
 *   Separate from the Kafka Streams application ID so this listener
 *   runs independently and can be scaled separately.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerTransactionSink {

    private final CustomerWalletTransactionRepository repository;

    @KafkaListener(
            topics      = "${payment.topics.customer-tx:payment.customer.transactions}",
            groupId     = "payment-customer-sink",
            concurrency = "${payment.sink.customer.concurrency:3}"
    )
    @Transactional
    public void onCustomerPayment(
            @Payload PaymentSuccessEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("CustomerTransactionSink: eventId={} transferId={} partition={} offset={}",
                event.getEventId(), event.getTransferId(), partition, offset);

        // Idempotency guard — re-delivery protection
        if (repository.existsByTransferId(event.getTransferId())) {
            log.info("Duplicate CustomerWalletTransaction skipped: transferId={}", event.getTransferId());
            return;
        }

        CustomerWalletTransaction txn = CustomerWalletTransaction.builder()
                .txnId(UUID.randomUUID().toString())
                .customerId(event.getCustomerId())
                .sourceWalletId(event.getSourceWalletId())
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
        log.info("CustomerWalletTransaction saved: txnId={} customerId={} amount={}",
                txn.getTxnId(), txn.getCustomerId(), txn.getAmount());
    }
}

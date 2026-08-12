package com.bhagwat.scm.paymentService.service;

import com.bhagwat.scm.paymentService.common.TransferType;
import com.bhagwat.scm.paymentService.common.WalletTransferStatus;
import com.bhagwat.scm.paymentService.dto.*;
import com.bhagwat.scm.paymentService.entity.CustomerWallet;
import com.bhagwat.scm.paymentService.entity.OrgWallet;
import com.bhagwat.scm.paymentService.repository.CustomerWalletRepository;
import com.bhagwat.scm.paymentService.repository.OrgWalletRepository;
import com.bhagwat.scm.paymentService.rest.CashfreeService;
import com.bhagwat.scm.paymentService.streaming.event.PaymentSuccessEvent;
import com.bhagwat.scm.paymentService.streaming.producer.PaymentEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Legacy payment service — kept for backwards compatibility with older controllers.
 *
 * Transaction recording is now event-driven:
 *   makePaymentToOrder() publishes a PaymentSuccessEvent to Kafka.
 *   The Kafka Streams topology routes it to the correct table
 *   (customer_wallet_transactions or org_wallet_transactions) based on payer type.
 *
 * @deprecated New payment flows should use PaymentGatewayService (standalone PG)
 *             or WalletTransferOrchestrator (wallet-based transfers).
 */
@Deprecated
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final CashfreeService           cashfreeService;
    private final OrgWalletRepository       orgWalletRepository;
    private final CustomerWalletRepository  customerWalletRepository;
    private final KafkaTemplate<String, PaymentSuccessEvent> kafkaTemplate;

    @Value("${payment.topics.success:payment.success.events}")
    private String successTopic;

    public String addMoneyToWallet(AddMoneyRequest request) {
        CustomerWallet wallet = customerWalletRepository.findByCustomerId(request.getCustomerId())
                .orElseThrow(() -> new RuntimeException("Customer not found"));
        wallet.setTotalBalanceAvailable(wallet.getTotalBalanceAvailable() + request.getAmount());
        customerWalletRepository.save(wallet);
        return "Money added successfully";
    }

    public String withdrawMoneyFromWallet(WithdrawMoneyRequest request) {
        OrgWallet wallet = orgWalletRepository.findByPartyId(request.getPartyId())
                .orElseThrow(() -> new RuntimeException("Organization not found"));
        if (wallet.getTotalBalanceAvailable() < request.getAmount()) {
            throw new RuntimeException("Insufficient funds");
        }
        wallet.setTotalBalanceAvailable(wallet.getTotalBalanceAvailable() - request.getAmount());
        orgWalletRepository.save(wallet);
        return "Money withdrawn successfully";
    }

    /**
     * Makes a payment from a customer wallet to an org wallet.
     * Balance updates are applied synchronously; transaction records are written
     * asynchronously via the Kafka Streams pipeline.
     */
    public String makePaymentToOrder(MakePaymentRequest request) {
        OrgWallet orgWallet = orgWalletRepository.findByPartyId(request.getSellerId())
                .orElseThrow(() -> new RuntimeException("Seller not found"));
        CustomerWallet customerWallet = customerWalletRepository.findByCustomerId(request.getCustomerId())
                .orElseThrow(() -> new RuntimeException("Customer not found"));

        if (customerWallet.getTotalBalanceAvailable() < request.getOrderValue()) {
            throw new RuntimeException("Insufficient funds in customer wallet");
        }

        // Balance updates
        customerWallet.setTotalBalanceAvailable(customerWallet.getTotalBalanceAvailable() - request.getOrderValue());
        customerWalletRepository.save(customerWallet);

        orgWallet.setTotalBalanceAvailable(orgWallet.getTotalBalanceAvailable() + request.getOrderValue());
        orgWalletRepository.save(orgWallet);

        // Publish event — Kafka Streams routes to customer_wallet_transactions
        String transferId = "LEGACY-" + request.getOrderId();
        BigDecimal amount = BigDecimal.valueOf(request.getOrderValue());

        PaymentSuccessEvent event = PaymentSuccessEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .sourceType("INDIVIDUAL")
                .customerId(request.getCustomerId() != null ? request.getCustomerId().toString() : null)
                .transferId(transferId)
                .transferType(TransferType.WALLET_TO_WALLET)
                .status(WalletTransferStatus.COMPLETED)
                .amount(amount)
                .fees(BigDecimal.ZERO)
                .netAmount(amount)
                .currency("INR")
                .counterpartyId(request.getSellerId() != null ? request.getSellerId().toString() : null)
                .counterpartyType("WALLET")
                .succeededAt(LocalDateTime.now())
                .build();

        kafkaTemplate.send(successTopic, event.getCustomerId(), event);
        return "Payment successful";
    }

    public PaymentResponseDto processPayment(PaymentRequestDto request) {
        if (request.getSourceAddress() == null || request.getTargetAddress() == null || request.getAmount() <= 0) {
            return new PaymentResponseDto("FAILURE", "Invalid payment details.");
        }
        CashfreePaymentRequest cashfreeRequest = new CashfreePaymentRequest();
        cashfreeRequest.setAmount(request.getAmount());
        cashfreeRequest.setCurrency("INR");
        cashfreeRequest.setOrderId("ORD-" + System.currentTimeMillis());
        return cashfreeService.initiatePayment(cashfreeRequest);
    }

    public PayoutResponseDto processPayout(PayoutRequestDto request) {
        if (request.getTargetAddress() == null || request.getAmount() <= 0) {
            return new PayoutResponseDto("FAILURE", "Invalid payout details.");
        }
        CashfreePayoutRequest cashfreeRequest = new CashfreePayoutRequest();
        cashfreeRequest.setAmount(request.getAmount());
        cashfreeRequest.setAccount(request.getBeneficiaryAccount());
        cashfreeRequest.setIfsc(request.getBeneficiaryIfsc());
        cashfreeRequest.setTransferId("TRF-" + System.currentTimeMillis());
        return cashfreeService.initiatePayout(cashfreeRequest);
    }
}

package com.bhagwat.scm.paymentService.service.wallet;

import com.bhagwat.scm.paymentService.common.*;
import com.bhagwat.scm.paymentService.dto.cashfree.CfCreateOrderRequest;
import com.bhagwat.scm.paymentService.dto.cashfree.CfCreateOrderResponse;
import com.bhagwat.scm.paymentService.dto.cashfree.CfCustomerDetails;
import com.bhagwat.scm.paymentService.dto.cashfree.CfOrderMeta;
import com.bhagwat.scm.paymentService.dto.wallet.AccountToWalletTopupRequest;
import com.bhagwat.scm.paymentService.entity.wallet.WalletTransaction;
import com.bhagwat.scm.paymentService.entity.wallet.WalletTransfer;
import com.bhagwat.scm.paymentService.repository.WalletTransactionRepository;
import com.bhagwat.scm.paymentService.repository.WalletTransferRepository;
import com.bhagwat.scm.paymentService.config.CashfreeProperties;
import com.bhagwat.scm.paymentService.rest.CashfreeGatewayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Collects funds from a bank/card/UPI via Cashfree PG and credits a wallet on success.
 *
 * Flow:
 *   1. Create Cashfree PG order (transferId is used as orderId)
 *   2. Return paymentLink → status = PENDING_PAYMENT
 *   3. User pays → PG webhook arrives at WalletWebhookHandler
 *   4. Credit wallet → status = COMPLETED
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountToWalletTopupService {

    private final CashfreeGatewayClient    gatewayClient;
    private final CashfreeProperties       cashfreeProps;
    private final WalletTransferRepository transferRepo;
    private final WalletTransactionRepository txnRepo;
    private final WalletAuditService       auditService;

    @Transactional
    public WalletTransfer initiate(WalletTransfer transfer, AccountToWalletTopupRequest req) {
        log.info("A2W topup initiated: transferId={} dstWallet={} amount={}",
                transfer.getTransferId(), transfer.getDestinationWalletId(), transfer.getAmount());

        // Create Cashfree PG order — use transferId as the Cashfree orderId
        CfCreateOrderRequest cfOrder = CfCreateOrderRequest.builder()
                .orderId(transfer.getTransferId())
                .orderAmount(transfer.getAmount())
                .orderCurrency(transfer.getCurrency())
                .orderNote(transfer.getDescription())
                .orderExpiryTime(ZonedDateTime.now()
                        .plusMinutes(cashfreeProps.getOrderExpiryMinutes())
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .customerDetails(CfCustomerDetails.builder()
                        .customerId(req.getPayerId())
                        .customerName(req.getPayerName())
                        .customerEmail(req.getPayerEmail())
                        .customerPhone(req.getPayerPhone())
                        .build())
                .orderMeta(CfOrderMeta.builder()
                        .returnUrl(cashfreeProps.getReturnUrl() + "?order_id={order_id}&txn_id=" + transfer.getTransferId())
                        .notifyUrl(cashfreeProps.getWebhookUrl())
                        .build())
                .build();

        CfCreateOrderResponse cfResponse = gatewayClient.createOrder(cfOrder);

        transfer.setPgOrderId(cfResponse.getCfOrderId());
        transfer.setPaymentSessionId(cfResponse.getPaymentSessionId());
        transfer.setPaymentLink(cfResponse.getHostedCheckoutUrl());
        transfer.setStatus(WalletTransferStatus.PENDING_PAYMENT);
        transfer = transferRepo.save(transfer);

        auditService.recordTransferEvent(AuditEventType.TRANSFER_PAYMENT_LINK_CREATED,
                transfer.getTransferId(), WalletTransferStatus.INITIATED.name(),
                WalletTransferStatus.PENDING_PAYMENT.name(),
                "Cashfree PG order created: " + cfResponse.getCfOrderId());

        log.info("A2W PG order created: transferId={} cfOrderId={} link={}",
                transfer.getTransferId(), cfResponse.getCfOrderId(), cfResponse.getHostedCheckoutUrl());
        return transfer;
    }

    /**
     * Called by WalletWebhookHandler after PG webhook confirms payment success.
     * Credits the destination wallet and marks transfer COMPLETED.
     */
    @Transactional
    public WalletTransfer onPaymentSuccess(WalletTransfer transfer,
                                           String cfPaymentId,
                                           WalletBalanceService balanceService) {
        log.info("A2W crediting wallet: transferId={} walletId={} amount={}",
                transfer.getTransferId(), transfer.getDestinationWalletId(), transfer.getAmount());

        BigDecimal balanceBefore = balanceService.credit(
                transfer.getDestinationWalletId(),
                transfer.getDestinationWalletType(),
                transfer.getNetAmount());

        WalletTransaction creditTxn = WalletTransaction.builder()
                .transactionId(UUID.randomUUID().toString())
                .transferId(transfer.getTransferId())
                .walletId(transfer.getDestinationWalletId())
                .walletType(transfer.getDestinationWalletType())
                .transactionType(WalletTransactionType.CREDIT)
                .amount(transfer.getNetAmount())
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceBefore.add(transfer.getNetAmount()))
                .currency(transfer.getCurrency())
                .description("A2W topup credit from payment " + cfPaymentId)
                .build();
        txnRepo.save(creditTxn);

        transfer.setCfPaymentId(cfPaymentId);
        transfer.setStatus(WalletTransferStatus.COMPLETED);
        transfer = transferRepo.save(transfer);

        auditService.record(AuditEventType.BALANCE_CREDITED,
                transfer.getDestinationWalletId(), transfer.getDestinationWalletType(),
                transfer.getTransferId(),
                balanceBefore.toString(), balanceBefore.add(transfer.getNetAmount()).toString(),
                null, "A2W topup credited: " + transfer.getNetAmount());

        log.info("A2W transfer completed: transferId={}", transfer.getTransferId());
        return transfer;
    }
}

package com.bhagwat.scm.paymentService.service.wallet;

import com.bhagwat.scm.paymentService.common.*;
import com.bhagwat.scm.paymentService.config.CashfreeProperties;
import com.bhagwat.scm.paymentService.dto.cashfree.CfCreateOrderRequest;
import com.bhagwat.scm.paymentService.dto.cashfree.CfCreateOrderResponse;
import com.bhagwat.scm.paymentService.dto.cashfree.CfCustomerDetails;
import com.bhagwat.scm.paymentService.dto.cashfree.CfOrderMeta;
import com.bhagwat.scm.paymentService.dto.cashfree.payout.CfPayoutTransferRequest;
import com.bhagwat.scm.paymentService.dto.cashfree.payout.CfPayoutTransferResponse;
import com.bhagwat.scm.paymentService.dto.wallet.AccountToAccountTransferRequest;
import com.bhagwat.scm.paymentService.entity.wallet.WalletTransfer;
import com.bhagwat.scm.paymentService.repository.WalletTransferRepository;
import com.bhagwat.scm.paymentService.rest.CashfreeGatewayClient;
import com.bhagwat.scm.paymentService.rest.CashfreePayoutClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Account-to-account transfer via Cashfree PG (collect) + Cashfree Payout (disburse).
 *
 * Flow:
 *   1. Create Cashfree PG order for collection
 *   2. Return paymentLink → status = PENDING_PAYMENT
 *   3. User pays → PG webhook arrives → WalletWebhookHandler calls onPaymentCollected()
 *   4. Trigger Cashfree Payout to destination account → status = PAYOUT_INITIATED
 *   5. Payout webhook → WalletWebhookHandler calls onPayoutSettled() → status = COMPLETED | REVERSED
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountToAccountTransferService {

    private final CashfreeGatewayClient    gatewayClient;
    private final CashfreePayoutClient     payoutClient;
    private final CashfreeProperties       cashfreeProps;
    private final WalletTransferRepository transferRepo;
    private final WalletAuditService       auditService;

    @Transactional
    public WalletTransfer initiate(WalletTransfer transfer, AccountToAccountTransferRequest req) {
        log.info("A2A transfer initiated: transferId={} amount={}", transfer.getTransferId(), transfer.getAmount());

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
                "A2A PG order: " + cfResponse.getCfOrderId());

        log.info("A2A PG order created: transferId={} cfOrderId={}", transfer.getTransferId(), cfResponse.getCfOrderId());
        return transfer;
    }

    /**
     * Called by WalletWebhookHandler when PG collection succeeds.
     * Triggers the payout leg to the destination account.
     */
    @Transactional
    public WalletTransfer onPaymentCollected(WalletTransfer transfer, String cfPaymentId) {
        log.info("A2A collection confirmed, initiating payout: transferId={}", transfer.getTransferId());

        transfer.setCfPaymentId(cfPaymentId);
        transfer.setStatus(WalletTransferStatus.COLLECTING);
        transferRepo.save(transfer);

        CfPayoutTransferRequest payoutReq = CfPayoutTransferRequest.builder()
                .transferId("PAYOUT-" + transfer.getTransferId())
                .transferAmount(transfer.getNetAmount())
                .transferRemarks(transfer.getDescription())
                .beneficiary(CfPayoutTransferRequest.CfPayoutBeneficiary.builder()
                        .beneficiaryName(transfer.getDestinationAccountName())
                        .bankAccount(CfPayoutTransferRequest.CfPayoutBeneficiary.BankAccount.builder()
                                .accountNumber(transfer.getDestinationAccountNumber())
                                .accountIfsc(transfer.getDestinationIfsc())
                                .build())
                        .build())
                .build();

        try {
            CfPayoutTransferResponse cfPayout = payoutClient.initiateTransfer(payoutReq);
            transfer.setCfPayoutId(cfPayout.getCfTransferId());
            transfer.setStatus(WalletTransferStatus.PAYOUT_INITIATED);
            transfer = transferRepo.save(transfer);
            auditService.recordTransferEvent(AuditEventType.TRANSFER_PAYOUT_INITIATED,
                    transfer.getTransferId(), WalletTransferStatus.COLLECTING.name(),
                    WalletTransferStatus.PAYOUT_INITIATED.name(),
                    "A2A payout initiated: " + cfPayout.getCfTransferId());
        } catch (Exception e) {
            log.error("A2A payout initiation failed: transferId={}", transfer.getTransferId(), e);
            transfer.setStatus(WalletTransferStatus.FAILED);
            transfer.setFailureReason("Payout initiation failed after collection: " + e.getMessage());
            transferRepo.save(transfer);
            auditService.recordTransferEvent(AuditEventType.TRANSFER_FAILED,
                    transfer.getTransferId(), WalletTransferStatus.COLLECTING.name(),
                    WalletTransferStatus.FAILED.name(), e.getMessage());
        }
        return transfer;
    }
}

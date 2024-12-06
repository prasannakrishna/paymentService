package com.bhagwat.scm.paymentService.service;

import com.bhagwat.scm.paymentService.common.TransactionStatus;
import com.bhagwat.scm.paymentService.dto.AddMoneyRequest;
import com.bhagwat.scm.paymentService.dto.MakePaymentRequest;
import com.bhagwat.scm.paymentService.dto.WithdrawMoneyRequest;
import com.bhagwat.scm.paymentService.entity.CustomerWallet;
import com.bhagwat.scm.paymentService.entity.CustomerWalletTransaction;
import com.bhagwat.scm.paymentService.entity.OrgWallet;
import com.bhagwat.scm.paymentService.entity.OrgWalletTransaction;
import com.bhagwat.scm.paymentService.repository.CustomerWalletRepository;
import com.bhagwat.scm.paymentService.repository.CustomerWalletTransactionRepository;
import com.bhagwat.scm.paymentService.repository.OrgWalletRepository;
import com.bhagwat.scm.paymentService.repository.OrgWalletTransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class PaymentService {

    @Autowired
    private OrgWalletRepository orgWalletRepository;

    @Autowired
    private CustomerWalletRepository customerWalletRepository;

    @Autowired
    private CustomerWalletTransactionRepository customerWalletTransactionRepository;

    @Autowired
    private OrgWalletTransactionRepository orgWalletTransactionRepository;

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

    public String makePaymentToOrder(MakePaymentRequest request) {
        OrgWallet orgWallet = orgWalletRepository.findByPartyId(request.getSellerId())
                .orElseThrow(() -> new RuntimeException("Seller not found"));

        CustomerWallet customerWallet = customerWalletRepository.findByCustomerId(request.getCustomerId())
                .orElseThrow(() -> new RuntimeException("Customer not found"));

        if (customerWallet.getTotalBalanceAvailable() < request.getOrderValue()) {
            throw new RuntimeException("Insufficient funds in customer wallet");
        }

        // Update customer wallet
        customerWallet.setTotalBalanceAvailable(customerWallet.getTotalBalanceAvailable() - request.getOrderValue());
        customerWalletRepository.save(customerWallet);

        // Create customer wallet transaction
        CustomerWalletTransaction customerTransaction = new CustomerWalletTransaction();
        customerTransaction.setCustomerId(request.getCustomerId());
        customerTransaction.setOrderValue(request.getOrderValue());
        customerTransaction.setOrderId(request.getOrderId());
        customerTransaction.setDate(LocalDateTime.now());
        customerTransaction.setStatus(TransactionStatus.COMPLETED);
        customerTransaction.setWalletBalanceAmount(customerWallet.getTotalBalanceAvailable());
        customerWalletTransactionRepository.save(customerTransaction);

        // Update organization wallet
        orgWallet.setTotalBalanceAvailable(orgWallet.getTotalBalanceAvailable() + request.getOrderValue());
        orgWalletRepository.save(orgWallet);

        // Create organization wallet transaction
        OrgWalletTransaction orgTransaction = new OrgWalletTransaction();
        orgTransaction.setPartyId(request.getSellerId());
        orgTransaction.setOrderId(request.getOrderId());
        orgTransaction.setAmountCredited(request.getOrderValue());
        orgTransaction.setDate(LocalDateTime.now());
        orgTransaction.setStatus(TransactionStatus.COMPLETED);
        orgWalletTransactionRepository.save(orgTransaction);

        return "Payment successful";
    }
}


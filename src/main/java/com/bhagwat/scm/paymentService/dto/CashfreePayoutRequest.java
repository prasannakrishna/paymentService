package com.bhagwat.scm.paymentService.dto;

import lombok.Data;

@Data
public class CashfreePayoutRequest {
    private String transferId;
    private double amount;
    private String account;
    private String ifsc;
    // Getters and Setters (omitted for brevity)
    public String getTransferId() { return transferId; }
    public void setTransferId(String transferId) { this.transferId = transferId; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }
    public String getIfsc() { return ifsc; }
    public void setIfsc(String ifsc) { this.ifsc = ifsc; }
}

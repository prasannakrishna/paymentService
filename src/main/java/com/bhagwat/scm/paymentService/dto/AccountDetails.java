package com.bhagwat.scm.paymentService.dto;

import lombok.Data;

@Data
public class AccountDetails {
    private String accountNumber;
    private String ifscCode;
    private String bankName;
    // Getters and Setters (omitted for brevity)
    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
    public String getIfscCode() { return ifscCode; }
    public void setIfscCode(String ifscCode) { this.ifscCode = ifscCode; }
    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }
}

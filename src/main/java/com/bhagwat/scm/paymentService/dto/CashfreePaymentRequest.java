package com.bhagwat.scm.paymentService.dto;

import lombok.Data;

@Data
public class CashfreePaymentRequest {
    private String orderId;
    private double amount;
    private String currency;
    // Getters and Setters (omitted for brevity)
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
}

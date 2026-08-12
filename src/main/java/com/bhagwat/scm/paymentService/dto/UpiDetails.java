package com.bhagwat.scm.paymentService.dto;

import lombok.Data;

@Data
public class UpiDetails {
    private String upiId;
    private String qrCodeData;
    // Getters and Setters (omitted for brevity)
    public String getUpiId() { return upiId; }
    public void setUpiId(String upiId) { this.upiId = upiId; }
    public String getQrCodeData() { return qrCodeData; }
    public void setQrCodeData(String qrCodeData) { this.qrCodeData = qrCodeData; }
}

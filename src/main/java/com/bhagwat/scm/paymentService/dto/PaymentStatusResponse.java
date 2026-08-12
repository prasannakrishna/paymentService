package com.bhagwat.scm.paymentService.dto;

import com.bhagwat.scm.paymentService.common.TransactionStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response for {@code GET /api/v1/payment/gateway/status/{transactionId}}.
 * Combines the locally stored transaction data with the latest Cashfree status.
 */
@Data
@Builder
public class PaymentStatusResponse {

    private String transactionId;
    private String orderId;
    private String cfOrderId;
    private TransactionStatus status;
    private String gatewayOrderStatus;
    private String gatewayPaymentStatus;
    private BigDecimal amount;
    private String currency;
    private String debitorId;
    private String creditorId;
    private String bankReference;
    private String failureReason;
    private LocalDateTime initiatedAt;
    private LocalDateTime updatedAt;
    private String message;
}

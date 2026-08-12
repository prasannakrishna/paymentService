package com.bhagwat.scm.paymentService.dto;

import com.bhagwat.scm.paymentService.common.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Request to initiate a payment through the Cashfree Payment Gateway.
 *
 * The response includes a {@code paymentLink} the client redirects the user to.
 * Cashfree handles PCI-compliant card/UPI/netbanking collection on its hosted page.
 *
 * Example:
 * <pre>
 * POST /api/v1/payment/gateway/initiate
 * {
 *   "orderId": "ORD-2024-001",
 *   "amount": 500.00,
 *   "currency": "INR",
 *   "description": "Order payment",
 *   "debitor": { "debitorId": "CUST-1", "name": "Alice", "email": "alice@mail.com", "phone": "9876543210" },
 *   "creditor": { "creditorId": "SELLER-1", "name": "Shop ABC" }
 * }
 * </pre>
 */
@Data
public class InitiatePaymentRequest {

    /**
     * Your unique order reference ID.
     * Max 50 chars — alphanumeric, hyphen, underscore only.
     */
    @NotBlank(message = "Order ID is required")
    private String orderId;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "1.00", message = "Amount must be at least 1.00")
    private BigDecimal amount;

    /** ISO 4217 currency. Currently only INR is supported for Cashfree PG. */
    @NotBlank(message = "Currency is required")
    private String currency = "INR";

    /** Human-readable description shown on checkout page (optional). */
    private String description;

    /** Preferred payment method (optional — all methods shown if not specified). */
    private PaymentMethod preferredPaymentMethod;

    @NotNull(message = "Debitor info is required")
    @Valid
    private DebitorInfo debitor;

    @NotNull(message = "Creditor info is required")
    @Valid
    private CreditorInfo creditor;

    /**
     * Idempotency key — if the same key is sent twice, the second call
     * returns the existing transaction instead of creating a new one.
     * Use the orderId if you don't have a separate idempotency key.
     */
    private String idempotencyKey;
}

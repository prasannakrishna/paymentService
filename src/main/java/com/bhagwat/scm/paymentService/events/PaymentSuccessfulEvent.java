package com.bhagwat.scm.paymentService.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentSuccessfulEvent {
    private String orderId;
    private String customerId;
    private BigDecimal amount;
    private String paymentTransactionId;
    private String deliveryAddress; // Crucial for shipment
    private List<ProductItemDetails> productItems; // Details needed for inventory and shipment

    // Nested class for product details
    public static class ProductItemDetails {
        private String productId;
        private int quantity;
        private BigDecimal pricePerUnit; // Price at time of order

        // Constructors, getters, setters
    }
    // Constructors, getters, setters
}
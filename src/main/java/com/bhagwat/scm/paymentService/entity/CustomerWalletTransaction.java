package com.bhagwat.scm.paymentService.entity;

import com.bhagwat.scm.paymentService.common.TransactionStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CustomerWalletTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long transactionId;

    private Long customerId;
    private Double orderValue;
    private Long orderId;
    private LocalDateTime date;

    @Enumerated(EnumType.STRING)
    private TransactionStatus status;

    private Double walletBalanceAmount;

    // Getters and setters
}


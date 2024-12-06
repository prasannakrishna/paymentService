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
public class OrgWalletTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long transactionId;

    private Long orderId;
    private Long partyId;
    private Double amountCredited;
    private LocalDateTime date;

    @Enumerated(EnumType.STRING)
    private TransactionStatus status;

    // Getters and setters
}


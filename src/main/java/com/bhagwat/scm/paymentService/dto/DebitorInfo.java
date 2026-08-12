package com.bhagwat.scm.paymentService.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * Represents the payer (debitor) — the entity whose account will be debited.
 */
@Data
public class DebitorInfo {

    /** Unique identifier for this customer in your system. */
    @NotBlank(message = "Debitor ID is required")
    private String debitorId;

    @NotBlank(message = "Debitor name is required")
    private String name;

    @NotBlank(message = "Debitor email is required")
    @Email(message = "Debitor email must be valid")
    private String email;

    /** 10-digit Indian mobile number. */
    @NotBlank(message = "Debitor phone is required")
    @Pattern(regexp = "^[6-9]\\d{9}$", message = "Phone must be a valid 10-digit Indian mobile number")
    private String phone;
}

package com.bhagwat.scm.paymentService.dto.wallet;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CreateIndividualWalletRequest {

    @NotBlank(message = "customerId is required")
    private String customerId;

    @NotBlank(message = "customerName is required")
    private String customerName;

    @Email(message = "customerEmail must be valid")
    private String customerEmail;

    @Pattern(regexp = "^[6-9]\\d{9}$", message = "Phone must be a valid 10-digit Indian mobile number")
    private String customerPhone;

    /** ISO 4217 currency. Defaults to INR. */
    private String currency = "INR";
}

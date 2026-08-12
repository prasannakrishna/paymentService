package com.bhagwat.scm.paymentService.dto.cashfree;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

/**
 * Order meta block for Cashfree PG — controls redirect and webhook behaviour.
 */
@Data
@Builder
public class CfOrderMeta {

    /**
     * URL Cashfree redirects to after payment.
     * Use "{order_id}" placeholder — Cashfree replaces it automatically.
     */
    @JsonProperty("return_url")
    private String returnUrl;

    /** URL Cashfree POSTs webhook events to. */
    @JsonProperty("notify_url")
    private String notifyUrl;

    /**
     * Payment methods to show on Cashfree checkout page.
     * Comma-separated: "cc,dc,nb,upi,paypal,app,paylater"
     * Null means all available methods.
     */
    @JsonProperty("payment_methods")
    private String paymentMethods;
}

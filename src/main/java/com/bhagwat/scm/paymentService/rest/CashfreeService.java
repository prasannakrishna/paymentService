package com.bhagwat.scm.paymentService.rest;

import com.bhagwat.scm.paymentService.dto.CashfreePaymentRequest;
import com.bhagwat.scm.paymentService.dto.CashfreePayoutRequest;
import com.bhagwat.scm.paymentService.dto.PaymentResponseDto;
import com.bhagwat.scm.paymentService.dto.PayoutResponseDto;
import org.springframework.stereotype.Service;

/**
 * @deprecated Use {@link CashfreeGatewayClient} for production gateway integration.
 * This class is kept for backward compatibility with the existing wallet payment flow.
 */
@Deprecated
@Service
public class CashfreeService {

    public PaymentResponseDto initiatePayment(CashfreePaymentRequest request) {
        return new PaymentResponseDto("SUCCESS",
                "Payment initiated. Order ID: " + request.getOrderId());
    }

    public PayoutResponseDto initiatePayout(CashfreePayoutRequest request) {
        return new PayoutResponseDto("SUCCESS",
                "Payout initiated. Transfer ID: " + request.getTransferId());
    }
}

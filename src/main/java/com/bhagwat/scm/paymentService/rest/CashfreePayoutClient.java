package com.bhagwat.scm.paymentService.rest;

import com.bhagwat.scm.paymentService.dto.cashfree.payout.CfPayoutTransferRequest;
import com.bhagwat.scm.paymentService.dto.cashfree.payout.CfPayoutTransferResponse;
import com.bhagwat.scm.paymentService.exception.PaymentGatewayException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Low-level HTTP client for the Cashfree Payouts API.
 *
 * Handles fund disbursement to bank accounts.
 * All errors are translated to {@link PaymentGatewayException}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CashfreePayoutClient {

    @Qualifier("cashfreePayoutRestClient")
    private final RestClient restClient;

    /**
     * Initiates a standard bank transfer via Cashfree Payouts.
     *
     * @param request payout request with beneficiary and amount
     * @return Cashfree response including {@code cfTransferId} and initial {@code transferStatus}
     */
    public CfPayoutTransferResponse initiateTransfer(CfPayoutTransferRequest request) {
        log.info("Initiating Cashfree payout: transferId={} amount={} beneficiary={}",
                request.getTransferId(), request.getTransferAmount(),
                request.getBeneficiary() != null ? request.getBeneficiary().getBeneficiaryName() : "?");

        try {
            CfPayoutTransferResponse response = restClient.post()
                    .uri("/standard-transfer")
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        String body = readBodySafely(resp);
                        log.error("Cashfree Payout 4xx: transferId={} status={} body={}",
                                request.getTransferId(), resp.getStatusCode(), body);
                        throw new PaymentGatewayException(
                                "Cashfree payout rejected: " + body,
                                HttpStatus.valueOf(resp.getStatusCode().value()));
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        log.error("Cashfree Payout 5xx: transferId={}", request.getTransferId());
                        throw new PaymentGatewayException(
                                "Cashfree payout server error — please retry", HttpStatus.BAD_GATEWAY);
                    })
                    .body(CfPayoutTransferResponse.class);

            log.info("Cashfree payout accepted: transferId={} cfTransferId={} status={}",
                    request.getTransferId(),
                    response != null ? response.getCfTransferId() : null,
                    response != null ? response.getTransferStatus() : null);
            return response;

        } catch (PaymentGatewayException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("Network error calling Cashfree Payout: transferId={}", request.getTransferId(), e);
            throw new PaymentGatewayException(
                    "Failed to reach Cashfree Payout gateway: " + e.getMessage(),
                    HttpStatus.SERVICE_UNAVAILABLE, e);
        }
    }

    private String readBodySafely(org.springframework.http.client.ClientHttpResponse resp) {
        try {
            return new String(resp.getBody().readAllBytes());
        } catch (Exception e) {
            return "(could not read response body)";
        }
    }
}

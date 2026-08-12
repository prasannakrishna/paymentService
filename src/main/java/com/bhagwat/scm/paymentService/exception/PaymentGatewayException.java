package com.bhagwat.scm.paymentService.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when the Cashfree gateway returns an error or the HTTP call fails.
 * Carries an HTTP status so the global handler can propagate it to the client.
 */
public class PaymentGatewayException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final String gatewayMessage;

    public PaymentGatewayException(String message, HttpStatus httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
        this.gatewayMessage = message;
    }

    public PaymentGatewayException(String message, HttpStatus httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.gatewayMessage = message;
    }

    public HttpStatus getHttpStatus() { return httpStatus; }
    public String getGatewayMessage() { return gatewayMessage; }
}

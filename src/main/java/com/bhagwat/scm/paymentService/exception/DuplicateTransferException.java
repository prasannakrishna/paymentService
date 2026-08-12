package com.bhagwat.scm.paymentService.exception;

public class DuplicateTransferException extends RuntimeException {
    private final String transferId;

    public DuplicateTransferException(String idempotencyKey, String transferId) {
        super("Transfer already exists for idempotencyKey=" + idempotencyKey + " (transferId=" + transferId + ")");
        this.transferId = transferId;
    }

    public String getTransferId() { return transferId; }
}

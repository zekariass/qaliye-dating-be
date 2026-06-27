package com.qaliye.backend.chat.exception;

public class InvalidReceiptSequenceException extends ChatException {
    public InvalidReceiptSequenceException(String detail) {
        super("INVALID_RECEIPT_SEQUENCE", detail, 422);
    }
    public InvalidReceiptSequenceException() {
        this("Receipt sequence is out of range.");
    }
}

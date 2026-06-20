package com.qaliye.backend.verification;

public class VerificationException extends RuntimeException {

    private final int status;
    private final String error;
    private final String errorMessage;

    public VerificationException(int status, String error, String errorMessage) {
        super(error);
        this.status = status;
        this.error = error;
        this.errorMessage = errorMessage;
    }

    public VerificationException(int status, String error) {
        this(status, error, null);
    }

    public int getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}

package com.qaliye.backend.verification;

import org.springframework.http.HttpStatus;

public class IdentityVerificationException extends RuntimeException {

    private final String errorCode;
    private final String errorMessage;
    private final HttpStatus status;

    public IdentityVerificationException(String errorCode, String errorMessage, HttpStatus status) {
        super(errorCode);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.status = status;
    }

    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public HttpStatus getStatus() { return status; }
}

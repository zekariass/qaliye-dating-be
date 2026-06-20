package com.qaliye.backend.discovery.exception;

public abstract class DiscoveryException extends RuntimeException {

    private final String errorCode;
    private final int httpStatus;

    protected DiscoveryException(String errorCode, String message, int httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode() { return errorCode; }
    public int getHttpStatus() { return httpStatus; }
}

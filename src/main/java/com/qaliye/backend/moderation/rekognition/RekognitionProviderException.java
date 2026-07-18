package com.qaliye.backend.moderation.rekognition;

/**
 * Thrown when the Rekognition API call fails.
 * {@code retryable} indicates whether the caller should schedule a retry.
 */
public class RekognitionProviderException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    public RekognitionProviderException(String errorCode, String message) {
        this(errorCode, message, false);
    }

    public RekognitionProviderException(String errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String getErrorCode() { return errorCode; }
    public boolean isRetryable() { return retryable; }
}

package com.qaliye.backend.moderation;

/**
 * Signals that an image cannot be processed for moderation.
 */
public class InvalidModerationImageException extends RuntimeException {

    private final String errorCode;

    public InvalidModerationImageException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

package com.qaliye.backend.chat.exception;

public class RateLimitedException extends ChatException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super("RATE_LIMITED", "Too many requests. Please slow down.", 429);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public RateLimitedException() {
        this(60L);
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}

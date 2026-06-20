package com.qaliye.backend.discovery.exception;

public class DailyLimitExceededException extends DiscoveryException {

    private final String limitType;

    public DailyLimitExceededException(String limitType) {
        super(toErrorCode(limitType), toMessage(limitType), 429);
        this.limitType = limitType;
    }

    public String getLimitType() { return limitType; }

    private static String toErrorCode(String limitType) {
        return switch (limitType) {
            case "DAILY_LIKES" -> "DAILY_LIKE_LIMIT_EXCEEDED";
            case "DAILY_SUPERLIKES" -> "DAILY_SUPERLIKE_LIMIT_EXCEEDED";
            case "DAILY_REWINDS" -> "DAILY_REWIND_LIMIT_EXCEEDED";
            default -> "DAILY_LIMIT_EXCEEDED";
        };
    }

    private static String toMessage(String limitType) {
        return switch (limitType) {
            case "DAILY_LIKES" -> "You have reached your daily like limit.";
            case "DAILY_SUPERLIKES" -> "You have reached your daily super-like limit.";
            case "DAILY_REWINDS" -> "You have reached your daily rewind limit.";
            default -> "You have reached your daily limit.";
        };
    }
}

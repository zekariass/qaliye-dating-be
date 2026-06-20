package com.qaliye.backend.actions;

public class DailyLimitReachedException extends RuntimeException {

    private final String limitType;

    public DailyLimitReachedException(String limitType) {
        super("Daily limit reached: " + limitType);
        this.limitType = limitType;
    }

    public String getLimitType() {
        return limitType;
    }
}

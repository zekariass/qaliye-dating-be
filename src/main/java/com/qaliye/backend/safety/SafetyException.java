package com.qaliye.backend.safety;

public class SafetyException extends RuntimeException {

    private final int status;
    private final String error;

    public SafetyException(int status, String error) {
        super(error);
        this.status = status;
        this.error = error;
    }

    public int getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }
}

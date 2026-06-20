package com.qaliye.backend.messaging;

public class MessagingException extends RuntimeException {

    private final int status;
    private final String error;

    public MessagingException(int status, String error) {
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

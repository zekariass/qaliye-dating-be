package com.qaliye.backend.chat.exception;

public class InvalidMessageException extends ChatException {
    public InvalidMessageException(String detail) {
        super("INVALID_MESSAGE", detail, 422);
    }
    public InvalidMessageException() {
        this("The message content is invalid.");
    }
}

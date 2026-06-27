package com.qaliye.backend.chat.exception;

public class ChatUnauthorizedException extends ChatException {
    public ChatUnauthorizedException(String detail) {
        super("UNAUTHORIZED", detail, 401);
    }
    public ChatUnauthorizedException() {
        this("Authentication required.");
    }
}

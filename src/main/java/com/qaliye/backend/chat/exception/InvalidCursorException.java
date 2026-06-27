package com.qaliye.backend.chat.exception;

public class InvalidCursorException extends ChatException {
    public InvalidCursorException() {
        super("INVALID_CURSOR", "The cursor is invalid or has been tampered with.", 400);
    }
}

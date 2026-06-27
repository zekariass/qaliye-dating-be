package com.qaliye.backend.chat.exception;

public class IdempotencyConflictException extends ChatException {
    public IdempotencyConflictException() {
        super("IDEMPOTENCY_CONFLICT",
                "A message with this clientMessageId already exists with different content.", 409);
    }
}

package com.qaliye.backend.chat.exception;

public class UserBlockedException extends ChatException {
    public UserBlockedException() {
        super("USER_BLOCKED", "A block exists between the participants.", 403);
    }
}

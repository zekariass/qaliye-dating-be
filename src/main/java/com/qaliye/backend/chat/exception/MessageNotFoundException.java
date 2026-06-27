package com.qaliye.backend.chat.exception;

public class MessageNotFoundException extends ChatException {
    public MessageNotFoundException() {
        super("MESSAGE_NOT_FOUND", "Message not found.", 404);
    }
}

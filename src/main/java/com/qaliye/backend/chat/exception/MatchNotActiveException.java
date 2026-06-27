package com.qaliye.backend.chat.exception;

public class MatchNotActiveException extends ChatException {
    public MatchNotActiveException() {
        super("MATCH_NOT_ACTIVE", "This chat is no longer available.", 409);
    }
}

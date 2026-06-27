package com.qaliye.backend.chat.exception;

public class MatchAccessDeniedException extends ChatException {
    public MatchAccessDeniedException() {
        super("MATCH_ACCESS_DENIED", "You are not a participant of this match.", 403);
    }
}

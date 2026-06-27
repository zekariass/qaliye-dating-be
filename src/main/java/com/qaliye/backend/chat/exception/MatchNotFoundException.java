package com.qaliye.backend.chat.exception;

public class MatchNotFoundException extends ChatException {
    public MatchNotFoundException() {
        super("MATCH_NOT_FOUND", "Match not found.", 404);
    }
}

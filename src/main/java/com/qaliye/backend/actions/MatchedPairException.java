package com.qaliye.backend.actions;

public class MatchedPairException extends RuntimeException {

    public MatchedPairException() {
        super("Cannot rewind a mutual match. Use unmatch instead.");
    }
}

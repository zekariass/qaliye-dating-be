package com.qaliye.backend.discovery.exception;

public class RewindMatchHasMessagesException extends DiscoveryException {

    public RewindMatchHasMessagesException() {
        super("REWIND_MATCH_HAS_MESSAGES",
                "Cannot rewind a match that already has messages.", 422);
    }
}

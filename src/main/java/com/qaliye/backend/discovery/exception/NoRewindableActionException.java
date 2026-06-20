package com.qaliye.backend.discovery.exception;

public class NoRewindableActionException extends DiscoveryException {

    public NoRewindableActionException() {
        super("NO_REWINDABLE_ACTION",
                "There is no recent action to rewind.", 404);
    }
}

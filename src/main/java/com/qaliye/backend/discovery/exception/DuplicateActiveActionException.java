package com.qaliye.backend.discovery.exception;

public class DuplicateActiveActionException extends DiscoveryException {

    public DuplicateActiveActionException() {
        super("DUPLICATE_ACTIVE_ACTION",
                "An active action already exists for this target.", 409);
    }
}

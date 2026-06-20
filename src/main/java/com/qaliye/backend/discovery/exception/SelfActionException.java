package com.qaliye.backend.discovery.exception;

public class SelfActionException extends DiscoveryException {

    public SelfActionException() {
        super("SELF_ACTION_NOT_ALLOWED", "Cannot perform a discovery action on your own profile.", 422);
    }
}

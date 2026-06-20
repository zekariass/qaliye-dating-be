package com.qaliye.backend.discovery.exception;

public class TargetIneligibleException extends DiscoveryException {

    public TargetIneligibleException() {
        super("DISCOVERY_TARGET_INELIGIBLE",
                "The target profile is no longer available.", 422);
    }
}

package com.qaliye.backend.discovery.exception;

public class RewindMatchGracePeriodExpiredException extends DiscoveryException {

    public RewindMatchGracePeriodExpiredException() {
        super("REWIND_MATCH_GRACE_EXPIRED",
                "The rewind grace period for this match has expired.", 422);
    }
}

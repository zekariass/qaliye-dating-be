package com.qaliye.backend.notifications.worker;

import java.util.concurrent.ThreadLocalRandom;

public final class BackoffCalculator {

    private static final long BASE_SECONDS = 5L;

    private BackoffCalculator() {}

    public static long compute(int attemptCount, long maxBackoffSeconds) {
        long exp = (long) Math.pow(2, Math.max(0, attemptCount - 1));
        long backoff = BASE_SECONDS * exp;
        long capped = Math.min(backoff, maxBackoffSeconds);
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, capped / 4));
        return capped + jitter;
    }
}

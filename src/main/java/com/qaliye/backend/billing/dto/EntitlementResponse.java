package com.qaliye.backend.billing.dto;

import java.time.Instant;
import java.util.Map;

public record EntitlementResponse(
        String plan,
        SubscriptionInfo subscription,
        Map<String, QuotaInfo> limits,
        CreditsInfo credits,
        ActiveBoostInfo activeBoost,
        Map<String, Boolean> features,
        Map<String, Integer> planLimits,
        int boostDurationMinutes
) {
    public record SubscriptionInfo(
            String status,
            Integer billingIntervalCount,
            String billingIntervalUnit,
            Instant expiresAt,
            boolean autoRenew
    ) {}

    public record QuotaInfo(
            int used,
            Integer limit,
            Integer remaining,
            Instant resetsAt
    ) {}

    public record CreditsInfo(
            int boostsAvailable,
            int superLikesAvailable,
            int rewindsAvailable
    ) {}


    public record ActiveBoostInfo(
            Instant startedAt,
            Instant expiresAt,
            long remainingSeconds
    ) {}
}

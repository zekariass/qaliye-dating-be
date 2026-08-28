package com.qaliye.backend.billing.dto;

import java.time.Instant;
import java.util.Map;

public record EntitlementResponse(
        String plan,
        SubscriptionInfo subscription,
        Map<String, ActionLimitAndCost> limitsAndCosts,
        CreditsInfo credits,
        ActiveBoostInfo activeBoost,
        Map<String, Boolean> features,
        Map<String, Integer> planLimits,
        int boostDurationMinutes,
        CountrySettings countrySettings
) {

    public record CountrySettings(
            String countryCode,
            boolean subscriptionEnabled,
            boolean creditsEnabled,
            boolean identityVerificationRequired
    ) {}
    public record SubscriptionInfo(
            String status,
            String provider,
            Integer billingIntervalCount,
            String billingIntervalUnit,
            Instant expiresAt,
            boolean autoRenew
    ) {}

    public record CreditsInfo(
            long creditBalance,
            int boostsAvailable,
            int superLikesAvailable,
            int rewindsAvailable
    ) {}


    public record ActiveBoostInfo(
            Instant startedAt,
            Instant expiresAt,
            long remainingSeconds
    ) {}

    public record ActionLimitAndCost(
            int used,
            Integer limit,
            Integer remaining,
            Instant resetsAt,
            long memberCreditCost,
            long actualCreditCost,
            String periodType,
            boolean applyCreditAfterLimit
    ) {}
}

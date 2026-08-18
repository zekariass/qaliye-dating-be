package com.qaliye.backend.billing.dto.admin;

public record UpdateSubscriptionPlanRequest(
        String name,
        String planCode,
        String countryCode,
        String planKind,
        Long priceMinorUnits,
        String currency,
        String billingInterval,
        String features,
        Boolean isActive
) {}

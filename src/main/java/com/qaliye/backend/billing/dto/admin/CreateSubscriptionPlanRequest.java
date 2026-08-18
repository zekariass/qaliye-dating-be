package com.qaliye.backend.billing.dto.admin;

import jakarta.validation.constraints.NotBlank;

public record CreateSubscriptionPlanRequest(
        @NotBlank String name,
        @NotBlank String planCode,
        @NotBlank String countryCode,
        @NotBlank String planKind,
        Long priceMinorUnits,
        String currency,
        String billingInterval,
        String features,
        Boolean isActive
) {}

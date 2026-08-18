package com.qaliye.backend.billing.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateConsumableProductRequest(
        @NotBlank String productCode,
        @NotBlank String name,
        @NotBlank String entitlementType,
        @NotNull @Positive long quantityGranted,
        Integer expiresAfterDays,
        Boolean isActive
) {}

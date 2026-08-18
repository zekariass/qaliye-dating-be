package com.qaliye.backend.billing.dto.admin;

public record UpdateConsumableProductRequest(
        String productCode,
        String name,
        String entitlementType,
        Long quantityGranted,
        Integer expiresAfterDays,
        Boolean isActive
) {}

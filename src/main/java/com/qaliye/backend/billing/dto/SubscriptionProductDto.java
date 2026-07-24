package com.qaliye.backend.billing.dto;

import java.util.UUID;

public record SubscriptionProductDto(
        UUID id,
        String productCode,
        String planCode,
        String planName,
        String billingIntervalUnit,
        Integer billingIntervalCount,
        Boolean autoRenewSupported,
        Boolean isActive
) {}

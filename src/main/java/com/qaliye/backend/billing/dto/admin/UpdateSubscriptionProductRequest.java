package com.qaliye.backend.billing.dto.admin;

import java.util.UUID;

public record UpdateSubscriptionProductRequest(
        UUID planId,
        String productCode,
        String billingIntervalUnit,
        Integer billingIntervalCount,
        Boolean autoRenewSupported,
        Long includedCredits,
        Boolean isActive
) {}

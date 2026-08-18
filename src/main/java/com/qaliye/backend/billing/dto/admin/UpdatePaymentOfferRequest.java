package com.qaliye.backend.billing.dto.admin;

import java.util.UUID;

public record UpdatePaymentOfferRequest(
        UUID subscriptionProductId,
        UUID consumableProductId,
        String countryCode,
        String platform,
        String currency,
        Integer priceMinorUnits,
        String externalProductId,
        String revenuecatOfferingId,
        String revenuecatPackageId,
        Boolean autoRenew,
        Boolean isActive
) {}

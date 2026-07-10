package com.qaliye.backend.billing.dto;

import java.util.UUID;

public record OfferDto(
        UUID id,
        String productCode,
        String productType,
        String countryCode,
        String currency,
        int priceMinorUnits,
        String displayPrice,
        Integer billingIntervalCount,
        String billingIntervalUnit,
        boolean autoRenew,
        String externalProductId,
        String revenuecatOfferingId,
        String revenuecatPackageId,
        boolean hasAvailablePaymentMethods,
        int availablePaymentMethodCount
) {}

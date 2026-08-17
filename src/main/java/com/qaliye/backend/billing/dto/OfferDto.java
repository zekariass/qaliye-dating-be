package com.qaliye.backend.billing.dto;

import java.util.List;
import java.util.UUID;

public record OfferDto(
        UUID id,
        String productCode,
        String productType,
        String countryCode,
        String currency,
        int priceMinorUnits,
        String displayPrice,
        int effectivePriceMinorUnits,
        String effectiveDisplayPrice,
        Integer billingIntervalCount,
        String billingIntervalUnit,
        long includedCredits,
        boolean autoRenew,
        String externalProductId,
        String revenuecatOfferingId,
        String revenuecatPackageId,
        boolean hasAvailablePaymentMethods,
        int availablePaymentMethodCount,
        PromotionDto promotion,
        List<ClaimablePromotionDto> claimablePromotions
) {}

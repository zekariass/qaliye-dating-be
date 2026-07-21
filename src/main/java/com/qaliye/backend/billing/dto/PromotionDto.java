package com.qaliye.backend.billing.dto;

import java.util.UUID;

public record PromotionDto(
        UUID campaignId,
        String campaignKey,
        String name,
        String description,
        String discountType,
        long discountValueBasisPointsOrMinorUnits,
        String discountCurrency,
        long originalAmountMinor,
        long discountAmountMinor,
        long finalAmountMinor,
        String effectiveDisplayPrice,
        String endsAt
) {}

package com.qaliye.backend.billing.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record CreatePaymentOfferRequest(
        UUID subscriptionProductId,
        UUID consumableProductId,
        @NotBlank String countryCode,
        @NotBlank String platform,
        @NotBlank String currency,
        @NotNull @Positive int priceMinorUnits,
        String externalProductId,
        String appleProductId,
        String googleProductId,
        String revenuecatOfferingId,
        String revenuecatPackageId,
        Boolean autoRenew,
        Boolean isActive
) {}

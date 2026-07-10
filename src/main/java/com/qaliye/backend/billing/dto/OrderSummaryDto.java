package com.qaliye.backend.billing.dto;

import java.time.Instant;
import java.util.UUID;

public record OrderSummaryDto(
        UUID id,
        String orderReference,
        String status,
        String productCode,
        String productType,
        String displayName,
        int expectedAmountMinorUnits,
        String expectedCurrency,
        String displayPrice,
        UUID paymentMethodId,
        String paymentMethodDisplayName,
        String paymentChannel,
        String paymentMethod,
        String methodCode,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        boolean canResumePayment,
        boolean canSubmitPayment,
        boolean canCreateNewOrder,
        Integer verificationCount
) {}

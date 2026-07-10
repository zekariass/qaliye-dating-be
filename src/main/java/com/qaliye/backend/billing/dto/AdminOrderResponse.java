package com.qaliye.backend.billing.dto;

import java.time.Instant;
import java.util.UUID;

public record AdminOrderResponse(
        UUID id,
        UUID userId,
        String userDisplayName,
        String orderReference,
        String status,
        int expectedAmountMinorUnits,
        String expectedCurrency,
        String paymentChannel,
        String paymentMethod,
        String methodCode,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {}

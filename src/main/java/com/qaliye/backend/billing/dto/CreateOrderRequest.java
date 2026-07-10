package com.qaliye.backend.billing.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateOrderRequest(
        @NotNull UUID paymentOfferId,
        @NotNull UUID paymentMethodId,
        String platform,
        String idempotencyKey
) {}

package com.qaliye.backend.billing.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

public record ManualTransferVerifyRequest(
        @NotNull UUID paymentOfferId,
        @NotNull UUID paymentMethodId,
        String platform,
        @NotNull Map<String, Object> verificationData,
        String idempotencyKey
) {}

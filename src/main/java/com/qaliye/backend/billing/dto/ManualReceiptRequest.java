package com.qaliye.backend.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

public record ManualReceiptRequest(
        @NotNull UUID paymentOfferId,
        @NotNull UUID paymentMethodId,
        String platform,
        @NotBlank String receiptStorageBucket,
        @NotBlank String receiptStoragePath,
        Map<String, Object> additionalNotes,
        String idempotencyKey
) {}

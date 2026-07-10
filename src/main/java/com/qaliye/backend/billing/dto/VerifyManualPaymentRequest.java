package com.qaliye.backend.billing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Map;
import java.util.UUID;

public record VerifyManualPaymentRequest(
        @NotNull UUID paymentOfferId,
        @NotNull UUID paymentMethodId,
        @NotNull Map<String, Object> verificationFields,
        @NotNull @Positive Integer submittedAmountMinorUnits,
        @NotNull String submittedCurrency,
        String idempotencyKey
) {}

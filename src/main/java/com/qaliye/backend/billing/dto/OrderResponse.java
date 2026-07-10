package com.qaliye.backend.billing.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String orderReference,
        String status,
        String statusReason,
        UUID paymentOfferId,
        int expectedAmountMinorUnits,
        String expectedCurrency,
        UUID paymentMethodId,
        String paymentChannel,
        String paymentMethod,
        String methodCode,
        String paymentMethodDisplayName,
        String providerCheckoutUrl,
        Map<String, Object> paymentInstructions,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        String verifyEtRequestId,
        Long pollAfterMs,
        boolean canRetryVerification,
        boolean canUploadReceipt,
        boolean canContactSupport,
        Integer verificationCount
) {}

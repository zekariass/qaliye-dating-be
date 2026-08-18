package com.qaliye.backend.billing.dto.admin;

import jakarta.validation.constraints.NotBlank;

public record CreatePaymentMethodRequest(
        @NotBlank String countryCode,
        @NotBlank String platform,
        @NotBlank String methodCode,
        @NotBlank String displayName,
        @NotBlank String paymentChannel,
        @NotBlank String paymentMethod,
        String paymentInstructions,
        Boolean isActive,
        Short displayOrder,
        String metadata,
        String verificationParams,
        String logoUrl
) {}

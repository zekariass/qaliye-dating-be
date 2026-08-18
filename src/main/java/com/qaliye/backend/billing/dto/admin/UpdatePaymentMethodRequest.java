package com.qaliye.backend.billing.dto.admin;

public record UpdatePaymentMethodRequest(
        String countryCode,
        String platform,
        String methodCode,
        String displayName,
        String paymentChannel,
        String paymentMethod,
        String paymentInstructions,
        Boolean isActive,
        Short displayOrder,
        String metadata,
        String verificationParams,
        String logoUrl
) {}

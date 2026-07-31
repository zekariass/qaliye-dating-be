package com.qaliye.backend.billing.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PaymentMethodDto(
        UUID id,
        String methodCode,
        String displayName,
        String paymentChannel,
        String paymentMethod,
        String paymentInstructionsHtml,
        int displayOrder,
        List<Map<String, Object>> verificationParams,
        String logoUrl
) {}

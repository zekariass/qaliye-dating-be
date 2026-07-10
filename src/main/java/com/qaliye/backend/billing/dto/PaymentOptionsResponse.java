package com.qaliye.backend.billing.dto;

import java.util.List;

public record PaymentOptionsResponse(
        String paymentChannel,
        String activeOnlineMethodCode,
        String platform,
        String billingCountryCode,
        String resolvedMarketCountryCode,
        boolean fallbackToGlobal,
        List<PaymentMethodDto> paymentMethods
) {}

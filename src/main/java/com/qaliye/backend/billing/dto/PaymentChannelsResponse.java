package com.qaliye.backend.billing.dto;

import java.util.List;

public record PaymentChannelsResponse(
        String platform,
        String billingCountryCode,
        String resolvedMarketCountryCode,
        boolean fallbackToGlobal,
        List<PaymentChannelDto> paymentChannels
) {}

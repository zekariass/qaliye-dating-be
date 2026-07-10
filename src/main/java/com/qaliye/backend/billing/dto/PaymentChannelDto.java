package com.qaliye.backend.billing.dto;

public record PaymentChannelDto(
        String code,
        String displayName,
        String activeOnlineMethodCode,
        int displayOrder,
        int methodCount
) {}

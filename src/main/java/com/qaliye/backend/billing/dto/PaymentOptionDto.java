package com.qaliye.backend.billing.dto;

public record PaymentOptionDto(
        String paymentChannel,
        String paymentMethod
) {}

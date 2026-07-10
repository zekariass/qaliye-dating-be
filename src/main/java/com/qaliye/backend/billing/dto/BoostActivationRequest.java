package com.qaliye.backend.billing.dto;

public record BoostActivationRequest(
        String idempotencyKey
) {}

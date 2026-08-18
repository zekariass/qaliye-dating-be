package com.qaliye.backend.billing.dto.admin;

public record UpdateFeatureActionRequest(
        String code,
        String name,
        String type
) {}

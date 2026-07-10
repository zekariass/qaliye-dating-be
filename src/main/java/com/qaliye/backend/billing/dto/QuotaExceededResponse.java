package com.qaliye.backend.billing.dto;

import java.time.Instant;

public record QuotaExceededResponse(
        String code,
        String message,
        QuotaInfo quota,
        UpgradeInfo upgrade
) {
    public record QuotaInfo(
            int used,
            int limit,
            int remaining,
            Instant resetsAt,
            String plan
    ) {}

    public record UpgradeInfo(
            boolean eligible,
            String recommendedPlan
    ) {}
}

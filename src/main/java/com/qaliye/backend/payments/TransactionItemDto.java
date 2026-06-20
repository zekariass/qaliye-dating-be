package com.qaliye.backend.payments;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TransactionItemDto(
        UUID transactionId,
        UUID userId,
        String provider,
        int amountCents,
        String currency,
        String paymentPurpose,
        String planCode,
        String receiptImageUrl,
        String status,
        OffsetDateTime createdAt,
        String userDisplayName
) {
}

package com.qaliye.backend.verification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record VerificationQueueItemDto(
        UUID verificationId,
        UUID userId,
        String displayName,
        OffsetDateTime submittedAt,
        String selfieSignedUrl,
        List<String> approvedPhotoUrls
) {
}

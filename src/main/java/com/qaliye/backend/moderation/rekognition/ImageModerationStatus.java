package com.qaliye.backend.moderation.rekognition;

public enum ImageModerationStatus {
    PENDING,
    PROCESSING,
    APPROVED,
    REJECTED,
    MANUAL_REVIEW,
    ERROR,
    SKIPPED
}

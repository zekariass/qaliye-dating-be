package com.qaliye.backend.moderation.aidetection;

/**
 * Action to take when AI-generated content is detected above the configured
 * confidence threshold.
 */
public enum AiDetectionAction {

    /** Photo is rejected and the user must upload a real photo. */
    REJECTED,

    /** Photo is held for manual moderator review. */
    MANUAL_REVIEW,

    /**
     * No enforcement action — the result is stored and returned to the client
     * as an advisory so the user can voluntarily replace the photo.
     */
    ADVISE_ONLY
}

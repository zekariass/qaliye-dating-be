package com.qaliye.backend.profile.dto;

/**
 * Advisory returned to the client when AI detection is configured as
 * {@code ADVISE_ONLY} and the photo was identified as likely AI-generated.
 * The photo is still accepted; the user is encouraged to replace it.
 */
public record AiDetectionAdvisoryDto(
        boolean aiGenerated,
        double confidence,
        String detectedGenerator
) {}

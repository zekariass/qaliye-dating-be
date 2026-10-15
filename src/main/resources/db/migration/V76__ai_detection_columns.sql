-- =============================================================================
-- V76: AI Detection Columns
-- Adds nullable AI-detection result columns to image_moderation_results.
-- These are populated when the AI-or-Not (or future provider) check runs on
-- the primary photo (photo_order = 0) and the first card photo (photo_order = 1).
-- =============================================================================

ALTER TABLE public.image_moderation_results
    ADD COLUMN IF NOT EXISTS ai_detection_enabled    BOOLEAN,
    ADD COLUMN IF NOT EXISTS ai_generated_detected   BOOLEAN,
    ADD COLUMN IF NOT EXISTS ai_generated_confidence DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS ai_detected_generator   VARCHAR(100),
    ADD COLUMN IF NOT EXISTS ai_detection_verdict    VARCHAR(20),
    ADD COLUMN IF NOT EXISTS ai_detection_action     VARCHAR(20);

COMMENT ON COLUMN public.image_moderation_results.ai_detection_enabled
    IS 'TRUE when AI detection ran for this photo; FALSE/NULL when skipped (disabled or ineligible order).';

COMMENT ON COLUMN public.image_moderation_results.ai_generated_detected
    IS 'TRUE when the AI detection provider detected AI-generated content above the confidence threshold.';

COMMENT ON COLUMN public.image_moderation_results.ai_generated_confidence
    IS 'Raw AI detection confidence score (0.0–1.0) returned by the provider.';

COMMENT ON COLUMN public.image_moderation_results.ai_detected_generator
    IS 'Name of the AI generator with the highest confidence (e.g. midjourney, stable_diffusion).';

COMMENT ON COLUMN public.image_moderation_results.ai_detection_verdict
    IS 'Raw verdict from the provider: ''ai'' or ''human''.';

COMMENT ON COLUMN public.image_moderation_results.ai_detection_action
    IS 'Action taken based on the result and config: REJECTED, MANUAL_REVIEW, or ADVISE_ONLY.';

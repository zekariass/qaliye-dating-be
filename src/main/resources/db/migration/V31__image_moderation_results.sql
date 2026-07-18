-- =============================================================================
-- V31: Image Moderation Results
-- Adds a dedicated table to persist per-image moderation outcomes from Amazon
-- Rekognition (or future providers).  The profile_photos.moderation_status
-- column continues to drive visibility; this table stores the detailed results
-- that inform that status, enable retries, and support manual review.
-- =============================================================================

-- Allow MANUAL_REVIEW as a valid profile_photos moderation status so photos
-- that need human inspection are stored correctly without DB constraint errors.
DO $$
BEGIN
    ALTER TABLE public.profile_photos
        DROP CONSTRAINT IF EXISTS profile_photos_moderation_status_check;
    ALTER TABLE public.profile_photos
        ADD CONSTRAINT profile_photos_moderation_status_check CHECK (
            moderation_status IN ('PENDING', 'APPROVED', 'REJECTED', 'MANUAL_REVIEW')
        );
EXCEPTION WHEN others THEN
    NULL; -- constraint may not have existed
END;
$$;

-- -------------------------------------------------------------------------
-- image_moderation_results
-- One row per profile photo.  Unique on image_id; rows are updated in-place
-- on retry (attempt_count is incremented, status transitions back through
-- PROCESSING → terminal).
-- -------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.image_moderation_results (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    image_id                 UUID NOT NULL,
    profile_id               UUID NOT NULL,

    provider                 VARCHAR(50)  NOT NULL DEFAULT 'REKOGNITION',
    status                   VARCHAR(30)  NOT NULL DEFAULT 'PENDING',

    face_detection_enabled   BOOLEAN      NOT NULL DEFAULT FALSE,
    nudity_moderation_enabled BOOLEAN     NOT NULL DEFAULT FALSE,

    face_count               INTEGER,
    selected_face_confidence DOUBLE PRECISION,
    brightness               DOUBLE PRECISION,
    sharpness                DOUBLE PRECISION,
    face_area_percentage     DOUBLE PRECISION,
    face_occluded            BOOLEAN,

    nudity_detected          BOOLEAN,
    sexual_content_detected  BOOLEAN,
    moderation_labels        JSONB,

    decision_reasons         TEXT[]       NOT NULL DEFAULT '{}',
    manual_review_reason     TEXT,
    provider_request_id      VARCHAR(255),

    image_storage_path       VARCHAR(500) NOT NULL,
    image_hash               VARCHAR(64),
    config_version           VARCHAR(255),

    attempt_count            INTEGER      NOT NULL DEFAULT 0,
    retry_after              TIMESTAMPTZ,
    last_error_code          VARCHAR(100),
    last_error_message       TEXT,

    processed_at             TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_imr_image_id UNIQUE (image_id)
);

CREATE INDEX IF NOT EXISTS idx_imr_status      ON public.image_moderation_results (status);
CREATE INDEX IF NOT EXISTS idx_imr_profile_id  ON public.image_moderation_results (profile_id);
CREATE INDEX IF NOT EXISTS idx_imr_image_hash  ON public.image_moderation_results (image_hash)
    WHERE image_hash IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_imr_retry       ON public.image_moderation_results (retry_after)
    WHERE status = 'ERROR';

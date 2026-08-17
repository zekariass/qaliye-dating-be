-- =============================================================================
-- V54: Identity verification manual review
-- =============================================================================

-- 1. Add MANUAL_REVIEW to verification_status CHECK constraint
ALTER TABLE public.app_users
    DROP CONSTRAINT IF EXISTS app_users_verification_status_check;

ALTER TABLE public.app_users
    ADD CONSTRAINT app_users_verification_status_check CHECK (
        verification_status IN ('NOT_STARTED', 'PENDING', 'VERIFIED', 'FAILED', 'MANUAL_REVIEW')
    );

-- 2. Create identity_verification_reviews table
CREATE TABLE IF NOT EXISTS public.identity_verification_reviews (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID        NOT NULL REFERENCES public.app_users(id) ON DELETE CASCADE,
    selfie_path     VARCHAR(500) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reviewer_id     UUID        REFERENCES public.app_users(id) ON DELETE SET NULL,
    reviewer_note   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at     TIMESTAMPTZ
);

-- One pending review per user at a time
CREATE UNIQUE INDEX IF NOT EXISTS uq_one_pending_identity_review
    ON public.identity_verification_reviews (user_id)
    WHERE status = 'PENDING';

-- Index for admin queue queries
CREATE INDEX IF NOT EXISTS idx_identity_reviews_status
    ON public.identity_verification_reviews (status, created_at);

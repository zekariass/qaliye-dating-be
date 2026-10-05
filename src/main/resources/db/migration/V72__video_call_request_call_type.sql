-- =============================================================================
-- V72: Video-call requests — add call_type (VIDEO | AUDIO)
--
--  1. video_call_requests.call_type — distinguishes video vs audio calls so
--     each type gets its own billing action and identifiable history.
--  2. feature_actions seed: AUDIO_CALL — its own plan-limit/credit rows in
--     subscription_plan_limit_and_cost, priced independently of VIDEO_CALL.
-- =============================================================================


-- =============================================================================
-- 1. video_call_requests.call_type
-- =============================================================================

ALTER TABLE public.video_call_requests
    ADD COLUMN IF NOT EXISTS call_type VARCHAR(10) NOT NULL DEFAULT 'VIDEO';

ALTER TABLE public.video_call_requests
    DROP CONSTRAINT IF EXISTS video_call_requests_call_type_check;

ALTER TABLE public.video_call_requests
    ADD CONSTRAINT video_call_requests_call_type_check
        CHECK (call_type IN ('VIDEO', 'AUDIO'));

-- Manual "remind" nudges — cooldown bookkeeping for POST /{id}/remind.
ALTER TABLE public.video_call_requests
    ADD COLUMN IF NOT EXISTS reminder_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE public.video_call_requests
    ADD COLUMN IF NOT EXISTS last_reminded_at TIMESTAMPTZ;


-- =============================================================================
-- 2. feature_actions — AUDIO_CALL seed
-- =============================================================================

INSERT INTO public.feature_actions (code, name, type, description)
VALUES ('AUDIO_CALL', 'Audio Call', 'ACTION',
        'Post-match audio call between matched users — charged to requester on first join')
ON CONFLICT (code) DO NOTHING;

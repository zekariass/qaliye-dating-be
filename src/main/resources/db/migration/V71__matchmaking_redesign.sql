-- =============================================================================
-- V71: Personal Matchmaking — Redesign Migration
--
-- Aligns the schema with the redesigned matchmaking system:
--  1. matchmaking_preferences  — add 5 alignment columns
--  2. matchmaking_requests     — drop obsolete messaging/escrow/snapshot columns
--  3. matchmaking_introductions — add score/reason columns; drop filter-param
--     columns; resolve legacy MUTUAL_INTEREST rows; update status CHECK
--  4. Drop matchmaking_messages and matchmaking_video_calls tables
--  5. Create video_call_requests table (post-match, match-scoped video calls)
--  6. feature_actions seed: VIDEO_CALL
-- =============================================================================


-- =============================================================================
-- 1. matchmaking_preferences — add alignment columns
-- =============================================================================

ALTER TABLE public.matchmaking_preferences
    ADD COLUMN IF NOT EXISTS marriage_timeline          VARCHAR(30) NULL,
    ADD COLUMN IF NOT EXISTS long_distance_relationship VARCHAR(5)  NULL,
    ADD COLUMN IF NOT EXISTS family_involvement         VARCHAR(5)  NULL,
    ADD COLUMN IF NOT EXISTS religion_important         VARCHAR(5)  NULL,
    ADD COLUMN IF NOT EXISTS willing_to_relocate        VARCHAR(5)  NULL;

ALTER TABLE public.matchmaking_preferences
    DROP CONSTRAINT IF EXISTS matchmaking_preferences_timeline_check,
    DROP CONSTRAINT IF EXISTS matchmaking_preferences_alignment_check;

ALTER TABLE public.matchmaking_preferences
    ADD CONSTRAINT matchmaking_preferences_timeline_check
        CHECK (marriage_timeline IS NULL OR marriage_timeline IN (
            'NOW', 'ONE_TO_TWO_YEARS', 'THREE_TO_FIVE_YEARS', 'NOT_SURE')),
    ADD CONSTRAINT matchmaking_preferences_alignment_check
        CHECK (
            (long_distance_relationship IS NULL OR
             long_distance_relationship IN ('YES', 'NO'))
        AND (family_involvement IS NULL OR
             family_involvement IN ('YES', 'NO'))
        AND (religion_important IS NULL OR
             religion_important IN ('YES', 'NO'))
        AND (willing_to_relocate IS NULL OR
             willing_to_relocate IN ('YES', 'NO'))
        );


-- =============================================================================
-- 2. matchmaking_requests — drop obsolete columns
-- =============================================================================

ALTER TABLE public.matchmaking_requests
    DROP COLUMN IF EXISTS preferences_snapshot,
    DROP COLUMN IF EXISTS preferences_version,
    DROP COLUMN IF EXISTS video_refund_key,
    DROP COLUMN IF EXISTS video_refunded_at,
    DROP COLUMN IF EXISTS user_last_read_message_seq,
    DROP COLUMN IF EXISTS admin_last_read_message_seq;


-- =============================================================================
-- 3. matchmaking_introductions — schema redesign
-- =============================================================================

-- Data migration: resolve legacy MUTUAL_INTEREST rows before CHECK change.
UPDATE public.matchmaking_introductions
SET    status       = 'MATCHED',
       completed_at = COALESCE(completed_at, updated_at)
WHERE  status = 'MUTUAL_INTEREST' AND match_id IS NOT NULL;

UPDATE public.matchmaking_introductions
SET    status = 'DECLINED'
WHERE  status = 'MUTUAL_INTEREST' AND match_id IS NULL;

-- Add score + reason columns (nullable first so historical rows don't fail).
ALTER TABLE public.matchmaking_introductions
    ADD COLUMN IF NOT EXISTS forward_percentage  INTEGER NULL,
    ADD COLUMN IF NOT EXISTS reverse_percentage  INTEGER NULL,
    ADD COLUMN IF NOT EXISTS overall_percentage  INTEGER NULL,
    ADD COLUMN IF NOT EXISTS match_details       JSONB   NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS reason              TEXT    NULL;

-- Drop obsolete filter-param columns.
ALTER TABLE public.matchmaking_introductions
    DROP COLUMN IF EXISTS search_source,
    DROP COLUMN IF EXISTS effective_filter_params,
    DROP COLUMN IF EXISTS admin_override_params;

-- Backfill historical rows so we can make the score columns NOT NULL.
UPDATE public.matchmaking_introductions
SET    forward_percentage = 0,
       reverse_percentage = 0,
       overall_percentage = 0
WHERE  forward_percentage IS NULL;

ALTER TABLE public.matchmaking_introductions
    ALTER COLUMN forward_percentage SET NOT NULL,
    ALTER COLUMN reverse_percentage SET NOT NULL,
    ALTER COLUMN overall_percentage SET NOT NULL;

-- Update the status CHECK to remove MUTUAL_INTEREST.
ALTER TABLE public.matchmaking_introductions
    DROP CONSTRAINT IF EXISTS matchmaking_introductions_status_check;

ALTER TABLE public.matchmaking_introductions
    ADD CONSTRAINT matchmaking_introductions_status_check
        CHECK (status IN (
            'PROPOSED', 'DECLINED', 'MATCHED', 'CANCELLED', 'EXPIRED'));


-- =============================================================================
-- 4. Drop obsolete tables
-- =============================================================================

-- Drop matchmaking_video_calls first (it may have FKs referencing it).
DROP TABLE IF EXISTS public.matchmaking_video_calls;

-- Drop matchmaking_messages.
DROP TABLE IF EXISTS public.matchmaking_messages;


-- =============================================================================
-- 5. video_call_requests — post-match video calls (match-scoped)
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.video_call_requests (
    id                   UUID        NOT NULL DEFAULT gen_random_uuid(),
    match_id             UUID        NOT NULL,
    requester_user_id    UUID        NOT NULL,
    responder_user_id    UUID        NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    request_expires_at   TIMESTAMPTZ NOT NULL,
    responded_at         TIMESTAMPTZ NULL,
    channel_name         VARCHAR(255) NULL,
    requester_charge_key UUID        NULL,
    requester_charged_at TIMESTAMPTZ NULL,
    requester_joined_at  TIMESTAMPTZ NULL,
    responder_joined_at  TIMESTAMPTZ NULL,
    ended_at             TIMESTAMPTZ NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT video_call_requests_pkey
        PRIMARY KEY (id),

    CONSTRAINT video_call_requests_match_fkey
        FOREIGN KEY (match_id) REFERENCES public.matches(id)
        ON DELETE CASCADE,

    CONSTRAINT video_call_requests_requester_fkey
        FOREIGN KEY (requester_user_id) REFERENCES public.app_users(id)
        ON DELETE CASCADE,

    CONSTRAINT video_call_requests_responder_fkey
        FOREIGN KEY (responder_user_id) REFERENCES public.app_users(id)
        ON DELETE CASCADE,

    CONSTRAINT video_call_requests_distinct_users_check
        CHECK (requester_user_id <> responder_user_id),

    CONSTRAINT video_call_requests_status_check
        CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED',
                          'CANCELLED', 'EXPIRED', 'COMPLETED')),

    CONSTRAINT video_call_requests_channel_check
        CHECK (status IN ('PENDING', 'DECLINED', 'CANCELLED', 'EXPIRED')
               OR channel_name IS NOT NULL),

    CONSTRAINT video_call_requests_charge_key_uq
        UNIQUE (requester_charge_key)
);

-- One active request per match at a time.
CREATE UNIQUE INDEX IF NOT EXISTS uq_video_call_request_per_match
    ON public.video_call_requests (match_id)
    WHERE status IN ('PENDING', 'ACCEPTED');

-- Sweeper index: find expired PENDING requests efficiently.
CREATE INDEX IF NOT EXISTS idx_video_call_requests_sweep
    ON public.video_call_requests (request_expires_at)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_video_call_requests_match
    ON public.video_call_requests (match_id);


-- =============================================================================
-- 6. feature_actions — add VIDEO_CALL; retire escrow action reference
-- =============================================================================

INSERT INTO public.feature_actions (code, name, type, description)
VALUES ('VIDEO_CALL', 'Video Call', 'ACTION',
        'Post-match video call between matched users — charged to requester on first join')
ON CONFLICT (code) DO NOTHING;

-- Remove plan limits that reference the now-retired escrow action.
-- The feature_actions row itself is intentionally kept for historical ledger rows.
-- (subscription_plan_limits was renamed subscription_plan_limit_and_cost in V53
--  and references actions by feature_action_id, not a code column.)
DELETE FROM public.subscription_plan_limit_and_cost
WHERE feature_action_id = (
    SELECT id FROM public.feature_actions
    WHERE code = 'PERSONAL_MATCHMAKING_VIDEO_ESCROW'
);

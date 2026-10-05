-- =============================================================================
-- V70: Personal Matchmaking Feature
--
-- Implements the full personal matchmaking database schema as described in
-- docs/important/private-mediated-match-making-design.md.
--
-- NOTE: squashed migration — this file creates the schema directly in its
-- final form (formerly V70 + V71 typed preference columns + V72 video
-- platform refactor). Safe only because none of those had been applied.
--
-- Changes:
--  1. matchmaking_preferences  — user preference sets (typed columns;
--     gender is profile-derived, never user-editable)
--  2. matchmaking_requests     — paid matchmaking requests
--  3. matchmaking_admin_notes  — append-only admin notes on requests
--  4. matchmaking_messages     — admin↔user coordination thread per request
--  5. matchmaking_introductions — admin-created introduction pairs
--  6. matchmaking_video_calls  — video introduction lifecycle (platform
--     IN_APP/EXTERNAL; EXTERNAL = ZOOM/GOOGLE_MEET link + optional
--     external_access_code passcode)
--  7. matches table: drop NOT NULL on like action IDs; add match_source='MATCHMAKING';
--     add matchmaking_introduction_id FK
--  8. feature_actions seed: PERSONAL_MATCHMAKING_REQUEST,
--     PERSONAL_MATCHMAKING_VIDEO_ESCROW
--  9. credit_source_type constraint extension: MATCHMAKING_ESCROW_REFUND
-- =============================================================================


-- =============================================================================
-- 1. MATCHMAKING PREFERENCES
-- =============================================================================

-- Typed columns mirror discovery_preferences plus height/smoking/drinking/
-- education/marital fields backed by profile facts.
-- Semantics: NULL scalar = inactive constraint; empty array / 'any' = none.
-- matchmaking_requests.preferences_snapshot stays JSONB (frozen at request
-- time) so live requests are unaffected by later edits.
CREATE TABLE IF NOT EXISTS public.matchmaking_preferences (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL,
    version     INTEGER      NOT NULL DEFAULT 1,

    /* Sought partner gender — the opposite of profiles.gender, derived at
       write time, never user-editable (matchmaking is hetero: candidate
       gender must differ). NULL when the profile has no gender. */
    gender      VARCHAR(10)  NULL,

    min_age           INTEGER NULL,
    max_age           INTEGER NULL,
    min_height_cm     INTEGER NULL,
    max_height_cm     INTEGER NULL,

    /* Acceptable candidate residence country codes (addresses.country_code). */
    specific_country_codes    TEXT[]      NOT NULL DEFAULT '{}',

    /* Mirror discovery_preferences.has/wants_children_preference. */
    has_children_preference   VARCHAR(20) NOT NULL DEFAULT 'any',
    wants_children_preference VARCHAR(30) NOT NULL DEFAULT 'any',

    /* Acceptable profile fact values. */
    religion_preferences      TEXT[] NOT NULL DEFAULT '{}',
    education_levels          TEXT[] NOT NULL DEFAULT '{}',
    marital_statuses          TEXT[] NOT NULL DEFAULT '{}',
    smoking_preferences       TEXT[] NOT NULL DEFAULT '{}',
    drinking_preferences      TEXT[] NOT NULL DEFAULT '{}',

    /* Catalog references, same as discovery_preferences. */
    language_preference_ids   UUID[] NOT NULL DEFAULT '{}',
    ethnicity_preference_ids  UUID[] NOT NULL DEFAULT '{}',

    matchmaker_notes TEXT    NULL,   -- admin-only
    user_notes       TEXT    NULL,   -- authored by the user

    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT matchmaking_preferences_pkey
        PRIMARY KEY (id),

    CONSTRAINT matchmaking_preferences_user_fkey
        FOREIGN KEY (user_id)
        REFERENCES public.app_users(id)
        ON DELETE CASCADE,

    CONSTRAINT matchmaking_preferences_user_unique
        UNIQUE (user_id),

    CONSTRAINT matchmaking_preferences_age_range_check
        CHECK ((min_age IS NULL OR min_age >= 18)
           AND (max_age IS NULL OR max_age <= 120)
           AND (min_age IS NULL OR max_age IS NULL OR min_age <= max_age)),

    CONSTRAINT matchmaking_preferences_height_range_check
        CHECK ((min_height_cm IS NULL OR min_height_cm > 0)
           AND (min_height_cm IS NULL OR max_height_cm IS NULL
                OR min_height_cm <= max_height_cm)),

    CONSTRAINT matchmaking_preferences_has_children_check
        CHECK (has_children_preference IN ('any', 'yes', 'no')),

    CONSTRAINT matchmaking_preferences_wants_children_check
        CHECK (wants_children_preference IN
               ('any', 'yes', 'no', 'not_sure', 'open_to_discussion')),

    CONSTRAINT matchmaking_preferences_marital_check
        CHECK (marital_statuses <@
               ARRAY['NEVER_MARRIED', 'DIVORCED', 'WIDOWED', 'SEPARATED']::text[]),

    CONSTRAINT matchmaking_preferences_smoking_check
        CHECK (smoking_preferences <@
               ARRAY['NO', 'YES', 'OCCASIONALLY', 'TRYING_TO_QUIT']::text[]),

    CONSTRAINT matchmaking_preferences_drinking_check
        CHECK (drinking_preferences <@
               ARRAY['NO', 'SOCIALLY', 'OCCASIONALLY', 'YES']::text[])
);

CREATE INDEX IF NOT EXISTS idx_matchmaking_preferences_user
    ON public.matchmaking_preferences (user_id);


-- =============================================================================
-- 2. MATCHMAKING REQUESTS
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.matchmaking_requests (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL,
    matchmaking_preferences_id UUID NOT NULL,
    preferences_version INTEGER  NOT NULL,
    preferences_snapshot JSONB   NOT NULL DEFAULT '{}'::jsonb,
    status          VARCHAR(30)  NOT NULL DEFAULT 'OPEN',
    request_charge_idempotency_key UUID NOT NULL,
    contact_phone   VARCHAR(30)  NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMPTZ  NULL,
    cancelled_at    TIMESTAMPTZ  NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    video_refund_key    UUID     NULL,
    video_refunded_at   TIMESTAMPTZ NULL,
    user_last_read_message_seq  BIGINT NULL,
    admin_last_read_message_seq BIGINT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT matchmaking_requests_pkey
        PRIMARY KEY (id),

    CONSTRAINT matchmaking_requests_user_fkey
        FOREIGN KEY (user_id)
        REFERENCES public.app_users(id)
        ON DELETE CASCADE,

    CONSTRAINT matchmaking_requests_preferences_fkey
        FOREIGN KEY (matchmaking_preferences_id)
        REFERENCES public.matchmaking_preferences(id)
        ON DELETE NO ACTION,

    CONSTRAINT matchmaking_requests_status_check
        CHECK (status IN (
            'OPEN', 'ON_HOLD', 'MATCHED',
            'COMPLETED', 'CANCELLED', 'EXPIRED'
        )),

    CONSTRAINT matchmaking_requests_snapshot_check
        CHECK (jsonb_typeof(preferences_snapshot) = 'object'),

    CONSTRAINT matchmaking_requests_charge_key_unique
        UNIQUE (request_charge_idempotency_key),

    CONSTRAINT matchmaking_requests_video_refund_key_unique
        UNIQUE (video_refund_key)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_matchmaking_one_active_request_per_user
    ON public.matchmaking_requests (user_id)
    WHERE status IN ('OPEN', 'ON_HOLD');

CREATE INDEX IF NOT EXISTS idx_matchmaking_requests_queue
    ON public.matchmaking_requests (status, created_at);

CREATE INDEX IF NOT EXISTS idx_matchmaking_requests_user
    ON public.matchmaking_requests (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_matchmaking_requests_expiry
    ON public.matchmaking_requests (expires_at)
    WHERE status = 'OPEN';


-- =============================================================================
-- 3. MATCHMAKING ADMIN NOTES
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.matchmaking_admin_notes (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    request_id      UUID        NOT NULL,
    admin_user_id   UUID        NOT NULL,
    note            TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT matchmaking_admin_notes_pkey
        PRIMARY KEY (id),

    CONSTRAINT matchmaking_admin_notes_request_fkey
        FOREIGN KEY (request_id)
        REFERENCES public.matchmaking_requests(id)
        ON DELETE CASCADE,

    CONSTRAINT matchmaking_admin_notes_admin_fkey
        FOREIGN KEY (admin_user_id)
        REFERENCES public.app_users(id)
        ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_matchmaking_admin_notes_request
    ON public.matchmaking_admin_notes (request_id, created_at);


-- =============================================================================
-- 4. MATCHMAKING MESSAGES
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.matchmaking_messages (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    seq             BIGINT      GENERATED ALWAYS AS IDENTITY NOT NULL,
    request_id      UUID        NOT NULL,
    sender_user_id  UUID        NULL,
    sender_role     VARCHAR(10) NOT NULL,
    body            TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT matchmaking_messages_pkey
        PRIMARY KEY (id),

    CONSTRAINT matchmaking_messages_request_fkey
        FOREIGN KEY (request_id)
        REFERENCES public.matchmaking_requests(id)
        ON DELETE CASCADE,

    CONSTRAINT matchmaking_messages_sender_fkey
        FOREIGN KEY (sender_user_id)
        REFERENCES public.app_users(id)
        ON DELETE NO ACTION,

    CONSTRAINT matchmaking_messages_role_check
        CHECK (sender_role IN ('ADMIN', 'USER', 'SYSTEM')),

    CONSTRAINT matchmaking_messages_sender_role_check
        CHECK (
            (sender_role = 'SYSTEM' AND sender_user_id IS NULL)
            OR (sender_role <> 'SYSTEM' AND sender_user_id IS NOT NULL)
        )
);

CREATE INDEX IF NOT EXISTS idx_matchmaking_messages_request
    ON public.matchmaking_messages (request_id, seq);


-- =============================================================================
-- 5. MATCHMAKING INTRODUCTIONS
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.matchmaking_introductions (
    id                          UUID        NOT NULL DEFAULT gen_random_uuid(),
    requester_request_id        UUID        NOT NULL,
    candidate_request_id        UUID        NOT NULL,
    initiated_by_admin_user_id  UUID        NOT NULL,
    search_source               VARCHAR(30) NOT NULL,
    effective_filter_params     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    admin_override_params       JSONB       NULL,
    requester_decision          VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    candidate_decision          VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    status                      VARCHAR(30) NOT NULL DEFAULT 'PROPOSED',
    requester_decided_at        TIMESTAMPTZ NULL,
    candidate_decided_at        TIMESTAMPTZ NULL,
    decision_expires_at         TIMESTAMPTZ NOT NULL,
    match_id                    UUID        NULL,
    proposed_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_at                 TIMESTAMPTZ NULL,
    completed_at                TIMESTAMPTZ NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT matchmaking_introductions_pkey
        PRIMARY KEY (id),

    CONSTRAINT matchmaking_introductions_requester_fkey
        FOREIGN KEY (requester_request_id)
        REFERENCES public.matchmaking_requests(id)
        ON DELETE CASCADE,

    CONSTRAINT matchmaking_introductions_candidate_fkey
        FOREIGN KEY (candidate_request_id)
        REFERENCES public.matchmaking_requests(id)
        ON DELETE CASCADE,

    CONSTRAINT matchmaking_introductions_admin_fkey
        FOREIGN KEY (initiated_by_admin_user_id)
        REFERENCES public.app_users(id)
        ON DELETE RESTRICT,

    CONSTRAINT matchmaking_introductions_match_fkey
        FOREIGN KEY (match_id)
        REFERENCES public.matches(id)
        ON DELETE SET NULL,

    CONSTRAINT matchmaking_introductions_request_distinct_check
        CHECK (requester_request_id <> candidate_request_id),

    CONSTRAINT matchmaking_introductions_search_source_check
        CHECK (search_source IN ('REQUESTER_PREFERENCES', 'ADMIN_OVERRIDE')),

    CONSTRAINT matchmaking_introductions_effective_params_check
        CHECK (jsonb_typeof(effective_filter_params) = 'object'),

    CONSTRAINT matchmaking_introductions_override_params_check
        CHECK (
            admin_override_params IS NULL
            OR jsonb_typeof(admin_override_params) = 'object'
        ),

    CONSTRAINT matchmaking_introductions_decision_check
        CHECK (
            requester_decision IN ('PENDING', 'INTERESTED', 'NOT_INTERESTED')
            AND candidate_decision IN ('PENDING', 'INTERESTED', 'NOT_INTERESTED')
        ),

    CONSTRAINT matchmaking_introductions_status_check
        CHECK (status IN (
            'PROPOSED', 'DECLINED', 'MUTUAL_INTEREST',
            'MATCHED', 'CANCELLED', 'EXPIRED'
        ))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_matchmaking_active_requester_introduction
    ON public.matchmaking_introductions (requester_request_id)
    WHERE status IN ('PROPOSED', 'MUTUAL_INTEREST');

CREATE UNIQUE INDEX IF NOT EXISTS uq_matchmaking_active_candidate_introduction
    ON public.matchmaking_introductions (candidate_request_id)
    WHERE status IN ('PROPOSED', 'MUTUAL_INTEREST');

CREATE INDEX IF NOT EXISTS idx_matchmaking_introductions_match
    ON public.matchmaking_introductions (match_id)
    WHERE match_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_matchmaking_introductions_sweep
    ON public.matchmaking_introductions (decision_expires_at)
    WHERE status = 'PROPOSED';


-- =============================================================================
-- 6. MATCHMAKING VIDEO CALLS
-- =============================================================================

/* Platform is generic: IN_APP = in-app call powered by the configured video
   provider (video.platform — vendor names never persist); EXTERNAL = a
   third-party link-based service (ZOOM / GOOGLE_MEET). */
CREATE TABLE IF NOT EXISTS public.matchmaking_video_calls (
    id                          UUID        NOT NULL DEFAULT gen_random_uuid(),
    introduction_id             UUID        NOT NULL,
    arranged_by_admin_user_id   UUID        NULL,
    platform                    VARCHAR(30) NULL,
    mode                        VARCHAR(30) NOT NULL DEFAULT 'PRIVATE',

    /* Opaque channel/room name for IN_APP, minted at arrange time;
       join credentials are minted on demand, never stored. */
    channel_name                VARCHAR(255) NULL,

    /* EXTERNAL only — service label + join URL. */
    external_provider           VARCHAR(50) NULL,
    external_meeting_url        TEXT        NULL,

    /* Optional EXTERNAL passcode/PIN (Zoom passcode, dial-in code). */
    external_access_code        VARCHAR(120) NULL,

    requester_outcome           VARCHAR(20) NULL,
    candidate_outcome           VARCHAR(20) NULL,
    outcome_expires_at          TIMESTAMPTZ NULL,
    status                      VARCHAR(30) NOT NULL DEFAULT 'ARRANGING',

    scheduled_at                TIMESTAMPTZ NULL,
    /* IANA timezone label agreed during chat coordination — display only;
       scheduled_at stays the UTC instant of truth. */
    scheduled_tz                VARCHAR(50) NULL,

    started_at                  TIMESTAMPTZ NULL,
    ended_at                    TIMESTAMPTZ NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT matchmaking_video_calls_pkey
        PRIMARY KEY (id),

    CONSTRAINT matchmaking_video_calls_introduction_fkey
        FOREIGN KEY (introduction_id)
        REFERENCES public.matchmaking_introductions(id)
        ON DELETE CASCADE,

    CONSTRAINT matchmaking_video_calls_admin_fkey
        FOREIGN KEY (arranged_by_admin_user_id)
        REFERENCES public.app_users(id)
        ON DELETE RESTRICT,

    CONSTRAINT matchmaking_video_calls_platform_check
        CHECK (
            platform IS NULL
            OR platform IN ('IN_APP', 'EXTERNAL')
        ),

    CONSTRAINT matchmaking_video_calls_external_provider_check
        CHECK (
            external_provider IS NULL
            OR external_provider IN ('ZOOM', 'GOOGLE_MEET')
        ),

    CONSTRAINT matchmaking_video_calls_outcome_check
        CHECK (
            (requester_outcome IS NULL OR requester_outcome IN ('CONTINUE', 'NOT_A_FIT'))
            AND (candidate_outcome IS NULL OR candidate_outcome IN ('CONTINUE', 'NOT_A_FIT'))
            AND (
                status = 'COMPLETED'
                OR (requester_outcome IS NULL AND candidate_outcome IS NULL)
            )
        ),

    CONSTRAINT matchmaking_video_calls_mode_check
        CHECK (mode IN ('PRIVATE', 'WITH_MATCHMAKER')),

    CONSTRAINT matchmaking_video_calls_status_check
        CHECK (status IN (
            'ARRANGING', 'SCHEDULED', 'IN_PROGRESS',
            'COMPLETED', 'CANCELLED', 'NO_SHOW', 'FAILED'
        )),

    /* Platform details are only required once the call has actually been
       arranged — while ARRANGING, platform/channel/URL may all be NULL.
         IN_APP   → channel_name
         EXTERNAL → external_provider (ZOOM/GOOGLE_MEET) + meeting URL
                    (+ optional external_access_code) */
    CONSTRAINT matchmaking_video_calls_platform_data_check
        CHECK (
            status NOT IN ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED')
            OR (
                (
                    platform = 'IN_APP'
                    AND channel_name IS NOT NULL
                )
                OR
                (
                    platform = 'EXTERNAL'
                    AND external_provider IN ('ZOOM', 'GOOGLE_MEET')
                    AND external_meeting_url IS NOT NULL
                )
            )
        )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_matchmaking_active_video_per_intro
    ON public.matchmaking_video_calls (introduction_id)
    WHERE status IN ('ARRANGING', 'SCHEDULED', 'IN_PROGRESS');

CREATE INDEX IF NOT EXISTS idx_matchmaking_video_calls_outcome_sweep
    ON public.matchmaking_video_calls (outcome_expires_at)
    WHERE status = 'COMPLETED';

CREATE INDEX IF NOT EXISTS idx_matchmaking_video_calls_arrange
    ON public.matchmaking_video_calls (status, created_at);


-- =============================================================================
-- 7. MATCHES TABLE ALTERATIONS
-- =============================================================================

-- Drop NOT NULL on like-action columns so matchmaking matches need no likes.
ALTER TABLE public.matches
    ALTER COLUMN user_one_like_action_id DROP NOT NULL,
    ALTER COLUMN user_two_like_action_id DROP NOT NULL,
    ALTER COLUMN created_by_action_id    DROP NOT NULL;

-- Add matchmaking_introduction_id FK.
ALTER TABLE public.matches
    ADD COLUMN IF NOT EXISTS matchmaking_introduction_id UUID NULL
        REFERENCES public.matchmaking_introductions(id)
        ON DELETE SET NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_match_matchmaking_introduction
    ON public.matches (matchmaking_introduction_id)
    WHERE matchmaking_introduction_id IS NOT NULL;

-- Extend match_source to allow MATCHMAKING.
ALTER TABLE public.matches
    DROP CONSTRAINT IF EXISTS matches_match_source_check;

ALTER TABLE public.matches
    ADD CONSTRAINT matches_match_source_check
        CHECK (match_source IN ('DISCOVERY', 'BLIND_DATE', 'MATCHMAKING'));


-- =============================================================================
-- 8. FEATURE ACTIONS SEED
-- =============================================================================

INSERT INTO public.feature_actions (code, name, type, description)
VALUES
    ('PERSONAL_MATCHMAKING_REQUEST',
     'Personal Matchmaking Request',
     'ACTION',
     'Personal matchmaking service fee — non-refundable search cost'),
    ('PERSONAL_MATCHMAKING_VIDEO_ESCROW',
     'Personal Matchmaking Video Escrow',
     'ACTION',
     'Prepaid video introduction escrow — refunded if no call is delivered')
ON CONFLICT (code) DO NOTHING;


-- =============================================================================
-- 9. CREDIT SOURCE TYPE: MATCHMAKING_ESCROW_REFUND
-- =============================================================================

ALTER TABLE public.user_entitlement_credit_lots
    DROP CONSTRAINT IF EXISTS user_entitlement_credit_lots_credit_source_type_check;

ALTER TABLE public.user_entitlement_credit_lots
    ADD CONSTRAINT user_entitlement_credit_lots_credit_source_type_check
        CHECK (
            credit_source_type IS NULL
            OR credit_source_type IN (
                'SUBSCRIPTION_ALLOWANCE',
                'CREDIT_PURCHASE',
                'PROMOTION',
                'ADMIN_ADJUSTMENT',
                'PROMOTION_REWARD',
                'MATCHMAKING_ESCROW_REFUND'
            )
        );


-- =============================================================================
-- 10. PLAN LIMIT/COST SEEDS — matchmaking actions
-- =============================================================================
--
-- Without a subscription_plan_limit_and_cost row an action evaluates to FREE
-- (ActionCostService "defaulting free" path) and is invisible in the client
-- entitlement response — so both matchmaking charges must be seeded here.
--
-- TODO(pricing): placeholder credit amounts — adjust before apply.
-- Matchmaking is a paid concierge service: same cost on every plan
-- (no free allowance, limit_value NULL = unlimited so member cost always
-- applies; the one-active-request unique index enforces serialization).
-- Clients read the split from entitlements.limits_and_costs:
--   PERSONAL_MATCHMAKING_REQUEST       → non-refundable search fee
--   PERSONAL_MATCHMAKING_VIDEO_ESCROW  → refundable video escrow

INSERT INTO public.subscription_plan_limit_and_cost
    (subscription_plan_id, feature_action_id, member_credit_cost,
     actual_credit_cost, limit_value, period_type, apply_credit_after_limit)
SELECT sp.id, fa.id, vals.member_cost, vals.actual_cost, vals.lv, vals.pt, vals.acal
FROM (VALUES
    ('PERSONAL_MATCHMAKING_REQUEST',      500::BIGINT, 500::BIGINT, NULL::INTEGER, 'DAY', TRUE),
    ('PERSONAL_MATCHMAKING_VIDEO_ESCROW', 250::BIGINT, 250::BIGINT, NULL::INTEGER, 'DAY', TRUE)
) AS vals(action_code, member_cost, actual_cost, lv, pt, acal)
CROSS JOIN public.subscription_plans sp
JOIN public.feature_actions fa ON fa.code = vals.action_code
WHERE sp.is_active = TRUE
ON CONFLICT (subscription_plan_id, feature_action_id) DO NOTHING;

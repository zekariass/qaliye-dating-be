

-- =============================================================================
-- QALIYE / HABESHA DRIVE - UPDATED BASELINE SCHEMA
-- PostgreSQL + Supabase + Spring Boot
--
-- This is a clean baseline schema for a NEW database.
-- Do not run it on an existing production database as a patch. Convert it into
-- versioned migrations (for example, Flyway V1__baseline.sql) and create a
-- separate migration plan for existing data.
--
-- Architecture:
--   * Supabase Auth owns credentials and sessions.
--   * Spring Boot owns all application reads/writes and uses Supabase JWTs.
--   * Direct client access is limited to Supabase Auth and approved chat-message
--     reads/realtime events.
--   * Private object storage uses storage paths as the source of truth; Spring
--     Boot generates short-lived signed URLs in API DTOs.
--
-- Core design decisions:
--   * app_users.address_id is the single address reference for a user.
--   * addresses does NOT contain user_id.
--   * Supported profile genders: MALE and FEMALE only.
--   * discovery_preferences.interested_in_gender is exactly one value:
--     MALE or FEMALE.
--   * Swipe actions are historical. Rewind marks an action REVERSED instead of
--     deleting it.
--   * Matches are historical. A fresh match can be created after a rewind
--     cancellation, while an established unmatch is retained for audit.
-- =============================================================================


-- =============================================================================
-- 1. EXTENSIONS AND SHARED FUNCTIONS
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "postgis";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";
CREATE EXTENSION IF NOT EXISTS "btree_gist";


CREATE OR REPLACE FUNCTION public.update_updated_at_column()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;


-- Convenience function for backend SQL projections. Age must never be persisted
-- because it changes over time.
CREATE OR REPLACE FUNCTION public.calculate_age(
    p_date_of_birth DATE,
    p_as_of_date DATE DEFAULT CURRENT_DATE
)
RETURNS INTEGER
LANGUAGE sql
STABLE
AS $$
    SELECT EXTRACT(YEAR FROM age(p_as_of_date, p_date_of_birth))::INTEGER;
$$;


-- =============================================================================
-- 2. SUBSCRIPTIONS, USERS, LOCATIONS, AND ADDRESSES
-- =============================================================================

CREATE TABLE public.subscription_plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL CHECK (char_length(BTRIM(name)) BETWEEN 1 AND 100),
    plan_code VARCHAR(50) NOT NULL CHECK (char_length(BTRIM(plan_code)) BETWEEN 1 AND 50),
    country_code VARCHAR(10) NOT NULL DEFAULT 'GLOBAL',

    -- FREE plans are fallback plans. PAID plans are referenced by active
    -- user_subscriptions. A user never needs a user_subscriptions row for FREE.
    plan_kind VARCHAR(20) NOT NULL CHECK (
        plan_kind IN ('FREE', 'PAID')
    ),

    -- Monetary values are represented in the currency's minor units.
    price_minor_units INTEGER NOT NULL CHECK (price_minor_units >= 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    billing_interval VARCHAR(20) NOT NULL CHECK (
        billing_interval IN ('NONE', 'WEEKLY', 'MONTHLY', 'YEARLY')
    ),

    -- Use subscription_plan_limits, not this JSON field, as the source of truth
    -- for daily action quotas. features is reserved for non-quota UI/feature flags.
    features JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (jsonb_typeof(features) = 'object'),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_plan_code_per_country UNIQUE (plan_code, country_code),
    CONSTRAINT check_subscription_plan_kind_and_billing CHECK (
        (plan_kind = 'FREE'
            AND price_minor_units = 0
            AND billing_interval = 'NONE')
        OR
        (plan_kind = 'PAID'
            AND billing_interval IN ('WEEKLY', 'MONTHLY', 'YEARLY'))
    )
);


-- Explicit quota configuration. Each ACTIVE plan must have one row for every
-- supported limit type. NULL limit_value means unlimited.
CREATE TABLE public.subscription_plan_limits (
    plan_id UUID NOT NULL REFERENCES public.subscription_plans(id) ON DELETE CASCADE,
    limit_type VARCHAR(30) NOT NULL CHECK (
        limit_type IN (
            'DAILY_LIKES',
            'DAILY_SUPERLIKES',
            'DAILY_REWINDS'
        )
    ),
    limit_value INTEGER CHECK (
        limit_value IS NULL OR limit_value >= 0
    ),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (plan_id, limit_type)
);


-- One active fallback FREE plan may exist per country. The backend resolves a
-- user's paid plan first; otherwise it selects a country-specific FREE plan,
-- falling back to the GLOBAL FREE plan.
CREATE UNIQUE INDEX unique_active_free_plan_per_country
    ON public.subscription_plans(country_code)
    WHERE plan_kind = 'FREE' AND is_active = TRUE;


-- Supabase Auth owns email, phone, passwords, OTPs, and OAuth identity.
-- address_id is added after public.addresses is created to avoid a circular
-- table-creation dependency.
CREATE TABLE public.app_users (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (
        status IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED', 'BANNED')
    ),
    role VARCHAR(20) NOT NULL DEFAULT 'USER' CHECK (
        role IN ('USER', 'MODERATOR', 'ADMIN')
    ),
    preferred_language VARCHAR(10) NOT NULL DEFAULT 'en' CHECK (
        preferred_language IN ('en', 'am', 'ti', 'om')
    ),
    last_active_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- Searchable city/country options for manual location selection.
-- The backend copies the trusted centroid to addresses.coords after the user
-- selects a location_place_id.
CREATE TABLE public.location_places (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    country_code VARCHAR(2) NOT NULL,
    country_name VARCHAR(100) NOT NULL CHECK (char_length(BTRIM(country_name)) BETWEEN 1 AND 100),
    region VARCHAR(100),
    city VARCHAR(100) NOT NULL CHECK (char_length(BTRIM(city)) BETWEEN 1 AND 100),
    display_name TEXT NOT NULL CHECK (char_length(BTRIM(display_name)) BETWEEN 1 AND 300),
    alternative_names TEXT,
    coords GEOGRAPHY(Point, 4326) NOT NULL,
    location_precision VARCHAR(20) NOT NULL DEFAULT 'CITY' CHECK (
        location_precision IN ('CITY', 'REGION', 'COUNTRY')
    ),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_location_place
        UNIQUE NULLS NOT DISTINCT (country_code, region, city)
);


-- An address has no user_id. A user owns at most one address through
-- app_users.address_id, which is unique.
-- Exact coordinates are backend-only and must never be returned directly to
-- mobile clients.
CREATE TABLE public.addresses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    location_place_id UUID REFERENCES public.location_places(id) ON DELETE SET NULL,

    country_code VARCHAR(2) NOT NULL,
    country_name VARCHAR(100) NOT NULL CHECK (char_length(BTRIM(country_name)) BETWEEN 1 AND 100),
    city VARCHAR(100) NOT NULL CHECK (char_length(BTRIM(city)) BETWEEN 1 AND 100),
    region VARCHAR(100),
    coords GEOGRAPHY(Point, 4326) NOT NULL,
    formatted_address TEXT,

    location_source VARCHAR(50) NOT NULL DEFAULT 'GPS' CHECK (
        location_source IN ('GPS', 'MANUAL', 'IP')
    ),
    location_precision VARCHAR(20) NOT NULL DEFAULT 'EXACT' CHECK (
        location_precision IN ('EXACT', 'CITY', 'REGION', 'COUNTRY', 'APPROXIMATE')
    ),
    accuracy_m NUMERIC(10, 2) CHECK (accuracy_m IS NULL OR accuracy_m >= 0),
    location_updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_manual_location_has_place CHECK (
        location_source <> 'MANUAL' OR location_place_id IS NOT NULL
    ),
    CONSTRAINT check_non_gps_has_no_accuracy CHECK (
        location_source = 'GPS' OR accuracy_m IS NULL
    ),
    CONSTRAINT check_manual_location_not_exact CHECK (
        location_source <> 'MANUAL'
        OR location_precision IN ('CITY', 'REGION', 'COUNTRY')
    )
);


ALTER TABLE public.app_users
    ADD COLUMN address_id UUID REFERENCES public.addresses(id) ON DELETE SET NULL;

ALTER TABLE public.app_users
    ADD CONSTRAINT unique_user_address UNIQUE (address_id);


-- =============================================================================
-- 3. USER PROFILES, PHOTOS, AND DISCOVERY PREFERENCES
-- =============================================================================

CREATE TABLE public.profiles (
    user_id UUID PRIMARY KEY REFERENCES public.app_users(id) ON DELETE RESTRICT,

    display_name VARCHAR(100) NOT NULL CHECK (char_length(BTRIM(display_name)) BETWEEN 1 AND 100),
    gender VARCHAR(20) NOT NULL CHECK (gender IN ('MALE', 'FEMALE')),
    date_of_birth DATE NOT NULL,
    bio TEXT CHECK (bio IS NULL OR char_length(BTRIM(bio)) <= 2000),

    height_cm INTEGER CHECK (height_cm BETWEEN 100 AND 250),
    residency_type VARCHAR(20) NOT NULL CHECK (
        residency_type IN ('ETHIOPIA', 'ERITREA', 'DIASPORA')
    ),

    ethnicity VARCHAR(100),
    nationality VARCHAR(100),
    religion VARCHAR(50),
    education_level VARCHAR(50),
    occupation VARCHAR(100),
    relationship_intention VARCHAR(50) NOT NULL CHECK (
        relationship_intention IN (
            'MARRIAGE',
            'SERIOUS_RELATIONSHIP',
            'LONG_TERM',
            'FRIENDSHIP',
            'NOT_SURE_YET'
        )
    ),
    marital_status VARCHAR(50),

    has_children BOOLEAN,
    wants_children BOOLEAN,
    smoking VARCHAR(20) CHECK (
        smoking IS NULL OR smoking IN ('NO', 'YES', 'OCCASIONALLY', 'TRYING_TO_QUIT')
    ),
    drinking VARCHAR(20) CHECK (
        drinking IS NULL OR drinking IN ('NO', 'SOCIALLY', 'OCCASIONALLY', 'YES')
    ),

    activity_level VARCHAR(20) CHECK (
        activity_level IS NULL OR activity_level IN ('SEDENTARY', 'LIGHT', 'MODERATE', 'ACTIVE', 'VERY_ACTIVE')
    ),
    interests TEXT[] NOT NULL DEFAULT '{}'::TEXT[],
    languages TEXT[] NOT NULL DEFAULT '{}'::TEXT[],

    is_visible BOOLEAN NOT NULL DEFAULT FALSE,
    is_onboarded BOOLEAN NOT NULL DEFAULT FALSE,
    -- Denormalized backend-maintained flag. The source records are in
    -- user_verifications; update both in one service transaction.
    is_verified BOOLEAN NOT NULL DEFAULT FALSE,

    profile_completion_score INTEGER NOT NULL DEFAULT 0 CHECK (
        profile_completion_score BETWEEN 0 AND 100
    ),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT visible_profile_must_be_onboarded CHECK (
        NOT is_visible OR is_onboarded
    )
);


-- Private storage only. image_url is intentionally NOT stored in this table.
-- Spring Boot returns a signed URL in the API response when allowed.
CREATE TABLE public.profile_photos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,

    storage_bucket VARCHAR(100) NOT NULL DEFAULT 'profile-photos',
    storage_path TEXT NOT NULL,

    photo_order INTEGER NOT NULL CHECK (photo_order BETWEEN 0 AND 8),
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,

    moderation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        moderation_status IN ('PENDING', 'APPROVED', 'REJECTED')
    ),
    reviewed_by UUID REFERENCES public.app_users(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMPTZ,
    rejection_reason TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (jsonb_typeof(metadata) = 'object'),

    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_profile_photo_storage_object UNIQUE (storage_bucket, storage_path),
    CONSTRAINT rejected_photo_cannot_be_primary CHECK (
        NOT is_primary OR moderation_status <> 'REJECTED'
    )
);


-- Preferences are created or completed during onboarding. There is intentionally
-- no arbitrary default for interested_in_gender; it must be explicitly selected.
CREATE TABLE public.discovery_preferences (
    user_id UUID PRIMARY KEY REFERENCES public.app_users(id) ON DELETE RESTRICT,

    discovery_mode VARCHAR(20) NOT NULL DEFAULT 'PUBLIC' CHECK (
        discovery_mode IN ('PUBLIC', 'INCOGNITO')
    ),

    -- Default includes every supported residency category. The mobile client may
    -- choose one or multiple categories; discovery API filters can further apply
    -- a requested scope such as NEARBY, ETHIOPIA, ERITREA, or DIASPORA.
    preferred_residency_types TEXT[] NOT NULL DEFAULT
        ARRAY['ETHIOPIA', 'ERITREA', 'DIASPORA']::TEXT[]
        CHECK (
            cardinality(preferred_residency_types) BETWEEN 1 AND 3
            AND preferred_residency_types <@
                ARRAY['ETHIOPIA', 'ERITREA', 'DIASPORA']::TEXT[]
            AND array_position(preferred_residency_types, NULL) IS NULL
        ),

    -- Exactly one supported target gender.
    interested_in_gender VARCHAR(20) NOT NULL CHECK (
        interested_in_gender IN ('MALE', 'FEMALE')
    ),

    min_age INTEGER NOT NULL DEFAULT 18 CHECK (min_age >= 18),
    max_age INTEGER NOT NULL DEFAULT 99 CHECK (max_age <= 120),
    max_distance_km INTEGER NOT NULL DEFAULT 50 CHECK (max_distance_km > 0),

    open_to_long_distance BOOLEAN NOT NULL DEFAULT FALSE,
    open_to_relocation BOOLEAN NOT NULL DEFAULT FALSE,
    show_verified_only BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_discovery_age_range CHECK (min_age <= max_age)
);


-- Age compliance is enforced in the database, not only in the mobile client.
CREATE OR REPLACE FUNCTION public.verify_profile_age_compliance()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.date_of_birth > (CURRENT_DATE - INTERVAL '18 years')::DATE THEN
        RAISE EXCEPTION
            'Age Compliance Violation: User profile registration requires a minimum age of 18 years.';
    END IF;

    IF NEW.date_of_birth < (CURRENT_DATE - INTERVAL '120 years')::DATE THEN
        RAISE EXCEPTION
            'Age Compliance Violation: Date of birth is outside the supported range.';
    END IF;

    RETURN NEW;
END;
$$;


-- A profile can enter discovery only when onboarding data, a single user
-- address, explicit preferences, and an approved primary photo exist.
-- The trigger is deferred so Spring Boot can create/update these related records
-- in one transaction and the database validates the final committed state.
CREATE OR REPLACE FUNCTION public.validate_visible_profile_dependencies()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_user_id UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        CASE TG_TABLE_NAME
            WHEN 'profiles' THEN v_user_id := OLD.user_id;
            WHEN 'profile_photos' THEN v_user_id := OLD.user_id;
            WHEN 'discovery_preferences' THEN v_user_id := OLD.user_id;
            WHEN 'app_users' THEN v_user_id := OLD.id;
            ELSE
                RAISE EXCEPTION 'Unsupported table for visible-profile validation: %', TG_TABLE_NAME;
        END CASE;
    ELSE
        CASE TG_TABLE_NAME
            WHEN 'profiles' THEN v_user_id := NEW.user_id;
            WHEN 'profile_photos' THEN v_user_id := NEW.user_id;
            WHEN 'discovery_preferences' THEN v_user_id := NEW.user_id;
            WHEN 'app_users' THEN v_user_id := NEW.id;
            ELSE
                RAISE EXCEPTION 'Unsupported table for visible-profile validation: %', TG_TABLE_NAME;
        END CASE;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.profiles p
        WHERE p.user_id = v_user_id
          AND p.is_visible = TRUE
          AND p.is_onboarded = TRUE
    ) THEN
        IF NOT EXISTS (
            SELECT 1
            FROM public.app_users au
            WHERE au.id = v_user_id
              AND au.status = 'ACTIVE'
              AND au.address_id IS NOT NULL
        ) THEN
            RAISE EXCEPTION
                'A visible profile requires an active user account with one address.';
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM public.discovery_preferences dp
            WHERE dp.user_id = v_user_id
        ) THEN
            RAISE EXCEPTION
                'A visible profile requires discovery preferences.';
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM public.profile_photos pp
            WHERE pp.user_id = v_user_id
              AND pp.deleted_at IS NULL
              AND pp.is_primary = TRUE
              AND pp.moderation_status = 'APPROVED'
        ) THEN
            RAISE EXCEPTION
                'A visible profile requires an approved primary photo.';
        END IF;
    END IF;

    RETURN NULL;
END;
$$;


-- =============================================================================
-- 4. SWIPING, REWINDS, AND DAILY LIMITS
-- =============================================================================

CREATE TABLE public.user_discovery_actions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    actor_user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
    target_user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,

    action_type VARCHAR(20) NOT NULL CHECK (
        action_type IN ('LIKE', 'PASS', 'SUPERLIKE')
    ),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (
        status IN ('ACTIVE', 'REVERSED')
    ),

    -- Generated on the device. This makes retrying a request idempotent.
    client_action_id UUID NOT NULL,

    reversed_at TIMESTAMPTZ,
    reversed_reason VARCHAR(30) CHECK (
        reversed_reason IN ('USER_REWIND', 'SYSTEM', 'ADMIN')
    ),

    metadata JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (jsonb_typeof(metadata) = 'object'),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_not_self_discovery_action CHECK (
        actor_user_id <> target_user_id
    ),
    CONSTRAINT check_reversal_state CHECK (
        (status = 'ACTIVE' AND reversed_at IS NULL AND reversed_reason IS NULL)
        OR
        (status = 'REVERSED' AND reversed_at IS NOT NULL AND reversed_reason IS NOT NULL)
    ),
    CONSTRAINT unique_discovery_client_action UNIQUE (
        actor_user_id, client_action_id
    )
);


-- Daily limits use UTC dates. Spring Boot must lock/update this row in the same
-- transaction as a like, super-like, or rewind.
CREATE TABLE public.user_daily_limits (
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
    limit_date DATE NOT NULL DEFAULT ((CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::DATE),

    likes_used INTEGER NOT NULL DEFAULT 0 CHECK (likes_used >= 0),
    super_likes_used INTEGER NOT NULL DEFAULT 0 CHECK (super_likes_used >= 0),
    rewinds_used INTEGER NOT NULL DEFAULT 0 CHECK (rewinds_used >= 0),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (user_id, limit_date)
);


-- Discovery actions are immutable except for ACTIVE -> REVERSED.
CREATE OR REPLACE FUNCTION public.enforce_discovery_action_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.actor_user_id IS DISTINCT FROM OLD.actor_user_id
       OR NEW.target_user_id IS DISTINCT FROM OLD.target_user_id
       OR NEW.action_type IS DISTINCT FROM OLD.action_type
       OR NEW.client_action_id IS DISTINCT FROM OLD.client_action_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Discovery action identity fields are immutable.';
    END IF;

    IF OLD.status = 'REVERSED' AND NEW.status <> 'REVERSED' THEN
        RAISE EXCEPTION 'A reversed discovery action cannot be reactivated.';
    END IF;

    RETURN NEW;
END;
$$;


-- =============================================================================
-- 5. BLOCKS, MATCHES, AND MESSAGES
-- =============================================================================

-- Blocks are historical. Only one ACTIVE block can exist in a direction.
CREATE TABLE public.user_blocks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    blocker_user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
    blocked_user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,

    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (
        status IN ('ACTIVE', 'REVOKED')
    ),
    reason TEXT,
    revoked_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_not_self_block CHECK (
        blocker_user_id <> blocked_user_id
    ),
    CONSTRAINT check_block_revocation_state CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL)
        OR
        (status = 'REVOKED' AND revoked_at IS NOT NULL)
    )
);


-- user_one_id and user_two_id are always UUID-sorted before insert.
-- Each row is one match lifecycle. An ended match remains as history.
CREATE TABLE public.matches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    user_one_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
    user_two_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,

    -- Both like actions that formed the match.
    user_one_like_action_id UUID NOT NULL
        REFERENCES public.user_discovery_actions(id) ON DELETE RESTRICT,
    user_two_like_action_id UUID NOT NULL
        REFERENCES public.user_discovery_actions(id) ON DELETE RESTRICT,

    -- The later action that caused the mutual-like check to create this match.
    created_by_action_id UUID NOT NULL
        REFERENCES public.user_discovery_actions(id) ON DELETE RESTRICT,

    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (
        status IN ('ACTIVE', 'ENDED')
    ),
    end_reason VARCHAR(30) CHECK (
        end_reason IN (
            'USER_UNMATCH',
            'CANCELLED_BY_REWIND',
            'BLOCKED',
            'ADMIN_ACTION'
        )
    ),
    ended_by_user_id UUID REFERENCES public.app_users(id) ON DELETE SET NULL,

    matched_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,

    -- Set by Spring Boot when a fresh match is created. A normal rewind is
    -- allowed only until this timestamp and only before the first message.
    rewind_eligible_until TIMESTAMPTZ,

    first_message_at TIMESTAMPTZ,
    last_message_at TIMESTAMPTZ,
    user_one_last_read_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_two_last_read_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_distinct_match_users CHECK (user_one_id <> user_two_id),
    CONSTRAINT check_match_user_order CHECK (user_one_id < user_two_id),
    CONSTRAINT check_match_status_end_state CHECK (
        (status = 'ACTIVE'
            AND ended_at IS NULL
            AND end_reason IS NULL
            AND ended_by_user_id IS NULL)
        OR
        (status = 'ENDED'
            AND ended_at IS NOT NULL
            AND end_reason IS NOT NULL)
    ),
    CONSTRAINT unique_match_creator_action UNIQUE (created_by_action_id)
);


-- Validates that each match is backed by two current active like/super-like
-- actions for exactly the same canonical user pair and that no active block
-- exists in either direction.
CREATE OR REPLACE FUNCTION public.validate_match_like_actions()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_user_one_action public.user_discovery_actions%ROWTYPE;
    v_user_two_action public.user_discovery_actions%ROWTYPE;
BEGIN
    SELECT *
    INTO v_user_one_action
    FROM public.user_discovery_actions
    WHERE id = NEW.user_one_like_action_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'user_one_like_action_id must exist.';
    END IF;

    SELECT *
    INTO v_user_two_action
    FROM public.user_discovery_actions
    WHERE id = NEW.user_two_like_action_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'user_two_like_action_id must exist.';
    END IF;

    IF v_user_one_action.actor_user_id <> NEW.user_one_id
       OR v_user_one_action.target_user_id <> NEW.user_two_id
       OR v_user_one_action.action_type NOT IN ('LIKE', 'SUPERLIKE')
       OR v_user_one_action.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'user_one_like_action_id is not an active like for this match pair.';
    END IF;

    IF v_user_two_action.actor_user_id <> NEW.user_two_id
       OR v_user_two_action.target_user_id <> NEW.user_one_id
       OR v_user_two_action.action_type NOT IN ('LIKE', 'SUPERLIKE')
       OR v_user_two_action.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'user_two_like_action_id is not an active like for this match pair.';
    END IF;

    IF NEW.created_by_action_id NOT IN (
        NEW.user_one_like_action_id,
        NEW.user_two_like_action_id
    ) THEN
        RAISE EXCEPTION 'created_by_action_id must be one of the two match like actions.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.user_blocks ub
        WHERE ub.status = 'ACTIVE'
          AND (
              (ub.blocker_user_id = NEW.user_one_id
               AND ub.blocked_user_id = NEW.user_two_id)
              OR
              (ub.blocker_user_id = NEW.user_two_id
               AND ub.blocked_user_id = NEW.user_one_id)
          )
    ) THEN
        RAISE EXCEPTION 'A match cannot be created for a blocked user pair.';
    END IF;

    RETURN NEW;
END;
$$;


-- Match identity is immutable. A match may move from ACTIVE to ENDED once,
-- but an ended match cannot be reactivated or rewritten into a different pair.
CREATE OR REPLACE FUNCTION public.enforce_match_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.user_one_id IS DISTINCT FROM OLD.user_one_id
       OR NEW.user_two_id IS DISTINCT FROM OLD.user_two_id
       OR NEW.user_one_like_action_id IS DISTINCT FROM OLD.user_one_like_action_id
       OR NEW.user_two_like_action_id IS DISTINCT FROM OLD.user_two_like_action_id
       OR NEW.created_by_action_id IS DISTINCT FROM OLD.created_by_action_id
       OR NEW.matched_at IS DISTINCT FROM OLD.matched_at
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Match identity fields are immutable.';
    END IF;

    IF OLD.status = 'ENDED' AND NEW.status <> 'ENDED' THEN
        RAISE EXCEPTION 'An ended match cannot be reactivated.';
    END IF;

    IF OLD.status = 'ENDED'
       AND (
           NEW.end_reason IS DISTINCT FROM OLD.end_reason
           OR NEW.ended_at IS DISTINCT FROM OLD.ended_at
           OR NEW.ended_by_user_id IS DISTINCT FROM OLD.ended_by_user_id
       ) THEN
        RAISE EXCEPTION 'An ended match cannot have its end state rewritten.';
    END IF;

    RETURN NEW;
END;
$$;


-- Deferred guard: Spring Boot may reverse a matching action and end the match
-- in one transaction. At commit, no ACTIVE match may reference a reversed action.
CREATE OR REPLACE FUNCTION public.validate_active_match_action_states()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.matches m
        JOIN public.user_discovery_actions a1
            ON a1.id = m.user_one_like_action_id
        JOIN public.user_discovery_actions a2
            ON a2.id = m.user_two_like_action_id
        WHERE m.status = 'ACTIVE'
          AND (a1.status <> 'ACTIVE' OR a2.status <> 'ACTIVE')
    ) THEN
        RAISE EXCEPTION
            'An active match cannot reference a reversed discovery action.';
    END IF;

    RETURN NULL;
END;
$$;


-- A block ends any current match immediately. The Spring Boot block service must
-- also write an audit record in the same transaction.
CREATE OR REPLACE FUNCTION public.end_active_matches_when_blocked()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'ACTIVE'
       AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM 'ACTIVE') THEN

        UPDATE public.matches
        SET status = 'ENDED',
            end_reason = 'BLOCKED',
            ended_by_user_id = NEW.blocker_user_id,
            ended_at = CURRENT_TIMESTAMP,
            updated_at = CURRENT_TIMESTAMP
        WHERE status = 'ACTIVE'
          AND (
              (user_one_id = NEW.blocker_user_id AND user_two_id = NEW.blocked_user_id)
              OR
              (user_one_id = NEW.blocked_user_id AND user_two_id = NEW.blocker_user_id)
          );
    END IF;

    RETURN NEW;
END;
$$;


-- Chat content remains after an ended match for audit/retention, but direct
-- client reads are allowed only while the match is ACTIVE.
CREATE TABLE public.messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    match_id UUID NOT NULL REFERENCES public.matches(id) ON DELETE RESTRICT,
    sender_user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,

    -- Generated by the client for idempotent sends.
    client_message_id UUID NOT NULL,

    message_type VARCHAR(20) NOT NULL DEFAULT 'TEXT' CHECK (
        message_type IN ('TEXT', 'IMAGE', 'VOICE', 'ICEBREAKER', 'PROMPT_REPLY')
    ),
    body TEXT,

    -- Private chat media. No public media_url is stored.
    storage_bucket VARCHAR(100),
    storage_path TEXT,

    moderation_status VARCHAR(20) NOT NULL DEFAULT 'APPROVED' CHECK (
        moderation_status IN ('PENDING', 'APPROVED', 'REJECTED_FLAGGED')
    ),
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (jsonb_typeof(metadata) = 'object'),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    edited_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    deleted_by_user_id UUID REFERENCES public.app_users(id) ON DELETE SET NULL,

    CONSTRAINT unique_sender_client_message UNIQUE (
        sender_user_id, client_message_id
    ),
    CONSTRAINT unique_message_storage_object UNIQUE (
        storage_bucket, storage_path
    ),
    CONSTRAINT check_message_storage_bucket_and_path_together CHECK (
        (storage_bucket IS NULL AND storage_path IS NULL)
        OR
        (storage_bucket IS NOT NULL AND storage_path IS NOT NULL)
    ),
    CONSTRAINT check_message_content_by_type CHECK (
        (
            message_type IN ('TEXT', 'ICEBREAKER', 'PROMPT_REPLY')
            AND NULLIF(BTRIM(body), '') IS NOT NULL
            AND storage_bucket IS NULL
            AND storage_path IS NULL
        )
        OR
        (
            message_type IN ('IMAGE', 'VOICE')
            AND storage_bucket IS NOT NULL
            AND storage_path IS NOT NULL
        )
    )
);


CREATE OR REPLACE FUNCTION public.validate_message_sender_is_match_participant()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.matches m
        WHERE m.id = NEW.match_id
          AND m.status = 'ACTIVE'
          AND (
              m.user_one_id = NEW.sender_user_id
              OR m.user_two_id = NEW.sender_user_id
          )
    ) THEN
        RAISE EXCEPTION
            'Message sender must be a participant in an active match.';
    END IF;

    RETURN NEW;
END;
$$;


CREATE OR REPLACE FUNCTION public.touch_match_message_timestamps()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE public.matches
    SET first_message_at = COALESCE(first_message_at, NEW.created_at),
        last_message_at = NEW.created_at,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = NEW.match_id;

    RETURN NEW;
END;
$$;


-- =============================================================================
-- 6. REPORTS, VERIFICATION, DEVICES, AND AUDIT
-- =============================================================================

CREATE TABLE public.user_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Null only for AUTO_FLAGGED system reports.
    reporter_user_id UUID REFERENCES public.app_users(id) ON DELETE SET NULL,
    reported_user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,

    report_type VARCHAR(50) NOT NULL CHECK (
        report_type IN (
            'FAKE_PROFILE',
            'HARASSMENT',
            'INAPPROPRIATE_PHOTO',
            'SCAM',
            'UNDERAGE',
            'OFF_PLATFORM_SOLICITATION',
            'AUTO_FLAGGED',
            'OTHER'
        )
    ),
    description TEXT,
    related_message_id UUID REFERENCES public.messages(id) ON DELETE SET NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN ('PENDING', 'UNDER_REVIEW', 'RESOLVED_NO_ACTION', 'RESOLVED_BANNED')
    ),
    reviewed_by UUID REFERENCES public.app_users(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_not_self_report CHECK (
        reporter_user_id IS NULL OR reporter_user_id <> reported_user_id
    ),
    CONSTRAINT check_reporter_presence CHECK (
        (report_type = 'AUTO_FLAGGED' AND reporter_user_id IS NULL)
        OR
        (report_type <> 'AUTO_FLAGGED' AND reporter_user_id IS NOT NULL)
    )
);


CREATE TABLE public.user_verifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,

    verification_type VARCHAR(30) NOT NULL CHECK (
        verification_type IN ('SELFIE_MATCH', 'GOVERNMENT_ID')
    ),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED')
    ),
    provider VARCHAR(50) NOT NULL DEFAULT 'MANUAL_ADMIN',
    provider_reference_id VARCHAR(255),

    storage_bucket VARCHAR(100) NOT NULL DEFAULT 'verification-selfies',
    storage_path TEXT NOT NULL,

    submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_by UUID REFERENCES public.app_users(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMPTZ,
    rejection_reason TEXT,
    expires_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (jsonb_typeof(metadata) = 'object'),

    CONSTRAINT unique_verification_storage_object UNIQUE (storage_bucket, storage_path)
);


CREATE TABLE public.notification_devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,

    device_token TEXT NOT NULL,
    platform VARCHAR(20) NOT NULL CHECK (
        platform IN ('IOS', 'ANDROID', 'WEB')
    ),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_device_token UNIQUE (device_token)
);


-- This table is append-only. target_table is intentionally text because the
-- audit log may cover multiple application tables.
CREATE TABLE public.audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id UUID REFERENCES public.app_users(id) ON DELETE SET NULL,

    action VARCHAR(100) NOT NULL,
    target_table VARCHAR(100) NOT NULL,
    target_id UUID,
    request_id UUID,

    details JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (jsonb_typeof(details) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);


CREATE OR REPLACE FUNCTION public.prevent_audit_log_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only and cannot be updated or deleted.';
END;
$$;


-- =============================================================================
-- 7. PAYMENTS, ENTITLEMENTS, AND BOOSTS
-- =============================================================================

CREATE TABLE public.user_subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
    plan_id UUID NOT NULL REFERENCES public.subscription_plans(id) ON DELETE RESTRICT,

    provider VARCHAR(50) NOT NULL CHECK (
        provider IN (
            'STRIPE',
            'APPLE_APP_STORE',
            'GOOGLE_PLAY',
            'TELEBIRR',
            'CBE_BIRR',
            'CHAPA',
            'BANK_TRANSFER'
        )
    ),
    provider_subscription_id VARCHAR(255),

    status VARCHAR(30) NOT NULL CHECK (
        status IN ('ACTIVE', 'PAST_DUE', 'CANCELED', 'UNPAID', 'PENDING_VERIFICATION')
    ),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    current_period_start TIMESTAMPTZ NOT NULL,
    current_period_end TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_subscription_period CHECK (
        current_period_end > current_period_start
    )
);


-- A subscription record must always reference a PAID plan. FREE is selected
-- as a backend fallback and is never inserted into user_subscriptions.
CREATE OR REPLACE FUNCTION public.validate_user_subscription_paid_plan()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.subscription_plans sp
        WHERE sp.id = NEW.plan_id
          AND sp.plan_kind = 'PAID'
    ) THEN
        RAISE EXCEPTION 'user_subscriptions.plan_id must reference a PAID subscription plan.';
    END IF;

    RETURN NEW;
END;
$$;


CREATE TABLE public.transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
    subscription_id UUID REFERENCES public.user_subscriptions(id) ON DELETE SET NULL,

    payment_purpose VARCHAR(30) NOT NULL CHECK (
        payment_purpose IN ('SUBSCRIPTION', 'CONSUMABLE_PACK', 'PROFILE_BOOST')
    ),
    amount_minor_units INTEGER NOT NULL CHECK (amount_minor_units >= 0),
    currency VARCHAR(3) NOT NULL,

    provider VARCHAR(50) NOT NULL CHECK (
        provider IN (
            'STRIPE',
            'APPLE_APP_STORE',
            'GOOGLE_PLAY',
            'TELEBIRR',
            'CBE_BIRR',
            'CHAPA',
            'BANK_TRANSFER'
        )
    ),
    provider_transaction_id VARCHAR(255),

    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN ('PENDING', 'COMPLETED', 'FAILED', 'MANUAL_REVIEW', 'REFUNDED')
    ),

    -- Private receipt. Spring Boot returns a signed URL only to authorized users.
    receipt_storage_bucket VARCHAR(100),
    receipt_storage_path TEXT,
    admin_notes TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_receipt_bucket_and_path_together CHECK (
        (receipt_storage_bucket IS NULL AND receipt_storage_path IS NULL)
        OR
        (receipt_storage_bucket IS NOT NULL AND receipt_storage_path IS NOT NULL)
    )
);


CREATE TABLE public.payment_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES public.app_users(id) ON DELETE SET NULL,
    subscription_id UUID REFERENCES public.user_subscriptions(id) ON DELETE SET NULL,

    provider VARCHAR(50) NOT NULL CHECK (
        provider IN (
            'STRIPE',
            'REVENUECAT',
            'APPLE_APP_STORE',
            'GOOGLE_PLAY',
            'TELEBIRR',
            'CBE_BIRR',
            'CHAPA',
            'BANK_TRANSFER'
        )
    ),
    provider_event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,

    amount_minor_units INTEGER CHECK (amount_minor_units IS NULL OR amount_minor_units >= 0),
    currency VARCHAR(3),
    raw_payload JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (jsonb_typeof(raw_payload) = 'object'),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- Append-only ledger for paid/earned/consumed consumables. It avoids trying to
-- derive a user's entitlement balance only from payment rows.
CREATE TABLE public.user_entitlement_ledger (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,

    entitlement_type VARCHAR(30) NOT NULL CHECK (
        entitlement_type IN (
            'SUPERLIKE_CREDIT',
            'REWIND_CREDIT',
            'BOOST_CREDIT',
            'PREMIUM_ACCESS'
        )
    ),
    quantity_delta INTEGER NOT NULL CHECK (quantity_delta <> 0),
    reason VARCHAR(30) NOT NULL CHECK (
        reason IN ('PURCHASE', 'CONSUMPTION', 'REFUND', 'ADMIN_GRANT', 'EXPIRY', 'ADJUSTMENT')
    ),

    transaction_id UUID REFERENCES public.transactions(id) ON DELETE SET NULL,
    related_discovery_action_id UUID
        REFERENCES public.user_discovery_actions(id) ON DELETE SET NULL,

    idempotency_key UUID,
    expires_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (jsonb_typeof(metadata) = 'object'),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- This table retains boost history. A user cannot have overlapping boost ranges;
-- the backend should extend an existing boost or schedule a later start.
CREATE TABLE public.active_boosts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
    transaction_id UUID REFERENCES public.transactions(id) ON DELETE SET NULL,

    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_boost_period CHECK (expires_at > started_at),
    CONSTRAINT no_overlapping_boosts_per_user EXCLUDE USING GIST (
        user_id WITH =,
        tstzrange(started_at, expires_at, '[)') WITH &&
    )
);


CREATE OR REPLACE FUNCTION public.validate_boost_transaction_owner()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.transaction_id IS NOT NULL
       AND NOT EXISTS (
            SELECT 1
            FROM public.transactions t
            WHERE t.id = NEW.transaction_id
              AND t.user_id = NEW.user_id
              AND t.payment_purpose = 'PROFILE_BOOST'
              AND t.status = 'COMPLETED'
       ) THEN
        RAISE EXCEPTION
            'A boost transaction must belong to the same user, be completed, and be a PROFILE_BOOST purchase.';
    END IF;

    RETURN NEW;
END;
$$;


-- =============================================================================
-- 8. CULTURAL PROFILE PROMPTS
-- =============================================================================

CREATE TABLE public.profile_prompts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    prompt_text TEXT NOT NULL CHECK (char_length(BTRIM(prompt_text)) BETWEEN 1 AND 500),
    category VARCHAR(50) NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);


CREATE TABLE public.profile_prompt_translations (
    prompt_id UUID NOT NULL REFERENCES public.profile_prompts(id) ON DELETE CASCADE,
    locale VARCHAR(10) NOT NULL CHECK (locale IN ('en', 'am', 'ti', 'om')),
    prompt_text TEXT NOT NULL CHECK (char_length(BTRIM(prompt_text)) BETWEEN 1 AND 500),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (prompt_id, locale)
);


CREATE TABLE public.profile_prompt_answers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
    prompt_id UUID NOT NULL REFERENCES public.profile_prompts(id) ON DELETE RESTRICT,
    answer_text TEXT NOT NULL CHECK (char_length(BTRIM(answer_text)) BETWEEN 1 AND 300),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_user_prompt UNIQUE (user_id, prompt_id)
);


-- =============================================================================
-- 9. INDEXES
-- =============================================================================

-- User/account and addresses.
CREATE INDEX idx_app_users_status ON public.app_users(status);
CREATE INDEX idx_app_users_last_active ON public.app_users(last_active_at DESC);

CREATE INDEX idx_location_places_active_country_city
    ON public.location_places(country_code, city)
    WHERE is_active = TRUE;

CREATE INDEX idx_location_places_coords
    ON public.location_places USING GIST(coords);

CREATE INDEX idx_location_places_display_trgm
    ON public.location_places USING GIN (LOWER(display_name) gin_trgm_ops)
    WHERE is_active = TRUE;

CREATE INDEX idx_location_places_city_trgm
    ON public.location_places USING GIN (LOWER(city) gin_trgm_ops)
    WHERE is_active = TRUE;

CREATE INDEX idx_location_places_alternative_names_trgm
    ON public.location_places USING GIN (LOWER(COALESCE(alternative_names, '')) gin_trgm_ops)
    WHERE is_active = TRUE;

CREATE INDEX idx_addresses_coords ON public.addresses USING GIST(coords);
CREATE INDEX idx_addresses_location_place_id ON public.addresses(location_place_id);
CREATE INDEX idx_addresses_location_updated_at ON public.addresses(location_updated_at DESC);


-- Discovery profile filtering.
CREATE INDEX idx_profiles_discovery_bundle
    ON public.profiles(gender, residency_type, date_of_birth)
    WHERE is_visible = TRUE
      AND is_onboarded = TRUE;

CREATE INDEX idx_profiles_verified_discovery
    ON public.profiles(is_verified)
    WHERE is_visible = TRUE
      AND is_onboarded = TRUE;

CREATE INDEX idx_profiles_date_of_birth ON public.profiles(date_of_birth);

CREATE INDEX idx_profile_photos_user_order
    ON public.profile_photos(user_id, photo_order)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_profile_photos_approved_primary
    ON public.profile_photos(user_id)
    WHERE deleted_at IS NULL
      AND is_primary = TRUE
      AND moderation_status = 'APPROVED';

CREATE INDEX idx_profile_photos_moderation_queue
    ON public.profile_photos(moderation_status, created_at)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX unique_active_profile_photo_order
    ON public.profile_photos(user_id, photo_order)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX unique_active_primary_photo_per_user
    ON public.profile_photos(user_id)
    WHERE is_primary = TRUE
      AND deleted_at IS NULL;


-- Discovery actions, rewind stack, and limits.
CREATE UNIQUE INDEX unique_active_discovery_action_per_pair
    ON public.user_discovery_actions(actor_user_id, target_user_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_discovery_actions_actor_rewind_stack
    ON public.user_discovery_actions(actor_user_id, status, created_at DESC);

CREATE INDEX idx_discovery_actions_target_active
    ON public.user_discovery_actions(target_user_id, status, created_at DESC);

CREATE INDEX idx_user_daily_limits_date
    ON public.user_daily_limits(limit_date);


-- Blocks, matches, and messages.
CREATE UNIQUE INDEX unique_active_block_per_direction
    ON public.user_blocks(blocker_user_id, blocked_user_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_user_blocks_reverse_active
    ON public.user_blocks(blocked_user_id, blocker_user_id)
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX unique_active_match_pair
    ON public.matches(user_one_id, user_two_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_matches_user_one_status_last_message
    ON public.matches(user_one_id, status, last_message_at DESC);

CREATE INDEX idx_matches_user_two_status_last_message
    ON public.matches(user_two_id, status, last_message_at DESC);

CREATE INDEX idx_matches_status_matched_at
    ON public.matches(status, matched_at DESC);

CREATE INDEX idx_messages_match_cursor
    ON public.messages(match_id, created_at ASC, id ASC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_messages_sender_created
    ON public.messages(sender_user_id, created_at DESC);

CREATE INDEX idx_messages_moderation_scan
    ON public.messages(moderation_status, created_at DESC);


-- Reports, verification, devices, and audit.
CREATE INDEX idx_reports_reported_user
    ON public.user_reports(reported_user_id, created_at DESC);

CREATE INDEX idx_reports_status_created
    ON public.user_reports(status, created_at DESC);

CREATE INDEX idx_user_verifications_user_submitted
    ON public.user_verifications(user_id, submitted_at DESC);

CREATE INDEX idx_user_verifications_status_submitted
    ON public.user_verifications(status, submitted_at);

CREATE INDEX idx_notification_devices_user_active
    ON public.notification_devices(user_id)
    WHERE is_active = TRUE;

CREATE INDEX idx_audit_log_target
    ON public.audit_log(target_table, target_id, created_at DESC);

CREATE INDEX idx_audit_log_actor
    ON public.audit_log(actor_user_id, created_at DESC);


-- Payments, entitlements, and boosts.
CREATE UNIQUE INDEX unique_provider_subscription_reference
    ON public.user_subscriptions(provider, provider_subscription_id)
    WHERE provider_subscription_id IS NOT NULL;

CREATE INDEX idx_user_subscriptions_user_status
    ON public.user_subscriptions(user_id, status);

CREATE UNIQUE INDEX unique_active_subscription_per_user
    ON public.user_subscriptions(user_id)
    WHERE status IN ('ACTIVE', 'PENDING_VERIFICATION');

-- The primary key already indexes (plan_id, limit_type). This index supports
-- administrative views by limit type across plans.
CREATE INDEX idx_subscription_plan_limits_type
    ON public.subscription_plan_limits(limit_type, plan_id);

CREATE UNIQUE INDEX unique_provider_transaction_reference
    ON public.transactions(provider, provider_transaction_id)
    WHERE provider_transaction_id IS NOT NULL;

CREATE INDEX idx_transactions_user
    ON public.transactions(user_id, created_at DESC);

CREATE INDEX idx_transactions_status_created
    ON public.transactions(status, created_at DESC);

CREATE UNIQUE INDEX unique_provider_payment_event
    ON public.payment_events(provider, provider_event_id);

CREATE INDEX idx_payment_events_subscription
    ON public.payment_events(subscription_id, created_at DESC);

CREATE INDEX idx_entitlement_ledger_user_type_created
    ON public.user_entitlement_ledger(user_id, entitlement_type, created_at DESC);

CREATE UNIQUE INDEX unique_entitlement_idempotency_key_per_user
    ON public.user_entitlement_ledger(user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_active_boosts_user_expiry
    ON public.active_boosts(user_id, expires_at);

CREATE INDEX idx_active_boosts_expires_at
    ON public.active_boosts(expires_at);


-- Prompts.
CREATE INDEX idx_profile_prompts_active_order
    ON public.profile_prompts(is_active, display_order);

CREATE INDEX idx_profile_prompt_answers_user
    ON public.profile_prompt_answers(user_id);


-- =============================================================================
-- 10. TRIGGERS
-- =============================================================================

CREATE TRIGGER enforce_profile_age_compliance
BEFORE INSERT OR UPDATE OF date_of_birth ON public.profiles
FOR EACH ROW
EXECUTE FUNCTION public.verify_profile_age_compliance();


-- Deferred constraints keep visible profiles valid even when a transaction
-- simultaneously changes the profile, primary photo, address, or preferences.
CREATE CONSTRAINT TRIGGER validate_visible_profile_after_profile_change
AFTER INSERT OR UPDATE OR DELETE ON public.profiles
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION public.validate_visible_profile_dependencies();

CREATE CONSTRAINT TRIGGER validate_visible_profile_after_photo_change
AFTER INSERT OR UPDATE OR DELETE ON public.profile_photos
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION public.validate_visible_profile_dependencies();

CREATE CONSTRAINT TRIGGER validate_visible_profile_after_preference_change
AFTER INSERT OR UPDATE OR DELETE ON public.discovery_preferences
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION public.validate_visible_profile_dependencies();

CREATE CONSTRAINT TRIGGER validate_visible_profile_after_user_change
AFTER INSERT OR UPDATE OR DELETE ON public.app_users
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION public.validate_visible_profile_dependencies();


CREATE TRIGGER enforce_discovery_action_immutability
BEFORE UPDATE ON public.user_discovery_actions
FOR EACH ROW
EXECUTE FUNCTION public.enforce_discovery_action_immutability();


CREATE TRIGGER validate_match_like_actions
BEFORE INSERT ON public.matches
FOR EACH ROW
EXECUTE FUNCTION public.validate_match_like_actions();


CREATE TRIGGER enforce_match_immutability
BEFORE UPDATE ON public.matches
FOR EACH ROW
EXECUTE FUNCTION public.enforce_match_immutability();


CREATE CONSTRAINT TRIGGER validate_active_match_action_states
AFTER UPDATE OF status ON public.user_discovery_actions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION public.validate_active_match_action_states();


CREATE TRIGGER end_active_matches_when_blocked
AFTER INSERT OR UPDATE OF status ON public.user_blocks
FOR EACH ROW
EXECUTE FUNCTION public.end_active_matches_when_blocked();


CREATE TRIGGER validate_message_sender_before_insert
BEFORE INSERT ON public.messages
FOR EACH ROW
EXECUTE FUNCTION public.validate_message_sender_is_match_participant();


CREATE TRIGGER touch_match_after_message_insert
AFTER INSERT ON public.messages
FOR EACH ROW
EXECUTE FUNCTION public.touch_match_message_timestamps();


CREATE TRIGGER validate_boost_transaction_owner
BEFORE INSERT OR UPDATE OF user_id, transaction_id ON public.active_boosts
FOR EACH ROW
EXECUTE FUNCTION public.validate_boost_transaction_owner();


CREATE TRIGGER validate_user_subscription_paid_plan
BEFORE INSERT OR UPDATE OF plan_id ON public.user_subscriptions
FOR EACH ROW
EXECUTE FUNCTION public.validate_user_subscription_paid_plan();


CREATE TRIGGER prevent_audit_log_mutation
BEFORE UPDATE OR DELETE ON public.audit_log
FOR EACH ROW
EXECUTE FUNCTION public.prevent_audit_log_mutation();


-- updated_at triggers: one shared implementation and one trigger per mutable table.
CREATE TRIGGER set_timestamp_subscription_plans
BEFORE UPDATE ON public.subscription_plans
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER set_timestamp_subscription_plan_limits
BEFORE UPDATE ON public.subscription_plan_limits
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER set_timestamp_app_users
BEFORE UPDATE ON public.app_users
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER set_timestamp_location_places
BEFORE UPDATE ON public.location_places
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER set_timestamp_addresses
BEFORE UPDATE ON public.addresses
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER set_timestamp_profiles
BEFORE UPDATE ON public.profiles
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER set_timestamp_profile_photos
BEFORE UPDATE ON public.profile_photos
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER set_timestamp_discovery_preferences
BEFORE UPDATE ON public.discovery_preferences
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER set_timestamp_user_daily_limits
BEFORE UPDATE ON public.user_daily_limits
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER set_timestamp_user_blocks
BEFORE UPDATE ON public.user_blocks
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER set_timestamp_matches
BEFORE UPDATE ON public.matches
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER set_timestamp_messages
BEFORE UPDATE ON public.messages
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER set_timestamp_user_reports
BEFORE UPDATE ON public.user_reports
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER set_timestamp_notification_devices
BEFORE UPDATE ON public.notification_devices
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER set_timestamp_user_subscriptions
BEFORE UPDATE ON public.user_subscriptions
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER set_timestamp_transactions
BEFORE UPDATE ON public.transactions
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER set_timestamp_profile_prompts
BEFORE UPDATE ON public.profile_prompts
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER set_timestamp_profile_prompt_translations
BEFORE UPDATE ON public.profile_prompt_translations
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER set_timestamp_profile_prompt_answers
BEFORE UPDATE ON public.profile_prompt_answers
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


-- =============================================================================
-- 11. SUPABASE AUTH USER CREATION
-- =============================================================================

-- Preferences are intentionally not created here because interested_in_gender
-- must be explicitly selected during onboarding.
CREATE OR REPLACE FUNCTION public.handle_new_auth_user()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
BEGIN
    INSERT INTO public.app_users (id)
    VALUES (NEW.id)
    ON CONFLICT (id) DO NOTHING;

    RETURN NEW;
END;
$$;


DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;

CREATE TRIGGER on_auth_user_created
AFTER INSERT ON auth.users
FOR EACH ROW
EXECUTE FUNCTION public.handle_new_auth_user();


-- =============================================================================
-- 12. DEFAULT PLAN CONFIGURATION
-- =============================================================================
-- Initial product defaults. Change the values here before the first deployment
-- if the commercial policy differs. A NULL limit_value means unlimited.
-- Do not create a user_subscriptions row for FREE users.

INSERT INTO public.subscription_plans (
    name,
    plan_code,
    country_code,
    plan_kind,
    price_minor_units,
    currency,
    billing_interval,
    features,
    is_active
)
VALUES (
    'Free',
    'FREE',
    'GLOBAL',
    'FREE',
    0,
    'USD',
    'NONE',
    '{}'::JSONB,
    TRUE
)
ON CONFLICT (plan_code, country_code) DO NOTHING;

INSERT INTO public.subscription_plan_limits (plan_id, limit_type, limit_value)
SELECT sp.id, cfg.limit_type, cfg.limit_value
FROM public.subscription_plans sp
CROSS JOIN (
    VALUES
        ('DAILY_LIKES'::VARCHAR(30), 50::INTEGER),
        ('DAILY_SUPERLIKES'::VARCHAR(30), 1::INTEGER),
        ('DAILY_REWINDS'::VARCHAR(30), 1::INTEGER)
) AS cfg(limit_type, limit_value)
WHERE sp.plan_code = 'FREE'
  AND sp.country_code = 'GLOBAL'
ON CONFLICT (plan_id, limit_type) DO NOTHING;


-- =============================================================================
-- 13. ROW LEVEL SECURITY
-- =============================================================================
--
-- RLS is enabled as defense in depth. Spring Boot uses a trusted server path
-- (direct PostgreSQL/JDBC with appropriate server role or Supabase service role)
-- for all application reads/writes. No direct client INSERT/UPDATE/DELETE
-- policies are created.
-- =============================================================================

ALTER TABLE public.subscription_plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.subscription_plan_limits ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.app_users ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.location_places ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.addresses ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profile_photos ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.discovery_preferences ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_discovery_actions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_daily_limits ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_blocks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.matches ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_verifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.notification_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.payment_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_entitlement_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.active_boosts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profile_prompts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profile_prompt_translations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profile_prompt_answers ENABLE ROW LEVEL SECURITY;


-- =============================================================================
-- 14. DIRECT CLIENT MESSAGE READ / REALTIME POLICY
-- =============================================================================

CREATE OR REPLACE FUNCTION public.can_read_match_messages(p_match_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.matches m
        WHERE m.id = p_match_id
          AND m.status = 'ACTIVE'
          AND (
              m.user_one_id = auth.uid()
              OR m.user_two_id = auth.uid()
          )
    );
$$;


REVOKE ALL ON FUNCTION public.can_read_match_messages(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.can_read_match_messages(UUID) TO authenticated;


CREATE POLICY "Users can read approved messages in their active matches"
ON public.messages
FOR SELECT TO authenticated
USING (
    deleted_at IS NULL
    AND moderation_status = 'APPROVED'
    AND public.can_read_match_messages(match_id)
);


-- =============================================================================
-- 15. SUPABASE REALTIME
-- =============================================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime'
          AND schemaname = 'public'
          AND tablename = 'messages'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.messages;
    END IF;
END $$;


ALTER TABLE public.messages REPLICA IDENTITY FULL;


-- =============================================================================
-- 15. PRIVATE SUPABASE STORAGE BUCKETS
-- =============================================================================

INSERT INTO storage.buckets (
    id,
    name,
    public,
    file_size_limit,
    allowed_mime_types
)
VALUES
    (
        'profile-photos',
        'profile-photos',
        FALSE,
        10485760,
        ARRAY['image/jpeg', 'image/png', 'image/webp']
    ),
    (
        'verification-selfies',
        'verification-selfies',
        FALSE,
        10485760,
        ARRAY['image/jpeg', 'image/png', 'image/webp']
    ),
    (
        'chat-media',
        'chat-media',
        FALSE,
        26214400,
        ARRAY[
            'image/jpeg',
            'image/png',
            'image/webp',
            'audio/mpeg',
            'audio/mp4',
            'audio/aac',
            'audio/wav'
        ]
    ),
    (
        'payment-receipts',
        'payment-receipts',
        FALSE,
        10485760,
        ARRAY[
            'image/jpeg',
            'image/png',
            'image/webp',
            'application/pdf'
        ]
    )
ON CONFLICT (id) DO UPDATE
SET public = EXCLUDED.public,
    file_size_limit = EXCLUDED.file_size_limit,
    allowed_mime_types = EXCLUDED.allowed_mime_types;


-- =============================================================================
-- 17. IMPLEMENTATION RULES FOR SPRING BOOT
-- =============================================================================
--
-- 1. Resolve the effective plan before every limited action:
--      - use the user's ACTIVE paid user_subscriptions row when its period is
--        current;
--      - otherwise use an ACTIVE FREE plan for the user's address country;
--      - otherwise fall back to the ACTIVE FREE plan for country_code = GLOBAL;
--      - load subscription_plan_limits for DAILY_LIKES, DAILY_SUPERLIKES, and
--        DAILY_REWINDS. NULL limit_value means unlimited;
--      - reject an active plan that does not have all three configured rows.
--    user_daily_limits records usage only; subscription_plan_limits defines the
--    allowed quota. Do not store FREE plans in user_subscriptions.
--
-- 2. Authenticate each request from the Supabase JWT, resolve app_users.id, and
--    reject every non-ACTIVE account before business processing.
--
-- 2. Discovery candidates must:
--      - be ACTIVE app users with visible, onboarded profiles;
--      - have an approved primary photo;
--      - have an address through app_users.address_id;
--      - satisfy interested_in_gender, age, residency, verified-only, and
--        distance/preference rules;
--      - exclude the requester, active blocks in either direction, current
--        active swipes, and ACTIVE matches;
--      - return 10 cards per fetch, with cursor pagination;
--      - return age computed with public.calculate_age(date_of_birth) and
--        distance computed in the backend using ST_Distance; never expose coords.
--
-- 3. Use date-of-birth bounds for the discovery age filter so the
--    idx_profiles_discovery_bundle index remains useful. Use PostGIS
--    ST_DWithin for nearby filtering and ST_Distance only for returned/ranked
--    candidates.
--
-- 4. Swipe transaction:
--      - lock/create the UTC user_daily_limits row;
--      - insert one idempotent user_discovery_actions row;
--      - update limits only when the insert is new;
--      - on LIKE/SUPERLIKE, check the reciprocal ACTIVE like;
--      - if reciprocal like exists, insert a match with both action IDs and set
--        a short rewind_eligible_until window (for example, 60 seconds).
--
-- 5. Rewind transaction:
--      - only reverse the user's latest eligible ACTIVE action;
--      - update action status to REVERSED with reversed_at/reversed_reason;
--      - when that action created an ACTIVE match, end the match with
--        CANCELLED_BY_REWIND only if it is within rewind_eligible_until and
--        first_message_at IS NULL;
--      - preserve the other user's original like;
--      - increment rewinds_used and, when applicable, consume a rewind credit;
--      - never hard-delete swipe or match history.
--
-- 6. Unmatch is separate from rewind:
--      - end the ACTIVE match with USER_UNMATCH;
--      - preserve action history;
--      - treat the pair as excluded from future discovery unless a later product
--        policy explicitly re-enables them.
--
-- 7. Block transaction:
--      - insert or reactivate the block;
--      - this schema ends any ACTIVE match automatically;
--      - write an audit entry in the same backend transaction;
--      - discovery queries exclude active blocks in either direction.
--
-- 8. Account deletion is soft deletion:
--      - set app_users.status = DEACTIVATED and deleted_at;
--      - hide the profile and revoke sessions;
--      - do not directly delete auth.users/app_users without a documented
--        retention/anonymization process, because the schema intentionally uses
--        restrictive foreign keys to preserve safety and payment records.
--
-- 9. All media and receipts remain private. Store only bucket/path and issue
--    signed URLs in authenticated responses; do not add persistent image_url or
--    media_url source-of-truth columns.
--
-- 10. Run this baseline only for a fresh database. For an existing database,
--     create an ordered migration plan that backfills data before adding NOT NULL
--     columns, changing unique constraints, or enabling new trigger rules.
-- =============================================================================


-- =============================================================================
-- 18. MIGRATION SCRIPTS: MOBILE APP ALIGNMENT (Gap Analysis v1)
-- =============================================================================
-- Run these ALTER statements against an existing database that was created from
-- the pre-alignment baseline. They are safe to apply in order on a live DB.
-- The baseline table definitions above already reflect the final state.
-- =============================================================================


-- 18.1  profiles: add activity_level, interests, languages
ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS activity_level VARCHAR(20) CHECK (
        activity_level IS NULL OR activity_level IN ('SEDENTARY', 'LIGHT', 'MODERATE', 'ACTIVE', 'VERY_ACTIVE')
    ),
    ADD COLUMN IF NOT EXISTS interests TEXT[] NOT NULL DEFAULT '{}'::TEXT[],
    ADD COLUMN IF NOT EXISTS languages TEXT[] NOT NULL DEFAULT '{}'::TEXT[];


-- 18.2  profiles: make has_children nullable (NULL = "Prefer not to say")
ALTER TABLE public.profiles
    ALTER COLUMN has_children DROP NOT NULL,
    ALTER COLUMN has_children DROP DEFAULT;


-- 18.3  profiles: change smoking and drinking from BOOLEAN to VARCHAR enum
--       Existing boolean values are preserved: TRUE -> 'YES', FALSE -> 'NO'.
ALTER TABLE public.profiles
    ALTER COLUMN smoking DROP DEFAULT,
    ALTER COLUMN drinking DROP DEFAULT;

ALTER TABLE public.profiles
    ALTER COLUMN smoking TYPE VARCHAR(20)
        USING CASE WHEN smoking::BOOLEAN THEN 'YES' ELSE 'NO' END,
    ALTER COLUMN drinking TYPE VARCHAR(20)
        USING CASE WHEN drinking::BOOLEAN THEN 'YES' ELSE 'NO' END;

ALTER TABLE public.profiles
    ADD CONSTRAINT profiles_smoking_check CHECK (
        smoking IS NULL OR smoking IN ('NO', 'YES', 'OCCASIONALLY', 'TRYING_TO_QUIT')
    ),
    ADD CONSTRAINT profiles_drinking_check CHECK (
        drinking IS NULL OR drinking IN ('NO', 'SOCIALLY', 'OCCASIONALLY', 'YES')
    );


-- 18.4  discovery_preferences: update discovery_mode enum to PUBLIC / INCOGNITO
--       Drop the old constraint first so the UPDATE is not blocked by it.
ALTER TABLE public.discovery_preferences
    DROP CONSTRAINT IF EXISTS discovery_preferences_discovery_mode_check;

UPDATE public.discovery_preferences
    SET discovery_mode = 'PUBLIC'
    WHERE discovery_mode IN ('STANDARD', 'GLOBAL');

SET CONSTRAINTS public.validate_visible_profile_after_preference_change IMMEDIATE;

ALTER TABLE public.discovery_preferences
    ALTER COLUMN discovery_mode SET DEFAULT 'PUBLIC',
    ADD CONSTRAINT discovery_preferences_discovery_mode_check CHECK (
        discovery_mode IN ('PUBLIC', 'INCOGNITO')
    );



-- =============================================================================
-- V4: Make optional profile fields nullable
-- Fields that are not part of the minimum required profile (name, gender,
-- date_of_birth, residency_type, relationship_intention, and status flags)
-- must accept NULL so a profile can be created before onboarding is complete.
-- =============================================================================

--================================================
-- -----------------------------------------------------------------------------
-- 1. smoking / drinking: drop the NOT NULL constraint that was inherited from
--    the original BOOLEAN columns and was never removed during the V3 type
--    conversion. The CHECK constraints already allow NULL.
-- -----------------------------------------------------------------------------
ALTER TABLE public.profiles
    ALTER COLUMN smoking  DROP NOT NULL,
    ALTER COLUMN drinking DROP NOT NULL;


-- -----------------------------------------------------------------------------
-- 2. interests / languages: were added in V3 as NOT NULL DEFAULT '{}'. Allow
--    NULL so an incomplete profile can omit them. The DEFAULT is kept so rows
--    inserted via plain SQL without an explicit value still get an empty array.
-- -----------------------------------------------------------------------------
ALTER TABLE public.profiles
    ALTER COLUMN interests  DROP NOT NULL,
    ALTER COLUMN languages  DROP NOT NULL;







--==============================================

-- =============================================================================
-- V5: Split smoking/drinking into boolean flag + detail enum
-- V3 converted smoking/drinking from BOOLEAN to VARCHAR enum.
-- The profile API spec now requires a boolean flag (smoking/drinking) AND a
-- detail column (smoking_detail/drinking_detail). This migration:
--   1. Adds the detail columns and copies the current VARCHAR values into them.
--   2. Converts smoking/drinking back to BOOLEAN (NO -> false, others -> true).
--   3. Adds canonical CHECK constraints on the new detail columns.
--   4. Adds missing CHECK constraints from the profile-api spec (marital_status,
--      array cardinality limits).
-- Idempotent: safe to run on a DB where these changes were already applied
-- manually (steps 1-4 are guarded; step 5+ use DROP ... IF EXISTS).
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. Add smoking_detail and drinking_detail columns (always idempotent)
-- -----------------------------------------------------------------------------
ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS smoking_detail  VARCHAR(50),
    ADD COLUMN IF NOT EXISTS drinking_detail VARCHAR(50);


-- -----------------------------------------------------------------------------
-- 2-4. Copy values and convert types — skipped when already BOOLEAN
-- -----------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name   = 'profiles'
          AND column_name  = 'smoking'
          AND data_type    = 'character varying'
    ) THEN
        UPDATE public.profiles
            SET smoking_detail  = smoking
            WHERE smoking IS NOT NULL;

        UPDATE public.profiles
            SET drinking_detail = drinking
            WHERE drinking IS NOT NULL;

        -- The UPDATEs above queue deferred constraint triggers on profiles.
        -- Flush them now so the subsequent ALTER TABLE DDL is not blocked.
        SET CONSTRAINTS ALL IMMEDIATE;

        ALTER TABLE public.profiles
            DROP CONSTRAINT IF EXISTS profiles_smoking_check,
            DROP CONSTRAINT IF EXISTS profiles_drinking_check;

        ALTER TABLE public.profiles
            ALTER COLUMN smoking  DROP DEFAULT,
            ALTER COLUMN drinking DROP DEFAULT;

        EXECUTE '
            ALTER TABLE public.profiles
                ALTER COLUMN smoking  TYPE BOOLEAN
                    USING CASE WHEN smoking  IS NULL OR smoking  = ''NO'' THEN FALSE ELSE TRUE END,
                ALTER COLUMN drinking TYPE BOOLEAN
                    USING CASE WHEN drinking IS NULL OR drinking = ''NO'' THEN FALSE ELSE TRUE END
        ';
    END IF;
END $$;


-- -----------------------------------------------------------------------------
-- 5. CHECK constraints for the new detail columns (drop-then-add = idempotent)
-- -----------------------------------------------------------------------------
ALTER TABLE public.profiles
    DROP CONSTRAINT IF EXISTS chk_profiles_smoking_detail,
    DROP CONSTRAINT IF EXISTS chk_profiles_drinking_detail;

ALTER TABLE public.profiles
    ADD CONSTRAINT chk_profiles_smoking_detail CHECK (
        smoking_detail IS NULL
        OR smoking_detail IN ('NO', 'YES', 'OCCASIONALLY', 'TRYING_TO_QUIT')
    ),
    ADD CONSTRAINT chk_profiles_drinking_detail CHECK (
        drinking_detail IS NULL
        OR drinking_detail IN ('NO', 'SOCIALLY', 'OCCASIONALLY', 'YES')
    );


-- -----------------------------------------------------------------------------
-- 6. marital_status canonical values constraint
-- -----------------------------------------------------------------------------
ALTER TABLE public.profiles
    DROP CONSTRAINT IF EXISTS chk_profiles_marital_status;

ALTER TABLE public.profiles
    ADD CONSTRAINT chk_profiles_marital_status CHECK (
        marital_status IS NULL
        OR marital_status IN ('NEVER_MARRIED', 'DIVORCED', 'WIDOWED', 'SEPARATED')
    );


-- -----------------------------------------------------------------------------
-- 7. Lifestyle array cardinality limits
-- -----------------------------------------------------------------------------
ALTER TABLE public.profiles
    DROP CONSTRAINT IF EXISTS chk_profiles_lifestyle_array_limits;

ALTER TABLE public.profiles
    ADD CONSTRAINT chk_profiles_lifestyle_array_limits CHECK (
        (interests  IS NULL OR cardinality(interests)  <= 20) AND
        (languages  IS NULL OR cardinality(languages)  <= 20)
    );


--=====================================================

-- Move discovery_mode from discovery_preferences to profiles.
-- Values: PUBLIC (default) | INCOGNITO

-- 1. Add column and constraint to profiles FIRST (before any UPDATE that could fire
--    deferred constraint triggers on this table).
ALTER TABLE public.profiles
    ADD COLUMN discovery_mode VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
    ADD CONSTRAINT profiles_discovery_mode_check
    CHECK (discovery_mode IN ('PUBLIC', 'INCOGNITO'));

-- 2. Backfill from existing discovery_preferences rows.
--    Any constraint trigger events are now pending on profiles, but we are done
--    altering profiles itself.
UPDATE public.profiles p
SET discovery_mode = COALESCE(
    (SELECT dp.discovery_mode FROM public.discovery_preferences dp WHERE dp.user_id = p.user_id),
    'PUBLIC'
);

-- 3. Drop the column from discovery_preferences.
ALTER TABLE public.discovery_preferences
    DROP COLUMN IF EXISTS discovery_mode;




--=============================================================


-- ============================================================================
-- V7__add_chat_sequences_outbox_and_notification_settings.sql
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Per-match ordered message sequences and receipt cursors
-- ---------------------------------------------------------------------------

ALTER TABLE public.matches
    ADD COLUMN next_message_sequence BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN user_one_last_delivered_sequence BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN user_two_last_delivered_sequence BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN user_one_last_read_sequence BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN user_two_last_read_sequence BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN user_one_last_delivered_at TIMESTAMPTZ,
    ADD COLUMN user_two_last_delivered_at TIMESTAMPTZ;

ALTER TABLE public.messages
    ADD COLUMN sequence_number BIGINT;

-- Backfill deterministic sequence values for existing messages.
WITH numbered_messages AS (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY match_id
            ORDER BY created_at ASC, id ASC
        )::BIGINT AS sequence_number
    FROM public.messages
)
UPDATE public.messages m
SET sequence_number = n.sequence_number
FROM numbered_messages n
WHERE m.id = n.id;

ALTER TABLE public.messages
    ALTER COLUMN sequence_number SET NOT NULL;

-- Existing historical messages are treated as delivered and read.
WITH max_sequences AS (
    SELECT
        match_id,
        MAX(sequence_number)::BIGINT AS max_sequence
    FROM public.messages
    GROUP BY match_id
)
UPDATE public.matches m
SET
    next_message_sequence = max_sequences.max_sequence + 1,
    user_one_last_delivered_sequence = max_sequences.max_sequence,
    user_two_last_delivered_sequence = max_sequences.max_sequence,
    user_one_last_read_sequence = max_sequences.max_sequence,
    user_two_last_read_sequence = max_sequences.max_sequence
FROM max_sequences
WHERE max_sequences.match_id = m.id;

-- Matches with no messages remain at sequence 1 and cursor 0.
UPDATE public.matches
SET next_message_sequence = 1
WHERE next_message_sequence IS NULL
   OR next_message_sequence < 1;

ALTER TABLE public.messages
    ADD CONSTRAINT check_messages_sequence_number_positive
    CHECK (sequence_number > 0);

ALTER TABLE public.messages
    ADD CONSTRAINT unique_messages_match_sequence
    UNIQUE (match_id, sequence_number);

ALTER TABLE public.matches
    ADD CONSTRAINT check_matches_receipt_sequence_state
    CHECK (
        next_message_sequence >= 1
        AND user_one_last_delivered_sequence >= 0
        AND user_two_last_delivered_sequence >= 0
        AND user_one_last_read_sequence >= 0
        AND user_two_last_read_sequence >= 0
        AND user_one_last_read_sequence <= user_one_last_delivered_sequence
        AND user_two_last_read_sequence <= user_two_last_delivered_sequence
        AND user_one_last_delivered_sequence < next_message_sequence
        AND user_two_last_delivered_sequence < next_message_sequence
    );

CREATE INDEX idx_messages_match_sequence
    ON public.messages(match_id, sequence_number DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_messages_match_sender_visible_sequence
    ON public.messages(match_id, sender_user_id, sequence_number)
    WHERE deleted_at IS NULL
      AND moderation_status = 'APPROVED';

DROP INDEX IF EXISTS public.idx_messages_match_cursor;

CREATE INDEX idx_matches_user_one_active_inbox
    ON public.matches(
        user_one_id,
        last_message_at DESC NULLS LAST,
        matched_at DESC,
        id DESC
    )
    WHERE status = 'ACTIVE';

CREATE INDEX idx_matches_user_two_active_inbox
    ON public.matches(
        user_two_id,
        last_message_at DESC NULLS LAST,
        matched_at DESC,
        id DESC
    )
    WHERE status = 'ACTIVE';


-- ---------------------------------------------------------------------------
-- 2. Transactional outbox
-- ---------------------------------------------------------------------------

CREATE TABLE public.chat_outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    event_type VARCHAR(100) NOT NULL CHECK (
        event_type IN (
            'chat.message.created',
            'chat.receipt.updated',
            'chat.match.ended',
            'inbox.match.updated',
            'inbox.match.removed'
        )
    ),

    match_id UUID REFERENCES public.matches(id) ON DELETE SET NULL,
    recipient_user_id UUID REFERENCES public.app_users(id) ON DELETE RESTRICT,

    topic TEXT NOT NULL CHECK (
        char_length(BTRIM(topic)) BETWEEN 1 AND 500
    ),

    -- Full immutable event envelope.
    payload JSONB NOT NULL CHECK (
        jsonb_typeof(payload) = 'object'
    ),

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')
    ),

    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (
        attempt_count >= 0
    ),

    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    locked_at TIMESTAMPTZ,
    locked_by VARCHAR(100),
    lease_expires_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,

    published_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_chat_outbox_recipient_shape CHECK (
        (
            event_type IN ('inbox.match.updated', 'inbox.match.removed')
            AND recipient_user_id IS NOT NULL
        )
        OR
        (
            event_type IN (
                'chat.message.created',
                'chat.receipt.updated',
                'chat.match.ended'
            )
            AND recipient_user_id IS NULL
        )
    ),

    CONSTRAINT check_chat_outbox_processing_lease CHECK (
        status <> 'PROCESSING'
        OR (
            locked_at IS NOT NULL
            AND locked_by IS NOT NULL
            AND lease_expires_at IS NOT NULL
        )
    )
);

CREATE INDEX idx_chat_outbox_claim_pending
    ON public.chat_outbox_events(available_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_chat_outbox_processing_lease
    ON public.chat_outbox_events(lease_expires_at)
    WHERE status = 'PROCESSING';

CREATE INDEX idx_chat_outbox_failed
    ON public.chat_outbox_events(created_at DESC)
    WHERE status = 'FAILED';

CREATE INDEX idx_chat_outbox_match_created
    ON public.chat_outbox_events(match_id, created_at DESC);

ALTER TABLE public.chat_outbox_events ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.chat_outbox_events
FROM anon, authenticated;


-- ---------------------------------------------------------------------------
-- 3. Per-user match notification settings
-- ---------------------------------------------------------------------------

CREATE TABLE public.match_notification_settings (
    match_id UUID NOT NULL REFERENCES public.matches(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,

    muted_until TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (match_id, user_id)
);

CREATE INDEX idx_match_notification_settings_user
    ON public.match_notification_settings(user_id, muted_until);

CREATE OR REPLACE FUNCTION public.validate_match_notification_settings_member()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.matches m
        WHERE m.id = NEW.match_id
          AND (
              m.user_one_id = NEW.user_id
              OR m.user_two_id = NEW.user_id
          )
    ) THEN
        RAISE EXCEPTION
            'Notification settings user must be a participant of the match.';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_match_notification_settings_member
BEFORE INSERT OR UPDATE OF match_id, user_id
ON public.match_notification_settings
FOR EACH ROW
EXECUTE FUNCTION public.validate_match_notification_settings_member();

CREATE TRIGGER set_timestamp_match_notification_settings
BEFORE UPDATE ON public.match_notification_settings
FOR EACH ROW
EXECUTE FUNCTION public.update_updated_at_column();

ALTER TABLE public.match_notification_settings ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.match_notification_settings
FROM anon, authenticated;


-- ---------------------------------------------------------------------------
-- 4. Improve existing message trigger protections
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.validate_message_sender_is_match_participant()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.matches m
        JOIN public.app_users sender
            ON sender.id = NEW.sender_user_id
           AND sender.status = 'ACTIVE'
        WHERE m.id = NEW.match_id
          AND m.status = 'ACTIVE'
          AND (
              m.user_one_id = NEW.sender_user_id
              OR m.user_two_id = NEW.sender_user_id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM public.user_blocks ub
              WHERE ub.status = 'ACTIVE'
                AND (
                    (
                        ub.blocker_user_id = m.user_one_id
                        AND ub.blocked_user_id = m.user_two_id
                    )
                    OR
                    (
                        ub.blocker_user_id = m.user_two_id
                        AND ub.blocked_user_id = m.user_one_id
                    )
                )
          )
    ) THEN
        RAISE EXCEPTION
            'Message sender must be an active participant in an active, unblocked match.';
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.touch_match_message_timestamps()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.deleted_at IS NULL
       AND NEW.moderation_status = 'APPROVED' THEN

        UPDATE public.matches
        SET
            first_message_at = COALESCE(first_message_at, NEW.created_at),
            last_message_at = CASE
                WHEN last_message_at IS NULL THEN NEW.created_at
                WHEN NEW.created_at > last_message_at THEN NEW.created_at
                ELSE last_message_at
            END,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = NEW.match_id;
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.enforce_message_identity_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.match_id IS DISTINCT FROM OLD.match_id
       OR NEW.sender_user_id IS DISTINCT FROM OLD.sender_user_id
       OR NEW.client_message_id IS DISTINCT FROM OLD.client_message_id
       OR NEW.sequence_number IS DISTINCT FROM OLD.sequence_number
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION
            'Message identity and sequence fields are immutable.';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER enforce_message_identity_immutability
BEFORE UPDATE ON public.messages
FOR EACH ROW
EXECUTE FUNCTION public.enforce_message_identity_immutability();



--=============================================================


-- ============================================================================
-- V8__configure_chat_private_realtime_broadcast.sql
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Remove direct client message reads.
-- Spring Boot is the chat read and write path.
-- ---------------------------------------------------------------------------

DROP POLICY IF EXISTS "Users can read approved messages in their active matches"
ON public.messages;

REVOKE ALL ON TABLE public.messages
FROM anon, authenticated;

DROP FUNCTION IF EXISTS public.can_read_match_messages(UUID);


-- ---------------------------------------------------------------------------
-- 2. Stop Postgres Changes publication for messages.
-- Chat uses private Broadcast events from the transactional outbox instead.
-- ---------------------------------------------------------------------------

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime'
          AND schemaname = 'public'
          AND tablename = 'messages'
    ) THEN
        ALTER PUBLICATION supabase_realtime DROP TABLE public.messages;
    END IF;
END $$;

ALTER TABLE public.messages REPLICA IDENTITY DEFAULT;


-- ---------------------------------------------------------------------------
-- 3. Secure Realtime topic helper functions.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.chat_realtime_is_active_match_member(
    p_topic TEXT,
    p_kind TEXT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_match_id UUID;
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RETURN FALSE;
    END IF;

    IF p_kind NOT IN ('events', 'typing', 'presence') THEN
        RETURN FALSE;
    END IF;

    IF p_topic !~ (
        '^match:[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-'
        || '[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}:'
        || p_kind
        || '$'
    ) THEN
        RETURN FALSE;
    END IF;

    v_match_id := split_part(p_topic, ':', 2)::UUID;

    RETURN EXISTS (
        SELECT 1
        FROM public.matches m
        JOIN public.app_users au
            ON au.id = v_user_id
           AND au.status = 'ACTIVE'
        WHERE m.id = v_match_id
          AND m.status = 'ACTIVE'
          AND (
              m.user_one_id = v_user_id
              OR m.user_two_id = v_user_id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM public.user_blocks ub
              WHERE ub.status = 'ACTIVE'
                AND (
                    (
                        ub.blocker_user_id = m.user_one_id
                        AND ub.blocked_user_id = m.user_two_id
                    )
                    OR
                    (
                        ub.blocker_user_id = m.user_two_id
                        AND ub.blocked_user_id = m.user_one_id
                    )
                )
          )
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.chat_realtime_is_own_inbox_topic(
    p_topic TEXT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RETURN FALSE;
    END IF;

    IF p_topic !~ (
        '^user:[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-'
        || '[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}:inbox$'
    ) THEN
        RETURN FALSE;
    END IF;

    IF split_part(p_topic, ':', 2)::UUID <> v_user_id THEN
        RETURN FALSE;
    END IF;

    RETURN EXISTS (
        SELECT 1
        FROM public.app_users au
        WHERE au.id = v_user_id
          AND au.status = 'ACTIVE'
    );
END;
$$;

REVOKE ALL ON FUNCTION public.chat_realtime_is_active_match_member(TEXT, TEXT)
FROM PUBLIC;

REVOKE ALL ON FUNCTION public.chat_realtime_is_own_inbox_topic(TEXT)
FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.chat_realtime_is_active_match_member(TEXT, TEXT)
TO authenticated;

GRANT EXECUTE ON FUNCTION public.chat_realtime_is_own_inbox_topic(TEXT)
TO authenticated;


-- ---------------------------------------------------------------------------
-- 4. Supabase Realtime private Broadcast and Presence policies.
-- ---------------------------------------------------------------------------

ALTER TABLE realtime.messages ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "chat realtime receive" ON realtime.messages;
DROP POLICY IF EXISTS "chat realtime publish ephemeral" ON realtime.messages;

CREATE POLICY "chat realtime receive"
ON realtime.messages
FOR SELECT
TO authenticated
USING (
    (
        extension = 'broadcast'
        AND (
            public.chat_realtime_is_active_match_member(
                realtime.topic(),
                'events'
            )
            OR public.chat_realtime_is_active_match_member(
                realtime.topic(),
                'typing'
            )
            OR public.chat_realtime_is_own_inbox_topic(
                realtime.topic()
            )
        )
    )
    OR
    (
        extension = 'presence'
        AND public.chat_realtime_is_active_match_member(
            realtime.topic(),
            'presence'
        )
    )
);

CREATE POLICY "chat realtime publish ephemeral"
ON realtime.messages
FOR INSERT
TO authenticated
WITH CHECK (
    (
        extension = 'broadcast'
        AND public.chat_realtime_is_active_match_member(
            realtime.topic(),
            'typing'
        )
    )
    OR
    (
        extension = 'presence'
        AND public.chat_realtime_is_active_match_member(
            realtime.topic(),
            'presence'
        )
    )
);


--===========================================



-- ============================================================================
-- V9__fix_presence_broadcast_authorization.sql
-- ============================================================================
-- Supabase Realtime presence channels internally use the 'broadcast' extension
-- for state-sync diffs in addition to the 'presence' extension for join/leave
-- events. The V8 "chat realtime receive" SELECT policy only allowed the
-- 'broadcast' extension for :events and :typing topics, so when Supabase
-- checked a broadcast-extension row on the :presence topic the policy fell
-- through all clauses and RLS denied the subscription with:
--   "Unauthorized: You do not have permissions to read from this Channel topic"
--
-- Fix: add chat_realtime_is_active_match_member(topic, 'presence') to both
-- the broadcast arm of the SELECT policy and the broadcast arm of the INSERT
-- (publish) policy so presence state-sync messages are authorized.
-- ============================================================================

DROP POLICY IF EXISTS "chat realtime receive" ON realtime.messages;
DROP POLICY IF EXISTS "chat realtime publish ephemeral" ON realtime.messages;

CREATE POLICY "chat realtime receive"
ON realtime.messages
FOR SELECT
TO authenticated
USING (
    (
        extension = 'broadcast'
        AND (
            public.chat_realtime_is_active_match_member(
                realtime.topic(),
                'events'
            )
            OR public.chat_realtime_is_active_match_member(
                realtime.topic(),
                'typing'
            )
            OR public.chat_realtime_is_active_match_member(
                realtime.topic(),
                'presence'
            )
            OR public.chat_realtime_is_own_inbox_topic(
                realtime.topic()
            )
        )
    )
    OR
    (
        extension = 'presence'
        AND public.chat_realtime_is_active_match_member(
            realtime.topic(),
            'presence'
        )
    )
);

CREATE POLICY "chat realtime publish ephemeral"
ON realtime.messages
FOR INSERT
TO authenticated
WITH CHECK (
    (
        extension = 'broadcast'
        AND (
            public.chat_realtime_is_active_match_member(
                realtime.topic(),
                'typing'
            )
            OR public.chat_realtime_is_active_match_member(
                realtime.topic(),
                'presence'
            )
        )
    )
    OR
    (
        extension = 'presence'
        AND public.chat_realtime_is_active_match_member(
            realtime.topic(),
            'presence'
        )
    )
);


--============================================================

-- ============================================================================
-- V10__add_activity_status_visibility.sql
-- ============================================================================
-- Add a boolean column `show_activity_status` to `app_users` to control
-- whether other authorized users may see this user's derived activity status.
-- ============================================================================

ALTER TABLE public.app_users
    ADD COLUMN show_activity_status BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN public.app_users.show_activity_status IS
    'Whether other authorized users may see this user''s derived activity status.';


--========================================================


-- ============================================================================
-- V11__add_push_notification_support.sql
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Extend notification_devices
-- ---------------------------------------------------------------------------

ALTER TABLE public.notification_devices
    ADD COLUMN installation_id UUID,
    ADD COLUMN app_environment VARCHAR(20) NOT NULL DEFAULT 'PRODUCTION'
        CHECK (app_environment IN ('DEVELOPMENT', 'PREVIEW', 'PRODUCTION')),
    ADD COLUMN disabled_at TIMESTAMPTZ,
    ADD COLUMN last_error_code VARCHAR(100),
    ADD COLUMN last_error_at TIMESTAMPTZ;

CREATE INDEX idx_notification_devices_active_environment
    ON public.notification_devices(user_id, app_environment)
    WHERE is_active = TRUE;

CREATE UNIQUE INDEX unique_active_notification_installation
    ON public.notification_devices(app_environment, installation_id)
    WHERE installation_id IS NOT NULL
      AND is_active = TRUE;


-- ---------------------------------------------------------------------------
-- 2. User notification preferences
-- ---------------------------------------------------------------------------

CREATE TABLE public.user_notification_preferences (
    user_id UUID PRIMARY KEY
        REFERENCES public.app_users(id) ON DELETE RESTRICT,

    push_enabled BOOLEAN NOT NULL DEFAULT TRUE,

    message_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    match_notifications_enabled   BOOLEAN NOT NULL DEFAULT TRUE,
    like_notifications_enabled    BOOLEAN NOT NULL DEFAULT TRUE,

    message_preview_enabled BOOLEAN NOT NULL DEFAULT FALSE,

    marketing_notifications_enabled        BOOLEAN NOT NULL DEFAULT FALSE,
    marketing_notifications_opted_in_at    TIMESTAMPTZ,
    marketing_notifications_consent_version VARCHAR(50),

    last_marketing_sent_at            TIMESTAMPTZ,
    marketing_reservation_event_id    UUID,
    marketing_reservation_expires_at  TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_marketing_opt_in CHECK (
        NOT marketing_notifications_enabled
        OR (
            marketing_notifications_opted_in_at IS NOT NULL
            AND NULLIF(BTRIM(marketing_notifications_consent_version), '') IS NOT NULL
        )
    )
);

CREATE TRIGGER set_timestamp_user_notification_preferences
BEFORE UPDATE ON public.user_notification_preferences
FOR EACH ROW
EXECUTE FUNCTION public.update_updated_at_column();

ALTER TABLE public.user_notification_preferences ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.user_notification_preferences
FROM anon, authenticated;

-- Backfill preference rows for existing users
INSERT INTO public.user_notification_preferences (user_id)
SELECT id
FROM public.app_users
ON CONFLICT (user_id) DO NOTHING;

-- Trigger: create default preference row for every future app_users row
CREATE OR REPLACE FUNCTION public.create_default_notification_preferences()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO public.user_notification_preferences (user_id)
    VALUES (NEW.id)
    ON CONFLICT (user_id) DO NOTHING;

    RETURN NEW;
END;
$$;

CREATE TRIGGER create_default_notification_preferences_after_user_insert
AFTER INSERT ON public.app_users
FOR EACH ROW
EXECUTE FUNCTION public.create_default_notification_preferences();


-- ---------------------------------------------------------------------------
-- 3. Notification campaigns
-- ---------------------------------------------------------------------------

CREATE TABLE public.notification_campaigns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    campaign_key VARCHAR(100) NOT NULL UNIQUE,

    title VARCHAR(120) NOT NULL,
    body  VARCHAR(300) NOT NULL,

    navigation_payload JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (jsonb_typeof(navigation_payload) = 'object'),

    audience_definition JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (jsonb_typeof(audience_definition) = 'object'),

    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN (
            'DRAFT',
            'SCHEDULED',
            'SENDING',
            'COMPLETED',
            'CANCELLED'
        )),

    scheduled_at  TIMESTAMPTZ,
    started_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    cancelled_at  TIMESTAMPTZ,

    created_by_user_id UUID
        REFERENCES public.app_users(id) ON DELETE SET NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER set_timestamp_notification_campaigns
BEFORE UPDATE ON public.notification_campaigns
FOR EACH ROW
EXECUTE FUNCTION public.update_updated_at_column();

-- Enforce campaign lifecycle rules:
--   * Content is immutable once SENDING/COMPLETED/CANCELLED.
--   * Invalid status transitions are rejected.
CREATE OR REPLACE FUNCTION public.enforce_notification_campaign_lifecycle()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- Reject invalid status transitions
    IF OLD.status = 'COMPLETED' OR OLD.status = 'CANCELLED' THEN
        IF NEW.status IS DISTINCT FROM OLD.status THEN
            RAISE EXCEPTION
                'Campaign status cannot change from % once finalised.', OLD.status;
        END IF;
    END IF;

    IF OLD.status = 'SENDING' AND NEW.status NOT IN ('COMPLETED', 'CANCELLED') THEN
        RAISE EXCEPTION
            'A SENDING campaign can only transition to COMPLETED or CANCELLED.';
    END IF;

    IF OLD.status = 'SCHEDULED' AND NEW.status NOT IN ('SENDING', 'CANCELLED', 'DRAFT') THEN
        RAISE EXCEPTION
            'A SCHEDULED campaign can only transition to SENDING, CANCELLED, or DRAFT.';
    END IF;

    -- SCHEDULED requires scheduled_at
    IF NEW.status = 'SCHEDULED' AND NEW.scheduled_at IS NULL THEN
        RAISE EXCEPTION 'scheduled_at is required when status is SCHEDULED.';
    END IF;

    -- SENDING requires started_at
    IF NEW.status = 'SENDING' AND NEW.started_at IS NULL THEN
        RAISE EXCEPTION 'started_at is required when status is SENDING.';
    END IF;

    -- COMPLETED requires started_at and completed_at
    IF NEW.status = 'COMPLETED'
       AND (NEW.started_at IS NULL OR NEW.completed_at IS NULL) THEN
        RAISE EXCEPTION
            'started_at and completed_at are required when status is COMPLETED.';
    END IF;

    -- CANCELLED requires cancelled_at
    IF NEW.status = 'CANCELLED' AND NEW.cancelled_at IS NULL THEN
        RAISE EXCEPTION 'cancelled_at is required when status is CANCELLED.';
    END IF;

    -- Content is immutable after SENDING / COMPLETED / CANCELLED
    IF OLD.status IN ('SENDING', 'COMPLETED', 'CANCELLED') THEN
        IF NEW.title IS DISTINCT FROM OLD.title THEN
            RAISE EXCEPTION 'Campaign title is immutable after %s.', OLD.status;
        END IF;
        IF NEW.body IS DISTINCT FROM OLD.body THEN
            RAISE EXCEPTION 'Campaign body is immutable after %s.', OLD.status;
        END IF;
        IF NEW.navigation_payload IS DISTINCT FROM OLD.navigation_payload THEN
            RAISE EXCEPTION
                'Campaign navigation_payload is immutable after %s.', OLD.status;
        END IF;
        IF NEW.audience_definition IS DISTINCT FROM OLD.audience_definition THEN
            RAISE EXCEPTION
                'Campaign audience_definition is immutable after %s.', OLD.status;
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER enforce_notification_campaign_lifecycle
BEFORE UPDATE ON public.notification_campaigns
FOR EACH ROW
EXECUTE FUNCTION public.enforce_notification_campaign_lifecycle();

ALTER TABLE public.notification_campaigns ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.notification_campaigns
FROM anon, authenticated;


-- ---------------------------------------------------------------------------
-- 4. Notification outbox events
-- ---------------------------------------------------------------------------

CREATE TABLE public.notification_outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    notification_type VARCHAR(30) NOT NULL CHECK (
        notification_type IN (
            'CHAT_MESSAGE',
            'MATCH_CREATED',
            'LIKE_RECEIVED',
            'ACCOUNT_ALERT',
            'MARKETING'
        )
    ),

    recipient_user_id UUID NOT NULL
        REFERENCES public.app_users(id) ON DELETE RESTRICT,

    actor_user_id UUID
        REFERENCES public.app_users(id) ON DELETE SET NULL,

    match_id UUID
        REFERENCES public.matches(id) ON DELETE SET NULL,

    message_id UUID
        REFERENCES public.messages(id) ON DELETE SET NULL,

    discovery_action_id UUID
        REFERENCES public.user_discovery_actions(id) ON DELETE SET NULL,

    campaign_id UUID
        REFERENCES public.notification_campaigns(id) ON DELETE SET NULL,

    dedupe_key VARCHAR(255) NOT NULL UNIQUE,
    collapse_key VARCHAR(255),

    payload JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (jsonb_typeof(payload) = 'object'),

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN (
            'PENDING',
            'PROCESSING',
            'FANOUT_COMPLETE',
            'SKIPPED',
            'FAILED'
        )
    ),

    attempt_count INTEGER NOT NULL DEFAULT 0
        CHECK (attempt_count >= 0),

    available_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at        TIMESTAMPTZ,

    locked_at         TIMESTAMPTZ,
    locked_by         VARCHAR(100),
    lease_expires_at  TIMESTAMPTZ,

    fanout_completed_at TIMESTAMPTZ,
    last_error        TEXT,

    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_notification_outbox_processing_lease CHECK (
        status <> 'PROCESSING'
        OR (
            locked_at IS NOT NULL
            AND locked_by IS NOT NULL
            AND lease_expires_at IS NOT NULL
        )
    )
);

CREATE INDEX idx_notification_outbox_claim_pending
    ON public.notification_outbox_events(available_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_notification_outbox_processing_lease
    ON public.notification_outbox_events(lease_expires_at)
    WHERE status = 'PROCESSING';

CREATE INDEX idx_notification_outbox_recipient_created
    ON public.notification_outbox_events(recipient_user_id, created_at DESC);

CREATE INDEX idx_notification_outbox_campaign
    ON public.notification_outbox_events(campaign_id, created_at)
    WHERE campaign_id IS NOT NULL;

ALTER TABLE public.notification_outbox_events ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.notification_outbox_events
FROM anon, authenticated;


-- ---------------------------------------------------------------------------
-- 5. Per-device notification deliveries
-- ---------------------------------------------------------------------------

CREATE TABLE public.notification_deliveries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    notification_outbox_event_id UUID NOT NULL
        REFERENCES public.notification_outbox_events(id) ON DELETE RESTRICT,

    notification_device_id UUID NOT NULL
        REFERENCES public.notification_devices(id) ON DELETE RESTRICT,

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN (
            'PENDING',
            'PROCESSING',
            'SUBMITTED',
            'CONFIRMED',
            'UNKNOWN',
            'FAILED',
            'SKIPPED'
        )
    ),

    resolution_code VARCHAR(100),

    attempt_count INTEGER NOT NULL DEFAULT 0
        CHECK (attempt_count >= 0),

    available_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    locked_at        TIMESTAMPTZ,
    locked_by        VARCHAR(100),
    lease_expires_at TIMESTAMPTZ,

    provider_ticket_id  TEXT,
    submitted_at        TIMESTAMPTZ,

    next_receipt_check_at TIMESTAMPTZ,
    receipt_deadline_at   TIMESTAMPTZ,
    receipt_checked_at    TIMESTAMPTZ,
    confirmed_at          TIMESTAMPTZ,

    last_error_code VARCHAR(100),
    last_error      TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_notification_delivery_per_device
        UNIQUE (notification_outbox_event_id, notification_device_id),

    CONSTRAINT check_notification_delivery_processing_lease CHECK (
        status <> 'PROCESSING'
        OR (
            locked_at IS NOT NULL
            AND locked_by IS NOT NULL
            AND lease_expires_at IS NOT NULL
        )
    )
);

CREATE INDEX idx_notification_deliveries_claim_pending
    ON public.notification_deliveries(available_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_notification_deliveries_processing_lease
    ON public.notification_deliveries(lease_expires_at)
    WHERE status = 'PROCESSING';

CREATE INDEX idx_notification_deliveries_receipt_check
    ON public.notification_deliveries(next_receipt_check_at)
    WHERE status = 'SUBMITTED';

CREATE UNIQUE INDEX unique_notification_delivery_provider_ticket
    ON public.notification_deliveries(provider_ticket_id)
    WHERE provider_ticket_id IS NOT NULL;

CREATE TRIGGER set_timestamp_notification_deliveries
BEFORE UPDATE ON public.notification_deliveries
FOR EACH ROW
EXECUTE FUNCTION public.update_updated_at_column();

ALTER TABLE public.notification_deliveries ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.notification_deliveries
FROM anon, authenticated;

-- Also apply RLS to notification_devices (already has rows; add without dropping)
ALTER TABLE public.notification_devices ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.notification_devices
FROM anon, authenticated;

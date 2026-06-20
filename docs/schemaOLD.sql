-- ==========================================
-- QALIYE FINAL SCHEMA
-- Spring Boot-centric architecture
--
-- Direct Supabase client access is limited to:
--   1) Supabase Auth/session
--   2) SELECT on messages for chat initial read + Supabase Realtime receive
--
-- All other reads/writes are performed through Spring Boot using:
--   - Supabase JWT validation for caller identity
--   - Direct Postgres/JDBC or service-role operations for trusted server writes
--   - Supabase Storage service-role access for private files
-- ==========================================


-- ==========================================
-- 1. EXTENSIONS & GLOBAL UTILITIES
-- ==========================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "postgis";

CREATE OR REPLACE FUNCTION public.update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;


-- ==========================================
-- 2. CORE INFRASTRUCTURE & AUTHENTICATION
-- ==========================================

CREATE TABLE public.subscription_plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    plan_code VARCHAR(50) NOT NULL,
    country_code VARCHAR(10) NOT NULL DEFAULT 'GLOBAL',
    price_cents INTEGER NOT NULL CHECK (price_cents >= 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    billing_interval VARCHAR(20) NOT NULL CHECK (
        billing_interval IN ('WEEKLY', 'MONTHLY', 'YEARLY')
    ),
    features JSONB NOT NULL DEFAULT '{}'
        CHECK (jsonb_typeof(features) = 'object'),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_plan_code_per_country UNIQUE (plan_code, country_code)
);

-- Supabase Auth owns email, phone, password, OTP, OAuth identity.
-- This table links auth users to internal application state.
CREATE TABLE public.app_users (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (
        status IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED')
    ),
    role VARCHAR(20) NOT NULL DEFAULT 'USER' CHECK (
        role IN ('USER', 'MODERATOR', 'ADMIN')
    ),
    preferred_language VARCHAR(10) NOT NULL DEFAULT 'en' CHECK (
        preferred_language IN ('en', 'am', 'ti', 'om')
    ),
    last_active_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Searchable city/country options for manual location selection.
-- This is intentionally a single denormalized place table for MVP instead of
-- separate countries/cities tables. The mobile app searches locations through
-- Spring Boot, the user selects a place_id, and Spring Boot copies the trusted
-- centroid into addresses.coords.
CREATE TABLE public.location_places (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    country_code VARCHAR(2) NOT NULL,
    country_name VARCHAR(100) NOT NULL,
    region VARCHAR(100),
    city VARCHAR(100) NOT NULL,
    display_name TEXT NOT NULL,
	alternative_names TEXT,
    coords GEOGRAPHY(Point, 4326) NOT NULL,
    location_precision VARCHAR(20) NOT NULL DEFAULT 'CITY' CHECK (
        location_precision IN ('CITY', 'REGION', 'COUNTRY')
    ),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_location_place UNIQUE NULLS NOT DISTINCT (country_code, region, city)
);

-- One location record per user. Exact coordinates are backend-only and never exposed
-- directly to the mobile client. GPS stores precise device coordinates; MANUAL
-- stores the selected location_places centroid.
CREATE TABLE public.addresses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE CASCADE,
    location_place_id UUID REFERENCES public.location_places(id) ON DELETE SET NULL,
    country_code VARCHAR(2) NOT NULL,
    country_name VARCHAR(100) NOT NULL,
    city VARCHAR(100) NOT NULL,
    region VARCHAR(100),
    coords GEOGRAPHY(Point, 4326) NOT NULL,
    formatted_address TEXT,
    location_source VARCHAR(50) NOT NULL DEFAULT 'GPS' CHECK (
        location_source IN ('GPS', 'MANUAL', 'IP')
    ),
    location_precision VARCHAR(20) NOT NULL DEFAULT 'EXACT' CHECK (
        location_precision IN ('EXACT', 'CITY', 'REGION', 'COUNTRY', 'APPROXIMATE')
    ),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_address_per_user UNIQUE (user_id),
    CONSTRAINT check_manual_location_has_place CHECK (
        location_source <> 'MANUAL' OR location_place_id IS NOT NULL
    )
);


-- ==========================================
-- 3. USER PROFILES & DISCOVERY SETTINGS
-- ==========================================

CREATE TABLE public.profiles (
    user_id UUID PRIMARY KEY REFERENCES public.app_users(id) ON DELETE CASCADE,
    address_id UUID REFERENCES public.addresses(id) ON DELETE SET NULL,

    display_name VARCHAR(100) NOT NULL,
    gender VARCHAR(20) NOT NULL CHECK (gender IN ('MALE', 'FEMALE', 'OTHER')),
    date_of_birth DATE NOT NULL,
    bio TEXT,

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

    has_children BOOLEAN DEFAULT FALSE,
    wants_children BOOLEAN,
    smoking BOOLEAN DEFAULT FALSE,
    drinking BOOLEAN DEFAULT FALSE,

    is_visible BOOLEAN NOT NULL DEFAULT TRUE,
    is_onboarded BOOLEAN NOT NULL DEFAULT FALSE,
    is_verified BOOLEAN NOT NULL DEFAULT FALSE,

    -- Server-computed only. The mobile client must never write this directly.
    profile_completion_score INTEGER DEFAULT 0 CHECK (
        profile_completion_score BETWEEN 0 AND 100
    ),

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Private storage only. Do not store public URLs as source of truth.
-- Spring Boot generates signed URLs for DTO responses.
CREATE TABLE public.profile_photos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE CASCADE,

    storage_bucket VARCHAR(100) NOT NULL DEFAULT 'profile-photos',
    storage_path TEXT NOT NULL,
    image_url TEXT NOT NULL DEFAULT '',

    photo_order INTEGER NOT NULL CHECK (photo_order BETWEEN 0 AND 8),
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    is_verified BOOLEAN NOT NULL DEFAULT FALSE,

    moderation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        moderation_status IN ('PENDING', 'APPROVED', 'REJECTED')
    ),
    reviewed_by UUID REFERENCES public.app_users(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMP WITH TIME ZONE,
    rejection_reason TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'
        CHECK (jsonb_typeof(metadata) = 'object'),

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_user_photo_order UNIQUE (user_id, photo_order),
    CONSTRAINT unique_profile_photo_storage_object UNIQUE (storage_bucket, storage_path)
);

CREATE TABLE public.discovery_preferences (
    user_id UUID PRIMARY KEY REFERENCES public.app_users(id) ON DELETE CASCADE,
    discovery_mode VARCHAR(20) NOT NULL DEFAULT 'STANDARD' CHECK (
        discovery_mode IN ('STANDARD', 'GLOBAL', 'INCOGNITO')
    ),
    preferred_residency_types TEXT[] NOT NULL DEFAULT '{}'
        CHECK (preferred_residency_types <@ ARRAY['ETHIOPIA','ERITREA','DIASPORA']::TEXT[]),
    interested_in_gender VARCHAR(20) NOT NULL DEFAULT 'ALL' CHECK (
        interested_in_gender IN ('MALE', 'FEMALE', 'ALL')
    ),
    min_age INTEGER NOT NULL DEFAULT 18 CHECK (min_age >= 18),
    max_age INTEGER NOT NULL DEFAULT 99 CHECK (max_age <= 120),
    max_distance_km INTEGER NOT NULL DEFAULT 50 CHECK (max_distance_km > 0),
    open_to_long_distance BOOLEAN NOT NULL DEFAULT FALSE,
    open_to_relocation BOOLEAN NOT NULL DEFAULT FALSE,
    show_verified_only BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_age_range CHECK (min_age <= max_age)
);


-- Automated core user state and default preference trigger.
CREATE OR REPLACE FUNCTION public.handle_new_auth_user()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.app_users (id)
    VALUES (NEW.id)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO public.discovery_preferences (
        user_id,
        discovery_mode,
        interested_in_gender,
        min_age,
        max_age,
        max_distance_km
    )
    VALUES (NEW.id, 'STANDARD', 'ALL', 18, 99, 50)
    ON CONFLICT (user_id) DO NOTHING;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, auth;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;

CREATE TRIGGER on_auth_user_created
AFTER INSERT ON auth.users
FOR EACH ROW
EXECUTE FUNCTION public.handle_new_auth_user();


-- ==========================================
-- 3.5 AGE COMPLIANCE VERIFICATION
-- ==========================================

CREATE OR REPLACE FUNCTION public.verify_profile_age_compliance()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.date_of_birth > (CURRENT_DATE - INTERVAL '18 years')::date THEN
        RAISE EXCEPTION 'Age Compliance Violation: User profile registration requires a minimum age of 18 years.';
    END IF;

    IF NEW.date_of_birth < (CURRENT_DATE - INTERVAL '120 years')::date THEN
        RAISE EXCEPTION 'Age Compliance Violation: Date of birth is outside the supported range.';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER enforce_profile_age_compliance
BEFORE INSERT OR UPDATE OF date_of_birth ON public.profiles
FOR EACH ROW
EXECUTE FUNCTION public.verify_profile_age_compliance();


-- ==========================================
-- 4. SWIPING ENGINE & VELOCITY LIMITS
-- ==========================================

CREATE TABLE public.user_discovery_actions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE CASCADE,
    target_user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE CASCADE,
    action_type VARCHAR(20) NOT NULL CHECK (
        action_type IN ('LIKE', 'PASS', 'SUPERLIKE')
    ),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_actor_target_action UNIQUE (actor_user_id, target_user_id),
    CONSTRAINT check_not_self_action CHECK (actor_user_id <> target_user_id)
);

CREATE TABLE public.user_daily_limits (
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE CASCADE,
    limit_date DATE NOT NULL DEFAULT CURRENT_DATE,
    likes_used INTEGER NOT NULL DEFAULT 0 CHECK (likes_used >= 0),
    super_likes_used INTEGER NOT NULL DEFAULT 0 CHECK (super_likes_used >= 0),
    rewinds_used INTEGER NOT NULL DEFAULT 0 CHECK (rewinds_used >= 0),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (user_id, limit_date)
);


-- ==========================================
-- 5. MATCHMAKING & COMMUNICATIONS ENGINE
-- ==========================================

-- Spring Boot must sort user UUIDs before insert: user_one_id < user_two_id.
CREATE TABLE public.matches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_one_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE CASCADE,
    user_two_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACCEPTED' CHECK (
        status IN ('ACCEPTED', 'UNMATCHED')
    ),
    matched_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    unmatched_at TIMESTAMP WITH TIME ZONE,
    last_message_at TIMESTAMP WITH TIME ZONE,
    user_one_last_read_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    user_two_last_read_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_user_order CHECK (user_one_id < user_two_id),
    CONSTRAINT unique_match_pair UNIQUE (user_one_id, user_two_id)
);

CREATE TABLE public.messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    match_id UUID NOT NULL REFERENCES public.matches(id) ON DELETE CASCADE,
    sender_user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE CASCADE,
    client_message_id UUID NOT NULL,
    message_type VARCHAR(20) NOT NULL DEFAULT 'TEXT' CHECK (
        message_type IN ('TEXT', 'IMAGE', 'VOICE', 'ICEBREAKER', 'PROMPT_REPLY')
    ),
    body TEXT,

    -- For private chat media. Spring Boot uploads and returns signed URLs via API.
    storage_bucket VARCHAR(100),
    storage_path TEXT,

    moderation_status VARCHAR(20) NOT NULL DEFAULT 'APPROVED' CHECK (
        moderation_status IN ('PENDING', 'APPROVED', 'REJECTED_FLAGGED')
    ),
    metadata JSONB NOT NULL DEFAULT '{}'
        CHECK (jsonb_typeof(metadata) = 'object'),

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    edited_at TIMESTAMP WITH TIME ZONE,
    deleted_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT unique_sender_client_message UNIQUE (sender_user_id, client_message_id),
    CONSTRAINT unique_message_storage_object UNIQUE (storage_bucket, storage_path),
    CONSTRAINT check_message_has_content CHECK (
        NULLIF(BTRIM(body), '') IS NOT NULL
        OR NULLIF(BTRIM(storage_path), '') IS NOT NULL
    ),
    CONSTRAINT check_storage_bucket_and_path_together CHECK (
        (storage_bucket IS NULL AND storage_path IS NULL)
        OR (storage_bucket IS NOT NULL AND storage_path IS NOT NULL)
    )
);

-- Defense-in-depth: even though only Spring Boot writes messages,
-- the database still rejects messages from non-participants or inactive matches.
CREATE OR REPLACE FUNCTION public.validate_message_sender_is_match_participant()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.matches m
        WHERE m.id = NEW.match_id
          AND m.status = 'ACCEPTED'
          AND (m.user_one_id = NEW.sender_user_id OR m.user_two_id = NEW.sender_user_id)
    ) THEN
        RAISE EXCEPTION 'Message sender must be a participant in an accepted match.';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER validate_message_sender_before_insert
BEFORE INSERT ON public.messages
FOR EACH ROW
EXECUTE FUNCTION public.validate_message_sender_is_match_participant();

CREATE OR REPLACE FUNCTION public.touch_match_last_message_at()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE public.matches
    SET last_message_at = COALESCE(NEW.created_at, CURRENT_TIMESTAMP),
        updated_at = CURRENT_TIMESTAMP
    WHERE id = NEW.match_id;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER touch_match_after_message_insert
AFTER INSERT ON public.messages
FOR EACH ROW
EXECUTE FUNCTION public.touch_match_last_message_at();


-- ==========================================
-- 6. AUDITING, SECURITY, & IDENTITY CHECKS
-- ==========================================

CREATE TABLE public.user_blocks (
    blocker_user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE CASCADE,
    blocked_user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE CASCADE,
    reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (blocker_user_id, blocked_user_id),
    CONSTRAINT check_not_self_block CHECK (blocker_user_id <> blocked_user_id)
);

CREATE TABLE public.user_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_user_id UUID REFERENCES public.app_users(id) ON DELETE SET NULL,
    reported_user_id UUID REFERENCES public.app_users(id) ON DELETE SET NULL,
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
    reviewed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_not_self_report CHECK (
        reporter_user_id IS NULL
        OR reported_user_id IS NULL
        OR reporter_user_id <> reported_user_id
    )
);

CREATE TABLE public.user_verifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE CASCADE,
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

    submitted_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    reviewed_by UUID REFERENCES public.app_users(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMP WITH TIME ZONE,
    rejection_reason TEXT,
    expires_at TIMESTAMP WITH TIME ZONE,
    metadata JSONB NOT NULL DEFAULT '{}'
        CHECK (jsonb_typeof(metadata) = 'object'),

    CONSTRAINT unique_verification_storage_object UNIQUE (storage_bucket, storage_path)
);

CREATE TABLE public.notification_devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE CASCADE,
    device_token TEXT NOT NULL,
    platform VARCHAR(20) NOT NULL CHECK (platform IN ('IOS', 'ANDROID', 'WEB')),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_device_token UNIQUE (device_token)
);

-- Moderator/admin operations tracking.
CREATE TABLE public.audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id UUID REFERENCES public.app_users(id) ON DELETE SET NULL,
    action VARCHAR(100) NOT NULL,
    target_table VARCHAR(100) NOT NULL,
    target_id UUID,
    details JSONB NOT NULL DEFAULT '{}'
        CHECK (jsonb_typeof(details) = 'object'),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);


-- ==========================================
-- 7. REVENUE, SUBSCRIPTIONS & BOOSTS
-- ==========================================

CREATE TABLE public.user_subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE CASCADE,
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
    provider_subscription_id VARCHAR(255) UNIQUE,
    status VARCHAR(30) NOT NULL CHECK (
        status IN ('ACTIVE', 'PAST_DUE', 'CANCELED', 'UNPAID', 'PENDING_VERIFICATION')
    ),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    current_period_start TIMESTAMP WITH TIME ZONE NOT NULL,
    current_period_end TIMESTAMP WITH TIME ZONE NOT NULL,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_subscription_period CHECK (current_period_end > current_period_start)
);

CREATE TABLE public.transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE CASCADE,
    subscription_id UUID REFERENCES public.user_subscriptions(id) ON DELETE SET NULL,
    payment_purpose VARCHAR(30) NOT NULL CHECK (
        payment_purpose IN ('SUBSCRIPTION', 'CONSUMABLE_PACK', 'PROFILE_BOOST')
    ),
    amount_cents INTEGER NOT NULL CHECK (amount_cents >= 0),
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
    provider_transaction_id VARCHAR(255) UNIQUE,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN ('PENDING', 'COMPLETED', 'FAILED', 'MANUAL_REVIEW')
    ),

    -- Private receipt storage for local/manual payments.
    receipt_storage_bucket VARCHAR(100),
    receipt_storage_path TEXT,

    admin_notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_transaction_receipt_object UNIQUE (receipt_storage_bucket, receipt_storage_path),
    CONSTRAINT check_receipt_bucket_and_path_together CHECK (
        (receipt_storage_bucket IS NULL AND receipt_storage_path IS NULL)
        OR (receipt_storage_bucket IS NOT NULL AND receipt_storage_path IS NOT NULL)
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
    provider_event_id VARCHAR(255) NOT NULL UNIQUE,
    event_type VARCHAR(100) NOT NULL,
    amount_cents INTEGER,
    currency VARCHAR(3),
    raw_payload JSONB NOT NULL DEFAULT '{}'
        CHECK (jsonb_typeof(raw_payload) = 'object'),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE public.active_boosts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE CASCADE,
    transaction_id UUID REFERENCES public.transactions(id) ON DELETE SET NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_boost_period CHECK (expires_at > started_at)
);


-- ==========================================
-- 8. CULTURAL PROFILE PROMPTS
-- ==========================================

CREATE TABLE public.profile_prompts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    prompt_text TEXT NOT NULL,
    category VARCHAR(50) NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE public.profile_prompt_translations (
    prompt_id UUID NOT NULL REFERENCES public.profile_prompts(id) ON DELETE CASCADE,
    locale VARCHAR(10) NOT NULL CHECK (locale IN ('en', 'am', 'ti', 'om')),
    prompt_text TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (prompt_id, locale)
);

CREATE TABLE public.profile_prompt_answers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE CASCADE,
    prompt_id UUID NOT NULL REFERENCES public.profile_prompts(id) ON DELETE CASCADE,
    answer_text TEXT NOT NULL CHECK (char_length(answer_text) BETWEEN 1 AND 300),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_user_prompt UNIQUE (user_id, prompt_id)
);


-- ==========================================
-- 9. PERFORMANCE INDEXES
-- ==========================================

-- Manual location search and spatial discovery.
CREATE INDEX idx_location_places_active_country_city
ON public.location_places(country_code, city)
WHERE is_active = TRUE;

CREATE INDEX idx_location_places_city_lower
ON public.location_places(LOWER(city))
WHERE is_active = TRUE;

CREATE INDEX idx_location_places_display_lower
ON public.location_places(LOWER(display_name))
WHERE is_active = TRUE;

CREATE INDEX idx_location_places_coords
ON public.location_places USING GIST(coords);

CREATE INDEX idx_addresses_coords ON public.addresses USING GIST(coords);
CREATE INDEX idx_addresses_user_id ON public.addresses(user_id);
CREATE INDEX idx_addresses_location_place_id ON public.addresses(location_place_id);

-- Discovery filtering. Spring Boot performs final eligibility and ranking.
CREATE INDEX idx_profiles_discovery_bundle
ON public.profiles(gender, residency_type, date_of_birth)
WHERE is_visible = TRUE
  AND is_onboarded = TRUE
  AND address_id IS NOT NULL;

CREATE INDEX idx_profiles_date_of_birth ON public.profiles(date_of_birth);
CREATE INDEX idx_profiles_address_id ON public.profiles(address_id);
CREATE INDEX idx_profiles_verified_visible
ON public.profiles(is_verified)
WHERE is_visible = TRUE AND is_onboarded = TRUE;

-- Profile photos.
CREATE INDEX idx_profile_photos_user_order ON public.profile_photos(user_id, photo_order);
CREATE INDEX idx_profile_photos_moderation_queue
ON public.profile_photos(moderation_status, created_at);
CREATE INDEX idx_profile_photos_approved_primary
ON public.profile_photos(user_id)
WHERE is_primary = TRUE AND moderation_status = 'APPROVED';
CREATE INDEX idx_profile_photos_user_status
ON public.profile_photos(user_id, moderation_status);

-- Enforces at most one primary photo per user. Backend enforces at least one
-- approved primary photo before onboarding/discovery.
CREATE UNIQUE INDEX unique_primary_photo_per_user
ON public.profile_photos(user_id)
WHERE is_primary = TRUE;

-- Blocks and discovery actions.
CREATE INDEX idx_blocks_reverse ON public.user_blocks(blocked_user_id, blocker_user_id);
CREATE INDEX idx_discovery_actions_actor_created
ON public.user_discovery_actions(actor_user_id, created_at DESC);
CREATE INDEX idx_discovery_actions_target_created
ON public.user_discovery_actions(target_user_id, created_at DESC);

-- Daily limits.
CREATE INDEX idx_user_daily_limits_date ON public.user_daily_limits(limit_date);

-- Matches and messages.
CREATE INDEX idx_matches_user_one_status_last_message
ON public.matches(user_one_id, status, last_message_at DESC);
CREATE INDEX idx_matches_user_two_status_last_message
ON public.matches(user_two_id, status, last_message_at DESC);
CREATE INDEX idx_matches_status ON public.matches(status);

CREATE INDEX idx_messages_match_created
ON public.messages(match_id, created_at ASC)
WHERE deleted_at IS NULL;

CREATE INDEX idx_messages_sender_created
ON public.messages(sender_user_id, created_at DESC);

CREATE INDEX idx_messages_moderation_scan
ON public.messages(moderation_status, created_at DESC);

-- Reports / verification / devices.
CREATE INDEX idx_reports_reported_user ON public.user_reports(reported_user_id, created_at DESC);
CREATE INDEX idx_reports_status_created ON public.user_reports(status, created_at DESC);

CREATE INDEX idx_user_verifications_user_submitted
ON public.user_verifications(user_id, submitted_at DESC);
CREATE INDEX idx_user_verifications_status_submitted
ON public.user_verifications(status, submitted_at);

CREATE INDEX idx_notification_devices_user_active
ON public.notification_devices(user_id)
WHERE is_active = TRUE;

-- Revenue.
CREATE INDEX idx_transactions_user ON public.transactions(user_id, created_at DESC);
CREATE INDEX idx_transactions_status_created ON public.transactions(status, created_at DESC);
CREATE INDEX idx_transactions_provider_ref ON public.transactions(provider, provider_transaction_id);

CREATE INDEX idx_payment_events_provider_event
ON public.payment_events(provider, provider_event_id);

CREATE INDEX idx_user_subscriptions_user_status
ON public.user_subscriptions(user_id, status);

CREATE UNIQUE INDEX unique_active_subscription_per_user
ON public.user_subscriptions(user_id)
WHERE status IN ('ACTIVE', 'PENDING_VERIFICATION');

-- Do not use a partial index with CURRENT_TIMESTAMP/NOW().
CREATE INDEX idx_active_boosts_user_expiry
ON public.active_boosts(user_id, expires_at);

CREATE INDEX idx_active_boosts_expires_at
ON public.active_boosts(expires_at);

-- Prompts.
CREATE INDEX idx_profile_prompts_active_order
ON public.profile_prompts(is_active, display_order);

CREATE INDEX idx_profile_prompt_answers_user
ON public.profile_prompt_answers(user_id);


-- ==========================================
-- 10. UPDATED_AT TIMESTAMP TRIGGERS
-- ==========================================

CREATE TRIGGER set_timestamp_subscription_plans
BEFORE UPDATE ON public.subscription_plans
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


-- ==========================================
-- 11. ENABLE ROW LEVEL SECURITY
-- ==========================================
--
-- RLS is enabled everywhere as defense-in-depth.
-- Only messages gets a direct client SELECT policy.
-- All other application access must go through Spring Boot.
-- Supabase Auth remains direct and is not controlled by these public-table policies.

ALTER TABLE public.subscription_plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.app_users ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.location_places ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.addresses ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profile_photos ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.discovery_preferences ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_discovery_actions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_daily_limits ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.matches ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_blocks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_verifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.notification_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.payment_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.active_boosts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profile_prompts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profile_prompt_translations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profile_prompt_answers ENABLE ROW LEVEL SECURITY;


-- ==========================================
-- 12. DIRECT CLIENT ACCESS POLICIES
-- ==========================================
--
-- No direct client INSERT/UPDATE/DELETE policies are created.
-- The only direct Supabase table access is chat message read/realtime receive.
--
-- The policy uses a SECURITY DEFINER helper so clients do not need direct SELECT
-- access to matches.

CREATE OR REPLACE FUNCTION public.can_read_match_messages(p_match_id UUID)
RETURNS BOOLEAN AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.matches m
        WHERE m.id = p_match_id
          AND m.status = 'ACCEPTED'
          AND (
              m.user_one_id = auth.uid()
              OR m.user_two_id = auth.uid()
          )
    );
$$ LANGUAGE sql
   STABLE
   SECURITY DEFINER
   SET search_path = public;

REVOKE ALL ON FUNCTION public.can_read_match_messages(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.can_read_match_messages(UUID) TO authenticated;

CREATE POLICY "Users can read approved messages in their accepted matches"
ON public.messages
FOR SELECT TO authenticated
USING (
    deleted_at IS NULL
    AND moderation_status = 'APPROVED'
    AND public.can_read_match_messages(match_id)
);


-- ==========================================
-- 13. SUPABASE REALTIME
-- ==========================================
--
-- Only messages is added to realtime because the client receives chat events
-- directly. Match lists and all other app data are fetched through Spring Boot.

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


-- ==========================================
-- 14. SUPABASE STORAGE BUCKETS
-- ==========================================
--
-- All buckets are private. Do not create authenticated client upload/read
-- policies on storage.objects. Spring Boot uploads/downloads through the
-- Supabase Storage API with the service-role key and returns signed URLs
-- only when appropriate.

INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES
    ('profile-photos', 'profile-photos', FALSE, 10485760, ARRAY['image/jpeg', 'image/png', 'image/webp']),
    ('verification-selfies', 'verification-selfies', FALSE, 10485760, ARRAY['image/jpeg', 'image/png', 'image/webp']),
    ('chat-media', 'chat-media', FALSE, 26214400, ARRAY['image/jpeg', 'image/png', 'image/webp', 'audio/mpeg', 'audio/mp4', 'audio/aac', 'audio/wav']),
    ('payment-receipts', 'payment-receipts', FALSE, 10485760, ARRAY['image/jpeg', 'image/png', 'image/webp', 'application/pdf'])
ON CONFLICT (id) DO UPDATE
SET public = EXCLUDED.public,
    file_size_limit = EXCLUDED.file_size_limit,
    allowed_mime_types = EXCLUDED.allowed_mime_types;


-- ==========================================
-- 15. IMPLEMENTATION NOTES
-- ==========================================
--
-- 1. Spring Boot owns all app reads/writes except direct chat message SELECT.
-- 2. The client may use Supabase Auth/session directly.
-- 3. The client may subscribe to public.messages realtime events, subject to RLS.
-- 4. The client must not directly query or mutate profiles, photos, preferences,
--    blocks, reports, devices, billing, verification, prompts, locations, or admin tables.
-- 5. Manual location selection uses location_places:
--      - Spring Boot exposes GET /api/v1/locations/search
--      - Mobile selects place_id
--      - Spring Boot copies location_places country/city/coords into addresses
--      - GPS submissions may write exact coordinates without location_place_id
-- 6. Spring Boot must enforce:
--      - app_users.status on every authenticated request
--      - onboarding completion rules
--      - location exists before onboarding completion
--      - manual addresses must reference location_places
--      - approved primary photo before discovery visibility
--      - profile_completion_score computation
--      - block + unmatch + audit in one transaction
--      - swipe idempotency without double-counting limits
--      - private Storage uploads and signed URL generation
--      - payment webhook idempotency
--      - admin/moderator role checks and audit logging
-- ==========================================



ALTER TABLE public.messages
  ADD COLUMN IF NOT EXISTS client_message_id UUID,
  ADD COLUMN IF NOT EXISTS media_url TEXT,
  ADD COLUMN IF NOT EXISTS storage_path TEXT,
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW();

CREATE UNIQUE INDEX IF NOT EXISTS unique_sender_client_message
ON public.messages(sender_user_id, client_message_id)
WHERE client_message_id IS NOT NULL;

CREATE OR REPLACE FUNCTION public.update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS set_timestamp_messages ON public.messages;

CREATE TRIGGER set_timestamp_messages
BEFORE UPDATE ON public.messages
FOR EACH ROW
EXECUTE FUNCTION public.update_updated_at_column();




ALTER TABLE public.profile_photos
  ADD COLUMN IF NOT EXISTS image_url TEXT,
  ADD COLUMN IF NOT EXISTS storage_path TEXT,
  ADD COLUMN IF NOT EXISTS photo_order INTEGER DEFAULT 0,
  ADD COLUMN IF NOT EXISTS is_primary BOOLEAN DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS is_verified BOOLEAN DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS moderation_status TEXT DEFAULT 'PENDING',
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW();

-- If the table is empty, it is safe to enforce NOT NULL
ALTER TABLE public.profile_photos
  ALTER COLUMN image_url SET NOT NULL,
  ALTER COLUMN storage_path SET NOT NULL,
  ALTER COLUMN photo_order SET NOT NULL,
  ALTER COLUMN is_primary SET NOT NULL,
  ALTER COLUMN is_verified SET NOT NULL,
  ALTER COLUMN moderation_status SET NOT NULL;

-- Recommended constraints/indexes
CREATE UNIQUE INDEX IF NOT EXISTS unique_user_photo_order
ON public.profile_photos(user_id, photo_order);

CREATE UNIQUE INDEX IF NOT EXISTS unique_primary_photo_per_user
ON public.profile_photos(user_id)
WHERE is_primary = TRUE;

CREATE INDEX IF NOT EXISTS idx_profile_photos_user_status
ON public.profile_photos(user_id, moderation_status);

-- Keep updated_at fresh
CREATE OR REPLACE FUNCTION public.update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS set_timestamp_profile_photos ON public.profile_photos;

CREATE TRIGGER set_timestamp_profile_photos
BEFORE UPDATE ON public.profile_photos
FOR EACH ROW
EXECUTE FUNCTION public.update_updated_at_column();



ALTER TABLE public.transactions
  ADD COLUMN IF NOT EXISTS receipt_image_url TEXT,
  ADD COLUMN IF NOT EXISTS admin_notes TEXT,
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW();

CREATE UNIQUE INDEX IF NOT EXISTS unique_provider_transaction_id
ON public.transactions(provider_transaction_id)
WHERE provider_transaction_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_transactions_user
ON public.transactions(user_id);

CREATE OR REPLACE FUNCTION public.update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS set_timestamp_transactions ON public.transactions;

CREATE TRIGGER set_timestamp_transactions
BEFORE UPDATE ON public.transactions
FOR EACH ROW
EXECUTE FUNCTION public.update_updated_at_column();




-- =============================================================================
-- V53: Payment & Credit System Redesign
--
-- 1.  Create feature_actions
-- 2.  Create subscription_plan_limit_and_cost + seed from subscription_plan_limits
-- 3.  Drop subscription_plan_limits
-- 4.  Create user_credit_balances
-- 5.  Create user_credit_ledger
-- 6.  Create user_credit_lot_consumptions
-- 7.  Modify user_entitlement_credit_lots (credit_source_type, BIGINT quantities)
-- 8.  Drop user_entitlement_credit_consumptions
-- 9.  Add included_credits to subscription_products
-- 10. Modify consumable_products (CREDIT_PURCHASE type, BIGINT quantity)
-- 11. Create user_action_limits_tracker + drop user_daily_limits/user_quota_usage
-- 12. Create country_settings + seed data
-- 13. Create pre_match_messages
-- 14. Add revealed_at + pre_match_message_id to user_discovery_actions
-- 15. Add verification_status/message/verified_at to app_users
-- 16. Migrate payment platform ANDROID/IOS → MOBILE
-- 17. Update payment platform CHECK constraints
-- 18. Redesign unique_active_online_payment_per_market index
-- 19. Update consumable_products seed data (credit packages)
-- =============================================================================

-- =============================================================================
-- 1. FEATURE ACTIONS
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.feature_actions (
    id   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50)  NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20)  NOT NULL CHECK (type IN ('ACTION', 'FEATURE'))
);

INSERT INTO public.feature_actions (code, name, type) VALUES
    ('LIKE',                   'Like',                   'ACTION'),
    ('SUPER_LIKE',             'Super Like',              'ACTION'),
    ('BOOST',                  'Boost',                  'ACTION'),
    ('REWIND',                 'Rewind',                 'ACTION'),
    ('SUPER_MESSAGE',          'Super Message',           'ACTION'),
    ('IMAGE_MESSAGE',          'Image Message',           'ACTION'),
    ('VOICE_MESSAGE',          'Voice Message',           'ACTION'),
    ('RETURN_PASSED_PROFILE',  'Return Passed Profile',   'ACTION'),
    ('CHANGE_ADDRESS',         'Change Address',          'ACTION'),
    ('SEE_WHO_LIKED_YOU',      'See Who Liked You',       'FEATURE'),
    ('INCOGNITO_MODE',         'Incognito Mode',          'FEATURE')
ON CONFLICT (code) DO NOTHING;

-- =============================================================================
-- 2. SUBSCRIPTION PLAN LIMIT AND COST
--    Replaces subscription_plan_limits.
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.subscription_plan_limit_and_cost (
    id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_plan_id    UUID        NOT NULL REFERENCES public.subscription_plans(id) ON DELETE RESTRICT,
    feature_action_id       UUID        NOT NULL REFERENCES public.feature_actions(id) ON DELETE RESTRICT,
    member_credit_cost      BIGINT      NOT NULL DEFAULT 0 CHECK (member_credit_cost >= 0),
    actual_credit_cost      BIGINT      NOT NULL DEFAULT 0 CHECK (actual_credit_cost >= 0),
    limit_value             INTEGER,
    period_type             VARCHAR(20) NOT NULL DEFAULT 'DAY' CHECK (
        period_type IN ('DAY', 'MONTH', 'BILLING_CYCLE')
    ),
    apply_credit_after_limit BOOLEAN    NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_plan_feature_action UNIQUE (subscription_plan_id, feature_action_id)
);

CREATE INDEX IF NOT EXISTS idx_splac_plan_id
    ON public.subscription_plan_limit_and_cost(subscription_plan_id);

-- Migrate data from subscription_plan_limits into subscription_plan_limit_and_cost.
-- Maps: LIKES→LIKE, SUPERLIKES→SUPER_LIKE, REWINDS→REWIND, BOOSTS→BOOST,
--       VOICE_CHAT_MSGS→VOICE_MESSAGE, IMAGE_CHAT_MSGS→IMAGE_MESSAGE
-- Period: DAILY→DAY, SUBSCRIPTION_MONTH→MONTH, BILLING_CYCLE→BILLING_CYCLE

INSERT INTO public.subscription_plan_limit_and_cost
    (subscription_plan_id, feature_action_id, member_credit_cost, actual_credit_cost,
     limit_value, period_type, apply_credit_after_limit)
SELECT
    spl.plan_id,
    fa.id,
    0   AS member_credit_cost,
    CASE
        WHEN spl.limit_type IN ('BOOSTS', 'VOICE_CHAT_MSGS', 'IMAGE_CHAT_MSGS') THEN 0
        ELSE 0
    END AS actual_credit_cost,
    spl.limit_value,
    CASE spl.period_type
        WHEN 'DAILY'              THEN 'DAY'
        WHEN 'SUBSCRIPTION_MONTH' THEN 'MONTH'
        WHEN 'BILLING_CYCLE'      THEN 'BILLING_CYCLE'
        ELSE                           'DAY'
    END AS period_type,
    FALSE AS apply_credit_after_limit
FROM public.subscription_plan_limits spl
JOIN public.feature_actions fa ON fa.code = CASE spl.limit_type
    WHEN 'LIKES'           THEN 'LIKE'
    WHEN 'SUPERLIKES'      THEN 'SUPER_LIKE'
    WHEN 'REWINDS'         THEN 'REWIND'
    WHEN 'BOOSTS'          THEN 'BOOST'
    WHEN 'VOICE_CHAT_MSGS' THEN 'VOICE_MESSAGE'
    WHEN 'IMAGE_CHAT_MSGS' THEN 'IMAGE_MESSAGE'
    ELSE NULL
END
WHERE fa.code IS NOT NULL
ON CONFLICT (subscription_plan_id, feature_action_id) DO NOTHING;

-- Ensure RETURN_PASSED_PROFILE, SUPER_LIKE, SUPER_MESSAGE rules exist for both plans.
-- FREE plan: unlimited LIKE, 1/day SUPER_LIKE at cost, 1/day REWIND at cost.
INSERT INTO public.subscription_plan_limit_and_cost
    (subscription_plan_id, feature_action_id, member_credit_cost, actual_credit_cost,
     limit_value, period_type, apply_credit_after_limit)
SELECT sp.id, fa.id, vals.member_cost, vals.actual_cost, vals.lv, vals.pt, vals.acal
FROM (VALUES
    ('RETURN_PASSED_PROFILE', 0::BIGINT, 0::BIGINT, 1::INTEGER,          'DAY',   FALSE),
    ('SUPER_MESSAGE',         0::BIGINT, 0::BIGINT, NULL::INTEGER,        'DAY',   FALSE),
    ('SEE_WHO_LIKED_YOU',     0::BIGINT, 0::BIGINT, NULL::INTEGER,        'MONTH', FALSE)
) AS vals(action_code, member_cost, actual_cost, lv, pt, acal)
CROSS JOIN (
    SELECT id FROM public.subscription_plans WHERE plan_code = 'FREE' AND country_code = 'GLOBAL' LIMIT 1
) sp
JOIN public.feature_actions fa ON fa.code = vals.action_code
ON CONFLICT (subscription_plan_id, feature_action_id) DO NOTHING;

INSERT INTO public.subscription_plan_limit_and_cost
    (subscription_plan_id, feature_action_id, member_credit_cost, actual_credit_cost,
     limit_value, period_type, apply_credit_after_limit)
SELECT sp.id, fa.id, vals.member_cost, vals.actual_cost, vals.lv, vals.pt, vals.acal
FROM (VALUES
    ('RETURN_PASSED_PROFILE', 0::BIGINT, 0::BIGINT, NULL::INTEGER,        'DAY',   TRUE),
    ('SUPER_MESSAGE',         0::BIGINT, 0::BIGINT, NULL::INTEGER,        'DAY',   FALSE),
    ('SEE_WHO_LIKED_YOU',     0::BIGINT, 0::BIGINT, NULL::INTEGER,        'MONTH', FALSE)
) AS vals(action_code, member_cost, actual_cost, lv, pt, acal)
CROSS JOIN (
    SELECT id FROM public.subscription_plans WHERE plan_code = 'PREMIUM' AND country_code = 'GLOBAL' LIMIT 1
) sp
JOIN public.feature_actions fa ON fa.code = vals.action_code
ON CONFLICT (subscription_plan_id, feature_action_id) DO NOTHING;

-- =============================================================================
-- 3. DROP subscription_plan_limits (data migrated above)
-- =============================================================================

DROP TABLE IF EXISTS public.subscription_plan_limits CASCADE;

-- =============================================================================
-- 4. USER CREDIT BALANCES
--    One row per user; balance must stay >= 0.
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.user_credit_balances (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
    balance    BIGINT      NOT NULL DEFAULT 0 CHECK (balance >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_credit_balance_user UNIQUE (user_id)
);

-- =============================================================================
-- 5. USER CREDIT LEDGER
--    Immutable audit log of every credit movement.
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.user_credit_ledger (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID         NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
    transaction_type VARCHAR(40)  NOT NULL CHECK (
        transaction_type IN (
            'SUBSCRIPTION_ALLOWANCE', 'CREDIT_PURCHASE',
            'ACTION_CONSUMPTION', 'REFUND', 'EXPIRATION',
            'PROMOTION', 'ADMIN_ADJUSTMENT', 'REVERSAL'
        )
    ),
    amount           BIGINT       NOT NULL,
    balance_after    BIGINT       NOT NULL CHECK (balance_after >= 0),
    source_type      VARCHAR(40),
    source_id        UUID,
    action_type      VARCHAR(50),
    idempotency_key  VARCHAR(255),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_credit_ledger_idempotency
        UNIQUE (user_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_credit_ledger_user
    ON public.user_credit_ledger(user_id, created_at DESC);

-- =============================================================================
-- 6. USER CREDIT LOT CONSUMPTIONS
--    Records exactly which credit lots supplied each consumed amount.
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.user_credit_lot_consumptions (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID        NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
    credit_lot_id  UUID        NOT NULL REFERENCES public.user_entitlement_credit_lots(id) ON DELETE RESTRICT,
    ledger_entry_id UUID       NOT NULL REFERENCES public.user_credit_ledger(id) ON DELETE RESTRICT,
    amount         BIGINT      NOT NULL CHECK (amount > 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_credit_lot_consumptions_lot
    ON public.user_credit_lot_consumptions(credit_lot_id);

CREATE INDEX IF NOT EXISTS idx_credit_lot_consumptions_ledger
    ON public.user_credit_lot_consumptions(ledger_entry_id);

-- =============================================================================
-- 7. MODIFY user_entitlement_credit_lots
--    Add credit_source_type, convert quantities to BIGINT.
-- =============================================================================

-- 7a. Add credit_source_type column (nullable for legacy BOOST/SUPERLIKE/REWIND rows)
ALTER TABLE public.user_entitlement_credit_lots
    ADD COLUMN IF NOT EXISTS credit_source_type VARCHAR(30);

-- 7b. Expand the entitlement_type CHECK to also accept new source types
--     (old rows keep BOOST_CREDIT/SUPERLIKE_CREDIT/REWIND_CREDIT)
ALTER TABLE public.user_entitlement_credit_lots
    DROP CONSTRAINT IF EXISTS user_entitlement_credit_lots_entitlement_type_check;

ALTER TABLE public.user_entitlement_credit_lots
    ADD CONSTRAINT user_entitlement_credit_lots_entitlement_type_check CHECK (
        entitlement_type IN (
            'BOOST_CREDIT', 'SUPERLIKE_CREDIT', 'REWIND_CREDIT',
            'CREDIT_PACKAGE'
        )
    );

-- Add CHECK on credit_source_type for new rows
ALTER TABLE public.user_entitlement_credit_lots
    DROP CONSTRAINT IF EXISTS user_entitlement_credit_lots_credit_source_type_check;

ALTER TABLE public.user_entitlement_credit_lots
    ADD CONSTRAINT user_entitlement_credit_lots_credit_source_type_check CHECK (
        credit_source_type IS NULL OR credit_source_type IN (
            'SUBSCRIPTION_ALLOWANCE', 'CREDIT_PURCHASE', 'PROMOTION', 'ADMIN_ADJUSTMENT'
        )
    );

-- 7c. Widen quantity columns from INTEGER to BIGINT
ALTER TABLE public.user_entitlement_credit_lots
    ALTER COLUMN quantity_granted   TYPE BIGINT,
    ALTER COLUMN quantity_remaining TYPE BIGINT;

-- 7d. Drop the FK constraint on source_ledger_entry_id so new rows can
--     reference user_credit_ledger entries (stored as a logical UUID reference)
ALTER TABLE public.user_entitlement_credit_lots
    DROP CONSTRAINT IF EXISTS user_entitlement_credit_lots_source_ledger_entry_id_fkey;

-- Re-add as NOT VALID so existing rows remain but column becomes non-FK (plain UUID)
-- We simply leave it as a UUID column without FK — both old ledger UUIDs and new ones.

-- 7e. Add composite index for new credit_source_type lookups
CREATE INDEX IF NOT EXISTS idx_credit_lots_user_source_remaining
    ON public.user_entitlement_credit_lots(user_id, credit_source_type, expires_at)
    WHERE quantity_remaining > 0 AND credit_source_type IS NOT NULL;

-- =============================================================================
-- 8. DROP user_entitlement_credit_consumptions (replaced by user_credit_lot_consumptions)
-- =============================================================================

DROP TABLE IF EXISTS public.user_entitlement_credit_consumptions CASCADE;

-- =============================================================================
-- 9. ADD included_credits TO subscription_products
-- =============================================================================

ALTER TABLE public.subscription_products
    ADD COLUMN IF NOT EXISTS included_credits BIGINT NOT NULL DEFAULT 0
        CHECK (included_credits >= 0);

-- =============================================================================
-- 10. MODIFY consumable_products
--     Add CREDIT_PURCHASE entitlement_type, widen quantity_granted to BIGINT,
--     replace old action-credit products with credit packages.
-- =============================================================================

-- 10a. Drop the old entitlement_type CHECK and add expanded one
ALTER TABLE public.consumable_products
    DROP CONSTRAINT IF EXISTS consumable_products_entitlement_type_check;

ALTER TABLE public.consumable_products
    ADD CONSTRAINT consumable_products_entitlement_type_check CHECK (
        entitlement_type IN (
            'BOOST_CREDIT', 'SUPERLIKE_CREDIT', 'REWIND_CREDIT',
            'CREDIT_PURCHASE'
        )
    );

-- 10b. Widen quantity_granted to BIGINT
ALTER TABLE public.consumable_products
    ALTER COLUMN quantity_granted TYPE BIGINT;

-- 10c. Deactivate all old action-credit products (BOOST/SUPERLIKE/REWIND packs).
--      They are replaced by unified credit packages.
UPDATE public.consumable_products
    SET is_active = FALSE,
        updated_at = CURRENT_TIMESTAMP
WHERE entitlement_type IN ('BOOST_CREDIT', 'SUPERLIKE_CREDIT', 'REWIND_CREDIT');

-- 10d. Seed credit package products
INSERT INTO public.consumable_products
    (id, product_code, name, entitlement_type, quantity_granted, expires_after_days, is_active)
VALUES
    ('f0000000-0000-0000-0000-000000000001', 'CREDITS_1000',  '1,000 Credits',   'CREDIT_PURCHASE', 1000,  NULL, TRUE),
    ('f0000000-0000-0000-0000-000000000002', 'CREDITS_5000',  '5,000 Credits',   'CREDIT_PURCHASE', 5000,  NULL, TRUE),
    ('f0000000-0000-0000-0000-000000000003', 'CREDITS_10000', '10,000 Credits',  'CREDIT_PURCHASE', 10000, NULL, TRUE),
    ('f0000000-0000-0000-0000-000000000004', 'CREDITS_25000', '25,000 Credits',  'CREDIT_PURCHASE', 25000, NULL, TRUE)
ON CONFLICT (product_code) DO UPDATE
    SET name              = EXCLUDED.name,
        entitlement_type  = EXCLUDED.entitlement_type,
        quantity_granted  = EXCLUDED.quantity_granted,
        expires_after_days = EXCLUDED.expires_after_days,
        is_active         = EXCLUDED.is_active,
        updated_at        = CURRENT_TIMESTAMP;

-- =============================================================================
-- 11. USER ACTION LIMITS TRACKER
--     Replaces user_daily_limits and user_quota_usage.
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.user_action_limits_tracker (
    id                              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                         UUID        NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
    subscription_plan_limit_and_cost_id UUID    NOT NULL
        REFERENCES public.subscription_plan_limit_and_cost(id) ON DELETE RESTRICT,
    used_count                      INTEGER     NOT NULL DEFAULT 0 CHECK (used_count >= 0),
    period_start_date               DATE        NOT NULL,
    period_end_date                 DATE        NOT NULL,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_action_limit_tracker
        UNIQUE (user_id, subscription_plan_limit_and_cost_id, period_start_date)
);

CREATE INDEX IF NOT EXISTS idx_action_limits_tracker_user
    ON public.user_action_limits_tracker(user_id, subscription_plan_limit_and_cost_id, period_start_date);

-- Drop old daily-limit tables
DROP TABLE IF EXISTS public.user_daily_limits CASCADE;
DROP TABLE IF EXISTS public.user_quota_usage CASCADE;

-- =============================================================================
-- 12. COUNTRY SETTINGS
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.country_settings (
    id                              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    country_code                    VARCHAR(10) NOT NULL UNIQUE,
    subscription_enabled            BOOLEAN     NOT NULL DEFAULT TRUE,
    credits_enabled                 BOOLEAN     NOT NULL DEFAULT TRUE,
    identity_verification_required  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Seed initial country settings
INSERT INTO public.country_settings
    (country_code, subscription_enabled, credits_enabled, identity_verification_required)
VALUES
    ('GLOBAL', TRUE,  TRUE,  FALSE),
    ('ET',     FALSE, TRUE,  FALSE),
    ('US',     TRUE,  TRUE,  FALSE),
    ('GB',     TRUE,  TRUE,  FALSE)
ON CONFLICT (country_code) DO UPDATE
    SET subscription_enabled           = EXCLUDED.subscription_enabled,
        credits_enabled                = EXCLUDED.credits_enabled,
        identity_verification_required = EXCLUDED.identity_verification_required,
        updated_at                     = CURRENT_TIMESTAMP;

-- =============================================================================
-- 13. PRE-MATCH MESSAGES (Super Messages)
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.pre_match_messages (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_id       UUID         NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
    receiver_id     UUID         NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
    message         TEXT         NOT NULL,
    action_type     VARCHAR(30)  NOT NULL DEFAULT 'SUPER_MESSAGE',
    credit_cost     BIGINT       NOT NULL DEFAULT 0 CHECK (credit_cost >= 0),
    status          VARCHAR(30)  NOT NULL DEFAULT 'SENT' CHECK (
        status IN ('SENT', 'VIEWED', 'ACCEPTED', 'PASSED', 'BLOCKED', 'EXPIRED')
    ),
    viewed_at       TIMESTAMPTZ,
    responded_at    TIMESTAMPTZ,
    match_id        UUID         REFERENCES public.matches(id) ON DELETE SET NULL,
    idempotency_key VARCHAR(255) UNIQUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_pre_match_messages_sender
    ON public.pre_match_messages(sender_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_pre_match_messages_receiver
    ON public.pre_match_messages(receiver_id, status, created_at DESC);

-- =============================================================================
-- 14. ADD revealed_at AND pre_match_message_id TO user_discovery_actions
-- =============================================================================

ALTER TABLE public.user_discovery_actions
    ADD COLUMN IF NOT EXISTS revealed_at TIMESTAMPTZ;

ALTER TABLE public.user_discovery_actions
    ADD COLUMN IF NOT EXISTS pre_match_message_id UUID
        REFERENCES public.pre_match_messages(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_discovery_actions_receiver_hidden_likes
    ON public.user_discovery_actions(target_user_id, revealed_at)
    WHERE action_type IN ('LIKE', 'SUPERLIKE')
      AND status = 'ACTIVE'
      AND revealed_at IS NULL;

-- =============================================================================
-- 15. ADD VERIFICATION COLUMNS TO app_users
-- =============================================================================

ALTER TABLE public.app_users
    ADD COLUMN IF NOT EXISTS verification_status VARCHAR(20) DEFAULT 'NOT_STARTED' CHECK (
        verification_status IN ('NOT_STARTED', 'PENDING', 'VERIFIED', 'FAILED')
    );

ALTER TABLE public.app_users
    ADD COLUMN IF NOT EXISTS verification_result_message TEXT;

ALTER TABLE public.app_users
    ADD COLUMN IF NOT EXISTS verified_at TIMESTAMPTZ;

-- =============================================================================
-- 16. DROP OLD PLATFORM CHECK CONSTRAINTS + OLD UNIQUE INDEX (before data migration)
--     Old constraints only allowed ('WEB','ANDROID','IOS') — must drop first
--     so the UPDATE to 'MOBILE' doesn't violate them.
--     Old unique index on (country_code, platform) must also drop first because
--     ANDROID+IOS rows for the same country collapse to MOBILE and would conflict.
-- =============================================================================

ALTER TABLE public.payment_methods
    DROP CONSTRAINT IF EXISTS payment_methods_platform_check;

ALTER TABLE public.payment_offers
    DROP CONSTRAINT IF EXISTS payment_offers_platform_check;

DROP INDEX IF EXISTS public.unique_active_online_payment_per_market;

DROP INDEX IF EXISTS public.unique_payment_offer_subscription;
DROP INDEX IF EXISTS public.unique_payment_offer_consumable;

-- =============================================================================
-- 17. MIGRATE PAYMENT PLATFORM: ANDROID / IOS → MOBILE
-- =============================================================================

UPDATE public.payment_methods
    SET platform   = 'MOBILE',
        updated_at = CURRENT_TIMESTAMP
WHERE platform IN ('ANDROID', 'IOS');

UPDATE public.payment_offers
    SET platform   = 'MOBILE',
        updated_at = CURRENT_TIMESTAMP
WHERE platform IN ('ANDROID', 'IOS');

-- =============================================================================
-- 18. ADD NEW PLATFORM CHECK CONSTRAINTS
-- =============================================================================

ALTER TABLE public.payment_methods
    ADD CONSTRAINT payment_methods_platform_check CHECK (
        platform IN ('WEB', 'MOBILE', 'ALL')
    );

ALTER TABLE public.payment_offers
    ADD CONSTRAINT payment_offers_platform_check CHECK (
        platform IN ('WEB', 'MOBILE', 'ALL')
    );

-- =============================================================================
-- 18. CREATE NEW unique_active_online_payment_method_per_market INDEX
--     Old index (country_code, platform) was dropped in step 16 before migration.
--     New index: (country_code, platform, method_code) — allows multiple
--     active online payment methods per market while preventing duplicates.
-- =============================================================================

CREATE UNIQUE INDEX IF NOT EXISTS unique_active_online_payment_method_per_market
    ON public.payment_methods(country_code, platform, method_code)
    WHERE payment_channel = 'ONLINE_PAYMENT' AND is_active = TRUE;

-- =============================================================================
-- 19. UPDATE payment_offers unique indexes (ANDROID/IOS → MOBILE collapsed rows)
--     After platform migration, (country_code, platform, subscription_product_id)
--     rows that were previously ANDROID and IOS are now both MOBILE.
--     De-duplicate by deactivating the older offer and keeping the newer one.
-- =============================================================================

-- Deactivate duplicate MOBILE subscription offers (keep the lowest id per market)
UPDATE public.payment_offers po
    SET is_active = FALSE,
        updated_at = CURRENT_TIMESTAMP
WHERE po.platform = 'MOBILE'
  AND po.subscription_product_id IS NOT NULL
  AND po.id::text NOT IN (
      SELECT MIN(id::text)
      FROM public.payment_offers
      WHERE platform = 'MOBILE'
        AND subscription_product_id IS NOT NULL
      GROUP BY country_code, platform, subscription_product_id
  );

-- Deactivate duplicate MOBILE consumable offers (keep the lowest id per market)
UPDATE public.payment_offers po
    SET is_active = FALSE,
        updated_at = CURRENT_TIMESTAMP
WHERE po.platform = 'MOBILE'
  AND po.consumable_product_id IS NOT NULL
  AND po.id::text NOT IN (
      SELECT MIN(id::text)
      FROM public.payment_offers
      WHERE platform = 'MOBILE'
        AND consumable_product_id IS NOT NULL
      GROUP BY country_code, platform, consumable_product_id
  );

-- Recreate unique partial indexes (old ones dropped in step 16 before migration)
CREATE UNIQUE INDEX IF NOT EXISTS unique_payment_offer_subscription
    ON public.payment_offers(country_code, platform, subscription_product_id)
    WHERE subscription_product_id IS NOT NULL AND is_active = TRUE;

CREATE UNIQUE INDEX IF NOT EXISTS unique_payment_offer_consumable
    ON public.payment_offers(country_code, platform, consumable_product_id)
    WHERE consumable_product_id IS NOT NULL AND is_active = TRUE;

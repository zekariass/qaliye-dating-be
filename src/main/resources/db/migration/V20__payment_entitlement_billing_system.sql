---- =============================================================================
---- V20: Payment, Subscription, Entitlement, Quota, and Boost System
----
---- Implements the full billing architecture from payment-entitlement-design-new.md.
---- Existing tables (subscription_plans, user_subscriptions, transactions,
---- payment_events, user_entitlement_ledger, active_boosts, user_daily_limits)
---- are preserved. New tables are added alongside them. Existing tables are
---- altered only with additive, non-destructive changes.
---- =============================================================================
--
---- Required for EXCLUDE USING GIST on UUID + tstzrange
--CREATE EXTENSION IF NOT EXISTS "btree_gist";
--
---- =============================================================================
---- 1. NEW SUBSCRIPTION PRODUCTS TABLE
---- Separates billing periods from plans. subscription_plans is preserved as-is;
---- new code uses subscription_products + payment_offers for pricing/durations.
---- =============================================================================
--
--CREATE TABLE IF NOT EXISTS public.subscription_products (
--    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
--    plan_id UUID NOT NULL REFERENCES public.subscription_plans(id) ON DELETE RESTRICT,
--    product_code VARCHAR(100) NOT NULL,
--    billing_interval_unit VARCHAR(20) NOT NULL CHECK (
--        billing_interval_unit IN ('DAY', 'WEEK', 'MONTH', 'YEAR')
--    ),
--    billing_interval_count SMALLINT NOT NULL CHECK (billing_interval_count > 0),
--    auto_renew_supported BOOLEAN NOT NULL DEFAULT TRUE,
--    is_active BOOLEAN NOT NULL DEFAULT TRUE,
--    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
--    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
--
--    CONSTRAINT unique_subscription_product_code UNIQUE (product_code)
--);
--
---- =============================================================================
---- 2. CONSUMABLE PRODUCTS TABLE
---- =============================================================================
--
--CREATE TABLE IF NOT EXISTS public.consumable_products (
--    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
--    product_code VARCHAR(100) NOT NULL,
--    name VARCHAR(100) NOT NULL,
--    entitlement_type VARCHAR(30) NOT NULL CHECK (
--        entitlement_type IN ('BOOST_CREDIT', 'SUPERLIKE_CREDIT', 'REWIND_CREDIT')
--    ),
--    quantity_granted INTEGER NOT NULL CHECK (quantity_granted > 0),
--    expires_after_days INTEGER CHECK (expires_after_days IS NULL OR expires_after_days > 0),
--    is_active BOOLEAN NOT NULL DEFAULT TRUE,
--    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
--    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
--
--    CONSTRAINT unique_consumable_product_code UNIQUE (product_code)
--);
--
---- =============================================================================
---- 3. PAYMENT OFFERS TABLE
---- =============================================================================
--
--CREATE TABLE IF NOT EXISTS public.payment_offers (
--    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
--    subscription_product_id UUID REFERENCES public.subscription_products(id) ON DELETE SET NULL,
--    consumable_product_id UUID REFERENCES public.consumable_products(id) ON DELETE SET NULL,
--    country_code VARCHAR(10) NOT NULL DEFAULT 'GLOBAL',
--    platform VARCHAR(20) NOT NULL CHECK (
--        platform IN ('ANDROID', 'IOS', 'WEB')
--    ),
--    payment_channel VARCHAR(50) NOT NULL CHECK (
--        payment_channel IN (
--            'REVENUECAT_APPLE', 'REVENUECAT_GOOGLE',
--            'CHAPA', 'MANUAL_TRANSFER', 'DIRECT_TELEBIRR'
--        )
--    ),
--    payment_method VARCHAR(50) NOT NULL CHECK (
--        payment_method IN (
--            'APPLE_IAP', 'GOOGLE_PLAY_BILLING',
--            'TELEBIRR', 'CBE_BIRR', 'BANK_TRANSFER', 'CARD'
--        )
--    ),
--    currency VARCHAR(3) NOT NULL,
--    price_minor_units INTEGER NOT NULL CHECK (price_minor_units >= 0),
--    external_product_id VARCHAR(255),
--    external_base_plan_id VARCHAR(255),
--    revenuecat_offering_id VARCHAR(100),
--    revenuecat_package_id VARCHAR(100),
--    auto_renew BOOLEAN NOT NULL DEFAULT FALSE,
--    is_active BOOLEAN NOT NULL DEFAULT TRUE,
--    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
--    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
--
--    CONSTRAINT check_offer_has_exactly_one_product CHECK (
--        (subscription_product_id IS NOT NULL AND consumable_product_id IS NULL)
--        OR
--        (subscription_product_id IS NULL AND consumable_product_id IS NOT NULL)
--    )
--);
--
--CREATE INDEX IF NOT EXISTS idx_payment_offers_country_platform_active
--    ON public.payment_offers(country_code, platform)
--    WHERE is_active = TRUE;
--
---- =============================================================================
---- 4. BILLING CUSTOMERS TABLE (RevenueCat mapping)
---- =============================================================================
--
--CREATE TABLE IF NOT EXISTS public.billing_customers (
--    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
--    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
--    provider VARCHAR(50) NOT NULL,
--    external_customer_id VARCHAR(255) NOT NULL,
--    original_external_customer_id VARCHAR(255),
--    metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
--    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
--    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
--
--    CONSTRAINT unique_billing_customer_provider_external UNIQUE (provider, external_customer_id),
--    CONSTRAINT unique_billing_customer_user_provider UNIQUE (user_id, provider)
--);
--
---- =============================================================================
---- 5. PAYMENT ORDERS TABLE
---- =============================================================================
--
--CREATE TABLE IF NOT EXISTS public.payment_orders (
--    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
--    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
--    payment_offer_id UUID NOT NULL REFERENCES public.payment_offers(id) ON DELETE RESTRICT,
--    order_reference VARCHAR(100) NOT NULL,
--    status VARCHAR(40) NOT NULL DEFAULT 'CREATED' CHECK (
--        status IN (
--            'CREATED', 'AWAITING_PAYMENT', 'RECEIPT_SUBMITTED',
--            'VERIFICATION_PENDING', 'MANUAL_REVIEW',
--            'VERIFIED', 'REJECTED', 'EXPIRED', 'CANCELLED'
--        )
--    ),
--    expected_amount_minor_units INTEGER NOT NULL CHECK (expected_amount_minor_units > 0),
--    expected_currency VARCHAR(3) NOT NULL,
--    payment_channel VARCHAR(50) NOT NULL,
--    payment_method VARCHAR(50) NOT NULL,
--    payment_instruction_snapshot JSONB NOT NULL DEFAULT '{}'::JSONB,
--    provider_checkout_url TEXT,
--    provider_order_reference VARCHAR(255),
--    expires_at TIMESTAMPTZ NOT NULL,
--    idempotency_key VARCHAR(255),
--    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
--    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
--
--    CONSTRAINT unique_payment_order_reference UNIQUE (order_reference)
--);
--
--CREATE INDEX IF NOT EXISTS idx_payment_orders_user_status
--    ON public.payment_orders(user_id, status);
--
--CREATE INDEX IF NOT EXISTS idx_payment_orders_status_created
--    ON public.payment_orders(status, created_at DESC);
--
--CREATE UNIQUE INDEX IF NOT EXISTS idx_payment_orders_idempotency
--    ON public.payment_orders(user_id, idempotency_key)
--    WHERE idempotency_key IS NOT NULL;
--
---- =============================================================================
---- 6. PAYMENT PROOFS TABLE
---- =============================================================================
--
--CREATE TABLE IF NOT EXISTS public.payment_proofs (
--    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
--    payment_order_id UUID NOT NULL REFERENCES public.payment_orders(id) ON DELETE RESTRICT,
--    proof_type VARCHAR(30) NOT NULL CHECK (
--        proof_type IN ('TRANSACTION_REFERENCE', 'RECEIPT_UPLOAD')
--    ),
--    payment_network VARCHAR(50),
--    transaction_reference VARCHAR(255),
--    receipt_storage_bucket VARCHAR(100),
--    receipt_storage_path TEXT,
--    submitted_amount_minor_units INTEGER,
--    submitted_currency VARCHAR(3),
--    submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
--);
--
--CREATE INDEX IF NOT EXISTS idx_payment_proofs_order
--    ON public.payment_proofs(payment_order_id);
--
---- =============================================================================
---- 7. PAYMENT VERIFICATION ATTEMPTS TABLE
---- =============================================================================
--
--CREATE TABLE IF NOT EXISTS public.payment_verification_attempts (
--    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
--    payment_order_id UUID NOT NULL REFERENCES public.payment_orders(id) ON DELETE RESTRICT,
--    payment_proof_id UUID REFERENCES public.payment_proofs(id) ON DELETE SET NULL,
--    verification_method VARCHAR(50) NOT NULL CHECK (
--        verification_method IN ('CHAPA_API', 'VERIFY_ET', 'ADMIN_REVIEW')
--    ),
--    provider_request_id VARCHAR(255),
--    provider_verification_reference VARCHAR(255),
--    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (
--        status IN (
--            'PENDING', 'VERIFIED', 'NOT_FOUND',
--            'AMOUNT_MISMATCH', 'RECIPIENT_MISMATCH',
--            'DUPLICATE_PAYMENT', 'MANUAL_REVIEW', 'REJECTED', 'FAILED'
--        )
--    ),
--    verified_amount_minor_units INTEGER,
--    verified_currency VARCHAR(3),
--    verified_recipient_reference VARCHAR(255),
--    verified_paid_at TIMESTAMPTZ,
--    raw_response JSONB NOT NULL DEFAULT '{}'::JSONB,
--    verified_by_admin_id UUID REFERENCES public.app_users(id) ON DELETE SET NULL,
--    admin_decision_note TEXT,
--    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
--    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
--);
--
--CREATE INDEX IF NOT EXISTS idx_payment_verification_order
--    ON public.payment_verification_attempts(payment_order_id);
--
---- Prevent re-use of a verified transfer reference
--CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_verified_provider_reference
--    ON public.payment_verification_attempts(verification_method, provider_verification_reference)
--    WHERE status = 'VERIFIED' AND provider_verification_reference IS NOT NULL;
--
---- =============================================================================
---- 8. ALTER user_subscriptions: add new columns for design-doc compatibility
---- =============================================================================
--
--ALTER TABLE public.user_subscriptions
--    ADD COLUMN IF NOT EXISTS payment_offer_id UUID REFERENCES public.payment_offers(id) ON DELETE SET NULL,
--    ADD COLUMN IF NOT EXISTS provider_subscription_reference VARCHAR(512),
--    ADD COLUMN IF NOT EXISTS auto_renew BOOLEAN NOT NULL DEFAULT FALSE,
--    ADD COLUMN IF NOT EXISTS ended_at TIMESTAMPTZ;
--
---- Add new statuses to user_subscriptions (drop old check and re-add expanded)
--ALTER TABLE public.user_subscriptions
--    DROP CONSTRAINT IF EXISTS user_subscriptions_status_check;
--
--ALTER TABLE public.user_subscriptions
--    ADD CONSTRAINT user_subscriptions_status_check CHECK (
--        status IN (
--            'ACTIVE', 'PAST_DUE', 'CANCELED', 'UNPAID',
--            'PENDING_VERIFICATION', 'GRACE_PERIOD', 'EXPIRED', 'REVOKED'
--        )
--    );
--
---- =============================================================================
---- 9. ALTER transactions: add new columns for design-doc compatibility
---- =============================================================================
--
--ALTER TABLE public.transactions
--    ADD COLUMN IF NOT EXISTS payment_order_id UUID REFERENCES public.payment_orders(id) ON DELETE SET NULL,
--    ADD COLUMN IF NOT EXISTS payment_offer_id UUID REFERENCES public.payment_offers(id) ON DELETE SET NULL,
--    ADD COLUMN IF NOT EXISTS related_transaction_id UUID REFERENCES public.transactions(id) ON DELETE SET NULL,
--    ADD COLUMN IF NOT EXISTS transaction_type VARCHAR(30) DEFAULT 'PURCHASE',
--    ADD COLUMN IF NOT EXISTS verification_provider VARCHAR(50),
--    ADD COLUMN IF NOT EXISTS country_code VARCHAR(10),
--    ADD COLUMN IF NOT EXISTS tax_amount_minor_units INTEGER,
--    ADD COLUMN IF NOT EXISTS provider_fee_minor_units INTEGER,
--    ADD COLUMN IF NOT EXISTS merchant_net_amount_minor_units INTEGER;
--
---- Expand transaction status constraint
--ALTER TABLE public.transactions
--    DROP CONSTRAINT IF EXISTS transactions_status_check;
--
--ALTER TABLE public.transactions
--    ADD CONSTRAINT transactions_status_check CHECK (
--        status IN (
--            'PENDING', 'COMPLETED', 'FAILED', 'MANUAL_REVIEW',
--            'REFUNDED', 'PARTIALLY_REFUNDED', 'REVERSED'
--        )
--    );
--
---- Expand payment_purpose constraint
--ALTER TABLE public.transactions
--    DROP CONSTRAINT IF EXISTS transactions_payment_purpose_check;
--
--ALTER TABLE public.transactions
--    ADD CONSTRAINT transactions_payment_purpose_check CHECK (
--        payment_purpose IN ('SUBSCRIPTION', 'CONSUMABLE_PACK', 'PROFILE_BOOST', 'CONSUMABLE')
--    );
--
---- Expand provider constraint
--ALTER TABLE public.transactions
--    DROP CONSTRAINT IF EXISTS transactions_provider_check;
--
--ALTER TABLE public.transactions
--    ADD CONSTRAINT transactions_provider_check CHECK (
--        provider IN (
--            'STRIPE', 'APPLE_APP_STORE', 'GOOGLE_PLAY',
--            'TELEBIRR', 'CBE_BIRR', 'CHAPA', 'BANK_TRANSFER',
--            'REVENUECAT', 'ADMIN'
--        )
--    );
--
---- =============================================================================
---- 10. ALTER payment_events: add new columns
---- =============================================================================
--
--ALTER TABLE public.payment_events
--    ADD COLUMN IF NOT EXISTS transaction_id UUID REFERENCES public.transactions(id) ON DELETE SET NULL,
--    ADD COLUMN IF NOT EXISTS payment_order_id UUID REFERENCES public.payment_orders(id) ON DELETE SET NULL,
--    ADD COLUMN IF NOT EXISTS processing_status VARCHAR(30) NOT NULL DEFAULT 'PROCESSED',
--    ADD COLUMN IF NOT EXISTS signature_verified_at TIMESTAMPTZ,
--    ADD COLUMN IF NOT EXISTS processed_at TIMESTAMPTZ,
--    ADD COLUMN IF NOT EXISTS processing_error TEXT;
--
---- Expand provider constraint on payment_events
--ALTER TABLE public.payment_events
--    DROP CONSTRAINT IF EXISTS payment_events_provider_check;
--
--ALTER TABLE public.payment_events
--    ADD CONSTRAINT payment_events_provider_check CHECK (
--        provider IN (
--            'STRIPE', 'REVENUECAT', 'APPLE_APP_STORE', 'GOOGLE_PLAY',
--            'TELEBIRR', 'CBE_BIRR', 'CHAPA', 'BANK_TRANSFER', 'VERIFY_ET'
--        )
--    );
--
---- =============================================================================
---- 11. ALTER user_entitlement_ledger: add new columns
---- =============================================================================
--
--ALTER TABLE public.user_entitlement_ledger
--    ADD COLUMN IF NOT EXISTS subscription_id UUID REFERENCES public.user_subscriptions(id) ON DELETE SET NULL;
--
---- Expand reason constraint
--ALTER TABLE public.user_entitlement_ledger
--    DROP CONSTRAINT IF EXISTS user_entitlement_ledger_reason_check;
--
--ALTER TABLE public.user_entitlement_ledger
--    ADD CONSTRAINT user_entitlement_ledger_reason_check CHECK (
--        reason IN (
--            'PURCHASE', 'SUBSCRIPTION_ALLOWANCE', 'CONSUMPTION',
--            'REFUND', 'EXPIRY', 'ADMIN_GRANT', 'ADJUSTMENT', 'REVERSAL'
--        )
--    );
--
---- Change idempotency_key from UUID to VARCHAR for flexible keys
--ALTER TABLE public.user_entitlement_ledger
--    ALTER COLUMN idempotency_key TYPE VARCHAR(255) USING idempotency_key::VARCHAR;
--
---- =============================================================================
---- 12. USER ENTITLEMENT CREDIT LOTS TABLE
---- =============================================================================
--
--CREATE TABLE IF NOT EXISTS public.user_entitlement_credit_lots (
--    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
--    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
--    entitlement_type VARCHAR(30) NOT NULL CHECK (
--        entitlement_type IN ('BOOST_CREDIT', 'SUPERLIKE_CREDIT', 'REWIND_CREDIT')
--    ),
--    source_ledger_entry_id UUID NOT NULL REFERENCES public.user_entitlement_ledger(id) ON DELETE RESTRICT,
--    quantity_granted INTEGER NOT NULL CHECK (quantity_granted > 0),
--    quantity_remaining INTEGER NOT NULL CHECK (quantity_remaining >= 0),
--    expires_at TIMESTAMPTZ,
--    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
--
--    CONSTRAINT check_remaining_not_exceed_granted CHECK (quantity_remaining <= quantity_granted),
--    CONSTRAINT unique_credit_lot_source UNIQUE (source_ledger_entry_id)
--);
--
--CREATE INDEX IF NOT EXISTS idx_credit_lots_user_type_remaining
--    ON public.user_entitlement_credit_lots(user_id, entitlement_type, expires_at)
--    WHERE quantity_remaining > 0;
--
---- =============================================================================
---- 13. USER ENTITLEMENT CREDIT CONSUMPTIONS TABLE
---- =============================================================================
--
--CREATE TABLE IF NOT EXISTS public.user_entitlement_credit_consumptions (
--    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
--    consumption_ledger_entry_id UUID NOT NULL REFERENCES public.user_entitlement_ledger(id) ON DELETE RESTRICT,
--    credit_lot_id UUID NOT NULL REFERENCES public.user_entitlement_credit_lots(id) ON DELETE RESTRICT,
--    quantity_consumed INTEGER NOT NULL CHECK (quantity_consumed > 0),
--    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
--);
--
--CREATE INDEX IF NOT EXISTS idx_credit_consumptions_lot
--    ON public.user_entitlement_credit_consumptions(credit_lot_id);
--
---- =============================================================================
---- 14. ALTER active_boosts: add new columns for design-doc compatibility
---- =============================================================================
--
--ALTER TABLE public.active_boosts
--    ADD COLUMN IF NOT EXISTS consumption_ledger_entry_id UUID
--        REFERENCES public.user_entitlement_ledger(id) ON DELETE SET NULL,
--    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
--    ADD COLUMN IF NOT EXISTS ended_at TIMESTAMPTZ,
--    ADD COLUMN IF NOT EXISTS end_reason VARCHAR(30);
--
--ALTER TABLE public.active_boosts
--    DROP CONSTRAINT IF EXISTS active_boosts_status_check;
--
--ALTER TABLE public.active_boosts
--    ADD CONSTRAINT active_boosts_status_check CHECK (
--        status IN ('ACTIVE', 'EXPIRED', 'CANCELLED', 'REVOKED')
--    );
--
---- =============================================================================
---- 15. USER QUOTA USAGE TABLE (new design-doc table, coexists with user_daily_limits)
---- =============================================================================
--
--CREATE TABLE IF NOT EXISTS public.user_quota_usage (
--    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
--    plan_id UUID NOT NULL REFERENCES public.subscription_plans(id) ON DELETE RESTRICT,
--    resource_type VARCHAR(30) NOT NULL CHECK (
--        resource_type IN ('LIKES', 'SUPERLIKES', 'REWINDS', 'BOOSTS')
--    ),
--    period_start TIMESTAMPTZ NOT NULL,
--    period_end TIMESTAMPTZ NOT NULL,
--    used_count INTEGER NOT NULL DEFAULT 0 CHECK (used_count >= 0),
--    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
--
--    PRIMARY KEY (user_id, resource_type, period_start)
--);
--
---- =============================================================================
---- 16. EXPAND subscription_plan_limits to support new resource/period types
---- =============================================================================
--
--ALTER TABLE public.subscription_plan_limits
--    DROP CONSTRAINT IF EXISTS subscription_plan_limits_limit_type_check;
--
--ALTER TABLE public.subscription_plan_limits
--    ADD CONSTRAINT subscription_plan_limits_limit_type_check CHECK (
--        limit_type IN (
--            'DAILY_LIKES', 'DAILY_SUPERLIKES', 'DAILY_REWINDS',
--            'LIKES', 'SUPERLIKES', 'REWINDS', 'BOOSTS'
--        )
--    );
--
---- Add period_type column (nullable for backwards compat with existing rows)
--ALTER TABLE public.subscription_plan_limits
--    ADD COLUMN IF NOT EXISTS period_type VARCHAR(30) DEFAULT 'DAILY';
--
---- =============================================================================
---- 17. SEED DATA
---- =============================================================================
--
---- Ensure FREE and PREMIUM plans exist with plan_kind
---- (subscription_plans already exists with price/billing data; new code ignores those)
--INSERT INTO public.subscription_plans (id, name, plan_code, country_code, plan_kind, price_minor_units, currency, billing_interval, features, is_active)
--VALUES
--    ('a0000000-0000-0000-0000-000000000001', 'Free', 'FREE', 'GLOBAL', 'FREE', 0, 'USD', 'NONE',
--     '{"seeWhoLikedYou": false, "advancedFilters": false, "incognitoMode": false}'::jsonb, TRUE),
--    ('a0000000-0000-0000-0000-000000000002', 'Premium', 'PREMIUM', 'GLOBAL', 'PAID', 0, 'USD', 'MONTHLY',
--     '{"seeWhoLikedYou": true, "advancedFilters": true, "incognitoMode": false}'::jsonb, TRUE)
--ON CONFLICT (plan_code, country_code) DO UPDATE
--    SET features = EXCLUDED.features,
--        name = EXCLUDED.name,
--        updated_at = CURRENT_TIMESTAMP;
--
---- Subscription products
--INSERT INTO public.subscription_products (id, plan_id, product_code, billing_interval_unit, billing_interval_count, auto_renew_supported, is_active)
--VALUES
--    ('b0000000-0000-0000-0000-000000000001',
--     (SELECT id FROM subscription_plans WHERE plan_code = 'PREMIUM' AND country_code = 'GLOBAL' LIMIT 1),
--     'PREMIUM_MONTHLY', 'MONTH', 1, TRUE, TRUE),
--    ('b0000000-0000-0000-0000-000000000002',
--     (SELECT id FROM subscription_plans WHERE plan_code = 'PREMIUM' AND country_code = 'GLOBAL' LIMIT 1),
--     'PREMIUM_3_MONTH', 'MONTH', 3, TRUE, TRUE),
--    ('b0000000-0000-0000-0000-000000000003',
--     (SELECT id FROM subscription_plans WHERE plan_code = 'PREMIUM' AND country_code = 'GLOBAL' LIMIT 1),
--     'PREMIUM_6_MONTH', 'MONTH', 6, TRUE, TRUE)
--ON CONFLICT (product_code) DO NOTHING;
--
---- Consumable products
--INSERT INTO public.consumable_products (id, product_code, name, entitlement_type, quantity_granted, expires_after_days, is_active)
--VALUES
--    ('c0000000-0000-0000-0000-000000000001', 'BOOST_PACK_1', '1 Boost', 'BOOST_CREDIT', 1, NULL, TRUE),
--    ('c0000000-0000-0000-0000-000000000002', 'BOOST_PACK_5', '5 Boosts', 'BOOST_CREDIT', 5, NULL, TRUE),
--    ('c0000000-0000-0000-0000-000000000003', 'SUPERLIKE_PACK_5', '5 Super Likes', 'SUPERLIKE_CREDIT', 5, NULL, TRUE),
--    ('c0000000-0000-0000-0000-000000000004', 'SUPERLIKE_PACK_20', '20 Super Likes', 'SUPERLIKE_CREDIT', 20, NULL, TRUE),
--    ('c0000000-0000-0000-0000-000000000005', 'REWIND_PACK_5', '5 Rewinds', 'REWIND_CREDIT', 5, NULL, TRUE)
--ON CONFLICT (product_code) DO NOTHING;
--
---- Plan limits for new resource types (coexist with existing DAILY_* rows)
--INSERT INTO public.subscription_plan_limits (plan_id, limit_type, limit_value, period_type)
--SELECT sp.id, lt.limit_type, lt.limit_value, lt.period_type
--FROM (SELECT id FROM subscription_plans WHERE plan_code = 'FREE' AND country_code = 'GLOBAL' LIMIT 1) sp
--CROSS JOIN (VALUES
--    ('LIKES', 50, 'DAILY'),
--    ('SUPERLIKES', 1, 'DAILY'),
--    ('REWINDS', 1, 'DAILY'),
--    ('BOOSTS', 0, 'SUBSCRIPTION_MONTH')
--) AS lt(limit_type, limit_value, period_type)
--ON CONFLICT (plan_id, limit_type) DO NOTHING;
--
--INSERT INTO public.subscription_plan_limits (plan_id, limit_type, limit_value, period_type)
--SELECT sp.id, lt.limit_type, lt.limit_value, lt.period_type
--FROM (SELECT id FROM subscription_plans WHERE plan_code = 'PREMIUM' AND country_code = 'GLOBAL' LIMIT 1) sp
--CROSS JOIN (VALUES
--    ('LIKES', 150, 'DAILY'),
--    ('SUPERLIKES', 5, 'DAILY'),
--    ('REWINDS', 10, 'DAILY'),
--    ('BOOSTS', 1, 'SUBSCRIPTION_MONTH')
--) AS lt(limit_type, limit_value, period_type)
--ON CONFLICT (plan_id, limit_type) DO NOTHING;
--
---- Sample payment offers for Ethiopia/Android Chapa
--INSERT INTO public.payment_offers (id, subscription_product_id, consumable_product_id, country_code, platform, payment_channel, payment_method, currency, price_minor_units, auto_renew, is_active)
--VALUES
--    ('d0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000001', NULL, 'ET', 'ANDROID', 'CHAPA', 'TELEBIRR', 'ETB', 14900, FALSE, TRUE),
--    ('d0000000-0000-0000-0000-000000000002', 'b0000000-0000-0000-0000-000000000002', NULL, 'ET', 'ANDROID', 'CHAPA', 'TELEBIRR', 'ETB', 39900, FALSE, TRUE),
--    ('d0000000-0000-0000-0000-000000000003', 'b0000000-0000-0000-0000-000000000003', NULL, 'ET', 'ANDROID', 'CHAPA', 'TELEBIRR', 'ETB', 69900, FALSE, TRUE),
--    ('d0000000-0000-0000-0000-000000000004', 'b0000000-0000-0000-0000-000000000001', NULL, 'ET', 'ANDROID', 'MANUAL_TRANSFER', 'BANK_TRANSFER', 'ETB', 14900, FALSE, TRUE),
--    ('d0000000-0000-0000-0000-000000000005', NULL, 'c0000000-0000-0000-0000-000000000002', 'ET', 'ANDROID', 'CHAPA', 'TELEBIRR', 'ETB', 9900, FALSE, TRUE)
--ON CONFLICT DO NOTHING;
--
---- Sample payment offers for iOS/GLOBAL (RevenueCat Apple)
--INSERT INTO public.payment_offers (id, subscription_product_id, consumable_product_id, country_code, platform, payment_channel, payment_method, currency, price_minor_units, external_product_id, auto_renew, is_active)
--VALUES
--    ('d0000000-0000-0000-0000-000000000010', 'b0000000-0000-0000-0000-000000000001', NULL, 'GLOBAL', 'IOS', 'REVENUECAT_APPLE', 'APPLE_IAP', 'USD', 799, 'qaliye_premium_monthly', TRUE, TRUE),
--    ('d0000000-0000-0000-0000-000000000011', 'b0000000-0000-0000-0000-000000000002', NULL, 'GLOBAL', 'IOS', 'REVENUECAT_APPLE', 'APPLE_IAP', 'USD', 2199, 'qaliye_premium_3month', TRUE, TRUE),
--    ('d0000000-0000-0000-0000-000000000012', 'b0000000-0000-0000-0000-000000000003', NULL, 'GLOBAL', 'IOS', 'REVENUECAT_APPLE', 'APPLE_IAP', 'USD', 3999, 'qaliye_premium_6month', TRUE, TRUE)
--ON CONFLICT DO NOTHING;
--
---- Sample payment offers for Android non-ET (RevenueCat Google)
--INSERT INTO public.payment_offers (id, subscription_product_id, consumable_product_id, country_code, platform, payment_channel, payment_method, currency, price_minor_units, external_product_id, auto_renew, is_active)
--VALUES
--    ('d0000000-0000-0000-0000-000000000020', 'b0000000-0000-0000-0000-000000000001', NULL, 'GLOBAL', 'ANDROID', 'REVENUECAT_GOOGLE', 'GOOGLE_PLAY_BILLING', 'USD', 799, 'qaliye_premium_monthly', TRUE, TRUE),
--    ('d0000000-0000-0000-0000-000000000021', 'b0000000-0000-0000-0000-000000000002', NULL, 'GLOBAL', 'ANDROID', 'REVENUECAT_GOOGLE', 'GOOGLE_PLAY_BILLING', 'USD', 2199, 'qaliye_premium_3month', TRUE, TRUE),
--    ('d0000000-0000-0000-0000-000000000022', 'b0000000-0000-0000-0000-000000000003', NULL, 'GLOBAL', 'ANDROID', 'REVENUECAT_GOOGLE', 'GOOGLE_PLAY_BILLING', 'USD', 3999, 'qaliye_premium_6month', TRUE, TRUE)
--ON CONFLICT DO NOTHING;
--
---- =============================================================================
---- 18. TRIGGERS FOR NEW TABLES
---- =============================================================================
--
--CREATE TRIGGER update_subscription_products_updated_at
--BEFORE UPDATE ON public.subscription_products
--FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();
--
--CREATE TRIGGER update_consumable_products_updated_at
--BEFORE UPDATE ON public.consumable_products
--FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();
--
--CREATE TRIGGER update_payment_offers_updated_at
--BEFORE UPDATE ON public.payment_offers
--FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();
--
--CREATE TRIGGER update_billing_customers_updated_at
--BEFORE UPDATE ON public.billing_customers
--FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();
--
--CREATE TRIGGER update_payment_orders_updated_at
--BEFORE UPDATE ON public.payment_orders
--FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();
--
--CREATE TRIGGER update_payment_verification_attempts_updated_at
--BEFORE UPDATE ON public.payment_verification_attempts
--FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();




-- =============================================================================
-- V20: Payment, Subscription, Entitlement, Quota, and Boost System
--
-- Implements the full billing architecture from payment-entitlement-design-new.md.
-- Existing tables (subscription_plans, user_subscriptions, transactions,
-- payment_events, user_entitlement_ledger, active_boosts, user_daily_limits)
-- are preserved. New tables are added alongside them. Existing tables are
-- altered only with additive, non-destructive changes.
-- =============================================================================

-- Required for EXCLUDE USING GIST on UUID + tstzrange
CREATE EXTENSION IF NOT EXISTS "btree_gist";

-- =============================================================================
-- 1. NEW SUBSCRIPTION PRODUCTS TABLE
-- Separates billing periods from plans. subscription_plans is preserved as-is;
-- new code uses subscription_products + payment_offers for pricing/durations.
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.subscription_products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id UUID NOT NULL REFERENCES public.subscription_plans(id) ON DELETE RESTRICT,
    product_code VARCHAR(100) NOT NULL,
    billing_interval_unit VARCHAR(20) NOT NULL CHECK (
        billing_interval_unit IN ('DAY', 'WEEK', 'MONTH', 'YEAR')
    ),
    billing_interval_count SMALLINT NOT NULL CHECK (billing_interval_count > 0),
    auto_renew_supported BOOLEAN NOT NULL DEFAULT TRUE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_subscription_product_code UNIQUE (product_code)
);

-- =============================================================================
-- 2. CONSUMABLE PRODUCTS TABLE
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.consumable_products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_code VARCHAR(100) NOT NULL,
    name VARCHAR(100) NOT NULL,
    entitlement_type VARCHAR(30) NOT NULL CHECK (
        entitlement_type IN ('BOOST_CREDIT', 'SUPERLIKE_CREDIT', 'REWIND_CREDIT')
    ),
    quantity_granted INTEGER NOT NULL CHECK (quantity_granted > 0),
    expires_after_days INTEGER CHECK (expires_after_days IS NULL OR expires_after_days > 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_consumable_product_code UNIQUE (product_code)
);

-- =============================================================================
-- 3. PAYMENT OFFERS TABLE
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.payment_offers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_product_id UUID REFERENCES public.subscription_products(id) ON DELETE SET NULL,
    consumable_product_id UUID REFERENCES public.consumable_products(id) ON DELETE SET NULL,
    country_code VARCHAR(10) NOT NULL DEFAULT 'GLOBAL',
    platform VARCHAR(20) NOT NULL CHECK (
        platform IN ('ANDROID', 'IOS', 'WEB')
    ),
    payment_channel VARCHAR(50) NOT NULL CHECK (
        payment_channel IN (
            'REVENUECAT_APPLE', 'REVENUECAT_GOOGLE',
            'CHAPA', 'MANUAL_TRANSFER', 'DIRECT_TELEBIRR'
        )
    ),
    payment_method VARCHAR(50) NOT NULL CHECK (
        payment_method IN (
            'APPLE_IAP', 'GOOGLE_PLAY_BILLING',
            'TELEBIRR', 'CBE_BIRR', 'BANK_TRANSFER', 'CARD'
        )
    ),
    currency VARCHAR(3) NOT NULL,
    price_minor_units INTEGER NOT NULL CHECK (price_minor_units >= 0),
    external_product_id VARCHAR(255),
    external_base_plan_id VARCHAR(255),
    revenuecat_offering_id VARCHAR(100),
    revenuecat_package_id VARCHAR(100),
    auto_renew BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_offer_has_exactly_one_product CHECK (
        (subscription_product_id IS NOT NULL AND consumable_product_id IS NULL)
        OR
        (subscription_product_id IS NULL AND consumable_product_id IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_payment_offers_country_platform_active
    ON public.payment_offers(country_code, platform)
    WHERE is_active = TRUE;

-- =============================================================================
-- 4. BILLING CUSTOMERS TABLE (RevenueCat mapping)
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.billing_customers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
    provider VARCHAR(50) NOT NULL,
    external_customer_id VARCHAR(255) NOT NULL,
    original_external_customer_id VARCHAR(255),
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_billing_customer_provider_external UNIQUE (provider, external_customer_id),
    CONSTRAINT unique_billing_customer_user_provider UNIQUE (user_id, provider)
);

-- =============================================================================
-- 5. PAYMENT ORDERS TABLE
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.payment_orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
    payment_offer_id UUID NOT NULL REFERENCES public.payment_offers(id) ON DELETE RESTRICT,
    order_reference VARCHAR(100) NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'CREATED' CHECK (
        status IN (
            'CREATED', 'AWAITING_PAYMENT', 'RECEIPT_SUBMITTED',
            'VERIFICATION_PENDING', 'MANUAL_REVIEW',
            'VERIFIED', 'REJECTED', 'EXPIRED', 'CANCELLED'
        )
    ),
    expected_amount_minor_units INTEGER NOT NULL CHECK (expected_amount_minor_units > 0),
    expected_currency VARCHAR(3) NOT NULL,
    payment_channel VARCHAR(50) NOT NULL,
    payment_method VARCHAR(50) NOT NULL,
    payment_instruction_snapshot JSONB NOT NULL DEFAULT '{}'::JSONB,
    provider_checkout_url TEXT,
    provider_order_reference VARCHAR(255),
    expires_at TIMESTAMPTZ NOT NULL,
    idempotency_key VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_payment_order_reference UNIQUE (order_reference)
);

CREATE INDEX IF NOT EXISTS idx_payment_orders_user_status
    ON public.payment_orders(user_id, status);

CREATE INDEX IF NOT EXISTS idx_payment_orders_status_created
    ON public.payment_orders(status, created_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS idx_payment_orders_idempotency
    ON public.payment_orders(user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- =============================================================================
-- 6. PAYMENT PROOFS TABLE
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.payment_proofs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_order_id UUID NOT NULL REFERENCES public.payment_orders(id) ON DELETE RESTRICT,
    proof_type VARCHAR(30) NOT NULL CHECK (
        proof_type IN ('TRANSACTION_REFERENCE', 'RECEIPT_UPLOAD')
    ),
    payment_network VARCHAR(50),
    transaction_reference VARCHAR(255),
    receipt_storage_bucket VARCHAR(100),
    receipt_storage_path TEXT,
    submitted_amount_minor_units INTEGER,
    submitted_currency VARCHAR(3),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_payment_proofs_order
    ON public.payment_proofs(payment_order_id);

-- =============================================================================
-- 7. PAYMENT VERIFICATION ATTEMPTS TABLE
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.payment_verification_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_order_id UUID NOT NULL REFERENCES public.payment_orders(id) ON DELETE RESTRICT,
    payment_proof_id UUID REFERENCES public.payment_proofs(id) ON DELETE SET NULL,
    verification_method VARCHAR(50) NOT NULL CHECK (
        verification_method IN ('CHAPA_API', 'VERIFY_ET', 'ADMIN_REVIEW')
    ),
    provider_request_id VARCHAR(255),
    provider_verification_reference VARCHAR(255),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN (
            'PENDING', 'VERIFIED', 'NOT_FOUND',
            'AMOUNT_MISMATCH', 'RECIPIENT_MISMATCH',
            'DUPLICATE_PAYMENT', 'MANUAL_REVIEW', 'REJECTED', 'FAILED'
        )
    ),
    verified_amount_minor_units INTEGER,
    verified_currency VARCHAR(3),
    verified_recipient_reference VARCHAR(255),
    verified_paid_at TIMESTAMPTZ,
    raw_response JSONB NOT NULL DEFAULT '{}'::JSONB,
    verified_by_admin_id UUID REFERENCES public.app_users(id) ON DELETE SET NULL,
    admin_decision_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_payment_verification_order
    ON public.payment_verification_attempts(payment_order_id);

-- Prevent re-use of a verified transfer reference
CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_verified_provider_reference
    ON public.payment_verification_attempts(verification_method, provider_verification_reference)
    WHERE status = 'VERIFIED' AND provider_verification_reference IS NOT NULL;

-- =============================================================================
-- 8. ALTER user_subscriptions: add new columns for design-doc compatibility
-- =============================================================================

ALTER TABLE public.user_subscriptions
    ADD COLUMN IF NOT EXISTS payment_offer_id UUID REFERENCES public.payment_offers(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS provider_subscription_reference VARCHAR(512),
    ADD COLUMN IF NOT EXISTS auto_renew BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS ended_at TIMESTAMPTZ;

-- Add new statuses to user_subscriptions (drop old check and re-add expanded)
ALTER TABLE public.user_subscriptions
    DROP CONSTRAINT IF EXISTS user_subscriptions_status_check;

ALTER TABLE public.user_subscriptions
    ADD CONSTRAINT user_subscriptions_status_check CHECK (
        status IN (
            'ACTIVE', 'PAST_DUE', 'CANCELED', 'UNPAID',
            'PENDING_VERIFICATION', 'GRACE_PERIOD', 'EXPIRED', 'REVOKED'
        )
    );

-- =============================================================================
-- 9. ALTER transactions: add new columns for design-doc compatibility
-- =============================================================================

ALTER TABLE public.transactions
    ADD COLUMN IF NOT EXISTS payment_order_id UUID REFERENCES public.payment_orders(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS payment_offer_id UUID REFERENCES public.payment_offers(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS related_transaction_id UUID REFERENCES public.transactions(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS transaction_type VARCHAR(30) DEFAULT 'PURCHASE',
    ADD COLUMN IF NOT EXISTS verification_provider VARCHAR(50),
    ADD COLUMN IF NOT EXISTS country_code VARCHAR(10),
    ADD COLUMN IF NOT EXISTS tax_amount_minor_units INTEGER,
    ADD COLUMN IF NOT EXISTS provider_fee_minor_units INTEGER,
    ADD COLUMN IF NOT EXISTS merchant_net_amount_minor_units INTEGER;

-- Expand transaction status constraint
ALTER TABLE public.transactions
    DROP CONSTRAINT IF EXISTS transactions_status_check;

ALTER TABLE public.transactions
    ADD CONSTRAINT transactions_status_check CHECK (
        status IN (
            'PENDING', 'COMPLETED', 'FAILED', 'MANUAL_REVIEW',
            'REFUNDED', 'PARTIALLY_REFUNDED', 'REVERSED'
        )
    );

-- Expand payment_purpose constraint
ALTER TABLE public.transactions
    DROP CONSTRAINT IF EXISTS transactions_payment_purpose_check;

ALTER TABLE public.transactions
    ADD CONSTRAINT transactions_payment_purpose_check CHECK (
        payment_purpose IN ('SUBSCRIPTION', 'CONSUMABLE_PACK', 'PROFILE_BOOST', 'CONSUMABLE')
    );

-- Expand provider constraint
ALTER TABLE public.transactions
    DROP CONSTRAINT IF EXISTS transactions_provider_check;

ALTER TABLE public.transactions
    ADD CONSTRAINT transactions_provider_check CHECK (
        provider IN (
            'STRIPE', 'APPLE_APP_STORE', 'GOOGLE_PLAY',
            'TELEBIRR', 'CBE_BIRR', 'CHAPA', 'BANK_TRANSFER',
            'REVENUECAT', 'ADMIN'
        )
    );

-- =============================================================================
-- 10. ALTER payment_events: add new columns
-- =============================================================================

ALTER TABLE public.payment_events
    ADD COLUMN IF NOT EXISTS transaction_id UUID REFERENCES public.transactions(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS payment_order_id UUID REFERENCES public.payment_orders(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS processing_status VARCHAR(30) NOT NULL DEFAULT 'PROCESSED',
    ADD COLUMN IF NOT EXISTS signature_verified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS processed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS processing_error TEXT;

-- Expand provider constraint on payment_events
ALTER TABLE public.payment_events
    DROP CONSTRAINT IF EXISTS payment_events_provider_check;

ALTER TABLE public.payment_events
    ADD CONSTRAINT payment_events_provider_check CHECK (
        provider IN (
            'STRIPE', 'REVENUECAT', 'APPLE_APP_STORE', 'GOOGLE_PLAY',
            'TELEBIRR', 'CBE_BIRR', 'CHAPA', 'BANK_TRANSFER', 'VERIFY_ET'
        )
    );

-- =============================================================================
-- 11. ALTER user_entitlement_ledger: add new columns
-- =============================================================================

ALTER TABLE public.user_entitlement_ledger
    ADD COLUMN IF NOT EXISTS subscription_id UUID REFERENCES public.user_subscriptions(id) ON DELETE SET NULL;

-- Expand reason constraint
ALTER TABLE public.user_entitlement_ledger
    DROP CONSTRAINT IF EXISTS user_entitlement_ledger_reason_check;

ALTER TABLE public.user_entitlement_ledger
    ADD CONSTRAINT user_entitlement_ledger_reason_check CHECK (
        reason IN (
            'PURCHASE', 'SUBSCRIPTION_ALLOWANCE', 'CONSUMPTION',
            'REFUND', 'EXPIRY', 'ADMIN_GRANT', 'ADJUSTMENT', 'REVERSAL'
        )
    );

-- Change idempotency_key from UUID to VARCHAR for flexible keys
ALTER TABLE public.user_entitlement_ledger
    ALTER COLUMN idempotency_key TYPE VARCHAR(255) USING idempotency_key::VARCHAR;

-- =============================================================================
-- 12. USER ENTITLEMENT CREDIT LOTS TABLE
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.user_entitlement_credit_lots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
    entitlement_type VARCHAR(30) NOT NULL CHECK (
        entitlement_type IN ('BOOST_CREDIT', 'SUPERLIKE_CREDIT', 'REWIND_CREDIT')
    ),
    source_ledger_entry_id UUID NOT NULL REFERENCES public.user_entitlement_ledger(id) ON DELETE RESTRICT,
    quantity_granted INTEGER NOT NULL CHECK (quantity_granted > 0),
    quantity_remaining INTEGER NOT NULL CHECK (quantity_remaining >= 0),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_remaining_not_exceed_granted CHECK (quantity_remaining <= quantity_granted),
    CONSTRAINT unique_credit_lot_source UNIQUE (source_ledger_entry_id)
);

CREATE INDEX IF NOT EXISTS idx_credit_lots_user_type_remaining
    ON public.user_entitlement_credit_lots(user_id, entitlement_type, expires_at)
    WHERE quantity_remaining > 0;

-- =============================================================================
-- 13. USER ENTITLEMENT CREDIT CONSUMPTIONS TABLE
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.user_entitlement_credit_consumptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    consumption_ledger_entry_id UUID NOT NULL REFERENCES public.user_entitlement_ledger(id) ON DELETE RESTRICT,
    credit_lot_id UUID NOT NULL REFERENCES public.user_entitlement_credit_lots(id) ON DELETE RESTRICT,
    quantity_consumed INTEGER NOT NULL CHECK (quantity_consumed > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_credit_consumptions_lot
    ON public.user_entitlement_credit_consumptions(credit_lot_id);

-- =============================================================================
-- 14. ALTER active_boosts: add new columns for design-doc compatibility
-- =============================================================================

ALTER TABLE public.active_boosts
    ADD COLUMN IF NOT EXISTS consumption_ledger_entry_id UUID
        REFERENCES public.user_entitlement_ledger(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS ended_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS end_reason VARCHAR(30);

ALTER TABLE public.active_boosts
    DROP CONSTRAINT IF EXISTS active_boosts_status_check;

ALTER TABLE public.active_boosts
    ADD CONSTRAINT active_boosts_status_check CHECK (
        status IN ('ACTIVE', 'EXPIRED', 'CANCELLED', 'REVOKED')
    );

-- =============================================================================
-- 15. USER QUOTA USAGE TABLE (new design-doc table, coexists with user_daily_limits)
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.user_quota_usage (
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,
    plan_id UUID NOT NULL REFERENCES public.subscription_plans(id) ON DELETE RESTRICT,
    resource_type VARCHAR(30) NOT NULL CHECK (
        resource_type IN ('LIKES', 'SUPERLIKES', 'REWINDS', 'BOOSTS')
    ),
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    used_count INTEGER NOT NULL DEFAULT 0 CHECK (used_count >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (user_id, resource_type, period_start)
);

-- =============================================================================
-- 16. EXPAND subscription_plan_limits to support new resource/period types
-- =============================================================================

ALTER TABLE public.subscription_plan_limits
    DROP CONSTRAINT IF EXISTS subscription_plan_limits_limit_type_check;

ALTER TABLE public.subscription_plan_limits
    ADD CONSTRAINT subscription_plan_limits_limit_type_check CHECK (
        limit_type IN (
            'DAILY_LIKES', 'DAILY_SUPERLIKES', 'DAILY_REWINDS',
            'LIKES', 'SUPERLIKES', 'REWINDS', 'BOOSTS'
        )
    );

-- Add period_type column (nullable for backwards compat with existing rows)
ALTER TABLE public.subscription_plan_limits
    ADD COLUMN IF NOT EXISTS period_type VARCHAR(30) DEFAULT 'DAILY';

ALTER TABLE public.subscription_plan_limits
    DROP CONSTRAINT IF EXISTS subscription_plan_limits_period_type_check;

ALTER TABLE public.subscription_plan_limits
    ADD CONSTRAINT subscription_plan_limits_period_type_check CHECK (
        period_type IN (
            'DAILY',
            'SUBSCRIPTION_MONTH',
            'BILLING_CYCLE'
        )
    );

-- =============================================================================
-- 17. SEED DATA
-- =============================================================================

-- Ensure FREE and PREMIUM plans exist with plan_kind
-- (subscription_plans already exists with price/billing data; new code ignores those)
INSERT INTO public.subscription_plans (id, name, plan_code, country_code, plan_kind, price_minor_units, currency, billing_interval, features, is_active)
VALUES
    ('a0000000-0000-0000-0000-000000000001', 'Free', 'FREE', 'GLOBAL', 'FREE', 0, 'USD', 'NONE',
     '{"seeWhoLikedYou": false, "advancedFilters": false, "incognitoMode": false}'::jsonb, TRUE),
    ('a0000000-0000-0000-0000-000000000002', 'Premium', 'PREMIUM', 'GLOBAL', 'PAID', 0, 'USD', 'MONTHLY',
     '{"seeWhoLikedYou": true, "advancedFilters": true, "incognitoMode": false}'::jsonb, TRUE)
ON CONFLICT (plan_code, country_code) DO UPDATE
    SET features = EXCLUDED.features,
        name = EXCLUDED.name,
        updated_at = CURRENT_TIMESTAMP;

-- Subscription products
INSERT INTO public.subscription_products (id, plan_id, product_code, billing_interval_unit, billing_interval_count, auto_renew_supported, is_active)
VALUES
    ('b0000000-0000-0000-0000-000000000001',
     (SELECT id FROM subscription_plans WHERE plan_code = 'PREMIUM' AND country_code = 'GLOBAL' LIMIT 1),
     'PREMIUM_MONTHLY', 'MONTH', 1, TRUE, TRUE),
    ('b0000000-0000-0000-0000-000000000002',
     (SELECT id FROM subscription_plans WHERE plan_code = 'PREMIUM' AND country_code = 'GLOBAL' LIMIT 1),
     'PREMIUM_3_MONTH', 'MONTH', 3, TRUE, TRUE),
    ('b0000000-0000-0000-0000-000000000003',
     (SELECT id FROM subscription_plans WHERE plan_code = 'PREMIUM' AND country_code = 'GLOBAL' LIMIT 1),
     'PREMIUM_6_MONTH', 'MONTH', 6, TRUE, TRUE)
ON CONFLICT (product_code) DO NOTHING;

-- Consumable products
-- Rename the earlier rewind seed when this script is re-run against a development database.
UPDATE public.consumable_products
SET product_code = 'REWIND_PACK_10',
    name = '10 Rewinds',
    quantity_granted = 10,
    updated_at = CURRENT_TIMESTAMP
WHERE product_code = 'REWIND_PACK_5'
  AND NOT EXISTS (
      SELECT 1
      FROM public.consumable_products
      WHERE product_code = 'REWIND_PACK_10'
  );

INSERT INTO public.consumable_products (id, product_code, name, entitlement_type, quantity_granted, expires_after_days, is_active)
VALUES
    ('c0000000-0000-0000-0000-000000000001', 'BOOST_PACK_1', '1 Boost', 'BOOST_CREDIT', 1, NULL, TRUE),
    ('c0000000-0000-0000-0000-000000000002', 'BOOST_PACK_5', '5 Boosts', 'BOOST_CREDIT', 5, NULL, TRUE),
    ('c0000000-0000-0000-0000-000000000003', 'SUPERLIKE_PACK_5', '5 Super Likes', 'SUPERLIKE_CREDIT', 5, NULL, TRUE),
    ('c0000000-0000-0000-0000-000000000004', 'SUPERLIKE_PACK_20', '20 Super Likes', 'SUPERLIKE_CREDIT', 20, NULL, TRUE),
    ('c0000000-0000-0000-0000-000000000005', 'REWIND_PACK_10', '10 Rewinds', 'REWIND_CREDIT', 10, NULL, TRUE)
ON CONFLICT (product_code) DO UPDATE
SET name = EXCLUDED.name,
    entitlement_type = EXCLUDED.entitlement_type,
    quantity_granted = EXCLUDED.quantity_granted,
    expires_after_days = EXCLUDED.expires_after_days,
    is_active = EXCLUDED.is_active,
    updated_at = CURRENT_TIMESTAMP;

-- Plan limits for new resource types (coexist with existing DAILY_* rows)
INSERT INTO public.subscription_plan_limits (plan_id, limit_type, limit_value, period_type)
SELECT sp.id, lt.limit_type, lt.limit_value, lt.period_type
FROM (SELECT id FROM subscription_plans WHERE plan_code = 'FREE' AND country_code = 'GLOBAL' LIMIT 1) sp
CROSS JOIN (VALUES
    ('LIKES', 50, 'DAILY'),
    ('SUPERLIKES', 1, 'DAILY'),
    ('REWINDS', 1, 'DAILY'),
    ('BOOSTS', 0, 'SUBSCRIPTION_MONTH')
) AS lt(limit_type, limit_value, period_type)
ON CONFLICT (plan_id, limit_type) DO NOTHING;

INSERT INTO public.subscription_plan_limits (plan_id, limit_type, limit_value, period_type)
SELECT sp.id, lt.limit_type, lt.limit_value, lt.period_type
FROM (SELECT id FROM subscription_plans WHERE plan_code = 'PREMIUM' AND country_code = 'GLOBAL' LIMIT 1) sp
CROSS JOIN (VALUES
    ('LIKES', 150, 'DAILY'),
    ('SUPERLIKES', 5, 'DAILY'),
    ('REWINDS', 10, 'DAILY'),
    ('BOOSTS', 1, 'SUBSCRIPTION_MONTH')
) AS lt(limit_type, limit_value, period_type)
ON CONFLICT (plan_id, limit_type) DO NOTHING;

-- Ethiopia / Android local payment offers. Prices for the added consumable packs are QA seed values.
INSERT INTO public.payment_offers (
    id, subscription_product_id, consumable_product_id, country_code, platform,
    payment_channel, payment_method, currency, price_minor_units, auto_renew, is_active
)
VALUES
    ('d0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000001', NULL, 'ET', 'ANDROID', 'CHAPA', 'TELEBIRR', 'ETB', 14900, FALSE, TRUE),
    ('d0000000-0000-0000-0000-000000000002', 'b0000000-0000-0000-0000-000000000002', NULL, 'ET', 'ANDROID', 'CHAPA', 'TELEBIRR', 'ETB', 39900, FALSE, TRUE),
    ('d0000000-0000-0000-0000-000000000003', 'b0000000-0000-0000-0000-000000000003', NULL, 'ET', 'ANDROID', 'CHAPA', 'TELEBIRR', 'ETB', 69900, FALSE, TRUE),
    ('d0000000-0000-0000-0000-000000000004', 'b0000000-0000-0000-0000-000000000001', NULL, 'ET', 'ANDROID', 'MANUAL_TRANSFER', 'BANK_TRANSFER', 'ETB', 14900, FALSE, TRUE),
    ('d0000000-0000-0000-0000-000000000005', NULL, 'c0000000-0000-0000-0000-000000000002', 'ET', 'ANDROID', 'CHAPA', 'TELEBIRR', 'ETB', 9900, FALSE, TRUE),
    ('d0000000-0000-0000-0000-000000000006', NULL, 'c0000000-0000-0000-0000-000000000001', 'ET', 'ANDROID', 'CHAPA', 'TELEBIRR', 'ETB', 2900, FALSE, TRUE),
    ('d0000000-0000-0000-0000-000000000007', NULL, 'c0000000-0000-0000-0000-000000000003', 'ET', 'ANDROID', 'CHAPA', 'TELEBIRR', 'ETB', 4900, FALSE, TRUE),
    ('d0000000-0000-0000-0000-000000000008', NULL, 'c0000000-0000-0000-0000-000000000004', 'ET', 'ANDROID', 'CHAPA', 'TELEBIRR', 'ETB', 14900, FALSE, TRUE),
    ('d0000000-0000-0000-0000-000000000009', NULL, 'c0000000-0000-0000-0000-000000000005', 'ET', 'ANDROID', 'CHAPA', 'TELEBIRR', 'ETB', 4900, FALSE, TRUE)
ON CONFLICT (id) DO UPDATE
SET subscription_product_id = EXCLUDED.subscription_product_id,
    consumable_product_id = EXCLUDED.consumable_product_id,
    country_code = EXCLUDED.country_code,
    platform = EXCLUDED.platform,
    payment_channel = EXCLUDED.payment_channel,
    payment_method = EXCLUDED.payment_method,
    currency = EXCLUDED.currency,
    price_minor_units = EXCLUDED.price_minor_units,
    external_product_id = NULL,
    external_base_plan_id = NULL,
    revenuecat_offering_id = NULL,
    revenuecat_package_id = NULL,
    auto_renew = EXCLUDED.auto_renew,
    is_active = EXCLUDED.is_active,
    updated_at = CURRENT_TIMESTAMP;

-- QA/Test Store offers for iOS. Premium products use RevenueCat standard package identifiers.
INSERT INTO public.payment_offers (
    id, subscription_product_id, consumable_product_id, country_code, platform,
    payment_channel, payment_method, currency, price_minor_units,
    external_product_id, revenuecat_offering_id, revenuecat_package_id,
    auto_renew, is_active
)
VALUES
    ('d0000000-0000-0000-0000-000000000010', 'b0000000-0000-0000-0000-000000000001', NULL, 'GLOBAL', 'IOS', 'REVENUECAT_APPLE', 'APPLE_IAP', 'USD', 799, 'qaliye_premium_monthly_test', 'qaliye_test', '$rc_monthly', TRUE, TRUE),
    ('d0000000-0000-0000-0000-000000000011', 'b0000000-0000-0000-0000-000000000002', NULL, 'GLOBAL', 'IOS', 'REVENUECAT_APPLE', 'APPLE_IAP', 'USD', 2199, 'qaliye_premium_3_month_test', 'qaliye_test', '$rc_three_month', TRUE, TRUE),
    ('d0000000-0000-0000-0000-000000000012', 'b0000000-0000-0000-0000-000000000003', NULL, 'GLOBAL', 'IOS', 'REVENUECAT_APPLE', 'APPLE_IAP', 'USD', 3999, 'qaliye_premium_6_month_test', 'qaliye_test', '$rc_six_month', TRUE, TRUE),
    ('d0000000-0000-0000-0000-000000000013', NULL, 'c0000000-0000-0000-0000-000000000001', 'GLOBAL', 'IOS', 'REVENUECAT_APPLE', 'APPLE_IAP', 'USD', 199, 'qaliye_boost_pack_1_test', 'qaliye_test', 'boost_1', FALSE, TRUE),
    ('d0000000-0000-0000-0000-000000000014', NULL, 'c0000000-0000-0000-0000-000000000002', 'GLOBAL', 'IOS', 'REVENUECAT_APPLE', 'APPLE_IAP', 'USD', 799, 'qaliye_boost_pack_5_test', 'qaliye_test', 'boost_5', FALSE, TRUE),
    ('d0000000-0000-0000-0000-000000000015', NULL, 'c0000000-0000-0000-0000-000000000003', 'GLOBAL', 'IOS', 'REVENUECAT_APPLE', 'APPLE_IAP', 'USD', 199, 'qaliye_superlike_pack_5_test', 'qaliye_test', 'superlike_5', FALSE, TRUE),
    ('d0000000-0000-0000-0000-000000000016', NULL, 'c0000000-0000-0000-0000-000000000004', 'GLOBAL', 'IOS', 'REVENUECAT_APPLE', 'APPLE_IAP', 'USD', 599, 'qaliye_superlike_pack_20_test', 'qaliye_test', 'superlike_20', FALSE, TRUE),
    ('d0000000-0000-0000-0000-000000000017', NULL, 'c0000000-0000-0000-0000-000000000005', 'GLOBAL', 'IOS', 'REVENUECAT_APPLE', 'APPLE_IAP', 'USD', 199, 'qaliye_rewind_pack_10_test', 'qaliye_test', 'rewind_10', FALSE, TRUE)
ON CONFLICT (id) DO UPDATE
SET subscription_product_id = EXCLUDED.subscription_product_id,
    consumable_product_id = EXCLUDED.consumable_product_id,
    country_code = EXCLUDED.country_code,
    platform = EXCLUDED.platform,
    payment_channel = EXCLUDED.payment_channel,
    payment_method = EXCLUDED.payment_method,
    currency = EXCLUDED.currency,
    price_minor_units = EXCLUDED.price_minor_units,
    external_product_id = EXCLUDED.external_product_id,
    external_base_plan_id = NULL,
    revenuecat_offering_id = EXCLUDED.revenuecat_offering_id,
    revenuecat_package_id = EXCLUDED.revenuecat_package_id,
    auto_renew = EXCLUDED.auto_renew,
    is_active = EXCLUDED.is_active,
    updated_at = CURRENT_TIMESTAMP;

-- QA/Test Store offers for Android outside Ethiopia. Premium products use RevenueCat standard package identifiers.
INSERT INTO public.payment_offers (
    id, subscription_product_id, consumable_product_id, country_code, platform,
    payment_channel, payment_method, currency, price_minor_units,
    external_product_id, revenuecat_offering_id, revenuecat_package_id,
    auto_renew, is_active
)
VALUES
    ('d0000000-0000-0000-0000-000000000020', 'b0000000-0000-0000-0000-000000000001', NULL, 'GLOBAL', 'ANDROID', 'REVENUECAT_GOOGLE', 'GOOGLE_PLAY_BILLING', 'USD', 799, 'qaliye_premium_monthly_test', 'qaliye_test', '$rc_monthly', TRUE, TRUE),
    ('d0000000-0000-0000-0000-000000000021', 'b0000000-0000-0000-0000-000000000002', NULL, 'GLOBAL', 'ANDROID', 'REVENUECAT_GOOGLE', 'GOOGLE_PLAY_BILLING', 'USD', 2199, 'qaliye_premium_3_month_test', 'qaliye_test', '$rc_three_month', TRUE, TRUE),
    ('d0000000-0000-0000-0000-000000000022', 'b0000000-0000-0000-0000-000000000003', NULL, 'GLOBAL', 'ANDROID', 'REVENUECAT_GOOGLE', 'GOOGLE_PLAY_BILLING', 'USD', 3999, 'qaliye_premium_6_month_test', 'qaliye_test', '$rc_six_month', TRUE, TRUE),
    ('d0000000-0000-0000-0000-000000000023', NULL, 'c0000000-0000-0000-0000-000000000001', 'GLOBAL', 'ANDROID', 'REVENUECAT_GOOGLE', 'GOOGLE_PLAY_BILLING', 'USD', 199, 'qaliye_boost_pack_1_test', 'qaliye_test', 'boost_1', FALSE, TRUE),
    ('d0000000-0000-0000-0000-000000000024', NULL, 'c0000000-0000-0000-0000-000000000002', 'GLOBAL', 'ANDROID', 'REVENUECAT_GOOGLE', 'GOOGLE_PLAY_BILLING', 'USD', 799, 'qaliye_boost_pack_5_test', 'qaliye_test', 'boost_5', FALSE, TRUE),
    ('d0000000-0000-0000-0000-000000000025', NULL, 'c0000000-0000-0000-0000-000000000003', 'GLOBAL', 'ANDROID', 'REVENUECAT_GOOGLE', 'GOOGLE_PLAY_BILLING', 'USD', 199, 'qaliye_superlike_pack_5_test', 'qaliye_test', 'superlike_5', FALSE, TRUE),
    ('d0000000-0000-0000-0000-000000000026', NULL, 'c0000000-0000-0000-0000-000000000004', 'GLOBAL', 'ANDROID', 'REVENUECAT_GOOGLE', 'GOOGLE_PLAY_BILLING', 'USD', 599, 'qaliye_superlike_pack_20_test', 'qaliye_test', 'superlike_20', FALSE, TRUE),
    ('d0000000-0000-0000-0000-000000000027', NULL, 'c0000000-0000-0000-0000-000000000005', 'GLOBAL', 'ANDROID', 'REVENUECAT_GOOGLE', 'GOOGLE_PLAY_BILLING', 'USD', 199, 'qaliye_rewind_pack_10_test', 'qaliye_test', 'rewind_10', FALSE, TRUE)
ON CONFLICT (id) DO UPDATE
SET subscription_product_id = EXCLUDED.subscription_product_id,
    consumable_product_id = EXCLUDED.consumable_product_id,
    country_code = EXCLUDED.country_code,
    platform = EXCLUDED.platform,
    payment_channel = EXCLUDED.payment_channel,
    payment_method = EXCLUDED.payment_method,
    currency = EXCLUDED.currency,
    price_minor_units = EXCLUDED.price_minor_units,
    external_product_id = EXCLUDED.external_product_id,
    external_base_plan_id = NULL,
    revenuecat_offering_id = EXCLUDED.revenuecat_offering_id,
    revenuecat_package_id = EXCLUDED.revenuecat_package_id,
    auto_renew = EXCLUDED.auto_renew,
    is_active = EXCLUDED.is_active,
    updated_at = CURRENT_TIMESTAMP;

-- =============================================================================
-- 18. TRIGGERS FOR NEW TABLES
-- =============================================================================

CREATE TRIGGER update_subscription_products_updated_at
BEFORE UPDATE ON public.subscription_products
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER update_consumable_products_updated_at
BEFORE UPDATE ON public.consumable_products
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER update_payment_offers_updated_at
BEFORE UPDATE ON public.payment_offers
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER update_billing_customers_updated_at
BEFORE UPDATE ON public.billing_customers
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER update_payment_orders_updated_at
BEFORE UPDATE ON public.payment_orders
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER update_payment_verification_attempts_updated_at
BEFORE UPDATE ON public.payment_verification_attempts
FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

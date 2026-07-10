-- =============================================================================
-- V21: payment_methods table + market-based payment routing
--
-- Introduces a normalised payment_methods table so that a payment offer
-- represents only WHAT is sold (product / country / platform / price) while
-- payment_methods represent HOW users in a given market can pay.
--
-- Safe incremental steps:
--   1. billing_country_code on app_users
--   2. payment_methods table + trigger + index
--   3. Seed GLOBAL and ET payment methods (+ legacy inactive back-fill rows)
--   4. payment_method_id (nullable) added to payment_orders
--   5. Remap orders that reference the duplicate ET offer (d...004)
--   6. Deterministic back-fill of payment_method_id from old channel/method cols
--   7. Remove duplicate ET offer
--   8. Make payment_method_id NOT NULL
--   9. Market-matching constraint trigger
--  10. Drop legacy payment_channel / payment_method from payment_orders
--  11. Drop legacy payment_channel / payment_method / external_base_plan_id
--       from payment_offers + remove their CHECK constraints
--  12. Unique partial indexes on payment_offers (one offer per product/market)
--  13. Expand provider constraints to include ARIFPAY
-- =============================================================================

-- =============================================================================
-- 1. billing_country_code on app_users
-- =============================================================================

ALTER TABLE public.app_users
    ADD COLUMN IF NOT EXISTS billing_country_code VARCHAR(10);

-- =============================================================================
-- 2. payment_methods table
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.payment_methods (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    country_code    VARCHAR(10)  NOT NULL DEFAULT 'GLOBAL',

    platform        VARCHAR(20)  NOT NULL CHECK (
                        platform IN ('ANDROID', 'IOS', 'WEB')
                    ),

    method_code     VARCHAR(100) NOT NULL,
    display_name    VARCHAR(150) NOT NULL,

    payment_channel VARCHAR(50)  NOT NULL,
    payment_method  VARCHAR(50)  NOT NULL,

    payment_instructions TEXT,

    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    display_order   SMALLINT     NOT NULL DEFAULT 0,

    metadata        JSONB        NOT NULL DEFAULT '{}'::JSONB,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_payment_method_market
        UNIQUE (country_code, platform, method_code)
);

CREATE TRIGGER update_payment_methods_updated_at
    BEFORE UPDATE ON public.payment_methods
    FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE INDEX IF NOT EXISTS idx_payment_methods_country_platform_active
    ON public.payment_methods(country_code, platform)
    WHERE is_active = TRUE;

-- =============================================================================
-- 3. Seed payment_methods
-- =============================================================================

-- ── GLOBAL IOS ───────────────────────────────────────────────────────────────
INSERT INTO public.payment_methods (
    id, country_code, platform, method_code, display_name,
    payment_channel, payment_method, is_active, display_order
) VALUES (
    'e0000000-0000-0000-0000-000000000001', 'GLOBAL', 'IOS',
    'APPLE_IAP', 'Apple App Store',
    'REVENUECAT_APPLE', 'APPLE_IAP', TRUE, 0
)
ON CONFLICT (country_code, platform, method_code) DO UPDATE
    SET display_name     = EXCLUDED.display_name,
        payment_channel  = EXCLUDED.payment_channel,
        payment_method   = EXCLUDED.payment_method,
        is_active        = EXCLUDED.is_active,
        updated_at       = CURRENT_TIMESTAMP;

-- ── GLOBAL ANDROID ───────────────────────────────────────────────────────────
INSERT INTO public.payment_methods (
    id, country_code, platform, method_code, display_name,
    payment_channel, payment_method, is_active, display_order
) VALUES (
    'e0000000-0000-0000-0000-000000000002', 'GLOBAL', 'ANDROID',
    'GOOGLE_PLAY', 'Google Play',
    'REVENUECAT_GOOGLE', 'GOOGLE_PLAY_BILLING', TRUE, 0
)
ON CONFLICT (country_code, platform, method_code) DO UPDATE
    SET display_name     = EXCLUDED.display_name,
        payment_channel  = EXCLUDED.payment_channel,
        payment_method   = EXCLUDED.payment_method,
        is_active        = EXCLUDED.is_active,
        updated_at       = CURRENT_TIMESTAMP;

-- ── GLOBAL WEB ───────────────────────────────────────────────────────────────
INSERT INTO public.payment_methods (
    id, country_code, platform, method_code, display_name,
    payment_channel, payment_method, is_active, display_order
) VALUES (
    'e0000000-0000-0000-0000-000000000003', 'GLOBAL', 'WEB',
    'STRIPE', 'Card',
    'REVENUECAT_WEB', 'STRIPE', TRUE, 0
)
ON CONFLICT (country_code, platform, method_code) DO UPDATE
    SET display_name     = EXCLUDED.display_name,
        payment_channel  = EXCLUDED.payment_channel,
        payment_method   = EXCLUDED.payment_method,
        is_active        = EXCLUDED.is_active,
        updated_at       = CURRENT_TIMESTAMP;

-- ── ET ANDROID: Chapa (inactive until integration complete) ──────────────────
INSERT INTO public.payment_methods (
    id, country_code, platform, method_code, display_name,
    payment_channel, payment_method, payment_instructions,
    is_active, display_order
) VALUES (
    'e0000000-0000-0000-0000-000000000010', 'ET', 'ANDROID',
    'CHAPA', 'Chapa',
    'CHAPA', 'HOSTED_CHECKOUT', NULL,
    FALSE, 10
)
ON CONFLICT (country_code, platform, method_code) DO UPDATE
    SET display_name    = EXCLUDED.display_name,
        payment_channel = EXCLUDED.payment_channel,
        payment_method  = EXCLUDED.payment_method,
        is_active       = EXCLUDED.is_active,
        updated_at      = CURRENT_TIMESTAMP;

-- ── ET ANDROID: ArifPay (inactive until integration complete) ────────────────
INSERT INTO public.payment_methods (
    id, country_code, platform, method_code, display_name,
    payment_channel, payment_method, payment_instructions,
    is_active, display_order
) VALUES (
    'e0000000-0000-0000-0000-000000000011', 'ET', 'ANDROID',
    'ARIFPAY', 'ArifPay',
    'ARIFPAY', 'HOSTED_CHECKOUT', NULL,
    FALSE, 11
)
ON CONFLICT (country_code, platform, method_code) DO UPDATE
    SET display_name    = EXCLUDED.display_name,
        payment_channel = EXCLUDED.payment_channel,
        payment_method  = EXCLUDED.payment_method,
        is_active       = EXCLUDED.is_active,
        updated_at      = CURRENT_TIMESTAMP;

-- ── ET ANDROID: Telebirr manual transfer (active) ────────────────────────────
INSERT INTO public.payment_methods (
    id, country_code, platform, method_code, display_name,
    payment_channel, payment_method, payment_instructions,
    is_active, display_order
) VALUES (
    'e0000000-0000-0000-0000-000000000012', 'ET', 'ANDROID',
    'TELEBIRR', 'Telebirr',
    'MANUAL_TRANSFER', 'TELEBIRR',
    'Send {{EXPECTED_AMOUNT}} {{CURRENCY}} to Telebirr account {{PAYMENT_ACCOUNT_NUMBER}} ({{PAYMENT_ACCOUNT_NAME}}). Use reference {{ORDER_REFERENCE}}. Payment expires {{ORDER_EXPIRY}}.',
    TRUE, 1
)
ON CONFLICT (country_code, platform, method_code) DO UPDATE
    SET display_name         = EXCLUDED.display_name,
        payment_channel      = EXCLUDED.payment_channel,
        payment_method       = EXCLUDED.payment_method,
        payment_instructions = EXCLUDED.payment_instructions,
        is_active            = EXCLUDED.is_active,
        updated_at           = CURRENT_TIMESTAMP;

-- ── ET ANDROID: CBE Bank Transfer manual (active) ────────────────────────────
INSERT INTO public.payment_methods (
    id, country_code, platform, method_code, display_name,
    payment_channel, payment_method, payment_instructions,
    is_active, display_order
) VALUES (
    'e0000000-0000-0000-0000-000000000013', 'ET', 'ANDROID',
    'CBE', 'CBE Bank Transfer',
    'MANUAL_TRANSFER', 'CBE_BANK_TRANSFER',
    'Transfer {{EXPECTED_AMOUNT}} {{CURRENCY}} to CBE account {{PAYMENT_ACCOUNT_NUMBER}} ({{PAYMENT_ACCOUNT_NAME}}). Use reference {{ORDER_REFERENCE}}. Payment expires {{ORDER_EXPIRY}}.',
    TRUE, 2
)
ON CONFLICT (country_code, platform, method_code) DO UPDATE
    SET display_name         = EXCLUDED.display_name,
        payment_channel      = EXCLUDED.payment_channel,
        payment_method       = EXCLUDED.payment_method,
        payment_instructions = EXCLUDED.payment_instructions,
        is_active            = EXCLUDED.is_active,
        updated_at           = CURRENT_TIMESTAMP;

-- ── ET ANDROID: CBE Birr manual (active) ─────────────────────────────────────
INSERT INTO public.payment_methods (
    id, country_code, platform, method_code, display_name,
    payment_channel, payment_method, payment_instructions,
    is_active, display_order
) VALUES (
    'e0000000-0000-0000-0000-000000000014', 'ET', 'ANDROID',
    'CBEBIRR', 'CBE Birr',
    'MANUAL_TRANSFER', 'CBE_BIRR',
    'Send {{EXPECTED_AMOUNT}} {{CURRENCY}} via CBE Birr to account {{PAYMENT_ACCOUNT_NUMBER}} ({{PAYMENT_ACCOUNT_NAME}}). Use reference {{ORDER_REFERENCE}}. Payment expires {{ORDER_EXPIRY}}.',
    TRUE, 3
)
ON CONFLICT (country_code, platform, method_code) DO UPDATE
    SET display_name         = EXCLUDED.display_name,
        payment_channel      = EXCLUDED.payment_channel,
        payment_method       = EXCLUDED.payment_method,
        payment_instructions = EXCLUDED.payment_instructions,
        is_active            = EXCLUDED.is_active,
        updated_at           = CURRENT_TIMESTAMP;

-- ── ET ANDROID: BOA manual transfer (active) ──────────────────────────────────
INSERT INTO public.payment_methods (
    id, country_code, platform, method_code, display_name,
    payment_channel, payment_method, payment_instructions,
    is_active, display_order
) VALUES (
    'e0000000-0000-0000-0000-000000000015', 'ET', 'ANDROID',
    'BOA', 'Bank of Abyssinia',
    'MANUAL_TRANSFER', 'BANK_TRANSFER',
    'Transfer {{EXPECTED_AMOUNT}} {{CURRENCY}} to Bank of Abyssinia account {{PAYMENT_ACCOUNT_NUMBER}} ({{PAYMENT_ACCOUNT_NAME}}). Use reference {{ORDER_REFERENCE}}. Payment expires {{ORDER_EXPIRY}}.',
    TRUE, 4
)
ON CONFLICT (country_code, platform, method_code) DO UPDATE
    SET display_name         = EXCLUDED.display_name,
        payment_channel      = EXCLUDED.payment_channel,
        payment_method       = EXCLUDED.payment_method,
        payment_instructions = EXCLUDED.payment_instructions,
        is_active            = EXCLUDED.is_active,
        updated_at           = CURRENT_TIMESTAMP;

-- ── ET ANDROID: M-Pesa manual transfer (active) ───────────────────────────────
INSERT INTO public.payment_methods (
    id, country_code, platform, method_code, display_name,
    payment_channel, payment_method, payment_instructions,
    is_active, display_order
) VALUES (
    'e0000000-0000-0000-0000-000000000016', 'ET', 'ANDROID',
    'MPESA', 'M-Pesa',
    'MANUAL_TRANSFER', 'MOBILE_MONEY',
    'Send {{EXPECTED_AMOUNT}} {{CURRENCY}} via M-Pesa to account {{PAYMENT_ACCOUNT_NUMBER}} ({{PAYMENT_ACCOUNT_NAME}}). Use reference {{ORDER_REFERENCE}}. Payment expires {{ORDER_EXPIRY}}.',
    TRUE, 5
)
ON CONFLICT (country_code, platform, method_code) DO UPDATE
    SET display_name         = EXCLUDED.display_name,
        payment_channel      = EXCLUDED.payment_channel,
        payment_method       = EXCLUDED.payment_method,
        payment_instructions = EXCLUDED.payment_instructions,
        is_active            = EXCLUDED.is_active,
        updated_at           = CURRENT_TIMESTAMP;

-- ── ET ANDROID: Dashen Bank manual transfer (active) ──────────────────────────
INSERT INTO public.payment_methods (
    id, country_code, platform, method_code, display_name,
    payment_channel, payment_method, payment_instructions,
    is_active, display_order
) VALUES (
    'e0000000-0000-0000-0000-000000000017', 'ET', 'ANDROID',
    'DASHEN', 'Dashen Bank',
    'MANUAL_TRANSFER', 'BANK_TRANSFER',
    'Transfer {{EXPECTED_AMOUNT}} {{CURRENCY}} to Dashen Bank account {{PAYMENT_ACCOUNT_NUMBER}} ({{PAYMENT_ACCOUNT_NAME}}). Use reference {{ORDER_REFERENCE}}. Payment expires {{ORDER_EXPIRY}}.',
    TRUE, 6
)
ON CONFLICT (country_code, platform, method_code) DO UPDATE
    SET display_name         = EXCLUDED.display_name,
        payment_channel      = EXCLUDED.payment_channel,
        payment_method       = EXCLUDED.payment_method,
        payment_instructions = EXCLUDED.payment_instructions,
        is_active            = EXCLUDED.is_active,
        updated_at           = CURRENT_TIMESTAMP;

-- ── ET ANDROID: Awash Bank manual transfer (active) ───────────────────────────
INSERT INTO public.payment_methods (
    id, country_code, platform, method_code, display_name,
    payment_channel, payment_method, payment_instructions,
    is_active, display_order
) VALUES (
    'e0000000-0000-0000-0000-000000000018', 'ET', 'ANDROID',
    'AWASH', 'Awash Bank',
    'MANUAL_TRANSFER', 'BANK_TRANSFER',
    'Transfer {{EXPECTED_AMOUNT}} {{CURRENCY}} to Awash Bank account {{PAYMENT_ACCOUNT_NUMBER}} ({{PAYMENT_ACCOUNT_NAME}}). Use reference {{ORDER_REFERENCE}}. Payment expires {{ORDER_EXPIRY}}.',
    TRUE, 7
)
ON CONFLICT (country_code, platform, method_code) DO UPDATE
    SET display_name         = EXCLUDED.display_name,
        payment_channel      = EXCLUDED.payment_channel,
        payment_method       = EXCLUDED.payment_method,
        payment_instructions = EXCLUDED.payment_instructions,
        is_active            = EXCLUDED.is_active,
        updated_at           = CURRENT_TIMESTAMP;

-- ── ET ANDROID: Siinqee Bank manual transfer (active) ─────────────────────────
INSERT INTO public.payment_methods (
    id, country_code, platform, method_code, display_name,
    payment_channel, payment_method, payment_instructions,
    is_active, display_order
) VALUES (
    'e0000000-0000-0000-0000-000000000019', 'ET', 'ANDROID',
    'SIINQEE', 'Siinqee Bank',
    'MANUAL_TRANSFER', 'BANK_TRANSFER',
    'Transfer {{EXPECTED_AMOUNT}} {{CURRENCY}} to Siinqee Bank account {{PAYMENT_ACCOUNT_NUMBER}} ({{PAYMENT_ACCOUNT_NAME}}). Use reference {{ORDER_REFERENCE}}. Payment expires {{ORDER_EXPIRY}}.',
    TRUE, 8
)
ON CONFLICT (country_code, platform, method_code) DO UPDATE
    SET display_name         = EXCLUDED.display_name,
        payment_channel      = EXCLUDED.payment_channel,
        payment_method       = EXCLUDED.payment_method,
        payment_instructions = EXCLUDED.payment_instructions,
        is_active            = EXCLUDED.is_active,
        updated_at           = CURRENT_TIMESTAMP;

-- ── ET ANDROID: Kaafie Birr manual transfer (active) ──────────────────────────
INSERT INTO public.payment_methods (
    id, country_code, platform, method_code, display_name,
    payment_channel, payment_method, payment_instructions,
    is_active, display_order
) VALUES (
    'e0000000-0000-0000-0000-000000000020', 'ET', 'ANDROID',
    'KAAFIEBIRR', 'Kaafie Birr',
    'MANUAL_TRANSFER', 'KAAFIEBIRR',
    'Send {{EXPECTED_AMOUNT}} {{CURRENCY}} via Kaafie Birr to account {{PAYMENT_ACCOUNT_NUMBER}} ({{PAYMENT_ACCOUNT_NAME}}). Use reference {{ORDER_REFERENCE}}. Payment expires {{ORDER_EXPIRY}}.',
    TRUE, 9
)
ON CONFLICT (country_code, platform, method_code) DO UPDATE
    SET display_name         = EXCLUDED.display_name,
        payment_channel      = EXCLUDED.payment_channel,
        payment_method       = EXCLUDED.payment_method,
        payment_instructions = EXCLUDED.payment_instructions,
        is_active            = EXCLUDED.is_active,
        updated_at           = CURRENT_TIMESTAMP;

-- ── ET ANDROID: Zemen Bank manual transfer (active) ───────────────────────────
INSERT INTO public.payment_methods (
    id, country_code, platform, method_code, display_name,
    payment_channel, payment_method, payment_instructions,
    is_active, display_order
) VALUES (
    'e0000000-0000-0000-0000-000000000021', 'ET', 'ANDROID',
    'ZEMEN', 'Zemen Bank',
    'MANUAL_TRANSFER', 'BANK_TRANSFER',
    'Transfer {{EXPECTED_AMOUNT}} {{CURRENCY}} to Zemen Bank account {{PAYMENT_ACCOUNT_NUMBER}} ({{PAYMENT_ACCOUNT_NAME}}). Use reference {{ORDER_REFERENCE}}. Payment expires {{ORDER_EXPIRY}}.',
    TRUE, 10
)
ON CONFLICT (country_code, platform, method_code) DO UPDATE
    SET display_name         = EXCLUDED.display_name,
        payment_channel      = EXCLUDED.payment_channel,
        payment_method       = EXCLUDED.payment_method,
        payment_instructions = EXCLUDED.payment_instructions,
        is_active            = EXCLUDED.is_active,
        updated_at           = CURRENT_TIMESTAMP;

-- ── Legacy inactive back-fill rows (V20 payment_orders used different values) ─
-- V20 payment_offers used payment_channel='CHAPA' with payment_method='TELEBIRR'
INSERT INTO public.payment_methods (
    id, country_code, platform, method_code, display_name,
    payment_channel, payment_method, is_active, display_order
) VALUES (
    'e0000000-0000-0000-0000-000000000091', 'ET', 'ANDROID',
    'LEGACY_CHAPA_TELEBIRR', 'Chapa (Legacy)',
    'CHAPA', 'TELEBIRR', FALSE, 99
)
ON CONFLICT (country_code, platform, method_code) DO NOTHING;

-- V20 payment_offers used payment_channel='MANUAL_TRANSFER' with payment_method='BANK_TRANSFER'
INSERT INTO public.payment_methods (
    id, country_code, platform, method_code, display_name,
    payment_channel, payment_method, is_active, display_order
) VALUES (
    'e0000000-0000-0000-0000-000000000092', 'ET', 'ANDROID',
    'LEGACY_BANK_TRANSFER', 'Bank Transfer (Legacy)',
    'MANUAL_TRANSFER', 'BANK_TRANSFER', FALSE, 99
)
ON CONFLICT (country_code, platform, method_code) DO NOTHING;

-- =============================================================================
-- 4. Add payment_method_id (nullable) to payment_orders
-- =============================================================================

ALTER TABLE public.payment_orders
    ADD COLUMN IF NOT EXISTS payment_method_id UUID
        REFERENCES public.payment_methods(id) ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS idx_payment_orders_method_id
    ON public.payment_orders(payment_method_id);

-- =============================================================================
-- 5. Remap orders referencing the duplicate ET PREMIUM_MONTHLY offer (d...004)
--    That offer (MANUAL_TRANSFER / BANK_TRANSFER duplicate) is removed below.
--    Any orders on it are remapped to the canonical CHAPA-seeded offer (d...001).
-- =============================================================================

UPDATE public.payment_orders
    SET payment_offer_id = 'd0000000-0000-0000-0000-000000000001'
    WHERE payment_offer_id = 'd0000000-0000-0000-0000-000000000004';

UPDATE public.user_subscriptions
    SET payment_offer_id = 'd0000000-0000-0000-0000-000000000001'
    WHERE payment_offer_id = 'd0000000-0000-0000-0000-000000000004';

UPDATE public.transactions
    SET payment_offer_id = 'd0000000-0000-0000-0000-000000000001'
    WHERE payment_offer_id = 'd0000000-0000-0000-0000-000000000004';

-- =============================================================================
-- 6. Deterministic back-fill: payment_orders.payment_method_id
--    Match on payment_channel + payment_method from legacy order columns,
--    scoped to the offer's country_code + platform.
-- =============================================================================

UPDATE public.payment_orders po
    SET payment_method_id = pm.id
    FROM public.payment_methods pm,
         public.payment_offers  pof
    WHERE pof.id              = po.payment_offer_id
      AND pm.payment_channel  = po.payment_channel
      AND pm.payment_method   = po.payment_method
      AND pm.country_code     = pof.country_code
      AND pm.platform         = pof.platform
      AND po.payment_method_id IS NULL;

-- =============================================================================
-- 7. Delete the duplicate ET PREMIUM_MONTHLY offer
-- =============================================================================

DELETE FROM public.payment_offers
    WHERE id = 'd0000000-0000-0000-0000-000000000004';

-- =============================================================================
-- 8. Make payment_method_id NOT NULL
--    (All rows should be back-filled by step 6. Any NULL row would indicate a
--    V20 order with a payment_channel/method combo not covered by the legacy
--    seeds. Fail loudly here during migration to surface the issue.)
-- =============================================================================

ALTER TABLE public.payment_orders
    ALTER COLUMN payment_method_id SET NOT NULL;

-- =============================================================================
-- 9. Market-matching constraint trigger
--    Ensures every payment order pairs an offer and a payment method that
--    belong to the same billing market (country_code + platform).
--    Historical orders remain valid even when a payment method is later
--    disabled because the trigger only checks country_code and platform, not
--    is_active.
-- =============================================================================

CREATE OR REPLACE FUNCTION public.validate_payment_order_market()
RETURNS TRIGGER AS $$
DECLARE
    v_offer_country  VARCHAR(10);
    v_offer_platform VARCHAR(20);
    v_method_country VARCHAR(10);
    v_method_platform VARCHAR(20);
BEGIN
    IF NEW.payment_method_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT country_code, platform
        INTO v_offer_country, v_offer_platform
        FROM public.payment_offers
        WHERE id = NEW.payment_offer_id;

    SELECT country_code, platform
        INTO v_method_country, v_method_platform
        FROM public.payment_methods
        WHERE id = NEW.payment_method_id;

    IF v_offer_country IS DISTINCT FROM v_method_country
    OR v_offer_platform IS DISTINCT FROM v_method_platform THEN
        RAISE EXCEPTION
            'payment_order_market_mismatch: offer(country=%, platform=%) vs method(country=%, platform=%)',
            v_offer_country, v_offer_platform,
            v_method_country, v_method_platform;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_validate_payment_order_market
    BEFORE INSERT OR UPDATE ON public.payment_orders
    FOR EACH ROW EXECUTE FUNCTION public.validate_payment_order_market();

-- =============================================================================
-- 10. Drop legacy payment_channel / payment_method from payment_orders
-- =============================================================================

ALTER TABLE public.payment_orders
    DROP COLUMN IF EXISTS payment_channel,
    DROP COLUMN IF EXISTS payment_method;

-- =============================================================================
-- 11. Remove obsolete columns from payment_offers
-- =============================================================================

-- Drop CHECK constraints that reference the columns being removed
ALTER TABLE public.payment_offers
    DROP CONSTRAINT IF EXISTS payment_offers_payment_channel_check;

ALTER TABLE public.payment_offers
    DROP CONSTRAINT IF EXISTS payment_offers_payment_method_check;

ALTER TABLE public.payment_offers
    DROP COLUMN IF EXISTS payment_channel,
    DROP COLUMN IF EXISTS payment_method,
    DROP COLUMN IF EXISTS external_base_plan_id;

-- =============================================================================
-- 12. Unique partial indexes on payment_offers
--     Enforce one offer per (country, platform, subscription_product)
--     and one per (country, platform, consumable_product).
-- =============================================================================

CREATE UNIQUE INDEX IF NOT EXISTS unique_payment_offer_subscription
    ON public.payment_offers(country_code, platform, subscription_product_id)
    WHERE subscription_product_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS unique_payment_offer_consumable
    ON public.payment_offers(country_code, platform, consumable_product_id)
    WHERE consumable_product_id IS NOT NULL;

-- =============================================================================
-- 13. Expand provider constraints to include ARIFPAY
-- =============================================================================

ALTER TABLE public.transactions
    DROP CONSTRAINT IF EXISTS transactions_provider_check;

ALTER TABLE public.transactions
    ADD CONSTRAINT transactions_provider_check CHECK (
        provider IN (
            'STRIPE', 'APPLE_APP_STORE', 'GOOGLE_PLAY',
            'TELEBIRR', 'CBE_BIRR', 'CHAPA', 'ARIFPAY', 'BANK_TRANSFER',
            'REVENUECAT', 'ADMIN'
        )
    );

ALTER TABLE public.payment_events
    DROP CONSTRAINT IF EXISTS payment_events_provider_check;

ALTER TABLE public.payment_events
    ADD CONSTRAINT payment_events_provider_check CHECK (
        provider IN (
            'STRIPE', 'REVENUECAT', 'APPLE_APP_STORE', 'GOOGLE_PLAY',
            'TELEBIRR', 'CBE_BIRR', 'CHAPA', 'ARIFPAY', 'BANK_TRANSFER', 'VERIFY_ET'
        )
    );

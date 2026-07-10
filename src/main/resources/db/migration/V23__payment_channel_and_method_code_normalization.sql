-- =============================================================================
-- V23: Normalize payment_channel to two types + lowercase method_code
--
-- 1. payment_channel: only ONLINE_PAYMENT and MANUAL_TRANSFER
-- 2. Partial unique index: one active ONLINE_PAYMENT per (country_code, platform)
-- 3. method_code: standardized values (apple, google, stripe, chapa, arifpay,
--    telebirr, cbe, cbebirr, boa, mpesa, dashen, awash, siinqee, kaafiebirr, zemen)
-- =============================================================================

-- =============================================================================
-- 1. Update payment_channel values to ONLINE_PAYMENT or MANUAL_TRANSFER
-- =============================================================================

UPDATE public.payment_methods
    SET payment_channel = 'ONLINE_PAYMENT',
        updated_at = CURRENT_TIMESTAMP
    WHERE payment_channel IN ('REVENUECAT_APPLE', 'REVENUECAT_GOOGLE', 'REVENUECAT_WEB',
                              'CHAPA', 'ARIFPAY');

-- =============================================================================
-- 2. Update method_code to standardized lowercase values
--    APPLE_IAP      -> apple
--    GOOGLE_PLAY    -> google
--    STRIPE         -> stripe
--    CHAPA          -> chapa
--    ARIFPAY        -> arifpay
--    TELEBIRR       -> telebirr
--    CBE            -> cbe
--    CBEBIRR        -> cbebirr
--    BOA            -> boa
--    MPESA          -> mpesa
--    DASHEN         -> dashen
--    AWASH          -> awash
--    SIINQEE        -> siinqee
--    KAAFIEBIRR     -> kaafiebirr
--    ZEMEN          -> zemen
-- =============================================================================

UPDATE public.payment_methods SET method_code = 'apple',    updated_at = CURRENT_TIMESTAMP WHERE method_code = 'APPLE_IAP';
UPDATE public.payment_methods SET method_code = 'google',   updated_at = CURRENT_TIMESTAMP WHERE method_code = 'GOOGLE_PLAY';
UPDATE public.payment_methods SET method_code = 'stripe',   updated_at = CURRENT_TIMESTAMP WHERE method_code = 'STRIPE';
UPDATE public.payment_methods SET method_code = 'chapa',    updated_at = CURRENT_TIMESTAMP WHERE method_code = 'CHAPA';
UPDATE public.payment_methods SET method_code = 'arifpay',  updated_at = CURRENT_TIMESTAMP WHERE method_code = 'ARIFPAY';
UPDATE public.payment_methods SET method_code = 'telebirr', updated_at = CURRENT_TIMESTAMP WHERE method_code = 'TELEBIRR';
UPDATE public.payment_methods SET method_code = 'cbe',      updated_at = CURRENT_TIMESTAMP WHERE method_code = 'CBE';
UPDATE public.payment_methods SET method_code = 'cbebirr',  updated_at = CURRENT_TIMESTAMP WHERE method_code = 'CBEBIRR';
UPDATE public.payment_methods SET method_code = 'boa',      updated_at = CURRENT_TIMESTAMP WHERE method_code = 'BOA';
UPDATE public.payment_methods SET method_code = 'mpesa',    updated_at = CURRENT_TIMESTAMP WHERE method_code = 'MPESA';
UPDATE public.payment_methods SET method_code = 'dashen',   updated_at = CURRENT_TIMESTAMP WHERE method_code = 'DASHEN';
UPDATE public.payment_methods SET method_code = 'awash',    updated_at = CURRENT_TIMESTAMP WHERE method_code = 'AWASH';
UPDATE public.payment_methods SET method_code = 'siinqee',  updated_at = CURRENT_TIMESTAMP WHERE method_code = 'SIINQEE';
UPDATE public.payment_methods SET method_code = 'kaafiebirr', updated_at = CURRENT_TIMESTAMP WHERE method_code = 'KAAFIEBIRR';
UPDATE public.payment_methods SET method_code = 'zemen',    updated_at = CURRENT_TIMESTAMP WHERE method_code = 'ZEMEN';

-- =============================================================================
-- 3. Add CHECK constraint on payment_channel
-- =============================================================================

ALTER TABLE public.payment_methods
    DROP CONSTRAINT IF EXISTS payment_methods_payment_channel_check;

ALTER TABLE public.payment_methods
    ADD CONSTRAINT payment_methods_payment_channel_check CHECK (
        payment_channel IN ('ONLINE_PAYMENT', 'MANUAL_TRANSFER')
    );

-- =============================================================================
-- 4. Partial unique index: only one active ONLINE_PAYMENT per market
--    (country_code + platform)
-- =============================================================================

CREATE UNIQUE INDEX IF NOT EXISTS unique_active_online_payment_per_market
    ON public.payment_methods(country_code, platform)
    WHERE payment_channel = 'ONLINE_PAYMENT' AND is_active = TRUE;

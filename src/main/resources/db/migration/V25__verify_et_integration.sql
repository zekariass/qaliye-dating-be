-- =============================================================================
-- V25: Verify.et Integration
--
-- 1. Add verify.et tracking columns to payment_verification_attempts
-- 2. Index on verify_et_request_id for fast webhook lookup
-- 3. Expand provider constraints on transactions and payment_events
-- =============================================================================

-- =============================================================================
-- 1. verify.et tracking columns on payment_verification_attempts
-- =============================================================================

ALTER TABLE public.payment_verification_attempts
    ADD COLUMN IF NOT EXISTS verify_et_request_id       VARCHAR(36)   NULL,
    ADD COLUMN IF NOT EXISTS verify_et_idempotency_key  VARCHAR(255)  NULL,
    ADD COLUMN IF NOT EXISTS settlement_account_matched  BOOLEAN       NULL,
    ADD COLUMN IF NOT EXISTS confirmed_before            BOOLEAN       NULL;

-- =============================================================================
-- 2. Index: fast webhook lookup by verify_et_request_id
-- =============================================================================

CREATE INDEX IF NOT EXISTS idx_pva_verify_et_request_id
    ON public.payment_verification_attempts(verify_et_request_id)
    WHERE verify_et_request_id IS NOT NULL;

-- =============================================================================
-- 3. Expand provider CHECK on transactions to include MANUAL_TRANSFER banks
-- =============================================================================

ALTER TABLE public.transactions
    DROP CONSTRAINT IF EXISTS transactions_provider_check;

ALTER TABLE public.transactions
    ADD CONSTRAINT transactions_provider_check CHECK (
        provider IN (
            'STRIPE', 'APPLE_APP_STORE', 'GOOGLE_PLAY',
            'TELEBIRR', 'CBE_BIRR', 'CHAPA', 'ARIFPAY', 'BANK_TRANSFER',
            'REVENUECAT', 'ADMIN',
            'cbe', 'telebirr', 'cbebirr', 'mpesa', 'boa',
            'awash', 'dashen', 'siinqee', 'kaafiebirr', 'zemen'
        )
    );

-- =============================================================================
-- 4. Expand provider CHECK on payment_events to include VERIFY_ET
-- =============================================================================

ALTER TABLE public.payment_events
    DROP CONSTRAINT IF EXISTS payment_events_provider_check;

ALTER TABLE public.payment_events
    ADD CONSTRAINT payment_events_provider_check CHECK (
        provider IN (
            'STRIPE', 'REVENUECAT', 'APPLE_APP_STORE', 'GOOGLE_PLAY',
            'TELEBIRR', 'CBE_BIRR', 'CHAPA', 'ARIFPAY', 'BANK_TRANSFER',
            'VERIFY_ET'
        )
    );

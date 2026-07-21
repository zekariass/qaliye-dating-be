-- =============================================================================
-- V43: Local Promotion and Discount System
-- =============================================================================

-- Add PROMOTION as a valid provider value in user_subscriptions
ALTER TABLE public.user_subscriptions
    DROP CONSTRAINT IF EXISTS user_subscriptions_provider_check;

ALTER TABLE public.user_subscriptions
    ADD CONSTRAINT user_subscriptions_provider_check CHECK (
        provider IN (
            'STRIPE', 'APPLE_APP_STORE', 'GOOGLE_PLAY',
            'TELEBIRR', 'CBE_BIRR', 'CHAPA', 'BANK_TRANSFER',
            'REVENUECAT', 'ADMIN', 'ARIFPAY', 'PROMOTION'
        )
    );

-- =============================================================================
-- Promotion Campaigns
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.promotion_campaigns (
    id                       UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_key             VARCHAR(100)  NOT NULL,
    name                     VARCHAR(255)  NOT NULL,
    description              TEXT,
    trigger_type             VARCHAR(30)   NOT NULL,
    eligibility_type         VARCHAR(40)   NOT NULL,
    benefit_type             VARCHAR(30)   NOT NULL,
    discount_type            VARCHAR(20),
    discount_value           BIGINT,
    discount_currency        VARCHAR(3),
    subscription_product_id  UUID          NOT NULL REFERENCES public.subscription_products(id),
    country_code             VARCHAR(10)   NOT NULL,
    duration_days            INTEGER,
    new_user_window_days     INTEGER,
    max_redemptions          INTEGER,
    max_redemptions_per_user INTEGER       NOT NULL DEFAULT 1,
    reserved_count           INTEGER       NOT NULL DEFAULT 0,
    fulfilled_count          INTEGER       NOT NULL DEFAULT 0,
    priority                 INTEGER       NOT NULL DEFAULT 0,
    starts_at                TIMESTAMPTZ   NOT NULL,
    ends_at                  TIMESTAMPTZ,
    status                   VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    created_by_user_id       UUID          REFERENCES public.app_users(id) ON DELETE SET NULL,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_promotion_campaign_key UNIQUE (campaign_key),

    CONSTRAINT chk_campaign_trigger_type CHECK (
        trigger_type IN ('AUTO_ON_SIGNUP', 'USER_CLAIM', 'PURCHASE')
    ),
    CONSTRAINT chk_campaign_eligibility_type CHECK (
        eligibility_type IN ('ANY_ELIGIBLE_USER', 'NEW_USER', 'NEVER_SUBSCRIBED', 'NO_ACTIVE_SUBSCRIPTION')
    ),
    CONSTRAINT chk_campaign_benefit_type CHECK (
        benefit_type IN ('FREE_PREMIUM', 'DISCOUNT')
    ),
    CONSTRAINT chk_campaign_discount_type CHECK (
        discount_type IS NULL OR discount_type IN ('FIXED', 'PERCENTAGE')
    ),
    CONSTRAINT chk_campaign_status CHECK (
        status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'EXPIRED')
    ),
    CONSTRAINT chk_campaign_free_premium CHECK (
        benefit_type <> 'FREE_PREMIUM'
        OR (duration_days > 0 AND discount_type IS NULL AND discount_value IS NULL AND discount_currency IS NULL)
    ),
    CONSTRAINT chk_campaign_discount_required CHECK (
        benefit_type <> 'DISCOUNT'
        OR (discount_type IS NOT NULL AND discount_value IS NOT NULL)
    ),
    CONSTRAINT chk_campaign_percentage_value CHECK (
        discount_type <> 'PERCENTAGE'
        OR (discount_value > 0 AND discount_value <= 10000 AND discount_currency IS NULL)
    ),
    CONSTRAINT chk_campaign_fixed_currency CHECK (
        discount_type <> 'FIXED'
        OR (discount_value > 0 AND discount_currency IS NOT NULL)
    ),
    CONSTRAINT chk_campaign_new_user_window CHECK (
        eligibility_type <> 'NEW_USER' OR new_user_window_days > 0
    ),
    CONSTRAINT chk_campaign_ends_after_starts CHECK (
        ends_at IS NULL OR ends_at > starts_at
    ),
    CONSTRAINT chk_campaign_max_redemptions CHECK (
        max_redemptions IS NULL OR max_redemptions > 0
    ),
    CONSTRAINT chk_campaign_max_per_user CHECK (
        max_redemptions_per_user > 0
    ),
    CONSTRAINT chk_campaign_reserved_count    CHECK (reserved_count    >= 0),
    CONSTRAINT chk_campaign_fulfilled_count   CHECK (fulfilled_count   >= 0)
);

CREATE INDEX IF NOT EXISTS idx_promotion_campaigns_key
    ON public.promotion_campaigns(campaign_key);

CREATE INDEX IF NOT EXISTS idx_promotion_campaigns_status_dates
    ON public.promotion_campaigns(status, starts_at, ends_at);

CREATE INDEX IF NOT EXISTS idx_promotion_campaigns_product_status
    ON public.promotion_campaigns(subscription_product_id, status);

CREATE INDEX IF NOT EXISTS idx_promotion_campaigns_country_status
    ON public.promotion_campaigns(country_code, status);

-- =============================================================================
-- Promotion Redemptions
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.promotion_redemptions (
    id                     UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id            UUID          NOT NULL REFERENCES public.promotion_campaigns(id),
    user_id                UUID          NOT NULL REFERENCES public.app_users(id),
    subscription_id        UUID          REFERENCES public.user_subscriptions(id) ON DELETE SET NULL,
    payment_offer_id       UUID          REFERENCES public.payment_offers(id)     ON DELETE SET NULL,
    payment_order_id       UUID          REFERENCES public.payment_orders(id)     ON DELETE SET NULL,
    status                 VARCHAR(20)   NOT NULL,
    eligibility_country    VARCHAR(10)   NOT NULL,
    original_amount_minor  BIGINT,
    discount_amount_minor  BIGINT,
    final_amount_minor     BIGINT,
    currency               VARCHAR(3),
    reserved_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    fulfilled_at           TIMESTAMPTZ,
    cancelled_at           TIMESTAMPTZ,
    expired_at             TIMESTAMPTZ,
    failed_at              TIMESTAMPTZ,
    failure_code           VARCHAR(100),
    failure_reason         TEXT,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_redemption_status CHECK (
        status IN ('RESERVED', 'PROVIDER_PENDING', 'FULFILLED', 'FAILED', 'CANCELLED', 'EXPIRED')
    )
);

CREATE INDEX IF NOT EXISTS idx_promotion_redemptions_campaign
    ON public.promotion_redemptions(campaign_id);

CREATE INDEX IF NOT EXISTS idx_promotion_redemptions_user
    ON public.promotion_redemptions(user_id);

CREATE INDEX IF NOT EXISTS idx_promotion_redemptions_campaign_user
    ON public.promotion_redemptions(campaign_id, user_id);

CREATE INDEX IF NOT EXISTS idx_promotion_redemptions_status
    ON public.promotion_redemptions(status);

CREATE INDEX IF NOT EXISTS idx_promotion_redemptions_order
    ON public.promotion_redemptions(payment_order_id)
    WHERE payment_order_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_promotion_redemptions_subscription
    ON public.promotion_redemptions(subscription_id)
    WHERE subscription_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_promotion_redemptions_stale
    ON public.promotion_redemptions(status, reserved_at)
    WHERE status IN ('RESERVED', 'PROVIDER_PENDING');

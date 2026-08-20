-- =============================================================================
-- V61: Add CREDITS benefit type to promotion_campaigns
-- =============================================================================

-- Add included_credits column (nullable; required when benefit_type = CREDITS)
ALTER TABLE public.promotion_campaigns
    ADD COLUMN IF NOT EXISTS included_credits BIGINT;

-- Expand benefit_type constraint to include CREDITS
ALTER TABLE public.promotion_campaigns
    DROP CONSTRAINT IF EXISTS chk_campaign_benefit_type;
ALTER TABLE public.promotion_campaigns
    ADD CONSTRAINT chk_campaign_benefit_type CHECK (
        benefit_type IN ('FREE_PREMIUM', 'DISCOUNT', 'CREDITS')
    );

-- CREDITS campaigns must have included_credits > 0
ALTER TABLE public.promotion_campaigns
    DROP CONSTRAINT IF EXISTS chk_campaign_credits_required;
ALTER TABLE public.promotion_campaigns
    ADD CONSTRAINT chk_campaign_credits_required CHECK (
        benefit_type <> 'CREDITS'
        OR (included_credits IS NOT NULL AND included_credits > 0)
    );

-- included_credits must be positive when set (applies to any benefit type)
ALTER TABLE public.promotion_campaigns
    DROP CONSTRAINT IF EXISTS chk_campaign_included_credits_positive;
ALTER TABLE public.promotion_campaigns
    ADD CONSTRAINT chk_campaign_included_credits_positive CHECK (
        included_credits IS NULL OR included_credits > 0
    );

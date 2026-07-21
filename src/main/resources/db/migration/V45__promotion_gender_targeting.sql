-- =============================================================================
-- V45: Add gender-targeted campaigns and record eligibility gender at redemption
-- =============================================================================

-- Add target_gender to promotion_campaigns (nullable for no gender restriction)
ALTER TABLE public.promotion_campaigns
    ADD COLUMN IF NOT EXISTS target_gender VARCHAR(10);

-- Add CHECK constraint for valid gender values (NULL allowed)
ALTER TABLE public.promotion_campaigns
    DROP CONSTRAINT IF EXISTS chk_campaign_target_gender;
ALTER TABLE public.promotion_campaigns
    ADD CONSTRAINT chk_campaign_target_gender CHECK (
        target_gender IS NULL OR target_gender IN ('MALE', 'FEMALE')
    );

-- Index for filtering by gender during eligibility checks
CREATE INDEX IF NOT EXISTS idx_promotion_campaigns_gender_status
    ON public.promotion_campaigns(target_gender, status)
    WHERE target_gender IS NOT NULL;

-- Add eligibility_gender to promotion_redemptions to record matched gender
ALTER TABLE public.promotion_redemptions
    ADD COLUMN IF NOT EXISTS eligibility_gender VARCHAR(10);

ALTER TABLE public.promotion_redemptions
    DROP CONSTRAINT IF EXISTS chk_redemption_eligibility_gender;
ALTER TABLE public.promotion_redemptions
    ADD CONSTRAINT chk_redemption_eligibility_gender CHECK (
        eligibility_gender IS NULL OR eligibility_gender IN ('MALE', 'FEMALE')
    );

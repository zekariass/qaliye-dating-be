-- =============================================================================
-- V59: Add consumable product support to promotion campaigns
-- =============================================================================

-- Make subscription_product_id nullable (was NOT NULL) so consumable-only campaigns can exist
ALTER TABLE public.promotion_campaigns
    ALTER COLUMN subscription_product_id DROP NOT NULL;

-- Add consumable_product_id column
ALTER TABLE public.promotion_campaigns
    ADD COLUMN IF NOT EXISTS consumable_product_id UUID
    REFERENCES public.consumable_products(id);

-- Ensure exactly one product target is set (mutual exclusivity)
ALTER TABLE public.promotion_campaigns
    DROP CONSTRAINT IF EXISTS chk_campaign_product_target;
ALTER TABLE public.promotion_campaigns
    ADD CONSTRAINT chk_campaign_product_target CHECK (
        (subscription_product_id IS NOT NULL AND consumable_product_id IS NULL)
        OR
        (subscription_product_id IS NULL AND consumable_product_id IS NOT NULL)
    );

-- FREE_PREMIUM only makes sense for subscription products
ALTER TABLE public.promotion_campaigns
    DROP CONSTRAINT IF EXISTS chk_campaign_free_premium_consumable;
ALTER TABLE public.promotion_campaigns
    ADD CONSTRAINT chk_campaign_free_premium_consumable CHECK (
        benefit_type <> 'FREE_PREMIUM' OR consumable_product_id IS NULL
    );

-- Index for consumable product lookups
CREATE INDEX IF NOT EXISTS idx_promotion_campaigns_consumable_product_status
    ON public.promotion_campaigns(consumable_product_id, status)
    WHERE consumable_product_id IS NOT NULL;

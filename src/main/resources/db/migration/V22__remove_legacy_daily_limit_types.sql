-- =============================================================================
-- V22: Remove legacy DAILY_LIKES / DAILY_SUPERLIKES / DAILY_REWINDS limit types
--
-- The V20 migration introduced new-style limit types (LIKES, SUPERLIKES, REWINDS,
-- BOOSTS) with a period_type column, but kept the old DAILY_* rows "for backwards
-- compatibility".  Both sets duplicated the same data.  The application code has
-- now been migrated to read only the new-style types, so the old rows and their
-- CHECK constraint entries can be removed.
-- =============================================================================

-- 1. Delete legacy DAILY_* rows from subscription_plan_limits
DELETE FROM public.subscription_plan_limits
WHERE limit_type IN ('DAILY_LIKES', 'DAILY_SUPERLIKES', 'DAILY_REWINDS');

-- 2. Update the CHECK constraint to only allow the new-style limit types
ALTER TABLE public.subscription_plan_limits
    DROP CONSTRAINT IF EXISTS subscription_plan_limits_limit_type_check;

ALTER TABLE public.subscription_plan_limits
    ADD CONSTRAINT subscription_plan_limits_limit_type_check CHECK (
        limit_type IN ('LIKES', 'SUPERLIKES', 'REWINDS', 'BOOSTS')
    );

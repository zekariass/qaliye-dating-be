-- =============================================================================
-- V39: Add voice/image chat message daily limits
--
-- Adds two new limit types to subscription_plan_limits and two tracking columns
-- to user_daily_limits, following the same pattern as LIKES/SUPERLIKES/REWINDS.
-- =============================================================================

-- 1. Expand the CHECK constraint to allow the new limit types
ALTER TABLE public.subscription_plan_limits
    DROP CONSTRAINT IF EXISTS subscription_plan_limits_limit_type_check;

ALTER TABLE public.subscription_plan_limits
    ADD CONSTRAINT subscription_plan_limits_limit_type_check CHECK (
        limit_type IN ('LIKES', 'SUPERLIKES', 'REWINDS', 'BOOSTS',
                       'VOICE_CHAT_MSGS', 'IMAGE_CHAT_MSGS')
    );

-- 2. Add tracking columns to user_daily_limits
ALTER TABLE public.user_daily_limits
    ADD COLUMN IF NOT EXISTS voice_chat_msgs_used INTEGER NOT NULL DEFAULT 0;

ALTER TABLE public.user_daily_limits
    ADD COLUMN IF NOT EXISTS image_chat_msgs_used INTEGER NOT NULL DEFAULT 0;

-- 3. Seed plan limits for FREE plan (0 voice/image messages per day)
INSERT INTO public.subscription_plan_limits (plan_id, limit_type, limit_value, period_type)
SELECT sp.id, lt.limit_type, lt.limit_value, lt.period_type
FROM (SELECT id FROM subscription_plans WHERE plan_code = 'FREE' AND country_code = 'GLOBAL' LIMIT 1) sp
CROSS JOIN (VALUES
    ('VOICE_CHAT_MSGS', 0::INTEGER, 'DAILY'),
    ('IMAGE_CHAT_MSGS', 0::INTEGER, 'DAILY')
) AS lt(limit_type, limit_value, period_type)
ON CONFLICT (plan_id, limit_type) DO NOTHING;

-- 4. Seed plan limits for PREMIUM plan (unlimited voice/image messages per day)
INSERT INTO public.subscription_plan_limits (plan_id, limit_type, limit_value, period_type)
SELECT sp.id, lt.limit_type, lt.limit_value, lt.period_type
FROM (SELECT id FROM subscription_plans WHERE plan_code = 'PREMIUM' AND country_code = 'GLOBAL' LIMIT 1) sp
CROSS JOIN (VALUES
    ('VOICE_CHAT_MSGS', NULL::INTEGER, 'DAILY'),
    ('IMAGE_CHAT_MSGS', NULL::INTEGER, 'DAILY')
) AS lt(limit_type, limit_value, period_type)
ON CONFLICT (plan_id, limit_type) DO NOTHING;

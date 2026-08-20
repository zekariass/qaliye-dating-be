-- =============================================================================
-- V62: Move INCOGNITO_MODE from subscription_plans.features to
--      subscription_plan_limit_and_cost (action-based billing)
--
-- Incognito Mode is no longer a boolean feature flag in subscription_plans.features.
-- It is now controlled by subscription_plan_limit_and_cost, just like LIKE,
-- SUPER_LIKE, REWIND, etc. The action is evaluated and charged via ActionCostService.
-- =============================================================================

-- 1. Change INCOGNITO_MODE from FEATURE type to ACTION type
UPDATE public.feature_actions
SET type = 'FEATURE',
    name = 'Incognito Mode'
WHERE code = 'INCOGNITO_MODE';

-- 2. Remove incognitoMode from subscription_plans.features JSON
UPDATE public.subscription_plans
SET features = features - 'incognitoMode'
WHERE features ? 'incognitoMode';

-- 3. Seed subscription_plan_limit_and_cost for INCOGNITO_MODE
--    FREE plan: blocked (limit 0, no credit bypass)
INSERT INTO public.subscription_plan_limit_and_cost
    (subscription_plan_id, feature_action_id, member_credit_cost, actual_credit_cost,
     limit_value, period_type, apply_credit_after_limit)
SELECT sp.id, fa.id, 0, 0, 0, 'MONTH', FALSE
FROM public.subscription_plans sp
JOIN public.feature_actions fa ON fa.code = 'INCOGNITO_MODE'
WHERE sp.plan_code = 'FREE' AND sp.country_code = 'GLOBAL' AND sp.is_active = TRUE
ON CONFLICT (subscription_plan_id, feature_action_id) DO NOTHING;

--    PREMIUM plan: unlimited, free (included in subscription)
INSERT INTO public.subscription_plan_limit_and_cost
    (subscription_plan_id, feature_action_id, member_credit_cost, actual_credit_cost,
     limit_value, period_type, apply_credit_after_limit)
SELECT sp.id, fa.id, 0, 0, NULL, 'MONTH', FALSE
FROM public.subscription_plans sp
JOIN public.feature_actions fa ON fa.code = 'INCOGNITO_MODE'
WHERE sp.plan_code = 'PREMIUM' AND sp.country_code = 'GLOBAL' AND sp.is_active = TRUE
ON CONFLICT (subscription_plan_id, feature_action_id) DO NOTHING;

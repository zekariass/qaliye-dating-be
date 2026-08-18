-- =============================================================================
-- V56: Add MESSAGE feature action
-- =============================================================================
-- Registers the MESSAGE action code in feature_actions so that
-- subscription_plan_limit_and_cost rows can be configured per plan.
-- No plan-level limits are seeded here — configure them via the admin API
-- or directly in subscription_plan_limit_and_cost per plan as required.
-- =============================================================================

INSERT INTO public.feature_actions (code, name, type)
VALUES ('MESSAGE', 'Message', 'ACTION')
ON CONFLICT (code) DO NOTHING;

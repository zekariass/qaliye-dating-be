-- Ensure plan_kind lookups for premium placement remain performant
CREATE INDEX IF NOT EXISTS idx_subscription_plans_plan_kind
    ON subscription_plans(plan_kind);

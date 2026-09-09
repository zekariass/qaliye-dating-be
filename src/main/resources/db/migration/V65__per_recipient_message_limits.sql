-- =============================================================================
-- V65: Per-recipient message limits
--
-- 1. Extend period_type CHECK constraint to include 'LIFETIME'.
--    LIFETIME rules bypass the period-based user_action_limits_tracker and are
--    instead tracked per sender-recipient pair in user_message_pair_tracker.
--
-- 2. Create user_message_pair_tracker.
--    Tracks message counts per (sender, recipient, rule).
--    - For LIFETIME rules: period_start_date IS NULL, one row per pair forever.
--    - For period-based rules: period_start_date IS NOT NULL, one row per pair
--      per period (reserved for future per-recipient periodic limits).
-- =============================================================================

-- 1. Extend period_type to accept LIFETIME
ALTER TABLE public.subscription_plan_limit_and_cost
    DROP CONSTRAINT IF EXISTS subscription_plan_limit_and_cost_period_type_check;

ALTER TABLE public.subscription_plan_limit_and_cost
    ADD CONSTRAINT subscription_plan_limit_and_cost_period_type_check
    CHECK (period_type IN ('DAY', 'MONTH', 'BILLING_CYCLE', 'LIFETIME'));

-- 2. Per sender-recipient pair message count tracker
CREATE TABLE public.user_message_pair_tracker (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_id         UUID        NOT NULL REFERENCES public.app_users(id),
    recipient_id      UUID        NOT NULL REFERENCES public.app_users(id),
    rule_id           UUID        NOT NULL REFERENCES public.subscription_plan_limit_and_cost(id),
    message_count     INT         NOT NULL DEFAULT 0,
    period_start_date DATE,
    period_end_date   DATE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- One row per (sender, recipient, rule) for LIFETIME (no period dates)
CREATE UNIQUE INDEX umpt_lifetime_unique
    ON public.user_message_pair_tracker (sender_id, recipient_id, rule_id)
    WHERE period_start_date IS NULL;

-- One row per (sender, recipient, rule, period) for period-based rules
CREATE UNIQUE INDEX umpt_period_unique
    ON public.user_message_pair_tracker (sender_id, recipient_id, rule_id, period_start_date)
    WHERE period_start_date IS NOT NULL;

CREATE INDEX umpt_sender_recipient_idx
    ON public.user_message_pair_tracker (sender_id, recipient_id);

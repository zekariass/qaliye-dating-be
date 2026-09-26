-- =============================================================================
-- V66: Configurable LIKE action variant system
--
-- Adds a generic "action variant" mechanism on top of feature_actions, initially
-- used only by the LIKE action (HEART, ROSE, BUNA, CHOCOLATE, FLOWERS, RING).
--
-- 1.  Extend feature_actions with active/sort_order/icon/description/has_variants
-- 2.  Create action_feature_variants (variant metadata per feature action)
-- 3.  Create subscription_plan_variant_limit_and_cost (variant pricing/limits)
-- 4.  Extend subscription_plan_limit_and_cost with variant_pricing_enabled /
--     variant_limits_enabled flags
-- 5.  Add user_discovery_actions.action_variant_code + CHECK constraint
-- 6.  Seed the six initial LIKE variants and their plan pricing (HEART mirrors
--     the existing LIKE pricing so default behavior is unchanged)
-- =============================================================================

-- =============================================================================
-- 1. EXTEND feature_actions
-- =============================================================================

ALTER TABLE public.feature_actions
    ADD COLUMN IF NOT EXISTS active       BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS sort_order   INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS icon         VARCHAR(500),
    ADD COLUMN IF NOT EXISTS description  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS has_variants BOOLEAN     NOT NULL DEFAULT FALSE;

-- =============================================================================
-- 2. ACTION FEATURE VARIANTS
--    Variant metadata (code/name/description/icon/active/sort_order) scoped to
--    a fundamental feature action. Variant codes are unique per feature action,
--    not globally, so the same code could theoretically be reused by a
--    different action in the future.
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.action_feature_variants (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    feature_action_id  UUID         NOT NULL REFERENCES public.feature_actions(id) ON DELETE RESTRICT,
    code               VARCHAR(50)  NOT NULL,
    name               VARCHAR(100) NOT NULL,
    description        VARCHAR(255),
    icon               VARCHAR(500),
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order         INTEGER      NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_action_feature_variant_code UNIQUE (feature_action_id, code)
);

CREATE INDEX IF NOT EXISTS idx_action_feature_variants_lookup
    ON public.action_feature_variants(feature_action_id, active, sort_order);

-- =============================================================================
-- 3. SUBSCRIPTION PLAN VARIANT LIMIT AND COST
--    Per-plan pricing/limit configuration for a specific variant. Mirrors the
--    shape of subscription_plan_limit_and_cost.
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.subscription_plan_variant_limit_and_cost (
    id                         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_plan_id       UUID        NOT NULL REFERENCES public.subscription_plans(id) ON DELETE RESTRICT,
    action_feature_variant_id  UUID        NOT NULL REFERENCES public.action_feature_variants(id) ON DELETE RESTRICT,
    member_credit_cost         BIGINT      NOT NULL DEFAULT 0 CHECK (member_credit_cost >= 0),
    actual_credit_cost         BIGINT      NOT NULL DEFAULT 0 CHECK (actual_credit_cost >= 0),
    limit_value                INTEGER,
    period_type                VARCHAR(20) NOT NULL DEFAULT 'DAY' CHECK (
        period_type IN ('DAY', 'MONTH', 'BILLING_CYCLE')
    ),
    apply_credit_after_limit   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_plan_variant UNIQUE (subscription_plan_id, action_feature_variant_id)
);

CREATE INDEX IF NOT EXISTS idx_splvac_plan_id
    ON public.subscription_plan_variant_limit_and_cost(subscription_plan_id);

CREATE INDEX IF NOT EXISTS idx_splvac_variant_id
    ON public.subscription_plan_variant_limit_and_cost(action_feature_variant_id);

-- =============================================================================
-- 4. EXTEND subscription_plan_limit_and_cost WITH VARIANT FLAGS
-- =============================================================================

ALTER TABLE public.subscription_plan_limit_and_cost
    ADD COLUMN IF NOT EXISTS variant_pricing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS variant_limits_enabled  BOOLEAN NOT NULL DEFAULT FALSE;

-- =============================================================================
-- 4b. EXTEND user_action_limits_tracker TO ALSO SUPPORT VARIANT-SCOPED LIMITS
--     Adds a second, nullable rule reference so a tracker row can be scoped
--     either to a feature-action-level rule (existing behavior, unchanged) or
--     to a variant-level rule (used only when variant_limits_enabled = TRUE).
--     Exactly one of the two rule columns must be populated.
-- =============================================================================

ALTER TABLE public.user_action_limits_tracker
    ALTER COLUMN subscription_plan_limit_and_cost_id DROP NOT NULL;

ALTER TABLE public.user_action_limits_tracker
    ADD COLUMN IF NOT EXISTS subscription_plan_variant_limit_and_cost_id UUID
        REFERENCES public.subscription_plan_variant_limit_and_cost(id) ON DELETE RESTRICT;

ALTER TABLE public.user_action_limits_tracker
    DROP CONSTRAINT IF EXISTS chk_action_limits_tracker_exactly_one_rule;

ALTER TABLE public.user_action_limits_tracker
    ADD CONSTRAINT chk_action_limits_tracker_exactly_one_rule CHECK (
        (subscription_plan_limit_and_cost_id IS NOT NULL AND subscription_plan_variant_limit_and_cost_id IS NULL)
        OR
        (subscription_plan_limit_and_cost_id IS NULL AND subscription_plan_variant_limit_and_cost_id IS NOT NULL)
    );

CREATE UNIQUE INDEX IF NOT EXISTS unique_action_variant_limit_tracker
    ON public.user_action_limits_tracker(user_id, subscription_plan_variant_limit_and_cost_id, period_start_date)
    WHERE subscription_plan_variant_limit_and_cost_id IS NOT NULL;

-- =============================================================================
-- 5. user_discovery_actions.action_variant_code
-- =============================================================================

ALTER TABLE public.user_discovery_actions
    ADD COLUMN IF NOT EXISTS action_variant_code VARCHAR(50);

ALTER TABLE public.user_discovery_actions
    DROP CONSTRAINT IF EXISTS user_discovery_actions_variant_requires_like_check;

ALTER TABLE public.user_discovery_actions
    ADD CONSTRAINT user_discovery_actions_variant_requires_like_check CHECK (
        action_type = 'LIKE' OR action_variant_code IS NULL
    );

-- =============================================================================
-- 6. SEED: LIKE gains variant support
-- =============================================================================

UPDATE public.feature_actions
SET has_variants = TRUE
WHERE code = 'LIKE';

INSERT INTO public.action_feature_variants
    (feature_action_id, code, name, description, icon, active, sort_order)
SELECT fa.id, v.code, v.name, v.description, v.icon, TRUE, v.sort_order
FROM public.feature_actions fa
CROSS JOIN (VALUES
    ('HEART',     'Heart',     'Strong feelings',  'https://cdn.qal.app/actions/heart.webp',     1),
    ('ROSE',      'Rose',      'Notice me',         'https://cdn.qal.app/actions/rose.webp',      2),
    ('BUNA',      'Buna',      'Let''s talk',       'https://cdn.qal.app/actions/buna.webp',      3),
    ('CHOCOLATE', 'Chocolate', 'I like you',        'https://cdn.qal.app/actions/chocolate.webp', 4),
    ('FLOWERS',   'Flowers',   'You''re special',   'https://cdn.qal.app/actions/flowers.webp',   5),
    ('RING',      'Ring',      'I''m serious',      'https://cdn.qal.app/actions/ring.webp',      6)
) AS v(code, name, description, icon, sort_order)
WHERE fa.code = 'LIKE'
ON CONFLICT (feature_action_id, code) DO NOTHING;

-- Enable variant pricing (not variant-specific limits) for every plan's existing
-- LIKE rule. All LIKE variants continue to share the existing LIKE usage limit;
-- only the credit cost becomes variant-specific.
UPDATE public.subscription_plan_limit_and_cost splac
SET variant_pricing_enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP
FROM public.feature_actions fa
WHERE splac.feature_action_id = fa.id
  AND fa.code = 'LIKE';

-- Seed per-plan variant pricing. HEART mirrors the plan's existing LIKE cost
-- (so a plain Like's price is unchanged by this migration). The other variants
-- get modest starting defaults that administrators can adjust via the existing
-- payment/pricing configuration mechanism.
INSERT INTO public.subscription_plan_variant_limit_and_cost
    (subscription_plan_id, action_feature_variant_id, member_credit_cost, actual_credit_cost,
     limit_value, period_type, apply_credit_after_limit)
SELECT
    splac.subscription_plan_id,
    afv.id,
    CASE afv.code
        WHEN 'HEART' THEN splac.member_credit_cost
        WHEN 'ROSE' THEN 10
        WHEN 'BUNA' THEN 15
        WHEN 'CHOCOLATE' THEN 20
        WHEN 'FLOWERS' THEN 25
        WHEN 'RING' THEN 50
        ELSE 0
    END AS member_credit_cost,
    CASE afv.code
        WHEN 'HEART' THEN splac.actual_credit_cost
        WHEN 'ROSE' THEN 10
        WHEN 'BUNA' THEN 15
        WHEN 'CHOCOLATE' THEN 20
        WHEN 'FLOWERS' THEN 25
        WHEN 'RING' THEN 50
        ELSE 0
    END AS actual_credit_cost,
    NULL AS limit_value,
    'DAY' AS period_type,
    FALSE AS apply_credit_after_limit
FROM public.subscription_plan_limit_and_cost splac
JOIN public.feature_actions fa ON fa.id = splac.feature_action_id AND fa.code = 'LIKE'
CROSS JOIN public.action_feature_variants afv
JOIN public.feature_actions fa2 ON fa2.id = afv.feature_action_id AND fa2.code = 'LIKE'
ON CONFLICT (subscription_plan_id, action_feature_variant_id) DO NOTHING;

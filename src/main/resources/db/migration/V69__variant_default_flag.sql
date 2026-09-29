-- =============================================================================
-- V69: Default flag on action_feature_variants
--
-- Lets the client identify each feature action's default variant (the one used
-- when the user performs the plain action, e.g. a plain LIKE without picking a
-- variant). Enforced at the DB level: at most one variant per feature action
-- may be flagged as default via a partial unique index.
-- =============================================================================

ALTER TABLE public.action_feature_variants
    ADD COLUMN IF NOT EXISTS is_default BOOLEAN NOT NULL DEFAULT FALSE;

-- Partial unique index guarantees at most one is_default = TRUE row
-- per feature_action_id.
CREATE UNIQUE INDEX IF NOT EXISTS unique_default_variant_per_action
    ON public.action_feature_variants(feature_action_id)
    WHERE is_default = TRUE;

-- Backfill: HEART is the canonical plain Like (mirrors the pre-variant LIKE
-- pricing seeded in V66).
UPDATE public.action_feature_variants afv
SET is_default = TRUE,
    updated_at = CURRENT_TIMESTAMP
FROM public.feature_actions fa
WHERE afv.feature_action_id = fa.id
  AND fa.code = 'LIKE'
  AND afv.code = 'HEART';

-- ============================================================================
-- V12__revisit_passes_and_discovery_exclusions.sql
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Extend reversed_reason on user_discovery_actions to include REVISIT_PASSES
--    The inline column constraint is auto-named by PostgreSQL.
-- ---------------------------------------------------------------------------
ALTER TABLE public.user_discovery_actions
    DROP CONSTRAINT IF EXISTS user_discovery_actions_reversed_reason_check;

ALTER TABLE public.user_discovery_actions
    ADD CONSTRAINT user_discovery_actions_reversed_reason_check CHECK (
        reversed_reason IN ('USER_REWIND', 'SYSTEM', 'ADMIN', 'REVISIT_PASSES')
    );

-- ---------------------------------------------------------------------------
-- 2. Index for efficient selection of active PASS actions ordered by recency
--    (used by the revisit-passes endpoint).
--    The existing idx_discovery_actions_actor_rewind_stack covers
--    (actor_user_id, status, created_at DESC) but does not filter on
--    action_type, so a partial index adds efficiency.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_discovery_actions_actor_pass_active
    ON public.user_discovery_actions(actor_user_id, created_at DESC)
    WHERE action_type = 'PASS' AND status = 'ACTIVE';

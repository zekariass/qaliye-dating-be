-- =============================================================================
-- V19: Allow BLOCK as a reversed_reason for user_discovery_actions.
-- Needed so blocking a user can reverse the caller's active LIKE/SUPERLIKE
-- actions on that target, letting the caller rediscover them after unblocking.
-- =============================================================================

ALTER TABLE public.user_discovery_actions
    DROP CONSTRAINT IF EXISTS user_discovery_actions_reversed_reason_check;

ALTER TABLE public.user_discovery_actions
    ADD CONSTRAINT user_discovery_actions_reversed_reason_check CHECK (
        reversed_reason IN ('USER_REWIND', 'SYSTEM', 'ADMIN', 'REVISIT_PASSES', 'BLOCK')
    );

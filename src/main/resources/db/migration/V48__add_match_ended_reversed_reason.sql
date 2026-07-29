ALTER TABLE public.user_discovery_actions
    DROP CONSTRAINT IF EXISTS user_discovery_actions_reversed_reason_check;

ALTER TABLE public.user_discovery_actions
    ADD CONSTRAINT user_discovery_actions_reversed_reason_check CHECK (
        reversed_reason IN ('USER_REWIND', 'SYSTEM', 'ADMIN', 'REVISIT_PASSES', 'BLOCK', 'MATCH_ENDED', 'ACCOUNT_DELETED')
    );

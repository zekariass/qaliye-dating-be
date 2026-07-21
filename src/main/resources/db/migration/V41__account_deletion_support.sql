-- =============================================================================
-- V41: Account deletion support.
-- Adds DELETED to the app_users status CHECK constraint so the application
-- can mark accounts as permanently deleted without violating the constraint.
-- Also adds ACCOUNT_DELETED to the matches end_reason CHECK constraint.
-- =============================================================================

ALTER TABLE public.app_users
    DROP CONSTRAINT IF EXISTS app_users_status_check;

ALTER TABLE public.app_users
    ADD CONSTRAINT app_users_status_check CHECK (
        status IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED', 'BANNED', 'DELETED')
    );

ALTER TABLE public.matches
    DROP CONSTRAINT IF EXISTS matches_end_reason_check;

ALTER TABLE public.matches
    ADD CONSTRAINT matches_end_reason_check CHECK (
        end_reason IN (
            'USER_UNMATCH',
            'CANCELLED_BY_REWIND',
            'BLOCKED',
            'ADMIN_ACTION',
            'ACCOUNT_DELETED'
        )
    );

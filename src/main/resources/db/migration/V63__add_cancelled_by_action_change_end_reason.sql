-- =============================================================================
-- V63: Add CANCELLED_BY_ACTION_CHANGE to matches end_reason CHECK constraint.
-- The SwipeActionService.reverseExistingAction() method ends a match with this
-- reason when a user changes their action (e.g. LIKE -> PASS or LIKE -> SUPERLIKE).
-- Without this value in the constraint, the UPDATE fails and the entire
-- transaction rolls back, preventing the action change from being persisted.
-- =============================================================================

ALTER TABLE public.matches
    DROP CONSTRAINT IF EXISTS matches_end_reason_check;

ALTER TABLE public.matches
    ADD CONSTRAINT matches_end_reason_check CHECK (
        end_reason IN (
            'USER_UNMATCH',
            'CANCELLED_BY_REWIND',
            'BLOCKED',
            'ADMIN_ACTION',
            'ACCOUNT_DELETED',
            'CANCELLED_BY_ACTION_CHANGE'
        )
    );

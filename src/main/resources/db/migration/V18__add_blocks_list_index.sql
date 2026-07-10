-- =============================================================================
-- V18: Add index for efficient cursor pagination of active blocks by blocker.
-- =============================================================================

CREATE INDEX IF NOT EXISTS idx_user_blocks_blocker_active_created
    ON public.user_blocks(blocker_user_id, created_at DESC, id DESC)
    WHERE status = 'ACTIVE';

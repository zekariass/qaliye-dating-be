-- V75: Video-call no-show auto-close
--
--  1. video_call_requests.status — add 'NO_SHOW': an ACCEPTED request where
--     one participant never joined within the join-grace window. Terminal;
--     frees the match's single live-request slot like any other end state.
--
--  2. Sweep index — find ACCEPTED requests past the join grace efficiently.
--
--  3. user_entitlement_credit_lots.credit_source_type — add
--     'VIDEO_CALL_REFUND' so a pro-rata no-show refund is backed by a lot
--     (keeps lot totals consistent with the central balance).

-- =============================================================================
-- 1. STATUS CHECK — add NO_SHOW
-- =============================================================================

ALTER TABLE public.video_call_requests
    DROP CONSTRAINT IF EXISTS video_call_requests_status_check;

ALTER TABLE public.video_call_requests
    ADD CONSTRAINT video_call_requests_status_check
        CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED',
                          'CANCELLED', 'EXPIRED', 'COMPLETED', 'NO_SHOW'));

-- =============================================================================
-- 2. SWEEP INDEX — ACCEPTED rows by accept time
-- =============================================================================

CREATE INDEX IF NOT EXISTS idx_video_call_requests_no_show
    ON public.video_call_requests (responded_at)
    WHERE status = 'ACCEPTED';

-- =============================================================================
-- 3. CREDIT SOURCE TYPE — VIDEO_CALL_REFUND
-- =============================================================================

ALTER TABLE public.user_entitlement_credit_lots
    DROP CONSTRAINT IF EXISTS user_entitlement_credit_lots_credit_source_type_check;

ALTER TABLE public.user_entitlement_credit_lots
    ADD CONSTRAINT user_entitlement_credit_lots_credit_source_type_check
        CHECK (
            credit_source_type IS NULL
            OR credit_source_type IN (
                'SUBSCRIPTION_ALLOWANCE',
                'CREDIT_PURCHASE',
                'PROMOTION',
                'ADMIN_ADJUSTMENT',
                'PROMOTION_REWARD',
                'MATCHMAKING_ESCROW_REFUND',
                'VIDEO_CALL_REFUND'
            )
        );

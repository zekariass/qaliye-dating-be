-- =============================================================================
-- V73: Video-call requests — maximum call duration cap
--
--  1. video_call_requests.call_deadline_at — anchored on the FIRST successful
--     join of either party (now + per-call-type cap). The Agora join token's
--     privilege expiry is clamped to this timestamp, so Agora's edge servers
--     drop both participants at the same instant — enforcement is server-side,
--     no client cooperation needed. NULL until anyone joins.
--  2. Partial index for the 30s deadline sweeper: ACCEPTED calls past their
--     deadline are transitioned to COMPLETED (+ ended_at) and both parties get
--     the *_CALL_ENDED_TIME_LIMIT alert.
--
--  Join timestamps already exist: requester_joined_at / responder_joined_at
--  (stamped on first join only — guarded UPDATE … IS NULL).
-- =============================================================================

ALTER TABLE public.video_call_requests
    ADD COLUMN IF NOT EXISTS call_deadline_at TIMESTAMPTZ NULL;

-- Deadline sweeper: ACCEPTED rows with an elapsed deadline.
CREATE INDEX IF NOT EXISTS idx_video_call_requests_deadline
    ON public.video_call_requests (call_deadline_at)
    WHERE status = 'ACCEPTED' AND call_deadline_at IS NOT NULL;

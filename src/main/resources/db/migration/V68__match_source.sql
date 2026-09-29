-- =============================================================================
-- V68: Match origin marker
--
-- Lets the client distinguish matches created through Blind Date from organic
-- discovery matches. Stored denormalized on matches so inbox / metadata reads
-- do not need to join blind_date_final_decisions or user_discovery_actions
-- (like reuse means action tags alone are unreliable).
-- =============================================================================

ALTER TABLE public.matches
    ADD COLUMN IF NOT EXISTS match_source VARCHAR(20) NOT NULL DEFAULT 'DISCOVERY';

ALTER TABLE public.matches
    DROP CONSTRAINT IF EXISTS matches_match_source_check;

ALTER TABLE public.matches
    ADD CONSTRAINT matches_match_source_check CHECK (
        match_source IN ('DISCOVERY', 'BLIND_DATE')
    );

-- Backfill: a blind date reveal wrote match_id with outcome 'MATCHED' only when
-- it actually inserted the match row. 'ALREADY_MATCHED' points at a pre-existing
-- discovery match and must stay 'DISCOVERY'.
UPDATE public.matches m
SET match_source = 'BLIND_DATE'
FROM public.blind_date_final_decisions fd
WHERE fd.match_id = m.id
  AND fd.outcome = 'MATCHED';

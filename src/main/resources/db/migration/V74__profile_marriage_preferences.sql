-- =============================================================================
-- V74: Profile — marriage and relationship preference fields
--
-- Adds five new nullable columns to the profiles table so users can express
-- their marriage timeline, stance on long-distance relationships, how
-- important family involvement is, how important religion is to them, and
-- whether they are willing to relocate.
--
-- All columns are nullable so existing profiles continue to work unchanged.
-- No existing data is modified.
-- =============================================================================

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS marriage_timeline VARCHAR(30) CHECK (
        marriage_timeline IS NULL
        OR marriage_timeline IN (
            'WITHIN_6_MONTHS', 'WITHIN_1_YEAR', '1_TO_2_YEARS',
            '2_TO_5_YEARS', 'MORE_THAN_5_YEARS', 'NOT_SURE'
        )
    ),
    ADD COLUMN IF NOT EXISTS long_distance_relationship VARCHAR(10) CHECK (
        long_distance_relationship IS NULL
        OR long_distance_relationship IN ('YES', 'NO', 'MAYBE')
    ),
    ADD COLUMN IF NOT EXISTS family_involvement VARCHAR(20) CHECK (
        family_involvement IS NULL
        OR family_involvement IN (
            'VERY_IMPORTANT', 'IMPORTANT', 'SOMEWHAT_IMPORTANT', 'NOT_IMPORTANT'
        )
    ),
    ADD COLUMN IF NOT EXISTS religion_importance VARCHAR(20) CHECK (
        religion_importance IS NULL
        OR religion_importance IN (
            'VERY_IMPORTANT', 'SOMEWHAT_IMPORTANT', 'NOT_IMPORTANT'
        )
    ),
    ADD COLUMN IF NOT EXISTS willing_to_relocate VARCHAR(10) CHECK (
        willing_to_relocate IS NULL
        OR willing_to_relocate IN ('YES', 'NO', 'MAYBE')
    );

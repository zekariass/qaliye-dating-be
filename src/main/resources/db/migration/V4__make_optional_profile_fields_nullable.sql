-- =============================================================================
-- V4: Make optional profile fields nullable
-- Fields that are not part of the minimum required profile (name, gender,
-- date_of_birth, residency_type, relationship_intention, and status flags)
-- must accept NULL so a profile can be created before onboarding is complete.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. smoking / drinking: drop the NOT NULL constraint that was inherited from
--    the original BOOLEAN columns and was never removed during the V3 type
--    conversion. The CHECK constraints already allow NULL.
-- -----------------------------------------------------------------------------
ALTER TABLE public.profiles
    ALTER COLUMN smoking  DROP NOT NULL,
    ALTER COLUMN drinking DROP NOT NULL;


-- -----------------------------------------------------------------------------
-- 2. interests / languages: were added in V3 as NOT NULL DEFAULT '{}'. Allow
--    NULL so an incomplete profile can omit them. The DEFAULT is kept so rows
--    inserted via plain SQL without an explicit value still get an empty array.
-- -----------------------------------------------------------------------------
ALTER TABLE public.profiles
    ALTER COLUMN interests  DROP NOT NULL,
    ALTER COLUMN languages  DROP NOT NULL;

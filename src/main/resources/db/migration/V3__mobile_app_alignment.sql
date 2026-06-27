-- =============================================================================
-- V3: Mobile App Alignment (Gap Analysis v1)
-- Aligns the database schema with the mobile app's profile and discovery screens.
-- All steps are ordered so each can run safely on the existing production data.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. profiles: add activity_level, interests, languages
-- -----------------------------------------------------------------------------
ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS activity_level VARCHAR(20) CHECK (
        activity_level IS NULL
        OR activity_level IN ('SEDENTARY', 'LIGHT', 'MODERATE', 'ACTIVE', 'VERY_ACTIVE')
    ),
    ADD COLUMN IF NOT EXISTS interests TEXT[] NOT NULL DEFAULT '{}'::TEXT[],
    ADD COLUMN IF NOT EXISTS languages TEXT[] NOT NULL DEFAULT '{}'::TEXT[];


-- -----------------------------------------------------------------------------
-- 2. profiles: make has_children nullable  (NULL = "Prefer not to say")
-- -----------------------------------------------------------------------------
ALTER TABLE public.profiles
    ALTER COLUMN has_children DROP NOT NULL,
    ALTER COLUMN has_children DROP DEFAULT;


-- -----------------------------------------------------------------------------
-- 3. profiles: change smoking and drinking from BOOLEAN to VARCHAR enum
--    Existing data is preserved: TRUE -> 'YES', FALSE -> 'NO'.
-- -----------------------------------------------------------------------------
ALTER TABLE public.profiles
    ALTER COLUMN smoking  DROP DEFAULT,
    ALTER COLUMN drinking DROP DEFAULT;

ALTER TABLE public.profiles
    ALTER COLUMN smoking  TYPE VARCHAR(20)
        USING CASE WHEN smoking::BOOLEAN  THEN 'YES' ELSE 'NO' END,
    ALTER COLUMN drinking TYPE VARCHAR(20)
        USING CASE WHEN drinking::BOOLEAN THEN 'YES' ELSE 'NO' END;

ALTER TABLE public.profiles
    ADD CONSTRAINT profiles_smoking_check CHECK (
        smoking IS NULL
        OR smoking IN ('NO', 'YES', 'OCCASIONALLY', 'TRYING_TO_QUIT')
    ),
    ADD CONSTRAINT profiles_drinking_check CHECK (
        drinking IS NULL
        OR drinking IN ('NO', 'SOCIALLY', 'OCCASIONALLY', 'YES')
    );


-- -----------------------------------------------------------------------------
-- 4. discovery_preferences: replace STANDARD/GLOBAL/INCOGNITO with PUBLIC/INCOGNITO
--    Drop the old constraint first so the UPDATE is not blocked, then migrate
--    existing rows, then add the new constraint and update the column default.
-- -----------------------------------------------------------------------------
ALTER TABLE public.discovery_preferences
    DROP CONSTRAINT IF EXISTS discovery_preferences_discovery_mode_check;

UPDATE public.discovery_preferences
    SET discovery_mode = 'PUBLIC'
    WHERE discovery_mode IN ('STANDARD', 'GLOBAL');

-- Fire the deferred constraint trigger now so the ALTER TABLE below is not
-- blocked by pending trigger events on the same table.
SET CONSTRAINTS public.validate_visible_profile_after_preference_change IMMEDIATE;

ALTER TABLE public.discovery_preferences
    ALTER COLUMN discovery_mode SET DEFAULT 'PUBLIC',
    ADD CONSTRAINT discovery_preferences_discovery_mode_check CHECK (
        discovery_mode IN ('PUBLIC', 'INCOGNITO')
    );

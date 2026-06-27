-- =============================================================================
-- V5: Split smoking/drinking into boolean flag + detail enum
-- V3 converted smoking/drinking from BOOLEAN to VARCHAR enum.
-- The profile API spec now requires a boolean flag (smoking/drinking) AND a
-- detail column (smoking_detail/drinking_detail). This migration:
--   1. Adds the detail columns and copies the current VARCHAR values into them.
--   2. Converts smoking/drinking back to BOOLEAN (NO -> false, others -> true).
--   3. Adds canonical CHECK constraints on the new detail columns.
--   4. Adds missing CHECK constraints from the profile-api spec (marital_status,
--      array cardinality limits).
-- Idempotent: safe to run on a DB where these changes were already applied
-- manually (steps 1-4 are guarded; step 5+ use DROP ... IF EXISTS).
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. Add smoking_detail and drinking_detail columns (always idempotent)
-- -----------------------------------------------------------------------------
ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS smoking_detail  VARCHAR(50),
    ADD COLUMN IF NOT EXISTS drinking_detail VARCHAR(50);


-- -----------------------------------------------------------------------------
-- 2-4. Copy values and convert types — skipped when already BOOLEAN
-- -----------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name   = 'profiles'
          AND column_name  = 'smoking'
          AND data_type    = 'character varying'
    ) THEN
        UPDATE public.profiles
            SET smoking_detail  = smoking
            WHERE smoking IS NOT NULL;

        UPDATE public.profiles
            SET drinking_detail = drinking
            WHERE drinking IS NOT NULL;

        -- The UPDATEs above queue deferred constraint triggers on profiles.
        -- Flush them now so the subsequent ALTER TABLE DDL is not blocked.
        SET CONSTRAINTS ALL IMMEDIATE;

        ALTER TABLE public.profiles
            DROP CONSTRAINT IF EXISTS profiles_smoking_check,
            DROP CONSTRAINT IF EXISTS profiles_drinking_check;

        ALTER TABLE public.profiles
            ALTER COLUMN smoking  DROP DEFAULT,
            ALTER COLUMN drinking DROP DEFAULT;

        EXECUTE '
            ALTER TABLE public.profiles
                ALTER COLUMN smoking  TYPE BOOLEAN
                    USING CASE WHEN smoking  IS NULL OR smoking  = ''NO'' THEN FALSE ELSE TRUE END,
                ALTER COLUMN drinking TYPE BOOLEAN
                    USING CASE WHEN drinking IS NULL OR drinking = ''NO'' THEN FALSE ELSE TRUE END
        ';
    END IF;
END $$;


-- -----------------------------------------------------------------------------
-- 5. CHECK constraints for the new detail columns (drop-then-add = idempotent)
-- -----------------------------------------------------------------------------
ALTER TABLE public.profiles
    DROP CONSTRAINT IF EXISTS chk_profiles_smoking_detail,
    DROP CONSTRAINT IF EXISTS chk_profiles_drinking_detail;

ALTER TABLE public.profiles
    ADD CONSTRAINT chk_profiles_smoking_detail CHECK (
        smoking_detail IS NULL
        OR smoking_detail IN ('NO', 'YES', 'OCCASIONALLY', 'TRYING_TO_QUIT')
    ),
    ADD CONSTRAINT chk_profiles_drinking_detail CHECK (
        drinking_detail IS NULL
        OR drinking_detail IN ('NO', 'SOCIALLY', 'OCCASIONALLY', 'YES')
    );


-- -----------------------------------------------------------------------------
-- 6. marital_status canonical values constraint
-- -----------------------------------------------------------------------------
ALTER TABLE public.profiles
    DROP CONSTRAINT IF EXISTS chk_profiles_marital_status;

ALTER TABLE public.profiles
    ADD CONSTRAINT chk_profiles_marital_status CHECK (
        marital_status IS NULL
        OR marital_status IN ('NEVER_MARRIED', 'DIVORCED', 'WIDOWED', 'SEPARATED')
    );


-- -----------------------------------------------------------------------------
-- 7. Lifestyle array cardinality limits
-- -----------------------------------------------------------------------------
ALTER TABLE public.profiles
    DROP CONSTRAINT IF EXISTS chk_profiles_lifestyle_array_limits;

ALTER TABLE public.profiles
    ADD CONSTRAINT chk_profiles_lifestyle_array_limits CHECK (
        (interests  IS NULL OR cardinality(interests)  <= 20) AND
        (languages  IS NULL OR cardinality(languages)  <= 20)
    );

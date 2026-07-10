-- =============================================================================
-- V15: Language/Ethnicity Catalog, Profile Column Migration,
--      and Discovery Preferences Extension
-- =============================================================================
--
-- Changes in this migration:
--
-- 1. Create public.languages catalog (UUID PK, country-scoped code)
-- 2. Create public.ethnicities catalog (UUID PK, country-scoped code)
-- 3. Seed both catalogs with Habesha-first data
-- 4. Add updated_at triggers on both catalog tables
-- 5. Add profiles.language_ids (UUID[]), profiles.ethnicity_ids (UUID[]),
--    profiles.ethnicity_other_text (TEXT)
-- 6. Migrate existing profiles.ethnicity (VARCHAR) → ethnicity_ids
-- 7. Migrate existing profiles.languages (TEXT[])  → language_ids
--    Unmapped text values are silently dropped (reported via comment below).
-- 8. Drop obsolete profiles.ethnicity and profiles.languages columns
-- 9. Add GIN indexes on new UUID array columns in profiles
-- 10.Extend discovery_preferences with:
--      location_mode, specific_country_codes, expand_search_when_limited,
--      has_children_preference, wants_children_preference, religion_preferences,
--      language_preference_ids, ethnicity_preference_ids, preferences_version
-- 11.Make discovery_preferences.max_age and max_distance_km nullable
--
-- Legacy-data migration notes:
--   profiles.ethnicity values mapped to catalog codes (country_code = ET):
--     AMHARA   → amhara   | OROMO    → oromo    | TIGRINYA → tigrayan
--     SOMALI   → somali   | SIDAMA   → sidama   | GURAGE   → gurage
--     WOLAYTA  → wolayta  | AFAR     → afar     | HADIYA   → hadiya
--     GAMO     → gamo     | OTHER    → unmapped  (ethnicity_other_text stays null)
--   profiles.languages text values mapped (case-insensitive, country_code = ET):
--     amharic / amhara → am | english          → en
--     oromo / afaan oromo → om | tigrinya / tigriniya → ti
--     somali → so | arabic → ar
--   Any text value not matching the above is silently dropped.
-- =============================================================================


-- =============================================================================
-- 1. LANGUAGES CATALOG
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.languages (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    code         TEXT        NOT NULL,
    country_code CHAR(2)     NOT NULL,
    name         TEXT        NOT NULL,
    native_name  TEXT,
    is_active    BOOLEAN     NOT NULL DEFAULT TRUE,
    sort_order   INTEGER     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT languages_code_country_unique UNIQUE (code, country_code),
    CONSTRAINT languages_code_lowercase_check
        CHECK (code = lower(code)),
    CONSTRAINT languages_country_code_uppercase_check
        CHECK (country_code = upper(country_code))
);

CREATE OR REPLACE FUNCTION public.set_languages_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END; $$;

CREATE TRIGGER set_languages_updated_at
BEFORE UPDATE ON public.languages
FOR EACH ROW EXECUTE FUNCTION public.set_languages_updated_at();

CREATE INDEX IF NOT EXISTS languages_active_country_sort_idx
    ON public.languages (is_active, country_code, sort_order, name);

CREATE INDEX IF NOT EXISTS languages_code_idx
    ON public.languages (code);


-- =============================================================================
-- 2. ETHNICITIES CATALOG
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.ethnicities (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    code         TEXT        NOT NULL,
    country_code CHAR(2)     NOT NULL,
    name         TEXT        NOT NULL,
    region       TEXT,
    is_active    BOOLEAN     NOT NULL DEFAULT TRUE,
    sort_order   INTEGER     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ethnicities_code_country_unique UNIQUE (code, country_code),
    CONSTRAINT ethnicities_code_lowercase_check
        CHECK (code = lower(code)),
    CONSTRAINT ethnicities_country_code_uppercase_check
        CHECK (country_code = upper(country_code))
);

CREATE OR REPLACE FUNCTION public.set_ethnicities_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END; $$;

CREATE TRIGGER set_ethnicities_updated_at
BEFORE UPDATE ON public.ethnicities
FOR EACH ROW EXECUTE FUNCTION public.set_ethnicities_updated_at();

CREATE INDEX IF NOT EXISTS ethnicities_active_country_sort_idx
    ON public.ethnicities (is_active, country_code, sort_order, name);

CREATE INDEX IF NOT EXISTS ethnicities_code_idx
    ON public.ethnicities (code);


-- =============================================================================
-- 3. SEED: LANGUAGES (Habesha-first)
-- =============================================================================

INSERT INTO public.languages (code, country_code, name, native_name, sort_order) VALUES
    -- Amharic
    ('am', 'ET', 'Amharic',        'አማርኛ',     1),
    ('am', 'ER', 'Amharic',        'አማርኛ',     2),
    ('am', 'GB', 'Amharic',        'አማርኛ',     3),
    ('am', 'US', 'Amharic',        'አማርኛ',     4),
    -- Afaan Oromo
    ('om', 'ET', 'Afaan Oromo',    'Afaan Oromoo', 5),
    ('om', 'KE', 'Afaan Oromo',    'Afaan Oromoo', 6),
    -- Tigrinya
    ('ti', 'ET', 'Tigrinya',       'ትግርኛ',     7),
    ('ti', 'ER', 'Tigrinya',       'ትግርኛ',     8),
    -- Somali
    ('so', 'ET', 'Somali',         'Soomaali',  9),
    ('so', 'SO', 'Somali',         'Soomaali',  10),
    ('so', 'KE', 'Somali',         'Soomaali',  11),
    ('so', 'GB', 'Somali',         'Soomaali',  12),
    ('so', 'US', 'Somali',         'Soomaali',  13),
    -- Arabic
    ('ar', 'ET', 'Arabic',         'العربية',   14),
    ('ar', 'ER', 'Arabic',         'العربية',   15),
    ('ar', 'SA', 'Arabic',         'العربية',   16),
    ('ar', 'AE', 'Arabic',         'العربية',   17),
    -- English
    ('en', 'ET', 'English',        'English',   18),
    ('en', 'ER', 'English',        'English',   19),
    ('en', 'GB', 'English',        'English',   20),
    ('en', 'US', 'English',        'English',   21),
    ('en', 'CA', 'English',        'English',   22),
    ('en', 'AU', 'English',        'English',   23),
    -- Afar
    ('aa', 'ET', 'Afar',           'Qafaraf',   24),
    ('aa', 'ER', 'Afar',           'Qafaraf',   25),
    -- Sidama
    ('sid','ET', 'Sidama',         'Sidaamu Afoo', 26),
    -- Wolaytta
    ('wal','ET', 'Wolaytta',       'Wolaitta',  27),
    -- Harari
    ('har','ET', 'Harari',         'ሐረሪ',       28),
    -- Tigre (Eritrea)
    ('tgr','ER', 'Tigre',          'ትግረ',       29)
ON CONFLICT (code, country_code) DO NOTHING;


-- =============================================================================
-- 4. SEED: ETHNICITIES (Habesha-first)
-- =============================================================================

INSERT INTO public.ethnicities (code, country_code, name, region, sort_order) VALUES
    -- Ethiopia
    ('amhara',   'ET', 'Amhara',             'East Africa', 1),
    ('oromo',    'ET', 'Oromo',              'East Africa', 2),
    ('tigrayan', 'ET', 'Tigrayan',           'East Africa', 3),
    ('gurage',   'ET', 'Gurage',             'East Africa', 4),
    ('afar',     'ET', 'Afar',               'East Africa', 5),
    ('sidama',   'ET', 'Sidama',             'East Africa', 6),
    ('somali',   'ET', 'Somali',             'East Africa', 7),
    ('harari',   'ET', 'Harari',             'East Africa', 8),
    ('wolayta',  'ET', 'Wolayta',            'East Africa', 9),
    ('hadiya',   'ET', 'Hadiya',             'East Africa', 10),
    ('gamo',     'ET', 'Gamo',               'East Africa', 11),
    -- Eritrea
    ('tigrinya', 'ER', 'Eritrean Tigrinya',  'East Africa', 12),
    ('tigre',    'ER', 'Tigre',              'East Africa', 13),
    ('afar',     'ER', 'Afar',               'East Africa', 14),
    -- Somalia
    ('somali',   'SO', 'Somali',             'East Africa', 15),
    -- Kenya
    ('somali',   'KE', 'Somali',             'East Africa', 16),
    ('oromo',    'KE', 'Oromo',              'East Africa', 17)
ON CONFLICT (code, country_code) DO NOTHING;


-- =============================================================================
-- 5. ADD NEW COLUMNS TO profiles
-- =============================================================================

-- Flush deferred constraint triggers before DDL on profiles
SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS language_ids     UUID[]  NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS ethnicity_ids    UUID[]  NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS ethnicity_other_text TEXT
        CHECK (ethnicity_other_text IS NULL OR char_length(trim(ethnicity_other_text)) <= 200);


-- =============================================================================
-- 6. MIGRATE: profiles.ethnicity → ethnicity_ids
-- =============================================================================

UPDATE public.profiles p
SET ethnicity_ids = ARRAY(
    SELECT e.id
    FROM public.ethnicities e
    WHERE e.country_code = 'ET'
      AND e.code = CASE upper(p.ethnicity)
          WHEN 'AMHARA'   THEN 'amhara'
          WHEN 'OROMO'    THEN 'oromo'
          WHEN 'TIGRINYA' THEN 'tigrayan'
          WHEN 'SOMALI'   THEN 'somali'
          WHEN 'SIDAMA'   THEN 'sidama'
          WHEN 'GURAGE'   THEN 'gurage'
          WHEN 'WOLAYTA'  THEN 'wolayta'
          WHEN 'AFAR'     THEN 'afar'
          WHEN 'HADIYA'   THEN 'hadiya'
          WHEN 'GAMO'     THEN 'gamo'
          ELSE NULL
      END
    LIMIT 1
)
WHERE p.ethnicity IS NOT NULL
  AND upper(p.ethnicity) != 'OTHER';


-- =============================================================================
-- 7. MIGRATE: profiles.languages (TEXT[]) → language_ids (UUID[])
-- =============================================================================

UPDATE public.profiles p
SET language_ids = (
    SELECT ARRAY_AGG(DISTINCT l.id ORDER BY l.id)
    FROM unnest(p.languages) AS raw_lang
    JOIN public.languages l
        ON l.country_code = 'ET'
       AND l.code = CASE lower(trim(raw_lang))
           WHEN 'amharic'       THEN 'am'
           WHEN 'amhara'        THEN 'am'
           WHEN 'english'       THEN 'en'
           WHEN 'oromo'         THEN 'om'
           WHEN 'afaan oromo'   THEN 'om'
           WHEN 'afaan_oromo'   THEN 'om'
           WHEN 'tigrinya'      THEN 'ti'
           WHEN 'tigriniya'     THEN 'ti'
           WHEN 'somali'        THEN 'so'
           WHEN 'arabic'        THEN 'ar'
           ELSE NULL
       END
    WHERE l.id IS NOT NULL
)
WHERE array_length(p.languages, 1) > 0;

-- Ensure no NULLs after the update (profile had languages but none mapped)
UPDATE public.profiles SET language_ids = '{}' WHERE language_ids IS NULL;


-- =============================================================================
-- 8. DROP OLD COLUMNS
-- =============================================================================

-- Drop the array-cardinality constraint that references languages column
ALTER TABLE public.profiles
    DROP CONSTRAINT IF EXISTS chk_profiles_lifestyle_array_limits;

-- Re-add constraint for interests only
ALTER TABLE public.profiles
    ADD CONSTRAINT chk_profiles_lifestyle_array_limits CHECK (
        interests IS NULL OR cardinality(interests) <= 20
    );

-- Drop old columns
ALTER TABLE public.profiles
    DROP COLUMN IF EXISTS ethnicity,
    DROP COLUMN IF EXISTS languages;

-- Add cardinality constraints for the new UUID array columns
ALTER TABLE public.profiles
    ADD CONSTRAINT chk_profiles_language_ids_limit
        CHECK (cardinality(language_ids) <= 20),
    ADD CONSTRAINT chk_profiles_ethnicity_ids_limit
        CHECK (cardinality(ethnicity_ids) <= 10);


-- =============================================================================
-- 9. GIN INDEXES ON NEW PROFILE ARRAYS
-- =============================================================================

CREATE INDEX IF NOT EXISTS profiles_language_ids_gin_idx
    ON public.profiles USING GIN (language_ids);

CREATE INDEX IF NOT EXISTS profiles_ethnicity_ids_gin_idx
    ON public.profiles USING GIN (ethnicity_ids);


-- =============================================================================
-- 10. EXTEND discovery_preferences
-- =============================================================================

-- Make max_age and max_distance_km nullable
ALTER TABLE public.discovery_preferences
    DROP CONSTRAINT IF EXISTS check_discovery_age_range,
    DROP CONSTRAINT IF EXISTS discovery_preferences_max_age_check,
    DROP CONSTRAINT IF EXISTS discovery_preferences_max_distance_km_check;

ALTER TABLE public.discovery_preferences
    ALTER COLUMN max_age          DROP NOT NULL,
    ALTER COLUMN max_distance_km  DROP NOT NULL;

ALTER TABLE public.discovery_preferences
    ADD CONSTRAINT check_discovery_age_range
        CHECK (max_age IS NULL OR min_age <= max_age),
    ADD CONSTRAINT discovery_preferences_max_age_check
        CHECK (max_age IS NULL OR max_age <= 120),
    ADD CONSTRAINT discovery_preferences_max_distance_km_check
        CHECK (max_distance_km IS NULL OR max_distance_km > 0);

-- New columns
ALTER TABLE public.discovery_preferences
    ADD COLUMN IF NOT EXISTS location_mode
        TEXT NOT NULL DEFAULT 'anywhere'
        CHECK (location_mode IN ('nearby', 'diaspora', 'specific_countries', 'anywhere')),
    ADD COLUMN IF NOT EXISTS specific_country_codes  TEXT[],
    ADD COLUMN IF NOT EXISTS expand_search_when_limited
        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS has_children_preference
        TEXT NOT NULL DEFAULT 'any'
        CHECK (has_children_preference IN ('any', 'yes', 'no')),
    ADD COLUMN IF NOT EXISTS wants_children_preference
        TEXT NOT NULL DEFAULT 'any'
        CHECK (wants_children_preference IN ('any', 'yes', 'no', 'not_sure', 'open_to_discussion')),
    ADD COLUMN IF NOT EXISTS religion_preferences    TEXT[],
    ADD COLUMN IF NOT EXISTS language_preference_ids UUID[]  NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS ethnicity_preference_ids UUID[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS preferences_version     INTEGER NOT NULL DEFAULT 1;

-- Migrate existing location intent from preferred_residency_types → location_mode
UPDATE public.discovery_preferences
SET location_mode = CASE
    WHEN preferred_residency_types @> ARRAY['DIASPORA']::TEXT[]
         AND NOT (preferred_residency_types @> ARRAY['ETHIOPIA']::TEXT[])
         AND NOT (preferred_residency_types @> ARRAY['ERITREA']::TEXT[])
        THEN 'diaspora'
    ELSE 'anywhere'
END;

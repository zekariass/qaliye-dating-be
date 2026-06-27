-- Move discovery_mode from discovery_preferences to profiles.
-- Values: PUBLIC (default) | INCOGNITO

-- 1. Add column and constraint to profiles FIRST (before any UPDATE that could fire
--    deferred constraint triggers on this table).
ALTER TABLE public.profiles
    ADD COLUMN discovery_mode VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
    ADD CONSTRAINT profiles_discovery_mode_check
    CHECK (discovery_mode IN ('PUBLIC', 'INCOGNITO'));

-- 2. Backfill from existing discovery_preferences rows.
--    Any constraint trigger events are now pending on profiles, but we are done
--    altering profiles itself.
UPDATE public.profiles p
SET discovery_mode = COALESCE(
    (SELECT dp.discovery_mode FROM public.discovery_preferences dp WHERE dp.user_id = p.user_id),
    'PUBLIC'
);

-- 3. Drop the column from discovery_preferences.
ALTER TABLE public.discovery_preferences
    DROP COLUMN IF EXISTS discovery_mode;

-- =============================================================================
-- V16: Remove open_to_long_distance and open_to_relocation from
--      discovery_preferences. These fields are superseded by location_mode.
-- =============================================================================

ALTER TABLE public.discovery_preferences
    DROP COLUMN IF EXISTS open_to_long_distance,
    DROP COLUMN IF EXISTS open_to_relocation;

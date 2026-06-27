-- ============================================================================
-- V10__add_activity_status_visibility.sql
-- ============================================================================

ALTER TABLE public.app_users
    ADD COLUMN show_activity_status BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN public.app_users.show_activity_status IS
    'Whether other authorized users may see this user''s derived activity status.';

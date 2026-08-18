-- Add super_message_enabled column to user_notification_preferences
ALTER TABLE public.user_notification_preferences
    ADD COLUMN IF NOT EXISTS super_message_enabled BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN public.user_notification_preferences.super_message_enabled IS
    'Whether the user wants to receive push notifications when they receive a Super Message';

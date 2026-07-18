-- Add superlike_notifications_enabled column to user_notification_preferences
ALTER TABLE public.user_notification_preferences
    ADD COLUMN IF NOT EXISTS superlike_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN public.user_notification_preferences.superlike_notifications_enabled IS
    'Whether the user wants to receive push notifications when someone superlikes their profile';

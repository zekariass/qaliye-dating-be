-- Add SUPER_MESSAGE_RECEIVED to the notification_type CHECK constraint
ALTER TABLE notification_outbox_events
    DROP CONSTRAINT IF EXISTS notification_outbox_events_notification_type_check;

ALTER TABLE notification_outbox_events
    ADD CONSTRAINT notification_outbox_events_notification_type_check
    CHECK (notification_type IN ('CHAT_MESSAGE', 'MATCH_CREATED', 'LIKE_RECEIVED',
                                 'SUPERLIKE_RECEIVED', 'SUPER_MESSAGE_RECEIVED',
                                 'ACCOUNT_ALERT', 'MARKETING'));

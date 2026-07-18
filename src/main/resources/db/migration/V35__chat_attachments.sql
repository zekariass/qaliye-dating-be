-- ============================================================================
-- V35__chat_attachments.sql
-- Adds image and voice attachment support to the existing user-to-user chat.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Relax messages.body constraint to allow attachment-only messages
-- ---------------------------------------------------------------------------
ALTER TABLE public.messages
    ALTER COLUMN body DROP NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. Chat attachments table
-- ---------------------------------------------------------------------------
CREATE TABLE public.chat_attachments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id      UUID NOT NULL REFERENCES public.messages(id) ON DELETE CASCADE,
    attachment_type TEXT    NOT NULL CHECK (attachment_type IN ('IMAGE', 'VOICE')),
    file_name       TEXT    NOT NULL,
    content_type    TEXT    NOT NULL,
    file_size_bytes BIGINT  NOT NULL CHECK (file_size_bytes > 0),
    storage_bucket  TEXT    NOT NULL,
    storage_path    TEXT    NOT NULL,
    duration_ms     BIGINT  CHECK (duration_ms IS NULL OR duration_ms > 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_chat_attachment_duration CHECK (
        (attachment_type = 'IMAGE' AND duration_ms IS NULL)
        OR
        (attachment_type = 'VOICE' AND duration_ms IS NOT NULL AND duration_ms > 0)
    )
);

CREATE INDEX idx_chat_attachments_message
    ON public.chat_attachments(message_id);

ALTER TABLE public.chat_attachments ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.chat_attachments
FROM anon, authenticated;

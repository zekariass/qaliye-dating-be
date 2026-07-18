-- ============================================================================
-- V36__relax_message_content_constraint.sql
-- Relax check_message_content_by_type to allow TEXT messages with a null body.
-- V35 dropped NOT NULL on messages.body for attachment-only messages but did
-- not update this check constraint, causing DataIntegrityViolationException
-- whenever a message is sent with files but no body text.
-- Application-layer validation in MessageCommandService already enforces that
-- every message has either a body or at least one attachment.
-- ============================================================================

ALTER TABLE public.messages
    DROP CONSTRAINT IF EXISTS check_message_content_by_type;

-- Re-add a relaxed version: body may only be NULL when message_type is known
-- (TEXT / ICEBREAKER / PROMPT_REPLY), and when body is present it must be
-- a non-empty, non-whitespace-only string not exceeding 10 000 characters.
ALTER TABLE public.messages
    ADD CONSTRAINT check_message_content_by_type CHECK (
        body IS NULL
        OR (
            NULLIF(BTRIM(body), '') IS NOT NULL
            AND char_length(body) <= 10000
        )
    );

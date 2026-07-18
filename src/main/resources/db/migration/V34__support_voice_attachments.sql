-- ============================================================
-- QALIYE SUPPORT CHAT — VOICE MESSAGE ATTACHMENTS
-- Migration V34 — forward-only, additive.
--
-- Adds attachment_kind and duration_ms columns to
-- support_attachments and updates the metadata-insert
-- helper to accept these fields from the JSON payload.
-- ============================================================

-- 1. Add columns
ALTER TABLE public.support_attachments
    ADD COLUMN IF NOT EXISTS attachment_kind VARCHAR(20)
        CHECK (attachment_kind IN ('IMAGE', 'DOCUMENT', 'TEXT', 'VOICE', 'OTHER')),
    ADD COLUMN IF NOT EXISTS duration_ms BIGINT
        CHECK (duration_ms IS NULL OR duration_ms > 0);

-- 2. Backfill existing rows with a derived kind
UPDATE public.support_attachments
SET attachment_kind = CASE
    WHEN content_type LIKE 'audio/%' THEN 'VOICE'
    WHEN content_type LIKE 'image/%' THEN 'IMAGE'
    WHEN content_type = 'application/pdf' THEN 'DOCUMENT'
    WHEN content_type LIKE 'text/%' THEN 'TEXT'
    ELSE 'OTHER'
END
WHERE attachment_kind IS NULL;

-- 3. Recreate insert_support_attachment_metadata to accept
--    optional attachment_kind and duration_ms from JSON.
CREATE OR REPLACE FUNCTION public.insert_support_attachment_metadata(
    p_message_id      UUID,
    p_attachments     JSONB
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    attachment_item   JSONB;
    v_bucket          TEXT;
    v_path            TEXT;
    v_file_name       TEXT;
    v_content_type    TEXT;
    v_size            BIGINT;
    v_kind            TEXT;
    v_duration        BIGINT;
BEGIN
    IF p_attachments IS NULL THEN
        p_attachments := '[]'::JSONB;
    END IF;

    IF jsonb_typeof(p_attachments) <> 'array' THEN
        RAISE EXCEPTION 'Attachments must be a JSON array';
    END IF;

    IF jsonb_array_length(p_attachments) > 10 THEN
        RAISE EXCEPTION 'A message may contain at most 10 attachments';
    END IF;

    FOR attachment_item IN
        SELECT value
        FROM jsonb_array_elements(p_attachments)
    LOOP
        IF jsonb_typeof(attachment_item) <> 'object' THEN
            RAISE EXCEPTION 'Every attachment entry must be a JSON object';
        END IF;

        IF NOT (
            attachment_item ? 'storage_bucket'
            AND attachment_item ? 'storage_path'
            AND attachment_item ? 'file_name'
            AND attachment_item ? 'content_type'
            AND attachment_item ? 'file_size_bytes'
        ) THEN
            RAISE EXCEPTION 'Attachment metadata is missing required fields';
        END IF;

        v_bucket       := NULLIF(BTRIM(attachment_item ->> 'storage_bucket'), '');
        v_path         := NULLIF(BTRIM(attachment_item ->> 'storage_path'), '');
        v_file_name    := NULLIF(BTRIM(attachment_item ->> 'file_name'), '');
        v_content_type := NULLIF(BTRIM(attachment_item ->> 'content_type'), '');

        BEGIN
            v_size := (attachment_item ->> 'file_size_bytes')::BIGINT;
        EXCEPTION
            WHEN invalid_text_representation OR numeric_value_out_of_range THEN
                RAISE EXCEPTION 'Attachment file_size_bytes must be an integer';
        END;

        v_kind := NULLIF(BTRIM(attachment_item ->> 'attachment_kind'), '');

        IF v_kind IS NOT NULL AND v_kind NOT IN ('IMAGE', 'DOCUMENT', 'TEXT', 'VOICE', 'OTHER') THEN
            RAISE EXCEPTION 'Invalid attachment_kind: %', v_kind;
        END IF;

        BEGIN
            v_duration := NULLIF(attachment_item ->> 'duration_ms', '')::BIGINT;
        EXCEPTION
            WHEN invalid_text_representation OR numeric_value_out_of_range THEN
                v_duration := NULL;
        END;

        IF v_kind = 'VOICE' THEN
            IF v_duration IS NULL OR v_duration <= 0 THEN
                RAISE EXCEPTION 'Voice attachments must have a positive duration_ms';
            END IF;
        ELSIF v_kind IS NOT NULL AND v_kind <> 'VOICE' AND v_duration IS NOT NULL THEN
            v_duration := NULL;
        END IF;

        INSERT INTO public.support_attachments (
            message_id,
            storage_bucket,
            storage_path,
            file_name,
            content_type,
            file_size_bytes,
            attachment_kind,
            duration_ms
        )
        VALUES (
            p_message_id,
            v_bucket,
            v_path,
            v_file_name,
            v_content_type,
            v_size,
            v_kind,
            v_duration
        );
    END LOOP;
END;
$$;

REVOKE ALL
ON FUNCTION public.insert_support_attachment_metadata(UUID, JSONB)
FROM PUBLIC, anon, authenticated;

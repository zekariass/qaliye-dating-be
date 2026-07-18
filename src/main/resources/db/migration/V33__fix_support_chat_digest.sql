-- ============================================================
-- QALIYE SUPPORT CHAT — FIX digest() SCHEMA RESOLUTION
-- Migration V33 — forward-only, additive.
--
-- Problem: V32 called public.digest() but pgcrypto is installed
-- in the extensions schema (Supabase default), not public.
-- The SECURITY DEFINER functions use SET search_path = '' so
-- unqualified digest() cannot be found either.
--
-- Fix: Create a public.sha256_hex(text) wrapper as a plpgsql
-- SECURITY DEFINER function that dynamically resolves the schema
-- where pgcrypto's digest() lives at creation time.  plpgsql
-- functions are never inlined, so the wrapper's own search_path
-- applies rather than the caller's empty one.
-- ============================================================

-- 1. Wrapper: SHA-256 hex digest of a text value.
--    plpgsql (not inlineable) + SECURITY DEFINER + search_path
--    set to the schema where digest() actually lives.
DO $_$
DECLARE
    v_schema text;
BEGIN
    SELECT n.nspname
    INTO v_schema
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE p.proname = 'digest'
    LIMIT 1;

    IF v_schema IS NULL THEN
        RAISE EXCEPTION 'pgcrypto digest() function not found in any schema';
    END IF;

    EXECUTE format(
        $f$CREATE OR REPLACE FUNCTION public.sha256_hex(input text)
        RETURNS text
        LANGUAGE plpgsql
        IMMUTABLE
        PARALLEL SAFE
        SECURITY DEFINER
        SET search_path = %I
        AS $body$
        BEGIN
            RETURN encode(digest(convert_to(input, 'UTF8'), 'sha256'), 'hex');
        END;
        $body$$f$,
        v_schema
    );
END;
$_$;

REVOKE ALL
ON FUNCTION public.sha256_hex(text)
FROM PUBLIC, anon, authenticated;

GRANT EXECUTE
ON FUNCTION public.sha256_hex(text)
TO service_role;


-- 2. Recreate append_support_user_message using public.sha256_hex()

CREATE OR REPLACE FUNCTION public.append_support_user_message(
    p_user_id                UUID,
    p_client_message_id      UUID,
    p_body                   TEXT,
    p_attachments            JSONB DEFAULT '[]'::JSONB,
    p_metadata               JSONB DEFAULT '{}'::JSONB
)
RETURNS public.support_messages
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    conversation_row         public.support_conversations%ROWTYPE;
    inserted_message         public.support_messages%ROWTYPE;
    normalized_body          TEXT;
    normalized_attachments   JSONB;
    request_fingerprint      CHAR(64);
    allocated_sequence       BIGINT;
BEGIN
    IF p_user_id IS NULL OR p_client_message_id IS NULL THEN
        RAISE EXCEPTION 'User ID and client message ID are required';
    END IF;

    IF p_metadata IS NULL OR jsonb_typeof(p_metadata) <> 'object' THEN
        RAISE EXCEPTION 'Message metadata must be a JSON object';
    END IF;

    normalized_attachments := COALESCE(p_attachments, '[]'::JSONB);

    IF jsonb_typeof(normalized_attachments) <> 'array' THEN
        RAISE EXCEPTION 'Attachments must be a JSON array';
    END IF;

    IF jsonb_array_length(normalized_attachments) > 10 THEN
        RAISE EXCEPTION 'A message may contain at most 10 attachments';
    END IF;

    normalized_body := NULLIF(BTRIM(p_body), '');

    IF normalized_body IS NULL
       AND jsonb_array_length(normalized_attachments) = 0 THEN
        RAISE EXCEPTION
            'A support message must contain a body or at least one attachment';
    END IF;

    IF normalized_body IS NOT NULL
       AND char_length(normalized_body) > 10000 THEN
        RAISE EXCEPTION 'Message body exceeds 10000 characters';
    END IF;

    SELECT *
    INTO conversation_row
    FROM public.support_conversations AS c
    WHERE c.user_id = p_user_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Default support conversation was not provisioned for this user';
    END IF;

    request_fingerprint := public.sha256_hex(
        jsonb_build_object(
            'sender_type', 'USER',
            'body', normalized_body,
            'attachments', normalized_attachments,
            'metadata', p_metadata
        )::TEXT
    );

    SELECT *
    INTO inserted_message
    FROM public.support_messages AS m
    WHERE m.conversation_id = conversation_row.id
      AND m.sender_user_id = p_user_id
      AND m.client_message_id = p_client_message_id;

    IF FOUND THEN
        IF inserted_message.request_hash <> request_fingerprint THEN
            RAISE EXCEPTION
                'Idempotency conflict: client message ID was reused with a different payload';
        END IF;

        RETURN inserted_message;
    END IF;

    allocated_sequence := conversation_row.next_public_sequence;

    INSERT INTO public.support_messages (
        conversation_id,
        sequence_number,
        sender_type,
        sender_user_id,
        body,
        client_message_id,
        request_hash,
        metadata
    )
    VALUES (
        conversation_row.id,
        allocated_sequence,
        'USER',
        p_user_id,
        normalized_body,
        p_client_message_id,
        request_fingerprint,
        p_metadata
    )
    RETURNING *
    INTO inserted_message;

    PERFORM public.insert_support_attachment_metadata(
        inserted_message.id,
        normalized_attachments
    );

    UPDATE public.support_conversations
    SET
        next_public_sequence = allocated_sequence + 1,
        status = 'WAITING_STAFF',
        last_public_message_at = inserted_message.created_at,
        last_public_message_sender_type = 'USER',
        waiting_since = CASE
            WHEN conversation_row.status = 'WAITING_STAFF'
                THEN conversation_row.waiting_since
            ELSE inserted_message.created_at
        END,
        last_activity_at = inserted_message.created_at,
        closed_at = NULL,
        closed_by_app_user_id = NULL,
        closed_by_type = NULL
    WHERE id = conversation_row.id;

    RETURN inserted_message;
END;
$$;

REVOKE ALL
ON FUNCTION public.append_support_user_message(UUID, UUID, TEXT, JSONB, JSONB)
FROM PUBLIC, anon, authenticated;

GRANT EXECUTE
ON FUNCTION public.append_support_user_message(UUID, UUID, TEXT, JSONB, JSONB)
TO service_role;


-- 3. Recreate append_support_staff_message using public.sha256_hex()

CREATE OR REPLACE FUNCTION public.append_support_staff_message(
    p_conversation_id        UUID,
    p_staff_user_id          UUID,
    p_client_message_id      UUID,
    p_body                   TEXT,
    p_attachments            JSONB DEFAULT '[]'::JSONB,
    p_metadata               JSONB DEFAULT '{}'::JSONB
)
RETURNS public.support_messages
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    conversation_row         public.support_conversations%ROWTYPE;
    inserted_message         public.support_messages%ROWTYPE;
    normalized_body          TEXT;
    normalized_attachments   JSONB;
    request_fingerprint      CHAR(64);
    allocated_sequence       BIGINT;
BEGIN
    IF p_conversation_id IS NULL OR p_client_message_id IS NULL THEN
        RAISE EXCEPTION 'Conversation ID and client message ID are required';
    END IF;

    PERFORM public.assert_support_staff_role(p_staff_user_id);

    IF p_metadata IS NULL OR jsonb_typeof(p_metadata) <> 'object' THEN
        RAISE EXCEPTION 'Message metadata must be a JSON object';
    END IF;

    normalized_attachments := COALESCE(p_attachments, '[]'::JSONB);

    IF jsonb_typeof(normalized_attachments) <> 'array' THEN
        RAISE EXCEPTION 'Attachments must be a JSON array';
    END IF;

    IF jsonb_array_length(normalized_attachments) > 10 THEN
        RAISE EXCEPTION 'A message may contain at most 10 attachments';
    END IF;

    normalized_body := NULLIF(BTRIM(p_body), '');

    IF normalized_body IS NULL
       AND jsonb_array_length(normalized_attachments) = 0 THEN
        RAISE EXCEPTION
            'A support message must contain a body or at least one attachment';
    END IF;

    IF normalized_body IS NOT NULL
       AND char_length(normalized_body) > 10000 THEN
        RAISE EXCEPTION 'Message body exceeds 10000 characters';
    END IF;

    SELECT *
    INTO conversation_row
    FROM public.support_conversations AS c
    WHERE c.id = p_conversation_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Support conversation not found';
    END IF;

    request_fingerprint := public.sha256_hex(
        jsonb_build_object(
            'sender_type', 'STAFF',
            'body', normalized_body,
            'attachments', normalized_attachments,
            'metadata', p_metadata
        )::TEXT
    );

    SELECT *
    INTO inserted_message
    FROM public.support_messages AS m
    WHERE m.conversation_id = p_conversation_id
      AND m.sender_user_id = p_staff_user_id
      AND m.client_message_id = p_client_message_id;

    IF FOUND THEN
        IF inserted_message.request_hash <> request_fingerprint THEN
            RAISE EXCEPTION
                'Idempotency conflict: client message ID was reused with a different payload';
        END IF;

        RETURN inserted_message;
    END IF;

    IF conversation_row.status = 'IDLE' THEN
        RAISE EXCEPTION
            'Staff cannot send the first public message in an idle conversation';
    END IF;

    IF conversation_row.status = 'CLOSED' THEN
        RAISE EXCEPTION
            'Conversation is closed; reopen it explicitly before replying';
    END IF;

    allocated_sequence := conversation_row.next_public_sequence;

    INSERT INTO public.support_messages (
        conversation_id,
        sequence_number,
        sender_type,
        sender_user_id,
        body,
        client_message_id,
        request_hash,
        metadata
    )
    VALUES (
        p_conversation_id,
        allocated_sequence,
        'STAFF',
        p_staff_user_id,
        normalized_body,
        p_client_message_id,
        request_fingerprint,
        p_metadata
    )
    RETURNING *
    INTO inserted_message;

    PERFORM public.insert_support_attachment_metadata(
        inserted_message.id,
        normalized_attachments
    );

    UPDATE public.support_conversations
    SET
        next_public_sequence = allocated_sequence + 1,
        status = 'WAITING_USER',
        assigned_staff_user_id = COALESCE(
            assigned_staff_user_id,
            p_staff_user_id
        ),
        last_public_message_at = inserted_message.created_at,
        last_public_message_sender_type = 'STAFF',
        waiting_since = NULL,
        first_staff_response_at = COALESCE(
            first_staff_response_at,
            inserted_message.created_at
        ),
        last_activity_at = inserted_message.created_at
    WHERE id = p_conversation_id;

    RETURN inserted_message;
END;
$$;

REVOKE ALL
ON FUNCTION public.append_support_staff_message(UUID, UUID, UUID, TEXT, JSONB, JSONB)
FROM PUBLIC, anon, authenticated;

GRANT EXECUTE
ON FUNCTION public.append_support_staff_message(UUID, UUID, UUID, TEXT, JSONB, JSONB)
TO service_role;


-- 4. Recreate append_support_internal_note using public.sha256_hex()

CREATE OR REPLACE FUNCTION public.append_support_internal_note(
    p_conversation_id        UUID,
    p_staff_user_id          UUID,
    p_client_note_id         UUID,
    p_body                   TEXT,
    p_metadata               JSONB DEFAULT '{}'::JSONB
)
RETURNS public.support_internal_notes
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    conversation_row         public.support_conversations%ROWTYPE;
    inserted_note            public.support_internal_notes%ROWTYPE;
    normalized_body          TEXT;
    request_fingerprint      CHAR(64);
BEGIN
    IF p_conversation_id IS NULL OR p_client_note_id IS NULL THEN
        RAISE EXCEPTION 'Conversation ID and client note ID are required';
    END IF;

    PERFORM public.assert_support_staff_role(p_staff_user_id);

    normalized_body := NULLIF(BTRIM(p_body), '');

    IF normalized_body IS NULL THEN
        RAISE EXCEPTION 'Internal note body is required';
    END IF;

    IF char_length(normalized_body) > 10000 THEN
        RAISE EXCEPTION 'Internal note exceeds 10000 characters';
    END IF;

    IF p_metadata IS NULL OR jsonb_typeof(p_metadata) <> 'object' THEN
        RAISE EXCEPTION 'Internal-note metadata must be a JSON object';
    END IF;

    SELECT *
    INTO conversation_row
    FROM public.support_conversations AS c
    WHERE c.id = p_conversation_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Support conversation not found';
    END IF;

    IF conversation_row.status = 'IDLE' THEN
        RAISE EXCEPTION 'Cannot add an internal note to an idle conversation';
    END IF;

    request_fingerprint := public.sha256_hex(
        jsonb_build_object(
            'body', normalized_body,
            'metadata', p_metadata
        )::TEXT
    );

    SELECT *
    INTO inserted_note
    FROM public.support_internal_notes AS n
    WHERE n.conversation_id = p_conversation_id
      AND n.staff_user_id = p_staff_user_id
      AND n.client_note_id = p_client_note_id;

    IF FOUND THEN
        IF inserted_note.request_hash <> request_fingerprint THEN
            RAISE EXCEPTION
                'Idempotency conflict: client note ID was reused with a different payload';
        END IF;

        RETURN inserted_note;
    END IF;

    INSERT INTO public.support_internal_notes (
        conversation_id,
        staff_user_id,
        client_note_id,
        body,
        request_hash,
        metadata
    )
    VALUES (
        p_conversation_id,
        p_staff_user_id,
        p_client_note_id,
        normalized_body,
        request_fingerprint,
        p_metadata
    )
    RETURNING *
    INTO inserted_note;

    UPDATE public.support_conversations
    SET last_activity_at = inserted_note.created_at
    WHERE id = p_conversation_id;

    RETURN inserted_note;
END;
$$;

REVOKE ALL
ON FUNCTION public.append_support_internal_note(UUID, UUID, UUID, TEXT, JSONB)
FROM PUBLIC, anon, authenticated;

GRANT EXECUTE
ON FUNCTION public.append_support_internal_note(UUID, UUID, UUID, TEXT, JSONB)
TO service_role;




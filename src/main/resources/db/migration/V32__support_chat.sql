-- ============================================================
-- QALIYE SUPPORT CHAT
-- PostgreSQL / Supabase
-- Migration V32 — forward-only, additive.
-- Does NOT alter any existing user-to-user chat table, trigger,
-- function, publication, or policy.
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;


-- ============================================================
-- 1. SUPPORT CONVERSATIONS
-- ============================================================

CREATE TABLE public.support_conversations (
    id                              UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    user_id                         UUID NOT NULL UNIQUE
        REFERENCES public.app_users(id) ON DELETE RESTRICT,

    status                          VARCHAR(20) NOT NULL DEFAULT 'IDLE'
        CHECK (
            status IN (
                'IDLE',
                'WAITING_STAFF',
                'WAITING_USER',
                'CLOSED'
            )
        ),

    priority                        SMALLINT NOT NULL DEFAULT 3
        CHECK (priority BETWEEN 1 AND 5),

    assigned_staff_user_id          UUID
        REFERENCES public.app_users(id) ON DELETE SET NULL,

    next_public_sequence            BIGINT NOT NULL DEFAULT 1
        CHECK (next_public_sequence > 0),

    user_last_read_sequence         BIGINT NOT NULL DEFAULT 0
        CHECK (user_last_read_sequence >= 0),

    staff_last_read_sequence        BIGINT NOT NULL DEFAULT 0
        CHECK (staff_last_read_sequence >= 0),

    last_public_message_at          TIMESTAMPTZ,

    last_public_message_sender_type VARCHAR(10)
        CHECK (
            last_public_message_sender_type IN ('USER', 'STAFF')
        ),

    waiting_since                   TIMESTAMPTZ,

    first_staff_response_at         TIMESTAMPTZ,

    last_activity_at                TIMESTAMPTZ,

    closed_at                       TIMESTAMPTZ,

    closed_by_app_user_id           UUID
        REFERENCES public.app_users(id) ON DELETE RESTRICT,

    closed_by_type                  VARCHAR(10)
        CHECK (
            closed_by_type IN ('USER', 'STAFF', 'SYSTEM')
        ),

    created_at                      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_support_conv_read_state CHECK (
        user_last_read_sequence < next_public_sequence
        AND staff_last_read_sequence < next_public_sequence
    ),

    CONSTRAINT check_support_conv_public_state CHECK (
        (
            status = 'IDLE'
            AND last_public_message_at IS NULL
            AND last_public_message_sender_type IS NULL
            AND waiting_since IS NULL
            AND closed_at IS NULL
            AND closed_by_app_user_id IS NULL
            AND closed_by_type IS NULL
        )
        OR
        (
            status = 'WAITING_STAFF'
            AND last_public_message_at IS NOT NULL
            AND last_public_message_sender_type = 'USER'
            AND waiting_since IS NOT NULL
            AND closed_at IS NULL
            AND closed_by_app_user_id IS NULL
            AND closed_by_type IS NULL
        )
        OR
        (
            status = 'WAITING_USER'
            AND last_public_message_at IS NOT NULL
            AND last_public_message_sender_type = 'STAFF'
            AND waiting_since IS NULL
            AND closed_at IS NULL
            AND closed_by_app_user_id IS NULL
            AND closed_by_type IS NULL
        )
        OR
        (
            status = 'CLOSED'
            AND last_public_message_at IS NOT NULL
            AND last_public_message_sender_type IS NOT NULL
            AND waiting_since IS NULL
            AND closed_at IS NOT NULL
            AND closed_by_type IS NOT NULL
            AND (
                (closed_by_type = 'SYSTEM' AND closed_by_app_user_id IS NULL)
                OR
                (
                    closed_by_type IN ('USER', 'STAFF')
                    AND closed_by_app_user_id IS NOT NULL
                )
            )
        )
    )
);

CREATE INDEX idx_support_conv_waiting_staff_queue
    ON public.support_conversations (
        priority ASC,
        waiting_since ASC,
        id
    )
    WHERE status = 'WAITING_STAFF';

CREATE INDEX idx_support_conv_waiting_user
    ON public.support_conversations (
        assigned_staff_user_id,
        last_public_message_at DESC,
        id
    )
    WHERE status = 'WAITING_USER';

CREATE INDEX idx_support_conv_assigned_staff
    ON public.support_conversations (
        assigned_staff_user_id,
        status,
        last_activity_at DESC NULLS LAST
    )
    WHERE assigned_staff_user_id IS NOT NULL;


-- ============================================================
-- 2. PUBLIC SUPPORT MESSAGES
-- ============================================================

CREATE TABLE public.support_messages (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    conversation_id             UUID NOT NULL
        REFERENCES public.support_conversations(id) ON DELETE CASCADE,

    sequence_number             BIGINT NOT NULL
        CHECK (sequence_number > 0),

    sender_type                 VARCHAR(10) NOT NULL
        CHECK (sender_type IN ('USER', 'STAFF')),

    sender_user_id              UUID NOT NULL
        REFERENCES public.app_users(id) ON DELETE RESTRICT,

    body                        TEXT,

    client_message_id           UUID NOT NULL,

    request_hash                CHAR(64) NOT NULL
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),

    metadata                    JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (
            jsonb_typeof(metadata) = 'object'
            AND octet_length(metadata::TEXT) <= 16384
        ),

    created_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_support_msg_sequence
        UNIQUE (conversation_id, sequence_number),

    CONSTRAINT unique_support_msg_client
        UNIQUE (
            conversation_id,
            sender_user_id,
            client_message_id
        ),

    CONSTRAINT check_support_message_body_format CHECK (
        body IS NULL
        OR (
            NULLIF(BTRIM(body), '') IS NOT NULL
            AND char_length(body) <= 10000
        )
    )
);

CREATE INDEX idx_support_msg_conv_sender_sequence
    ON public.support_messages (
        conversation_id,
        sender_type,
        sequence_number DESC
    );


-- ============================================================
-- 3. PUBLIC MESSAGE ATTACHMENTS
-- ============================================================

CREATE TABLE public.support_attachments (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    message_id                  UUID NOT NULL
        REFERENCES public.support_messages(id) ON DELETE CASCADE,

    storage_bucket              VARCHAR(100) NOT NULL
        CHECK (storage_bucket = 'support-attachments'),

    storage_path                TEXT NOT NULL,

    file_name                   VARCHAR(255) NOT NULL,

    content_type                VARCHAR(100) NOT NULL,

    file_size_bytes             BIGINT NOT NULL
        CHECK (
            file_size_bytes > 0
            AND file_size_bytes <= 26214400
        ),

    created_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_support_attachment_object
        UNIQUE (storage_bucket, storage_path),

    CONSTRAINT check_support_attachment_file_name CHECK (
        NULLIF(BTRIM(file_name), '') IS NOT NULL
    ),

    CONSTRAINT check_support_attachment_content_type CHECK (
        NULLIF(BTRIM(content_type), '') IS NOT NULL
    ),

    CONSTRAINT check_support_attachment_storage_path CHECK (
        NULLIF(BTRIM(storage_path), '') IS NOT NULL
        AND char_length(storage_path) <= 1024
        AND storage_path !~ '(^|/)\.\.(/|$)'
    )
);

CREATE INDEX idx_support_attachment_message
    ON public.support_attachments(message_id);


-- ============================================================
-- 4. INTERNAL STAFF NOTES
-- ============================================================

CREATE TABLE public.support_internal_notes (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    conversation_id             UUID NOT NULL
        REFERENCES public.support_conversations(id) ON DELETE CASCADE,

    staff_user_id               UUID NOT NULL
        REFERENCES public.app_users(id) ON DELETE RESTRICT,

    client_note_id              UUID NOT NULL,

    body                        TEXT NOT NULL
        CHECK (
            NULLIF(BTRIM(body), '') IS NOT NULL
            AND char_length(body) <= 10000
        ),

    request_hash                CHAR(64) NOT NULL
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),

    metadata                    JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (
            jsonb_typeof(metadata) = 'object'
            AND octet_length(metadata::TEXT) <= 16384
        ),

    created_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_support_internal_note_client
        UNIQUE (
            conversation_id,
            staff_user_id,
            client_note_id
        )
);

CREATE INDEX idx_support_internal_note_conversation
    ON public.support_internal_notes (
        conversation_id,
        created_at DESC,
        id
    );


-- ============================================================
-- 5. PER-STAFF PUBLIC READ CURSORS
-- ============================================================

CREATE TABLE public.support_conversation_staff_reads (
    conversation_id             UUID NOT NULL
        REFERENCES public.support_conversations(id) ON DELETE CASCADE,

    staff_user_id               UUID NOT NULL
        REFERENCES public.app_users(id) ON DELETE RESTRICT,

    last_read_sequence          BIGINT NOT NULL DEFAULT 0
        CHECK (last_read_sequence >= 0),

    last_read_at                TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    created_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (
        conversation_id,
        staff_user_id
    )
);

CREATE INDEX idx_support_staff_reads_staff
    ON public.support_conversation_staff_reads (
        staff_user_id,
        last_read_at DESC
    );


-- ============================================================
-- 6. UPDATED_AT TRIGGER
-- ============================================================

CREATE OR REPLACE FUNCTION public.set_support_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER set_support_conversations_updated_at
BEFORE UPDATE ON public.support_conversations
FOR EACH ROW
EXECUTE FUNCTION public.set_support_updated_at();

CREATE TRIGGER set_support_staff_reads_updated_at
BEFORE UPDATE ON public.support_conversation_staff_reads
FOR EACH ROW
EXECUTE FUNCTION public.set_support_updated_at();


-- ============================================================
-- 7. AUTO-PROVISION ONE DEFAULT SUPPORT CONVERSATION PER USER
-- ============================================================

CREATE OR REPLACE FUNCTION public.create_default_support_conversation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
    INSERT INTO public.support_conversations (
        user_id,
        status
    )
    VALUES (
        NEW.id,
        'IDLE'
    )
    ON CONFLICT (user_id) DO NOTHING;

    RETURN NEW;
END;
$$;

REVOKE ALL
ON FUNCTION public.create_default_support_conversation()
FROM PUBLIC, anon, authenticated;

CREATE TRIGGER create_default_support_conversation_after_user_insert
AFTER INSERT ON public.app_users
FOR EACH ROW
EXECUTE FUNCTION public.create_default_support_conversation();


-- ============================================================
-- 8. DEFENCE-IN-DEPTH SENDER VALIDATION
-- ============================================================

CREATE OR REPLACE FUNCTION public.validate_public_support_message_sender()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = ''
AS $$
DECLARE
    conversation_owner_id UUID;
BEGIN
    SELECT c.user_id
    INTO conversation_owner_id
    FROM public.support_conversations AS c
    WHERE c.id = NEW.conversation_id;

    IF conversation_owner_id IS NULL THEN
        RAISE EXCEPTION 'Support conversation does not exist';
    END IF;

    IF NEW.sender_type = 'USER' THEN
        IF NEW.sender_user_id <> conversation_owner_id THEN
            RAISE EXCEPTION
                'User sender does not own the support conversation';
        END IF;
    ELSE
        IF NOT EXISTS (
            SELECT 1
            FROM public.app_users AS u
            WHERE u.id = NEW.sender_user_id
              AND u.role::TEXT IN ('ADMIN', 'MODERATOR')
        ) THEN
            RAISE EXCEPTION
                'Sender must have ADMIN or MODERATOR role';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_public_support_message_sender_before_insert
BEFORE INSERT ON public.support_messages
FOR EACH ROW
EXECUTE FUNCTION public.validate_public_support_message_sender();


-- ============================================================
-- 9. HELPER: ASSERT SUPPORT STAFF ROLE
-- ============================================================

CREATE OR REPLACE FUNCTION public.assert_support_staff_role(
    p_staff_user_id UUID
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
    IF p_staff_user_id IS NULL OR NOT EXISTS (
        SELECT 1
        FROM public.app_users AS u
        WHERE u.id = p_staff_user_id
          AND u.role::TEXT IN ('ADMIN', 'MODERATOR')
    ) THEN
        RAISE EXCEPTION
            'ADMIN or MODERATOR role is required for support staff operations';
    END IF;
END;
$$;

REVOKE ALL
ON FUNCTION public.assert_support_staff_role(UUID)
FROM PUBLIC, anon, authenticated;


-- ============================================================
-- 10. HELPER: INSERT VALIDATED ATTACHMENT METADATA
-- ============================================================

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

        INSERT INTO public.support_attachments (
            message_id,
            storage_bucket,
            storage_path,
            file_name,
            content_type,
            file_size_bytes
        )
        VALUES (
            p_message_id,
            v_bucket,
            v_path,
            v_file_name,
            v_content_type,
            v_size
        );
    END LOOP;
END;
$$;

REVOKE ALL
ON FUNCTION public.insert_support_attachment_metadata(UUID, JSONB)
FROM PUBLIC, anon, authenticated;


-- ============================================================
-- 11. USER MESSAGE CREATION
-- ============================================================

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

    request_fingerprint := encode(
        public.digest(
            convert_to(
                jsonb_build_object(
                    'sender_type', 'USER',
                    'body', normalized_body,
                    'attachments', normalized_attachments,
                    'metadata', p_metadata
                )::TEXT,
                'UTF8'
            ),
            'sha256'
        ),
        'hex'
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


-- ============================================================
-- 12. STAFF PUBLIC MESSAGE CREATION
-- ============================================================

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

    request_fingerprint := encode(
        public.digest(
            convert_to(
                jsonb_build_object(
                    'sender_type', 'STAFF',
                    'body', normalized_body,
                    'attachments', normalized_attachments,
                    'metadata', p_metadata
                )::TEXT,
                'UTF8'
            ),
            'sha256'
        ),
        'hex'
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


-- ============================================================
-- 13. INTERNAL NOTE CREATION
-- ============================================================

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

    request_fingerprint := encode(
        public.digest(
            convert_to(
                jsonb_build_object(
                    'body', normalized_body,
                    'metadata', p_metadata
                )::TEXT,
                'UTF8'
            ),
            'sha256'
        ),
        'hex'
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


-- ============================================================
-- 14. USER READ-CURSOR UPDATE
-- ============================================================

CREATE OR REPLACE FUNCTION public.mark_support_conversation_read_by_user(
    p_conversation_id       UUID,
    p_user_id               UUID,
    p_last_read_sequence    BIGINT
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    maximum_sequence BIGINT;
BEGIN
    IF p_last_read_sequence IS NULL OR p_last_read_sequence < 0 THEN
        RAISE EXCEPTION 'Read sequence must be a non-negative integer';
    END IF;

    SELECT c.next_public_sequence - 1
    INTO maximum_sequence
    FROM public.support_conversations AS c
    WHERE c.id = p_conversation_id
      AND c.user_id = p_user_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Support conversation not found';
    END IF;

    UPDATE public.support_conversations
    SET user_last_read_sequence = GREATEST(
        user_last_read_sequence,
        LEAST(p_last_read_sequence, maximum_sequence)
    )
    WHERE id = p_conversation_id;
END;
$$;

REVOKE ALL
ON FUNCTION public.mark_support_conversation_read_by_user(UUID, UUID, BIGINT)
FROM PUBLIC, anon, authenticated;

GRANT EXECUTE
ON FUNCTION public.mark_support_conversation_read_by_user(UUID, UUID, BIGINT)
TO service_role;


-- ============================================================
-- 15. STAFF READ-CURSOR UPDATE
-- ============================================================

CREATE OR REPLACE FUNCTION public.mark_support_conversation_read_by_staff(
    p_conversation_id       UUID,
    p_staff_user_id         UUID,
    p_last_read_sequence    BIGINT
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    maximum_sequence BIGINT;
    bounded_sequence BIGINT;
BEGIN
    IF p_last_read_sequence IS NULL OR p_last_read_sequence < 0 THEN
        RAISE EXCEPTION 'Read sequence must be a non-negative integer';
    END IF;

    PERFORM public.assert_support_staff_role(p_staff_user_id);

    SELECT c.next_public_sequence - 1
    INTO maximum_sequence
    FROM public.support_conversations AS c
    WHERE c.id = p_conversation_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Support conversation not found';
    END IF;

    bounded_sequence := LEAST(
        p_last_read_sequence,
        maximum_sequence
    );

    INSERT INTO public.support_conversation_staff_reads (
        conversation_id,
        staff_user_id,
        last_read_sequence,
        last_read_at
    )
    VALUES (
        p_conversation_id,
        p_staff_user_id,
        bounded_sequence,
        CURRENT_TIMESTAMP
    )
    ON CONFLICT (conversation_id, staff_user_id)
    DO UPDATE SET
        last_read_sequence = GREATEST(
            public.support_conversation_staff_reads.last_read_sequence,
            EXCLUDED.last_read_sequence
        ),
        last_read_at = CURRENT_TIMESTAMP;

    UPDATE public.support_conversations
    SET staff_last_read_sequence = GREATEST(
        staff_last_read_sequence,
        bounded_sequence
    )
    WHERE id = p_conversation_id;
END;
$$;

REVOKE ALL
ON FUNCTION public.mark_support_conversation_read_by_staff(UUID, UUID, BIGINT)
FROM PUBLIC, anon, authenticated;

GRANT EXECUTE
ON FUNCTION public.mark_support_conversation_read_by_staff(UUID, UUID, BIGINT)
TO service_role;


-- ============================================================
-- 16. ASSIGNMENT AND PRIORITY
-- ============================================================

CREATE OR REPLACE FUNCTION public.assign_support_conversation(
    p_conversation_id        UUID,
    p_actor_staff_user_id    UUID,
    p_assigned_staff_user_id UUID
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
    PERFORM public.assert_support_staff_role(p_actor_staff_user_id);

    IF p_assigned_staff_user_id IS NOT NULL THEN
        PERFORM public.assert_support_staff_role(p_assigned_staff_user_id);
    END IF;

    UPDATE public.support_conversations
    SET assigned_staff_user_id = p_assigned_staff_user_id
    WHERE id = p_conversation_id
      AND status <> 'IDLE';

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Active support conversation not found';
    END IF;
END;
$$;

REVOKE ALL
ON FUNCTION public.assign_support_conversation(UUID, UUID, UUID)
FROM PUBLIC, anon, authenticated;

GRANT EXECUTE
ON FUNCTION public.assign_support_conversation(UUID, UUID, UUID)
TO service_role;


CREATE OR REPLACE FUNCTION public.set_support_conversation_priority(
    p_conversation_id       UUID,
    p_actor_staff_user_id   UUID,
    p_priority              SMALLINT
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
    PERFORM public.assert_support_staff_role(p_actor_staff_user_id);

    IF p_priority IS NULL OR p_priority NOT BETWEEN 1 AND 5 THEN
        RAISE EXCEPTION 'Priority must be between 1 and 5';
    END IF;

    UPDATE public.support_conversations
    SET priority = p_priority
    WHERE id = p_conversation_id
      AND status <> 'IDLE';

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Active support conversation not found';
    END IF;
END;
$$;

REVOKE ALL
ON FUNCTION public.set_support_conversation_priority(UUID, UUID, SMALLINT)
FROM PUBLIC, anon, authenticated;

GRANT EXECUTE
ON FUNCTION public.set_support_conversation_priority(UUID, UUID, SMALLINT)
TO service_role;


-- ============================================================
-- 17. CLOSE CONVERSATION
-- ============================================================

CREATE OR REPLACE FUNCTION public.close_support_conversation_by_user(
    p_conversation_id       UUID,
    p_user_id               UUID
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
    UPDATE public.support_conversations
    SET
        status = 'CLOSED',
        waiting_since = NULL,
        closed_at = CURRENT_TIMESTAMP,
        closed_by_app_user_id = p_user_id,
        closed_by_type = 'USER',
        last_activity_at = CURRENT_TIMESTAMP
    WHERE id = p_conversation_id
      AND user_id = p_user_id
      AND status <> 'IDLE';

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Active support conversation not found';
    END IF;
END;
$$;

REVOKE ALL
ON FUNCTION public.close_support_conversation_by_user(UUID, UUID)
FROM PUBLIC, anon, authenticated;

GRANT EXECUTE
ON FUNCTION public.close_support_conversation_by_user(UUID, UUID)
TO service_role;


CREATE OR REPLACE FUNCTION public.close_support_conversation_by_staff(
    p_conversation_id       UUID,
    p_staff_user_id         UUID
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
    PERFORM public.assert_support_staff_role(p_staff_user_id);

    UPDATE public.support_conversations
    SET
        status = 'CLOSED',
        waiting_since = NULL,
        closed_at = CURRENT_TIMESTAMP,
        closed_by_app_user_id = p_staff_user_id,
        closed_by_type = 'STAFF',
        last_activity_at = CURRENT_TIMESTAMP
    WHERE id = p_conversation_id
      AND status <> 'IDLE';

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Active support conversation not found';
    END IF;
END;
$$;

REVOKE ALL
ON FUNCTION public.close_support_conversation_by_staff(UUID, UUID)
FROM PUBLIC, anon, authenticated;

GRANT EXECUTE
ON FUNCTION public.close_support_conversation_by_staff(UUID, UUID)
TO service_role;


-- ============================================================
-- 18. EXPLICIT STAFF REOPEN
-- ============================================================

CREATE OR REPLACE FUNCTION public.reopen_support_conversation_by_staff(
    p_conversation_id       UUID,
    p_staff_user_id         UUID
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    conversation_row public.support_conversations%ROWTYPE;
BEGIN
    PERFORM public.assert_support_staff_role(p_staff_user_id);

    SELECT *
    INTO conversation_row
    FROM public.support_conversations AS c
    WHERE c.id = p_conversation_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Support conversation not found';
    END IF;

    IF conversation_row.status <> 'CLOSED' THEN
        RAISE EXCEPTION 'Only a closed conversation may be reopened';
    END IF;

    UPDATE public.support_conversations
    SET
        status = CASE
            WHEN conversation_row.last_public_message_sender_type = 'USER'
                THEN 'WAITING_STAFF'
            ELSE 'WAITING_USER'
        END,
        waiting_since = CASE
            WHEN conversation_row.last_public_message_sender_type = 'USER'
                THEN CURRENT_TIMESTAMP
            ELSE NULL
        END,
        assigned_staff_user_id = COALESCE(
            assigned_staff_user_id,
            p_staff_user_id
        ),
        closed_at = NULL,
        closed_by_app_user_id = NULL,
        closed_by_type = NULL,
        last_activity_at = CURRENT_TIMESTAMP
    WHERE id = p_conversation_id;
END;
$$;

REVOKE ALL
ON FUNCTION public.reopen_support_conversation_by_staff(UUID, UUID)
FROM PUBLIC, anon, authenticated;

GRANT EXECUTE
ON FUNCTION public.reopen_support_conversation_by_staff(UUID, UUID)
TO service_role;


-- ============================================================
-- 19. ROW-LEVEL SECURITY
-- ============================================================

ALTER TABLE public.support_conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.support_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.support_attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.support_internal_notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.support_conversation_staff_reads ENABLE ROW LEVEL SECURITY;

CREATE POLICY support_conv_user_select
ON public.support_conversations
FOR SELECT TO authenticated
USING (user_id = auth.uid());

CREATE POLICY support_msg_user_select
ON public.support_messages
FOR SELECT TO authenticated
USING (
    EXISTS (
        SELECT 1
        FROM public.support_conversations AS c
        WHERE c.id = support_messages.conversation_id
          AND c.user_id = auth.uid()
    )
);

CREATE POLICY support_attachment_user_select
ON public.support_attachments
FOR SELECT TO authenticated
USING (
    EXISTS (
        SELECT 1
        FROM public.support_messages AS m
        JOIN public.support_conversations AS c ON c.id = m.conversation_id
        WHERE m.id = support_attachments.message_id
          AND c.user_id = auth.uid()
    )
);


-- ============================================================
-- 20. CLIENT TABLE PRIVILEGES
-- ============================================================

REVOKE ALL ON public.support_conversations FROM anon, authenticated;
REVOKE ALL ON public.support_messages FROM anon, authenticated;
REVOKE ALL ON public.support_attachments FROM anon, authenticated;
REVOKE ALL ON public.support_internal_notes FROM anon, authenticated;
REVOKE ALL ON public.support_conversation_staff_reads FROM anon, authenticated;

GRANT SELECT (
    id, user_id, status, next_public_sequence,
    user_last_read_sequence, staff_last_read_sequence,
    last_public_message_at, last_public_message_sender_type,
    waiting_since, closed_at, created_at
)
ON public.support_conversations TO authenticated;

GRANT SELECT (
    id, conversation_id, sequence_number, sender_type, body, created_at
)
ON public.support_messages TO authenticated;

GRANT SELECT (
    id, message_id, file_name, content_type, file_size_bytes, created_at
)
ON public.support_attachments TO authenticated;


-- ============================================================
-- 21. BACKFILL DEFAULT CONVERSATIONS FOR EXISTING USERS
-- ============================================================

INSERT INTO public.support_conversations (user_id, status)
SELECT u.id, 'IDLE'
FROM public.app_users AS u
ON CONFLICT (user_id) DO NOTHING;


INSERT INTO storage.buckets (id, name, public)
VALUES ('support-attachments', 'support-attachments', false);
-- ============================================================================
-- V7__add_chat_sequences_outbox_and_notification_settings.sql
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Per-match ordered message sequences and receipt cursors
-- ---------------------------------------------------------------------------

ALTER TABLE public.matches
    ADD COLUMN next_message_sequence BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN user_one_last_delivered_sequence BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN user_two_last_delivered_sequence BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN user_one_last_read_sequence BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN user_two_last_read_sequence BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN user_one_last_delivered_at TIMESTAMPTZ,
    ADD COLUMN user_two_last_delivered_at TIMESTAMPTZ;

ALTER TABLE public.messages
    ADD COLUMN sequence_number BIGINT;

-- Backfill deterministic sequence values for existing messages.
WITH numbered_messages AS (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY match_id
            ORDER BY created_at ASC, id ASC
        )::BIGINT AS sequence_number
    FROM public.messages
)
UPDATE public.messages m
SET sequence_number = n.sequence_number
FROM numbered_messages n
WHERE m.id = n.id;

ALTER TABLE public.messages
    ALTER COLUMN sequence_number SET NOT NULL;

-- Existing historical messages are treated as delivered and read.
WITH max_sequences AS (
    SELECT
        match_id,
        MAX(sequence_number)::BIGINT AS max_sequence
    FROM public.messages
    GROUP BY match_id
)
UPDATE public.matches m
SET
    next_message_sequence = max_sequences.max_sequence + 1,
    user_one_last_delivered_sequence = max_sequences.max_sequence,
    user_two_last_delivered_sequence = max_sequences.max_sequence,
    user_one_last_read_sequence = max_sequences.max_sequence,
    user_two_last_read_sequence = max_sequences.max_sequence
FROM max_sequences
WHERE max_sequences.match_id = m.id;

-- Matches with no messages remain at sequence 1 and cursor 0.
UPDATE public.matches
SET next_message_sequence = 1
WHERE next_message_sequence IS NULL
   OR next_message_sequence < 1;

ALTER TABLE public.messages
    ADD CONSTRAINT check_messages_sequence_number_positive
    CHECK (sequence_number > 0);

ALTER TABLE public.messages
    ADD CONSTRAINT unique_messages_match_sequence
    UNIQUE (match_id, sequence_number);

ALTER TABLE public.matches
    ADD CONSTRAINT check_matches_receipt_sequence_state
    CHECK (
        next_message_sequence >= 1
        AND user_one_last_delivered_sequence >= 0
        AND user_two_last_delivered_sequence >= 0
        AND user_one_last_read_sequence >= 0
        AND user_two_last_read_sequence >= 0
        AND user_one_last_read_sequence <= user_one_last_delivered_sequence
        AND user_two_last_read_sequence <= user_two_last_delivered_sequence
        AND user_one_last_delivered_sequence < next_message_sequence
        AND user_two_last_delivered_sequence < next_message_sequence
    );

CREATE INDEX idx_messages_match_sequence
    ON public.messages(match_id, sequence_number DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_messages_match_sender_visible_sequence
    ON public.messages(match_id, sender_user_id, sequence_number)
    WHERE deleted_at IS NULL
      AND moderation_status = 'APPROVED';

DROP INDEX IF EXISTS public.idx_messages_match_cursor;

CREATE INDEX idx_matches_user_one_active_inbox
    ON public.matches(
        user_one_id,
        last_message_at DESC NULLS LAST,
        matched_at DESC,
        id DESC
    )
    WHERE status = 'ACTIVE';

CREATE INDEX idx_matches_user_two_active_inbox
    ON public.matches(
        user_two_id,
        last_message_at DESC NULLS LAST,
        matched_at DESC,
        id DESC
    )
    WHERE status = 'ACTIVE';


-- ---------------------------------------------------------------------------
-- 2. Transactional outbox
-- ---------------------------------------------------------------------------

CREATE TABLE public.chat_outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    event_type VARCHAR(100) NOT NULL CHECK (
        event_type IN (
            'chat.message.created',
            'chat.receipt.updated',
            'chat.match.ended',
            'inbox.match.updated',
            'inbox.match.removed'
        )
    ),

    match_id UUID REFERENCES public.matches(id) ON DELETE SET NULL,
    recipient_user_id UUID REFERENCES public.app_users(id) ON DELETE RESTRICT,

    topic TEXT NOT NULL CHECK (
        char_length(BTRIM(topic)) BETWEEN 1 AND 500
    ),

    -- Full immutable event envelope.
    payload JSONB NOT NULL CHECK (
        jsonb_typeof(payload) = 'object'
    ),

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')
    ),

    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (
        attempt_count >= 0
    ),

    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    locked_at TIMESTAMPTZ,
    locked_by VARCHAR(100),
    lease_expires_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,

    published_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_chat_outbox_recipient_shape CHECK (
        (
            event_type IN ('inbox.match.updated', 'inbox.match.removed')
            AND recipient_user_id IS NOT NULL
        )
        OR
        (
            event_type IN (
                'chat.message.created',
                'chat.receipt.updated',
                'chat.match.ended'
            )
            AND recipient_user_id IS NULL
        )
    ),

    CONSTRAINT check_chat_outbox_processing_lease CHECK (
        status <> 'PROCESSING'
        OR (
            locked_at IS NOT NULL
            AND locked_by IS NOT NULL
            AND lease_expires_at IS NOT NULL
        )
    )
);

CREATE INDEX idx_chat_outbox_claim_pending
    ON public.chat_outbox_events(available_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_chat_outbox_processing_lease
    ON public.chat_outbox_events(lease_expires_at)
    WHERE status = 'PROCESSING';

CREATE INDEX idx_chat_outbox_failed
    ON public.chat_outbox_events(created_at DESC)
    WHERE status = 'FAILED';

CREATE INDEX idx_chat_outbox_match_created
    ON public.chat_outbox_events(match_id, created_at DESC);

ALTER TABLE public.chat_outbox_events ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.chat_outbox_events
FROM anon, authenticated;


-- ---------------------------------------------------------------------------
-- 3. Per-user match notification settings
-- ---------------------------------------------------------------------------

CREATE TABLE public.match_notification_settings (
    match_id UUID NOT NULL REFERENCES public.matches(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES public.app_users(id) ON DELETE RESTRICT,

    muted_until TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (match_id, user_id)
);

CREATE INDEX idx_match_notification_settings_user
    ON public.match_notification_settings(user_id, muted_until);

CREATE OR REPLACE FUNCTION public.validate_match_notification_settings_member()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.matches m
        WHERE m.id = NEW.match_id
          AND (
              m.user_one_id = NEW.user_id
              OR m.user_two_id = NEW.user_id
          )
    ) THEN
        RAISE EXCEPTION
            'Notification settings user must be a participant of the match.';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_match_notification_settings_member
BEFORE INSERT OR UPDATE OF match_id, user_id
ON public.match_notification_settings
FOR EACH ROW
EXECUTE FUNCTION public.validate_match_notification_settings_member();

CREATE TRIGGER set_timestamp_match_notification_settings
BEFORE UPDATE ON public.match_notification_settings
FOR EACH ROW
EXECUTE FUNCTION public.update_updated_at_column();

ALTER TABLE public.match_notification_settings ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.match_notification_settings
FROM anon, authenticated;


-- ---------------------------------------------------------------------------
-- 4. Improve existing message trigger protections
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.validate_message_sender_is_match_participant()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.matches m
        JOIN public.app_users sender
            ON sender.id = NEW.sender_user_id
           AND sender.status = 'ACTIVE'
        WHERE m.id = NEW.match_id
          AND m.status = 'ACTIVE'
          AND (
              m.user_one_id = NEW.sender_user_id
              OR m.user_two_id = NEW.sender_user_id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM public.user_blocks ub
              WHERE ub.status = 'ACTIVE'
                AND (
                    (
                        ub.blocker_user_id = m.user_one_id
                        AND ub.blocked_user_id = m.user_two_id
                    )
                    OR
                    (
                        ub.blocker_user_id = m.user_two_id
                        AND ub.blocked_user_id = m.user_one_id
                    )
                )
          )
    ) THEN
        RAISE EXCEPTION
            'Message sender must be an active participant in an active, unblocked match.';
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.touch_match_message_timestamps()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.deleted_at IS NULL
       AND NEW.moderation_status = 'APPROVED' THEN

        UPDATE public.matches
        SET
            first_message_at = COALESCE(first_message_at, NEW.created_at),
            last_message_at = CASE
                WHEN last_message_at IS NULL THEN NEW.created_at
                WHEN NEW.created_at > last_message_at THEN NEW.created_at
                ELSE last_message_at
            END,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = NEW.match_id;
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.enforce_message_identity_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.match_id IS DISTINCT FROM OLD.match_id
       OR NEW.sender_user_id IS DISTINCT FROM OLD.sender_user_id
       OR NEW.client_message_id IS DISTINCT FROM OLD.client_message_id
       OR NEW.sequence_number IS DISTINCT FROM OLD.sequence_number
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION
            'Message identity and sequence fields are immutable.';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER enforce_message_identity_immutability
BEFORE UPDATE ON public.messages
FOR EACH ROW
EXECUTE FUNCTION public.enforce_message_identity_immutability();

-- ============================================================================
-- V8__configure_chat_private_realtime_broadcast.sql
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Remove direct client message reads.
-- Spring Boot is the chat read and write path.
-- ---------------------------------------------------------------------------

DROP POLICY IF EXISTS "Users can read approved messages in their active matches"
ON public.messages;

REVOKE ALL ON TABLE public.messages
FROM anon, authenticated;

DROP FUNCTION IF EXISTS public.can_read_match_messages(UUID);


-- ---------------------------------------------------------------------------
-- 2. Stop Postgres Changes publication for messages.
-- Chat uses private Broadcast events from the transactional outbox instead.
-- ---------------------------------------------------------------------------

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime'
          AND schemaname = 'public'
          AND tablename = 'messages'
    ) THEN
        ALTER PUBLICATION supabase_realtime DROP TABLE public.messages;
    END IF;
END $$;

ALTER TABLE public.messages REPLICA IDENTITY DEFAULT;


-- ---------------------------------------------------------------------------
-- 3. Secure Realtime topic helper functions.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.chat_realtime_is_active_match_member(
    p_topic TEXT,
    p_kind TEXT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_match_id UUID;
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RETURN FALSE;
    END IF;

    IF p_kind NOT IN ('events', 'typing', 'presence') THEN
        RETURN FALSE;
    END IF;

    IF p_topic !~ (
        '^match:[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-'
        || '[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}:'
        || p_kind
        || '$'
    ) THEN
        RETURN FALSE;
    END IF;

    v_match_id := split_part(p_topic, ':', 2)::UUID;

    RETURN EXISTS (
        SELECT 1
        FROM public.matches m
        JOIN public.app_users au
            ON au.id = v_user_id
           AND au.status = 'ACTIVE'
        WHERE m.id = v_match_id
          AND m.status = 'ACTIVE'
          AND (
              m.user_one_id = v_user_id
              OR m.user_two_id = v_user_id
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
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.chat_realtime_is_own_inbox_topic(
    p_topic TEXT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RETURN FALSE;
    END IF;

    IF p_topic !~ (
        '^user:[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-'
        || '[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}:inbox$'
    ) THEN
        RETURN FALSE;
    END IF;

    IF split_part(p_topic, ':', 2)::UUID <> v_user_id THEN
        RETURN FALSE;
    END IF;

    RETURN EXISTS (
        SELECT 1
        FROM public.app_users au
        WHERE au.id = v_user_id
          AND au.status = 'ACTIVE'
    );
END;
$$;

REVOKE ALL ON FUNCTION public.chat_realtime_is_active_match_member(TEXT, TEXT)
FROM PUBLIC;

REVOKE ALL ON FUNCTION public.chat_realtime_is_own_inbox_topic(TEXT)
FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.chat_realtime_is_active_match_member(TEXT, TEXT)
TO authenticated;

GRANT EXECUTE ON FUNCTION public.chat_realtime_is_own_inbox_topic(TEXT)
TO authenticated;


-- ---------------------------------------------------------------------------
-- 4. Supabase Realtime private Broadcast and Presence policies.
-- ---------------------------------------------------------------------------

ALTER TABLE realtime.messages ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "chat realtime receive" ON realtime.messages;
DROP POLICY IF EXISTS "chat realtime publish ephemeral" ON realtime.messages;

CREATE POLICY "chat realtime receive"
ON realtime.messages
FOR SELECT
TO authenticated
USING (
    (
        extension = 'broadcast'
        AND (
            public.chat_realtime_is_active_match_member(
                realtime.topic(),
                'events'
            )
            OR public.chat_realtime_is_active_match_member(
                realtime.topic(),
                'typing'
            )
            OR public.chat_realtime_is_own_inbox_topic(
                realtime.topic()
            )
        )
    )
    OR
    (
        extension = 'presence'
        AND public.chat_realtime_is_active_match_member(
            realtime.topic(),
            'presence'
        )
    )
);

CREATE POLICY "chat realtime publish ephemeral"
ON realtime.messages
FOR INSERT
TO authenticated
WITH CHECK (
    (
        extension = 'broadcast'
        AND public.chat_realtime_is_active_match_member(
            realtime.topic(),
            'typing'
        )
    )
    OR
    (
        extension = 'presence'
        AND public.chat_realtime_is_active_match_member(
            realtime.topic(),
            'presence'
        )
    )
);

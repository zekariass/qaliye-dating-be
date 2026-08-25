-- =============================================================================
-- Realtime Chat Broadcast & Presence Policies
-- =============================================================================
-- These policies live in the `realtime` schema which is excluded from
-- pg_dump --schema=public. Run this script manually on every new DB setup
-- (Step 4c of migration-plan.md).
--
-- NOTE: Do NOT run V8/V9 migration files directly — they contain
--   ALTER TABLE realtime.messages ENABLE ROW LEVEL SECURITY
-- which fails with "must be owner of table messages" in Supabase.
-- RLS is already enabled on realtime.messages by Supabase internally.
-- This script contains only the CREATE POLICY statements (final version from V9).
-- =============================================================================

DROP POLICY IF EXISTS "chat realtime receive" ON realtime.messages;
DROP POLICY IF EXISTS "chat realtime publish ephemeral" ON realtime.messages;

-- Allows authenticated users to receive broadcast/presence events on channels
-- they are an active member of (matched + not blocked).
CREATE POLICY "chat realtime receive"
ON realtime.messages
FOR SELECT
TO authenticated
USING (
    (
        extension = 'broadcast'
        AND (
            public.chat_realtime_is_active_match_member(realtime.topic(), 'events')
            OR public.chat_realtime_is_active_match_member(realtime.topic(), 'typing')
            OR public.chat_realtime_is_active_match_member(realtime.topic(), 'presence')
            OR public.chat_realtime_is_own_inbox_topic(realtime.topic())
        )
    )
    OR
    (
        extension = 'presence'
        AND public.chat_realtime_is_active_match_member(realtime.topic(), 'presence')
    )
);

-- Allows authenticated users to publish typing indicators and presence events.
CREATE POLICY "chat realtime publish ephemeral"
ON realtime.messages
FOR INSERT
TO authenticated
WITH CHECK (
    (
        extension = 'broadcast'
        AND (
            public.chat_realtime_is_active_match_member(realtime.topic(), 'typing')
            OR public.chat_realtime_is_active_match_member(realtime.topic(), 'presence')
        )
    )
    OR
    (
        extension = 'presence'
        AND public.chat_realtime_is_active_match_member(realtime.topic(), 'presence')
    )
);

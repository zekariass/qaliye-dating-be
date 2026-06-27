-- ============================================================================
-- V9__fix_presence_broadcast_authorization.sql
-- ============================================================================
-- Supabase Realtime presence channels internally use the 'broadcast' extension
-- for state-sync diffs in addition to the 'presence' extension for join/leave
-- events. The V8 "chat realtime receive" SELECT policy only allowed the
-- 'broadcast' extension for :events and :typing topics, so when Supabase
-- checked a broadcast-extension row on the :presence topic the policy fell
-- through all clauses and RLS denied the subscription with:
--   "Unauthorized: You do not have permissions to read from this Channel topic"
--
-- Fix: add chat_realtime_is_active_match_member(topic, 'presence') to both
-- the broadcast arm of the SELECT policy and the broadcast arm of the INSERT
-- (publish) policy so presence state-sync messages are authorized.
-- ============================================================================

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
            OR public.chat_realtime_is_active_match_member(
                realtime.topic(),
                'presence'
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
        AND (
            public.chat_realtime_is_active_match_member(
                realtime.topic(),
                'typing'
            )
            OR public.chat_realtime_is_active_match_member(
                realtime.topic(),
                'presence'
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

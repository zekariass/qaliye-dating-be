-- ============================================================================
-- V37__add_per_user_cleared_sequence.sql
-- Adds per-user "cleared sequence" columns to matches so each user can clear
-- their own view of the conversation without affecting the other participant.
-- Messages with sequence_number <= the user's cleared sequence are hidden from
-- that user's message list, inbox last-message preview, and unread count.
-- The match itself remains active and unaffected.
-- ============================================================================

ALTER TABLE public.matches
    ADD COLUMN user_one_cleared_sequence BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN user_two_cleared_sequence BIGINT NOT NULL DEFAULT 0;

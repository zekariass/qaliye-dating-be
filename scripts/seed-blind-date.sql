-- =============================================================================
-- Blind Date seed data for UI testing (dev only — NOT a Flyway migration)
--
-- Run with:  psql -d <db> -f scripts/seed-blind-date.sql
--
-- Creates:
--   * blind_date_configurations + question sets for all 14 users
--   * Session A (OPEN, round 1 open)   — creator u1, participants u2..u7 w/ answers
--   * Session B (REVEAL, pending final decisions) — creator u8, finalist u9
--   * Session C (COMPLETED, NO_MATCH)  — creator u14, participants u3,u5,u7
--   * Session D (OPEN) — creator u4,  participants u9,u10,u6 w/ answers
--   * Session E (OPEN) — creator u9,  participants u2,u11 w/ answers
--   * Session F (OPEN) — creator u14, zero participants (fresh join target)
--
-- Idempotent: safe to re-run (ON CONFLICT DO NOTHING everywhere).
-- =============================================================================

BEGIN;

-- ── Users ────────────────────────────────────────────────────────────────────
-- u1  4a76f3a4-ad40-4490-9348-f14af251f357   creator of session A
-- u2  464482c2-aad9-4c67-8f6f-f1acfe784b7c   participant A
-- u3  18f3c437-0064-40b2-8187-156647d30b8b   participant A + C
-- u4  27b68e92-e7b3-4f7f-8d1f-c57cc1698e4d   participant A
-- u5  a84e3e97-e8de-495e-b7e0-86609ee64b43   participant A + C
-- u6  7391c444-cbc1-49df-8e1f-d58ae85a9609   participant A
-- u7  246dda8a-2ba4-4135-a4a2-8c701be56066   participant A + C
-- u8  20733cf6-bc36-49fd-83e8-a855267cbb5f   creator of session B
-- u9  337e8846-7116-4d4c-9c52-e058fadfdfb2   finalist of session B
-- u10 f8ff8619-028f-4fb2-9bec-5cf9cf2e00a0   participant B
-- u11 56bc199c-019f-4bc2-b96a-7b05b55daaa0   participant B
-- u12 8cdf660e-d212-4446-be66-14c609777590   participant B
-- u13 f2c63429-590c-45cf-9e13-ff8564451ece   participant B
-- u14 f9e79197-0b32-41a7-bc78-941bc64e427e   creator of session C

-- ── 1. Configurations (enabled, English) ─────────────────────────────────────
INSERT INTO public.blind_date_configurations (user_id, enabled, language_code)
SELECT u.user_id, TRUE, 'en'
FROM (VALUES
    ('4a76f3a4-ad40-4490-9348-f14af251f357'::uuid),
    ('464482c2-aad9-4c67-8f6f-f1acfe784b7c'),
    ('18f3c437-0064-40b2-8187-156647d30b8b'),
    ('27b68e92-e7b3-4f7f-8d1f-c57cc1698e4d'),
    ('a84e3e97-e8de-495e-b7e0-86609ee64b43'),
    ('7391c444-cbc1-49df-8e1f-d58ae85a9609'),
    ('246dda8a-2ba4-4135-a4a2-8c701be56066'),
    ('20733cf6-bc36-49fd-83e8-a855267cbb5f'),
    ('337e8846-7116-4d4c-9c52-e058fadfdfb2'),
    ('f8ff8619-028f-4fb2-9bec-5cf9cf2e00a0'),
    ('56bc199c-019f-4bc2-b96a-7b05b55daaa0'),
    ('8cdf660e-d212-4446-be66-14c609777590'),
    ('f2c63429-590c-45cf-9e13-ff8564451ece'),
    ('f9e79197-0b32-41a7-bc78-941bc64e427e')
) AS u(user_id)
ON CONFLICT (user_id) DO NOTHING;

-- ── 2. Question sets: every user gets the first 5 platform questions ──────────
INSERT INTO public.blind_date_question_sets (user_id)
SELECT user_id FROM public.blind_date_configurations
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO public.blind_date_question_set_questions (question_set_id, question_id, sort_order)
SELECT qs.id, q.id, q.rn
FROM public.blind_date_question_sets qs
JOIN (
    SELECT id, ROW_NUMBER() OVER (ORDER BY sort_order) AS rn
    FROM public.blind_date_questions
    WHERE active
) q ON q.rn <= 5
ON CONFLICT (question_set_id, question_id) DO NOTHING;

INSERT INTO public.blind_date_question_set_answers (question_set_question_id, answer)
SELECT qsq.id, 'My seeded answer — testing the Blind Date UI.'
FROM public.blind_date_question_set_questions qsq
ON CONFLICT (question_set_question_id) DO NOTHING;

-- =============================================================================
-- SESSION A — OPEN, round 1 accepting answers
-- =============================================================================

INSERT INTO public.blind_date_sessions
    (id, creator_user_id, status, language_code, expires_at, credit_charge_idempotency_key)
VALUES
    ('aa000000-0000-4000-8000-0000000000a1',
     '4a76f3a4-ad40-4490-9348-f14af251f357',
     'OPEN', 'en', NOW() + INTERVAL '2 days', gen_random_uuid())
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.blind_date_session_rounds
    (id, session_id, round_number, status, started_at)
VALUES
    ('aa000000-0000-4000-8000-0000000000b1',
     'aa000000-0000-4000-8000-0000000000a1', 1, 'OPEN', NOW() - INTERVAL '2 hours')
ON CONFLICT (session_id, round_number) DO NOTHING;

-- Snapshot 3 platform questions into round 1 (with the creator's answers)
INSERT INTO public.blind_date_session_questions
    (id, session_id, round_id, source_question_id, question_text, answer_text, language_code, sort_order)
SELECT gen_random_uuid(),
       'aa000000-0000-4000-8000-0000000000a1',
       'aa000000-0000-4000-8000-0000000000b1',
       q.id, t.question, ca.answer_text, 'en', q.sort_order
FROM public.blind_date_questions q
JOIN public.blind_date_question_translations t
  ON t.question_id = q.id AND t.language_code = 'en'
JOIN (VALUES
    ('BD_Q_INTRO_THREE_WORDS', 'Curious, loyal, caffeinated.'),
    ('BD_Q_REL_LOOKING_FOR',   'Someone kind who laughs at my jokes.'),
    ('BD_Q_FUN_DESERT_ISLAND', 'A book, a knife, and a satellite phone.')
) AS ca(code, answer_text) ON ca.code = q.code
WHERE q.code IN ('BD_Q_INTRO_THREE_WORDS', 'BD_Q_REL_LOOKING_FOR', 'BD_Q_FUN_DESERT_ISLAND')
ON CONFLICT DO NOTHING;

-- Participants u2..u7, all ACTIVE in round 1
INSERT INTO public.blind_date_session_participants
    (id, session_id, user_id, status, current_round_id, credit_charge_idempotency_key, joined_at)
SELECT p.participant_id,
       'aa000000-0000-4000-8000-0000000000a1',
       p.user_id, 'ACTIVE',
       'aa000000-0000-4000-8000-0000000000b1',
       gen_random_uuid(),
       NOW() - (p.idx || ' minutes')::interval
FROM (VALUES
    ('aa000000-0000-4000-8000-0000000000c1'::uuid, '464482c2-aad9-4c67-8f6f-f1acfe784b7c'::uuid, 90),
    ('aa000000-0000-4000-8000-0000000000c2',       '18f3c437-0064-40b2-8187-156647d30b8b',       80),
    ('aa000000-0000-4000-8000-0000000000c3',       '27b68e92-e7b3-4f7f-8d1f-c57cc1698e4d',       70),
    ('aa000000-0000-4000-8000-0000000000c4',       'a84e3e97-e8de-495e-b7e0-86609ee64b43',       60),
    ('aa000000-0000-4000-8000-0000000000c5',       '7391c444-cbc1-49df-8e1f-d58ae85a9609',       45),
    ('aa000000-0000-4000-8000-0000000000c6',       '246dda8a-2ba4-4135-a4a2-8c701be56066',       30)
) AS p(participant_id, user_id, idx)
ON CONFLICT (session_id, user_id) DO NOTHING;

-- Answers: each participant answers all 3 round-1 questions
INSERT INTO public.blind_date_session_answers (participant_id, session_question_id, answer, submitted_at)
SELECT p.id, sq.id, a.answer, NOW() - (a.mins || ' minutes')::interval
FROM public.blind_date_session_participants p
JOIN (VALUES
    ('464482c2-aad9-4c67-8f6f-f1acfe784b7c'::uuid, 1, 'Quiet, stubborn, funny.', 55),
    ('464482c2-aad9-4c67-8f6f-f1acfe784b7c',       2, 'Honesty and a good sense of humor.', 54),
    ('464482c2-aad9-4c67-8f6f-f1acfe784b7c',       3, 'Coffee, a hammock, sunscreen.', 53),
    ('18f3c437-0064-40b2-8187-156647d30b8b',       1, 'Ambitious, warm, restless.', 50),
    ('18f3c437-0064-40b2-8187-156647d30b8b',       2, 'A partner who feels like home.', 49),
    ('18f3c437-0064-40b2-8187-156647d30b8b',       3, 'My dog, a boat, matches.', 48),
    ('27b68e92-e7b3-4f7f-8d1f-c57cc1698e4d',       1, 'Calm, curious, kind.', 40),
    ('27b68e92-e7b3-4f7f-8d1f-c57cc1698e4d',       2, 'Someone who communicates openly.', 39),
    ('27b68e92-e7b3-4f7f-8d1f-c57cc1698e4d',       3, 'A fishing rod, a tent, rum.', 38),
    ('a84e3e97-e8de-495e-b7e0-86609ee64b43',       1, 'Loud, loyal, hungry.', 30),
    ('a84e3e97-e8de-495e-b7e0-86609ee64b43',       2, 'Patience and shared faith.', 29),
    ('a84e3e97-e8de-495e-b7e0-86609ee64b43',       3, 'A guitar, seeds, a journal.', 28),
    ('7391c444-cbc1-49df-8e1f-d58ae85a9609',       1, 'Thoughtful, dry, driven.', 20),
    ('7391c444-cbc1-49df-8e1f-d58ae85a9609',       2, 'Someone to build a life with.', 19),
    ('7391c444-cbc1-49df-8e1f-d58ae85a9609',       3, 'A knife, rope, and a cookbook.', 18),
    ('246dda8a-2ba4-4135-a4a2-8c701be56066',       1, 'Playful, honest, chill.', 10),
    ('246dda8a-2ba4-4135-a4a2-8c701be56066',       2, 'A best friend I can laugh with.', 9),
    ('246dda8a-2ba4-4135-a4a2-8c701be56066',       3, 'A surfboard, sunglasses, snacks.', 8)
) AS a(user_id, sort_order, answer, mins) ON a.user_id = p.user_id
JOIN public.blind_date_session_questions sq
  ON sq.round_id = 'aa000000-0000-4000-8000-0000000000b1'
 AND sq.sort_order = a.sort_order
WHERE p.session_id = 'aa000000-0000-4000-8000-0000000000a1'
ON CONFLICT (participant_id, session_question_id) DO NOTHING;

-- =============================================================================
-- SESSION B — REVEAL: finalist chosen, both final decisions pending
-- =============================================================================

INSERT INTO public.blind_date_sessions
    (id, creator_user_id, status, language_code, expires_at, credit_charge_idempotency_key, created_at)
VALUES
    ('bb000000-0000-4000-8000-0000000000a2',
     '20733cf6-bc36-49fd-83e8-a855267cbb5f',
     'REVEAL', 'en', NOW() + INTERVAL '1 day', gen_random_uuid(), NOW() - INTERVAL '3 days')
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.blind_date_session_rounds
    (id, session_id, round_number, status, started_at, completed_at)
VALUES
    ('bb000000-0000-4000-8000-0000000000b1',
     'bb000000-0000-4000-8000-0000000000a2', 1, 'CLOSED',
     NOW() - INTERVAL '3 days', NOW() - INTERVAL '2 days'),
    ('bb000000-0000-4000-8000-0000000000b2',
     'bb000000-0000-4000-8000-0000000000a2', 2, 'CLOSED',
     NOW() - INTERVAL '2 days', NOW() - INTERVAL '1 day')
ON CONFLICT (session_id, round_number) DO NOTHING;

-- Round 1 questions for session B
INSERT INTO public.blind_date_session_questions
    (id, session_id, round_id, source_question_id, question_text, answer_text, language_code, sort_order)
SELECT gen_random_uuid(),
       'bb000000-0000-4000-8000-0000000000a2',
       'bb000000-0000-4000-8000-0000000000b1',
       q.id, t.question, ca.answer_text, 'en', q.sort_order
FROM public.blind_date_questions q
JOIN public.blind_date_question_translations t
  ON t.question_id = q.id AND t.language_code = 'en'
JOIN (VALUES
    ('BD_Q_INTRO_PERFECT_DAY',    'Slow morning, long walk, good dinner.'),
    ('BD_Q_VALUES_FAMILY_MEANING', 'Everything. They come first.'),
    ('BD_Q_LIFE_WEEKEND',          'Hiking, then cooking for friends.')
) AS ca(code, answer_text) ON ca.code = q.code
WHERE q.code IN ('BD_Q_INTRO_PERFECT_DAY', 'BD_Q_VALUES_FAMILY_MEANING', 'BD_Q_LIFE_WEEKEND')
ON CONFLICT DO NOTHING;

-- Round 2 questions for session B
INSERT INTO public.blind_date_session_questions
    (id, session_id, round_id, source_question_id, question_text, answer_text, language_code, sort_order)
SELECT gen_random_uuid(),
       'bb000000-0000-4000-8000-0000000000a2',
       'bb000000-0000-4000-8000-0000000000b2',
       q.id, t.question, ca.answer_text, 'en', q.sort_order
FROM public.blind_date_questions q
JOIN public.blind_date_question_translations t
  ON t.question_id = q.id AND t.language_code = 'en'
JOIN (VALUES
    ('BD_Q_REL_LOVE_LANGUAGE',  'Acts of service and long talks.'),
    ('BD_Q_LIFE_FIVE_YEARS',    'Married, maybe a kid, still hiking.'),
    ('BD_Q_FUN_LAST_LAUGH',     'My nephew trying to whistle.')
) AS ca(code, answer_text) ON ca.code = q.code
WHERE q.code IN ('BD_Q_REL_LOVE_LANGUAGE', 'BD_Q_LIFE_FIVE_YEARS', 'BD_Q_FUN_LAST_LAUGH')
ON CONFLICT DO NOTHING;

-- Participants u9..u13: u9 is FINALIST, u10/u11 ELIMINATED in round 2,
-- u12/u13 ELIMINATED in round 1
INSERT INTO public.blind_date_session_participants
    (id, session_id, user_id, status, current_round_id, credit_charge_idempotency_key,
     joined_at, eliminated_at, finalist_at)
SELECT p.participant_id,
       'bb000000-0000-4000-8000-0000000000a2',
       p.user_id, p.status, p.round_id, gen_random_uuid(),
       NOW() - INTERVAL '3 days' + (p.idx || ' minutes')::interval,
       p.eliminated_at, p.finalist_at
FROM (VALUES
    ('bb000000-0000-4000-8000-0000000000d1'::uuid, '337e8846-7116-4d4c-9c52-e058fadfdfb2'::uuid,
     'FINALIST',  'bb000000-0000-4000-8000-0000000000b2'::uuid, 5,  NULL::timestamptz,             NOW() - INTERVAL '1 day'),
    ('bb000000-0000-4000-8000-0000000000d2',       'f8ff8619-028f-4fb2-9bec-5cf9cf2e00a0',
     'ELIMINATED','bb000000-0000-4000-8000-0000000000b2',        15, NOW() - INTERVAL '1 day',      NULL),
    ('bb000000-0000-4000-8000-0000000000d3',       '56bc199c-019f-4bc2-b96a-7b05b55daaa0',
     'ELIMINATED','bb000000-0000-4000-8000-0000000000b2',        25, NOW() - INTERVAL '1 day',      NULL),
    ('bb000000-0000-4000-8000-0000000000d4',       '8cdf660e-d212-4446-be66-14c609777590',
     'ELIMINATED','bb000000-0000-4000-8000-0000000000b1',        35, NOW() - INTERVAL '2 days',     NULL),
    ('bb000000-0000-4000-8000-0000000000d5',       'f2c63429-590c-45cf-9e13-ff8564451ece',
     'ELIMINATED','bb000000-0000-4000-8000-0000000000b1',        45, NOW() - INTERVAL '2 days',     NULL)
) AS p(participant_id, user_id, status, round_id, idx, eliminated_at, finalist_at)
ON CONFLICT (session_id, user_id) DO NOTHING;

-- Round 1 selections: u9/u10/u11 advanced, u12/u13 eliminated
INSERT INTO public.blind_date_round_selections
    (round_id, participant_id, selected_by_user_id, decision, created_at)
SELECT s.round_id, s.participant_id,
       '20733cf6-bc36-49fd-83e8-a855267cbb5f', s.decision,
       NOW() - INTERVAL '2 days'
FROM (VALUES
    ('bb000000-0000-4000-8000-0000000000b1'::uuid, 'bb000000-0000-4000-8000-0000000000d1'::uuid, 'ADVANCE'),
    ('bb000000-0000-4000-8000-0000000000b1',       'bb000000-0000-4000-8000-0000000000d2',       'ADVANCE'),
    ('bb000000-0000-4000-8000-0000000000b1',       'bb000000-0000-4000-8000-0000000000d3',       'ADVANCE'),
    ('bb000000-0000-4000-8000-0000000000b1',       'bb000000-0000-4000-8000-0000000000d4',       'ELIMINATE'),
    ('bb000000-0000-4000-8000-0000000000b1',       'bb000000-0000-4000-8000-0000000000d5',       'ELIMINATE')
) AS s(round_id, participant_id, decision)
ON CONFLICT (round_id, participant_id) DO NOTHING;

-- Round 2 selections: u9 finalist, u10/u11 eliminated
INSERT INTO public.blind_date_round_selections
    (round_id, participant_id, selected_by_user_id, decision, created_at)
SELECT s.round_id, s.participant_id,
       '20733cf6-bc36-49fd-83e8-a855267cbb5f', s.decision,
       NOW() - INTERVAL '1 day'
FROM (VALUES
    ('bb000000-0000-4000-8000-0000000000b2'::uuid, 'bb000000-0000-4000-8000-0000000000d1'::uuid, 'SELECT_FINALIST'),
    ('bb000000-0000-4000-8000-0000000000b2',       'bb000000-0000-4000-8000-0000000000d2',       'ELIMINATE'),
    ('bb000000-0000-4000-8000-0000000000b2',       'bb000000-0000-4000-8000-0000000000d3',       'ELIMINATE')
) AS s(round_id, participant_id, decision)
ON CONFLICT (round_id, participant_id) DO NOTHING;

-- Final decision row: both sides still PENDING
INSERT INTO public.blind_date_final_decisions
    (session_id, finalist_participant_id, revealed_at, decision_deadline_at)
VALUES
    ('bb000000-0000-4000-8000-0000000000a2',
     'bb000000-0000-4000-8000-0000000000d1',
     NOW() - INTERVAL '1 day', NOW() + INTERVAL '1 day')
ON CONFLICT (session_id) DO NOTHING;

-- =============================================================================
-- SESSION C — COMPLETED with NO_MATCH outcome (history)
-- =============================================================================

INSERT INTO public.blind_date_sessions
    (id, creator_user_id, status, language_code, credit_charge_idempotency_key,
     created_at, closed_at, completed_at)
VALUES
    ('cc000000-0000-4000-8000-0000000000a3',
     'f9e79197-0b32-41a7-bc78-941bc64e427e',
     'COMPLETED', 'en', gen_random_uuid(),
     NOW() - INTERVAL '10 days', NOW() - INTERVAL '8 days', NOW() - INTERVAL '8 days')
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.blind_date_session_rounds
    (id, session_id, round_number, status, started_at, completed_at)
VALUES
    ('cc000000-0000-4000-8000-0000000000b1',
     'cc000000-0000-4000-8000-0000000000a3', 1, 'CLOSED',
     NOW() - INTERVAL '10 days', NOW() - INTERVAL '8 days')
ON CONFLICT (session_id, round_number) DO NOTHING;

INSERT INTO public.blind_date_session_questions
    (id, session_id, round_id, source_question_id, question_text, answer_text, language_code, sort_order)
SELECT gen_random_uuid(),
       'cc000000-0000-4000-8000-0000000000a3',
       'cc000000-0000-4000-8000-0000000000b1',
       q.id, t.question, 'Creator answer.', 'en', q.sort_order
FROM public.blind_date_questions q
JOIN public.blind_date_question_translations t
  ON t.question_id = q.id AND t.language_code = 'en'
WHERE q.code IN ('BD_Q_INTRO_THREE_WORDS', 'BD_Q_REL_DEAL_BREAKER', 'BD_Q_FUN_SUPERPOWER')
ON CONFLICT DO NOTHING;

INSERT INTO public.blind_date_session_participants
    (id, session_id, user_id, status, current_round_id, credit_charge_idempotency_key,
     joined_at, eliminated_at, revealed_at)
SELECT p.participant_id,
       'cc000000-0000-4000-8000-0000000000a3',
       p.user_id, p.status,
       'cc000000-0000-4000-8000-0000000000b1',
       gen_random_uuid(),
       NOW() - INTERVAL '10 days',
       p.eliminated_at, p.revealed_at
FROM (VALUES
    ('cc000000-0000-4000-8000-0000000000e1'::uuid, '18f3c437-0064-40b2-8187-156647d30b8b'::uuid,
     'REVEALED',  NULL::timestamptz,        NOW() - INTERVAL '8 days'),
    ('cc000000-0000-4000-8000-0000000000e2',       'a84e3e97-e8de-495e-b7e0-86609ee64b43',
     'ELIMINATED', NOW() - INTERVAL '8 days', NULL),
    ('cc000000-0000-4000-8000-0000000000e3',       '246dda8a-2ba4-4135-a4a2-8c701be56066',
     'ELIMINATED', NOW() - INTERVAL '8 days', NULL)
) AS p(participant_id, user_id, status, eliminated_at, revealed_at)
ON CONFLICT (session_id, user_id) DO NOTHING;

INSERT INTO public.blind_date_final_decisions
    (session_id, finalist_participant_id, revealed_at, decision_deadline_at,
     creator_decision, participant_decision, creator_decided_at, participant_decided_at, outcome)
VALUES
    ('cc000000-0000-4000-8000-0000000000a3',
     'cc000000-0000-4000-8000-0000000000e1',
     NOW() - INTERVAL '9 days', NOW() - INTERVAL '8 days',
     'INTERESTED', 'NOT_INTERESTED',
     NOW() - INTERVAL '9 days', NOW() - INTERVAL '8 days',
     'NO_MATCH')
ON CONFLICT (session_id) DO NOTHING;

-- =============================================================================
-- SESSIONS D / E / F — extra OPEN sessions to join (round 1 open)
-- Creators: u4, u9, u14 — swap creator_user_id values to get the
-- male/female mix you want (gender/age/photo come from each creator's profile).
-- u1 and u8 cannot create more sessions (active_session_exists).
-- =============================================================================

-- ── Session D — creator u4, 3 participants with answers ──────────────────────
INSERT INTO public.blind_date_sessions
    (id, creator_user_id, status, language_code, expires_at, credit_charge_idempotency_key)
VALUES
    ('dd000000-0000-4000-8000-0000000000a4',
     '27b68e92-e7b3-4f7f-8d1f-c57cc1698e4d',
     'OPEN', 'en', NOW() + INTERVAL '3 days', gen_random_uuid())
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.blind_date_session_rounds
    (id, session_id, round_number, status, started_at)
VALUES
    ('dd000000-0000-4000-8000-0000000000b1',
     'dd000000-0000-4000-8000-0000000000a4', 1, 'OPEN', NOW() - INTERVAL '5 hours')
ON CONFLICT (session_id, round_number) DO NOTHING;

INSERT INTO public.blind_date_session_questions
    (id, session_id, round_id, source_question_id, question_text, answer_text, language_code, sort_order)
SELECT gen_random_uuid(),
       'dd000000-0000-4000-8000-0000000000a4',
       'dd000000-0000-4000-8000-0000000000b1',
       q.id, t.question, ca.answer_text, 'en', q.sort_order
FROM public.blind_date_questions q
JOIN public.blind_date_question_translations t
  ON t.question_id = q.id AND t.language_code = 'en'
JOIN (VALUES
    ('BD_Q_INTRO_PROUDEST',        'Raising my little sister.'),
    ('BD_Q_VALUES_FAITH_ROLE',     'It guides how I treat people.'),
    ('BD_Q_LIFE_MORNING_ROUTINE',  'Coffee, journaling, a quick run.')
) AS ca(code, answer_text) ON ca.code = q.code
WHERE q.code IN ('BD_Q_INTRO_PROUDEST', 'BD_Q_VALUES_FAITH_ROLE', 'BD_Q_LIFE_MORNING_ROUTINE')
ON CONFLICT DO NOTHING;

INSERT INTO public.blind_date_session_participants
    (id, session_id, user_id, status, current_round_id, credit_charge_idempotency_key, joined_at)
SELECT p.participant_id,
       'dd000000-0000-4000-8000-0000000000a4',
       p.user_id, 'ACTIVE',
       'dd000000-0000-4000-8000-0000000000b1',
       gen_random_uuid(),
       NOW() - (p.idx || ' minutes')::interval
FROM (VALUES
    ('dd000000-0000-4000-8000-0000000000c1'::uuid, '337e8846-7116-4d4c-9c52-e058fadfdfb2'::uuid, 200),
    ('dd000000-0000-4000-8000-0000000000c2',       'f8ff8619-028f-4fb2-9bec-5cf9cf2e00a0',       150),
    ('dd000000-0000-4000-8000-0000000000c3',       '7391c444-cbc1-49df-8e1f-d58ae85a9609',       100)
) AS p(participant_id, user_id, idx)
ON CONFLICT (session_id, user_id) DO NOTHING;

INSERT INTO public.blind_date_session_answers (participant_id, session_question_id, answer, submitted_at)
SELECT p.id, sq.id, a.answer, NOW() - (a.mins || ' minutes')::interval
FROM public.blind_date_session_participants p
JOIN (VALUES
    ('337e8846-7116-4d4c-9c52-e058fadfdfb2'::uuid, 1, 'Finishing my first marathon.', 190),
    ('337e8846-7116-4d4c-9c52-e058fadfdfb2',       2, 'It keeps me grounded.', 185),
    ('337e8846-7116-4d4c-9c52-e058fadfdfb2',       3, 'Prayer, then a long breakfast.', 180),
    ('f8ff8619-028f-4fb2-9bec-5cf9cf2e00a0',       1, 'Building my own business.', 140),
    ('f8ff8619-028f-4fb2-9bec-5cf9cf2e00a0',       2, 'A big part of who I am.', 135),
    ('f8ff8619-028f-4fb2-9bec-5cf9cf2e00a0',       3, 'Gym, then coffee with a book.', 130),
    ('7391c444-cbc1-49df-8e1f-d58ae85a9609',       1, 'My garden, honestly.', 90),
    ('7391c444-cbc1-49df-8e1f-d58ae85a9609',       2, 'Tradition more than ritual.', 85),
    ('7391c444-cbc1-49df-8e1f-d58ae85a9609',       3, 'Slowly. Very slowly.', 80)
) AS a(user_id, sort_order, answer, mins) ON a.user_id = p.user_id
JOIN public.blind_date_session_questions sq
  ON sq.round_id = 'dd000000-0000-4000-8000-0000000000b1'
 AND sq.sort_order = a.sort_order
WHERE p.session_id = 'dd000000-0000-4000-8000-0000000000a4'
ON CONFLICT (participant_id, session_question_id) DO NOTHING;

-- ── Session E — creator u9, 2 participants with answers ──────────────────────
INSERT INTO public.blind_date_sessions
    (id, creator_user_id, status, language_code, expires_at, credit_charge_idempotency_key)
VALUES
    ('ee000000-0000-4000-8000-0000000000a5',
     '337e8846-7116-4d4c-9c52-e058fadfdfb2',
     'OPEN', 'en', NOW() + INTERVAL '4 days', gen_random_uuid())
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.blind_date_session_rounds
    (id, session_id, round_number, status, started_at)
VALUES
    ('ee000000-0000-4000-8000-0000000000b1',
     'ee000000-0000-4000-8000-0000000000a5', 1, 'OPEN', NOW() - INTERVAL '1 hour')
ON CONFLICT (session_id, round_number) DO NOTHING;

INSERT INTO public.blind_date_session_questions
    (id, session_id, round_id, source_question_id, question_text, answer_text, language_code, sort_order)
SELECT gen_random_uuid(),
       'ee000000-0000-4000-8000-0000000000a5',
       'ee000000-0000-4000-8000-0000000000b1',
       q.id, t.question, ca.answer_text, 'en', q.sort_order
FROM public.blind_date_questions q
JOIN public.blind_date_question_translations t
  ON t.question_id = q.id AND t.language_code = 'en'
JOIN (VALUES
    ('BD_Q_VALUES_NON_NEGOTIABLE', 'Honesty, always.'),
    ('BD_Q_REL_DEAL_BREAKER',      'Disrespect toward waiters.'),
    ('BD_Q_FUN_SUPERPOWER',        'Teleportation — no more traffic.')
) AS ca(code, answer_text) ON ca.code = q.code
WHERE q.code IN ('BD_Q_VALUES_NON_NEGOTIABLE', 'BD_Q_REL_DEAL_BREAKER', 'BD_Q_FUN_SUPERPOWER')
ON CONFLICT DO NOTHING;

INSERT INTO public.blind_date_session_participants
    (id, session_id, user_id, status, current_round_id, credit_charge_idempotency_key, joined_at)
SELECT p.participant_id,
       'ee000000-0000-4000-8000-0000000000a5',
       p.user_id, 'ACTIVE',
       'ee000000-0000-4000-8000-0000000000b1',
       gen_random_uuid(),
       NOW() - (p.idx || ' minutes')::interval
FROM (VALUES
    ('ee000000-0000-4000-8000-0000000000c1'::uuid, '464482c2-aad9-4c67-8f6f-f1acfe784b7c'::uuid, 50),
    ('ee000000-0000-4000-8000-0000000000c2',       '56bc199c-019f-4bc2-b96a-7b05b55daaa0',       30)
) AS p(participant_id, user_id, idx)
ON CONFLICT (session_id, user_id) DO NOTHING;

INSERT INTO public.blind_date_session_answers (participant_id, session_question_id, answer, submitted_at)
SELECT p.id, sq.id, a.answer, NOW() - (a.mins || ' minutes')::interval
FROM public.blind_date_session_participants p
JOIN (VALUES
    ('464482c2-aad9-4c67-8f6f-f1acfe784b7c'::uuid, 1, 'Keeping my word.', 45),
    ('464482c2-aad9-4c67-8f6f-f1acfe784b7c',       2, 'Constant phone checking.', 40),
    ('464482c2-aad9-4c67-8f6f-f1acfe784b7c',       3, 'Flying, obviously.', 35),
    ('56bc199c-019f-4bc2-b96a-7b05b55daaa0',       1, 'Kindness to strangers.', 25),
    ('56bc199c-019f-4bc2-b96a-7b05b55daaa0',       2, 'Arrogance.', 20),
    ('56bc199c-019f-4bc2-b96a-7b05b55daaa0',       3, 'Time travel to fix my mistakes.', 15)
) AS a(user_id, sort_order, answer, mins) ON a.user_id = p.user_id
JOIN public.blind_date_session_questions sq
  ON sq.round_id = 'ee000000-0000-4000-8000-0000000000b1'
 AND sq.sort_order = a.sort_order
WHERE p.session_id = 'ee000000-0000-4000-8000-0000000000a5'
ON CONFLICT (participant_id, session_question_id) DO NOTHING;

-- ── Session F — creator u14, fresh session with zero participants ────────────
INSERT INTO public.blind_date_sessions
    (id, creator_user_id, status, language_code, expires_at, credit_charge_idempotency_key)
VALUES
    ('ff000000-0000-4000-8000-0000000000a6',
     'f9e79197-0b32-41a7-bc78-941bc64e427e',
     'OPEN', 'en', NOW() + INTERVAL '5 days', gen_random_uuid())
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.blind_date_session_rounds
    (id, session_id, round_number, status, started_at)
VALUES
    ('ff000000-0000-4000-8000-0000000000b1',
     'ff000000-0000-4000-8000-0000000000a6', 1, 'OPEN', NOW() - INTERVAL '15 minutes')
ON CONFLICT (session_id, round_number) DO NOTHING;

INSERT INTO public.blind_date_session_questions
    (id, session_id, round_id, source_question_id, question_text, answer_text, language_code, sort_order)
SELECT gen_random_uuid(),
       'ff000000-0000-4000-8000-0000000000a6',
       'ff000000-0000-4000-8000-0000000000b1',
       q.id, t.question, ca.answer_text, 'en', q.sort_order
FROM public.blind_date_questions q
JOIN public.blind_date_question_translations t
  ON t.question_id = q.id AND t.language_code = 'en'
JOIN (VALUES
    ('BD_Q_INTRO_PERFECT_DAY',   'Beach morning, museum afternoon, live music at night.'),
    ('BD_Q_REL_LOOKING_FOR',     'A teammate for life.'),
    ('BD_Q_FUN_DESERT_ISLAND',   'A boat. I am leaving.')
) AS ca(code, answer_text) ON ca.code = q.code
WHERE q.code IN ('BD_Q_INTRO_PERFECT_DAY', 'BD_Q_REL_LOOKING_FOR', 'BD_Q_FUN_DESERT_ISLAND')
ON CONFLICT DO NOTHING;

COMMIT;

-- Quick sanity check
SELECT s.id, s.status, s.creator_user_id,
       (SELECT COUNT(*) FROM blind_date_session_participants p WHERE p.session_id = s.id) AS participants
FROM blind_date_sessions s
ORDER BY s.created_at;

-- =============================================================================
-- V67: Blind Date feature
--
-- Multi-round, creator-led dating experience with anonymous participation until
-- a final reveal. See docs/important/Qal_Blind_Date_Full_Feature_Design.md
--
--  1. Platform language catalog + question catalog (categories, questions,
--     normalized translations)
--  2. Per-user configuration and permanent question set
--  3. Sessions, rounds, immutable question snapshots
--  4. Participants, answers, round selections
--  5. Final reveal decisions
--  6. feature_actions: BLIND_DATE_SESSION_CREATE / BLIND_DATE_PARTICIPATE
--  7. Dedicated inactive BLIND_DATE variant under LIKE
--  8. user_discovery_actions.action_source (rewind exclusion marker)
--  9. Seed platform catalog (en + am)
-- =============================================================================


-- =============================================================================
-- 1. PLATFORM LANGUAGE + QUESTION CATALOG
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.blind_date_languages (
    code        VARCHAR(10)  PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.blind_date_question_categories (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(100) NOT NULL UNIQUE,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    icon_url    TEXT,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_bd_question_categories_active
    ON public.blind_date_question_categories(active, sort_order);

CREATE TABLE IF NOT EXISTS public.blind_date_question_category_translations (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id   UUID         NOT NULL
        REFERENCES public.blind_date_question_categories(id) ON DELETE CASCADE,
    language_code VARCHAR(10)  NOT NULL,
    name          VARCHAR(255) NOT NULL,
    description   TEXT,

    CONSTRAINT uq_bd_category_translation UNIQUE (category_id, language_code)
);

CREATE TABLE IF NOT EXISTS public.blind_date_questions (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id UUID         NOT NULL
        REFERENCES public.blind_date_question_categories(id) ON DELETE RESTRICT,
    code        VARCHAR(100) UNIQUE,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_bd_questions_category
    ON public.blind_date_questions(category_id, active, sort_order);

CREATE TABLE IF NOT EXISTS public.blind_date_question_translations (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id   UUID        NOT NULL
        REFERENCES public.blind_date_questions(id) ON DELETE CASCADE,
    language_code VARCHAR(10) NOT NULL,
    question      TEXT        NOT NULL,

    CONSTRAINT uq_bd_question_translation UNIQUE (question_id, language_code)
);


-- =============================================================================
-- 2. PER-USER CONFIGURATION AND PERMANENT QUESTION SET
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.blind_date_configurations (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID        NOT NULL UNIQUE
        REFERENCES public.profiles(user_id) ON DELETE CASCADE,
    enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
    language_code VARCHAR(10) NOT NULL DEFAULT 'en',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.blind_date_question_sets (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL UNIQUE
        REFERENCES public.profiles(user_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.blind_date_question_set_questions (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    question_set_id UUID        NOT NULL
        REFERENCES public.blind_date_question_sets(id) ON DELETE CASCADE,
    question_id     UUID        NOT NULL
        REFERENCES public.blind_date_questions(id) ON DELETE RESTRICT,
    sort_order      INTEGER     NOT NULL DEFAULT 0,
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_bd_question_set_question UNIQUE (question_set_id, question_id)
);

CREATE TABLE IF NOT EXISTS public.blind_date_question_set_answers (
    id                       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    question_set_question_id UUID        NOT NULL UNIQUE
        REFERENCES public.blind_date_question_set_questions(id) ON DELETE CASCADE,
    answer                   TEXT        NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.blind_date_custom_questions (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    question_set_id UUID        NOT NULL
        REFERENCES public.blind_date_question_sets(id) ON DELETE CASCADE,
    question        TEXT        NOT NULL,
    answer          TEXT        NOT NULL,
    sort_order      INTEGER     NOT NULL DEFAULT 0,
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Moderated UGC: reject blank text and cap length (design §31)
    CONSTRAINT chk_bd_custom_question_text CHECK (
        BTRIM(question) <> '' AND LENGTH(question) <= 500
    ),
    CONSTRAINT chk_bd_custom_answer_text CHECK (
        BTRIM(answer) <> '' AND LENGTH(answer) <= 2000
    )
);

CREATE INDEX IF NOT EXISTS idx_bd_custom_questions_set
    ON public.blind_date_custom_questions(question_set_id, active, sort_order);


-- =============================================================================
-- 3. SESSIONS, ROUNDS, QUESTION SNAPSHOTS
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.blind_date_sessions (
    id                            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    creator_user_id               UUID        NOT NULL
        REFERENCES public.profiles(user_id) ON DELETE CASCADE,
    status                        VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    language_code                 VARCHAR(10) NOT NULL DEFAULT 'en',
    expires_at                    TIMESTAMPTZ,
    credit_charge_idempotency_key UUID        NOT NULL UNIQUE,
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_at                     TIMESTAMPTZ,
    completed_at                  TIMESTAMPTZ,
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_bd_session_status CHECK (
        status IN ('OPEN', 'REVEAL', 'CLOSED', 'EXPIRED', 'COMPLETED', 'CANCELLED')
    )
);

-- A creator occupies their slot while OPEN or awaiting final decisions (REVEAL).
CREATE UNIQUE INDEX IF NOT EXISTS uq_blind_date_one_active_session_per_creator
    ON public.blind_date_sessions (creator_user_id)
    WHERE status IN ('OPEN', 'REVEAL');

-- Discover feed + expiry worker
CREATE INDEX IF NOT EXISTS idx_bd_sessions_discover
    ON public.blind_date_sessions(status, expires_at);

CREATE TABLE IF NOT EXISTS public.blind_date_session_rounds (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id   UUID        NOT NULL
        REFERENCES public.blind_date_sessions(id) ON DELETE CASCADE,
    round_number INTEGER     NOT NULL CHECK (round_number >= 1),
    status       VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,

    CONSTRAINT uq_bd_session_round UNIQUE (session_id, round_number),
    CONSTRAINT chk_bd_round_status CHECK (status IN ('OPEN', 'CLOSED'))
);

-- At most one OPEN round per session (design §23)
CREATE UNIQUE INDEX IF NOT EXISTS uq_bd_one_open_round_per_session
    ON public.blind_date_session_rounds (session_id)
    WHERE status = 'OPEN';

CREATE TABLE IF NOT EXISTS public.blind_date_session_questions (
    id                       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Denormalized so session-scoped question uniqueness (rule 32) is enforceable
    session_id               UUID        NOT NULL
        REFERENCES public.blind_date_sessions(id) ON DELETE CASCADE,
    round_id                 UUID        NOT NULL
        REFERENCES public.blind_date_session_rounds(id) ON DELETE CASCADE,
    source_question_id       UUID
        REFERENCES public.blind_date_questions(id) ON DELETE SET NULL,
    source_custom_question_id UUID
        REFERENCES public.blind_date_custom_questions(id) ON DELETE SET NULL,
    question_text            TEXT        NOT NULL,
    -- The creator's own answer, snapshotted alongside the question (design §8).
    -- Nullable because a permanent-set question may not have an answer yet; the
    -- service rejects snapshotting unanswered questions.
    answer_text              TEXT,
    language_code            VARCHAR(10) NOT NULL,
    sort_order               INTEGER     NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_bd_session_questions_round
    ON public.blind_date_session_questions(round_id, sort_order);

-- Rule 32: a question used in one round cannot be reused in a later round of
-- the same session.
CREATE UNIQUE INDEX IF NOT EXISTS uq_bd_session_platform_question
    ON public.blind_date_session_questions (session_id, source_question_id)
    WHERE source_question_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_bd_session_custom_question
    ON public.blind_date_session_questions (session_id, source_custom_question_id)
    WHERE source_custom_question_id IS NOT NULL;


-- =============================================================================
-- 4. PARTICIPANTS, ANSWERS, SELECTIONS
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.blind_date_session_participants (
    id                            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id                    UUID        NOT NULL
        REFERENCES public.blind_date_sessions(id) ON DELETE CASCADE,
    user_id                       UUID        NOT NULL
        REFERENCES public.profiles(user_id) ON DELETE CASCADE,
    status                        VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    current_round_id              UUID
        REFERENCES public.blind_date_session_rounds(id) ON DELETE SET NULL,
    credit_charge_idempotency_key UUID        NOT NULL UNIQUE,
    joined_at                     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    eliminated_at                 TIMESTAMPTZ,
    advanced_at                   TIMESTAMPTZ,
    finalist_at                   TIMESTAMPTZ,
    withdrawn_at                  TIMESTAMPTZ,
    revealed_at                   TIMESTAMPTZ,
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_bd_session_participant UNIQUE (session_id, user_id),
    CONSTRAINT chk_bd_participant_status CHECK (
        status IN ('ACTIVE', 'ADVANCED', 'ELIMINATED', 'FINALIST', 'REVEALED', 'WITHDRAWN')
    )
);

CREATE INDEX IF NOT EXISTS idx_bd_participants_session_status
    ON public.blind_date_session_participants(session_id, status);

CREATE INDEX IF NOT EXISTS idx_bd_participants_user
    ON public.blind_date_session_participants(user_id);

CREATE INDEX IF NOT EXISTS idx_bd_participants_round
    ON public.blind_date_session_participants(current_round_id);

-- A creator must never participate in their own session (design §25)
CREATE OR REPLACE FUNCTION public.blind_date_reject_creator_participation()
RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM public.blind_date_sessions s
        WHERE s.id = NEW.session_id
          AND s.creator_user_id = NEW.user_id
    ) THEN
        RAISE EXCEPTION 'Blind Date creator cannot participate in their own session';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_bd_reject_creator_participation
    ON public.blind_date_session_participants;

CREATE TRIGGER trg_bd_reject_creator_participation
    BEFORE INSERT OR UPDATE OF user_id, session_id
    ON public.blind_date_session_participants
    FOR EACH ROW
    EXECUTE FUNCTION public.blind_date_reject_creator_participation();

CREATE TABLE IF NOT EXISTS public.blind_date_session_answers (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    participant_id      UUID        NOT NULL
        REFERENCES public.blind_date_session_participants(id) ON DELETE CASCADE,
    session_question_id UUID        NOT NULL
        REFERENCES public.blind_date_session_questions(id) ON DELETE CASCADE,
    answer              TEXT        NOT NULL,
    submitted_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_bd_session_answer UNIQUE (participant_id, session_question_id),
    CONSTRAINT chk_bd_answer_text CHECK (
        BTRIM(answer) <> '' AND LENGTH(answer) <= 2000
    )
);

CREATE TABLE IF NOT EXISTS public.blind_date_round_selections (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    round_id            UUID        NOT NULL
        REFERENCES public.blind_date_session_rounds(id) ON DELETE CASCADE,
    participant_id      UUID        NOT NULL
        REFERENCES public.blind_date_session_participants(id) ON DELETE CASCADE,
    selected_by_user_id UUID        NOT NULL
        REFERENCES public.profiles(user_id) ON DELETE RESTRICT,
    decision            VARCHAR(30) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_bd_round_selection UNIQUE (round_id, participant_id),
    CONSTRAINT chk_bd_round_selection_decision CHECK (
        decision IN ('ADVANCE', 'ELIMINATE', 'SELECT_FINALIST')
    )
);

CREATE INDEX IF NOT EXISTS idx_bd_round_selections_participant
    ON public.blind_date_round_selections(participant_id);


-- =============================================================================
-- 5. FINAL REVEAL DECISIONS
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.blind_date_final_decisions (
    id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id              UUID        NOT NULL UNIQUE
        REFERENCES public.blind_date_sessions(id) ON DELETE CASCADE,
    finalist_participant_id UUID        NOT NULL UNIQUE
        REFERENCES public.blind_date_session_participants(id) ON DELETE RESTRICT,
    revealed_at             TIMESTAMPTZ,
    decision_deadline_at    TIMESTAMPTZ,
    creator_decision        VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    participant_decision    VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    creator_decided_at      TIMESTAMPTZ,
    participant_decided_at  TIMESTAMPTZ,
    outcome                 VARCHAR(30),
    match_id                UUID        REFERENCES public.matches(id) ON DELETE SET NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_bd_creator_decision CHECK (
        creator_decision IN ('PENDING', 'INTERESTED', 'NOT_INTERESTED')
    ),
    CONSTRAINT chk_bd_participant_decision CHECK (
        participant_decision IN ('PENDING', 'INTERESTED', 'NOT_INTERESTED')
    ),
    CONSTRAINT chk_bd_outcome CHECK (
        outcome IS NULL
        OR outcome IN ('MATCHED', 'NO_MATCH', 'ALREADY_MATCHED', 'EXPIRED')
    )
);

CREATE INDEX IF NOT EXISTS idx_bd_final_decisions_deadline
    ON public.blind_date_final_decisions(decision_deadline_at)
    WHERE outcome IS NULL;


-- =============================================================================
-- 6. FEATURE ACTIONS
-- =============================================================================

INSERT INTO public.feature_actions (code, name, type)
VALUES
    ('BLIND_DATE_SESSION_CREATE', 'Create Blind Date Session', 'ACTION'),
    ('BLIND_DATE_PARTICIPATE',    'Join Blind Date',           'ACTION')
ON CONFLICT (code) DO NOTHING;


-- =============================================================================
-- 7. DEDICATED BLIND_DATE LIKE VARIANT
--    Inserted inactive on purpose: findActiveByActionCode('LIKE') feeds the
--    swipe variant picker, so an inactive variant never becomes a user-selectable
--    option and needs no pricing configuration. findByActionCodeAndVariantCode
--    still resolves it so display metadata works for historical actions.
-- =============================================================================

INSERT INTO public.action_feature_variants
    (feature_action_id, code, name, description, icon, active, sort_order)
SELECT fa.id, 'BLIND_DATE', 'Blind Date', 'Matched through Blind Date',
       'https://cdn.qal.app/actions/blind-date.webp', FALSE, 99
FROM public.feature_actions fa
WHERE fa.code = 'LIKE'
ON CONFLICT (feature_action_id, code) DO NOTHING;


-- =============================================================================
-- 8. SYSTEM-GENERATED ACTION MARKER
--    Lets the rewind lookup exclude system-generated blind date likes so they
--    can never be the "last action" a user rewinds.
-- =============================================================================

ALTER TABLE public.user_discovery_actions
    ADD COLUMN IF NOT EXISTS action_source VARCHAR(30) NOT NULL DEFAULT 'DISCOVERY';

ALTER TABLE public.user_discovery_actions
    DROP CONSTRAINT IF EXISTS user_discovery_actions_action_source_check;

ALTER TABLE public.user_discovery_actions
    ADD CONSTRAINT user_discovery_actions_action_source_check CHECK (
        action_source IN ('DISCOVERY', 'BLIND_DATE')
    );

CREATE INDEX IF NOT EXISTS idx_user_discovery_actions_rewind_lookup
    ON public.user_discovery_actions(actor_user_id, created_at DESC)
    WHERE status = 'ACTIVE' AND action_source = 'DISCOVERY';


-- =============================================================================
-- 9. SEED PLATFORM CATALOG
-- =============================================================================

INSERT INTO public.blind_date_languages (code, name, active, sort_order)
VALUES
    ('en', 'English', TRUE, 1),
    ('am', 'አማርኛ',   TRUE, 2),
    ('ti', 'ትግርኛ',   TRUE, 3),
    ('om', 'Oromoo', TRUE, 4)
ON CONFLICT (code) DO NOTHING;

INSERT INTO public.blind_date_question_categories (code, name, description, sort_order, active)
VALUES
    ('GETTING_TO_KNOW', 'Getting to Know You', 'Light, everyday questions',      1, TRUE),
    ('VALUES_BELIEFS',  'Values & Beliefs',    'What matters most to you',        2, TRUE),
    ('LIFESTYLE',       'Lifestyle',           'How you spend your days',         3, TRUE),
    ('RELATIONSHIPS',   'Relationships',       'Love, family and commitment',     4, TRUE),
    ('FUN_RANDOM',      'Fun & Random',        'Playful conversation starters',   5, TRUE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO public.blind_date_questions (category_id, code, sort_order, active)
SELECT c.id, q.code, q.sort_order, TRUE
FROM (VALUES
    ('GETTING_TO_KNOW', 'BD_Q_INTRO_THREE_WORDS',      1),
    ('GETTING_TO_KNOW', 'BD_Q_INTRO_PERFECT_DAY',      2),
    ('GETTING_TO_KNOW', 'BD_Q_INTRO_PROUDEST',         3),
    ('VALUES_BELIEFS',  'BD_Q_VALUES_NON_NEGOTIABLE',  1),
    ('VALUES_BELIEFS',  'BD_Q_VALUES_FAITH_ROLE',      2),
    ('VALUES_BELIEFS',  'BD_Q_VALUES_FAMILY_MEANING',  3),
    ('LIFESTYLE',       'BD_Q_LIFE_MORNING_ROUTINE',   1),
    ('LIFESTYLE',       'BD_Q_LIFE_WEEKEND',           2),
    ('LIFESTYLE',       'BD_Q_LIFE_FIVE_YEARS',        3),
    ('RELATIONSHIPS',   'BD_Q_REL_LOOKING_FOR',        1),
    ('RELATIONSHIPS',   'BD_Q_REL_LOVE_LANGUAGE',      2),
    ('RELATIONSHIPS',   'BD_Q_REL_DEAL_BREAKER',       3),
    ('FUN_RANDOM',      'BD_Q_FUN_SUPERPOWER',         1),
    ('FUN_RANDOM',      'BD_Q_FUN_LAST_LAUGH',         2),
    ('FUN_RANDOM',      'BD_Q_FUN_DESERT_ISLAND',      3)
) AS q(category_code, code, sort_order)
JOIN public.blind_date_question_categories c ON c.code = q.category_code
ON CONFLICT (code) DO NOTHING;

INSERT INTO public.blind_date_question_category_translations (category_id, language_code, name, description)
SELECT c.id, t.language_code, t.name, t.description
FROM (VALUES
    ('GETTING_TO_KNOW', 'en', 'Getting to Know You',  'Light, everyday questions'),
    ('GETTING_TO_KNOW', 'am', 'እንተዋወቅ',              'ቀላል የዕለት ተዕለት ጥያቄዎች'),
    ('VALUES_BELIEFS',  'en', 'Values & Beliefs',     'What matters most to you'),
    ('VALUES_BELIEFS',  'am', 'እምነትና መርሆች',          'ለእርስዎ በጣም አስፈላጊ የሆነው'),
    ('LIFESTYLE',       'en', 'Lifestyle',            'How you spend your days'),
    ('LIFESTYLE',       'am', 'የአኗኗር ዘይቤ',            'ቀንዎን እንዴት ያሳልፋሉ'),
    ('RELATIONSHIPS',   'en', 'Relationships',        'Love, family and commitment'),
    ('RELATIONSHIPS',   'am', 'ግንኙነቶች',              'ፍቅር፣ ቤተሰብ እና ቁርጠኝነት'),
    ('FUN_RANDOM',      'en', 'Fun & Random',         'Playful conversation starters'),
    ('FUN_RANDOM',      'am', 'አዝናኝ ጥያቄዎች',          'አዝናኝ የጨዋታ መጀመሪያዎች')
) AS t(category_code, language_code, name, description)
JOIN public.blind_date_question_categories c ON c.code = t.category_code
ON CONFLICT (category_id, language_code) DO NOTHING;

INSERT INTO public.blind_date_question_translations (question_id, language_code, question)
SELECT q.id, t.language_code, t.question
FROM (VALUES
    ('BD_Q_INTRO_THREE_WORDS',     'en', 'Describe yourself in three words.'),
    ('BD_Q_INTRO_THREE_WORDS',     'am', 'ራስዎን በሶስት ቃላት ይግለጹ።'),
    ('BD_Q_INTRO_PERFECT_DAY',     'en', 'What does your perfect day look like?'),
    ('BD_Q_INTRO_PERFECT_DAY',     'am', 'ፍጹም ቀንዎ ምን ይመስላል?'),
    ('BD_Q_INTRO_PROUDEST',        'en', 'What are you most proud of?'),
    ('BD_Q_INTRO_PROUDEST',        'am', 'በምን ነገር በጣም ይኮራሉ?'),
    ('BD_Q_VALUES_NON_NEGOTIABLE', 'en', 'What is one value you will never compromise on?'),
    ('BD_Q_VALUES_NON_NEGOTIABLE', 'am', 'ፈጽሞ የማይደራደሩበት አንድ መርህ ምንድን ነው?'),
    ('BD_Q_VALUES_FAITH_ROLE',     'en', 'What role does faith play in your life?'),
    ('BD_Q_VALUES_FAITH_ROLE',     'am', 'እምነት በሕይወትዎ ውስጥ ምን ሚና ይጫወታል?'),
    ('BD_Q_VALUES_FAMILY_MEANING', 'en', 'What does family mean to you?'),
    ('BD_Q_VALUES_FAMILY_MEANING', 'am', 'ቤተሰብ ለእርስዎ ምን ማለት ነው?'),
    ('BD_Q_LIFE_MORNING_ROUTINE',  'en', 'How do you start your mornings?'),
    ('BD_Q_LIFE_MORNING_ROUTINE',  'am', 'ጠዋትዎን እንዴት ይጀምራሉ?'),
    ('BD_Q_LIFE_WEEKEND',          'en', 'What is your ideal weekend?'),
    ('BD_Q_LIFE_WEEKEND',          'am', 'ተመራጭ የሳምንት መጨረሻዎ ምን ይመስላል?'),
    ('BD_Q_LIFE_FIVE_YEARS',       'en', 'Where do you see yourself in five years?'),
    ('BD_Q_LIFE_FIVE_YEARS',       'am', 'ከአምስት ዓመት በኋላ ራስዎን የት ያያሉ?'),
    ('BD_Q_REL_LOOKING_FOR',       'en', 'What are you looking for in a partner?'),
    ('BD_Q_REL_LOOKING_FOR',       'am', 'በአጋርዎ ውስጥ ምን ይፈልጋሉ?'),
    ('BD_Q_REL_LOVE_LANGUAGE',     'en', 'How do you show someone you care?'),
    ('BD_Q_REL_LOVE_LANGUAGE',     'am', 'ለአንድ ሰው እንደሚያስቡ እንዴት ያሳያሉ?'),
    ('BD_Q_REL_DEAL_BREAKER',      'en', 'What is an instant deal breaker for you?'),
    ('BD_Q_REL_DEAL_BREAKER',      'am', 'ወዲያውኑ ግንኙነት የሚያቋርጥብዎ ነገር ምንድን ነው?'),
    ('BD_Q_FUN_SUPERPOWER',        'en', 'If you had one superpower, what would it be?'),
    ('BD_Q_FUN_SUPERPOWER',        'am', 'አንድ ልዩ ኃይል ቢኖርዎት ምን ይሆን ነበር?'),
    ('BD_Q_FUN_LAST_LAUGH',        'en', 'What last made you laugh out loud?'),
    ('BD_Q_FUN_LAST_LAUGH',        'am', 'በመጨረሻ ጮክ ብለው የሳቁት በምን ነበር?'),
    ('BD_Q_FUN_DESERT_ISLAND',     'en', 'Three things you would take to a desert island?'),
    ('BD_Q_FUN_DESERT_ISLAND',     'am', 'ወደ ምድረ በዳ ደሴት ሶስት ምን ይዘው ይሄዳሉ?')
) AS t(question_code, language_code, question)
JOIN public.blind_date_questions q ON q.code = t.question_code
ON CONFLICT (question_id, language_code) DO NOTHING;

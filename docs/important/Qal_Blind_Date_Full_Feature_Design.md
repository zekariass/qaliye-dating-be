# Qal Blind Date — Full Feature Design

## 1. Purpose

Blind Date is a multi-round, creator-led dating experience.

A user creates a Blind Date session containing questions and answers. Other users can discover and participate in **Round 1**. Participants answer the creator's questions without seeing the creator's identity.

The creator reviews the answers and progressively filters participants through additional rounds. Only the final selected participant reaches the reveal stage. At the end, both people independently decide whether they are interested. A match is created only when **both sides are interested**.

The feature is intentionally different from ordinary swipe-based dating.

---

## 2. Core Product Flow

```text
Creator prepares permanent Blind Date Question Set
                    |
                    v
          Creates Blind Date Session
                    |
          BLIND_DATE_SESSION_CREATE
                    |
                    v
                ROUND 1
                    |
          Multiple users participate
                    |
          BLIND_DATE_PARTICIPATE
                    |
                    v
        Participants answer questions
                    |
                    v
          Creator reviews answers
                    |
          +---------+----------+
          |                    |
       Eliminate             Advance
          |                    |
          v                    v
      Removed              ROUND 2
      from session             |
                               v
                         More questions
                               |
                               v
                         Creator filters
                               |
                               v
                            ROUND 3
                               |
                               v
                          Finalist (1)
                               |
                               v
                           REVEAL
                               |
                +--------------+--------------+
                |                             |
         Creator decides              Finalist decides
                |                             |
                +--------------+--------------+
                               |
                      Both interested?
                         /          \
                       Yes           No
                        |             |
                      MATCH       No Match
```

---

## 3. Main Concepts

### 3.1 Blind Date Question Set

A reusable, permanent configuration owned by a user.

The user can:

- Select questions from the platform question pool.
- Select a category first, then browse questions in that category.
- Add custom questions.
- Remove questions.
- Reorder questions.
- Update answers.
- Change the preferred Blind Date language.
- Maintain the question set without affecting existing sessions.

The question set is **not itself a paid action**.

### 3.2 Blind Date Session

A published Blind Date opportunity created from the user's question set.

A session:

- Has one creator.
- Has a configurable lifetime.
- Can be manually closed.
- Can expire automatically.
- Contains immutable snapshots of the questions used by its rounds.
- Allows many participants.
- Has multiple rounds.
- Only exposes Round 1 for new participants.
- Keeps the creator anonymous until the final reveal.

The creator pays `BLIND_DATE_SESSION_CREATE` when creating the session.

### 3.3 Blind Date Round

A stage within a session.

Round 1 is the only publicly joinable round.

Later rounds are only available to participants whom the creator advances.

Each round can have its own questions. The creator can freely choose questions from their permanent question set for each round and can use custom questions where supported.

### 3.4 Participant

A user who joins Round 1 of a Blind Date session.

The participant:

- Pays `BLIND_DATE_PARTICIPATE` once.
- Answers Round 1 questions.
- May be advanced to later rounds.
- Is not charged again for later rounds.
- Is eliminated if the creator does not select them.
- Loses access to the session after elimination.
- Can reach the final reveal only if selected as the sole finalist.

---

## 4. Monetization

Blind Date uses the existing Qal action-feature and subscription-plan cost/limit infrastructure.

The following two feature codes must be added to the existing `feature_actions` table and configured through the existing `subscription_plan_limit_and_cost` table:

```text
BLIND_DATE_SESSION_CREATE
BLIND_DATE_PARTICIPATE
```

### 4.1 BLIND_DATE_SESSION_CREATE

Paid by the creator when creating/publishing a Blind Date session.

The cost and usage limit are determined by the existing subscription-plan configuration.

Conceptually:

```text
Creator
   |
   +-- Create Blind Date Session
   |
   +-- action feature:
       BLIND_DATE_SESSION_CREATE
   |
   +-- configured credit cost/limit
```

### 4.2 BLIND_DATE_PARTICIPATE

Paid by a participant when joining Round 1 of a Blind Date session.

The cost and usage limit are determined by the existing subscription-plan configuration.

The participant is charged only once for that Blind Date session. Advancing to later rounds does not create another participation charge.

```text
Participant
   |
   +-- Join Round 1
   |
   +-- action feature:
       BLIND_DATE_PARTICIPATE
   |
   +-- configured credit cost/limit
```

### 4.3 No Credit Charge for Final Mutual Match

When the creator and finalist both choose `INTERESTED`, the system creates the normal Qal match and records the two corresponding `LIKE` actions using the dedicated `BLIND_DATE` variant.

This automatic final matching process does **not** charge credits.

It is a system-generated consequence of the Blind Date flow, not a separately purchased discovery action.

### 4.4 Idempotency

Both paid actions must be idempotent:

- `BLIND_DATE_SESSION_CREATE`
- `BLIND_DATE_PARTICIPATE`

A retry, double tap, timeout, or duplicate API request must never result in two charges.

Use a unique idempotency key for each logical charge.

The charge and the corresponding Blind Date state transition should be performed transactionally.

## 5. User Blind Date Question Set

### 5.1 Language

Each user chooses the language in which they want to conduct their Blind Date.

Store the language code in the user's persistent Blind Date configuration, for example:

```text
language_code = "am"
```

The client retrieves the appropriate translation from the translation tables.

The supported language list should be controlled by the platform.

---

## 6. Platform Question Selection

When creating the permanent question set:

```text
Add Question
    |
    +-- Platform Question
    |
    +-- Custom Question
```

For a platform question:

```text
Select Category
       |
       v
Fetch questions for category
       |
       v
Select question
       |
       v
Provide answer
```

The platform question text is translated through normalized translation tables.

---

## 7. Custom Questions

Users can create their own questions.

Custom questions belong to the user's permanent Blind Date Question Set.

They should support:

- Question text.
- Answer.
- Sort order.
- Active/inactive status if needed.
- Creation/update timestamps.

There is no hard-coded maximum number unless product requirements later introduce one.

---

## 8. Permanent Set vs Session Snapshot

This distinction is critical.

### Permanent Question Set

The user can change it at any time.

```text
User A Question Set

Q1 + Answer
Q2 + Answer
Q3 + Answer
Custom Q4 + Answer
```

### Session Snapshot

When a session/round is created, the selected questions are copied into immutable session records.

```text
Permanent Set
     |
     v
Session Round 1
     |
     +-- Question snapshot
     +-- Answer snapshot
```

If the user later changes the permanent question set, existing session snapshots are unaffected.

---

## 9. Session Lifecycle

Recommended session states:

```text
OPEN
REVEAL
CLOSED
EXPIRED
COMPLETED
CANCELLED
```

- **OPEN:** Round 1 can be discovered and new participants can join.
- **REVEAL:** A finalist has been selected. The session is no longer discoverable or joinable; the system is waiting for both final decisions.
- **CLOSED:** Creator manually closes the session before a finalist is selected.
- **EXPIRED:** `expires_at` has been reached.
- **COMPLETED:** Final reveal and mutual decision process is complete (match, no-match, or already-matched).
- **CANCELLED:** Optional administrative/system state.

`expires_at` should be nullable. `NULL` means the session does not automatically expire and must be closed manually.

### 9.1 Session Termination Handling

Termination paths differ by state:

- **Manual close** is only allowed while `OPEN`. Once a finalist exists (`REVEAL`), the creator cannot close the session — they must submit a final decision instead.
- **Expiry** of an `OPEN` session transitions it to `EXPIRED`.
- **Expiry or decision-deadline pass** of a `REVEAL` session resolves each `PENDING` decision as `NOT_INTERESTED` and completes the session with outcome `EXPIRED`.
- **Admin cancellation** transitions the session to `CANCELLED` from any non-terminal state.

In all cases:

- All participants who are not the finalist transition to `ELIMINATED` with `eliminated_at` set.
- Any round still in `OPEN` status transitions to `CLOSED`.
- No credits are refunded automatically. `BLIND_DATE_SESSION_CREATE` and `BLIND_DATE_PARTICIPATE` are consumed at charge time; refunds, if ever needed, are a manual admin operation.

A scheduled worker on the existing Quartz infrastructure scans for `OPEN`/`REVEAL` sessions whose `expires_at` or final-decision deadline has passed and applies the transitions above.

---

## 10. One Active Session Per Creator

Recommended rule: a creator can have only one active Blind Date session at a time. A session counts as active while it is `OPEN` or `REVEAL` — a session awaiting final decisions still occupies the creator's slot.

```sql
CREATE UNIQUE INDEX uq_blind_date_one_active_session_per_creator
ON public.blind_date_sessions (creator_user_id)
WHERE status IN ('OPEN', 'REVEAL');
```

---

## 11. Round Model

A session can contain multiple rounds.

Example:

```text
Session 123

Round 1
20 participants
       |
       +-- 15 eliminated
       |
       +-- 5 advanced

Round 2
5 participants
       |
       +-- 3 eliminated
       |
       +-- 2 advanced

Round 3
2 participants
       |
       +-- 1 eliminated
       |
       +-- 1 finalist

Reveal
       |
       v
Mutual decision
```

Only Round 1 is discoverable to new users.

---

## 12. Round Questions

The creator can choose different questions for each round.

There is no requirement that every round use the same questions.

The creator controls the questions for each round, selecting from the permanent question set and adding custom questions where appropriate.

---

## 12.1 Cross-Round Question Uniqueness

A question selected for one round must not be selectable again in any later round of the same Blind Date session.

This applies to both:

- Platform questions.
- Custom questions.

Example:

```text
Round 1:
Q1, Q2, Q3

Round 2:
Q4, Q5, Q6

Round 3:
Q7, Q8
```

The backend must reject any attempt to add a question to a later round if that question was already used in an earlier round of the same session.

This rule applies to the underlying question identity, not merely the displayed translated text.

For platform questions, compare `source_question_id`.

For custom questions, compare `source_custom_question_id`.

## 13. Participant Discovery and Privacy

### 13.1 Only Round 1 is discoverable

A participant can only enter through Round 1.

They cannot discover or directly join Round 2 or later.

### 13.2 Creator identity remains hidden

Until the final reveal, participants must not be able to identify the creator.

Do not expose:

- Creator photo.
- Creator name.
- Creator username.
- Creator public profile.
- Other identifying profile information.

### 13.3 Eliminated users

Once a participant is filtered out/eliminated:

- They lose access to the Blind Date session.
- They cannot re-enter.
- They cannot continue seeing the creator's Blind Date.
- They cannot use the session to gather more information.

This also reduces abuse involving multiple accounts attempting to learn information about the same creator.

---

## 14. Participant States

Recommended states:

```text
ACTIVE
ADVANCED
ELIMINATED
FINALIST
REVEALED
WITHDRAWN
```

Possible lifecycle:

```text
ACTIVE
  |
  +-- ELIMINATED
  |
  +-- WITHDRAWN
  |
  +-- ADVANCED
         |
         +-- ADVANCED
                |
                +-- FINALIST
                       |
                       +-- REVEALED
```

`MATCHED` should not be a participant state. Matching is a separate outcome between the two users.

### 14.1 Current Round Tracking

Each participant row carries `current_round_id`, set to Round 1 on join. When the creator records `ADVANCE` for a participant in round N, `current_round_id` stays at N — the next round does not exist yet. When the creator creates round N+1, `current_round_id` is updated to the new round for every participant advanced from round N. This is the authoritative answer to "which round is this participant in" and is used for:

- Deciding which round's questions the participant can see and answer.
- Validating that a creator selection targets a participant actually in that round.
- Preventing answer submission to rounds the participant has not reached.

`ADVANCED` is intentionally reused as the status across rounds; `current_round_id` disambiguates which round the participant occupies.

### 14.2 Withdrawal

A participant may withdraw at any time before being eliminated (`WITHDRAWN`). Withdrawal is permanent for that session, removes access exactly like elimination, and does not refund `BLIND_DATE_PARTICIPATE`.

If the finalist withdraws while the session is in `REVEAL`, the session completes immediately: any `PENDING` final decision is resolved as `NOT_INTERESTED` and the outcome is recorded as `NO_MATCH`.

---

## 15. Participant Answers

Answers belong to:

```text
participant
+
session question
```

This allows every participant to answer the same question independently.

---

## 16. Creator Selection

At the end of each round, the creator reviews participant answers.

The creator can:

- Advance a participant.
- Eliminate a participant.

Selections should be stored as separate historical records.

Possible decisions:

```text
ADVANCE
ELIMINATE
SELECT_FINALIST
```

`SELECT_FINALIST` is only valid in the round the creator treats as final. It promotes the target participant to `FINALIST`, eliminates every other participant still active in the session, transitions the session to `REVEAL`, and creates the `blind_date_final_decisions` record — all in one transaction (see §17).

---

## 17. Finalist Rule

The creator ultimately selects exactly one finalist.

Only the finalist reaches the reveal stage.

Finalist selection is performed through the normal round-selection mechanism using the `SELECT_FINALIST` decision (see §16 and §27). When a finalist is selected:

1. The participant's status becomes `FINALIST`.
2. All other participants still active in the session become `ELIMINATED`.
3. The session status becomes `REVEAL` (no longer discoverable or joinable).
4. A `blind_date_final_decisions` row is created with both decisions `PENDING`, `revealed_at` set, and a `decision_deadline_at`.
5. Both users are notified that the reveal has occurred, via the existing notification outbox infrastructure.

All of the above happens in a single transaction.

The finalist's status remains `FINALIST` until they view the reveal; it transitions to `REVEALED` on their first access of the revealed profile. The creator has no participant row, so no equivalent transition is needed on that side.

---

## 18. Reveal

Before reveal:

- Creator sees participant answers but not the participant's real photo.
- Participant sees the Blind Date experience but not the creator's real photo.

After the creator selects the finalist:

```text
Creator <---- reveal ----> Finalist
```

Both sides can see the other's relevant dating profile/photo.

---

## 19. Final Mutual Decision

After the reveal, both users independently decide:

```text
Interested
Not Interested
```

The result is:

```text
Creator = Interested
Finalist = Interested
        |
        v
      MATCH
```

If either side selects `Not Interested`:

```text
NOT_MATCHED
```

The creator's finalist selection alone is **not** a match.

---

## 19.1 Final Match Persistence

When the final Blind Date reveal is completed:

1. The creator and finalist independently choose `INTERESTED` or `NOT_INTERESTED`.
2. A match is created only when **both users choose `INTERESTED`**.
3. The match must be created using Qal's existing `matches` table.
4. Do not create a separate Blind Date-specific match table.
5. When the mutual match is created, record the corresponding two-way discovery actions in the existing `user_discovery_actions` table.
6. Both discovery actions must use the `BLIND_DATE` like variant (a dedicated variant under `LIKE`, see §19.4).
7. These automatic Blind Date match actions must **not charge credits**.
8. No `BLIND_DATE_PARTICIPATE` or other action-feature cost is applied at this stage.

Conceptually:

```text
Creator -> Finalist
LIKE / BLIND_DATE

Finalist -> Creator
LIKE / BLIND_DATE

        |
        v
Existing matches table
```

The two `user_discovery_actions` records represent the mutual interest that produced the match. The existing matching system remains the source of truth for the resulting match.

## 19.2 Pre-existing Discovery State

Before creating the two `BLIND_DATE` actions, the service checks the existing discovery state for the pair:

- If either direction already has an `ACTIVE` row in `user_discovery_actions`: a LIKE-type action (any variant) is reused for the match instead of inserting a duplicate; a `PASS` cannot be reused — it is reversed using the existing action-reversal mechanism and replaced with a new `BLIND_DATE` action. A new `BLIND_DATE` action is only inserted for directions with no active action.
- If an `ACTIVE` match already exists between the two users, no new actions or match are created; the session completes with outcome `ALREADY_MATCHED`.
- If either user has an active block on the other, no match is created; the session completes with outcome `NO_MATCH`. The service checks blocks explicitly first so the outcome is clean rather than relying on a failed insert.

## 19.3 System-Generated Action Marker and Rewind Exclusion

The two automatic `BLIND_DATE` actions are inserted with `action_source = 'BLIND_DATE'` (a new column on `user_discovery_actions`, default `'DISCOVERY'`).

- The rewind lookup in `SwipeService` excludes `action_source = 'BLIND_DATE'` rows, so a blind-date like can never be the "last action" a user rewinds.
- The resulting match is created with `rewind_eligible_until = NULL`, so it cannot be ended by rewind.
- `client_action_id` is generated deterministically from the session and direction (e.g., a name-based UUID over `session_id + actor_id`), so a retried final-decision transaction cannot insert duplicate actions.

## 19.4 Dedicated BLIND_DATE Variant

A dedicated `BLIND_DATE` variant is added under the `LIKE` feature action in `action_feature_variants`:

```sql
INSERT INTO public.action_feature_variants
    (feature_action_id, code, name, description, icon, active, sort_order)
SELECT fa.id, 'BLIND_DATE', 'Blind Date', 'Matched through Blind Date',
       'https://cdn.qal.app/actions/blind-date.webp', FALSE, 99
FROM public.feature_actions fa
WHERE fa.code = 'LIKE';
```

It is inserted with `active = FALSE` deliberately:

- `findActiveByActionCode("LIKE")` feeds the swipe variant picker, so an inactive variant never appears as a user-selectable option and needs no pricing configuration.
- `findByActionCodeAndVariantCode` resolves variants regardless of the active flag, so display metadata (name/icon) still works for these historical actions.
- The actions are system-generated and inserted directly; they never pass through user-facing variant validation or `evaluateVariant`, so the inactive flag does not block creation.

Fallback: if the `BLIND_DATE` variant row is missing at match-creation time, the service inserts a plain `LIKE` with `action_variant_code = NULL`. The match outcome never depends on the variant; it is only a presentation/analytics marker.

# 20. Recommended Database Architecture

The feature should be organized around these major tables:

```text
profiles
    |
    +-- Blind Date configuration
             |
             +-- blind_date_question_sets
                       |
                       +-- blind_date_question_set_questions
                       +-- blind_date_custom_questions
                       +-- permanent answers

blind_date_sessions
    |
    +-- blind_date_session_rounds
              |
              +-- blind_date_session_questions
              |
              +-- blind_date_session_participants
                        |
                        +-- blind_date_session_answers
                        |
                        +-- blind_date_round_selections

blind_date_final_decisions
```

All primary IDs should use UUID.

---

# 21. Proposed Tables

## 21.1 Blind Date Configuration

If configuration is not already appropriate on `profiles`, use a dedicated table:

```sql
CREATE TABLE public.blind_date_configurations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    user_id UUID NOT NULL UNIQUE
        REFERENCES public.profiles(id)
        ON DELETE CASCADE,

    enabled BOOLEAN NOT NULL DEFAULT TRUE,

    language_code VARCHAR(10) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

The important persistent setting is the user's selected Blind Date language.

---

## 21.2 Blind Date Question Sets

```sql
CREATE TABLE public.blind_date_question_sets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    user_id UUID NOT NULL UNIQUE
        REFERENCES public.profiles(id)
        ON DELETE CASCADE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

A user has one reusable question set.

---

## 21.3 Platform Question Categories

```sql
CREATE TABLE public.blind_date_question_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    code VARCHAR(100) NOT NULL UNIQUE,

    name VARCHAR(255) NOT NULL,

    description TEXT,

    icon_url TEXT,

    sort_order INTEGER NOT NULL DEFAULT 0,

    active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

Category translations use a separate translation table.

---

## 21.4 Platform Questions

```sql
CREATE TABLE public.blind_date_questions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    category_id UUID NOT NULL
        REFERENCES public.blind_date_question_categories(id)
        ON DELETE RESTRICT,

    code VARCHAR(100) UNIQUE,

    active BOOLEAN NOT NULL DEFAULT TRUE,

    sort_order INTEGER NOT NULL DEFAULT 0,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

Question text is stored in the translation table.

---

## 21.5 Question Translations

```sql
CREATE TABLE public.blind_date_question_translations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    question_id UUID NOT NULL
        REFERENCES public.blind_date_questions(id)
        ON DELETE CASCADE,

    language_code VARCHAR(10) NOT NULL,

    question TEXT NOT NULL,

    UNIQUE (question_id, language_code)
);
```

---

## 21.6 Category Translations

```sql
CREATE TABLE public.blind_date_question_category_translations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    category_id UUID NOT NULL
        REFERENCES public.blind_date_question_categories(id)
        ON DELETE CASCADE,

    language_code VARCHAR(10) NOT NULL,

    name VARCHAR(255) NOT NULL,

    description TEXT,

    UNIQUE (category_id, language_code)
);
```

---

## 21.7 Questions Selected by User

```sql
CREATE TABLE public.blind_date_question_set_questions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    question_set_id UUID NOT NULL
        REFERENCES public.blind_date_question_sets(id)
        ON DELETE CASCADE,

    question_id UUID NOT NULL
        REFERENCES public.blind_date_questions(id)
        ON DELETE RESTRICT,

    sort_order INTEGER NOT NULL DEFAULT 0,

    active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (question_set_id, question_id)
);
```

---

## 21.8 Answers to Permanent Questions

```sql
CREATE TABLE public.blind_date_question_set_answers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    question_set_question_id UUID NOT NULL
        REFERENCES public.blind_date_question_set_questions(id)
        ON DELETE CASCADE,

    answer TEXT NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (question_set_question_id)
);
```

---

## 21.9 Custom Questions

```sql
CREATE TABLE public.blind_date_custom_questions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    question_set_id UUID NOT NULL
        REFERENCES public.blind_date_question_sets(id)
        ON DELETE CASCADE,

    question TEXT NOT NULL,

    answer TEXT NOT NULL,

    sort_order INTEGER NOT NULL DEFAULT 0,

    active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

There is no hard-coded maximum number unless product requirements later introduce one.

---

# 22. Blind Date Sessions

```sql
CREATE TABLE public.blind_date_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    creator_user_id UUID NOT NULL
        REFERENCES public.profiles(id)
        ON DELETE CASCADE,

    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',

    expires_at TIMESTAMPTZ,

    credit_charge_idempotency_key UUID NOT NULL UNIQUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

The current privacy rule is that eliminated participants lose access to the session, so a configurable visibility column is not required.

Recommended active-session constraint:

```sql
CREATE UNIQUE INDEX uq_blind_date_one_active_session_per_creator
ON public.blind_date_sessions (creator_user_id)
WHERE status IN ('OPEN', 'REVEAL');
```

---

# 23. Session Rounds

```sql
CREATE TABLE public.blind_date_session_rounds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    session_id UUID NOT NULL
        REFERENCES public.blind_date_sessions(id)
        ON DELETE CASCADE,

    round_number INTEGER NOT NULL,

    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,

    UNIQUE (session_id, round_number)
);
```

Only Round 1 is initially discoverable.

Round statuses:

```text
OPEN
CLOSED
```

A round is `OPEN` while it accepts answers and creator selections. Creating round N+1 closes round N, so at most one round per session is `OPEN` at a time. A round also closes when the session terminates (§9.1) or when a finalist is selected from it.

---

# 24. Session Question Snapshots

```sql
CREATE TABLE public.blind_date_session_questions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    round_id UUID NOT NULL
        REFERENCES public.blind_date_session_rounds(id)
        ON DELETE CASCADE,

    source_question_id UUID
        REFERENCES public.blind_date_questions(id)
        ON DELETE SET NULL,

    source_custom_question_id UUID
        REFERENCES public.blind_date_custom_questions(id)
        ON DELETE SET NULL,

    question_text TEXT NOT NULL,

    language_code VARCHAR(10) NOT NULL,

    sort_order INTEGER NOT NULL DEFAULT 0,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

The copied `question_text` is intentional. It preserves the historical question even if the source platform/custom question is later edited or deleted.

---

# 25. Session Participants

```sql
CREATE TABLE public.blind_date_session_participants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    session_id UUID NOT NULL
        REFERENCES public.blind_date_sessions(id)
        ON DELETE CASCADE,

    user_id UUID NOT NULL
        REFERENCES public.profiles(id)
        ON DELETE CASCADE,

    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',

    current_round_id UUID
        REFERENCES public.blind_date_session_rounds(id)
        ON DELETE SET NULL,

    credit_charge_idempotency_key UUID NOT NULL UNIQUE,

    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    eliminated_at TIMESTAMPTZ,
    advanced_at TIMESTAMPTZ,
    finalist_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (session_id, user_id)
);
```

A creator must not be allowed to participate in their own session. Enforce this in application/service logic or with an appropriate database trigger.

---

# 26. Participant Answers

```sql
CREATE TABLE public.blind_date_session_answers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    participant_id UUID NOT NULL
        REFERENCES public.blind_date_session_participants(id)
        ON DELETE CASCADE,

    session_question_id UUID NOT NULL
        REFERENCES public.blind_date_session_questions(id)
        ON DELETE CASCADE,

    answer TEXT NOT NULL,

    submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (participant_id, session_question_id)
);
```

---

# 27. Round Selections

```sql
CREATE TABLE public.blind_date_round_selections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    round_id UUID NOT NULL
        REFERENCES public.blind_date_session_rounds(id)
        ON DELETE CASCADE,

    participant_id UUID NOT NULL
        REFERENCES public.blind_date_session_participants(id)
        ON DELETE CASCADE,

    selected_by_user_id UUID NOT NULL
        REFERENCES public.profiles(id)
        ON DELETE RESTRICT,

    decision VARCHAR(30) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (round_id, participant_id)
);
```

Possible decisions:

```text
ADVANCE
ELIMINATE
SELECT_FINALIST
```

`SELECT_FINALIST` may be recorded for at most one participant per session. Recording it triggers the finalist transition described in §17.

---

# 28. Final Reveal and Decisions

Use a dedicated table:

```sql
CREATE TABLE public.blind_date_final_decisions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    session_id UUID NOT NULL UNIQUE
        REFERENCES public.blind_date_sessions(id)
        ON DELETE CASCADE,

    finalist_participant_id UUID NOT NULL UNIQUE
        REFERENCES public.blind_date_session_participants(id)
        ON DELETE RESTRICT,

    revealed_at TIMESTAMPTZ,

    decision_deadline_at TIMESTAMPTZ,

    creator_decision VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    participant_decision VARCHAR(30) NOT NULL DEFAULT 'PENDING',

    creator_decided_at TIMESTAMPTZ,
    participant_decided_at TIMESTAMPTZ,

    outcome VARCHAR(30),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

Possible decisions:

```text
PENDING
INTERESTED
NOT_INTERESTED
```

A match is created only when both decisions are `INTERESTED`.

`decision_deadline_at` bounds how long the reveal can stay pending (recommended default: 72 hours, configurable). When the deadline passes with any `PENDING` decision, the expiry worker resolves each pending side as `NOT_INTERESTED` and completes the session.

`outcome` records the final result once resolved:

```text
MATCHED
NO_MATCH
ALREADY_MATCHED
EXPIRED
```

The actual match should use Qal's normal matching system rather than duplicating match records inside Blind Date.

---

# 29. State Machines

## Session

```text
OPEN
 |
 +-- REVEAL
 |     |
 |     +-- COMPLETED
 |
 +-- CLOSED
 |
 +-- EXPIRED
 |
 +-- COMPLETED
 |
 +-- CANCELLED
```

## Participant

```text
ACTIVE
 |
 +-- ELIMINATED
 |
 +-- WITHDRAWN
 |
 +-- ADVANCED
        |
        +-- ELIMINATED
        |
        +-- WITHDRAWN
        |
        +-- ADVANCED (next round)
               |
               +-- FINALIST
                      |
                      +-- REVEALED
                      |
                      +-- WITHDRAWN (see §14.2)
```

## Final decision

```text
PENDING
   |
   +-- INTERESTED
   |
   +-- NOT_INTERESTED
```

---

# 30. API-Level Flow

## Question Set

```text
GET    /blind-date/question-categories
GET    /blind-date/questions?categoryId=...
POST   /blind-date/question-set/questions
DELETE /blind-date/question-set/questions/{id}
PUT    /blind-date/question-set/questions/{id}/answer

POST   /blind-date/question-set/custom-questions
PUT    /blind-date/question-set/custom-questions/{id}
DELETE /blind-date/question-set/custom-questions/{id}

PUT    /blind-date/configuration
```

## Create Session

```text
POST /blind-date/sessions
```

The request selects the questions for Round 1.

Server:

1. Validates the creator.
2. Validates the selected questions.
3. Checks that the creator has no other open session.
4. Creates the session.
5. Charges `BLIND_DATE_SESSION_CREATE`.
6. Creates Round 1.
7. Snapshots the selected questions.
8. Returns the session.

These operations should be transactional.

## Discover

```text
GET /blind-date/sessions/discover
```

Return only eligible `OPEN` sessions where Round 1 is joinable.

Exclude:

- The current user's own session.
- Sessions already joined by the user (including eliminated and withdrawn participation).
- Sessions not in `OPEN` status.
- Sessions that have expired.
- Sessions whose creator has an active block on the user, or whom the user has blocked — checked in both directions via the existing blocks infrastructure.
- Sessions where the user already has an `ACTIVE` match with the creator.
- Sessions failing mutual gender/preference compatibility: the participant's gender must satisfy the creator's discovery preferences and the creator's gender must satisfy the participant's discovery preferences, evaluated with the same rules used by the normal discovery feed.

## Participate

```text
POST /blind-date/sessions/{sessionId}/participate
```

Server:

1. Verifies session is open.
2. Verifies Round 1 is joinable.
3. Verifies user eligibility.
4. Checks duplicate participation.
5. Charges `BLIND_DATE_PARTICIPATE`.
6. Creates participant.
7. Returns Round 1 questions.

The charge must be idempotent.

## Submit Answers

```text
POST /blind-date/participants/{participantId}/answers
```

The participant answers the questions for their current round (identified by `current_round_id`).

Answers are editable until the round closes for that participant: once the creator records a selection (`ADVANCE`, `ELIMINATE`, or `SELECT_FINALIST`) for the participant in that round, or the round itself is closed, the participant's answers for that round become read-only.

## Withdraw

```text
POST /blind-date/participants/{participantId}/withdraw
```

The participant permanently leaves the session (`WITHDRAWN`). No refund.

## Creator Reviews

```text
GET /blind-date/sessions/{sessionId}/participants
GET /blind-date/sessions/{sessionId}/participants/{participantId}
```

The creator sees answers but not the participant's private identity/photo before the final reveal.

## Select Participants

```text
POST /blind-date/sessions/{sessionId}/rounds/{roundId}/selections
```

The creator chooses:

```text
ADVANCE
ELIMINATE
```

The service validates that the participant belongs to the session and is eligible for that round.

## Create Next Round

```text
POST /blind-date/sessions/{sessionId}/rounds
```

The creator selects questions from their permanent question set and/or custom questions.

The questions are copied into the new round as immutable snapshots.

Creating the new round closes the previous round and moves every participant advanced from it to the new round (`current_round_id`).

Only participants advanced from the previous round are eligible.

## Finalist / Reveal

```text
POST /blind-date/sessions/{sessionId}/rounds/{roundId}/finalist
```

Body: `{ "participantId": "..." }`

Equivalent to recording a `SELECT_FINALIST` selection. Atomically promotes the participant to `FINALIST`, eliminates all remaining participants, transitions the session to `REVEAL`, and creates the `blind_date_final_decisions` record with a decision deadline (see §17 and §28).

## Final Decisions

```text
POST /blind-date/sessions/{sessionId}/final-decision
```

The creator and finalist independently submit:

```text
INTERESTED
NOT_INTERESTED
```

When both decisions are available:

```text
both INTERESTED -> create normal Qal match
otherwise       -> NOT_MATCHED
```

---

# 31. Security and Abuse Prevention

### Creator isolation

Participants must never be able to query the creator's normal dating profile through Blind Date APIs before reveal.

### Participant isolation

The creator should only see information intentionally exposed by Blind Date.

### Round isolation

Participants cannot request questions or answers from rounds they are not eligible to access.

### Eliminated-user isolation

After elimination, the participant cannot continue accessing the session.

### Finalist isolation

Only the single finalist can enter the reveal stage.

### Duplicate participation

```sql
UNIQUE (session_id, user_id)
```

prevents the same account from joining the same session twice.

### Multiple-account abuse

Database uniqueness cannot prevent a real person from creating multiple accounts. Existing Qal account/device/abuse-prevention mechanisms should be used in addition to the Blind Date rules.

### Custom question moderation

Custom questions are user-generated content shown to other users:

- Enforce a length limit (e.g., 500 characters) and reject empty/blank text.
- Custom questions are reportable through the existing report infrastructure.
- Admins can deactivate a custom question (`active = FALSE`); existing session snapshots are unaffected because they store copied `question_text`.

---

# 32. Credit Charging Summary

| Action | Actor | Charge | When |
|---|---|---|---|
| Create Question Set | Creator | Free | Configuration |
| Edit Question Set | Creator | Free | Anytime |
| Create Blind Date Session | Creator | `BLIND_DATE_SESSION_CREATE` | Session creation |
| Participate | Participant | `BLIND_DATE_PARTICIPATE` | Joining Round 1 |
| Advance | Participant | Free | Creator selects |
| Later rounds | Participant | Free | Participation continues |
| Finalist selection | Participant | Free | Creator selects |
| Reveal | Both | Free | Finalist selected |
| Final decision | Both | Free | After reveal |
| Match | Both | Free | Both interested |

---

# 33. Core Business Rules

1. A user can maintain one permanent Blind Date Question Set.
2. The question set can contain platform and custom questions.
3. Platform questions are selected by category.
4. Users provide their own answers.
5. Users can modify their permanent question set at any time.
6. Existing session snapshots never change.
7. Users choose a Blind Date language and it is persisted.
8. A session is created from selected questions and answers.
9. Session creation costs `BLIND_DATE_SESSION_CREATE`.
10. Only Round 1 is publicly discoverable.
11. New participants can only join Round 1.
12. Participation costs `BLIND_DATE_PARTICIPATE`.
13. A participant pays only once per session.
14. Later rounds do not charge again.
15. Each round can have its own questions.
16. The creator chooses questions for each round.
17. The creator controls who advances.
18. Eliminated participants lose access to the session.
19. The creator remains anonymous until final reveal.
20. Only one finalist reaches the reveal stage.
21. Creator and finalist see each other after reveal.
22. Both independently decide whether they are interested.
23. A match occurs only when both are interested.
24. Sessions can have an expiration date.
25. `expires_at = NULL` means manual closure.
26. A creator can have at most one active session (`OPEN` or `REVEAL`).
27. `BLIND_DATE_SESSION_CREATE` and `BLIND_DATE_PARTICIPATE` are configured through the existing `feature_actions` and `subscription_plan_limit_and_cost` infrastructure.
28. Paid Blind Date actions must be idempotent.
29. UUIDs are used for IDs.
30. Translation data is normalized into translation tables rather than JSONB.
31. Session questions preserve immutable historical snapshots.
32. A question used in one round cannot be selected again in a later round of the same session.
33. Eliminated users cannot continue observing the session.
34. Round 2+ participants come only from creator-selected participants from the previous round.
35. A mutual final Blind Date interest creates a record in the existing `matches` table.
36. The mutual final interest also creates two `user_discovery_actions` records using the dedicated `BLIND_DATE` like variant, one in each direction; if the variant row is missing the actions fall back to a plain `LIKE` (variant `NULL`).
37. The automatic final `BLIND_DATE` actions and match creation do not charge credits.
38. Each participant carries `current_round_id` identifying the round they currently occupy.
39. `SELECT_FINALIST` is the only way to create a finalist and does so atomically.
40. Finalist selection transitions the session to `REVEAL` and eliminates all other remaining participants.
41. Final decisions have a deadline; expiry resolves pending decisions as `NOT_INTERESTED`.
42. System-generated `BLIND_DATE` actions use `action_source = 'BLIND_DATE'`, are excluded from rewind, and reuse existing active actions where present.
43. Blind Date matches are created with `rewind_eligible_until = NULL`.
44. If the pair is already matched, the session completes as `ALREADY_MATCHED` without creating new actions.
45. Session termination (close/expire/cancel) eliminates remaining participants and closes open rounds.
46. No automatic credit refunds; refunds are manual admin operations.
47. Answers lock once the creator records a selection for that participant in that round, or the round closes.
48. Participants may withdraw; withdrawal is permanent and non-refundable.
49. Discover excludes blocked pairs (both directions), existing matches, and mutually incompatible gender/preference pairs.
50. Custom questions are moderated UGC: length-limited, reportable, and admin-deactivatable.
51. Creating a new round closes the previous round; at most one round per session is `OPEN`.
52. An existing `ACTIVE` `PASS` between the pair is reversed and replaced by the `BLIND_DATE` action; an existing LIKE-type action is reused.
53. If the finalist withdraws during `REVEAL`, the session completes with outcome `NO_MATCH`.
54. The creator cannot manually close a session in `REVEAL`; they must submit a final decision.
55. Deadline- or expiry-driven resolution of pending final decisions records outcome `EXPIRED`; explicit decisions record `MATCHED` or `NO_MATCH`.

---

# 34. Final Architecture

```text
                         QAL BLIND DATE
                              |
             +----------------+----------------+
             |                                 |
             v                                 v
     Permanent Question Set             Blind Date Session
             |                                 |
       +-----+------+                    +-----+------+
       |            |                    |            |
 Platform       Custom               Rounds       Creator
 Questions      Questions                |
       |            |              +-----+-----+
       |            |              |           |
       +-----+------+           Round 1      Round 2...
             |                    |
          Answers                  |
             |              +-----+------+-----+
             |              |            |     |
             |           User B       User C  User D
             |              |            |     |
             |           answers      answers answers
             |              |            |     |
             |              +------+-----+-----+
             |                     |
             |                 Creator
             |                 selects
             |                     |
             |              +------+------+
             |              |             |
             |           ELIMINATE      ADVANCE
             |                            |
             |                            v
             |                         Round 2
             |                            |
             |                         Round 3
             |                            |
             |                         Finalist
             |                            |
             |                          Reveal
             |                            |
             |                   +--------+--------+
             |                   |                 |
             |                Creator          Finalist
             |                decides           decides
             |                   |                 |
             |                   +--------+--------+
             |                            |
             |                     Both interested
             |                            |
             |                          MATCH
```

## 34.1 Existing Qal Match and Discovery Infrastructure

Blind Date does not create parallel match infrastructure.

At the successful end of the flow:

```text
Creator <---- reveal ----> Finalist
       |                     |
       +---- INTERESTED -----+
                 |
                 v
        user_discovery_actions
          Creator -> Finalist
               BLIND_DATE

        user_discovery_actions
          Finalist -> Creator
               BLIND_DATE
                 |
                 v
           existing matches
```

The two `BLIND_DATE` actions are system-generated and free of additional credit charges.

## Design Principle

The feature has four deliberately separate layers:

1. **Question Set** — reusable user-owned content.
2. **Session** — one published Blind Date opportunity.
3. **Rounds and Participants** — the progressive selection process.
4. **Reveal and Mutual Decision** — the final dating decision.

This separation keeps the data model flexible, preserves historical session integrity, supports multiple participants and rounds, and keeps the monetization and privacy rules explicit.

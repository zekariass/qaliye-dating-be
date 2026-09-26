# Blind Date API Reference

Complete API reference for the Blind Date feature. Intended for client
developers and AI agents integrating with the Qaliye backend.

**Base URL:** `/api/v1/blind-date`
**Auth:** All endpoints require a Bearer JWT. The caller's user id is derived
from the token — never pass a user id in the body.
**Content type:** `application/json` for all request bodies.

---

## 1. Concepts

Blind Date is a creator-led, multi-round anonymous dating flow:

1. A **creator** builds a permanent **question set** (platform questions with
   their answers + custom questions) and starts a **session**.
2. **Participants** discover the session and join (paid action).
3. Each **round**, participants answer the creator's questions; the creator
   then **advances**, **eliminates**, or picks a **finalist**.
4. Picking a finalist triggers the **reveal**: both sides see each other and
   submit a final decision (`INTERESTED` / `NOT_INTERESTED`).
5. Mutual `INTERESTED` creates a **match**.

### Status values

| Entity | Values |
|---|---|
| Session | `OPEN`, `REVEAL`, `CLOSED`, `EXPIRED`, `COMPLETED`, `CANCELLED` |
| Round | `OPEN`, `CLOSED` |
| Participant | `ACTIVE`, `ADVANCED`, `ELIMINATED`, `FINALIST`, `REVEALED`, `WITHDRAWN` |
| Selection decision | `ADVANCE`, `ELIMINATE`, `SELECT_FINALIST` |
| Final decision | `PENDING`, `INTERESTED`, `NOT_INTERESTED` |
| Final outcome | `MATCHED`, `NO_MATCH`, `ALREADY_MATCHED`, `EXPIRED` |

### Monetization

| Action | Feature code | When charged |
|---|---|---|
| Create session | `BLIND_DATE_SESSION_CREATE` | `POST /sessions` |
| Join session | `BLIND_DATE_PARTICIPATE` | `POST /sessions/{id}/join` |

Both are idempotent per `idempotency_key` — replaying a request with the same
key returns the existing resource without charging again. Depending on the
caller's subscription plan the action may be free (under limit), charged in
credits, or rejected with `action_limit_exceeded` / `insufficient_credits`.

### Errors

All errors return the standard error body with a machine-readable `code`
(HTTP status + reason string). Blind Date codes:

| Code | HTTP | Meaning |
|---|---|---|
| `session_not_found` | 404 | Session id does not exist |
| `session_not_open` | 409 | Session is not in `OPEN` status |
| `session_expired` | 409 | Session `expires_at` has passed |
| `session_full` | 409 | `max_participants` reached |
| `active_session_exists` | 409 | Creator already has an OPEN/REVEAL session |
| `already_joined` | 409 | User already joined this session |
| `creator_cannot_join` | 400 | Creator tried to join own session |
| `join_window_closed` | 409 | Join only allowed during round 1 |
| `no_open_round` | 409 | Session has no open round |
| `round_still_open` | 409 | Close the current round before creating the next |
| `max_rounds_reached` | 409 | `max_rounds` reached — must select a finalist |
| `round_not_found` / `round_closed` | 404 / 409 | Round missing or already closed |
| `participant_not_found` | 404 | Participant id does not exist |
| `participant_not_active` | 409 | Participant is eliminated/withdrawn |
| `participant_not_in_round` | 409 | Participant is not in the current round |
| `not_participant` / `not_session_creator` / `not_a_decision_party` | 403 | Caller lacks the required role |
| `invalid_decision` | 400 | Unknown selection/final decision value |
| `decision_already_submitted` | 409 | Final decision already submitted by caller |
| `decision_already_resolved` | 409 | Final outcome already resolved |
| `session_not_in_reveal` | 409 | Final decision requires `REVEAL` status |
| `no_final_decision` / `finalist_not_found` | 404 / 409 | Reveal state inconsistent |
| `cannot_withdraw` | 409 | Participant cannot withdraw in current state |
| `answer_required` / `answer_too_long` / `answer_locked` | 400 / 400 / 409 | Answer validation failures |
| `question_not_found` / `question_unanswered` / `question_not_in_round` | 404 / 400 / 400 | Question validation failures |
| `question_required` / `question_too_long` | 400 | Custom question validation |
| `invalid_question_count` | 400 | Round needs 1–20 questions |
| `max_questions_reached` | 409 | Question set already holds `limits.max_questions` platform questions |
| `max_custom_questions_reached` | 409 | Question set already holds `limits.max_custom_questions` custom questions |
| `unsupported_language` | 400 | Language code not in catalog |
| `set_question_not_found` / `custom_question_not_found` | 404 | Question-set row missing |
| `idempotency_key_required` | 400 | Missing idempotency key |

---

## 2. Public Client API

### 2.1 Catalog

#### `GET /catalog/categories`

Lists question categories, translated.

**Query params**

| Param | Type | Default | Description |
|---|---|---|---|
| `language` | string | `en` | BCP-47 language code |

**Response `200`** — array of:

```json
{
  "id": "uuid",
  "code": "LIFESTYLE",
  "name": "Lifestyle",
  "description": "Questions about daily life",
  "icon_url": "https://...",
  "sort_order": 1
}
```

#### `GET /catalog/questions`

Lists platform questions, optionally filtered by category, translated.

**Query params**

| Param | Type | Default | Description |
|---|---|---|---|
| `categoryId` | uuid | — | Filter to one category |
| `language` | string | `en` | BCP-47 language code |

**Response `200`** — array of:

```json
{
  "id": "uuid",
  "category_id": "uuid",
  "code": "Q_MORNING_PERSON",
  "question": "Are you a morning person?",
  "sort_order": 1
}
```

### 2.2 Configuration

#### `GET /configuration`

Returns the caller's Blind Date configuration plus global feature limits.
Creates a default configuration on first call.

**Response `200`**

```json
{
  "enabled": true,
  "language_code": "en",
  "supported_languages": [
    { "code": "en", "name": "English" },
    { "code": "am", "name": "Amharic" }
  ],
  "limits": {
    "max_participants": 20,
    "max_rounds": 5,
    "max_round_questions": 20,
    "max_questions": 50,
    "max_custom_questions": 10
  }
}
```

`limits` are server-side caps — use them to decide whether to show
"join" / "start next round" affordances.

#### `PATCH /configuration`

Updates the caller's configuration. All fields optional.

**Request**

```json
{ "languageCode": "am", "enabled": true }
```

| Field | Type | Description |
|---|---|---|
| `languageCode` | string? | Must be a supported language code |
| `enabled` | boolean? | Opt in/out of the feature |

**Response `200`** — same shape as `GET /configuration`.
**Errors:** `unsupported_language`

### 2.3 Question set

The caller's permanent set of answered questions, reused across sessions.

#### `GET /question-set`

**Response `200`**

```json
{
  "questions": [
    {
      "id": "uuid",
      "question_id": "uuid",
      "question": "Are you a morning person?",
      "answer": "Absolutely not.",
      "sort_order": 1,
      "category_id": "uuid",
      "category_code": "LIFESTYLE"
    }
  ],
  "custom_questions": [
    { "id": "uuid", "question": "Coffee or tea?", "answer": "Coffee.", "sort_order": 1 }
  ]
}
```

#### `POST /question-set/questions`

Adds a platform question (with the caller's answer) to the set.

**Request**

```json
{ "questionId": "uuid", "answer": "Absolutely not." }
```

| Field | Type | Required | Description |
|---|---|---|---|
| `questionId` | uuid | yes | From `GET /catalog/questions` |
| `answer` | string | no | Caller's own answer (≤ 2000 chars) |

**Response `200`** — the created set-question object (same shape as
`questions[]` above).
**Errors:** `question_not_found`, `answer_too_long`, `max_questions_reached`

#### `POST /question-set/questions/{setQuestionId}/answer`

Updates the caller's answer to a set question.

**Request:** `{ "answer": "Changed my mind." }`
**Response `204`**
**Errors:** `set_question_not_found`, `answer_too_long`

#### `DELETE /question-set/questions/{setQuestionId}`

Removes a platform question from the set. **Response `204`.**

#### `PATCH /question-set/questions/{setQuestionId}/order`

**Request:** `{ "sortOrder": 3 }` — **Response `204`.**

#### `POST /question-set/custom-questions`

Adds a custom question + answer.

**Request**

```json
{ "question": "Coffee or tea?", "answer": "Coffee." }
```

`question` ≤ 500 chars, `answer` ≤ 2000 chars. Both required.

**Response `200`** — the created custom-question object.
**Errors:** `question_required`, `question_too_long`, `answer_too_long`,
`max_custom_questions_reached`

#### `PATCH /question-set/custom-questions/{customQuestionId}`

Partial update. All fields optional:

```json
{ "question": "Tea or coffee?", "answer": "Tea.", "sortOrder": 2 }
```

**Response `200`** — updated object. **Errors:** `custom_question_not_found`

#### `DELETE /question-set/custom-questions/{customQuestionId}`

**Response `204`.**

### 2.4 Session discovery & lifecycle

#### `GET /sessions/discover`

Lists open sessions the caller is eligible to join (round 1 still open,
mutual preference compatibility, no block/match with creator, not already
joined, not own session).

**Query params**

| Param | Type | Default | Description |
|---|---|---|---|
| `page` | int | 0 | Zero-based page |
| `size` | int | 20 | Page size (max 50) |

**Response `200`** — array of:

```json
{
  "id": "uuid",
  "creator_user_id": "uuid",
  "status": "OPEN",
  "language_code": "en",
  "expires_at": "2026-09-21T10:00:00Z",
  "created_at": "2026-09-20T09:00:00Z",
  "participant_count": 7,
  "creator": {
    "gender": "FEMALE",
    "age": 27,
    "religion": "ORTHODOX",
    "relationship_intention": "MARRIAGE",
    "city": "Addis Ababa",
    "country": "Ethiopia",
    "primary_photo": {
      "id": "uuid",
      "signed_url": "https://…",
      "expires_at": "2026-09-20T11:00:00Z"
    }
  }
}
```

**`creator` object** — present on every session payload (`/sessions/discover`,
`/sessions/{id}`, `/sessions/mine`, `/participations`, `POST /sessions`). It
carries the session owner's public profile info so participants can decide
whether to join. `primary_photo.signed_url` is a short-lived signed URL —
the client is expected to render it **blurred**. Any field may be `null` if
the creator hasn't filled it in or has no approved primary photo.

**`final_decision` object** — present on session payloads once the session
has a final-decision row (i.e. status `REVEAL` or later); `null` otherwise:

```json
"final_decision": {
  "my_decision": "INTERESTED",
  "other_party_decided": false,
  "outcome": null,
  "match_id": null,
  "revealed_at": "2026-09-19T10:00:00Z",
  "decision_deadline_at": "2026-09-21T10:00:00Z"
}
```

- `my_decision` — the caller's own decision (`PENDING` / `INTERESTED` /
  `NOT_INTERESTED`), or `null` if the caller is neither creator nor finalist.
- `other_party_decided` — `true` once the other side has submitted; their
  actual choice is **never** exposed until the outcome resolves.
- `outcome` — `null` while pending, then `MATCHED` / `NO_MATCH` /
  `ALREADY_MATCHED` / `EXPIRED`. `match_id` is set on `MATCHED`/`ALREADY_MATCHED`.

Compare `participant_count` with `limits.max_participants` from
`GET /configuration` to show "7/20" or disable join on full sessions.

#### `GET /sessions/mine`

Lists sessions the caller **created or joined**, newest first. Use this to
restore state after app restart — it returns the caller's `participant_id`
needed for answer submission and withdrawal.

**Query params:** `page` (default 0), `size` (default 20, max 50)

**Response `200`** — array of:

```json
{
  "id": "uuid",
  "creator_user_id": "uuid",
  "status": "OPEN",
  "language_code": "en",
  "expires_at": "2026-09-21T10:00:00Z",
  "created_at": "2026-09-20T09:00:00Z",
  "role": "PARTICIPANT",
  "participant_id": "uuid",
  "participant_status": "ACTIVE",
  "current_round_id": "uuid",
  "participant_count": 7,
  "current_round_number": 1
}
```

`role` is `CREATOR` or `PARTICIPANT`. `participant_id`,
`participant_status` and `current_round_id` are `null` for sessions the
caller only created.

#### `GET /participations`

Lists only the sessions the caller **joined as a participant**, newest join
first. Same response shape as `GET /sessions/mine` (`role` is always
`PARTICIPANT` here).

**Query params:** `page` (default 0), `size` (default 20, max 50)

#### `POST /sessions`

Creates a session (creator side). **Paid** — charges
`BLIND_DATE_SESSION_CREATE`. Snapshots the chosen questions into round 1.

**Request**

```json
{
  "idempotency_key": "uuid",
  "question_ids": ["uuid"],
  "custom_question_ids": ["uuid"],
  "language_code": "en",
  "expires_at": "2026-09-21T10:00:00Z"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `idempotency_key` | uuid | yes | Client-generated; safe retries |
| `question_ids` | uuid[] | no | **Platform question ids** — the `question_id` field of each item in `GET /question-set` (or `id` from the catalog). NOT the set-question row `id` |
| `custom_question_ids` | uuid[] | no | Custom-question `id`s from the caller's set |
| `language_code` | string | no | Defaults to caller's configured language |
| `expires_at` | ISO-8601 | no | Session expiry; swept by the expiry worker |

Total questions must be 1–20 (`invalid_question_count`).

**Response `200`**

```json
{
  "id": "uuid",
  "creator_user_id": "uuid",
  "status": "OPEN",
  "language_code": "en",
  "expires_at": "2026-09-21T10:00:00Z",
  "created_at": "2026-09-20T09:00:00Z",
  "rounds": [
    {
      "id": "uuid",
      "session_id": "uuid",
      "round_number": 1,
      "status": "OPEN",
      "started_at": "2026-09-20T09:00:00Z",
      "completed_at": null
    }
  ],
  "participant_count": 0,
  "current_round_number": 1
}
```

**Errors:** `active_session_exists`, `invalid_question_count`,
`question_unanswered`, `unsupported_language`, charge errors

#### `GET /sessions/{sessionId}`

Full session detail. Same response shape as `POST /sessions`.
Use `current_round_number` vs `limits.max_rounds` to decide whether the
creator can start another round.

#### `POST /sessions/{sessionId}/close`

Creator closes the session early. Closes open rounds and eliminates
still-active participants. **Response `204`.**
**Errors:** `not_session_creator`, `session_not_open`

#### `POST /sessions/{sessionId}/rounds`

Creator starts the next round after closing the current one. Snapshots a new
question set for the round.

**Request:** `{ "questionIds": ["uuid"], "customQuestionIds": ["uuid"] }`
(1–20 total questions)

**Response `200`** — the new round object.
**Errors:** `round_still_open`, `max_rounds_reached`, `invalid_question_count`

#### `POST /sessions/{sessionId}/rounds/close`

Creator closes the current open round. Participants not explicitly advanced
are eliminated. **Response `204`.**
**Errors:** `no_open_round`

#### `GET /sessions/{sessionId}/participants`

**Creator only.** Returns the session's participant roster with each
participant's answers to the current open round — the data the creator needs
to make selections. Participant `user_id` is intentionally **not** exposed;
the game stays blind until reveal.

**Response `200`** — array of:

```json
{
  "participant_id": "uuid",
  "status": "ACTIVE",
  "current_round_id": "uuid",
  "joined_at": "2026-09-20T09:30:00Z",
  "answers": [
    {
      "session_question_id": "uuid",
      "question": "Are you a morning person?",
      "answer": "Absolutely not.",
      "submitted_at": "2026-09-20T09:45:00Z"
    }
  ]
}
```

`answers` is empty when no round is open. Feed `participant_id` values into
`POST /sessions/{sessionId}/selections`.

**Errors:** `session_not_found`, `not_session_creator`

#### `GET /sessions/{sessionId}/results`

**Creator only.** Session results screen: the outcome, the revealed finalist
(winner) with their public profile, and the finalist's answers grouped by
round. Works for sessions in **any** status — it is only meaningful for
`REVEAL` and later, and a `REVEAL` caller gets the in-progress view for
free.

Unlike the roster endpoint, the winner's `user_id` **is** exposed here —
the reveal has already happened once a finalist exists.

**Response `200`**

```json
{
  "session_id": "uuid",
  "status": "COMPLETED",
  "created_at": "2026-09-20T09:00:00Z",
  "participant_count": 7,
  "round_count": 3,
  "outcome": "MATCHED",
  "match_id": "uuid",
  "winner": {
    "participant_id": "uuid",
    "user_id": "uuid",
    "status": "REVEALED",
    "profile": {
      "display_name": "Sara",
      "age": 27,
      "gender": "FEMALE",
      "religion": "ORTHODOX",
      "relationship_intention": "MARRIAGE",
      "city": "Addis Ababa",
      "country": "Ethiopia",
      "primary_photo": {
        "id": "uuid",
        "signed_url": "https://…",
        "expires_at": "2026-09-20T11:00:00Z"
      }
    },
    "rounds": [
      {
        "round_id": "uuid",
        "round_number": 1,
        "answers": [
          {
            "session_question_id": "uuid",
            "question": "Are you a morning person?",
            "answer": "Absolutely not.",
            "submitted_at": "2026-09-20T09:45:00Z"
          }
        ]
      }
    ]
  }
}
```

- `participant_count` — all participants who ever joined (including
  eliminated/withdrawn). `round_count` — total session rounds.
- `outcome` / `match_id` — `null` until the final decision resolves
  (`REVEAL` still in progress) or no final-decision row exists.
- `winner` — `null` when no finalist was ever selected (session closed,
  cancelled or expired before `SELECT_FINALIST`). Not an error — render an
  empty-state instead.
- `winner.status` — the participant's status (`FINALIST`, `REVEALED`, or
  `WITHDRAWN` if they left during reveal).
- `winner.profile` — `null` if the finalist's profile can't be loaded; any
  field inside may be `null`. `primary_photo` is `null` when the finalist
  has no approved primary photo.
- `winner.rounds` — every session round ordered by `round_number`, each
  with the finalist's answers for that round (possibly an empty array).

**Errors:** `session_not_found`, `not_session_creator`

#### `GET /rounds/{roundId}/questions`

Returns the snapshotted questions for a round (what participants answer).

**Response `200`** — array of:

```json
{ "id": "uuid", "round_id": "uuid", "question": "Are you a morning person?", "sort_order": 1, "my_answer": "Definitely not" }
```

- `my_answer` — the caller's saved answer for that question, or `null` if not
  yet answered (or if the caller isn't a participant). Use it to resume the
  answering flow: skip/pre-fill questions that already have `my_answer`.

### 2.5 Participation

#### `POST /sessions/{sessionId}/join`

Joins a session. **Paid** — charges `BLIND_DATE_PARTICIPATE`. Only allowed
while round 1 is open.

**Request:** `{ "idempotency_key": "uuid" }` — **optional**. If omitted, the
server derives a deterministic key from `session_id + user_id` (a user can
only join a session once, so retries are naturally idempotent). Send your own
key only if you want client-controlled replay semantics.

**Response `200`**

```json
{
  "participant_id": "uuid",
  "session_id": "uuid",
  "status": "ACTIVE",
  "current_round_id": "uuid"
}
```

**Errors:** `session_not_found`, `session_not_open`, `session_expired`,
`session_full`, `creator_cannot_join`, `already_joined`, `join_window_closed`,
`no_open_round`, charge errors

#### `POST /participants/{participantId}/answers`

Submits (or updates) the participant's answers for the current round. Answers
are locked once the creator has made a selection on that participant.

**Partial submission is supported** — the `answers` map may contain any subset
of the round's questions, and each entry is upserted independently. For a
"one question at a time" UI, auto-save each answer as the user advances
(single-entry map) rather than holding all answers locally until the end —
this avoids losing work if the creator selects/eliminates the participant
(`answer_locked`) before a final batch submit.

**Request**

```json
{
  "answers": {
    "session-question-uuid-1": "My answer",
    "session-question-uuid-2": "Another answer"
  }
}
```

Keys are `id` values from `GET /rounds/{roundId}/questions`. Each answer
≤ 2000 chars.

**Response `204`**
**Errors:** `participant_not_found`, `not_participant`,
`participant_not_active`, `no_current_round`, `question_not_in_round`,
`answer_too_long`, `answer_locked`

#### `POST /participants/{participantId}/withdraw`

Participant leaves the session. **Response `204`.**
**Errors:** `participant_not_found`, `not_participant`, `cannot_withdraw`

### 2.6 Selections (creator)

#### `POST /sessions/{sessionId}/selections`

Creator decides a participant's fate in the current round. Idempotent per
(round, participant) — re-posting updates the decision.

**Request**

```json
{ "participantId": "uuid", "decision": "ADVANCE" }
```

| `decision` | Effect |
|---|---|
| `ADVANCE` | Participant survives to the next round |
| `ELIMINATE` | Participant is eliminated (notified) |
| `SELECT_FINALIST` | Participant becomes the finalist; session atomically transitions to `REVEAL`, all other still-active participants are eliminated and notified |

**Response `204`**
**Errors:** `not_session_creator`, `session_not_open`, `no_open_round`,
`participant_not_in_session`, `participant_not_in_round`,
`participant_not_active`, `invalid_decision`

### 2.7 Final decision (creator + finalist)

#### `POST /sessions/{sessionId}/final-decision`

Submits the caller's post-reveal decision. Callable by the creator and the
finalist only, while the session is in `REVEAL`. When both have decided, the
outcome resolves atomically; mutual `INTERESTED` creates a match.

**Request:** `{ "decision": "INTERESTED" }` (`INTERESTED` | `NOT_INTERESTED`)

**Response `200`**

```json
{
  "session_id": "uuid",
  "creator_decision": "INTERESTED",
  "participant_decision": "PENDING",
  "outcome": null,
  "match_id": null
}
```

`outcome` is `null` until both parties decide, then one of `MATCHED`,
`NO_MATCH`, `ALREADY_MATCHED`, `EXPIRED`. `match_id` is set when
`outcome = MATCHED`. If the 48-hour decision window lapses, the expiry worker
resolves the outcome as `EXPIRED`.

**Errors:** `session_not_found`, `session_not_in_reveal`,
`not_a_decision_party`, `invalid_decision`, `decision_already_submitted`,
`decision_already_resolved`

---

## 3. Admin API

There are currently **no dedicated Blind Date admin endpoints**. Admin-facing
integration points today:

- **Catalog management** — languages, categories and platform questions are
  seeded by Flyway migration (`V67__blind_date.sql`). New catalog content is
  added via migration; there is no CRUD admin surface yet.
- **Limits** — `max_participants` / `max_rounds` / `max_round_questions` /
  `max_questions` / `max_custom_questions` are server config
  (`BLIND_DATE_MAX_PARTICIPANTS`, `BLIND_DATE_MAX_ROUNDS`,
  `BLIND_DATE_MAX_ROUND_QUESTIONS`, `BLIND_DATE_MAX_QUESTIONS`,
  `BLIND_DATE_MAX_CUSTOM_QUESTIONS` env vars), not runtime-editable via API.
- **Pricing & quotas** — `BLIND_DATE_SESSION_CREATE` and
  `BLIND_DATE_PARTICIPATE` are standard `feature_actions`; plan rules,
  credit costs and per-period limits are managed through the existing admin
  billing/plan tooling.
- **Notifications** — Blind Date alerts ride on the generic `ACCOUNT_ALERT`
  notification type with codes `BLIND_DATE_REVEAL`, `BLIND_DATE_ELIMINATED`,
  `BLIND_DATE_MATCHED`, `BLIND_DATE_NO_MATCH`.

If admin moderation of sessions is needed (force-close, inspect
participants), that surface still needs to be built — the service layer
(`closeSession`, `cancelSession`) already supports it.

---

## 4. Push notifications

Clients should handle these `ACCOUNT_ALERT` codes:

| Alert code | Recipient | When |
|---|---|---|
| `BLIND_DATE_REVEAL` | Creator + finalist | Finalist selected; identities revealed |
| `BLIND_DATE_ELIMINATED` | Participant | Eliminated in a round or on finalist selection |
| `BLIND_DATE_MATCHED` | Creator + finalist | Mutual `INTERESTED`; match created |
| `BLIND_DATE_NO_MATCH` | Creator + finalist | Final outcome was not a match |

## 5. Client integration checklist

1. `GET /configuration` once per session-entry — cache `limits` and
   `supported_languages`.
2. Build the question set before allowing session creation
   (`GET /question-set`, catalog endpoints).
3. `POST /sessions` with a fresh `idempotency_key` per user intent; reuse the
   key on retry.
4. Poll or refresh `GET /sessions/{id}` for `status`, `participant_count`,
   `current_round_number`; drive UI affordances from `limits`.
5. Participants: `POST /sessions/{id}/join` → `GET /rounds/{id}/questions` →
   `POST /participants/{id}/answers`.
6. Creator: `POST /sessions/{id}/selections` per participant →
   `POST /sessions/{id}/rounds/close` → `POST /sessions/{id}/rounds` (until
   `max_rounds`) or `SELECT_FINALIST`.
7. Both parties: `POST /sessions/{id}/final-decision`; on `MATCHED`, deep-link
   to chat with `match_id`.

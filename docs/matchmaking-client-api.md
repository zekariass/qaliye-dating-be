# Personal Matchmaking — Client API

> Audience: mobile / web client integration. For admin (matchmaker console)
> endpoints see `matchmaking-admin-api.md`.

Base path: **`/api/v1/matchmaking`** (preferences, requests, introductions).

Audio & video calls (post-match, user-initiated) are documented separately
in `video-audio-call-client-api.md`.

---

## 1. Conventions

### Naming convention

- **JSON request bodies and responses: `snake_case` everywhere.**
  The server configures Jackson with `PropertyNamingStrategies.SNAKE_CASE`
  globally, so every JSON field is snake_case on the wire (e.g. `min_age`,
  `charge_idempotency_key`, `request_expires_at`).
- Enum string values are `UPPER_SNAKE_CASE` (e.g. `INTERESTED`, `PENDING`)
  except the children-preference fields which are lowercase
  (`any`, `yes`, `no`, `not_sure`, `open_to_discussion`).

### Authentication

Every endpoint requires a Bearer JWT access token:

```
Authorization: Bearer <access_token>
```

The user's identity is derived from the token's `sub` claim (a user UUID).
There is no user-id parameter anywhere — callers can only act on their own
resources.

### Content types

- Requests with a body: `Content-Type: application/json`
- All responses: `application/json`
- **No file uploads** exist in this API.

### Date / time format

All timestamps are ISO-8601 with UTC offset, produced by
`OffsetDateTime` serialization, e.g. `"2026-10-01T22:31:07.123456+00:00"`.

### ID format

All `*_id` fields are RFC 4122 UUIDs serialized as strings.

### Error format

All errors return a single envelope:

```json
{
  "error": {
    "code": "CONFLICT",
    "message": "You already have an active matchmaking request."
  }
}
```

| Status | Typical `code` values | Meaning |
|--------|----------------------|---------|
| 400 | `VALIDATION_ERROR`, `INVALID_PARAMETER` | Bean validation or parameter/type errors; `message` lists `field: reason` pairs |
| 401 | — (container/Spring default) | Missing or invalid Bearer token |
| 402 | `insufficient_credits` | Not enough credits for the charged action |
| 403 | `FORBIDDEN` | Authenticated but not the owner/participant |
| 404 | `NOT_FOUND` | Resource does not exist (or is not visible to you) |
| 409 | `CONFLICT` | Invalid state transition (already active request, decision already submitted, terminal status, match ended, …) |
| 422 | `UNPROCESSABLE` | Semantically invalid value (enum out of range, missing prerequisites like preferences) |
| 429 | `RATE_LIMITED` | Action-limit exceeded (`error.details.action_type`, `error.details.period_type` may be present) |
| 500 | `INTERNAL_ERROR` | Unexpected server error |

Machine-readable `code` values are passed through verbatim when the server
supplies one (e.g. `insufficient_credits`).

### Pagination

- `page` (0-based, default `0`) + `size` (default `20` for client lists).
- Most list endpoints return **bare JSON arrays** (`200` + `[]` when empty).
- The video-call-request list returns a paged envelope
  `{ "requests": [...], "total": n, "page": p, "size": s }`.

### Lifecycle reference

```
Request:      OPEN → ON_HOLD → MATCHED / COMPLETED / CANCELLED / EXPIRED
Introduction: PROPOSED → MATCHED / DECLINED / CANCELLED / EXPIRED
Video call:   PENDING → ACCEPTED → COMPLETED
                   ↘ DECLINED / CANCELLED / EXPIRED
```

Notes on the introduction flow: when **both** sides record `INTERESTED`
within the decision window the introduction goes straight to `MATCHED` and a
`matches` row is created — there is no scheduled-call step in between. Video
calls are a separate, user-initiated feature **after the match exists**
(see §5).

Platform defaults (server-configurable, subject to change):

| Setting | Default |
|---------|---------|
| Request lifetime (`expires_at`) | 90 days |
| Introduction decision window | 72 hours |
| Introductions consumed per request | 1 (request → `COMPLETED` when consumed) |
| Re-introduction cooldown | 30 days |
| Call request expiry (`PENDING`) | 48 hours |

### Endpoint index

**Part I — Matchmaking**

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/v1/matchmaking/preferences` | Read my preferences |
| PUT | `/api/v1/matchmaking/preferences` | Create/replace my preferences |
| POST | `/api/v1/matchmaking/requests` | Create request (idempotent) |
| GET | `/api/v1/matchmaking/requests` | My request history |
| GET | `/api/v1/matchmaking/requests/active` | Current OPEN/ON_HOLD request |
| GET | `/api/v1/matchmaking/requests/{requestId}` | One request (mine only) |
| DELETE | `/api/v1/matchmaking/requests/{requestId}` | Cancel request |
| GET | `/api/v1/matchmaking/introductions` | My introduction history (both roles) |
| GET | `/api/v1/matchmaking/introductions/{introductionId}` | Introduction detail + partner profile + score |
| POST | `/api/v1/matchmaking/introductions/{introductionId}/decision` | Accept/decline |

Audio & video call endpoints: see `video-audio-call-client-api.md`.

---

# Part I — Personal Matchmaking

## 2. Preferences

Preferences describe who the user wants to be matched with. They are stored
per user (upsert semantics — one row per user) and are read **live** during
candidate matching — there is no snapshot freezing; edits apply to the
next search/introduction.

### `GET /api/v1/matchmaking/preferences`

Returns the caller's current preferences.

**Success:** `200 OK` → `Preference` object (see schema below).

**Errors:**

| Status | Condition |
|--------|-----------|
| 404 `NOT_FOUND` | Preferences never set — `"Matchmaking preferences not found. Set them first."` |

---

### `PUT /api/v1/matchmaking/preferences`

Creates or fully replaces the caller's preferences. Every field you omit is
reset to its "inactive" default (the PUT is a replace, not a patch).
`version` increments on each update. `matchmaker_notes` (admin-only) is
preserved across user updates and is never writable here.

**Request body:** `application/json` — all fields optional; `null`/omitted
scalar = inactive constraint; omitted/empty array = "any".

| Field | Type | Required | Constraints / normalization |
|-------|------|----------|-----------------------------|
| `min_age` | integer \| null | optional | ≥ 18; must be ≤ `max_age` when both set |
| `max_age` | integer \| null | optional | ≤ 120; must be ≥ `min_age` |
| `min_height_cm` | integer \| null | optional | > 0; must be ≤ `max_height_cm` |
| `max_height_cm` | integer \| null | optional | must be ≥ `min_height_cm` |
| `specific_country_codes` | string[] | optional | ISO 3166-1 alpha-2; **auto-uppercased**; empty = any country |
| `has_children_preference` | string | optional | `any` \| `yes` \| `no`; **auto-lowercased**; null/blank → `"any"` |
| `wants_children_preference` | string | optional | `any` \| `yes` \| `no` \| `not_sure` \| `open_to_discussion`; auto-lowercased; null → `"any"` |
| `religion_preferences` | string[] | optional | Candidate religions: `ORTHODOX_CHRISTIAN`, `PROTESTANT`, `CATHOLIC`, `MUSLIM`, `TRADITIONAL`, `OTHER`, `PREFER_NOT_TO_SAY`. Sent verbatim |
| `education_levels` | string[] | optional | Candidate education levels: `HIGH_SCHOOL`, `DIPLOMA`, `BACHELORS`, `MASTERS`, `DOCTORATE`, `OTHER`. Sent verbatim |
| `marital_statuses` | string[] | optional | **Auto-uppercased**; each ∈ `NEVER_MARRIED`, `DIVORCED`, `WIDOWED`, `SEPARATED` |
| `smoking_preferences` | string[] | optional | **Auto-uppercased**; each ∈ `NO`, `YES`, `OCCASIONALLY`, `TRYING_TO_QUIT` |
| `drinking_preferences` | string[] | optional | **Auto-uppercased**; each ∈ `NO`, `SOCIALLY`, `OCCASIONALLY`, `YES` |
| `language_preference_ids` | string[] (UUIDs) | optional | UUIDs from the languages catalog; empty = any |
| `ethnicity_preference_ids` | string[] (UUIDs) | optional | UUIDs from the ethnicities catalog; empty = any |
| `user_notes` | string \| null | optional | Free-text notes for your matchmaker |
| `marriage_timeline` | string \| null | optional | How soon you want to marry — compatibility dimension |
| `long_distance_relationship` | string \| null | optional | Openness to long distance — compatibility dimension |
| `family_involvement` | string \| null | optional | Expected family involvement — compatibility dimension |
| `religion_important` | string \| null | optional | How important religion is — compatibility dimension |
| `willing_to_relocate` | string \| null | optional | Willingness to relocate — compatibility dimension |

> **`gender` is not accepted.** The stored `gender` column is derived on every
> write as the **opposite** of the caller's `profiles.gender` — it is the
> sought partner gender and is never client-editable.

**Example request:**

```http
PUT /api/v1/matchmaking/preferences HTTP/1.1
Authorization: Bearer eyJhbGciOi...
Content-Type: application/json

{
  "min_age": 26,
  "max_age": 34,
  "min_height_cm": 160,
  "specific_country_codes": ["et", "us"],
  "has_children_preference": "no",
  "wants_children_preference": "yes",
  "religion_preferences": ["ORTHODOX_CHRISTIAN", "PROTESTANT"],
  "education_levels": ["BACHELORS", "MASTERS"],
  "marital_statuses": ["never_married"],
  "smoking_preferences": ["no"],
  "drinking_preferences": ["no", "socially"],
  "language_preference_ids": ["3fa85f64-5717-4562-b3fc-2c963f66afa6"],
  "ethnicity_preference_ids": [],
  "user_notes": "Prefer someone based in Addis or willing to relocate.",
  "marriage_timeline": "within_1_year",
  "long_distance_relationship": "open_to_it",
  "willing_to_relocate": "yes"
}
```

**Success:** `200 OK` → `Preference` object (normalized values echoed back).

**Errors:**

| Status | Condition |
|--------|-----------|
| 400 `VALIDATION_ERROR` | Malformed JSON body / wrong types |
| 422 `UNPROCESSABLE` | `min_age < 18`, `max_age > 120`, `min_age > max_age`, `min_height_cm <= 0`, `min_height_cm > max_height_cm`, or an enum value outside the allowed set (message names the field) |

### `Preference` object (response schema)

| Field | Type | Nullable | Notes |
|-------|------|----------|-------|
| `id` | UUID string | no | |
| `user_id` | UUID string | no | Always the caller |
| `version` | integer | no | Starts at 1, +1 per upsert |
| `gender` | string \| null | yes | Server-derived sought partner gender (`MALE`/`FEMALE`); null until profile has gender |
| `min_age`, `max_age` | integer | yes | |
| `min_height_cm`, `max_height_cm` | integer | yes | |
| `specific_country_codes` | string[] | no | `[]` = any |
| `has_children_preference` | string | no | `any`/`yes`/`no` |
| `wants_children_preference` | string | no | `any`/`yes`/`no`/`not_sure`/`open_to_discussion` |
| `religion_preferences`, `education_levels`, `marital_statuses`, `smoking_preferences`, `drinking_preferences` | string[] | no | `[]` = any |
| `language_preference_ids`, `ethnicity_preference_ids` | UUID string[] | no | `[]` = any |
| `matchmaker_notes` | string \| null | yes | Admin-managed internal notes; read-only for clients |
| `user_notes` | string \| null | yes | |
| `marriage_timeline`, `long_distance_relationship`, `family_involvement`, `religion_important`, `willing_to_relocate` | string \| null | yes | Compatibility dimensions |
| `created_at`, `updated_at` | ISO-8601 | no | |

---

## 3. Requests

A request puts the user into the matchmaking queue. Creating one evaluates
the `MATCHMAKING_REQUEST` action cost (subscription allowance first, then
credits) and requires saved preferences.

### `POST /api/v1/matchmaking/requests`

Creates a matchmaking request. **Idempotent** on `charge_idempotency_key`:
re-sending the same key returns the existing request instead of charging
twice.

**Request body:**

| Field | Type | Required |
|-------|------|----------|
| `charge_idempotency_key` | UUID string | **yes** — generate once per user intent, reuse on retries |

```json
{ "charge_idempotency_key": "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed" }
```

**Success:** `201 Created` → `Request` object (also `201` when returned via
the idempotency path — the body is the pre-existing row).

**Errors:**

| Status | Condition |
|--------|-----------|
| 400 `VALIDATION_ERROR` | `charge_idempotency_key` missing/null |
| 402 `insufficient_credits` | Not enough credits and no allowance left |
| 429 `RATE_LIMITED` | Request action limit reached for the period |
| 422 `UNPROCESSABLE` | Preferences never set — `"Set matchmaking preferences before submitting a request."` |
| 409 `CONFLICT` | An `OPEN`/`ON_HOLD` request already exists — `"You already have an active matchmaking request."` |

---

### `GET /api/v1/matchmaking/requests`

Caller's request history, **newest first** (`created_at DESC`).

**Query params:** `page` (int, default `0`), `size` (int, default `20`)

**Success:** `200 OK` → `Request[]` (possibly `[]`)

---

### `GET /api/v1/matchmaking/requests/active`

The caller's current `OPEN` or `ON_HOLD` request.

**Success:** `200 OK` → `Request`

**Errors:** `404 NOT_FOUND` — no active request. Treat 404 as the normal
"nothing active" signal, not a failure.

---

### `GET /api/v1/matchmaking/requests/{requestId}`

Fetch one request. Must belong to the caller.

**Success:** `200 OK` → `Request`

**Errors:** `404 NOT_FOUND` (unknown id) · `403 FORBIDDEN` (not the caller's request)

---

### `DELETE /api/v1/matchmaking/requests/{requestId}`

Cancels an `OPEN`/`ON_HOLD` request. Cascades: any `PROPOSED` introduction
involving the request is cancelled, and the other party's request is
released back to `OPEN`.

**Success:** `204 No Content` — empty body

**Errors:**

| Status | Condition |
|--------|-----------|
| 404 | Unknown id |
| 403 | Not the caller's request |
| 409 | Request already in a terminal state (`MATCHED`, `COMPLETED`, `CANCELLED`, `EXPIRED`) |

### `Request` object (response schema)

| Field | Type | Nullable | Notes |
|-------|------|----------|-------|
| `id` | UUID | no | |
| `user_id` | UUID | no | |
| `matchmaking_preferences_id` | UUID | no | Points at the caller's live preference row |
| `status` | string | no | `OPEN`, `ON_HOLD`, `MATCHED`, `COMPLETED`, `CANCELLED`, `EXPIRED` |
| `request_charge_idempotency_key` | UUID | yes | |
| `contact_phone` | string \| null | yes | |
| `created_at`, `updated_at` | ISO-8601 | no | |
| `expires_at` | ISO-8601 | yes | Auto-expiry time (≈90 days after creation) |
| `completed_at`, `cancelled_at` | ISO-8601 | yes | |

**Status meanings:**

| `status` | Meaning |
|----------|---------|
| `OPEN` | In the matchmaker queue |
| `ON_HOLD` | Paused — an introduction is in flight for this request |
| `MATCHED` | A match was created (both sides were `INTERESTED`) |
| `COMPLETED` | The request consumed its introduction quota and closed |
| `CANCELLED` | Cancelled by user or admin |
| `EXPIRED` | Lifetime elapsed with no match |

**Example `Request` response:**

```json
{
  "id": "a12f4e8b-9c3d-4e5f-8a1b-2c3d4e5f6a7b",
  "user_id": "b5f8c1d2-3e4a-4f5b-8c9d-0e1f2a3b4c5d",
  "matchmaking_preferences_id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "status": "OPEN",
  "request_charge_idempotency_key": "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
  "contact_phone": null,
  "created_at": "2026-10-01T21:45:00+00:00",
  "completed_at": null,
  "cancelled_at": null,
  "expires_at": "2026-12-30T21:45:00+00:00",
  "updated_at": "2026-10-01T21:45:00+00:00"
}
```

---

## 4. Introductions

An introduction pairs your request with a candidate chosen by a matchmaker.
Both sides must answer **INTERESTED** within the decision window (~72 h):
two `INTERESTED`s transition the introduction straight to `MATCHED` and
create a `matches` record — the pair then lands in the normal match/chat
flow, where calls can be requested (see `video-audio-call-client-api.md`).

### `GET /api/v1/matchmaking/introductions`

The caller's introduction history — every introduction where the caller is
**either** the `REQUESTER` or the `CANDIDATE`, newest proposal first
(`proposed_at DESC`, falling back to `created_at DESC` for legacy rows).

**Query params:** `page` (int, default `0`), `size` (int, default `20`)

**Success:** `200 OK` → `IntroductionSummary[]` (bare array, `[]` when empty)

### `IntroductionSummary` object (list response schema)

| Field | Type | Nullable | Notes |
|-------|------|----------|-------|
| `id` | UUID | no | Fetch `GET /introductions/{id}` for the full decision screen |
| `your_request_id` | UUID | no | The caller's own request id for this introduction |
| `role` | string | no | `REQUESTER` \| `CANDIDATE` — which side the caller is |
| `your_decision` | string | no | `PENDING` \| `INTERESTED` \| `NOT_INTERESTED` |
| `partner_decision` | string | no | Same domain — **masked to `"PENDING"` while `your_decision` is `PENDING`** (one side cannot see the other's choice before deciding) |
| `status` | string | no | `PROPOSED`, `MATCHED`, `DECLINED`, `CANCELLED`, `EXPIRED` |
| `overall_percentage` | int | no | Compatibility score 0–100 |
| `decision_expires_at` | ISO-8601 | yes | Auto-decline deadline (~72 h) |
| `match_id` | UUID | yes | Set on match — deep-link into chat |
| `proposed_at`, `created_at` | ISO-8601 | yes | |
| `partner` | object | no | `{ display_name, age, primary_photo_url }` — `primary_photo_url` is signed |

**Example `IntroductionSummary`:**

```json
{
  "id": "d3f5a9c2-7e1b-4c8d-9f2a-5b6c7d8e9f0a",
  "your_request_id": "a12f4e8b-9c3d-4e5f-8a1b-2c3d4e5f6a7b",
  "role": "REQUESTER",
  "your_decision": "PENDING",
  "partner_decision": "PENDING",
  "status": "PROPOSED",
  "overall_percentage": 78,
  "decision_expires_at": "2026-10-05T09:00:00+00:00",
  "match_id": null,
  "proposed_at": "2026-10-02T09:00:00+00:00",
  "created_at": "2026-10-02T09:00:00+00:00",
  "partner": {
    "display_name": "Sara",
    "age": 28,
    "primary_photo_url": "https://<signed-url>"
  }
}
```

---

### `GET /api/v1/matchmaking/introductions/{introductionId}`

**Success:** `200 OK` → `IntroductionView` — the introduction, the
bidirectional compatibility score, and the proposed partner's **full
profile** (photos, bio, address, lifestyle fields). This is everything the
decision screen needs; no extra profile call is required.

**Errors:** `404` unknown · `403` caller is not a participant

### `IntroductionView` object (GET response schema)

| Field | Type | Nullable | Notes |
|-------|------|----------|-------|
| `id` | UUID | no | |
| `requester_request_id`, `candidate_request_id` | UUID | no | |
| `role` | string | no | `REQUESTER` \| `CANDIDATE` — which side the caller is |
| `your_request_id` | UUID | no | The caller's own request id |
| `your_decision` | string | no | Caller's decision: `PENDING` \| `INTERESTED` \| `NOT_INTERESTED` |
| `partner_decision` | string | no | Other side's decision — **masked to `"PENDING"` while `your_decision` is `PENDING`** |
| `status` | string | no | `PROPOSED`, `MATCHED`, `DECLINED`, `CANCELLED`, `EXPIRED` |
| `forward_percentage` | int | no | How well **your** prefs are satisfied by the partner (0–100) |
| `reverse_percentage` | int | no | How well **their** prefs are satisfied by you (0–100) |
| `overall_percentage` | int | no | `round((forward + reverse) / 2)` |
| `decision_expires_at` | ISO-8601 | yes | Auto-decline deadline (~72 h) |
| `requester_decided_at`, `candidate_decided_at` | ISO-8601 | yes | |
| `match_id` | UUID | yes | Set on match creation — feed into the chat/match screens |
| `proposed_at`, `completed_at`, `created_at`, `updated_at` | ISO-8601 | yes | |
| `partner` | object | no | Proposed partner's profile — same shape as the other-user profile payload: `user_id`, `display_name`, `age`, `gender`, `bio`, `height_cm`, `residency_type`, `address` (`city`/`region`/`country_*`/`formatted_address`), `ethnicities[]`, `nationality`, `religion`, `education_level`, `occupation`, `relationship_intention`, `marital_status`, `has_children`, `wants_children`, `activity_level`, `interests[]`, `languages[]`, `is_verified`, `primary_photo_url`, `photos[]` (each `{id, photo_order, is_primary, signed_url, expires_at}`), `activity_status` |

> The partner profile is always rendered for an introduction — discovery
> visibility/incognito settings do not apply inside matchmaking.

### `POST /api/v1/matchmaking/introductions/{introductionId}/decision`

Submits your decision. **Final — cannot be changed.**

**Request body:**

| Field | Type | Required | Values |
|-------|------|----------|--------|
| `decision` | string | yes | `INTERESTED` \| `NOT_INTERESTED` (case-sensitive) |

```json
{ "decision": "INTERESTED" }
```

**Success:** `200 OK` → `Introduction` (raw row, post-transition state).
To learn whether the other side has answered, re-poll the `GET` endpoint
and read `partner_decision`; when both are `INTERESTED` the status becomes
`MATCHED` and `match_id` is populated.

**Errors:**

| Status | Condition |
|--------|-----------|
| 400 `VALIDATION_ERROR` | Missing/blank `decision`, or value not `INTERESTED`/`NOT_INTERESTED` |
| 403 | Caller is not a participant |
| 404 | Unknown introduction |
| 409 | Introduction no longer `PROPOSED`, or caller already submitted |

### `Introduction` object (decision-response schema)

| Field | Type | Nullable | Notes |
|-------|------|----------|-------|
| `id` | UUID | no | |
| `requester_request_id`, `candidate_request_id` | UUID | no | Your request is one of the two |
| `initiated_by_admin_user_id` | UUID | yes | Matchmaker who created it |
| `requester_decision`, `candidate_decision` | string | no | `PENDING` \| `INTERESTED` \| `NOT_INTERESTED` |
| `status` | string | no | `PROPOSED`, `MATCHED`, `DECLINED`, `CANCELLED`, `EXPIRED` |
| `forward_percentage`, `reverse_percentage`, `overall_percentage` | int | no | Bidirectional compatibility score |
| `match_details` | JSON string | yes | Per-dimension breakdown `[{dimension, matched, label}]` — parse the string as JSON |
| `reason` | string \| null | yes | Matchmaker's note on why the pair was chosen |
| `requester_decided_at`, `candidate_decided_at` | ISO-8601 | yes | |
| `decision_expires_at` | ISO-8601 | yes | Auto-decline deadline (~72 h) |
| `match_id` | UUID | yes | Set when a match was created |
| `proposed_at`, `completed_at`, `created_at`, `updated_at` | ISO-8601 | yes | |

**Example `Introduction` response:**

```json
{
  "id": "d3f5a9c2-7e1b-4c8d-9f2a-5b6c7d8e9f0a",
  "requester_request_id": "a12f4e8b-9c3d-4e5f-8a1b-2c3d4e5f6a7b",
  "candidate_request_id": "8b4d2f6a-1c3e-4a5b-9d8f-2e3a4b5c6d7e",
  "initiated_by_admin_user_id": "e6a1b8c4-2d5f-4e7a-9b3c-4d5e6f7a8b9c",
  "requester_decision": "INTERESTED",
  "candidate_decision": "PENDING",
  "status": "PROPOSED",
  "forward_percentage": 82,
  "reverse_percentage": 74,
  "overall_percentage": 78,
  "match_details": "[{\"dimension\":\"age\",\"matched\":true,\"label\":\"Age range\"}]",
  "reason": "Strong values alignment; both want children.",
  "requester_decided_at": "2026-10-02T10:05:00+00:00",
  "candidate_decided_at": null,
  "decision_expires_at": "2026-10-05T09:00:00+00:00",
  "match_id": null,
  "proposed_at": "2026-10-02T09:00:00+00:00",
  "completed_at": null,
  "created_at": "2026-10-02T09:00:00+00:00",
  "updated_at": "2026-10-02T10:05:00+00:00"
}
```

---

## 5. Notifications

Matchmaking lifecycle events arrive as push/in-app notifications. On the
wire they use the shared `ACCOUNT_ALERT` envelope — the FCM/Expo `data`
object always carries:

| Field | Notes |
|-------|-------|
| `notification_type` | Always `"ACCOUNT_ALERT"` for these events — **route on `alert_code`, not this field** |
| `alert_code` | One of the matchmaking codes below (also `VERIFICATION_*`, `PAYMENT_*`, `BLIND_DATE_*`, `*_CALL_*` for other features) |
| Entity ids | Per-event, see table — all values are **strings** (FCM data values are always strings) |

| `alert_code` | Recipients | Data fields (in addition to `notification_type`, `alert_code`) |
|--------------|-----------|----------------------------------------------------------------|
| `MATCHMAKING_REQUEST_CREATED` | Requester | `request_id` |
| `MATCHMAKING_REQUEST_CANCELLED` | Requester | `request_id` |
| `MATCHMAKING_REQUEST_EXPIRED` | Requester | `request_id` |
| `MATCHMAKING_INTRODUCTION_PROPOSED` | Both participants | `introduction_id`, `request_id` — `request_id` is **the recipient's own request** (differs per recipient) |
| `MATCHMAKING_INTRODUCTION_DECLINED` | Both participants | `introduction_id`, `request_id` (recipient's own) |
| `MATCHMAKING_INTRODUCTION_CANCELLED` | Both participants | `introduction_id`, `request_id` (recipient's own) — fired when an admin cancels the introduction or a participant's request cancel cascades |
| `MATCHMAKING_INTRODUCTION_EXPIRED` | Both participants | `introduction_id`, `request_id` (recipient's own) — decision window elapsed |
| `MATCHMAKING_MATCHED` | Both participants | `match_id`, `introduction_id` — `match_id` deep-links into chat |

Example `data` payload for `MATCHMAKING_INTRODUCTION_PROPOSED`:

```json
{
  "notification_type": "ACCOUNT_ALERT",
  "alert_code": "MATCHMAKING_INTRODUCTION_PROPOSED",
  "introduction_id": "d3f5a9c2-7e1b-4c8d-9f2a-5b6c7d8e9f0a",
  "request_id": "a12f4e8b-9c3d-4e5f-8a1b-2c3d4e5f6a7b"
}
```

> A `MATCH_CREATED` notification (`notification_type = "MATCH_CREATED"`,
> `match_id` in data) is emitted alongside `MATCHMAKING_MATCHED` — it is the
> generic match alert used by every match source, not matchmaking-specific.
> Dedup on `match_id` if you render both.

### Client integration notes

- **History exists:** `GET /introductions` lists all of the caller's
  introductions — proposals are no longer push-only.
- **`active_introduction_id`:** on `GET /requests/active` (and every other
  request response) — `ON_HOLD` + non-null id means an introduction is
  waiting for the caller's decision.
- **Decision masking:** `partner_decision` reads `PENDING` until the caller
  decides — it is not a signal that the partner is slow.
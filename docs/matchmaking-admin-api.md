# Personal Matchmaking — Admin API

> Audience: matchmaker console / ops tooling. For mobile/client endpoints see
> `matchmaking-client-api.md`.

Base path: **`/api/v1/admin/matchmaking`**

---

## 1. Conventions

### Naming convention

- **JSON request bodies and responses: `snake_case` everywhere.**
  The server configures Jackson with `PropertyNamingStrategies.SNAKE_CASE`
  globally, so every JSON field is snake_case on the wire (e.g.
  `requester_request_id`, `forward_percentage`, `matchmaker_notes`).
- Enum string values are `UPPER_SNAKE_CASE` (e.g. `PROPOSED`, `INTERESTED`)
  except the children-preference fields which are lowercase
  (`any`, `yes`, `no`, `not_sure`, `open_to_discussion`).

### Authentication & authorization

Every endpoint requires a Bearer JWT:

```
Authorization: Bearer <access_token>
```

The caller's `sub` must resolve to a user whose `role` is `ADMIN`; otherwise
the endpoint returns `403 FORBIDDEN` with `"Admin access required."` — even
if the JWT is otherwise valid.

### Content types

- Requests with a body: `Content-Type: application/json`
- All responses: `application/json`

### Date / time & ID formats

- Timestamps: ISO-8601 with UTC offset (`"2026-10-01T22:31:07.123456+00:00"`).
- IDs: RFC 4122 UUID strings.

### Error format

```json
{
  "error": {
    "code": "UNPROCESSABLE",
    "message": "Unknown request status: PENDNG"
  }
}
```

| Status | Meaning |
|--------|---------|
| 400 | Bean validation failure (`VALIDATION_ERROR`) |
| 401 | Missing/invalid token |
| 403 | `FORBIDDEN` — JWT owner is not `ADMIN` |
| 404 | `NOT_FOUND` — resource does not exist |
| 409 | `CONFLICT` — invalid state transition (cancelling a terminal request/introduction, creating an introduction against a request that is not `OPEN`, …) |
| 422 | `UNPROCESSABLE` — unknown enum value (status/override key), missing preferences or profile facts |

### Pagination

Admin list endpoints use `page` (0-based) + `size` (default **50**) and
return an envelope:

```json
{ "requests": [...], "total": 137, "page": 0, "size": 50 }
```

(`introductions` instead of `requests` on the introductions list.)

### Lifecycle reference

```
Request:      OPEN → ON_HOLD → MATCHED / COMPLETED / CANCELLED / EXPIRED
Introduction: PROPOSED → MATCHED / DECLINED / CANCELLED / EXPIRED
```

When both parties answer `INTERESTED` within the decision window the
introduction goes straight to `MATCHED` and a `matches` row is created —
there is no scheduled-call or messaging step between introduction and
match. Video calls are a **user-facing** feature on active matches (see the
client doc §5); there are no admin video-call endpoints.

### Endpoint index

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/stats` | Dashboard funnel counts |
| GET | `/requests` | Request queue (paged, status filter) |
| GET | `/requests/{requestId}` | Single request |
| DELETE | `/requests/{requestId}` | Cancel request (cascades) |
| GET | `/requests/{requestId}/introductions` | Introductions touching a request |
| POST | `/requests/{requestId}/candidate-search` | Scored/paged candidate search |
| GET | `/requests/{requestId}/notes` | Admin notes on a request |
| POST | `/requests/{requestId}/notes` | Add admin note |
| PATCH | `/users/{userId}/matchmaking-preferences/notes` | Set `matchmaker_notes` |
| GET | `/introductions` | Global introductions list (paged) |
| POST | `/introductions` | Propose an introduction |
| GET | `/introductions/{introductionId}` | Single introduction |
| DELETE | `/introductions/{introductionId}` | Cancel an open introduction |

---

## 2. Dashboard

### `GET /api/v1/admin/matchmaking/stats`

Funnel counts for the console dashboard.

**Success:** `200 OK` →

```json
{
  "requests": {
    "OPEN": 42,
    "ON_HOLD": 7,
    "MATCHED": 15,
    "COMPLETED": 30,
    "CANCELLED": 11,
    "EXPIRED": 9
  },
  "introductions": {
    "PROPOSED": 7,
    "MATCHED": 15,
    "DECLINED": 21,
    "CANCELLED": 3,
    "EXPIRED": 4
  },
  "actionNeeded": { "awaitingDecision": 7 },
  "totalMatchmakingMatches": 15
}
```

`requests` and `introductions` are status → count maps (a status only
appears when the count is > 0 — treat missing keys as `0`).
`actionNeeded.awaitingDecision` = `PROPOSED` introductions awaiting a user
decision. `totalMatchmakingMatches` counts `matches` rows created via the
matchmaking flow.

---

## 3. Requests (queue management)

### `GET /api/v1/admin/matchmaking/requests`

All requests, optionally filtered. Ordered `created_at ASC` (oldest first —
the queue is FIFO).

**Query params:**

| Param | Type | Default | Notes |
|-------|------|---------|-------|
| `status` | string | all | `OPEN`, `ON_HOLD`, `MATCHED`, `COMPLETED`, `CANCELLED`, `EXPIRED` — **422** on any other value |
| `page` | int | 0 | |
| `size` | int | 50 | |

**Success:** `200 OK` → paged envelope `{ "requests": [RequestRow], "total", "page", "size" }`

### `GET /api/v1/admin/matchmaking/requests/{requestId}`

Single request, any owner.

**Success:** `200 OK` → `RequestRow` · **Errors:** `404` unknown

### `DELETE /api/v1/admin/matchmaking/requests/{requestId}`

Admin cancel. Cascades like the user cancel: any `PROPOSED` introduction
involving the request is cancelled and the partner request is released back
to `OPEN`. Side effects: the owner receives a `MATCHMAKING_REQUEST_CANCELLED`
alert, and — when an introduction was cascaded — both participants receive
`MATCHMAKING_INTRODUCTION_CANCELLED`.

**Success:** `204 No Content` · **Errors:** `404` unknown · `409` already terminal

### `RequestRow` object

| Field | Type | Nullable | Notes |
|-------|------|----------|-------|
| `id` | UUID | no | |
| `user_id` | UUID | no | |
| `matchmaking_preferences_id` | UUID | no | |
| `status` | string | no | `OPEN` \| `ON_HOLD` \| `MATCHED` \| `COMPLETED` \| `CANCELLED` \| `EXPIRED` |
| `request_charge_idempotency_key` | UUID | yes | |
| `contact_phone` | string | yes | |
| `created_at`, `updated_at`, `expires_at`, `completed_at`, `cancelled_at` | ISO-8601 | yes (some) | |
| `active_introduction_id` | UUID | yes | Live `PROPOSED` introduction involving this request, if any |

---

## 4. Candidate search

### `POST /api/v1/admin/matchmaking/requests/{requestId}/candidate-search`

Runs the bidirectional scored search for a request: the requester's live
preferences (optionally overridden per-invocation) are evaluated against
each candidate's profile facts, **and** the candidate's own preferences are
evaluated against the requester. Results are ranked by `overall_percentage`.

Only candidates with an `OPEN` request (plus gender, block, and
re-introduction-cooldown eligibility rules) are returned.

**Request body** (optional — empty body = requester's live prefs, page 0, size 20):

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `override_params` | object | `{}` | Ad-hoc constraint overrides for this search only — keys from the preference set (e.g. `min_age`, `max_age`, `specific_country_codes`, `smoking_preferences`, `language_preference_ids`, …). Unknown keys → **422** |
| `page` | int | 0 | |
| `size` | int | 20 | |

```json
{
  "override_params": { "max_age": 40, "specific_country_codes": ["ET", "US"] },
  "page": 0,
  "size": 20
}
```

**Success:** `200 OK` → `CandidateMatchView[]` (bare array, ranked).

**Errors:** `404` unknown request · `422` unknown `override_params` key or invalid value

### `CandidateMatchView` object

| Field | Type | Notes |
|-------|------|-------|
| `request` | `RequestRow` | The candidate's matchmaking request |
| `profile` | `CandidateProfileCard` | Compact card: `user_id`, `display_name`, `age`, `gender`, `city`, `region`, `country_code`, `country_name`, `is_verified`, `primary_photo_url` (signed, ~1 h TTL), `last_active_at` |
| `preferences` | `PreferenceValues` | The candidate's live preference values (same field names as the client `PUT /preferences` body, minus `user_notes`) |
| `match` | `MatchScore` | `forward_percentage` (requester prefs vs candidate), `reverse_percentage` (candidate prefs vs requester), `overall_percentage`, `details`: `[{ "dimension", "matched", "label" }]` per-dimension breakdown |

---

## 5. Introductions

### `GET /api/v1/admin/matchmaking/introductions`

Global list for the dashboard, newest first.

**Query params:**

| Param | Type | Default | Notes |
|-------|------|---------|-------|
| `status` | string | all | `PROPOSED`, `MATCHED`, `DECLINED`, `CANCELLED`, `EXPIRED` — **422** otherwise |
| `user_id` | UUID | all | Either participant's user id |
| `search` | string | all | Display-name substring (case-insensitive) |
| `page` | int | 0 | |
| `size` | int | 50 | |

**Success:** `200 OK` → `{ "introductions": [IntroductionRow], "total", "page", "size" }`

### `POST /api/v1/admin/matchmaking/introductions`

Proposes a pairing between two `OPEN` requests. The service computes the
bidirectional compatibility score (persisted on the row, including the
per-dimension `match_details` breakdown), moves both requests to `ON_HOLD`,
sets the ~72 h `decision_expires_at` window, and notifies both users
(`MATCHMAKING_INTRODUCTION_PROPOSED`).

**Request body:**

| Field | Type | Required |
|-------|------|----------|
| `requester_request_id` | UUID | **yes** |
| `candidate_request_id` | UUID | **yes** |
| `reason` | string | optional — matchmaker's rationale, visible to users |

```json
{
  "requester_request_id": "a12f4e8b-9c3d-4e5f-8a1b-2c3d4e5f6a7b",
  "candidate_request_id": "8b4d2f6a-1c3e-4a5b-9d8f-2e3a4b5c6d7e",
  "reason": "Strong values alignment; both want children."
}
```

**Success:** `201 Created` → `IntroductionRow`

**Errors:**

| Status | Condition |
|--------|-----------|
| 400 | Missing `requester_request_id` / `candidate_request_id` |
| 404 | Either request id unknown |
| 409 | Either request not `OPEN` |
| 422 | Either user's preferences or profile facts missing (unprocessable prerequisite) |

### `GET /api/v1/admin/matchmaking/introductions/{introductionId}`

**Success:** `200 OK` → `IntroductionRow` · **Errors:** `404` unknown

### `GET /api/v1/admin/matchmaking/requests/{requestId}/introductions`

All introductions involving a request (either side), newest first.

**Success:** `200 OK` → `IntroductionRow[]` (`[]` if the request has none —
an unknown request id also yields `[]`, not `404`)

### `DELETE /api/v1/admin/matchmaking/introductions/{introductionId}`

Cancels a `PROPOSED` introduction → `CANCELLED`; both requests are released
back to `OPEN`. Both participants receive `MATCHMAKING_INTRODUCTION_CANCELLED`.

**Success:** `204 No Content` · **Errors:** `404` unknown · `409` not `PROPOSED`

### `IntroductionRow` object

| Field | Type | Nullable | Notes |
|-------|------|----------|-------|
| `id` | UUID | no | |
| `requester_request_id`, `candidate_request_id` | UUID | no | |
| `initiated_by_admin_user_id` | UUID | yes | The matchmaker who created it |
| `requester_decision`, `candidate_decision` | string | no | `PENDING` \| `INTERESTED` \| `NOT_INTERESTED` |
| `status` | string | no | `PROPOSED` \| `MATCHED` \| `DECLINED` \| `CANCELLED` \| `EXPIRED` |
| `forward_percentage`, `reverse_percentage`, `overall_percentage` | int | no | Bidirectional score |
| `match_details` | JSON string | yes | `[{dimension, matched, label}]` — parse as JSON |
| `reason` | string | yes | |
| `requester_decided_at`, `candidate_decided_at` | ISO-8601 | yes | |
| `decision_expires_at` | ISO-8601 | yes | ~72 h decision window |
| `match_id` | UUID | yes | Set when the pairing matched |
| `proposed_at`, `completed_at`, `created_at`, `updated_at` | ISO-8601 | yes | |

**Example `IntroductionRow`:**

```json
{
  "id": "d3f5a9c2-7e1b-4c8d-9f2a-5b6c7d8e9f0a",
  "requester_request_id": "a12f4e8b-9c3d-4e5f-8a1b-2c3d4e5f6a7b",
  "candidate_request_id": "8b4d2f6a-1c3e-4a5b-9d8f-2e3a4b5c6d7e",
  "initiated_by_admin_user_id": "e6a1b8c4-2d5f-4e7a-9b3c-4d5e6f7a8b9c",
  "requester_decision": "PENDING",
  "candidate_decision": "PENDING",
  "status": "PROPOSED",
  "forward_percentage": 82,
  "reverse_percentage": 74,
  "overall_percentage": 78,
  "match_details": "[{\"dimension\":\"age\",\"matched\":true,\"label\":\"Age range\"}]",
  "reason": "Strong values alignment; both want children.",
  "requester_decided_at": null,
  "candidate_decided_at": null,
  "decision_expires_at": "2026-10-05T09:00:00+00:00",
  "match_id": null,
  "proposed_at": "2026-10-02T09:00:00+00:00",
  "completed_at": null,
  "created_at": "2026-10-02T09:00:00+00:00",
  "updated_at": "2026-10-02T09:00:00+00:00"
}
```

---

## 6. Notes

### `GET /api/v1/admin/matchmaking/requests/{requestId}/notes`

Internal admin notes on a request, oldest first.

**Success:** `200 OK` → `NoteRow[]`

### `POST /api/v1/admin/matchmaking/requests/{requestId}/notes`

Appends a note; `admin_user_id` is set from the JWT `sub`.

**Request body:** `{ "note": "<string, required>" }`

**Success:** `201 Created` → `NoteRow` · **Errors:** `400` blank note · `404` unknown request

### `PATCH /api/v1/admin/matchmaking/users/{userId}/matchmaking-preferences/notes`

Sets `matchmaker_notes` on a user's preference row — admin-only field,
invisible to the user, preserved across the user's own `PUT /preferences`
upserts.

**Request body:** `{ "notes": "<string, required>" }`

**Success:** `200 OK` — empty body · **Errors:** `400` blank notes · `404` user has no preferences row

### `NoteRow` object

| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | |
| `request_id` | UUID | |
| `admin_user_id` | UUID | Author |
| `note` | string | |
| `created_at` | ISO-8601 | |

---

## 7. Removed surface

The previous design's admin endpoints — request messaging
(`sendAdminMessage`), scheduled video calls (`arrange`, `requestContacts`,
`submitOutcome`, provider selection), escrow, and the `MUTUAL_INTEREST`
introduction status — **no longer exist**. Video calls are entirely
user-initiated on active matches (`/api/v1/video-call-requests`, client
doc); admins have no call-management endpoints.

## 8. Console integration notes

- **Propose flow:** open request → `POST /requests/{id}/candidate-search`
  (tune `override_params` if the live prefs are too tight) →
  `POST /introductions` with the chosen pair → both requests go `ON_HOLD`
  until the decision window closes or both answer.
- **Resolution is user-driven:** decisions come from the client
  `decision` endpoint — admins monitor via `GET /introductions` and the
  `actionNeeded` counter, and can force-release a stuck pairing with
  `DELETE /introductions/{id}`.
- **Cooldown:** after a declined/expired introduction, a ~30-day
  re-introduction cooldown applies to the same pairing — the same pair will
  not surface in candidate search during the window.
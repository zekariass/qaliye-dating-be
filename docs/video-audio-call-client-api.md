# Audio & Video Calls

## 1. Call requests

User-initiated **audio or video** calls on an **active match** (any match
source — discovery or matchmaking). `call_type` (`VIDEO`/`AUDIO`) is fixed
at creation and drives billing, notifications, and history. There is at
most **one live request per match** at a time — regardless of `call_type`;
a new one can be created once the previous is terminal.

- **Requester pays** — the first requester `join` evaluates the
  `VIDEO_CALL` or `AUDIO_CALL` action cost per `call_type` (allowance →
  credits). The responder joins free.
- **Expiry** — a `PENDING` request auto-expires after ~48 h (server-side
  sweeper); the requester is notified.
- **Duration cap** — every call has a per-type max duration
  (`VIDEO_CALL_MAX_SECONDS` / `AUDIO_CALL_MAX_SECONDS`, default 1800 s).
  The deadline is anchored on the **first join of either party** and is
  shared: late joiners get only the remaining time. Agora drops both
  participants at the deadline — no client cooperation needed.
- **Killswitch** — if the match ends or either party blocks, live requests
  are cancelled; in-flight calls return `409`.

### `POST /api/v1/video-call-requests`

Creates (or idempotently returns) the live request for a match. Caller must
be a match participant; the other participant becomes the responder and
gets a `VIDEO_CALL_REQUESTED` or `AUDIO_CALL_REQUESTED` notification
(matching `call_type`).

**Request body:**

| Field | Type | Required |
|-------|------|----------|
| `match_id` | UUID string | **yes** — must be an `ACTIVE` match you belong to |
| `call_type` | string | no — `"VIDEO"` (default) or `"AUDIO"`; fixed at creation |

```json
{ "match_id": "c4e2d7a9-3b5f-4c8e-9a2d-6e7f8a9b0c1d", "call_type": "AUDIO" }
```

> **Idempotent:** if a live (`PENDING`/`ACCEPTED`) request already exists
> for the match, the existing row is returned — no duplicate is created.
> **The live request's `call_type` wins:** requesting `AUDIO` while a
> `VIDEO` request is pending returns the `VIDEO` request (and vice versa).
> Always render the returned `call_type`, not what you sent.

**Success:** `201 Created` → `VideoCallRequestView`

**Errors:**

| Status | Condition |
|--------|-----------|
| 400 | Missing `match_id` |
| 403 | Not a match participant |
| 404 | Match not found |
| 409 | Match not `ACTIVE`, or a block exists between the pair — `"Cannot request a call with this match."` |
| 422 | Unknown `call_type` |

### `GET /api/v1/matches/{matchId}/video-call-requests`

Request history for a match plus the live request (if any), newest first.
Caller must be a participant.

**Query params:** `page` (int, default `0`), `size` (int, default `20`)

**Success:** `200 OK` →

```json
{
  "requests": [ { "id": "…", "status": "PENDING", "…": "…" } ],
  "total": 3,
  "page": 0,
  "size": 20
}
```

**Errors:** `403` not a participant · `404` unknown match

### `POST /api/v1/video-call-requests/{id}/accept`

Responder accepts a `PENDING` request → `ACCEPTED`; the provider allocates
the channel (`channel_name`) and both sides are notified.

**Success:** `200 OK` → `VideoCallRequestView`
**Errors:** `403` not the responder · `404` unknown · `409` no longer `PENDING` / match or block killswitch

### `POST /api/v1/video-call-requests/{id}/decline`

Responder declines → `DECLINED`; the requester is notified.

**Success:** `200 OK` → `VideoCallRequestView` · **Errors:** same guard set as `accept`

### `POST /api/v1/video-call-requests/{id}/cancel`

Requester withdraws a `PENDING` request → `CANCELLED`; the responder is
notified.

**Success:** `200 OK` → `VideoCallRequestView` · **Errors:** `403` not the requester · `409` no longer `PENDING`

### `POST /api/v1/video-call-requests/{id}/remind`

Requester re-pings the responder while the request is still `PENDING` —
the "notify again" button. Re-emits the `*_CALL_REQUESTED` alert
(`VIDEO_CALL_*` or `AUDIO_CALL_*` per `call_type`) so the recipient's
incoming-call UI/ringtone re-fires. Server-side cooldown applies
(default ~2 min; `reminder_count`/`last_reminded_at` tracked per request).

**Success:** `200 OK` → `VideoCallRequestView` — `next_remind_in_seconds`
is set to the full cooldown again, so the client can re-arm its countdown
without waiting for the next `429`
**Errors:** `403` not the requester · `409` request no longer `PENDING` ·
`429` cooldown — `Retry-After` header + `error.details.retry_after_seconds`

### `POST /api/v1/video-call-requests/{id}/join`

Mints join credentials for an `ACCEPTED` request — call when the call is
due. Credentials are generated on demand by the RTC provider (Agora) and are
never stored; fetch a fresh session on every join. For `AUDIO` requests join
the same channel but publish audio only — tokens are media-agnostic.

**Token renewal uses this same endpoint.** Agora fires
`onTokenPrivilegeWillExpire` ~30 s before expiry — re-call `join` for a
fresh token. Before the deadline the renewal succeeds; at/after the
deadline it fails `409 CALL_DURATION_EXCEEDED` — that failure IS the
end-of-call signal.

**First requester join charges** the `VIDEO_CALL` or `AUDIO_CALL` action
(selected by the request's `call_type` — each is priced and ledgered
separately). Charged exactly once per request — retried joins are free;
the responder never pays.

**Call deadline:** the first successful join anchors
`call_deadline_at = now + cap`. Every token's privilege expiry is
`min(call_deadline_at, now + max_token_ttl)`, so both participants are
dropped at the same instant. `expires_at` in the response is that exact
instant — use it as both token expiry and the countdown target.

**Success:** `200 OK` →

```json
{
  "channel_name": "vcr_c4e2d7a9-3b5f-4c8e-9a2d-6e7f8a9b0c1d",
  "token": "<provider-issued-rtc-token>",
  "uid": 3298450112,
  "expires_at": "2026-10-05T19:00:00+00:00"
}
```

**Errors:**

| Status | `error.code` | Condition |
|--------|--------------|-----------|
| 402 | `insufficient_credits` | First requester join with no allowance/credits |
| 403 | `FORBIDDEN` | Not a participant |
| 404 | `NOT_FOUND` | Unknown request |
| 409 | `CONFLICT` | Request not `ACCEPTED`, or killswitch fired |
| 409 | `CALL_DURATION_EXCEEDED` | `now > call_deadline_at` — the call is over; the request is transitioned to `COMPLETED` with `ended_at` set |
| 429 | `RATE_LIMITED` | Video-call action limit |

### `POST /api/v1/video-call-requests/{id}/end`

Either participant ends an `ACCEPTED` call → `COMPLETED`. Idempotent —
repeated calls return the current state.

**Success:** `200 OK` → `VideoCallRequestView` · **Errors:** `403` not a
participant · `404` unknown · `409` killswitch (match ended / block)

### `VideoCallRequestView` object

Caller-relative view — `is_requester` orients the role and the `can_*`
flags tell the client which actions are currently valid.

| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | The request id used by all lifecycle endpoints |
| `match_id` | UUID | |
| `status` | string | `PENDING`, `ACCEPTED`, `DECLINED`, `CANCELLED`, `EXPIRED`, `COMPLETED` |
| `call_type` | string | `VIDEO` or `AUDIO` — join audio-only when `AUDIO` |
| `is_requester` | boolean | `true` when the caller created the request |
| `request_expires_at` | ISO-8601 | `PENDING` auto-expiry (~48 h from creation) |
| `responded_at` | ISO-8601 | Accept/decline timestamp |
| `can_cancel` | boolean | `PENDING` && caller is requester |
| `can_accept` | boolean | `PENDING` && caller is responder |
| `can_join` | boolean | `ACCEPTED` (either participant) |
| `requester_joined`, `responder_joined` | boolean | Join stamps — render "other party is in" UI |
| `call_deadline_at` | ISO-8601 | Absolute instant Agora drops both participants — `null` until the first join anchors it. Drive the in-call countdown from this |
| `ended_at` | ISO-8601 | |
| `created_at` | ISO-8601 | |
| `next_remind_in_seconds` | integer | `null` when remind isn't possible (not `PENDING`, or caller is responder); `0` = can remind now; `>0` = seconds until the cooldown lifts — drive the remind-button countdown from this |

**Example `VideoCallRequestView` response:**

```json
{
  "id": "f1a2b3c4-5d6e-7f8a-9b0c-1d2e3f4a5b6c",
  "match_id": "c4e2d7a9-3b5f-4c8e-9a2d-6e7f8a9b0c1d",
  "status": "ACCEPTED",
  "call_type": "VIDEO",
  "is_requester": true,
  "request_expires_at": "2026-10-05T20:00:00+00:00",
  "responded_at": "2026-10-03T21:12:00+00:00",
  "can_cancel": false,
  "can_accept": false,
  "can_join": true,
  "requester_joined": false,
  "responder_joined": true,
  "call_deadline_at": "2026-10-03T20:30:00+00:00",
  "ended_at": null,
  "created_at": "2026-10-03T20:00:00+00:00",
  "next_remind_in_seconds": null
}
```

> There is **no "get request by id" GET endpoint** — the request id arrives
> via the `*_CALL_*` notifications; fetch current state through
> `GET /matches/{matchId}/video-call-requests` or act directly on the id.

---

## 2. Notifications

Lifecycle events arrive as in-app/push notifications, grouped by feature.

**Matchmaking events (Part I)** — full payload-field table in
`matchmaking-client-api.md` §5

| Alert code | Meaning | Who gets it |
|------------|---------|-------------|
| `MATCHMAKING_REQUEST_CREATED` | Request created (charge receipt) | Requester |
| `MATCHMAKING_REQUEST_CANCELLED` | Request cancelled by user or admin | Requester |
| `MATCHMAKING_REQUEST_EXPIRED` | Request lifetime elapsed | Requester |
| `MATCHMAKING_INTRODUCTION_PROPOSED` | A matchmaker proposed a pairing | Both participants |
| `MATCHMAKING_INTRODUCTION_DECLINED` | An introduction was declined | Both participants |
| `MATCHMAKING_INTRODUCTION_CANCELLED` | Introduction cancelled by admin / request-cancel cascade | Both participants |
| `MATCHMAKING_INTRODUCTION_EXPIRED` | Decision window elapsed | Both participants |
| `MATCHMAKING_MATCHED` | Mutual `INTERESTED` → match created | Both participants |

**Call events (Part II) — code prefix carries the call type**

| Alert code | Meaning | Who gets it |
|------------|---------|-------------|
| `VIDEO_CALL_REQUESTED` | Incoming video call request on your match | Responder |
| `VIDEO_CALL_ACCEPTED` | Your request was accepted | Requester |
| `VIDEO_CALL_DECLINED` | Your request was declined | Requester |
| `VIDEO_CALL_CANCELLED` | Request withdrawn / match ended | The other party |
| `VIDEO_CALL_EXPIRED` | Pending request timed out | Requester |
| `VIDEO_CALL_ENDED_TIME_LIMIT` | Call hit the duration cap — render "time limit reached", not a generic ended screen | **Both** parties |
| `AUDIO_CALL_*` | Same events for `call_type=AUDIO` requests (`AUDIO_CALL_REQUESTED`, `AUDIO_CALL_ACCEPTED`, `AUDIO_CALL_DECLINED`, `AUDIO_CALL_CANCELLED`, `AUDIO_CALL_EXPIRED`, `AUDIO_CALL_ENDED_TIME_LIMIT`) | Same recipients |

Every `*_CALL_*` code tells the client the call type without a fetch —
render the incoming-call screen as audio or video directly from the code
(the `call_type` field is also on `VideoCallRequestView` and the inbox
summary for confirmation). The same `*_REQUESTED` code re-fires on
`POST /{id}/remind` — treat repeat deliveries as a re-ring of the existing
request, not a new one.

Notification `data` payloads arrive under the shared `ACCOUNT_ALERT`
envelope — `notification_type` is always `"ACCOUNT_ALERT"` and the event
identity is `alert_code`. Call alerts carry `video_call_request_id`;
matchmaking alerts carry `request_id` / `introduction_id` / `match_id`
per `matchmaking-client-api.md` §5. All values are strings — deep-link
directly, no fetch needed.

---

## 3. Client integration notes

**Matchmaking (Part I)**

- **Retries:** always reuse the same `charge_idempotency_key` when retrying
  `POST /requests` — a duplicate key returns the original request with `201`,
  never a double charge.
- **Request → introduction discovery:** the `MATCHMAKING_INTRODUCTION_PROPOSED`
  push carries `introduction_id` + `request_id`, and
  `GET /matchmaking/introductions` lists the full history — fetch
  `GET /matchmaking/introductions/{id}` for the decision screen. A live
  proposal is also visible as `active_introduction_id` on
  `GET /matchmaking/requests/active`.
- **Decisions are one-shot.** UI should confirm before submitting (`409` on
  a second attempt).
- **404 on `requests/active` is the "no active request" state**, not an
  error to surface to the user.
- **Request completes on its own:** after the configured number of
  introductions resolves (default 1), the request moves to `COMPLETED`
  rather than reopening — the user must create a new request to re-enter
  the queue.

**Audio & video calls (Part II)**

- **Call flow:** create → wait for `*_CALL_ACCEPTED` → `join` → `end`.
  Poll `GET /matches/{matchId}/video-call-requests` for state changes;
  the `can_*` flags drive which buttons to show.
- **Time limit:** run the in-call countdown against `call_deadline_at`
  (on the view) or `expires_at` (from `join` — same instant). At the
  deadline Agora drops both users server-side; expect a
  `*_CALL_ENDED_TIME_LIMIT` push and/or `409 CALL_DURATION_EXCEEDED` on
  the next `join` renewal — show "time limit reached" for both.
- **`call_type` is everywhere:** create body (optional, default `VIDEO`),
  `VideoCallRequestView`, inbox `video_call_request` summary, and the
  `*_CALL_*` notification prefix. For `AUDIO`, join with audio-only — the
  Agora channel/token are identical.
- **Ringing:** `*_CALL_REQUESTED` is the ring trigger (foreground: loop a
  ringtone; background: CallKit on iOS / ConnectionService on Android).
  Stop ringing on `*_ACCEPTED`/`_DECLINED`/`_CANCELLED`/`_EXPIRED` or
  local timeout. `POST /{id}/remind` re-fires it (cooldown, `429` —
  `Retry-After` header + `error.details.retry_after_seconds` carry the
  seconds to wait, `error.code` = `RATE_LIMITED`; disable the remind
  button until then).
- **Type mismatch is silent:** a live request of the other type is
  returned idempotently — render `call_type` from the response, and
  disable the other call-type button while a request is live.
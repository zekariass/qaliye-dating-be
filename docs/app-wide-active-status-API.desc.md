# App-Wide Activity Status — Frontend Integration

## Overview

Every user has an **activity status** computed server-side from `last_active_at` and
`show_activity_status`. The status is a privacy-safe label; the raw timestamp is never
exposed to clients.

### Status values

| Value | Meaning |
|---|---|
| `ONLINE` | Active within the last 180 seconds |
| `RECENTLY_ACTIVE` | Active more than 180 s ago but within the last 15 minutes (900 s) |
| `OFFLINE` | Last active more than 15 minutes ago |
| `HIDDEN` | User has disabled activity visibility — always overrides all time-derived values |

---

## Configuration thresholds (server defaults)

| Setting | Default | Env var |
|---|---|---|
| Online window | 180 s | `ACTIVITY_STATUS_ONLINE_WINDOW_SECONDS` |
| Recently-active window | 900 s | `ACTIVITY_STATUS_RECENTLY_ACTIVE_WINDOW_SECONDS` |
| Heartbeat write min interval | 60 s | `ACTIVITY_HEARTBEAT_WRITE_MIN_INTERVAL_SECONDS` |

---

## Endpoints

### 1 · Heartbeat — `POST /api/v1/activity/heartbeat`

Tells the server the authenticated user is currently active.

**Authentication:** Bearer JWT required.
**Request body:** none.
**Throttle:** The server only writes to `last_active_at` if the previous write was more than
60 seconds ago. Calls within the throttle window still return HTTP 200 without updating the
database.

#### Response — `200 OK`

```json
{
  "activity_status": "ONLINE",
  "show_activity_status": true
}
```

| Field | Type | Description |
|---|---|---|
| `activity_status` | `string` | Canonical status resolved from the caller's own `last_active_at` and `show_activity_status`. If the caller has disabled visibility this will be `"HIDDEN"`. |
| `show_activity_status` | `boolean` | The caller's current visibility setting. |

#### Mobile heartbeat contract

**Send a heartbeat:**
- immediately after login or session restoration;
- immediately after the app enters the foreground;
- every **90 seconds** while authenticated, foregrounded, and `AppState` is `"active"`.

**Stop sending heartbeats:**
- when the app moves to the background;
- when the app becomes inactive (`AppState !== "active"`);
- when the user signs out;
- when no authenticated session exists.

Do **not** send an explicit "go offline" request. The server infers OFFLINE from the
absence of recent heartbeats.

---

### 2 · Update visibility — `PATCH /api/v1/users/me/activity-visibility`

Lets the authenticated user show or hide their activity status from others.

**Authentication:** Bearer JWT required.

#### Request body

```json
{
  "show_activity_status": false
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `show_activity_status` | `boolean` | yes | `true` = visible to others; `false` = always shown as `HIDDEN`. |

#### Response — `200 OK`

```json
{
  "show_activity_status": false,
  "activity_status": "HIDDEN"
}
```

| Field | Type | Description |
|---|---|---|
| `show_activity_status` | `boolean` | The persisted value after the update. |
| `activity_status` | `string` | The caller's own status as seen by others after the change. |

---

### 3 · Batch status refresh — `POST /api/v1/activity/statuses`

Returns the current activity status for a list of user IDs.
Used to refresh stale statuses on list and detail screens without re-fetching full profiles.

**Authentication:** Bearer JWT required.

#### Request body

```json
{
  "user_ids": ["uuid-1", "uuid-2", "uuid-3"]
}
```

| Field | Type | Required | Constraints |
|---|---|---|---|
| `user_ids` | `uuid[]` | yes | 1–50 IDs per request. |

#### Response — `200 OK`

```json
{
  "items": [
    { "user_id": "uuid-1", "activity_status": "ONLINE" },
    { "user_id": "uuid-2", "activity_status": "OFFLINE" }
  ]
}
```

| Field | Type | Description |
|---|---|---|
| `items` | `array` | One entry per authorized target. |
| `items[].user_id` | `uuid` | The target user's ID. |
| `items[].activity_status` | `string` | Current activity status. |

#### Omission rules (privacy)

The server **silently omits** a requested ID when any of the following is true:

- the target does not exist;
- the target's account is not active or is deleted;
- a block exists in either direction between the caller and the target;
- the caller has no relationship with the target (no active match, no mutual like/superlike,
  and the target's profile is not publicly visible).

The response never reveals *why* a user was omitted. The frontend must treat a missing ID as
"status unavailable" and display nothing or a neutral placeholder.

---

## Where `activity_status` appears in other endpoints

Every endpoint that returns another user's profile includes `activity_status` as part of the
initial response payload. This is the **snapshot at the time the screen loads**.

| Endpoint | Field location |
|---|---|
| `GET /api/v1/discovery` | `items[].activity_status` |
| `GET /api/v1/likes` | `items[].activity_status` |
| `GET /api/v1/matches` | `items[].activity_status` |
| `GET /api/v1/chat/inbox` | `items[].participant.activity_status` |
| `GET /api/v1/chat/matches/{matchId}` | `participant.activity_status` |
| `GET /api/v1/chat/matches/{matchId}/messages` | `participant_activity_status` (top-level snapshot only) |
| `GET /api/v1/users/{userId}/profile` | `activity_status` |

---

## Frontend refresh contracts

### Discovery, Matches, Likes, Inbox

1. The initial API response includes `activity_status` for every returned profile.
2. While the screen is focused and the app is foregrounded, refresh the statuses of
   **currently visible** user IDs every **90 seconds** using a single
   `POST /api/v1/activity/statuses` request.
3. Do **not** make one request per profile.
4. Stop the refresh timer when the screen loses focus or the app goes to the background.

### Other-user profile screen

1. The initial `GET /api/v1/users/{userId}/profile` response includes `activity_status`.
2. While the profile screen is focused and the app is foregrounded, refresh the displayed
   user's status every **90 seconds** using `POST /api/v1/activity/statuses` with a single
   `user_ids` entry.
3. Stop the timer when the screen loses focus or the app backgrounds.

### Open chat screen

Do **not** use `POST /api/v1/activity/statuses` for an open chat.
Use `GET /api/v1/chat/matches/{matchId}` instead.

1. When a chat opens, fetch chat metadata and display `participant.activity_status` in the
   chat header.
2. While the chat is focused and the app is foregrounded, refresh
   `GET /api/v1/chat/matches/{matchId}` every **90 seconds** and update the header from
   `participant.activity_status`.
3. Trigger an immediate refresh after:
   - app foreground;
   - network recovery;
   - Realtime reconnection;
   - chat screen regains focus.
4. Stop polling when the chat loses focus, the app backgrounds, the user signs out, or the
   match becomes unavailable.

The field `participant_activity_status` in
`GET /api/v1/chat/matches/{matchId}/messages` is an **initial snapshot only** and is not
refreshed by the polling loop. Do not add activity status to individual message objects.

---

## Signal meanings — do not conflate

| Signal | Meaning |
|---|---|
| `activity_status` | App-wide presence for profiles, discovery, likes, matches, inbox, and chat header. |
| `match:<matchId>:presence` | Chat-specific "viewing this conversation" state only. Absence does **not** mean OFFLINE. |
| `typing` | Typing state only. |
| Read receipts | Whether the other participant has read a message. |
| Delivery receipts | Whether a message has been delivered. |

---

## Error responses

All endpoints follow the standard RFC 7807 error format.

| HTTP status | `status` code | When |
|---|---|---|
| `400 Bad Request` | — | Validation failure (e.g. `user_ids` empty or > 50, missing `show_activity_status`). |
| `401 Unauthorized` | — | Missing or invalid Bearer token. |
| `403 Forbidden` | `ACCOUNT_NOT_ACTIVE` | The caller's account is not active or has been deleted. |

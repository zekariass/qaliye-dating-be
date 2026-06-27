# Chat Feature — Backend

## Overview

One active match = one chat thread. Messages are linked directly to a `match_id` — there is no separate conversations table. Messaging stops immediately when a match ends for any reason (`USER_UNMATCH`, `CANCELLED_BY_REWIND`, `BLOCKED`, `ADMIN_ACTION`).

---

## Migrations

| Version | File | Purpose |
|---------|------|---------|
| V7 | `V7__add_chat_sequences_outbox_and_notification_settings.sql` | Per-match message sequences, receipt cursors, transactional outbox, notification settings |
| V8 | `V8__configure_chat_private_realtime_broadcast.sql` | Removes direct client DB reads, configures Realtime private Broadcast/Presence RLS policies |

**Never edit or re-run V6.** Run migrations in order: V7 → V8.

### Backfill behaviour

V7 assigns deterministic `sequence_number` values to all historical messages using `ROW_NUMBER() OVER (PARTITION BY match_id ORDER BY created_at ASC, id ASC)`. All historical messages are treated as delivered and read (`user_one/two_last_delivered/read_sequence` set to `MAX(sequence_number)` for each match). Matches with no messages remain at `next_message_sequence = 1` and cursor `0`.

---

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `SUPABASE_URL` | ✅ | Supabase project URL |
| `SUPABASE_SERVICE_ROLE_KEY` | ✅ | Service-role key — **never expose to mobile clients** |
| `SUPABASE_REALTIME_BROADCAST_URL` | optional | Defaults to `${SUPABASE_URL}/realtime/v1/api/broadcast` |
| `CHAT_CURSOR_HMAC_SECRET` | ✅ | 32+ byte secret for signing pagination cursors |
| `CHAT_OUTBOX_BATCH_SIZE` | optional | Rows claimed per outbox poll cycle (default: 100) |
| `CHAT_OUTBOX_POLL_INTERVAL` | optional | Polling interval ms (default: 500) |
| `CHAT_OUTBOX_LEASE_SECONDS` | optional | Worker lease duration (default: 60) |
| `CHAT_OUTBOX_MAX_ATTEMPTS` | optional | Max delivery attempts before FAILED (default: 20) |
| `CHAT_OUTBOX_MAX_BACKOFF_SECONDS` | optional | Maximum retry backoff (default: 300) |
| `CHAT_RATE_LIMIT_ENABLED` | optional | Enable/disable rate limiting (default: `true`) |
| `CHAT_RATE_LIMIT_USER_PER_MINUTE` | optional | Max message sends per user per minute (default: 30) |
| `CHAT_RATE_LIMIT_MATCH_PER_MINUTE` | optional | Max message sends per user per match per minute (default: 12) |
| `CHAT_RATE_LIMIT_CACHE_MAX_SIZE` | optional | Max entries in the in-memory rate limit cache (default: 100000) |
| `CHAT_RATE_LIMIT_CACHE_EXPIRE_MINUTES` | optional | Cache entry TTL in minutes (default: 5) |
| `CHAT_RATE_LIMIT_WINDOW_MILLIS` | optional | Fixed-window duration in milliseconds (default: 60000) |

---

## REST API

Base path: `/api/v1/chat`  
All endpoints require a valid Supabase Bearer JWT.

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/matches` | Paginated inbox (cursor-based) |
| `GET` | `/matches/{matchId}` | Chat metadata + receipt state |
| `GET` | `/matches/{matchId}/messages` | Message history (sequence-keyed pagination) |
| `POST` | `/matches/{matchId}/messages` | Send a message (idempotent) |
| `POST` | `/matches/{matchId}/receipts/delivered` | Mark messages delivered up to sequence |
| `POST` | `/matches/{matchId}/receipts/read` | Mark messages read up to sequence |
| `PATCH` | `/matches/{matchId}/notification-settings` | Update mute settings |

### Inbox — `GET /api/v1/chat/matches`

**Query parameters:**

| Parameter | Default | Notes |
|-----------|---------|-------|
| `filter` | `ALL` | `ALL` or `UNREAD` |
| `cursor` | — | Opaque HMAC-signed cursor from previous response |
| `limit` | `25` | 1–50 |

**Response:**
```json
{
  "items": [{ "match_id": "...", "status": "ACTIVE", "participant": { ... }, "last_message": { ... }, "unread_count": 2, "muted_until": null, "matched_at": "...", "last_message_at": "..." }],
  "next_cursor": "<opaque>"
}
```

Cursor encodes: `filter`, `snapshot_at`, `last_message_at`, `matched_at`, `match_id`. HMAC-SHA256 signed with `CHAT_CURSOR_HMAC_SECRET`. The filter value is embedded and verified — a cursor from an `ALL` query is rejected on an `UNREAD` query.

### Send Message — `POST /api/v1/chat/matches/{matchId}/messages`

```json
{ "client_message_id": "<uuid>", "message_type": "TEXT", "body": "Hello!" }
```

- `message_type`: `TEXT`, `ICEBREAKER`, `PROMPT_REPLY` only.
- Returns **201** for a new message, **200** for an idempotent retry with identical content.
- Returns **409 IDEMPOTENCY_CONFLICT** if the same `client_message_id` was sent with different content.
- Body: max 2 000 Unicode code points.

### Receipts

```json
{ "up_to_sequence": 42 }
```

- Receipts are **monotonic cursors**. A lower sequence is silently ignored (no error).
- `markRead` also advances `delivered` if the read sequence is ahead of it.

### Mute Settings

```json
{ "muted_until": "2025-12-31T23:59:59Z" }
```

Pass `null` for `muted_until` to unmute.

---

## Authorization Rules

Every protected endpoint checks, in order:

1. Valid JWT with a `sub` claim.
2. Caller's account `status = 'ACTIVE'`.
3. Caller is a participant of the match.
4. For write operations: match `status = 'ACTIVE'`.
5. For write operations: no active block between participants.

Error codes returned as RFC 7807 problem details:

| Code | HTTP | Meaning |
|------|------|---------|
| `UNAUTHORIZED` | 401 | Missing or invalid JWT |
| `ACCOUNT_NOT_ACTIVE` | 403 | Account suspended or deactivated |
| `MATCH_NOT_FOUND` | 404 | Match does not exist |
| `MATCH_ACCESS_DENIED` | 403 | Caller is not a participant |
| `MATCH_NOT_ACTIVE` | 409 | Match has ended |
| `USER_BLOCKED` | 403 | Active block between participants |
| `INVALID_CURSOR` | 400 | Cursor is invalid or tampered |
| `INVALID_RECEIPT_SEQUENCE` | 422 | Sequence out of valid range |
| `INVALID_MESSAGE` | 422 | Body/type validation failed |
| `IDEMPOTENCY_CONFLICT` | 409 | Same clientMessageId, different content |
| `RATE_LIMITED` | 429 | Rate limit exceeded |

---

## Idempotency

`POST /matches/{matchId}/messages` is idempotent on `(sender_user_id, client_message_id)`:

- **Pre-lock check**: query for existing message before acquiring row lock.
- **Post-lock re-check**: after `SELECT FOR UPDATE` on the match, check again.
- If found with identical `(match_id, message_type, body)` → return existing message with HTTP 200.
- If found with different content → return 409 `IDEMPOTENCY_CONFLICT`.
- The database has a `UNIQUE(sender_user_id, client_message_id)` constraint as final defense.

---

## Transactional Outbox

All durable Realtime events are published via the `chat_outbox_events` table. Events are **never published before the database transaction commits**.

### Event types

| Event | Topic | Description |
|-------|-------|-------------|
| `chat.message.created` | `match:{id}:events` | New message sent |
| `chat.receipt.updated` | `match:{id}:events` | Delivery/read receipt advanced |
| `chat.match.ended` | `match:{id}:events` | Match lifecycle ended |
| `inbox.match.updated` | `user:{id}:inbox` | Inbox item changed (per user) |
| `inbox.match.removed` | `user:{id}:inbox` | Match removed from inbox (per user) |

### Worker (`ChatOutboxPublisher`)

- `@Scheduled(fixedDelay)` polls every `CHAT_OUTBOX_POLL_INTERVAL` ms.
- Claims rows using `SELECT ... FOR UPDATE SKIP LOCKED` — safe for multiple workers.
- Sets `status = PROCESSING` with `locked_by` (hostname + PID) and `lease_expires_at`.
- On success → `status = PUBLISHED`.
- On failure → exponential back-off with jitter, requeued to `PENDING` with `available_at` delay.
- After `CHAT_OUTBOX_MAX_ATTEMPTS` → `status = FAILED`.
- Expired leases (`status = PROCESSING` with past `lease_expires_at`) are reclaimed on each poll cycle.

---

## Supabase Realtime Setup

### Channel topics

| Topic | Usage |
|-------|-------|
| `match:{matchId}:events` | Durable chat events (message created, receipt updated, match ended) — server-only publish |
| `match:{matchId}:typing` | Ephemeral typing indicators — client publish |
| `match:{matchId}:presence` | Online/offline presence — client publish |
| `user:{userId}:inbox` | Inbox update notifications — server-only publish |

### Security policies (V8)

- Clients can **subscribe** to `match:*:events`, `match:*:typing`, `match:*:presence` only if they are an active participant of an active, unblocked match.
- Clients can **subscribe** to `user:{userId}:inbox` only for their own user ID.
- Clients can **publish** only to `match:*:typing` and `match:*:presence` (ephemeral only).
- The server publishes durable events using the `SUPABASE_SERVICE_ROLE_KEY` — this key is **never** exposed to mobile clients.
- Direct authenticated reads from `public.messages` are **revoked** in V8. Spring Boot is the sole read/write path.

---

## Match Lifecycle

All match-ending operations go through `MatchLifecycleService.endMatch()`:

1. `SELECT ... FOR UPDATE` on the match row.
2. Verify status is `ACTIVE`.
3. `UPDATE matches SET status = 'ENDED' ...`
4. Create `chat.match.ended` outbox event.
5. Create `inbox.match.removed` outbox events for both participants.
6. Commit (all in the same transaction as the caller).

Flows using `MatchLifecycleService`:

| Flow | End reason |
|------|-----------|
| Unmatch (`DELETE /api/v1/matches/{id}`) | `USER_UNMATCH` |
| Block (`POST /api/v1/safety/block`) | `BLOCKED` |
| Rewind cancellation | `CANCELLED_BY_REWIND` |

The database trigger `end_active_matches_when_blocked` remains as defense-in-depth but should not be the primary mechanism.

---

## Rate Limiting

Rate limits are enforced by `CaffeineChatRateLimitService`, a fixed-window limiter backed by an in-process
[Caffeine](https://github.com/ben-manes/caffeine) cache.

Two limits are checked per send (after auth & authorization succeed):

| Scope | Default | Property |
|-------|---------|----------|
| Per user (all matches) | 30 / min | `CHAT_RATE_LIMIT_USER_PER_MINUTE` |
| Per user per match | 12 / min | `CHAT_RATE_LIMIT_MATCH_PER_MINUTE` |

Cache keys: `chat:send:user:<userId>` and `chat:send:match:<matchId>:user:<userId>`.

When a limit is exceeded the service throws HTTP **429** with error code `RATE_LIMITED` and a `Retry-After` header
containing the seconds remaining in the current window.

**Idempotent retries** that resolve to an already-persisted message bypass the rate limiter entirely
(`MessageCommandService` returns before calling `checkSendMessage`).

### Single-instance caveat

> Caffeine rate limits are **local to one Spring Boot instance** and **reset on restart**.
> They are appropriate for the current single-instance MVP deployment.
> Before horizontally scaling Spring Boot, replace `CaffeineChatRateLimitService` with
> a shared implementation such as Redis Lua-script rate limiting or a PostgreSQL advisory-lock
> approach. The `ChatRateLimitService` interface keeps this swap transparent to all callers.

---

## Client Deduplication

Clients must generate a `client_message_id` (UUID v4) per message attempt and persist it locally. On network retry, resubmit the same `client_message_id`. The server returns HTTP 200 (not 201) for retries that match the original content, so clients can detect deduplication by status code.

---

## Privacy

- `SUPABASE_SERVICE_ROLE_KEY` is never sent to mobile clients.
- Direct SQL reads from `public.messages` by `anon` or `authenticated` roles are revoked in V8.
- Inbox topics (`user:{userId}:inbox`) are scoped to the authenticated user's own ID.
- Match channel access is re-checked on every WebSocket join using the `chat_realtime_is_active_match_member` function with `SECURITY DEFINER`.

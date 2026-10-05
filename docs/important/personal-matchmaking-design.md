**# Personal Matchmaking — Design

This document defines the Personal Matchmaking system in its current
(redesigned) form. It **merges and supersedes** both
`personal-match-making-design.md` and the implementation-requirements
prompt `personal-matchmaking-redesign.md`.

Personal Matchmaking is a **manual, admin-driven** matching flow:

```text
User submits a matchmaking request
    → admin (matchmaker) searches compatible candidates
    → admin creates an introduction
    → both users independently decide INTERESTED / NOT_INTERESTED
    → mutual interest creates a normal match
    → messaging and audio/video calls reuse the existing
      conversation feature
```

The backend does **not** choose the match automatically. It only:
stores requests/preferences, runs scored candidate search for the
admin, creates introductions, enforces state transitions and quotas,
and creates the final match on mutual consent. The final pairing
decision is always made by the admin; user consent is enforced before
any match is created.

---

# 1. Responsibility Model — Client vs Admin vs Backend

## 1.1 Client (end user) — what the user does

```text
1. Maintain matchmaking preferences (PUT /preferences).
2. Submit ONE matchmaking request at a time (paid, idempotent).
3. See their own request status and history.
4. Receive an introduction proposal (notification), review the
   partner's profile/preferences/score, then decide
   INTERESTED or NOT_INTERESTED — one-time, irreversible.
5. After a match: use normal chat; request / accept / decline /
   join / end AUDIO or VIDEO calls on the match; nudge a pending
   call request (remind).
```

**The client never:** searches candidates, sees a candidate list,
contacts the admin through any matchmaking channel, arranges or
schedules calls, submits post-call outcomes, or sees the other
side's introduction decision before deciding.

## 1.2 Admin (matchmaker) — what the admin does

```text
1. Open the request queue (filterable by status, paged).
2. Open one request → requester profile + live preferences + notes.
3. Run candidate search, optionally with per-search filter
   overrides (never mutating the user's saved prefs).
4. Review ranked results: profile card, candidate's preferences,
   forward/reverse/overall %, per-dimension "why" details,
   free-text user/matchmaker notes.
5. Select exactly one candidate and create the introduction
   (with an editable "why this introduction" reason).
6. Cancel requests / introductions; write matchmaker notes;
   monitor the dashboard funnel.
```

**The admin never:** picks candidates automatically (backend only
ranks), creates a match directly (mutual consent does that),
mediates any call, or messages users in-platform.

## 1.3 Backend — what the backend does

```text
- Stores typed matchmaking preferences + requests (no JSONB prefs,
  no snapshots — live prefs are always evaluated).
- Charges PERSONAL_MATCHMAKING_REQUEST atomically with request
  insert; request fee is non-refundable.
- Runs candidate search: hard SQL exclusions + overrides, then
  bidirectional compatibility scoring and ranking.
- Enforces introduction quota, state transitions, decision window.
- Creates the matches row in the same transaction as the second
  INTERESTED (match_source = MATCHMAKING).
- Manages video_call_requests on ACTIVE matches; charges
  VIDEO_CALL / AUDIO_CALL to the requester on first join only.
- Mints Agora join credentials on demand (never stores tokens;
  App Certificate never leaves the server).
- Expires stale entities via Quartz workers + lazy finalization.
```

**The backend never** auto-selects a candidate, auto-creates an
introduction or match, exposes contact details between users, or
interprets free-text notes as matching criteria.

---

# 2. What Changed vs the Previous Design

Removed concepts (schema + code):

```text
REMOVED  matchmaking_messages — per-request admin↔user threads,
         seq column, read cursors, polling endpoints.
REMOVED  matchmaking_video_calls + mediated video lifecycle
         (ARRANGING/SCHEDULED/IN_PROGRESS, contact collection,
         post-call outcomes, escrow, video sweepers).
REMOVED  preference snapshots — requests evaluate the user's
         CURRENT matchmaking_preferences row.
REMOVED  effective_filter_params / admin_override_params /
         search_source on introductions — replaced by persisted
         scores + match_details.
REMOVED  MATCHMAKING_MESSAGE_* / MATCHMAKING_VIDEO_* alerts,
         MUTUAL_INTEREST status, PERSONAL_MATCHMAKING_VIDEO_ESCROW,
         MATCHMAKING_ESCROW_REFUND.
```

New concepts:

```text
NEW  Alignment-dim preference columns: marriage_timeline,
     long_distance_relationship, family_involvement,
     religion_important, willing_to_relocate (§3).
NEW  Bidirectional compatibility scoring + persisted details
     and reason on introductions (§7–§8).
NEW  Introduction quota (matchmaking.introductions-per-request).
NEW  video_call_requests — general match-scoped call capability
     for ALL match sources, now with call_type VIDEO/AUDIO and
     a requester "remind" nudge (§10).
```

---

# 3. matchmaking_preferences

One row per user — "what I'm looking for" (directional dims) plus
matchmaking self-attributes (alignment dims). **Typed columns only,
no JSONB.** Upsert semantics, `version` increments, `gender` is
profile-derived (sought partner gender = opposite of own, heterosexual
rule) and never user-supplied.

```sql
CREATE TABLE public.matchmaking_preferences (
    id            UUID DEFAULT gen_random_uuid() NOT NULL,
    user_id       UUID NOT NULL,
    version       INTEGER NOT NULL DEFAULT 1,
    gender        VARCHAR(20) NULL,          -- derived, never client-set

    /* directional dims */
    min_age INTEGER NULL, max_age INTEGER NULL,
    min_height_cm INTEGER NULL, max_height_cm INTEGER NULL,
    specific_country_codes  TEXT[] NOT NULL DEFAULT '{}',
    has_children_preference   VARCHAR(20) NOT NULL DEFAULT 'any',
    wants_children_preference VARCHAR(30) NOT NULL DEFAULT 'any',
    religion_preferences  TEXT[] NOT NULL DEFAULT '{}',
    education_levels      TEXT[] NOT NULL DEFAULT '{}',
    marital_statuses      TEXT[] NOT NULL DEFAULT '{}',
    smoking_preferences   TEXT[] NOT NULL DEFAULT '{}',
    drinking_preferences  TEXT[] NOT NULL DEFAULT '{}',
    language_preference_ids  UUID[] NOT NULL DEFAULT '{}',
    ethnicity_preference_ids UUID[] NOT NULL DEFAULT '{}',

    /* alignment dims — NULL = skipped = inactive for scoring */
    marriage_timeline          VARCHAR(30) NULL
        CHECK (marriage_timeline IN
            ('NOW','ONE_TO_TWO_YEARS','THREE_TO_FIVE_YEARS','NOT_SURE')),
    long_distance_relationship VARCHAR(5) NULL CHECK (... IN ('YES','NO')),
    family_involvement         VARCHAR(5) NULL CHECK (... IN ('YES','NO')),
    religion_important         VARCHAR(5) NULL CHECK (... IN ('YES','NO')),
    willing_to_relocate        VARCHAR(5) NULL CHECK (... IN ('YES','NO')),

    user_notes       TEXT NULL,   -- for the matchmaker, never machine-read
    matchmaker_notes TEXT NULL,   -- admin-only, preserved across user PUTs

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (user_id),
    FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
);
```

Value domains: has/wants-children lowercase
(`any|yes|no|not_sure|open_to_discussion`); marital/smoking/drinking
UPPERCASE catalog values; arrays empty = "any". `ANY`/NULL/empty =
dimension inactive.

## Preference semantics

**Directional dims** — requester's prefs tested against candidate
profile facts AND vice versa (bidirectional):

```text
age / height ranges, specific_countries, has/wants_children,
religion, education, marital_status, smoking, drinking,
languages (overlap), ethnicities (overlap)
```

**Alignment dims** — evaluated symmetrically against BOTH users'
own preference rows; active only when BOTH answered:

```text
marriage_timeline        MATCH if A=B or either NOT_SURE
long_distance            MATCH if both YES, or same country_code;
                         NO vs YES + different country → NO MATCH
family_involvement /     MATCH if A=B (YES↔YES, NO↔NO)
religion_important /
willing_to_relocate
gender (hard exclusion)  candidate.gender <> requester.gender —
                         never scored
```

No weights, no HARD/SOFT config. Admin overrides (§7) act as hard
exclusions on top. Free-text notes are review material only — the
backend never derives matching rules from them.

---

# 4. matchmaking_requests

One paid request = one matchmaking engagement.

```text
Columns: id, user_id, matchmaking_preferences_id (FK NO ACTION —
RESTRICT would break cascade-deletion ordering), status,
request_charge_idempotency_key (UNIQUE — covers charge+insert),
contact_phone (optional, admin-only visibility), created_at,
completed_at, cancelled_at, expires_at, updated_at.
```

**One live request per user:**

```sql
CREATE UNIQUE INDEX uq_matchmaking_one_active_request_per_user
ON matchmaking_requests (user_id) WHERE status IN ('OPEN','ON_HOLD');
```

Statuses & transitions:

```text
OPEN    → ON_HOLD    introduction created
ON_HOLD → OPEN       intro ended, quota remains (guarded UPDATE)
ON_HOLD → MATCHED    intro produced a match
ON_HOLD → COMPLETED  intro ended, quota exhausted / organic match
OPEN    → COMPLETED  admin close
OPEN/ON_HOLD → CANCELLED  user or admin (cancels live intro,
                     partner released to OPEN — never consumes quota)
OPEN    → EXPIRED    sweeper past expires_at
```

`expires_at = created_at + matchmaking.request-lifetime-days` (90 d).
ON_HOLD is never swept; it waits for the introduction outcome, then
the OPEN expiry applies on release.

---

# 5. Monetization

```text
PERSONAL_MATCHMAKING_REQUEST — charged once, atomically with the
    request INSERT (one transaction; failure → no row). Same
    idempotency key retry → existing request, never double charge.
    Non-refundable.

VIDEO_CALL / AUDIO_CALL — separate actions charged to the REQUESTER
    on first successful join only (idempotent via
    requester_charge_key). Responder joins free. Non-refundable once
    consumed. Insufficient credit → 402 on join.

Nothing else is charged: no escrow, no per-introduction fee.
Goodwill refunds = existing manual credit-grant tooling.
```

After submission the client shows:
*"Your matchmaker is carefully reviewing your request."*

---

# 6. Admin request queue

```text
GET /api/v1/admin/matchmaking/requests?status=&page=&size=
    → paged envelope { requests, total, page, size }
GET /api/v1/admin/matchmaking/requests/{requestId}
    → request + requester profile card + live prefs +
      introduction history + notes
GET /api/v1/admin/matchmaking/stats
    → funnel counts { requests{status→n}, introductions{status→n},
      actionNeeded.awaitingDecision, totalMatchmakingMatches }
```

---

# 7. Admin candidate search

```text
POST /api/v1/admin/matchmaking/requests/{requestId}/candidate-search
body: { override_params: {...}, page, size }
```

`override_params` keys are validated against the known preference
key set (unknown → 422); supplied keys replace the requester's live
value **for this search only** — saved preferences are never mutated.

**Eligibility — hard SQL exclusions:**

```text
1. request.status = 'OPEN'  (and only OPEN requests are candidates)
2. candidate ≠ requester
3. gender opposite (hetero rule)
4. candidate account active (not deactivated/anonymized/banned)
5. candidate profile visible (approved photo, complete profile)
6. no ACTIVE match between the pair
7. no user_blocks either direction
8. neither user has a live PROPOSED introduction
9. pair not introduced within reintroduction-cooldown-days (30)
   measured from the previous introduction's terminal timestamp
10. supplied overrides as hard filters
```

**Three-phase implementation:**

```text
Phase 1 SQL — exclusions + requester's scalar/array prefs vs
              candidate profile columns (+ overrides).
Phase 2 SQL — candidate's CURRENT preference columns vs
              requester profile facts (reverse direction).
Phase 3 Java — alignment-dim rules, score computation, ranking,
              details generation.
```

Ordering: `overall_percentage DESC, request_id ASC`; materialized
cap `matchmaking.candidate-search-cap` (200). The backend never
picks — it returns ranked `CandidateMatchView[]` for the admin.

---

# 8. Compatibility scoring

```text
forward_percentage — how well the candidate satisfies the
    REQUESTER's active dims (satisfied / active × 100)
reverse_percentage — how well the requester satisfies the
    CANDIDATE's active dims
overall_percentage — round((forward + reverse) / 2)
```

`details: [{dimension, matched, label}]` records every evaluated
dimension — powers the admin's "why" review and is persisted on the
introduction so the rationale survives later preference edits.
Empty preference set (all ANY/NULL) → that direction scores 100%
(nothing violated), noted in details.

Every proposed introduction carries a human-readable `reason`
generated ONLY from actual matched dimensions — never claiming
compatibility that wasn't scored. Admin-editable at creation.

---

# 9. matchmaking_introductions

One row = "A was introduced to B."

```text
Columns: id, requester_request_id, candidate_request_id,
initiated_by_admin_user_id (audit FK RESTRICT),
forward/reverse/overall_percentage, match_details JSONB, reason,
requester_decision / candidate_decision (PENDING|INTERESTED|
NOT_INTERESTED) + decided_at stamps, status, decision_expires_at
(72 h), match_id (SET NULL), proposed_at, completed_at, timestamps.
```

```sql
-- one live introduction per request side
UNIQUE (requester_request_id) WHERE status='PROPOSED'
UNIQUE (candidate_request_id) WHERE status='PROPOSED'
```

`MUTUAL_INTEREST` is not a stored status — the second INTERESTED
creates the match in the same transaction.

## Quota

`matchmaking.introductions-per-request` (default **1**):

```text
consumed = intros in DECLINED | MATCHED | EXPIRED
           (admin CANCELLED does NOT consume — ops correction)

intro ends w/o match: consumed < quota → request OPEN
                      consumed = quota → request COMPLETED
```

On creation both requests go `OPEN → ON_HOLD` in one transaction;
release uses guarded `WHERE status='ON_HOLD'` updates.

## User-facing proposal payload

```text
{ introductionId, role, decisionExpiresAt, myDecision,
  partner: { full profile — photos, bio, address, lifestyle },
  match: { forward, reverse, overall, reason } }
```

Partner decision is never exposed prematurely.

## Transitions

```text
PROPOSED → DECLINED   either side NOT_INTERESTED (settle per quota)
PROPOSED → MATCHED    second INTERESTED → match, same tx
PROPOSED → EXPIRED    decision deadline (sweeper + lazy finalize)
PROPOSED → CANCELLED  admin / request cancellation (quota kept)
```

---

# 10. Decision flow & match creation

Decisions lock the introduction row `FOR UPDATE`; a late
`user_blocks` row declines like NOT_INTERESTED; resubmitting the
same decision is idempotent.

On the **second INTERESTED**, same transaction:

```text
1. Re-check: pair already ACTIVE-matched (organic match while
   PROPOSED)? → introduction CANCELLED, both requests COMPLETED,
   response alreadyMatched.
2. INSERT matches (match_source='MATCHMAKING',
   matchmaking_introduction_id=intro.id, like columns NULL).
3. intro → MATCHED (match_id, completed_at); both requests → MATCHED.
```

`unique_active_match_pair` remains the race backstop. Match
validation becomes source-specific (`MATCHMAKING` validates the
introduction: both users, status MATCHED, both INTERESTED — no fake
LIKE rows ever). Post-match, it's an ordinary match — unmatch, block
and messaging follow normal rules.

---

# 11. Audio & video call requests (post-match, match-scoped)

A **general matched-pair capability** — any `match_source`, surfaced
as a conversation action. Not a matchmaking step; the admin has no
call endpoints.

## Flow

```text
A taps "call" → POST /video-call-requests {match_id, call_type}
    → PENDING row → responder gets *_CALL_REQUESTED alert
B: ACCEPT → channel minted (VideoCallProvider / Agora)
   DECLINE / (A) CANCEL / sweeper EXPIRE
requester may REMIND (re-fires *_CALL_REQUESTED, ~2 min cooldown)
Both join → {channel_name, token, uid, expires_at} minted on demand
   → requester's first join charges VIDEO_CALL or AUDIO_CALL
     (by call_type) via requester_charge_key idempotency
END → COMPLETED (row = call history; new call = new row)
```

## Schema (V71 + V72)

```text
id, match_id (CASCADE), requester_user_id, responder_user_id,
status, call_type ('VIDEO'|'AUDIO', default VIDEO, fixed),
request_expires_at (48 h), responded_at,
channel_name (required only for ACCEPTED/COMPLETED),
requester_charge_key UNIQUE + requester_charged_at,
requester_joined_at, responder_joined_at, ended_at,
reminder_count, last_reminded_at, timestamps.

Partial unique index: one PENDING-or-ACCEPTED per match —
regardless of call_type. A second create returns the live row;
the LIVE row's call_type wins (requesting AUDIO while a VIDEO
request is pending returns the VIDEO request).
```

## State machine

```text
PENDING → ACCEPTED | DECLINED | CANCELLED | EXPIRED
ACCEPTED → COMPLETED
```

Rules:

```text
- Participants only; match must be ACTIVE at create and accept —
  match end or a block cancels live requests (killswitch, 409).
- Direction stored (requester/responder) — client labels are
  role-aware.
- Charge: requester's first join only, per call_type action;
  responder free; no refund once consumed.
- Tokens minted per join, never persisted; Agora App Certificate
  never leaves the server.
- remind: requester-only, PENDING-only, cooldown-guarded
  (matchmaking.video-call-reminder-cooldown-seconds, 120 s),
  stamped atomically; re-emits the type-correct REQUESTED alert.
```

---

# 12. Notifications (implemented alert codes)

Matchmaking:

```text
MATCHMAKING_REQUEST_CREATED          → requester (charge receipt)
MATCHMAKING_REQUEST_CANCELLED        → requester
MATCHMAKING_REQUEST_EXPIRED          → requester
MATCHMAKING_INTRODUCTION_PROPOSED    → both
MATCHMAKING_INTRODUCTION_DECLINED    → both
MATCHMAKING_INTRODUCTION_CANCELLED   → both
MATCHMAKING_MATCHED                  → both
```

Calls — prefix carries the type; the AUDIO family mirrors all five:

```text
VIDEO_CALL_REQUESTED / AUDIO_CALL_REQUESTED    → responder
                                              (re-fired by remind)
VIDEO_CALL_ACCEPTED  / AUDIO_CALL_ACCEPTED     → requester
VIDEO_CALL_DECLINED  / AUDIO_CALL_DECLINED     → requester
VIDEO_CALL_CANCELLED / AUDIO_CALL_CANCELLED    → the other party
VIDEO_CALL_EXPIRED   / AUDIO_CALL_EXPIRED      → requester
```

---

# 13. API surface

## Client API — `/api/v1/matchmaking` + `/api/v1/video-call-requests`

```text
GET    /matchmaking/preferences                 → prefs | 404
PUT    /matchmaking/preferences                 → upsert (replace),
                                                  version++, gender re-derived
POST   /matchmaking/requests {charge_idempotency_key}
       → requires prefs + valid profile → charge+insert, one tx
GET    /matchmaking/requests?page=&size=        → own history
GET    /matchmaking/requests/active             → live request | 404
GET    /matchmaking/requests/{requestId}        → own only (403/404)
DELETE /matchmaking/requests/{requestId}        → cancel + cascade
GET    /matchmaking/introductions/{introductionId}
       → intro + partner profile + score (IntroductionView)
POST   /matchmaking/introductions/{id}/decision {decision}
       → INTERESTED | NOT_INTERESTED; second YES → match in same tx

POST   /video-call-requests {match_id, call_type?}
       → create-or-return live request (201)
GET    /matches/{matchId}/video-call-requests   → history + live row
POST   /video-call-requests/{id}/accept         → responder
POST   /video-call-requests/{id}/decline        → responder
POST   /video-call-requests/{id}/cancel         → requester, PENDING
POST   /video-call-requests/{id}/remind         → requester nudge (429 cd)
POST   /video-call-requests/{id}/join           → {channel_name, token,
                                                  uid, expires_at}
POST   /video-call-requests/{id}/end            → idempotent COMPLETED
```

## Admin API — `/api/v1/admin/matchmaking` (role=ADMIN required)

```text
GET    /stats                                  → funnel counts
GET    /requests?status=&page=&size=           → queue
GET    /requests/{requestId}                   → single request
DELETE /requests/{requestId}                   → cancel (cascades)
GET    /requests/{requestId}/introductions     → intros for a request
POST   /requests/{requestId}/candidate-search  → ranked scored list
GET    /requests/{requestId}/notes             → admin notes feed
POST   /requests/{requestId}/notes {note}      → append note
PATCH  /users/{userId}/matchmaking-preferences/notes
                                               → set matchmaker_notes
GET    /introductions?status=&user_id=&search= → global list
POST   /introductions {requester_request_id,
       candidate_request_id, reason?}          → revalidate + score
                                                 + ON_HOLD, one tx
GET    /introductions/{introductionId}         → single intro
DELETE /introductions/{introductionId}         → cancel PROPOSED,
                                                 release requests OPEN
```

No admin call-management endpoints exist — calls are purely
user-driven.

---

# 14. Workers & configuration

```text
MatchmakingRequestSweeper      (hourly)  — OPEN past expires_at →
                               EXPIRED; ON_HOLD never swept
MatchmakingIntroductionSweeper (5 min)   — PROPOSED past
                               decision_expires_at → EXPIRED,
                               requests settle per quota
VideoCallRequestSweeper        (15 min)  — PENDING past
                               request_expires_at → EXPIRED
```

Plus lazy finalization on every load of an overdue entity.

```yaml
matchmaking:
  request-lifetime-days: 90
  introduction-decision-hours: 72
  reintroduction-cooldown-days: 30
  candidate-search-cap: 200
  introductions-per-request: 1
  video-call-request-expiry-hours: 48
  video-call-reminder-cooldown-seconds: 120

video:
  platform: ${VIDEO_PLATFORM:AGORA}
  agora:
    app-id: ${AGORA_APP_ID}
    app-certificate: ${AGORA_APP_CERTIFICATE}
    token-ttl-seconds: ${AGORA_TOKEN_TTL_SECONDS:3600}
```

---

# 15. Pair protection, charging, deletion

**Pair protection** (search + re-validated at intro creation):
no ACTIVE match, no live PROPOSED intro, no block, not within
re-introduction cooldown; organic-match conflict handled via
alreadyMatched (§10).

**Charging/refund:** request fee and call charge both
non-refundable once consumed; goodwill = manual credit grant.
No escrow anywhere.

**Deletion:** user-owned rows CASCADE; `requests → preferences`
NO ACTION (RESTRICT would abort mid-cascade); `matches` deletion →
`introductions.match_id` and `matches.matchmaking_introduction_id`
SET NULL, `video_call_requests` CASCADE; admin audit FKs RESTRICT
(admins are deactivated, never deleted).

---

# 16. Relationship diagram & tables

```text
app_users ─┬─ profiles
           └─ matchmaking_preferences ── matchmaking_requests ─┬─ matchmaking_admin_notes
                                                               └─ matchmaking_introductions
                                                                       │ both INTERESTED
                                                                       ▼
                                                    matches ──┬─ messages (existing)
                    (match_source=MATCHMAKING)                └─ video_call_requests
```

- **New:** `video_call_requests` (V71; +`call_type`, reminder
  columns V72), typed pref columns + `matchmaking_admin_notes` (V71),
  `AUDIO_CALL` feature action (V72).
- **Kept/modified:** `matchmaking_preferences`,
  `matchmaking_requests`, `matchmaking_introductions`, `matches`
  (`match_source` += `MATCHMAKING`, nullable like columns).
- **Removed:** `matchmaking_messages`, `matchmaking_video_calls`,
  snapshot columns, escrow actions/refund source.

---

# 17. Core rules (condensed)

```text
1.  Preferences: typed columns; directional + 5 alignment dims;
    live row always evaluated — no snapshots.
2.  Matchmaking is admin-initiated; backend ranks, never selects.
3.  Eligibility = hard SQL exclusions; satisfaction = scored %.
    Alignment dims need both answers; gender is a hard exclusion.
4.  Overrides are per-search hard filters; saved prefs untouched.
5.  Intro creation: revalidate + score + persist details/reason +
    both ON_HOLD, one transaction. Quota (default 1): DECLINED/
    EXPIRED consume, CANCELLED does not.
6.  One live intro per request side; decisions one-shot, FOR
    UPDATE locked, partner's decision hidden until yours is in.
7.  Second INTERESTED → matches row same tx; organic conflict →
    alreadyMatched + CANCELLED + COMPLETED.
8.  No matchmaking messaging, no matchmaking video flow — post-match
    comms = existing conversation + video_call_requests.
9.  Calls: one live request per match regardless of call_type;
    requester charged on first join only (VIDEO_CALL/AUDIO_CALL);
    responder free; tokens on demand; remind with cooldown.
10. Fees non-refundable; sweeps + lazy finalization close stale
    entities; all tunables in application.yml.
```

---

# 18. Final end-to-end flow

```text
USER: preferences → request (paid) → OPEN
       "Your matchmaker is carefully reviewing your request"
ADMIN: open request → candidate search (overrides) → ranked list
       → select candidate → introduction (both ON_HOLD, 72 h)
USERS: notified → review profile+score+reason → INTERESTED / NOT
       ├─ any NOT → DECLINED → quota? OPEN : COMPLETED
       └─ both YES → matches row (MATCHMAKING) → MATCHED
CLIENT: existing conversation — text/voice/image + call requests
        PENDING → ACCEPTED → join (Agora, requester charged)
        → end
```**

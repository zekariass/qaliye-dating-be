# Personal Matchmaking — Redesign Implementation Plan

> **Reference design:** `personal-match-making-design.md`
> **Requirements:** `personal-matchmaking-redesign.md`
> **Next Flyway version:** V71 (V70 already applied)
> **Package roots:** `com.qaliye.backend.matchmaking` (existing),
> `com.qaliye.backend.videocall` (new — general post-match video calls)
> **Status:** REPLACES the previous version of this plan, which described
> the pre-redesign implementation (V70). Everything below is a
> **migration of the existing code**, not a greenfield build.

This is a redesign of the **existing** matchmaking implementation. Per
the requirements, each existing component is mapped to **Keep / Modify /
Remove / Replace** before any code is touched. Obsolete entities, tables,
services, APIs, workers and code are removed — not kept for backward
compatibility.

---

## 0. Implementation Order

Execute in this order. Each step is self-contained and compilable.

```
Step 1  — Flyway V71 (schema: alter + drop + create + feature action)
Step 2  — Configuration changes (MatchmakingProperties, application.yml)
Step 3  — Repository layer: modify 3, delete 2, add 1
Step 4  — Preference layer (5 new alignment columns end-to-end)
Step 5  — Compatibility scoring service (new)
Step 6  — Candidate search rework (scored ranked results)
Step 7  — Request service rework (no escrow, no snapshot, no greeting)
Step 8  — Introduction service rework (quota + direct match on mutual YES)
Step 9  — Video-call-request feature (new videocall package)
Step 10 — Controllers rework (user + admin) + new VideoCallRequestController
Step 11 — Workers (delete 2, modify 1, add 1)
Step 12 — Notification wiring
Step 13 — Cross-cutting modifications to existing files
Step 14 — Tests (delete obsolete, update affected, add new)
Step 15 — Final sweep: dead code, constants, docs
```

---

## 1. Component Map — Keep / Modify / Remove / Replace

Existing `matchmaking/` package, file by file:

| File | Disposition | Why |
|------|-------------|-----|
| `MatchmakingConstants.java` | **Modify** | drop INTRO `MUTUAL_INTEREST`, all `VIDEO_*` platform/outcome/contact constants, `MATCHMAKING_MESSAGE_*` events; add `VIDEO_CALL_*` request statuses + notification event names |
| `config/MatchmakingConfig.java` | **Keep** | unchanged |
| `config/MatchmakingProperties.java` | **Modify** | drop `videoOutcomeWindowHours` + any video/arranging props; add `introductionsPerRequest`, `videoCallRequestExpiryHours` |
| `controller/MatchmakingController.java` | **Modify** | remove `/messages*` + `/video-calls*` endpoints; keep preferences/requests/introductions |
| `controller/AdminMatchmakingController.java` | **Modify** | remove `/messages*`, `/video-calls*`, `/introductions/{id}/video-call`, outcome/arrange/contact endpoints; keep queue/stats/candidates/introductions/notes |
| `dto/CandidateMatchView.java` | **Modify** | add `preferences` + `match` (scores/details) to the view |
| `dto/IntroductionView.java` | **Modify** | add `match` block (percentages + reason), candidate `relevantPreferences` |
| `repository/MatchmakingPreferenceRepository.java` | **Modify** | +5 alignment columns in `PreferenceRow`, upsert SQL, `PreferenceValues` |
| `repository/MatchmakingRequestRepository.java` | **Modify** | drop `preferences_snapshot`, `preferences_version`, `video_refund_key`, `video_refunded_at`, both `*_last_read_message_seq` from `SELECT_COLS`/`RequestRow`/insert; keep queue/count/lock/status methods |
| `repository/MatchmakingIntroductionRepository.java` | **Modify** | add percentage + `match_details` + `reason` columns; drop `search_source`, `effective_filter_params`, `admin_override_params`; add `countConsumedByRequest` for quota; keep admin `findAll`/`countAll` |
| `repository/MatchmakingMessageRepository.java` | **Remove** | matchmaking messaging is gone |
| `repository/MatchmakingVideoCallRepository.java` | **Remove** | matchmaking video flow is gone |
| `repository/MatchmakingAdminNoteRepository.java` | **Keep** | unchanged |
| `service/MatchmakingPreferenceService.java` | **Modify** | accept + validate the 5 new alignment fields |
| `service/MatchmakingPreferenceNormalizer.java` | **Modify** | add `MARRIAGE_TIMELINE` + YES/NO alignment validation sets |
| `service/MatchmakingRequestService.java` | **Modify** | drop escrow charge, snapshot write, system-greeting insert, refund calls, read-cursor fields; single `PERSONAL_MATCHMAKING_REQUEST` charge |
| `service/MatchmakingCandidateSearchService.java` | **Modify** | remove snapshot reads; use live prefs; integrate scoring + ranking + profile cards + candidate prefs in result |
| `service/MatchmakingIntroductionService.java` | **Modify** | quota enforcement; decision → direct match creation (no video call); reason/scores stamped at creation; settle requests per quota |
| `service/MatchmakingMatchService.java` | **Keep** | already inserts `match_source='MATCHMAKING'` + `matchmaking_introduction_id`; reuse as-is |
| `service/MatchmakingAdminDashboardService.java` | **Modify** | stats: drop video-call metrics, keep request/intro funnels + action-needed |
| `service/MatchmakingAdminNoteService.java` | **Keep** | unchanged |
| `service/MatchmakingEscrowService.java` | **Remove** | no escrow anywhere |
| `service/MatchmakingMessageService.java` | **Remove** | messaging removed |
| `service/MatchmakingVideoCallService.java` | **Remove** | matchmaking video flow removed |
| `service/MatchmakingCompatibilityService.java` | **NEW** | forward/reverse/overall scoring + details + reason generation (§5 below) |
| `video/VideoCallProvider.java` | **Move** | → `videocall/provider/VideoCallProvider.java`; same SPI, now serves `video_call_requests` |
| `video/AgoraVideoProvider.java` | **Move** | → `videocall/provider/AgoraVideoProvider.java` |
| `video/VideoConfig.java` | **Move** | → `videocall/provider/VideoConfig.java` |
| `video/VideoProperties.java` | **Move** | → `videocall/provider/VideoProperties.java` |
| `worker/MatchmakingRequestExpiryWorker.java` | **Keep** | unchanged (OPEN + expires_at → EXPIRED) |
| `worker/MatchmakingIntroductionExpiryWorker.java` | **Modify** | settle requests per quota rule (§design §13) instead of reopening unconditionally |
| `worker/MatchmakingVideoOutcomeSweepWorker.java` | **Remove** | no outcome flow |
| `worker/MatchmakingStaleVideoCallWorker.java` | **Remove** | no ARRANGING/IN_PROGRESS calls |

Cross-package:

| File | Disposition | Why |
|------|-------------|-----|
| `CreditService.java` | **Modify** | remove `grantMatchmakingEscrowRefund` (no escrow refunds exist) |
| `QuartzConfig.java` | **Modify** | drop the 2 deleted video workers; add `VideoCallRequestSweeper` job/trigger |
| `NotificationDispatcher.java` | **Modify** | drop `MATCHMAKING_MESSAGE_*` + `MATCHMAKING_VIDEO_*` dispatches; add `VIDEO_CALL_*` dispatches |
| `application.yml` | **Modify** | matchmaking keys per §3 |
| `matches` schema | **Keep** | `match_source`/`matchmaking_introduction_id` already exist — no DDL needed |
| `videocall/` (new package) | **NEW** | `VideoCallRequestController`, `VideoCallRequestService`, `VideoCallRequestRepository`, `VideoCallRequestSweeper`, provider SPI moved in |

---

## 2. Flyway Migration — V71

**File:** `src/main/resources/db/migration/V71__matchmaking_redesign.sql`

### 2a. `matchmaking_preferences` — add alignment columns

```sql
ALTER TABLE public.matchmaking_preferences
    ADD COLUMN marriage_timeline          VARCHAR(30) NULL,
    ADD COLUMN long_distance_relationship VARCHAR(5)  NULL,
    ADD COLUMN family_involvement         VARCHAR(5)  NULL,
    ADD COLUMN religion_important         VARCHAR(5)  NULL,
    ADD COLUMN willing_to_relocate        VARCHAR(5)  NULL;

ALTER TABLE public.matchmaking_preferences
    ADD CONSTRAINT matchmaking_preferences_timeline_check
        CHECK (marriage_timeline IS NULL OR marriage_timeline IN (
            'NOW','ONE_TO_TWO_YEARS','THREE_TO_FIVE_YEARS','NOT_SURE')),
    ADD CONSTRAINT matchmaking_preferences_alignment_check
        CHECK (
            (long_distance_relationship IS NULL OR
             long_distance_relationship IN ('YES','NO'))
        AND (family_involvement IS NULL OR
             family_involvement IN ('YES','NO'))
        AND (religion_important IS NULL OR
             religion_important IN ('YES','NO'))
        AND (willing_to_relocate IS NULL OR
             willing_to_relocate IN ('YES','NO'))
        );
```

### 2b. `matchmaking_requests` — drop obsolete columns

```sql
ALTER TABLE public.matchmaking_requests
    DROP COLUMN preferences_snapshot,
    DROP COLUMN preferences_version,
    DROP COLUMN video_refund_key,
    DROP COLUMN video_refunded_at,
    DROP COLUMN user_last_read_message_seq,
    DROP COLUMN admin_last_read_message_seq;
```

### 2c. `matchmaking_introductions` — score columns, drop filter params

```sql
-- Data migration BEFORE the CHECK changes: resolve legacy
-- MUTUAL_INTEREST rows to their final state.
UPDATE public.matchmaking_introductions
SET    status = 'MATCHED', completed_at = COALESCE(completed_at, updated_at)
WHERE  status = 'MUTUAL_INTEREST' AND match_id IS NOT NULL;

UPDATE public.matchmaking_introductions
SET    status = 'DECLINED'
WHERE  status = 'MUTUAL_INTEREST' AND match_id IS NULL;

ALTER TABLE public.matchmaking_introductions
    ADD COLUMN forward_percentage  INTEGER NULL,   -- backfill below, then NOT NULL
    ADD COLUMN reverse_percentage  INTEGER NULL,
    ADD COLUMN overall_percentage  INTEGER NULL,
    ADD COLUMN match_details       JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN reason              TEXT NULL,
    DROP COLUMN search_source,
    DROP COLUMN effective_filter_params,
    DROP COLUMN admin_override_params;

-- Historical rows have no score data; stamp 0 so the columns can go NOT NULL.
UPDATE public.matchmaking_introductions
SET    forward_percentage = 0, reverse_percentage = 0, overall_percentage = 0
WHERE  forward_percentage IS NULL;

ALTER TABLE public.matchmaking_introductions
    ALTER COLUMN forward_percentage SET NOT NULL,
    ALTER COLUMN reverse_percentage SET NOT NULL,
    ALTER COLUMN overall_percentage SET NOT NULL;

ALTER TABLE public.matchmaking_introductions
    DROP CONSTRAINT matchmaking_introductions_status_check,
    ADD CONSTRAINT matchmaking_introductions_status_check
        CHECK (status IN (
            'PROPOSED','DECLINED','MATCHED','CANCELLED','EXPIRED'));
```

### 2d. Drop obsolete tables

```sql
DROP TABLE public.matchmaking_messages;
DROP TABLE public.matchmaking_video_calls;   -- drops its FKs/CHECKs/indexes
```

> Verify no other table still references `matchmaking_video_calls` before
> running (grep V70 — `matchmaking_requests.video_refund_*` were plain
> columns, no FK). Drop order: dependent-first if any FK exists.

### 2e. New `video_call_requests` table

```sql
CREATE TABLE public.video_call_requests (
    id                UUID DEFAULT gen_random_uuid() NOT NULL,
    match_id          UUID NOT NULL,
    requester_user_id UUID NOT NULL,
    responder_user_id UUID NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    request_expires_at TIMESTAMPTZ NOT NULL,
    responded_at      TIMESTAMPTZ NULL,
    channel_name      VARCHAR(255) NULL,
    requester_charge_key UUID NULL,
    requester_charged_at TIMESTAMPTZ NULL,
    requester_joined_at TIMESTAMPTZ NULL,
    responder_joined_at TIMESTAMPTZ NULL,
    ended_at          TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,

    CONSTRAINT video_call_requests_pkey PRIMARY KEY (id),
    CONSTRAINT video_call_requests_match_fkey
        FOREIGN KEY (match_id) REFERENCES public.matches(id)
        ON DELETE CASCADE,
    CONSTRAINT video_call_requests_requester_fkey
        FOREIGN KEY (requester_user_id) REFERENCES public.app_users(id)
        ON DELETE CASCADE,
    CONSTRAINT video_call_requests_responder_fkey
        FOREIGN KEY (responder_user_id) REFERENCES public.app_users(id)
        ON DELETE CASCADE,
    CONSTRAINT video_call_requests_distinct_users_check
        CHECK (requester_user_id <> responder_user_id),
    CONSTRAINT video_call_requests_status_check
        CHECK (status IN ('PENDING','ACCEPTED','DECLINED',
                          'CANCELLED','EXPIRED','COMPLETED')),
    CONSTRAINT video_call_requests_channel_check
        CHECK (status = 'PENDING' OR channel_name IS NOT NULL),
    CONSTRAINT video_call_requests_charge_key_uq
        UNIQUE (requester_charge_key)
);

CREATE UNIQUE INDEX uq_video_call_request_per_match
ON public.video_call_requests (match_id)
WHERE status IN ('PENDING','ACCEPTED');

CREATE INDEX idx_video_call_requests_sweep
ON public.video_call_requests (request_expires_at)
WHERE status = 'PENDING';

CREATE INDEX idx_video_call_requests_match
ON public.video_call_requests (match_id);
```

### 2f. `feature_actions`

```sql
INSERT INTO public.feature_actions (code, name, type, description)
VALUES ('VIDEO_CALL', 'Video Call', 'ACTION',
        'Video call between matched users — charged to requester on join')
ON CONFLICT (code) DO NOTHING;
```

`PERSONAL_MATCHMAKING_VIDEO_ESCROW`: remove referencing
`subscription_plan_limit_and_cost` rows, then delete the action row if
no FK blocks it (historical `user_credit_ledger` rows are unaffected —
they store the code as data). If a hard FK exists, leave the row; it is
simply never consumed. `MATCHMAKING_ESCROW_REFUND` stays in the
`credit_source_type` CHECK for historical ledger rows.

---

## 3. Configuration

`MatchmakingProperties.java` — final shape:

```java
@ConfigurationProperties(prefix = "matchmaking")
public class MatchmakingProperties {
    private int requestLifetimeDays         = 90;
    private int introductionDecisionHours   = 72;
    private int reintroductionCooldownDays  = 30;
    private int candidateSearchCap          = 200;
    private int introductionsPerRequest     = 1;    // NEW — design §12
    private int videoCallRequestExpiryHours = 48;   // NEW — design §15
    // removed: videoOutcomeWindowHours, arranging/stale video props
}
```

`application.yml` — replace the video-outcome keys:

```yaml
matchmaking:
  request-lifetime-days: 90
  introduction-decision-hours: 72
  reintroduction-cooldown-days: 30
  candidate-search-cap: 200
  introductions-per-request: 1
  video-call-request-expiry-hours: 48
```

`video:` block (platform/agora) stays as-is; only the owning classes move
to `videocall/provider/`.

---

## 4. Repository Layer

### 4a. `MatchmakingPreferenceRepository` — modify

- `PreferenceRow`: + `marriageTimeline`, `longDistanceRelationship`,
  `familyInvolvement`, `religionImportant`, `willingToRelocate` (all
  nullable `String`).
- `PreferenceValues` (the PUT body record): same 5 fields added.
- Upsert SQL: include the 5 columns on INSERT and `DO UPDATE SET`.
- `SELECT_COLS`: include the new columns.
- `toSnapshotMap()` **deleted** — snapshots no longer exist.

### 4b. `MatchmakingRequestRepository` — modify

- `SELECT_COLS`: remove `preferences_snapshot`, `preferences_version`,
  `video_refund_key`, `video_refunded_at`, `user_last_read_message_seq`,
  `admin_last_read_message_seq`.
- `RequestRow`: remove those 6 fields.
- `insert(...)`: drop the snapshot params; no `toSnapshotMap()` call.
- Delete: `stampVideoRefund`, `advanceUserCursor`, `advanceAdminCursor`,
  any `hasCompletedVideoCall` query.
- Keep: `findQueue`, `countQueue`, `findActiveByUserId`, `lockById`,
  `setStatus`, `releaseOnHold` (guarded `WHERE status='ON_HOLD'`),
  `findExpiredOpen`, `findByUserId` history, `updateContact`.

### 4c. `MatchmakingIntroductionRepository` — modify

- `IntroductionRow` + `SELECT_COLS`: + `forwardPercentage`,
  `reversePercentage`, `overallPercentage`, `matchDetails` (JSONB→String/
  `ObjectNode`), `reason`; − `searchSource`, `effectiveFilterParams`,
  `adminOverrideParams`.
- `insert(row)`: persist the new score/reason columns.
- **New:** `countConsumedByRequest(UUID requestId)` —
  `COUNT(*) WHERE (requester_request_id=:id OR candidate_request_id=:id)
   AND status IN ('DECLINED','MATCHED','EXPIRED')`.
- Keep: `findActiveByRequestId`, `findAll`/`countAll` (admin list),
  `findExpiredProposed`, `lockById`, `setStatus`, `setDecision`,
  `setMatchId`, cooldown query (`findRecentIntroductionBetweenPair`).
- Delete: any query referencing video calls or `MUTUAL_INTEREST`.

### 4d. Deletions

- `MatchmakingMessageRepository.java` — delete file.
- `MatchmakingVideoCallRepository.java` — delete file.

### 4e. NEW `VideoCallRequestRepository` (`videocall/` package)

| Method | SQL pattern |
|--------|-------------|
| `insert(row)` | `INSERT INTO video_call_requests ... RETURNING *` — set `request_expires_at = now + expiry` |
| `findById(UUID)` | standard |
| `lockById(UUID)` | `SELECT ... FOR UPDATE` — accept/decline/join writes |
| `findActiveByMatchId(UUID)` | `WHERE match_id=:id AND status IN ('PENDING','ACCEPTED')` — ≤1 by unique index |
| `findByMatchId(UUID, page, size)` | history, `ORDER BY created_at DESC` |
| `setStatus(id, expected, newStatus, respondedAt?)` | guarded transition |
| `accept(id, channelName, respondedAt)` | `status='ACCEPTED'` + channel in one UPDATE |
| `stampJoin(id, side)` | set `requester_joined_at`/`responder_joined_at` |
| `stampCharge(id, chargeKey)` | `SET requester_charge_key=:k, requester_charged_at=now WHERE requester_charge_key IS NULL` |
| `end(id, endedAt)` | `ACCEPTED → COMPLETED` |
| `findExpiredPending(now)` | `WHERE status='PENDING' AND request_expires_at < :now` |
| `cancelActiveForMatch(matchId)` | unmatch/block cleanup |

---

## 5. Compatibility Scoring — NEW `MatchmakingCompatibilityService`

**File:** `matchmaking/service/MatchmakingCompatibilityService.java`

Pure, dependency-light component — takes the **effective** requester
preferences (live prefs + admin overrides merged), the requester's own
prefs + profile facts, and the candidate's prefs + profile facts. Returns:

```java
public record CompatibilityResult(
        int forwardPercentage,
        int reversePercentage,
        int overallPercentage,
        List<DimensionResult> details,
        String reason) {}

public record DimensionResult(
        String dimension, boolean matched, String label) {}
```

**Inputs prepared by the caller** (`MatchmakingCandidateSearchService` /
introduction creation): a `PreferenceFacts` per user — the typed
`PreferenceRow` fields + resolved profile facts (age, heightCm,
countryCode, religion, education, maritalStatus, smokingDetail,
drinkingDetail, languageIds, ethnicityIds, hasChildren, wantsChildren).

**Evaluation rules** (design §6, §11):

```text
Directional dims — evaluated per side against the OTHER user's facts:
  age, height, specific_countries, has_children, wants_children,
  religion, education, marital_status, smoking, drinking,
  languages, ethnicity
  Active ⇔ non-NULL / non-'any' / non-empty array.

Alignment dims — single symmetric outcome counting in BOTH directions:
  marriage_timeline   → equal, or either NOT_SURE
  long_distance       → both YES, or same country_code; NO↔YES across
                        countries fails
  family_involvement  → equal YES/NO
  religion_important  → equal YES/NO
  willing_to_relocate → equal YES/NO
  Active only when BOTH sides answered (non-NULL).

forward  = satisfied requester-side active dims / active × 100
reverse  = satisfied candidate-side active dims / active × 100
overall  = round((forward + reverse) / 2)
active = 0 → 100 (note it in details)
```

**`reason` generation** — "why this introduction" (design §9/§12):
built ONLY from `details` entries where `matched = true`. Template
phrases per dimension (e.g. `marriage_timeline` → "You both are looking
for marriage within a similar timeframe", `willing_to_relocate` → "You
both are open to relocating", `languages` → "You both speak {langs}").
Join the top ~3 matched labels into a sentence list; never claim
compatibility for dims that weren't evaluated or failed. Fallback when
nothing matched: `"Your matchmaker selected this profile for you"`.

---

## 6. Candidate Search Rework

`MatchmakingCandidateSearchService` — keep the class + JDBC approach;
change what it searches on and what it returns.

```text
BEFORE: requester snapshot (JSONB map) + overrides → :f* params vs
        candidate profile facts; candidate mp.* columns vs :rq*
        requester facts (already bidirectional in SQL).

AFTER : requester's LIVE matchmaking_preferences columns + overrides
        → same :f* binding positions, but read straight from the
        preference row (no toSnapshotMap()).
        Candidate side unchanged (mp.* columns vs requester facts).
        Alignment dims are NOT in the SQL — they are scored in Java
        (Phase 3) after the bidirectional SQL pass.
```

Pipeline per search call:

```text
1. load requester request (must be OPEN) + requester prefs + profile
2. effective filters = requester prefs + admin overrides merged
   (KNOWN_FILTER_KEYS validated by the normalizer — unchanged)
3. Phase-1 SQL: eligibility exclusions (§10 design) + scalar/array
   directional checks + admin overrides — up to candidateSearchCap
4. Phase-2 SQL (existing): candidate mp.* directional checks vs
   requester facts — keep as hard filter? NO — see note below
5. Phase-3 Java: for each survivor, compatibilityService.score(
   requesterEffective, requesterFacts, candidatePrefs, candidateFacts)
   → CompatibilityResult
6. Rank by overallPercentage DESC, request_id ASC; page from the
   materialized list (page/size in request body)
7. Enrich each page item: profile card (signed photo via
   SupabaseStorageService — existing code) + candidate preferences +
   match block
```

**Design decision (scored, not filtered):** the redesign ranks ALL
eligible candidates by compatibility rather than hard-filtering on
every preference. The SQL keeps only **hard exclusions** (§10 items
1–10: status, gender, account state, visibility, match, blocks, live
intro, cooldown, explicit admin overrides). The requester's own
preference columns move from WHERE-clauses into the scorer. This keeps
"get all matches but rank based on compatibility" (requirement §6) and
makes zero-match preference sets still return results.

**DTO — `CandidateMatchView` (modified):**

```java
public record CandidateMatchView(
        RequestRow request,
        CandidateProfileCard profile,          // existing
        PreferenceValues preferences,          // NEW — candidate's prefs
        MatchScore match) {

    public record MatchScore(
            int forwardPercentage,
            int reversePercentage,
            int overallPercentage,
            List<DimensionResult> details) {}
}
```

**Introduction-creation re-validation** (design §18): when the admin
creates the introduction, `createIntroduction` re-runs the scorer for
the chosen pair and persists `forward/reverse/overall_percentage` +
`match_details` + `reason` on the row — the admin may override `reason`
in the request body.

---

## 7. Request Service Rework

`MatchmakingRequestService.createRequest` — new flow:

```text
@Transactional
→ assertProfileComplete + assertHasApprovedPhoto (unchanged)
→ assertNoActiveRequest (unchanged)
→ existsByChargeKey(idempotencyKey) → return existing (unchanged)
→ loadPreferences(userId) — must exist → 422
→ charge PERSONAL_MATCHMAKING_REQUEST  (REMOVE the escrow charge)
→ INSERT matchmaking_requests
    (status=OPEN, expires_at=now+requestLifetimeDays,
     request_charge_idempotency_key, contact_phone)
→ notify MATCHMAKING_REQUEST_CREATED (new event)
// removed: preferences_snapshot write, toSnapshotMap(),
//          escrow charge, insertSystemGreeting()
```

`cancelRequest` / `cancelRequestAdmin`:

```text
@Transactional
→ lock request; terminal → 409
→ if ON_HOLD: cancel active introduction via
  introductionService path — CANCELLED (quota NOT consumed),
  partner request → OPEN
→ request → CANCELLED + cancelled_at
→ notify MATCHMAKING_REQUEST_CANCELLED
// removed: videoCallRepo cascade, tryGrantEscrowRefund
```

`getActiveRequest`: unchanged minus removed `RequestRow` fields.

`lazyFinalizeExpiredRequest`: EXPIRED + notify; no refund call.

Delete: cursor-advance helpers, `stampVideoRefund` usage,
`hasCompletedVideoCall` usage.

---

## 8. Introduction Service Rework

`MatchmakingIntroductionService`:

### `createIntroduction(adminUserId, requesterRequestId, candidateRequestId, reason?)`

```text
@Transactional
→ lock both requests FOR UPDATE (lower UUID first) — unchanged pattern
→ revalidate both OPEN (409)
→ quota check on BOTH requests:
    countConsumedByRequest(id) < introductionsPerRequest  (409 if reached)
→ full pair-protection checks (match, blocks, live intro, cooldown)
→ compatibilityService.score(...) — persist percentages + details
→ reason = body.reason ?: result.reason
→ INSERT introductions (PROPOSED, decision_expires_at = now + 72h,
    scores, match_details, reason)
→ both requests OPEN → ON_HOLD (guarded)
→ notify MATCHMAKING_INTRODUCTION_PROPOSED to both
```

### `recordDecision(introductionId, userId, decision)`

```text
@Transactional
→ lockById FOR UPDATE
→ lazy-finalize if PROPOSED + decision_expires_at < now
→ side check; idempotent same-decision → current state
→ block check → treat as NOT_INTERESTED
→ stamp decision + decided_at
→ if NOT_INTERESTED: introduction → DECLINED; settleRequests()
→ if both INTERESTED:
    matchResult = matchmakingMatchService.createMatch(userA, userB, introId)
    if alreadyMatched → introduction → CANCELLED (not consumed),
        both requests → COMPLETED, return { alreadyMatched, matchId }
    else → introduction → MATCHED + match_id + completed_at,
        both requests → MATCHED, notify MATCHMAKING_MATCH_CREATED,
        return { matchId }
→ else return { status: PROPOSED }
// removed: MUTUAL_INTEREST intermediate state, video-call INSERT,
//          MATCHMAKING_VIDEO_CALL_CREATED notification
```

### `settleRequests(intro)` — quota-aware release

```text
for each of requester/candidate request:
    consumed = countConsumedByRequest(requestId)   // includes this intro
    if consumed >= introductionsPerRequest → COMPLETED (terminal)
    else → OPEN (guarded WHERE status='ON_HOLD')
// used by: DECLINED path, EXPIRED path (sweeper + lazy), NOT cancel
// CANCELLED never consumes: partner + self always reopen
```

### `cancelIntroduction` (admin)

```text
→ introduction → CANCELLED; both requests → OPEN (guarded)
→ notify MATCHMAKING_INTRODUCTION_CANCELLED
// no callDelivered distinction — cancellation never consumes quota
```

### `lazyFinalizeProposalExpiry`

```text
→ EXPIRED + settleRequests() (consumes quota) + notify EXPIRED
```

---

## 9. Video-Call-Request Feature — NEW `videocall/` package

Post-match capability for ANY match source. All code new except the
provider SPI which **moves** from `matchmaking/video/`.

### Files

```text
videocall/provider/VideoCallProvider.java     ← moved (SPI unchanged:
                                                  name(), channelFor(id),
                                                  issueJoin(id, userId, host))
videocall/provider/AgoraVideoProvider.java    ← moved
videocall/provider/VideoProperties.java       ← moved (video.* config)
videocall/provider/VideoConfig.java           ← moved

videocall/repository/VideoCallRequestRepository.java   ← §4e
videocall/service/VideoCallRequestService.java
videocall/controller/VideoCallRequestController.java
videocall/dto/VideoCallRequestView.java
videocall/worker/VideoCallRequestSweeper.java
videocall/VideoCallConstants.java
```

`VideoCallProvider.channelFor` currently takes `callId` — reuse as-is:
`channelFor(videoCallRequestId)`. `issueJoin(callId, userId, host)` —
`host=false` for both users (no matchmaker in calls anymore).

### `VideoCallRequestService`

```text
createRequest(userId, matchId)
    @Transactional
    → caller must be a match participant; match.status = ACTIVE
    → no user_blocks between pair
    → findActiveByMatchId → if exists return it (idempotent)
      else INSERT (PENDING, request_expires_at = now + 48h,
      requester=caller, responder=other)
    → notify VIDEO_CALL_REQUESTED → responder

accept(requestId, responderId)
    @Transactional → lockById; must be PENDING + caller = responder;
    match still ACTIVE else CANCELLED
    → channelName = provider.channelFor(id); status → ACCEPTED +
      responded_at; notify VIDEO_CALL_ACCEPTED → requester

decline(requestId, responderId)
    → PENDING → DECLINED; notify VIDEO_CALL_DECLINED

cancel(requestId, requesterId)
    → PENDING → CANCELLED; notify VIDEO_CALL_CANCELLED

join(requestId, userId)
    @Transactional → lockById
    → participant only; status must be ACCEPTED
    → if caller = requester AND requester_charge_key IS NULL:
        charge VIDEO_CALL (existing credit pipeline, idempotency =
        generated UUID → stampCharge WHERE charge_key IS NULL;
        if stamp lost the race → another tx already charged, proceed)
        insufficient credit → billing error, no join
    → stampJoin(side)
    → return provider.issueJoin(id, userId, host=false)
      { channelName, token, uid, expiresAt }

end(requestId, userId)
    → participant; ACCEPTED → COMPLETED + ended_at; idempotent

listForMatch(matchId, userId, page, size)
    → participant check; returns history + live request
    → each row annotated caller-relative: isRequester, canCancel,
      canAccept, canJoin

expireSweep() — PENDING + request_expires_at < now → EXPIRED +
    notify VIDEO_CALL_EXPIRED → requester

cancelActiveForMatch(matchId) — invoked when a match is
    deactivated/unmatched (hook into existing match lifecycle or
    lazy-check on next load — pick listener if one exists)
```

**Blocked-user rule:** a `user_blocks` insert during a live request →
request CANCELLED. Implement as a lazy check inside every service load
(consistent with the rest of the codebase) — cheap EXISTS query.

---

## 10. Controllers

### `MatchmakingController` — remove

```text
GET/POST /requests/{id}/messages*          (3 endpoints)
POST     /requests/{id}/messages/mark-read
GET      /video-calls/*                    (current/join/contact/outcome)
```
Keep: preferences GET/PUT (body gains the 5 alignment fields),
requests POST/GET/PATCH/cancel, introductions GET/decision.

### `AdminMatchmakingController` — remove

```text
GET/POST /requests/{id}/messages* + mark-read
POST     /introductions/{id}/video-call
GET/POST /video-calls/*                    (arrange/join/start/complete/
                                          no-show/cancel/request-contacts)
```
Keep: request queue (+status filter), request detail, cancel, complete,
matchmaker-notes, admin notes, candidate search (now returns scored
`CandidateMatchView`), introductions create/list/detail/cancel, stats.

### NEW `VideoCallRequestController` (`videocall/`)

```text
POST /api/v1/video-call-requests                 { matchId }
GET  /api/v1/matches/{matchId}/video-call-requests?page=&size=
POST /api/v1/video-call-requests/{id}/accept
POST /api/v1/video-call-requests/{id}/decline
POST /api/v1/video-call-requests/{id}/cancel
POST /api/v1/video-call-requests/{id}/join
POST /api/v1/video-call-requests/{id}/end
```

Response `VideoCallRequestView`: `{ id, matchId, status, isRequester,
requestExpiresAt, respondedAt, canCancel, canAccept, canJoin, endedAt,
createdAt }` — role-aware per caller (design §16 state labels).

Join response: `{ channelName, token, uid, expiresAt }`.

---

## 11. Workers

```text
KEEP     MatchmakingRequestExpiryWorker      — unchanged
MODIFY   MatchmakingIntroductionExpiryWorker — settleRequests() quota path
DELETE   MatchmakingVideoOutcomeSweepWorker  — file removed
DELETE   MatchmakingStaleVideoCallWorker     — file removed
NEW      VideoCallRequestSweeper             — PENDING + expired → EXPIRED,
                                              notify requester (15 min)
```

`QuartzConfig`: remove the two deleted JobDetail/Trigger beans, add the
new sweeper pair.

---

## 12. Notification Wiring

`NotificationDispatcher` changes:

```text
DELETE dispatch* for: MATCHMAKING_MESSAGE_RECEIVED,
    all MATCHMAKING_VIDEO_* (created/scheduled/started/completed/
    no-show/cancelled/contacts-requested),
    MATCHMAKING_REQUEST_VIDEO_REFUNDED

KEEP   dispatch* for: INTRODUCTION_PROPOSED/DECLINED/EXPIRED/
    CANCELLED, MATCH_CREATED, REQUEST_CANCELLED/EXPIRED

ADD    dispatchVideoCallRequested(responderId, requestId)
ADD    dispatchVideoCallAccepted(requesterId, requestId)
ADD    dispatchVideoCallDeclined(requesterId, requestId)
ADD    dispatchVideoCallCancelled(responderId, requestId)
ADD    dispatchVideoCallExpired(requesterId, requestId)
ADD    dispatchMatchmakingRequestCreated(userId, requestId)
```

---

## 13. Cross-Cutting Modifications

| File | Change |
|------|--------|
| `CreditService.java` | remove `grantMatchmakingEscrowRefund` + its ledger `MATCHMAKING_ESCROW_REFUND` path |
| `application.yml` | per §3 |
| `QuartzConfig.java` | per §11 |
| `V70` feature_actions | untouched; V71 adds `VIDEO_CALL`, retires escrow action |
| match lifecycle (unmatch/block handler) | call `videoCallRequestService.cancelActiveForMatch(matchId)` — find the existing match-deactivation point and hook in |
| `matchmaking-admin-api.md` | update: remove message/video sections, document scored candidate response, `reason` on intro creation, stats changes |
| `MatchmakingConstants` | delete dead constants; add `VIDEO_CALL_REQUEST_*` statuses, `VIDEO_CALL` action code |

---

## 14. Tests

### Delete

```text
MatchmakingEscrowServiceTest          (8 tests)
MatchmakingMessageServiceTest         (if exists)
MatchmakingVideoCallServiceTest       (13 tests)
```

### Update

| Test | Changes |
|------|---------|
| `MatchmakingCandidateSearchServiceTest` | no snapshot map; scored results; new DTO ctor; profile-card stubs (already updated for `SupabaseStorageService`) |
| `MatchmakingRequestServiceTest` | no escrow charge/assert; no snapshot write; ctor arg changes (repo deps trimmed) |
| `MatchmakingIntroductionServiceTest` | decisions → direct match; quota settle; CANCELLED no-consume; remove video-call mocks |
| `MatchmakingPreferenceServiceTest` | new alignment fields validation |
| `MatchmakingMatchServiceTest` | unchanged (service kept) |

### New

| Test | Cover |
|------|-------|
| `MatchmakingCompatibilityServiceTest` | each directional dim active/satisfied/inactive; all 5 alignment rules incl. NOT_SURE + same-country edge; forward/reverse/overall math; empty-prefs → 100; reason text only from matched dims |
| `VideoCallRequestServiceTest` | create/accept/decline/cancel transitions; dup-request idempotency; responder/requester guards; join charge on first requester join + charge-race stamp; insufficient credit; expire sweep; block-cancel |
| `VideoCallRequestRepositoryTest` | partial unique index (one active per match); charge-key unique |

Test patterns: keep `@ExtendWith(MockitoExtension.class)` + mocked
`NamedParameterJdbcTemplate` + `ArgumentCaptor<MapSqlParameterSource>`.

---

## 15. Correctness Invariants (updated)

```text
1.  Request is never OPEN and unpaid — charge + insert in one tx.
2.  No double VIDEO_CALL charge — UNIQUE(requester_charge_key) +
    guarded stamp; the loser of a race re-reads and joins free of
    a second charge.
3.  ON_HOLD release never resurrects a cancelled request —
    guarded WHERE status='ON_HOLD'.
4.  Decisions serialize — FOR UPDATE on the introduction row.
5.  Match on mutual interest is atomic — second INTERESTED, match
    INSERT, intro → MATCHED, requests → MATCHED, one tx;
    unique_active_match_pair is the race backstop.
6.  Quota: CANCELLED intros never consume; DECLINED/EXPIRED/
    MATCHED do; exhausted → COMPLETED not OPEN.
7.  Alignment dims score only when BOTH sides answered; no
    invented preference rules beyond §6 of the design.
8.  "Why this introduction" claims only matched dimensions.
9.  One live video-call request per match — partial unique index.
10. No vendor name in domain/DB — channel_name is opaque; tokens
    never persisted.
11. No matchmaking messaging/video tables remain — migration drops
    them; code deletes references (compile-verified).
12. Responder never pays — only requester_charge_key exists.
```

---

## 16. Non-Goals / Deferred

```text
- Provider webhooks for call-end detection (client-end endpoint only)
- Multiple simultaneous calls per match (by design: one at a time)
- Rescheduling an accepted call (decline + new request)
- Video call for unmatched pairs (match-scoped by FK)
- Recording/moderation of calls
- Admin involvement in post-match calls (none — user feature)
- Refund paths of any kind (request fee + call charge non-refundable)
```

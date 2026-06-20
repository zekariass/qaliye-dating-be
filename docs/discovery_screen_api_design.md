# Qaliye — Discovery Screen: Spring Boot API Design Specification

**Version:** 1.0  
**Date:** 2025-06-20  
**Scope:** Discovery profile queue, swipe actions (Like / Pass / Super Like), Rewind, and match creation  
**Source of truth:** `docs/schema.sql` (PostgreSQL + PostGIS), `src/screens/discovery/DiscoverScreen.tsx`, `src/components/discovery/ProfileCard.tsx`, `src/components/discovery/ProfileDetailsSection.tsx`, `src/components/discovery/LocationFilterDropdown.tsx`, `src/components/discovery/CardActionButtons.tsx`

---

## Table of Contents

1. [Endpoint List](#1-endpoint-list)
2. [Request and Response JSON Examples](#2-request-and-response-json-examples)
3. [DTO Definitions](#3-dto-definitions)
4. [Service-Layer Business Rules](#4-service-layer-business-rules)
5. [Database Access and Query Strategy](#5-database-access-and-query-strategy)
6. [Transaction and Locking Strategy](#6-transaction-and-locking-strategy)
7. [Error Codes and Messages](#7-error-codes-and-messages)
8. [Pagination and Cursor Design](#8-pagination-and-cursor-design)
9. [Discovery Filtering and Ranking Algorithm](#9-discovery-filtering-and-ranking-algorithm)
10. [Test Cases](#10-test-cases)

---

## 1. Endpoint List

| # | Method | Path | Description | Auth |
|---|--------|------|-------------|------|
| 1 | `GET` | `/api/v1/discovery/profiles` | Fetch next batch of discovery cards | Required |
| 2 | `POST` | `/api/v1/discovery/actions/like` | Record a Like on a profile | Required |
| 3 | `POST` | `/api/v1/discovery/actions/pass` | Record a Pass on a profile | Required |
| 4 | `POST` | `/api/v1/discovery/actions/superlike` | Record a Super Like on a profile | Required |
| 5 | `POST` | `/api/v1/discovery/actions/rewind` | Undo the most recent eligible swipe | Required |

All endpoints require a valid Supabase JWT Bearer token in the `Authorization` header. The authenticated user's `sub` claim maps to `app_users.id`.

---

## 2. Request and Response JSON Examples

### 2.1 GET `/api/v1/discovery/profiles`

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `location_filter` | `string` | No | `NEARBY` | One of `NEARBY`, `ETHIOPIA`, `ERITREA`, `DIASPORA` |
| `cursor` | `string` | No | — | Opaque cursor from previous response for pagination |

**Request (no body):**
```
GET /api/v1/discovery/profiles?location_filter=NEARBY&cursor=eyJ0eXBlIjoiT0ZGU0VUIiwidmFsdWUiOjB9
Authorization: Bearer <supabase_jwt>
```

**Response `200 OK`:**
```json
{
  "profiles": [
    {
      "user_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "display_name": "Mekdes",
      "age": 26,
      "gender": "FEMALE",
      "bio": "I enjoy quiet evenings, traditional food, and deep conversations.",
      "residency_type": "ETHIOPIA",
      "city": "Addis Ababa",
      "region": "Addis Ababa",
      "country_name": "Ethiopia",
      "distance_km": 3,
      "is_verified": true,
      "relationship_intention": "SERIOUS_RELATIONSHIP",
      "height_cm": 162,
      "ethnicity": "Amhara",
      "nationality": "Ethiopian",
      "religion": "Orthodox Christian",
      "education_level": "Master's Degree",
      "occupation": "Nurse",
      "marital_status": "Never Married",
      "has_children": false,
      "wants_children": true,
      "smoking": false,
      "drinking": false,
      "photos": [
        {
          "photo_id": "f1a2b3c4-d5e6-7890-abcd-ef1234567891",
          "signed_url": "https://supabase-project.supabase.co/storage/v1/object/sign/profile-photos/...",
          "photo_order": 0,
          "is_primary": true,
          "expires_at": "2025-06-20T13:00:00Z"
        },
        {
          "photo_id": "f1a2b3c4-d5e6-7890-abcd-ef1234567892",
          "signed_url": "https://supabase-project.supabase.co/storage/v1/object/sign/profile-photos/...",
          "photo_order": 1,
          "is_primary": false,
          "expires_at": "2025-06-20T13:00:00Z"
        }
      ],
      "prompt_answers": [
        {
          "prompt_text": "My ideal weekend looks like",
          "answer_text": "Cooking injera together and watching old Ethiopian movies"
        }
      ],
      "is_boosted": false,
      "discovery_score": 0.87
    }
  ],
  "next_cursor": "eyJ0eXBlIjoiT0ZGU0VUIiwidmFsdWUiOjEwfQ==",
  "has_more": true,
  "total_eligible": 42,
  "location_filter": "NEARBY",
  "batch_size": 10
}
```

**Response `200 OK` — empty queue:**
```json
{
  "profiles": [],
  "next_cursor": null,
  "has_more": false,
  "total_eligible": 0,
  "location_filter": "NEARBY",
  "batch_size": 0
}
```

---

### 2.2 POST `/api/v1/discovery/actions/like`

**Request:**
```json
{
  "target_user_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "client_action_id": "c9d8e7f6-a5b4-3210-fedc-ba9876543210"
}
```

**Response `200 OK` — like recorded, no match:**
```json
{
  "action_id": "d1e2f3a4-b5c6-7890-abcd-ef1234567890",
  "action_type": "LIKE",
  "status": "ACTIVE",
  "is_match": false,
  "match": null,
  "daily_likes_remaining": 47,
  "created_at": "2025-06-20T12:00:00Z"
}
```

**Response `200 OK` — mutual like, match created:**
```json
{
  "action_id": "d1e2f3a4-b5c6-7890-abcd-ef1234567890",
  "action_type": "LIKE",
  "status": "ACTIVE",
  "is_match": true,
  "match": {
    "match_id": "m1a2b3c4-d5e6-7890-abcd-ef1234567890",
    "matched_at": "2025-06-20T12:00:00Z",
    "rewind_eligible_until": "2025-06-20T12:10:00Z",
    "other_user": {
      "user_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "display_name": "Mekdes",
      "primary_photo_url": "https://supabase-project.supabase.co/storage/v1/object/sign/..."
    }
  },
  "daily_likes_remaining": 47,
  "created_at": "2025-06-20T12:00:00Z"
}
```

**Response `200 OK` — idempotent replay (client_action_id already exists):**
```json
{
  "action_id": "d1e2f3a4-b5c6-7890-abcd-ef1234567890",
  "action_type": "LIKE",
  "status": "ACTIVE",
  "is_match": false,
  "match": null,
  "daily_likes_remaining": 47,
  "created_at": "2025-06-20T12:00:00Z",
  "_idempotent": true
}
```

---

### 2.3 POST `/api/v1/discovery/actions/pass`

**Request:**
```json
{
  "target_user_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "client_action_id": "c9d8e7f6-a5b4-3210-fedc-ba9876543211"
}
```

**Response `200 OK`:**
```json
{
  "action_id": "e2f3a4b5-c6d7-8901-bcde-f01234567891",
  "action_type": "PASS",
  "status": "ACTIVE",
  "is_match": false,
  "match": null,
  "created_at": "2025-06-20T12:01:00Z"
}
```

> Pass does not consume daily limits, does not trigger match checks, and always succeeds (subject only to target eligibility and block checks).

---

### 2.4 POST `/api/v1/discovery/actions/superlike`

**Request:**
```json
{
  "target_user_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "client_action_id": "c9d8e7f6-a5b4-3210-fedc-ba9876543212"
}
```

**Response `200 OK`:**
```json
{
  "action_id": "f3a4b5c6-d7e8-9012-cdef-012345678902",
  "action_type": "SUPERLIKE",
  "status": "ACTIVE",
  "is_match": false,
  "match": null,
  "daily_super_likes_remaining": 0,
  "super_like_credits_remaining": 2,
  "created_at": "2025-06-20T12:02:00Z"
}
```

---

### 2.5 POST `/api/v1/discovery/actions/rewind`

**Request body:** _(empty — rewind always targets the most recent eligible action)_
```json
{}
```

**Response `200 OK` — rewind succeeded:**
```json
{
  "reversed_action_id": "d1e2f3a4-b5c6-7890-abcd-ef1234567890",
  "reversed_action_type": "LIKE",
  "reversed_target_user_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "match_cancelled": false,
  "match_id": null,
  "daily_rewinds_remaining": 0,
  "restored_profile": {
    "user_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "display_name": "Mekdes",
    "age": 26,
    "gender": "FEMALE",
    "bio": "I enjoy quiet evenings, traditional food, and deep conversations.",
    "residency_type": "ETHIOPIA",
    "city": "Addis Ababa",
    "region": "Addis Ababa",
    "country_name": "Ethiopia",
    "distance_km": 3,
    "is_verified": true,
    "relationship_intention": "SERIOUS_RELATIONSHIP",
    "height_cm": 162,
    "ethnicity": "Amhara",
    "nationality": "Ethiopian",
    "religion": "Orthodox Christian",
    "education_level": "Master's Degree",
    "occupation": "Nurse",
    "marital_status": "Never Married",
    "has_children": false,
    "wants_children": true,
    "smoking": false,
    "drinking": false,
    "photos": [
      {
        "photo_id": "f1a2b3c4-d5e6-7890-abcd-ef1234567891",
        "signed_url": "https://supabase-project.supabase.co/storage/v1/object/sign/...",
        "photo_order": 0,
        "is_primary": true,
        "expires_at": "2025-06-20T13:00:00Z"
      }
    ],
    "prompt_answers": [],
    "is_boosted": false,
    "discovery_score": 0.87
  },
  "reversed_at": "2025-06-20T12:05:00Z"
}
```

**Response `200 OK` — rewind cancelled a fresh match:**
```json
{
  "reversed_action_id": "d1e2f3a4-b5c6-7890-abcd-ef1234567890",
  "reversed_action_type": "LIKE",
  "reversed_target_user_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "match_cancelled": true,
  "match_id": "m1a2b3c4-d5e6-7890-abcd-ef1234567890",
  "daily_rewinds_remaining": 0,
  "restored_profile": { "..." : "..." },
  "reversed_at": "2025-06-20T12:05:00Z"
}
```

---

## 3. DTO Definitions

### 3.1 Discovery Profile Card

```java
/** Returned in the profiles array of GET /api/v1/discovery/profiles
 *  and in the restored_profile field of rewind responses. */
public record DiscoveryProfileDto(
    UUID userId,
    String displayName,
    int age,                          // calculated server-side from date_of_birth
    String gender,                    // "MALE" | "FEMALE"
    @Nullable String bio,
    String residencyType,             // "ETHIOPIA" | "ERITREA" | "DIASPORA"
    String city,
    @Nullable String region,
    String countryName,
    @Nullable Integer distanceKm,     // null when location_filter != NEARBY
    boolean isVerified,
    String relationshipIntention,
    @Nullable Integer heightCm,
    @Nullable String ethnicity,
    @Nullable String nationality,
    @Nullable String religion,
    @Nullable String educationLevel,
    @Nullable String occupation,
    @Nullable String maritalStatus,
    boolean hasChildren,
    @Nullable Boolean wantsChildren,
    boolean smoking,
    boolean drinking,
    List<DiscoveryPhotoDto> photos,
    List<PromptAnswerDto> promptAnswers,
    boolean isBoosted,
    double discoveryScore
) {}

public record DiscoveryPhotoDto(
    UUID photoId,
    String signedUrl,           // short-lived Supabase Storage signed URL (1 hour TTL)
    int photoOrder,
    boolean isPrimary,
    Instant expiresAt
) {}

public record PromptAnswerDto(
    String promptText,          // localised to the requesting user's preferred_language
    String answerText
) {}
```

### 3.2 Discovery Profiles Response

```java
public record DiscoveryProfilesResponse(
    List<DiscoveryProfileDto> profiles,
    @Nullable String nextCursor,
    boolean hasMore,
    int totalEligible,
    String locationFilter,
    int batchSize
) {}
```

### 3.3 Swipe Action Request (shared by Like, Pass, SuperLike)

```java
public record SwipeActionRequest(
    @NotNull UUID targetUserId,
    @NotNull UUID clientActionId   // device-generated v4 UUID for idempotency
) {}
```

### 3.4 Swipe Action Response

```java
public record SwipeActionResponse(
    UUID actionId,
    String actionType,             // "LIKE" | "PASS" | "SUPERLIKE"
    String status,                 // "ACTIVE"
    boolean isMatch,
    @Nullable MatchSummaryDto match,
    @Nullable Integer dailyLikesRemaining,
    @Nullable Integer dailySuperLikesRemaining,
    @Nullable Integer superLikeCreditsRemaining,
    Instant createdAt,
    boolean idempotent             // true if this was a duplicate request
) {}

public record MatchSummaryDto(
    UUID matchId,
    Instant matchedAt,
    Instant rewindEligibleUntil,
    MatchedUserSummaryDto otherUser
) {}

public record MatchedUserSummaryDto(
    UUID userId,
    String displayName,
    String primaryPhotoUrl
) {}
```

### 3.5 Rewind Request / Response

```java
// Request body is empty (or an empty JSON object)
public record RewindRequest() {}

public record RewindResponse(
    UUID reversedActionId,
    String reversedActionType,
    UUID reversedTargetUserId,
    boolean matchCancelled,
    @Nullable UUID matchId,
    int dailyRewindsRemaining,
    DiscoveryProfileDto restoredProfile,
    Instant reversedAt
) {}
```

### 3.6 Plan Entitlement (Internal service DTO, not exposed in API)

```java
public record UserPlanEntitlement(
    UUID userId,
    String planCode,
    boolean isPaid,
    @Nullable Integer dailyLikesLimit,       // null = unlimited
    @Nullable Integer dailySuperLikesLimit,  // null = unlimited
    @Nullable Integer dailyRewindsLimit,     // null = unlimited
    int superLikeCredits,                    // from user_entitlement_ledger
    int rewindCredits
) {}
```

---

## 4. Service-Layer Business Rules

### 4.1 Actor Eligibility (applied to every action endpoint)

Before processing any swipe action, the service must confirm all of the following:

1. **Actor account:** `app_users.status = 'ACTIVE'` and `deleted_at IS NULL`.
2. **Actor profile:** `profiles.is_visible = TRUE` and `profiles.is_onboarded = TRUE`.
3. **Actor preferences:** `discovery_preferences` row exists.
4. **Actor address:** `app_users.address_id IS NOT NULL`.

If any condition fails, return `403 DISCOVERY_ACTOR_INELIGIBLE`.

### 4.2 Target Eligibility

The target profile must satisfy:

1. `app_users.status = 'ACTIVE'` and `deleted_at IS NULL`.
2. `profiles.is_visible = TRUE` and `profiles.is_onboarded = TRUE`.
3. At least one `profile_photos` row with `is_primary = TRUE`, `moderation_status = 'APPROVED'`, and `deleted_at IS NULL`.
4. No active block from target → actor or actor → target (`user_blocks.status = 'ACTIVE'`).
5. No existing **active** `user_discovery_actions` from actor → target (i.e., `status = 'ACTIVE'` for the same pair). This enforces one active action per pair.
6. No `matches` row with `status = 'ACTIVE'` for this pair.

If any condition fails, return `422 DISCOVERY_TARGET_INELIGIBLE`.

### 4.3 Like Action Rules

```
Service method: DiscoveryService.recordLike(actorId, targetUserId, clientActionId)
```

1. Verify actor and target eligibility (sections 4.1, 4.2).
2. **Idempotency check:** Query `user_discovery_actions` for `(actor_user_id = actorId, client_action_id = clientActionId)`.
    - If found: return the existing record with `idempotent = true`. Do not decrement daily limits again.
3. **Duplicate active action check:** Query `unique_active_discovery_action_per_pair` index.
    - If an active Like or Super Like for this actor → target already exists: return `409 DUPLICATE_ACTIVE_ACTION`.
4. **Daily limit check:** Load or upsert `user_daily_limits` for `(actorId, TODAY_UTC)`. Lock the row with `SELECT ... FOR UPDATE`.
    - Determine actor's plan via `user_subscriptions` (ACTIVE/PENDING_VERIFICATION) → `subscription_plan_limits` (DAILY_LIKES). Fall back to the GLOBAL FREE plan.
    - If `likes_used >= limit_value` (and limit is not NULL): return `429 DAILY_LIKE_LIMIT_EXCEEDED`.
5. **Insert action:** `INSERT INTO user_discovery_actions (actor_user_id, target_user_id, action_type='LIKE', status='ACTIVE', client_action_id, ...)`.
6. **Increment daily counter:** `UPDATE user_daily_limits SET likes_used = likes_used + 1`.
7. **Mutual like check:** Query for an active Like or Super Like from target → actor.
    - If found: create a match (section 4.6).
8. Return response.

### 4.4 Pass Action Rules

```
Service method: DiscoveryService.recordPass(actorId, targetUserId, clientActionId)
```

1. Verify actor eligibility (section 4.1). Target need only exist and not be blocked.
2. Idempotency check: same as Like (step 2 above).
3. Duplicate active action check: same as Like (step 3 above).
4. **No daily limit applied.** Passes are unlimited for all plan tiers.
5. Insert action: `action_type = 'PASS'`.
6. **No mutual check.** Pass never creates a match.
7. Return response.

### 4.5 Super Like Action Rules

```
Service method: DiscoveryService.recordSuperLike(actorId, targetUserId, clientActionId)
```

1. Verify actor and target eligibility.
2. Idempotency check.
3. Duplicate active action check.
4. **Daily super-like limit check:** Same pattern as Like, using `DAILY_SUPERLIKES` limit and `super_likes_used` counter.
    - If `super_likes_used >= limit_value`: check `user_entitlement_ledger` for available `SUPERLIKE_CREDIT` balance (`SUM(quantity_delta) > 0`).
        - If credits exist: consume one credit by inserting a `CONSUMPTION` row into the ledger. Proceed.
        - If no credits: return `429 DAILY_SUPERLIKE_LIMIT_EXCEEDED`.
    - If within daily limit: increment `super_likes_used`. No credit consumed.
5. Insert action: `action_type = 'SUPERLIKE'`.
6. **Mutual like check:** Query for an active Like or Super Like from target → actor. If found: create a match.
7. Insert `user_entitlement_ledger` credit consumption row (if applicable) linked via `related_discovery_action_id`.
8. Return response including `superLikeCreditsRemaining`.

### 4.6 Match Creation

Invoked when a Like or Super Like from actor creates mutual interest with an existing Like or Super Like from target.

```
Service method: DiscoveryService.createMatchTransactionally(actorActionId, existingTargetActionId)
```

1. Canonical pair ordering: `user_one_id = MIN(actorId, targetId)`, `user_two_id = MAX(actorId, targetId)`.
2. **Active match guard:** Check `unique_active_match_pair` index. If an active match exists, skip creation (should not normally occur given prior eligibility checks).
3. Set `rewind_eligible_until = NOW() + INTERVAL '10 minutes'` (configurable).
4. Insert into `matches`:
   ```sql
   INSERT INTO matches (
     user_one_id, user_two_id,
     user_one_like_action_id, user_two_like_action_id,
     created_by_action_id,
     status, rewind_eligible_until, matched_at
   ) VALUES (...)
   ```
5. The `validate_match_like_actions` database trigger validates both actions are ACTIVE, belong to the correct pair, and are LIKE/SUPERLIKE type, and that no block exists. This fires as part of the INSERT.
6. Return the new `matches` row.

### 4.7 Rewind Rules

```
Service method: DiscoveryService.rewind(actorId)
```

1. Verify actor eligibility.
2. **Daily rewind limit check:** Load/upsert `user_daily_limits` for `(actorId, TODAY_UTC)`. Lock with `SELECT ... FOR UPDATE`.
    - Determine plan limit for `DAILY_REWINDS`.
    - If `rewinds_used >= limit_value`: check `user_entitlement_ledger` for `REWIND_CREDIT` balance.
        - If credits: consume one. Proceed.
        - Else: return `429 DAILY_REWIND_LIMIT_EXCEEDED`.
3. **Find the most recent eligible action:**
   ```sql
   SELECT * FROM user_discovery_actions
   WHERE actor_user_id = :actorId
     AND status = 'ACTIVE'
   ORDER BY created_at DESC
   LIMIT 1
   FOR UPDATE
   ```
    - If no eligible action: return `404 NO_REWINDABLE_ACTION`.
4. **If the action is LIKE or SUPERLIKE**, check whether it created a match:
   ```sql
   SELECT * FROM matches
   WHERE created_by_action_id = :actionId
     AND status = 'ACTIVE'
   ```
    - **If active match found (fresh match rewind):**
      a. Verify `rewind_eligible_until > NOW()`. If expired: return `422 REWIND_MATCH_GRACE_PERIOD_EXPIRED`.
      b. Verify `first_message_at IS NULL`. If messages have been sent: return `422 REWIND_MATCH_HAS_MESSAGES`.
      c. Update match: `status = 'ENDED'`, `end_reason = 'CANCELLED_BY_REWIND'`, `ended_by_user_id = actorId`, `ended_at = NOW()`.
      d. Set `match_cancelled = true` in response.
      e. The `validate_active_match_action_states` trigger (DEFERRED) will validate at commit. The match is ENDED before the action is REVERSED, so the constraint is satisfied.
    - **If no active match (or action is PASS):** Proceed directly.
5. **Mark action as REVERSED:**
   ```sql
   UPDATE user_discovery_actions
   SET status = 'REVERSED',
       reversed_at = NOW(),
       reversed_reason = 'USER_REWIND'
   WHERE id = :actionId
   ```
   The `enforce_discovery_action_immutability` trigger allows only ACTIVE → REVERSED.
6. **Increment rewind counter:** `UPDATE user_daily_limits SET rewinds_used = rewinds_used + 1`.
7. **Load restored profile** using the same projection as `GET /api/v1/discovery/profiles` (generate fresh signed URLs). The rewound target profile becomes eligible again in the discovery queue because its action is now REVERSED.
8. Return `RewindResponse` including the full `DiscoveryProfileDto` for the restored card.

**Key invariant:** Rewind only reverses the *actor's* action. The other user's prior Like or Super Like (if any) remains ACTIVE. It does **not** constitute an unmatch in the sense of the `USER_UNMATCH` flow.

### 4.8 Relationship Intention Value Mapping

The database stores `relationship_intention` as an uppercase enum value. The API response returns it as stored. The mobile client is responsible for localised display strings.

| DB Value | Mobile Display (en) |
|----------|---------------------|
| `MARRIAGE` | Marriage |
| `SERIOUS_RELATIONSHIP` | Serious relationship |
| `LONG_TERM` | Long-term |
| `FRIENDSHIP` | Friendship |
| `NOT_SURE_YET` | Open to dating / Not sure yet |

### 4.9 Signed URL Generation

- All photos are stored in private Supabase Storage. **No public URLs exist.**
- Spring Boot calls the Supabase Storage REST API (`POST /storage/v1/object/sign/{bucket}/{path}`) using the `SUPABASE_SERVICE_ROLE_KEY` to generate signed URLs.
- TTL: **3600 seconds** (1 hour). The `expires_at` field in the response allows the client to refresh before expiry.
- Signed URLs are generated per-request and are never cached or persisted.
- **Raw `storage_path` values are never returned to the client.**

### 4.10 Age Calculation

Age is calculated server-side using the `calculate_age(date_of_birth)` database function (or its Java equivalent). The client never sends or receives `date_of_birth`. The `age` field in the response is an integer (years).

```sql
SELECT EXTRACT(YEAR FROM age(CURRENT_DATE, p.date_of_birth))::INTEGER AS age
```

### 4.11 Distance Calculation and Rounding

- The backend computes straight-line distance using PostGIS geography coordinates:
  ```sql
  ST_Distance(actor_address.coords::geography, candidate_address.coords::geography) / 1000.0
  ```
- **Never expose raw latitude/longitude to the client.**
- The returned `distance_km` is rounded to the nearest whole kilometre using `ROUND()`.
- Minimum returned value: `1` km (distances < 500 m are reported as 1 km to prevent triangulation).
- For non-NEARBY location filters, `distance_km` is `null`.

### 4.12 Boost Prioritisation

Candidates with an active boost (`active_boosts` row where `NOW() BETWEEN started_at AND expires_at`) are assigned a boost multiplier in the ranking score. The `is_boosted` field in the profile DTO signals this to the mobile client for future UI rendering.

---

## 5. Database Access and Query Strategy

### 5.1 Core Discovery Query

The discovery query uses a single parameterised SQL statement combining all eligibility filters. Spring Boot executes it via JDBC/JdbcTemplate or JOOQ. JPA/Hibernate is **not** used for this query due to the PostGIS distance function and complex exclusion logic.

```sql
-- Parameters:
--   :actorId         UUID  — authenticated user
--   :targetGender    TEXT  — from discovery_preferences.interested_in_gender
--   :minAge          INT   — from discovery_preferences.min_age
--   :maxAge          INT   — from discovery_preferences.max_age
--   :residencyTypes  TEXT[]— from discovery_preferences.preferred_residency_types
--                            (may be overridden by location_filter, see section 5.2)
--   :actorCoords     GEOGRAPHY — actor's address coords (for NEARBY only)
--   :maxDistanceKm   INT   — from discovery_preferences.max_distance_km (NEARBY only)
--   :showVerifiedOnly BOOLEAN — from discovery_preferences.show_verified_only
--   :limit           INT   — always 10
--   :offset          INT   — from cursor

WITH excluded_targets AS (
    -- All user_ids that must not appear in discovery results
    SELECT target_user_id AS user_id
    FROM user_discovery_actions
    WHERE actor_user_id = :actorId
      AND status = 'ACTIVE'   -- only active actions exclude; REVERSED actions re-qualify

    UNION

    -- Active matches (both directions)
    SELECT CASE
        WHEN user_one_id = :actorId THEN user_two_id
        ELSE user_one_id
    END AS user_id
    FROM matches
    WHERE (user_one_id = :actorId OR user_two_id = :actorId)
      AND status = 'ACTIVE'

    UNION

    -- Blocks in either direction
    SELECT blocked_user_id AS user_id
    FROM user_blocks
    WHERE blocker_user_id = :actorId AND status = 'ACTIVE'

    UNION

    SELECT blocker_user_id AS user_id
    FROM user_blocks
    WHERE blocked_user_id = :actorId AND status = 'ACTIVE'
),
candidate_distances AS (
    SELECT
        p.user_id,
        p.display_name,
        p.gender,
        p.date_of_birth,
        calculate_age(p.date_of_birth)                              AS age,
        p.bio,
        p.residency_type,
        p.is_verified,
        p.relationship_intention,
        p.height_cm,
        p.ethnicity,
        p.nationality,
        p.religion,
        p.education_level,
        p.occupation,
        p.marital_status,
        p.has_children,
        p.wants_children,
        p.smoking,
        p.drinking,
        a.city,
        a.region,
        a.country_name,
        CASE
            WHEN :locationFilter = 'NEARBY' THEN
                ROUND(
                    ST_Distance(:actorCoords::geography, a.coords::geography) / 1000.0
                )::INTEGER
            ELSE NULL
        END                                                          AS distance_km,
        EXISTS (
            SELECT 1 FROM active_boosts ab
            WHERE ab.user_id = p.user_id
              AND NOW() BETWEEN ab.started_at AND ab.expires_at
        )                                                            AS is_boosted
    FROM profiles p
    JOIN app_users au    ON au.id = p.user_id
    JOIN addresses a     ON a.id  = au.address_id
    WHERE p.is_visible      = TRUE
      AND p.is_onboarded    = TRUE
      AND au.status         = 'ACTIVE'
      AND au.deleted_at     IS NULL
      AND p.user_id        <> :actorId
      AND p.gender          = :targetGender
      AND p.user_id        NOT IN (SELECT user_id FROM excluded_targets)
      -- Age filter
      AND calculate_age(p.date_of_birth) BETWEEN :minAge AND :maxAge
      -- Verified-only filter
      AND (:showVerifiedOnly = FALSE OR p.is_verified = TRUE)
      -- Residency / location filter (see section 5.2)
      AND p.residency_type  = ANY(:residencyTypes::TEXT[])
      -- Approved primary photo guard
      AND EXISTS (
          SELECT 1 FROM profile_photos pp
          WHERE pp.user_id            = p.user_id
            AND pp.is_primary         = TRUE
            AND pp.moderation_status  = 'APPROVED'
            AND pp.deleted_at         IS NULL
      )
)
SELECT
    cd.*,
    -- Ranking score: boosted profiles first, then recency/activity
    (
        CASE WHEN cd.is_boosted THEN 1000.0 ELSE 0.0 END
        + (EXTRACT(EPOCH FROM au.last_active_at) / 1e9)
        -- NEARBY: penalise by distance
        + CASE
            WHEN :locationFilter = 'NEARBY' AND cd.distance_km IS NOT NULL
            THEN (1.0 - LEAST(cd.distance_km::float / :maxDistanceKm, 1.0)) * 200.0
            ELSE 0.0
          END
    )                                                                AS discovery_score
FROM candidate_distances cd
JOIN app_users au ON au.id = cd.user_id
-- NEARBY distance gate: exclude profiles beyond max_distance_km
-- (unless open_to_long_distance is TRUE, in which case no distance gate applies)
WHERE (
    :locationFilter <> 'NEARBY'
    OR :openToLongDistance = TRUE
    OR (
        ST_DWithin(
            :actorCoords::geography,
            (SELECT coords FROM addresses WHERE id = (SELECT address_id FROM app_users WHERE id = cd.user_id)),
            :maxDistanceKm * 1000.0
        )
    )
)
ORDER BY discovery_score DESC, cd.user_id ASC   -- secondary sort by user_id for stable pagination
LIMIT :limit
OFFSET :offset;
```

> **Performance note:** The query relies on `idx_profiles_discovery_bundle` (gender, residency_type, date_of_birth, partial on is_visible+is_onboarded) and `idx_addresses_coords` (GIST on coords). The `excluded_targets` CTE benefits from `idx_discovery_actions_actor_rewind_stack` and `unique_active_discovery_action_per_pair`.

### 5.2 Location Filter → Residency Type Mapping

The `location_filter` query parameter is translated server-side to a `residencyTypes` array and a distance gate:

| `location_filter` | `residencyTypes` used in query | Distance gate |
|---|---|---|
| `NEARBY` | All three values in `discovery_preferences.preferred_residency_types` | `ST_DWithin` up to `max_distance_km` (unless `open_to_long_distance = true`) |
| `ETHIOPIA` | `['ETHIOPIA']` | None |
| `ERITREA` | `['ERITREA']` | None |
| `DIASPORA` | `['DIASPORA']` | None |

When `location_filter` is `ETHIOPIA`, `ERITREA`, or `DIASPORA`, the actor's `preferred_residency_types` constraint is overridden by the explicit filter. The selected filter must also be compatible with the user's `preferred_residency_types`; if it is not, the server returns an empty result (no error) because the filter is a valid user intent.

### 5.3 Photo Retrieval

After the discovery query returns candidate `user_id` values, photos are loaded in a single batched query:

```sql
SELECT
    pp.id,
    pp.user_id,
    pp.storage_bucket,
    pp.storage_path,
    pp.photo_order,
    pp.is_primary
FROM profile_photos pp
WHERE pp.user_id          = ANY(:candidateUserIds::UUID[])
  AND pp.moderation_status = 'APPROVED'
  AND pp.deleted_at        IS NULL
ORDER BY pp.user_id, pp.photo_order ASC;
```

Signed URLs are generated in a subsequent batch call to Supabase Storage. The backend maps photos back to their profile by `user_id`.

### 5.4 Prompt Answer Retrieval

```sql
SELECT
    ppa.user_id,
    COALESCE(ppt.prompt_text, pp.prompt_text) AS prompt_text,
    ppa.answer_text
FROM profile_prompt_answers ppa
JOIN profile_prompts pp          ON pp.id = ppa.prompt_id AND pp.is_active = TRUE
LEFT JOIN profile_prompt_translations ppt
    ON ppt.prompt_id = ppa.prompt_id
   AND ppt.locale    = :actorPreferredLanguage
WHERE ppa.user_id = ANY(:candidateUserIds::UUID[])
ORDER BY ppa.user_id, pp.display_order ASC;
```

### 5.5 Action Recording Queries

```sql
-- Idempotency check
SELECT id, action_type, status, created_at
FROM user_discovery_actions
WHERE actor_user_id    = :actorId
  AND client_action_id = :clientActionId;

-- Duplicate active action check
SELECT id FROM user_discovery_actions
WHERE actor_user_id  = :actorId
  AND target_user_id = :targetId
  AND status         = 'ACTIVE';

-- Insert action
INSERT INTO user_discovery_actions
    (id, actor_user_id, target_user_id, action_type, status, client_action_id)
VALUES
    (gen_random_uuid(), :actorId, :targetId, :actionType, 'ACTIVE', :clientActionId)
ON CONFLICT (actor_user_id, client_action_id) DO NOTHING
RETURNING *;

-- Mutual like check (for Like and Super Like)
SELECT id, action_type
FROM user_discovery_actions
WHERE actor_user_id  = :targetId
  AND target_user_id = :actorId
  AND action_type   IN ('LIKE', 'SUPERLIKE')
  AND status         = 'ACTIVE';
```

### 5.6 Daily Limits Upsert

```sql
INSERT INTO user_daily_limits (user_id, limit_date, likes_used, super_likes_used, rewinds_used)
VALUES (:userId, CURRENT_DATE AT TIME ZONE 'UTC', 0, 0, 0)
ON CONFLICT (user_id, limit_date) DO NOTHING;

SELECT * FROM user_daily_limits
WHERE user_id    = :userId
  AND limit_date = CURRENT_DATE AT TIME ZONE 'UTC'
FOR UPDATE;
```

### 5.7 Plan Limit Resolution

```sql
WITH user_plan AS (
    SELECT sp.plan_code, sp.plan_kind, spl.limit_type, spl.limit_value
    FROM user_subscriptions us
    JOIN subscription_plans sp  ON sp.id  = us.plan_id
    JOIN subscription_plan_limits spl ON spl.plan_id = sp.id
    WHERE us.user_id = :userId
      AND us.status IN ('ACTIVE', 'PENDING_VERIFICATION')
      AND sp.is_active = TRUE
    UNION ALL
    -- Fallback to FREE plan (country-specific first, then GLOBAL)
    SELECT sp.plan_code, sp.plan_kind, spl.limit_type, spl.limit_value
    FROM subscription_plans sp
    JOIN subscription_plan_limits spl ON spl.plan_id = sp.id
    WHERE sp.plan_kind = 'FREE'
      AND sp.is_active = TRUE
    ORDER BY
        -- Country-specific first
        CASE WHEN sp.country_code = :userCountryCode THEN 0 ELSE 1 END
    LIMIT 3   -- one row per limit_type from the free plan
)
SELECT limit_type, limit_value
FROM user_plan
WHERE limit_type IN ('DAILY_LIKES', 'DAILY_SUPERLIKES', 'DAILY_REWINDS');
```

### 5.8 Rewind — Find Most Recent Eligible Action

```sql
SELECT uda.*
FROM user_discovery_actions uda
WHERE uda.actor_user_id = :actorId
  AND uda.status        = 'ACTIVE'
ORDER BY uda.created_at DESC
LIMIT 1
FOR UPDATE;
```

Uses `idx_discovery_actions_actor_rewind_stack` (actor_user_id, status, created_at DESC).

### 5.9 Entitlement Balance Query

```sql
SELECT COALESCE(SUM(quantity_delta), 0) AS balance
FROM user_entitlement_ledger
WHERE user_id          = :userId
  AND entitlement_type = :entitlementType   -- 'SUPERLIKE_CREDIT' or 'REWIND_CREDIT'
  AND (expires_at IS NULL OR expires_at > NOW());
```

---

## 6. Transaction and Locking Strategy

### 6.1 Like / Super Like Transaction Boundary

```
BEGIN;

  -- 1. Upsert + lock daily_limits row
  INSERT INTO user_daily_limits ... ON CONFLICT DO NOTHING;
  SELECT ... FROM user_daily_limits WHERE ... FOR UPDATE;

  -- 2. Insert discovery action (ON CONFLICT DO NOTHING for idempotency)
  INSERT INTO user_discovery_actions ...;

  -- 3. Update daily limit counter
  UPDATE user_daily_limits SET likes_used = likes_used + 1 ...;

  -- 4. [Super Like only] Insert entitlement ledger consumption row
  INSERT INTO user_entitlement_ledger ...;

  -- 5. Mutual like check (SELECT ... FOR UPDATE on target's action row)
  SELECT ... FROM user_discovery_actions WHERE actor_user_id = :targetId AND ... FOR UPDATE;

  -- 6. [If mutual] Insert match row
  --    validate_match_like_actions trigger fires and validates HERE
  INSERT INTO matches ...;

COMMIT;
-- DEFERRED trigger validate_active_match_action_states fires at commit.
```

**Isolation level:** `READ COMMITTED` (Postgres default). The `FOR UPDATE` locks prevent concurrent double-likes.

### 6.2 Pass Transaction Boundary

```
BEGIN;
  INSERT INTO user_discovery_actions (action_type = 'PASS') ON CONFLICT DO NOTHING;
COMMIT;
```

Minimal transaction — no limit counters, no mutual check.

### 6.3 Rewind Transaction Boundary

```
BEGIN;

  -- 1. Upsert + lock daily_limits
  INSERT INTO user_daily_limits ... ON CONFLICT DO NOTHING;
  SELECT ... FROM user_daily_limits WHERE ... FOR UPDATE;

  -- 2. Find + lock most recent eligible action
  SELECT * FROM user_discovery_actions WHERE ... FOR UPDATE;

  -- 3. [If Like/SuperLike] Check for active match
  SELECT * FROM matches WHERE created_by_action_id = :actionId FOR UPDATE;

  -- 4. [If fresh match] End match
  UPDATE matches SET status = 'ENDED', end_reason = 'CANCELLED_BY_REWIND', ...;

  -- 5. Reverse action
  UPDATE user_discovery_actions SET status = 'REVERSED', reversed_at = NOW(), ...;

  -- 6. Increment rewind counter
  UPDATE user_daily_limits SET rewinds_used = rewinds_used + 1 ...;

  -- 7. [If rewind credit used] Insert ledger consumption row
  INSERT INTO user_entitlement_ledger ...;

COMMIT;
-- DEFERRED trigger validate_active_match_action_states fires at commit.
-- At commit: match is ENDED and action is REVERSED → constraint is satisfied.
```

### 6.4 Concurrency and Race Conditions

| Race condition | Mitigation |
|---|---|
| Two simultaneous likes from actor | `unique_active_discovery_action_per_pair` partial unique index rejects the duplicate at DB level. The service catches `DataIntegrityViolationException` and returns `409 DUPLICATE_ACTIVE_ACTION`. |
| Actor likes target, target likes actor simultaneously | Both acquire `FOR UPDATE` on the target's existing action row before inserting the match. The second transaction to run the mutual check will see the first match already created via `unique_active_match_pair`. Service must check for existing active match before inserting. |
| Daily limit over-spend | `SELECT ... FOR UPDATE` on `user_daily_limits` serialises all concurrent like/superlike/rewind requests for the same user and UTC date. |
| Double rewind submission | `FOR UPDATE` on the action row ensures only one rewind proceeds. The second concurrent rewind will block, then see the action is already `REVERSED` and return `404 NO_REWINDABLE_ACTION` (or find the next eligible action). |

### 6.5 Deferred Trigger Dependency

The triggers `validate_active_match_action_states` (DEFERRED) and `validate_visible_profile_dependencies` (DEFERRED) fire at transaction commit, not at the individual statement level. The rewind transaction can therefore:
- End the match (`ENDED`) in step 4.
- Reverse the action (`REVERSED`) in step 5.
  And the deferred trigger at commit will see: match is ENDED (not ACTIVE), so no violation.

---

## 7. Error Codes and Messages

All errors follow this envelope:
```json
{
  "error": {
    "code": "DISCOVERY_TARGET_INELIGIBLE",
    "message": "The target profile is not eligible for this action.",
    "details": {}
  }
}
```

| HTTP Status | Error Code | Trigger Condition |
|---|---|---|
| `400` | `INVALID_REQUEST` | Missing or malformed required fields |
| `400` | `INVALID_CURSOR` | Cursor cannot be decoded |
| `401` | `UNAUTHORIZED` | Missing or invalid JWT |
| `403` | `DISCOVERY_ACTOR_INELIGIBLE` | Actor account/profile not eligible |
| `403` | `ACTOR_PROFILE_INCOMPLETE` | Actor has no address or preferences |
| `404` | `NO_REWINDABLE_ACTION` | No ACTIVE action exists to rewind |
| `409` | `DUPLICATE_ACTIVE_ACTION` | An active action already exists for this actor → target pair |
| `422` | `DISCOVERY_TARGET_INELIGIBLE` | Target not visible, not active, blocked, or already actioned |
| `422` | `SELF_ACTION_NOT_ALLOWED` | `targetUserId == actorId` |
| `422` | `REWIND_MATCH_GRACE_PERIOD_EXPIRED` | Match `rewind_eligible_until` has passed |
| `422` | `REWIND_MATCH_HAS_MESSAGES` | Match `first_message_at IS NOT NULL` |
| `422` | `REWIND_ACTION_ALREADY_REVERSED` | Action is already `REVERSED` (stale client state) |
| `429` | `DAILY_LIKE_LIMIT_EXCEEDED` | `likes_used >= plan daily limit` and no fallback |
| `429` | `DAILY_SUPERLIKE_LIMIT_EXCEEDED` | `super_likes_used >= limit` and no `SUPERLIKE_CREDIT` balance |
| `429` | `DAILY_REWIND_LIMIT_EXCEEDED` | `rewinds_used >= limit` and no `REWIND_CREDIT` balance |
| `500` | `INTERNAL_ERROR` | Unexpected server error |

---

## 8. Pagination and Cursor Design

### 8.1 Cursor Format

Cursors are **opaque base64-encoded JSON** objects. The client treats them as strings and never parses them.

Internal structure (never exposed):
```json
{
  "type": "OFFSET",
  "value": 10,
  "location_filter": "NEARBY",
  "generated_at": "2025-06-20T12:00:00Z"
}
```

Encoded:
```
eyJ0eXBlIjoiT0ZGU0VUIiwidmFsdWUiOjEwLCJsb2NhdGlvbl9maWx0ZXIiOiJORUFSQlkiLCJnZW5lcmF0ZWRfYXQiOiIyMDI1LTA2LTIwVDEyOjAwOjAwWiJ9
```

### 8.2 Batch Size and Pagination Rules

- Each response contains exactly **10 profiles** (or fewer when the queue is nearly exhausted).
- The server computes `total_eligible` (a COUNT of qualifying candidates) before applying LIMIT/OFFSET, so the mobile client can display "N profiles remaining" or determine when to stop fetching.
- `has_more` is `true` when `offset + batch_size < total_eligible`.
- When `has_more` is `false`, `next_cursor` is `null`.

### 8.3 Cursor Invalidation

The cursor carries `generated_at`. If the client submits a cursor older than **30 minutes**, the server resets to offset 0 and returns a fresh batch. The response includes a `cursor_reset: true` field in that case.

Cursors are **not** session-bound. The same cursor can be submitted from any device.

### 8.4 Stable Ordering

The ORDER BY clause includes `user_id ASC` as a tiebreaker after `discovery_score DESC`. This ensures that a repeated request with the same cursor returns the same profiles (assuming no changes to the pool). New swipes shift the excluded set, so subsequent pages skip already-actioned profiles correctly even without a cursor.

---

## 9. Discovery Filtering and Ranking Algorithm

### 9.1 Mandatory Filters (Hard Exclusions)

Applied in the WHERE clause. A candidate who fails any of these is never returned.

| Filter | Logic |
|---|---|
| **Self-exclusion** | `candidate.user_id <> actorId` |
| **Account status** | `app_users.status = 'ACTIVE'` and `deleted_at IS NULL` |
| **Profile visibility** | `profiles.is_visible = TRUE` and `profiles.is_onboarded = TRUE` |
| **Approved primary photo** | EXISTS approved non-deleted primary photo |
| **Gender preference** | `profiles.gender = discovery_preferences.interested_in_gender` |
| **Age range** | `calculate_age(date_of_birth) BETWEEN min_age AND max_age` |
| **Active action exclusion** | No ACTIVE discovery action from actor → candidate |
| **Active match exclusion** | No ACTIVE match between actor and candidate |
| **Block exclusion** | No ACTIVE block in either direction |
| **Residency / location filter** | As per section 5.2 |
| **Verified-only** | `profiles.is_verified = TRUE` when `show_verified_only = TRUE` |
| **Distance gate (NEARBY)** | `ST_DWithin(actorCoords, candidateCoords, max_distance_km * 1000)` unless `open_to_long_distance = TRUE` |

### 9.2 Ranking Score

After filtering, candidates are ordered by `discovery_score DESC`. The score components:

| Component | Weight | Description |
|---|---|---|
| **Active boost** | +1000.0 | Binary: candidate has an active `active_boosts` row |
| **Recent activity** | `epoch(last_active_at) / 1e9` | Continuously active users appear higher; scales with Unix epoch fraction |
| **Distance penalty (NEARBY only)** | `(1 - min(km / max_km, 1)) × 200` | Closer candidates score higher; profiles at or beyond `max_distance_km` score 0 on this component |

The `discovery_score` value is exposed in the response DTO for future client-side debugging or transparency features. It is never user-visible in the UI.

### 9.3 Long-Distance Rules

- `open_to_long_distance = TRUE` in `discovery_preferences` disables the `ST_DWithin` distance gate entirely for the NEARBY filter. All profiles matching other criteria are included regardless of distance.
- `open_to_relocation = TRUE` is stored but does not affect filtering logic in v1 (reserved for future matching quality signals).

### 9.4 Discovery Mode

`discovery_preferences.discovery_mode` is stored but not enforced in the v1 discovery queue filter. It is reserved for:
- `INCOGNITO`: hide the actor from other users' discovery queues (future).
- `GLOBAL`: override residency filters (future).

### 9.5 Boosted Profile Handling

Boosted profiles (active `active_boosts` row) receive a +1000 score boost, effectively placing them first in every page. Within the boosted tier, profiles are ranked by recency. The `is_boosted` flag is returned in the response for future UI differentiation.

### 9.6 Profile Re-eligibility After Rewind

A profile that was swiped becomes eligible again in the queue **only if** its discovery action has been reversed (`status = 'REVERSED'`). The exclusion query (`excluded_targets` CTE) filters only `status = 'ACTIVE'` actions. A reversed action does not exclude the target profile from future discovery batches.

---

## 10. Test Cases

### 10.1 Discovery Filtering

| ID | Scenario | Expected Result |
|---|---|---|
| DQ-01 | Actor fetches queue; actor's own profile is a candidate | Actor's profile excluded |
| DQ-02 | Candidate has `is_visible = FALSE` | Excluded |
| DQ-03 | Candidate has `is_onboarded = FALSE` | Excluded |
| DQ-04 | Candidate's `app_users.status = 'SUSPENDED'` | Excluded |
| DQ-05 | Candidate has no approved primary photo | Excluded |
| DQ-06 | Candidate has `app_users.deleted_at` set | Excluded |
| DQ-07 | Candidate's gender does not match `interested_in_gender` | Excluded |
| DQ-08 | Candidate's age is below `min_age` | Excluded |
| DQ-09 | Candidate's age is above `max_age` | Excluded |
| DQ-10 | `show_verified_only = TRUE`; candidate is not verified | Excluded |
| DQ-11 | `show_verified_only = TRUE`; candidate is verified | Included |
| DQ-12 | `location_filter = NEARBY`; candidate is beyond `max_distance_km`, `open_to_long_distance = FALSE` | Excluded |
| DQ-13 | `location_filter = NEARBY`; candidate is beyond `max_distance_km`, `open_to_long_distance = TRUE` | Included |
| DQ-14 | `location_filter = ETHIOPIA`; candidate has `residency_type = 'DIASPORA'` | Excluded |
| DQ-15 | `location_filter = DIASPORA`; candidate has `residency_type = 'DIASPORA'` | Included |
| DQ-16 | Actor has active block on candidate | Excluded |
| DQ-17 | Candidate has active block on actor | Excluded |
| DQ-18 | Actor has an ACTIVE PASS on candidate | Excluded |
| DQ-19 | Actor has an ACTIVE LIKE on candidate | Excluded |
| DQ-20 | Actor had a REVERSED LIKE on candidate (after rewind) | Included |
| DQ-21 | Actor and candidate have ACTIVE match | Excluded |
| DQ-22 | Actor and candidate have ENDED match (unmatch) | Included (no active match) |
| DQ-23 | Candidate has active boost | Returned first in batch |
| DQ-24 | Distance response: candidate at 0.4 km | Returned as 1 km (minimum floor) |
| DQ-25 | Age response: candidate DOB is exactly 18 years ago today | Returned with age = 18 |

### 10.2 Profile Data Correctness

| ID | Scenario | Expected Result |
|---|---|---|
| PD-01 | Age calculated from `date_of_birth` spanning a birthday | Correct year count using `age()` function |
| PD-02 | Response includes raw latitude/longitude | Never — assert `lat`, `lng`, `coords` fields are absent |
| PD-03 | Photo `storage_path` exposed in response | Never — assert only `signed_url` and `photo_id` present |
| PD-04 | Candidate has REJECTED photo with `is_primary = TRUE` | Not possible per DB constraint; but primary photo check still runs |
| PD-05 | Prompt answer exists, actor preferred_language = 'am' | `prompt_text` uses Amharic translation if available |
| PD-06 | Candidate has 4 photos; all approved | All 4 returned with signed URLs, ordered by `photo_order` |
| PD-07 | Signed URL TTL | `expires_at` is approximately 1 hour from request time |

### 10.3 Like Action

| ID | Scenario | Expected Result |
|---|---|---|
| LA-01 | Valid like, no existing mutual | `200`, `is_match = false`, `likes_used++` |
| LA-02 | Like target who already liked actor | `200`, `is_match = true`, match created, `rewind_eligible_until` set |
| LA-03 | Like with duplicate `client_action_id` (retry) | `200`, `idempotent = true`, counter not incremented again |
| LA-04 | Actor has already liked target (different `client_action_id`) | `409 DUPLICATE_ACTIVE_ACTION` |
| LA-05 | Actor's daily like limit exhausted, no credits | `429 DAILY_LIKE_LIMIT_EXCEEDED` |
| LA-06 | Target has `app_users.status = 'BANNED'` | `422 DISCOVERY_TARGET_INELIGIBLE` |
| LA-07 | Actor likes own user_id | `422 SELF_ACTION_NOT_ALLOWED` |
| LA-08 | Concurrent like requests from same actor to same target | One succeeds; second gets `409 DUPLICATE_ACTIVE_ACTION` |
| LA-09 | Actor likes target simultaneously as target likes actor | One match created; no duplicate match |
| LA-10 | Active block exists on target | `422 DISCOVERY_TARGET_INELIGIBLE` |

### 10.4 Pass Action

| ID | Scenario | Expected Result |
|---|---|---|
| PA-01 | Valid pass | `200`, action inserted, no limit consumed |
| PA-02 | Pass with duplicate `client_action_id` | `200`, `idempotent = true` |
| PA-03 | Pass target already passed | `409 DUPLICATE_ACTIVE_ACTION` |
| PA-04 | Pass does not trigger match check | Confirmed: `is_match = false` always |
| PA-05 | Pass with daily likes at limit | `200` — passes are unlimited |

### 10.5 Super Like Action

| ID | Scenario | Expected Result |
|---|---|---|
| SL-01 | Valid superlike within daily quota | `200`, `super_likes_used++`, no credit consumed |
| SL-02 | Daily quota exceeded, 2 credits in ledger | `200`, credit consumed, ledger CONSUMPTION row inserted |
| SL-03 | Daily quota exceeded, 0 credits | `429 DAILY_SUPERLIKE_LIMIT_EXCEEDED` |
| SL-04 | Superlike creates mutual match | Same as LA-02 |
| SL-05 | Superlike idempotency | Same as LA-03 |

### 10.6 Rewind — Pass Reversal

| ID | Scenario | Expected Result |
|---|---|---|
| RW-01 | Actor passed; no match involved | Action reversed, `match_cancelled = false`, restored_profile returned |
| RW-02 | No eligible actions exist | `404 NO_REWINDABLE_ACTION` |
| RW-03 | Daily rewind limit exhausted, credit available | Credit consumed, rewind proceeds |
| RW-04 | Daily rewind limit exhausted, no credits | `429 DAILY_REWIND_LIMIT_EXCEEDED` |
| RW-05 | Reversed profile appears in next discovery batch | Profile included (REVERSED action does not exclude) |
| RW-06 | Rewind called twice in sequence | Second rewind targets the action before the first one reversed; each call reverses the next most recent action |

### 10.7 Rewind — Fresh Match Cancellation

| ID | Scenario | Expected Result |
|---|---|---|
| RM-01 | Actor liked; match created; `rewind_eligible_until` has not passed; no messages | Match ended with `CANCELLED_BY_REWIND`, action reversed, `match_cancelled = true` |
| RM-02 | Actor liked; match created; `rewind_eligible_until` has passed | `422 REWIND_MATCH_GRACE_PERIOD_EXPIRED` |
| RM-03 | Actor liked; match created; message already sent | `422 REWIND_MATCH_HAS_MESSAGES` |
| RM-04 | After match-cancellation rewind, target's original like remains ACTIVE | Assert target's action `status = 'ACTIVE'` in DB |
| RM-05 | After match-cancellation rewind, actor can like target again | New like action inserts successfully; `unique_active_discovery_action_per_pair` allows it (old action is REVERSED) |
| RM-06 | Rewind on an established match (not fresh) | Grace period has expired; `422 REWIND_MATCH_GRACE_PERIOD_EXPIRED` |

### 10.8 Distance and Age Calculation

| ID | Scenario | Expected Result |
|---|---|---|
| DC-01 | Candidate within 0.499 km | `distance_km = 1` |
| DC-02 | Candidate at exactly 50 km | `distance_km = 50`, included when `max_distance_km = 50` |
| DC-03 | Candidate at 50.001 km, `open_to_long_distance = FALSE` | Excluded |
| DC-04 | Age calculated on candidate's birthday (today) | Age incremented correctly |
| DC-05 | Candidate born exactly 18 years ago (today) | Included, age = 18 |
| DC-06 | Candidate born 17 years, 364 days ago | Excluded by age filter |
| DC-07 | `location_filter != NEARBY` | `distance_km = null` in response |

### 10.9 Daily Limit Reset

| ID | Scenario | Expected Result |
|---|---|---|
| DL-01 | Actor at limit at 23:59 UTC; new request at 00:00 UTC next day | New `user_daily_limits` row, limit reset to 0 |
| DL-02 | Two concurrent requests at midnight UTC | `ON CONFLICT DO NOTHING` ensures only one row is created; `FOR UPDATE` serialises the counter |

### 10.10 Cursor and Pagination

| ID | Scenario | Expected Result |
|---|---|---|
| CU-01 | First request, no cursor | Returns first 10, `next_cursor` set |
| CU-02 | Request with `next_cursor` from previous response | Returns next 10 (no overlap) |
| CU-03 | Cursor older than 30 minutes | Server resets to offset 0, `cursor_reset = true` in response |
| CU-04 | All candidates returned; last page | `has_more = false`, `next_cursor = null` |
| CU-05 | Cursor from different `location_filter` submitted | Server detects mismatch, resets to offset 0 |

---

## Appendix A: Spring Boot Controller Signatures

```java
@RestController
@RequestMapping("/api/v1/discovery")
@SecurityRequirement(name = "bearerAuth")
public class DiscoveryController {

    @GetMapping("/profiles")
    public ResponseEntity<DiscoveryProfilesResponse> getProfiles(
        @RequestParam(defaultValue = "NEARBY") String locationFilter,
        @RequestParam(required = false) String cursor,
        @AuthenticationPrincipal Jwt jwt
    ) { ... }

    @PostMapping("/actions/like")
    public ResponseEntity<SwipeActionResponse> like(
        @Valid @RequestBody SwipeActionRequest request,
        @AuthenticationPrincipal Jwt jwt
    ) { ... }

    @PostMapping("/actions/pass")
    public ResponseEntity<SwipeActionResponse> pass(
        @Valid @RequestBody SwipeActionRequest request,
        @AuthenticationPrincipal Jwt jwt
    ) { ... }

    @PostMapping("/actions/superlike")
    public ResponseEntity<SwipeActionResponse> superLike(
        @Valid @RequestBody SwipeActionRequest request,
        @AuthenticationPrincipal Jwt jwt
    ) { ... }

    @PostMapping("/actions/rewind")
    public ResponseEntity<RewindResponse> rewind(
        @AuthenticationPrincipal Jwt jwt
    ) { ... }
}
```

---

## Appendix B: Configuration Properties

```properties
# Rewind grace period for fresh matches
discovery.rewind.match-grace-period-minutes=10

# Discovery batch size
discovery.queue.batch-size=10

# Cursor max age before reset
discovery.cursor.max-age-minutes=30

# Signed URL TTL for profile photos
storage.signed-url.ttl-seconds=3600

# Minimum returned distance (anti-triangulation floor)
discovery.distance.min-km=1
```

---

## Appendix C: Recommended Indexes (Summary)

All indexes are already defined in `schema.sql`. Key ones for Discovery:

| Index | Purpose |
|---|---|
| `idx_profiles_discovery_bundle` | Partial index on (gender, residency_type, dob) for visible+onboarded profiles |
| `idx_profiles_verified_discovery` | Verified-only filter |
| `idx_addresses_coords` | GIST index for PostGIS `ST_Distance` and `ST_DWithin` |
| `unique_active_discovery_action_per_pair` | Prevents duplicate active actions; enables fast exclusion check |
| `idx_discovery_actions_actor_rewind_stack` | Ordered stack for rewind: (actor, status, created_at DESC) |
| `idx_discovery_actions_target_active` | Mutual like check: (target, status, created_at DESC) |
| `unique_active_match_pair` | Prevents duplicate active matches |
| `idx_user_daily_limits_date` | Daily limit row lookup by date |
| `idx_entitlement_ledger_user_type_created` | Credit balance sum by (user, type) |
| `idx_active_boosts_user_expiry` | Boost status check per user |
| `idx_profile_photos_approved_primary` | Existence check for approved primary photo |
| `idx_user_blocks_reverse_active` | Block check in reverse direction |

# Discovery API

**Base path:** `/api/v1/discovery`  
**Authentication:** All endpoints require a JWT Bearer token in the `Authorization` header.

---

## Table of Contents

1. [GET /profiles](#1-get-profiles) — Fetch discovery feed (primary endpoint)
2. [GET /cards](#2-get-cards) — Fetch swipe cards (legacy endpoint)
3. [POST /actions/like](#3-post-actionslike)
4. [POST /actions/pass](#4-post-actionspass)
5. [POST /actions/superlike](#5-post-actionssuperlike)
6. [POST /actions/rewind](#6-post-actionsrewind)
7. [GET /preferences](#7-get-preferences)
8. [PUT /preferences](#8-put-preferences)
9. [DELETE /preferences](#9-delete-preferences)
10. [Error Format](#error-format)

---

## 1. GET /profiles

Fetches the next batch of candidate profiles for the authenticated user based on their discovery preferences.

### Query Parameters

| Parameter        | Type   | Required | Default  | Description |
|-----------------|--------|----------|----------|-------------|
| `locationFilter` | string | No       | `NEARBY` | Scopes results by location. One of: `NEARBY`, `ETHIOPIA`, `ERITREA`, `DIASPORA`. Invalid values fall back to `NEARBY`. |
| `cursor`         | string | No       | —        | Opaque pagination token returned from the previous response. Omit for the first page. |

### Response `200 OK`

```json
{
  "profiles": [],
  "nextCursor": "eyJvZmZzZXQiOjEwfQ",
  "hasMore": true,
  "totalEligible": 47,
  "locationFilter": "NEARBY",
  "batchSize": 10,
  "cursorReset": false
}
```

#### Top-level fields

| Field            | Type    | Description |
|-----------------|---------|-------------|
| `profiles`       | array   | Ordered list of profile cards for this batch. |
| `nextCursor`     | string \| null | Pass this as `cursor` on the next request to get the next page. `null` when no more results. |
| `hasMore`        | boolean | Whether more profiles exist beyond this batch. |
| `totalEligible`  | integer | Total number of eligible candidates at the time of this request. |
| `locationFilter` | string  | The effective location filter used for this query. |
| `batchSize`      | integer | Number of profiles returned in this batch. |
| `cursorReset`    | boolean | `true` if the cursor was reset (e.g. filter changed). The client should discard any cached queue state. |

#### `DiscoveryProfileDto`

| Field                   | Type            | Nullable | Description |
|------------------------|-----------------|----------|-------------|
| `userId`                | UUID (string)   | No       | Unique identifier for the candidate user. |
| `displayName`           | string          | No       | Publicly shown name. |
| `age`                   | integer         | No       | Calculated age in years. |
| `gender`                | string          | No       | `MALE` or `FEMALE`. |
| `bio`                   | string          | Yes      | Free-text biography. |
| `residencyType`         | string          | No       | `ETHIOPIA`, `ERITREA`, or `DIASPORA`. |
| `city`                  | string          | Yes      | City from the user's address. |
| `region`                | string          | Yes      | Region/state from the user's address. |
| `countryName`           | string          | Yes      | Full country name. |
| `distanceKm`            | integer         | Yes      | Approximate distance in km. Only populated when `locationFilter` is `NEARBY`; `null` otherwise. |
| `isVerified`            | boolean         | No       | Profile has been identity-verified. |
| `relationshipIntention` | string          | Yes      | e.g. `MARRIAGE`, `SERIOUS_RELATIONSHIP`. |
| `heightCm`              | integer         | Yes      | Height in centimetres. |
| `ethnicity`             | string          | Yes      | Self-reported ethnicity. |
| `nationality`           | string          | Yes      | Self-reported nationality. |
| `religion`              | string          | Yes      | e.g. `MUSLIM`, `CHRISTIAN`. |
| `educationLevel`        | string          | Yes      | e.g. `BACHELORS`, `MASTERS`. |
| `occupation`            | string          | Yes      | Free-text occupation. |
| `maritalStatus`         | string          | Yes      | e.g. `SINGLE`, `DIVORCED`. |
| `hasChildren`           | boolean         | No       | Whether the user has children. |
| `wantsChildren`         | boolean         | Yes      | Whether the user wants children. `null` means not specified. |
| `smoking`               | boolean         | No       | Whether the user smokes. |
| `drinking`              | boolean         | No       | Whether the user drinks alcohol. |
| `photos`                | array           | No       | List of approved photos (see `DiscoveryPhotoDto`). |
| `promptAnswers`         | array           | No       | Up to 2 answered profile prompts (see `DiscoveryPromptAnswerDto`). |
| `isBoosted`             | boolean         | No       | User is currently running a boost; boosted profiles rank first. |
| `discoveryScore`        | float           | No       | Internal ranking score. For ordering display only; do not persist. |

#### `DiscoveryPhotoDto`

| Field        | Type          | Description |
|-------------|---------------|-------------|
| `id`         | UUID (string) | Photo identifier. |
| `photoOrder` | integer       | Display order (ascending). |
| `isPrimary`  | boolean       | Whether this is the main profile photo. |
| `signedUrl`  | string        | Pre-signed URL to the image. Valid until `expiresAt`. |
| `expiresAt`  | ISO-8601 string | When the `signedUrl` expires. Refresh by re-fetching profiles. |

#### `DiscoveryPromptAnswerDto`

| Field        | Type          | Description |
|-------------|---------------|-------------|
| `promptId`   | UUID (string) | Identifier of the prompt. |
| `promptText` | string        | The prompt question, localised to the actor's preferred language where available. |
| `answerText` | string        | The candidate's answer. |

---

## 2. GET /cards

> **Legacy endpoint.** Use [GET /profiles](#1-get-profiles) for new integrations. Returns a simpler card shape without the full profile attributes.

### Query Parameters

| Parameter | Type    | Required | Default  | Description |
|----------|---------|----------|----------|-------------|
| `cursor`  | string  | No       | —        | Opaque keyset cursor from a previous response. |
| `limit`   | integer | No       | `10`     | Number of cards to return. Clamped to `[1, 50]`. |
| `scope`   | string  | No       | `NEARBY` | One of: `NEARBY`, `ETHIOPIA`, `ERITREA`, `DIASPORA`. |

### Response `200 OK`

```json
{
  "cards": [],
  "next_cursor": "MCoxMDA6M..."
}
```

#### `CardDto`

| Field                    | Type          | Nullable | Description |
|-------------------------|---------------|----------|-------------|
| `userId`                 | UUID (string) | No       | |
| `displayName`            | string        | No       | |
| `age`                    | integer       | No       | |
| `gender`                 | string        | No       | `MALE` or `FEMALE`. |
| `bio`                    | string        | Yes      | |
| `residencyType`          | string        | No       | `ETHIOPIA`, `ERITREA`, or `DIASPORA`. |
| `city`                   | string        | Yes      | |
| `countryCode`            | string        | Yes      | ISO 3166-1 alpha-2 code. |
| `distanceKm`             | float         | Yes      | `null` for non-NEARBY scopes. |
| `isVerified`             | boolean       | No       | |
| `isBoosted`              | boolean       | No       | |
| `profileCompletionScore` | integer       | No       | Internal score used for ranking. |
| `relationshipIntention`  | string        | Yes      | |
| `photos`                 | array         | No       | See `PhotoCardDto` below. |
| `promptAnswers`          | array         | No       | Up to 2 items. See `PromptAnswerDto` below. |

#### `PhotoCardDto`

| Field       | Type          | Description |
|------------|---------------|-------------|
| `id`        | UUID (string) | Photo identifier. |
| `order`     | integer       | Display order (ascending). |
| `isPrimary` | boolean       | |
| `signedUrl` | string        | Pre-signed URL; valid for 1 hour. |

#### `PromptAnswerDto`

| Field        | Type   | Description |
|-------------|--------|-------------|
| `promptText` | string | The prompt question (localised). |
| `answerText` | string | The candidate's answer. |

---

## 3. POST /actions/like

Records a **like** action on a target profile.

### Request Body

```json
{
  "targetUserId": "550e8400-e29b-41d4-a716-446655440000",
  "clientActionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

| Field            | Type          | Required | Description |
|-----------------|---------------|----------|-------------|
| `targetUserId`   | UUID (string) | Yes      | The profile being liked. |
| `clientActionId` | UUID (string) | Yes      | Client-generated idempotency key. Reusing the same key returns the original result with `idempotent: true` instead of recording a duplicate action. |

### Response `200 OK` — `SwipeActionResponse`

```json
{
  "actionId": "...",
  "actionType": "LIKE",
  "status": "ACTIVE",
  "isMatch": false,
  "match": null,
  "dailyLikesRemaining": 14,
  "dailySuperLikesRemaining": 3,
  "superLikeCreditsRemaining": null,
  "createdAt": "2025-06-24T10:00:00Z",
  "idempotent": false
}
```

| Field                       | Type          | Nullable | Description |
|----------------------------|---------------|----------|-------------|
| `actionId`                  | UUID (string) | No       | Server-assigned identifier for this action. |
| `actionType`                | string        | No       | `LIKE`, `PASS`, or `SUPER_LIKE`. |
| `status`                    | string        | No       | `ACTIVE`. |
| `isMatch`                   | boolean       | No       | `true` if this like created a mutual match. |
| `match`                     | object        | Yes      | Present only when `isMatch` is `true`. See `MatchSummaryDto`. |
| `dailyLikesRemaining`       | integer       | Yes      | Remaining likes for today. `null` if unlimited (premium). |
| `dailySuperLikesRemaining`  | integer       | Yes      | Remaining super-likes for today. `null` if not applicable. |
| `superLikeCreditsRemaining` | integer       | Yes      | Remaining purchased super-like credits. `null` if not applicable. |
| `createdAt`                 | ISO-8601 string | No     | When the action was recorded on the server. |
| `idempotent`                | boolean       | No       | `true` if this was a duplicate request using the same `clientActionId`. |

#### `MatchSummaryDto`

| Field                 | Type          | Description |
|----------------------|---------------|-------------|
| `matchId`             | UUID (string) | Unique match identifier. |
| `matchedAt`           | ISO-8601 string | When the match occurred. |
| `rewindEligibleUntil` | ISO-8601 string | Deadline before which the match can be rewound. |
| `otherUser`           | object        | See `MatchedUserSummaryDto`. |

#### `MatchedUserSummaryDto`

| Field             | Type          | Description |
|------------------|---------------|-------------|
| `userId`          | UUID (string) | |
| `displayName`     | string        | |
| `primaryPhotoUrl` | string        | Pre-signed URL to the matched user's primary photo. |

---

## 4. POST /actions/pass

Records a **pass** (skip) on a target profile. `actionType` is `PASS`. `isMatch` is always `false`.

### Request Body

Same as [POST /actions/like](#3-post-actionslike).

### Response `200 OK`

Same `SwipeActionResponse` shape; `match` will always be `null`.

---

## 5. POST /actions/superlike

Records a **super-like** on a target profile. Consumes a super-like credit or daily quota. `actionType` is `SUPER_LIKE`.

### Request Body

Same as [POST /actions/like](#3-post-actionslike).

### Response `200 OK`

Same `SwipeActionResponse` shape.

---

## 6. POST /actions/rewind

Undoes the most recent swipe action. No request body required.

### Response `200 OK` — `RewindResponse`

```json
{
  "reversedActionId": "...",
  "reversedActionType": "LIKE",
  "reversedTargetUserId": "...",
  "matchCancelled": true,
  "matchId": "...",
  "dailyRewindsRemaining": 2,
  "restoredProfile": {},
  "reversedAt": "2025-06-24T10:05:00Z"
}
```

| Field                   | Type          | Nullable | Description |
|------------------------|---------------|----------|-------------|
| `reversedActionId`      | UUID (string) | No       | The action that was undone. |
| `reversedActionType`    | string        | No       | `LIKE`, `PASS`, or `SUPER_LIKE`. |
| `reversedTargetUserId`  | UUID (string) | No       | The profile the action was on. |
| `matchCancelled`        | boolean       | No       | `true` if a match was dissolved by this rewind. |
| `matchId`               | UUID (string) | Yes      | The dissolved match ID. `null` if no match was involved. |
| `dailyRewindsRemaining` | integer       | No       | How many more rewinds the user can perform today. |
| `restoredProfile`       | object        | Yes      | Full `DiscoveryProfileDto` of the rewound profile so the client can re-display the card. |
| `reversedAt`            | ISO-8601 string | No     | Server timestamp of the rewind. |

---

## 7. GET /preferences

Returns the authenticated user's current discovery preferences.

### Response `200 OK`

```json
{
  "interested_in_gender": "FEMALE",
  "min_age": 22,
  "max_age": 35,
  "max_distance_km": 50,
  "discovery_mode": "PUBLIC",
  "preferred_residency_types": ["ETHIOPIA", "DIASPORA"],
  "open_to_long_distance": false,
  "open_to_relocation": false,
  "show_verified_only": false
}
```

| Field                       | Type     | Description |
|----------------------------|----------|-------------|
| `interested_in_gender`      | string   | `MALE` or `FEMALE`. |
| `min_age`                   | integer  | Minimum candidate age filter. |
| `max_age`                   | integer  | Maximum candidate age filter. |
| `max_distance_km`           | integer  | Distance radius in km (applies to NEARBY scope). |
| `discovery_mode`            | string   | `PUBLIC` — visible in others' feeds. `INCOGNITO` — hidden from discovery. |
| `preferred_residency_types` | string[] | Subset of `["ETHIOPIA", "ERITREA", "DIASPORA"]`. |
| `open_to_long_distance`     | boolean  | If `true`, distance filter is bypassed for NEARBY scope. |
| `open_to_relocation`        | boolean  | User is open to relocating. Informational. |
| `show_verified_only`        | boolean  | If `true`, only show verified profiles. |

**Error:** `404` with code `PREFERENCES_REQUIRED` if no preferences have been saved yet.

---

## 8. PUT /preferences

Creates or replaces the user's discovery preferences.

### Request Body

```json
{
  "interestedInGender": "FEMALE",
  "minAge": 22,
  "maxAge": 35,
  "maxDistanceKm": 50,
  "preferredResidencyTypes": ["ETHIOPIA", "DIASPORA"],
  "discoveryMode": "PUBLIC",
  "openToLongDistance": false,
  "openToRelocation": false,
  "showVerifiedOnly": false
}
```

| Field                     | Type     | Required | Constraints             | Description |
|--------------------------|----------|----------|-------------------------|-------------|
| `interestedInGender`      | string   | Yes      | `MALE` or `FEMALE`      | Gender of profiles to show. |
| `minAge`                  | integer  | Yes      | `18–120`, ≤ `maxAge`    | Minimum candidate age. |
| `maxAge`                  | integer  | Yes      | `18–120`                | Maximum candidate age. |
| `maxDistanceKm`           | integer  | Yes      | `1–500`                 | Distance radius for NEARBY scope. |
| `preferredResidencyTypes` | string[] | No       | Values: `ETHIOPIA`, `ERITREA`, `DIASPORA` | If omitted or empty, all three are used. |
| `discoveryMode`           | string   | No       | `PUBLIC` or `INCOGNITO` | Defaults to `PUBLIC`. |
| `openToLongDistance`      | boolean  | No       | —                       | Defaults to `false`. |
| `openToRelocation`        | boolean  | No       | —                       | Defaults to `false`. |
| `showVerifiedOnly`        | boolean  | No       | —                       | Defaults to `false`. |

### Response `200 OK`

```json
{
  "preferences": {
    "interested_in_gender": "FEMALE",
    "min_age": 22,
    "max_age": 35,
    "max_distance_km": 50,
    "discovery_mode": "PUBLIC",
    "preferred_residency_types": ["ETHIOPIA", "DIASPORA"],
    "open_to_long_distance": false,
    "open_to_relocation": false,
    "show_verified_only": false
  },
  "onboarding": {
    "next_step": "COMPLETE",
    "can_complete_onboarding": true
  }
}
```

The `onboarding` block reflects the user's updated onboarding state. Use `next_step` to redirect the user if onboarding is not yet complete.

---

## 9. DELETE /preferences

Clears all discovery preferences. The user must re-set preferences before using discovery.

### Response `200 OK`

```json
{
  "preferences": null,
  "onboarding": {
    "next_step": "DISCOVERY_PREFERENCES",
    "can_complete_onboarding": false
  }
}
```

---

## Error Format

All error responses follow this envelope:

```json
{
  "error": {
    "code": "ERROR_CODE",
    "message": "Human-readable description."
  }
}
```

For rate-limit errors an additional `details` field is included:

```json
{
  "error": {
    "code": "DAILY_LIKE_LIMIT_EXCEEDED",
    "message": "You have reached your daily like limit.",
    "details": {
      "limit_type": "DAILY_LIKES"
    }
  }
}
```

### Error Codes Reference

| HTTP  | Code                           | Trigger |
|-------|-------------------------------|---------|
| `403` | `DISCOVERY_ACTOR_INELIGIBLE`  | Account is suspended or otherwise ineligible for discovery. |
| `403` | `ACTOR_PROFILE_INCOMPLETE`    | Profile not fully onboarded or discovery preferences not set. |
| `404` | `NO_REWINDABLE_ACTION`        | No recent action exists to rewind. |
| `404` | `PREFERENCES_REQUIRED`        | Preferences not found (`GET /preferences` only). |
| `409` | `DUPLICATE_ACTIVE_ACTION`     | An active action toward this target already exists. Safe to ignore on retry when reusing the same `clientActionId` (returns `idempotent: true`). |
| `422` | `SELF_ACTION_NOT_ALLOWED`     | `targetUserId` matches the caller's own user ID. |
| `422` | `DISCOVERY_TARGET_INELIGIBLE` | Target profile is no longer active or visible. |
| `429` | `DAILY_LIKE_LIMIT_EXCEEDED`   | Daily like quota exhausted. `details.limit_type = "DAILY_LIKES"`. |
| `429` | `DAILY_SUPERLIKE_LIMIT_EXCEEDED` | Daily super-like quota exhausted. `details.limit_type = "DAILY_SUPERLIKES"`. |
| `429` | `DAILY_REWIND_LIMIT_EXCEEDED` | Daily rewind quota exhausted. `details.limit_type = "DAILY_REWINDS"`. |

---

## Notes for Frontend Clients

- **Signed photo URLs** expire (1 hour for `/cards`; check `expiresAt` for `/profiles`). Do not cache them across app sessions.
- **Cursor tokens** are opaque — never parse or construct them manually. Treat as black-box strings.
- **Idempotency** — generate a fresh `clientActionId` (UUID v4) per swipe gesture. On network retry, reuse the same `clientActionId`; the server returns `idempotent: true` with the original result and no duplicate is recorded.
- **`cursorReset: true`** in the profiles response means pagination restarted. Discard any locally queued profile IDs and start fresh.
- **`INCOGNITO` mode** hides the user from other users' feeds. Their own discovery feed continues to work normally.
- **`locationFilter` / `scope`** — `NEARBY` applies the `maxDistanceKm` radius around the user; `ETHIOPIA`, `ERITREA`, and `DIASPORA` filter by residency type and ignore distance entirely.

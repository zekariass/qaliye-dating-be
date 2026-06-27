# Revisit Passes Endpoint

## Overview

The revisit passes endpoint is a **command endpoint** called from the mobile app **Settings screen** when a user chooses to reconsider profiles they previously passed. It reverses eligible PASS actions, making those profiles eligible again in the normal discovery feed. The endpoint does **not** return profile cards or a separate revisit feed.

## Endpoint

```
POST /api/v1/discovery/passes/revisit
```

## Authentication

Requires valid JWT authentication via `Authorization: Bearer <token>` header.

## Request Parameters

### Query Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `count` | Integer | No | `10` | Number of passed profiles to reopen. **Supported values only:** `10`, `20`, `30` |

## Request Examples

### Reopen 10 (default)
```http
POST /api/v1/discovery/passes/revisit
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

### Reopen 20
```http
POST /api/v1/discovery/passes/revisit?count=20
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

### Reopen 30
```http
POST /api/v1/discovery/passes/revisit?count=30
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

## Response

### Success Response (200 OK)

Returns a small command result:

```json
{
  "success": true,
  "reopenedCount": 7
}
```

- `success` is `true` when the request is processed successfully.
- `reopenedCount` is the number of PASS actions actually reversed (0 to `count`).

**Zero result example:** When no eligible passes exist:
```json
{
  "success": true,
  "reopenedCount": 0
}
```
This is a successful result, not an error.

### Error Responses

#### 400 Bad Request
- `count` is not `10`, `20`, or `30`

#### 401 Unauthorized
- Missing or invalid JWT token

#### 403 Forbidden
- User account is ineligible (inactive, incomplete profile, etc.)

## Behavior

### Eligibility Rules

The endpoint only reverses PASS actions whose targets are **currently eligible** under existing Discovery rules:

1. **Target Account Status**: Active, visible, onboarded, not deleted
2. **Discovery Mode**: Not INCOGNITO
3. **Profile Completeness**: Approved primary photo exists
4. **Preference Matching**: Matches actor's current gender, age, residency preferences
5. **Location/Distance**: Within actor's current distance limits (unless `openToLongDistance` is true)
6. **No Active Blocks**: No active block in either direction
7. **No Match Record**: No match (active or ended) between the pair
8. **No Active Like/Superlike**: Actor has no active LIKE or SUPERLIKE against the target

### Process

1. Authenticate caller from JWT
2. Require caller to be ACTIVE and eligible for Discovery
3. Select the caller's most recently created ACTIVE PASS actions (`created_at DESC, id DESC`)
4. Evaluate each candidate using existing Discovery eligibility checks
5. For every currently eligible candidate:
   - Preserve the history row
   - Set `status = 'REVERSED'`
   - Set `reversed_at = NOW()`
   - Set `reversed_reason = 'REVISIT_PASSES'`
6. Return `success = true` and the number actually reversed
7. The endpoint may inspect more candidates than requested, but never reopens more than requested

### What Is NOT Done

- **No profiles returned** - the response contains only `success` and `reopenedCount`
- **No entitlement consumption** - no rewind credits, daily limits, likes, superlikes, boosts, or subscriptions are consumed
- **No notifications** - no push notifications, outbox events, matches, likes, chat events, or activity-status events
- **No separate revisit feed** - reopened profiles appear naturally in the normal discovery feed
- **No hard deletes** - PASS history is preserved with `status = 'REVERSED'`

### Concurrency Protection

The endpoint uses `FOR UPDATE SKIP LOCKED` on candidate PASS action rows. Simultaneous revisit requests cannot reopen the same PASS action twice.

## Frontend Integration

### Usage Pattern

1. User navigates to **Settings -> Revisit Passed Profiles**
2. User selects an amount: **10**, **20**, or **30**
3. Frontend calls this endpoint with the selected `count`
4. On success, frontend shows a confirmation (e.g., "7 profiles reopened")
5. User returns to the normal discovery feed, where reopened profiles may now appear

### Example Implementation

```typescript
const reopenPassedProfiles = async (count: 10 | 20 | 30) => {
  const response = await fetch(
    `/api/v1/discovery/passes/revisit?count=${count}`,
    {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${jwtToken}`,
        'Content-Type': 'application/json'
      }
    }
  );

  if (!response.ok) {
    throw new Error('Failed to reopen passed profiles');
  }

  const data = await response.json();
  return {
    success: data.success,
    reopenedCount: data.reopenedCount
  };
};
```

## Related Endpoints

- `GET /api/v1/discovery/profiles` - Main discovery feed (shows reopened profiles after successful revisit)
- `POST /api/v1/discovery/actions/like` - Like a profile
- `POST /api/v1/discovery/actions/pass` - Pass on a profile
- `POST /api/v1/discovery/actions/rewind` - Undo the last action

## Database

This feature uses the `REVISIT_PASSES` value added to the `reversed_reason` enum in the `user_discovery_actions` table via the V12 Flyway migration.

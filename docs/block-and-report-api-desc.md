# Block & Report API — Frontend Integration Guide

All endpoints require a valid bearer token (`Authorization: Bearer <token>`).
Base path: `/api/v1`.

---

## 1. Report a user

**Endpoint:**

```http
POST /api/v1/users/{userId}/report
```

**Description:**
Submit a report about another user for moderation review.

**Path parameters:**

| Parameter | Type | Description |
|---|---|---|
| `userId` | UUID | ID of the user being reported |

**Request body:**

```json
{
  "reportType": "FAKE_PROFILE",
  "description": "This profile uses someone else's photos."
}
```

**Fields:**

| Field | Type | Required | Constraints |
|---|---|---|---|
| `reportType` | string | yes | One of `FAKE_PROFILE`, `HARASSMENT`, `HATE_SPEECH`, `INAPPROPRIATE_CONTENT`, `SCAM`, `UNDERAGE`, `VIOLENCE_OR_THREATS`, `PRIVACY_VIOLATION`, `OFF_PLATFORM_SOLICITATION`, `SPAM`, `OTHER` |
| `description` | string | no | Max 2000 characters |

**Responses:**

| Status | Meaning |
|---|---|
| `201 Created` | Report submitted successfully |
| `400 Bad Request` | Invalid `reportType` or `description` too long |
| `422 Unprocessable Entity` | Reporter tried to report themselves (`CANNOT_REPORT_SELF`) |
| `404 Not Found` | Target user does not exist or is not active |

**Success response body:**

```json
{
  "id": "report-uuid",
  "reportedUserId": "reported-user-uuid",
  "reportType": "FAKE_PROFILE",
  "description": "This profile uses someone else's photos.",
  "status": "PENDING",
  "createdAt": "2026-06-30T12:34:56Z"
}
```

---

## 2. Block a user

**Endpoint:**

```http
POST /api/v1/users/{userId}/block
```

**Description:**
Block another user. This hides them from discovery, prevents further interactions, and immediately ends any active match between the two users.

**Path parameters:**

| Parameter | Type | Description |
|---|---|---|
| `userId` | UUID | ID of the user to block |

**Request body:**

Optional.

```json
{
  "reason": "Harassment in chat"
}
```

**Fields:**

| Field | Type | Required | Constraints |
|---|---|---|---|
| `reason` | string | no | Max 500 characters |

**Responses:**

| Status | Meaning |
|---|---|
| `201 Created` | User blocked (or already blocked) |
| `422 Unprocessable Entity` | User tried to block themselves (`CANNOT_BLOCK_SELF`) |
| `404 Not Found` | Target user does not exist or is not active |

**Success response body:**

```json
{
  "id": "block-uuid",
  "blockedUserId": "blocked-user-uuid",
  "status": "ACTIVE",
  "reason": "Harassment in chat",
  "blockedAt": "2026-06-30T12:34:56Z"
}
```

**Note:** If the target is already blocked, the existing block is returned with a `201` status.

---

## 3. Unblock a user

**Endpoint:**

```http
DELETE /api/v1/users/{userId}/block
```

**Description:**
Revoke an active block so the user becomes visible/reachable again.

**Path parameters:**

| Parameter | Type | Description |
|---|---|---|
| `userId` | UUID | ID of the user to unblock |

**Request body:**

None.

**Responses:**

| Status | Meaning |
|---|---|
| `204 No Content` | Block revoked successfully |
| `404 Not Found` | No active block found for this user |

---

## 4. Legacy block endpoint

**Endpoint:**

```http
POST /api/v1/safety/block
```

**Description:**
Older block endpoint kept for backward compatibility. Prefer `POST /api/v1/users/{userId}/block`.

**Request body:**

```json
{
  "blockedUserId": "user-uuid"
}
```

**Responses:**

| Status | Meaning |
|---|---|
| `204 No Content` | Block created successfully |
| `400 Bad Request` | Missing or invalid `blockedUserId` |
| `422 Unprocessable Entity` | User tried to block themselves |
| `404 Not Found` | Target user does not exist |

---

## Side effects

- **Block:** Any active match between the caller and the target is immediately ended with `end_reason = 'BLOCKED'`.
- **Block:** Any active `LIKE` or `SUPERLIKE` the caller previously sent to the target is reversed (`status = 'REVERSED'`, `reversed_reason = 'BLOCK'`). This allows the caller to rediscover and re-like the profile after unblocking.
- **Block:** The blocked user is excluded from the caller's discovery feed, revisit passes, likes lists, and any future discovery actions.
- **Report:** No automatic block is created. If your app should also block after reporting, call the block endpoint separately.

---

## 5. List my blocked users

**Endpoint:**

```http
GET /api/v1/me/blocks
```

**Description:**
Return the authenticated user's currently active blocks, sorted by most recent block first. Supports opaque cursor-based pagination.

**Query parameters:**

| Parameter | Type | Required | Rules |
|---|---|---|---|
| `limit` | integer | no | Default `20`, minimum `1`, maximum `50` |
| `cursor` | string | no | Opaque cursor from the previous `nextCursor` response field |

**First page request:**

```http
GET /api/v1/me/blocks?limit=20
Authorization: Bearer <token>
```

**Next page request:**

```http
GET /api/v1/me/blocks?limit=20&cursor=eyJibG9ja2VkQXQiOiIyMDI2LTA2LTMwVDEyOjM0OjU2WiIsImxhc3RJZCI6ImJsb2NrLXV1aWQifQ
Authorization: Bearer <token>
```

**Responses:**

| Status | Meaning |
|---|---|
| `200 OK` | Block list returned (may be empty) |
| `400 Bad Request` | Invalid or malformed cursor |

**Success response body:**

```json
{
  "items": [
    {
      "id": "block-uuid",
      "blockedAt": "2026-06-30T12:34:56Z",
      "reason": "Harassment in chat",
      "blockedUser": {
        "id": "user-uuid",
        "displayName": "Sara",
        "address": {
          "id": "address-uuid",
          "countryCode": "ET",
          "countryName": "Ethiopia",
          "cityName": "Addis Ababa"
        },
        "primaryPhotoUrl": "https://signed-thumbnail-url",
        "primaryPhotoId": "photo-uuid"
      }
    }
  ],
  "nextCursor": "opaque-cursor-value",
  "hasMore": true
}
```

### Pagination behavior

- The cursor is **opaque**. Frontend must pass the exact `nextCursor` value returned by the previous response; do not parse or construct it manually.
- Internally the cursor is a Base64-encoded JSON payload carrying the last returned block's `blockedAt` timestamp and block record ID.
- Sorting is stable: `created_at DESC, id DESC`. New blocks created after the first page is loaded will appear before the cursor and will not shift the current page contents.
- When a user is unblocked (row becomes `status = 'REVOKED'), it is no longer returned on subsequent pages, preventing stale results.

### Blocked-user field rules

**Address:**

- Only `id`, `countryCode`, `countryName`, and `cityName` are returned.
- These values are taken directly from the `addresses` table (`id`, `country_code`, `country_name`, `city`).
- If the blocked user has no address record, `address` is `null`.

**Profile photo:**

- Only the approved primary photo is returned: `is_primary = TRUE AND moderation_status = 'APPROVED' AND deleted_at IS NULL`.
- The URL is a short-lived signed thumbnail URL. No storage bucket, storage path, or full-resolution URL is exposed.
- If no approved primary photo exists, `primaryPhotoUrl` and `primaryPhotoId` are `null`.

### Privacy restrictions

The endpoint does **not** return:

- Online status or last-active time
- Bio, prompts, interests, work, education, religion, or dating preferences
- Additional photos
- Likes, matches, messages, or relationship status
- Distance, coordinates, or live location
- Any address details beyond the four allowed fields above

---

## Error response format

All endpoints return standard Spring error responses:

```json
{
  "timestamp": "2026-06-30T12:34:56.000+00:00",
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "CANNOT_BLOCK_SELF",
  "path": "/api/v1/users/{userId}/block"
}
```


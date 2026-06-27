# Matches Endpoint Description

## Endpoint
- **Method:** `GET`
- **Path:** `/api/v1/discovery/matches`
- **Authentication:** Bearer JWT (required)
- **Query Parameters:** Optional

## Request
```http
GET /api/v1/discovery/matches?page=0&size=20
Authorization: Bearer <jwt-token>
```

### Query Parameters
| Parameter | Type    | Default | Constraints | Description |
|-----------|---------|---------|-------------|-------------|
| `page`    | Integer | `0`     | >= 0        | Zero-based page number. |
| `size`    | Integer | `20`    | 1–50        | Number of items per page. Values above 50 are clamped to 50. |

## Response
Returns a `MatchesPageResponse` object:

```json
{
  "items": [
    {
      "matchId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "userId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
      "displayName": "Jane Doe",
      "age": 28,
      "isVerified": true,
      "primaryPhotoUrl": "https://<supabase-host>/storage/v1/object/sign/photos/abc.jpg?token=...",
      "matchedAt": "2026-06-25T09:00:00Z",
      "rewindEligibleUntil": "2026-06-25T09:30:00Z",
      "firstMessageAt": "2026-06-25T10:05:00Z",
      "lastMessageAt": "2026-06-25T14:30:00Z",
      "hasConversation": true,
      "isUnread": true,
      "distanceKm": 12,
      "city": "London",
      "region": "England",
      "countryName": "United Kingdom"
    },
    {
      "matchId": "4da96f75-6828-51ef-c4gd-3d074g77bgb7",
      "userId": "8d0f7780-8536-51ef-b445-f18gd2g01bf8",
      "displayName": "Aisha Hassan",
      "age": 26,
      "isVerified": false,
      "primaryPhotoUrl": null,
      "matchedAt": "2026-06-24T18:00:00Z",
      "rewindEligibleUntil": null,
      "firstMessageAt": null,
      "lastMessageAt": null,
      "hasConversation": false,
      "isUnread": false,
      "distanceKm": null,
      "city": null,
      "region": null,
      "countryName": null
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 7,
  "totalPages": 1,
  "hasNext": false,
  "hasPrevious": false
}
```

## Field Descriptions

### Page Metadata (`MatchesPageResponse`)
| Field           | Type                  | Nullable | Description |
|-----------------|-----------------------|----------|-------------|
| `items`         | Array\<MatchItemDto\> | No       | Match items for the current page. Empty array when there are no results. |
| `page`          | Integer               | No       | Current page number (zero-based). |
| `size`          | Integer               | No       | Effective page size used for this response. |
| `totalElements` | Long                  | No       | Total number of ACTIVE matches for the current user. |
| `totalPages`    | Integer               | No       | Total number of pages available. |
| `hasNext`       | Boolean               | No       | `true` if a next page exists. |
| `hasPrevious`   | Boolean               | No       | `true` if a previous page exists. |

### Match Item Fields (`MatchItemDto`)
| Field                 | Type    | Nullable | Description |
|-----------------------|---------|----------|-------------|
| `matchId`             | UUID    | No       | ID of the match. |
| `userId`              | UUID    | No       | ID of the other matched user. |
| `displayName`         | String  | No       | Display name of the other user. |
| `age`                 | Integer | No       | Age of the other user, calculated from date of birth. |
| `isVerified`          | Boolean | No       | Whether the other user's profile is verified. |
| `primaryPhotoUrl`     | String  | **Yes**  | Signed URL to the other user's primary photo. Valid for **1 hour**. `null` if no approved primary photo exists. |
| `matchedAt`           | Instant | No       | ISO-8601 UTC timestamp when the match was created. |
| `rewindEligibleUntil` | Instant | **Yes**  | Deadline until which this match can be rewound. `null` if the grace period has expired or was never set. |
| `firstMessageAt`      | Instant | **Yes**  | Timestamp of the first message sent in this match. `null` if no messages have been sent. |
| `lastMessageAt`       | Instant | **Yes**  | Timestamp of the most recent message. `null` if no messages have been sent. |
| `hasConversation`     | Boolean | No       | `true` if at least one message has been sent (`firstMessageAt != null`). Convenience shorthand. |
| `isUnread`            | Boolean | No       | `true` if there are messages the current user has not read yet. Always `false` when `hasConversation` is `false`. |
| `distanceKm`          | Integer | **Yes**  | Geodesic distance in km from the current user to the matched user. Minimum value is `1` when both addresses are set. `null` if either user has no address. |
| `city`                | String  | **Yes**  | City of the matched user. `null` if no address is set. |
| `region`              | String  | **Yes**  | Region/state of the matched user. `null` if no address is set. |
| `countryName`         | String  | **Yes**  | Full country name of the matched user. `null` if no address is set. |

## Ordering

Results are ordered by the backend — **no client-side sorting required**:

1. **Active conversations first** — sorted by `lastMessageAt DESC` (most recently active chat at the top)
2. **New matches without messages last** — sorted by `matchedAt DESC` (newest uncontacted match next)

This is the standard inbox ordering used by most dating/messaging apps.

## Frontend Implementation Notes

1. **Inbox-style list:** Render matches as a conversation inbox. Each card shows the other user's photo, name, distance, and a message preview or "New Match!" badge if `hasConversation` is `false`.

2. **Unread indicator:** Use `isUnread` to show a notification dot or bold text on the match card. Clear it locally once the user opens the conversation (the backend resets the `last_read_at` when the user reads messages).

3. **"New Match" state:** When `hasConversation` is `false`, the match has no messages yet. Show a special "New Match!" badge and prompt the user to send the first message.

4. **Rewind eligibility:** If `rewindEligibleUntil` is non-null and in the future **and** `hasConversation` is `false`, the match can still be rewound. Use this to conditionally show a rewind option.

5. **Photo handling:** `primaryPhotoUrl` is a signed URL expiring after **1 hour**. If `null`, show a placeholder avatar.

6. **Distance & location:** `distanceKm` is always `>= 1` when present. Format location as `"London, England"` or fall back gracefully if fields are `null`.

7. **Pagination:** Use `page` and `size` for infinite scroll. Stop fetching when `hasNext` is `false`. Reset to `page=0` on pull-to-refresh.

8. **Empty state:** When `totalElements` is `0`, show "No matches yet — keep swiping!".

9. **Only ACTIVE matches:** Ended (unmatched/blocked) matches are automatically excluded.

## Error Responses

All error responses follow the `ApiError` shape:
```json
{
  "error": "<error-code>",
  "message": "<human-readable message>",
  "status": <http-status-code>
}
```

### 401 Unauthorized
```json
{
  "error": "error",
  "message": "Missing subject in JWT",
  "status": 401
}
```
The JWT token is missing, expired, or has no `sub` claim.

### 500 Internal Server Error
```json
{
  "error": "internal_error",
  "message": "An unexpected error occurred",
  "status": 500
}
```

## Example Usage

### First Page (defaults)
```http
GET /api/v1/discovery/matches
Authorization: Bearer <jwt-token>
```

### Explicit Parameters
```http
GET /api/v1/discovery/matches?page=0&size=20
Authorization: Bearer <jwt-token>
```

### Second Page
```http
GET /api/v1/discovery/matches?page=1&size=20
Authorization: Bearer <jwt-token>
```

## Related Endpoints
- **Rewind a match:** `POST /api/v1/discovery/actions/rewind` — cancels the most recent match (within grace period, before first message)
- **Unmatch:** (separate endpoint) — ends an ACTIVE match with reason `USER_UNMATCH`

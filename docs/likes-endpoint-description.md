# Likes Endpoint Description

## Endpoint
- **Method:** `GET`
- **Path:** `/api/v1/discovery/likes`
- **Authentication:** Bearer JWT (required)
- **Query Parameters:** Optional

## Request
The endpoint accepts query parameters to control pagination and direction:

```http
GET /api/v1/discovery/likes?direction=RECEIVED&page=0&size=20
Authorization: Bearer <jwt-token>
```

### Query Parameters
| Parameter   | Type    | Default    | Constraints | Description |
|-------------|---------|------------|-------------|-------------|
| `direction` | String  | `RECEIVED` | `RECEIVED` or `SENT` | `RECEIVED`: likes/superlikes the current user received. `SENT`: likes/superlikes the current user sent. |
| `page`      | Integer | `0`        | >= 0        | Zero-based page number. |
| `size`      | Integer | `20`       | 1–50        | Number of items per page. Values above 50 are clamped to 50. |

## Response
Returns a `LikesPageResponse` object with the following structure:

```json
{
  "items": [
    {
      "actionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "userId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
      "displayName": "Jane Doe",
      "age": 28,
      "isVerified": true,
      "primaryPhotoUrl": "https://<supabase-host>/storage/v1/object/sign/photos/abc.jpg?token=...",
      "actionType": "LIKE",
      "likedAt": "2026-06-25T10:00:00Z",
      "distanceKm": 15,
      "city": "London",
      "region": "England",
      "countryName": "United Kingdom"
    },
    {
      "actionId": "4da96f75-6828-51ef-c4gd-3d074g77bgb7",
      "userId": "8d0f7780-8536-51ef-b445-f18gd2g01bf8",
      "displayName": "John Smith",
      "age": 32,
      "isVerified": false,
      "primaryPhotoUrl": null,
      "actionType": "SUPERLIKE",
      "likedAt": "2026-06-25T09:30:00Z",
      "distanceKm": null,
      "city": null,
      "region": null,
      "countryName": null
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 45,
  "totalPages": 3,
  "hasNext": true,
  "hasPrevious": false,
  "direction": "RECEIVED"
}
```

## Field Descriptions

### Page Metadata (`LikesPageResponse`)
| Field           | Type              | Nullable | Description |
|-----------------|-------------------|----------|-------------|
| `items`         | Array\<LikeItemDto\> | No    | Like items for the current page. Empty array when there are no results. |
| `page`          | Integer           | No       | Current page number (zero-based). |
| `size`          | Integer           | No       | Effective page size used for this response. |
| `totalElements` | Long              | No       | Total number of ACTIVE likes/superlikes matching the direction. |
| `totalPages`    | Integer           | No       | Total number of pages available. |
| `hasNext`       | Boolean           | No       | `true` if a next page exists. |
| `hasPrevious`   | Boolean           | No       | `true` if a previous page exists. |
| `direction`     | String            | No       | The resolved direction: `RECEIVED` or `SENT`. |

### Like Item Fields (`LikeItemDto`)
| Field             | Type    | Nullable | Description |
|-------------------|---------|----------|-------------|
| `actionId`        | UUID    | No       | ID of the discovery action (like or superlike). |
| `userId`          | UUID    | No       | For `RECEIVED`: the user who liked/superliked the current user. For `SENT`: the user the current user liked/superliked. |
| `displayName`     | String  | No       | Display name of the other user. |
| `age`             | Integer | No       | Age of the other user, calculated from their date of birth. Always >= 18. |
| `isVerified`      | Boolean | No       | Whether the other user's profile is verified. |
| `primaryPhotoUrl` | String  | **Yes**  | Signed URL to the other user's primary photo. Valid for **1 hour**. `null` if the user has no approved primary photo. |
| `actionType`      | String  | No       | `"LIKE"` or `"SUPERLIKE"`. |
| `likedAt`         | Instant | No       | ISO-8601 UTC timestamp when the action was created. |
| `distanceKm`      | Integer | **Yes**  | Geodesic distance in km from the current user to the other user. Minimum value is `1` when both addresses are set. `null` if either user has no address. |
| `city`            | String  | **Yes**  | City of the other user. `null` if no address is set. |
| `region`          | String  | **Yes**  | Region/state of the other user. `null` if no address is set. |
| `countryName`     | String  | **Yes**  | Full country name of the other user. `null` if no address is set. |

## Frontend Implementation Notes

1. **Two tabs UI:** Implement two tabs in your UI:
   - "Received Likes" — call with `direction=RECEIVED`
   - "Sent Likes" — call with `direction=SENT`

2. **Pagination:** Use `page` and `size` for infinite scroll or paged navigation:
   - Start at `page=0&size=20` (defaults, can be omitted)
   - Fetch next page by incrementing `page` by 1
   - Stop fetching when `hasNext` is `false`
   - When switching tabs, reset to `page=0`

3. **Display both like types:** Use `actionType` to distinguish visually — e.g. show a star badge for `SUPERLIKE`.

4. **Photo handling:** `primaryPhotoUrl` is a signed URL expiring after **1 hour**. Cache it client-side for that duration. If `null`, show a profile-photo placeholder.

5. **Distance display:** `distanceKm` is always `>= 1` when present (users in the same building still show `1 km`). If `null`, hide the distance chip or show "Location unknown".

6. **Location display:** Compose a location string from `city`, `region`, and `countryName` (e.g. `"London, England"` or just `"United Kingdom"` if city/region are null). Hide the field entirely if all three are `null`.

7. **Ordering:** Items are always ordered by `likedAt` descending (newest first) — no client-side sorting needed.

8. **Empty state:** When `totalElements` is `0`, show a direction-specific empty state (e.g. "No one has liked you yet" / "You haven't liked anyone yet").

9. **User navigation:** Tap on a card to open the other user's profile using `userId`.

10. **Only active likes shown:** Only `ACTIVE` likes are returned. Reversed (rewound) actions are excluded automatically.

## Error Responses

All error responses follow the `ApiError` shape:
```json
{
  "error": "<error-code>",
  "message": "<human-readable message>",
  "status": <http-status-code>
}
```

### 400 Bad Request — Invalid Direction
```json
{
  "error": "validation_error",
  "message": "Invalid direction 'INVALID'. Must be RECEIVED or SENT.",
  "status": 400
}
```
Returned when `direction` is anything other than `RECEIVED` or `SENT`.

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
An unexpected server-side error occurred.

## Example Usage

### Received Likes — First Page (defaults)
```http
GET /api/v1/discovery/likes
Authorization: Bearer <jwt-token>
```

### Received Likes — Explicit Parameters
```http
GET /api/v1/discovery/likes?direction=RECEIVED&page=0&size=20
Authorization: Bearer <jwt-token>
```

### Sent Likes — Second Page
```http
GET /api/v1/discovery/likes?direction=SENT&page=1&size=20
Authorization: Bearer <jwt-token>
```

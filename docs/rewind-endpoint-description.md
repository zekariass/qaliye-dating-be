# Rewind Endpoint Description

## Endpoint
- **Method:** `POST`
- **Path:** `/api/v1/discovery/actions/rewind`
- **Authentication:** Bearer JWT (required)
- **Request Body:** None

## Request
No request body is required. The endpoint only needs JWT authentication to identify the user.

```http
POST /api/v1/discovery/actions/rewind
Authorization: Bearer <jwt-token>
Content-Type: application/json
```

## Response
Returns a `RewindResponse` object with the following structure:

```json
{
  "reversedActionId": "uuid-of-reversed-action",
  "reversedActionType": "LIKE|PASS|SUPERLIKE",
  "reversedTargetUserId": "uuid-of-user-who-was-target",
  "matchCancelled": true,
  "matchId": "uuid-of-cancelled-match-or-null",
  "dailyRewindsRemaining": 2,
  "restoredProfile": {
    "userId": "uuid",
    "displayName": "John Doe",
    "age": 28,
    "gender": "MALE",
    "bio": "Profile bio text",
    "residencyType": "RESIDENT",
    "city": "London",
    "region": "England",
    "countryName": "United Kingdom",
    "distanceKm": 5,
    "isVerified": true,
    "relationshipIntention": "SERIOUS",
    "heightCm": 180,
    "ethnicity": "White",
    "nationality": "British",
    "religion": "Christian",
    "educationLevel": "UNIVERSITY",
    "occupation": "Engineer",
    "maritalStatus": "SINGLE",
    "hasChildren": false,
    "wantsChildren": true,
    "smoking": "NEVER",
    "drinking": "OCCASIONALLY",
    "photos": [
      {
        "id": "uuid",
        "url": "https://...",
        "order": 0,
        "isPrimary": true
      }
    ],
    "promptAnswers": [
      {
        "prompt": "My ideal weekend is...",
        "answer": "Hiking and reading"
      }
    ],
    "isBoosted": false,
    "discoveryScore": 0.85
  },
  "reversedAt": "2026-06-25T10:00:00Z"
}
```

## Field Descriptions

### Action Details
- **reversedActionId** (UUID): The ID of the discovery action that was reversed
- **reversedActionType** (String): Type of action reversed - "LIKE", "PASS", or "SUPERLIKE"
- **reversedTargetUserId** (UUID): The ID of the user who was the target of the reversed action
- **reversedAt** (Instant): Timestamp when the rewind occurred

### Match Information
- **matchCancelled** (Boolean): Whether a match was cancelled due to this rewind (only true if reversed action was LIKE/SUPERLIKE and had created a match)
- **matchId** (UUID | null): ID of the match that was cancelled, or null if no match was involved

### Limits
- **dailyRewindsRemaining** (Integer): Number of rewinds remaining for the user today

### Restored Profile (IMPORTANT)
- **restoredProfile** (DiscoveryProfileDto | null): The profile that is now available again in the user's feed. **This is the profile card that should be shown back to the user.** If null, the profile could not be loaded (e.g., user was deleted or became ineligible).

## Frontend Implementation Notes

1. **Show the restored profile:** When a successful rewind response is received, display the `restoredProfile` object as a photo card in the discovery feed. This is the profile the user just rewound on.

2. **Handle null restoredProfile:** If `restoredProfile` is null, the profile could not be restored (user deleted, banned, or became ineligible). In this case, just show the next profile in the feed.

3. **Update remaining count:** Display `dailyRewindsRemaining` to show the user how many rewinds they have left.

4. **Match cancellation:** If `matchCancelled` is true, inform the user that the match was cancelled (e.g., show a toast notification).

## Error Responses

### 429 Too Many Requests
```json
{
  "errorCode": "DAILY_REWIND_LIMIT_EXCEEDED",
  "message": "You have reached your daily rewind limit."
}
```
User has exceeded their daily rewind limit and has no rewind credits remaining.

### 400 Bad Request
```json
{
  "errorCode": "NO_REWINDABLE_ACTION",
  "message": "No action available to rewind."
}
```
User has no recent actions that can be rewound.

### 400 Bad Request
```json
{
  "errorCode": "REWIND_MATCH_GRACE_PERIOD_EXPIRED",
  "message": "Cannot rewind match after grace period."
}
```
The match was created too long ago to be rewound.

### 400 Bad Request
```json
{
  "errorCode": "REWIND_MATCH_HAS_MESSAGES",
  "message": "Cannot rewind match with messages."
}
```
The match already has messages, so it cannot be rewound.

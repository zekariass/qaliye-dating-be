# Unmatch Endpoint

Allows an authenticated user to unmatch (end) an existing match. Both users can call this endpoint. After a successful unmatch, the match is moved to an `ENDED` status and both users will no longer see each other in their chat inbox.

---

## `DELETE /api/v1/matches/{matchId}`

### Authentication
Requires a valid bearer token (JWT) in the `Authorization` header. The user ID is resolved from the token.

### URL Parameters

| Parameter | Type   | Required | Description                  |
| --------- | ------ | -------- | ---------------------------- |
| `matchId` | UUID   | Yes      | The unique ID of the match   |

### Request Body
None.

### Responses

#### `204 No Content`
The match was active and has been successfully ended (unmatched). The caller should remove the match from the local inbox / chat list.

#### `200 OK`
The match was already ended or did not exist. This is a safe no-op. The frontend may treat this the same as `204` (no active match remains).

#### `404 Not Found`
```json
{
  "message": "match_not_found"
}
```
The specified `matchId` does not exist in the database.

#### `403 Forbidden`
```json
{
  "error": "not_participant"
}
```
The authenticated user is not a participant in this match.

### Side Effects
- When the unmatch succeeds (`204`), the backend emits:
  - A `MatchEndedEvent`
  - An `InboxMatchRemovedEvent` for **both** users
- Any connected WebSocket clients will receive real-time inbox removal updates.

### Example Request
```bash
curl -X DELETE "https://api.qaliye.com/api/v1/matches/550e8400-e29b-41d4-a716-446655440000" \
  -H "Authorization: Bearer <jwt_token>"
```

### Frontend Integration Notes
- On `204` or `200`, navigate the user away from the chat screen and remove the match thread from the local list.
- Do **not** retry on `403` or `404`; surface the error to the user.
- If the other user unmatches first, the frontend will learn about it via the real-time `InboxMatchRemovedEvent` and can handle removal without polling.

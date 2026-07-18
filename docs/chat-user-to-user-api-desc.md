# User-to-User Chat API

Base URL: `/api/v1/chat`

All endpoints require authentication. The authenticated user is resolved from the security context and referred to as the **caller** throughout this document.

---

## Table of Contents

- [1. Get Inbox](#1-get-inbox)
- [2. Get Match Metadata](#2-get-match-metadata)
- [3. Get Messages](#3-get-messages)
- [4. Send Text Message](#4-send-text-message)
- [5. Send Message with Attachments](#5-send-message-with-attachments)
- [6. Refresh Attachment Signed URL](#6-refresh-attachment-signed-url)
- [7. Mark Delivered](#7-mark-delivered)
- [8. Mark Read](#8-mark-read)
- [9. Update Notification Settings](#9-update-notification-settings)
- [Data Models](#data-models)
- [Error Responses](#error-responses)
- [Realtime Events](#realtime-events)
- [Attachment Validation Rules](#attachment-validation-rules)

---

## 1. Get Inbox

Retrieves a paginated list of the caller's chat matches (conversations), ordered by most recent activity. Each item includes the participant's profile, last message preview, and unread count.

### Request

```
GET /api/v1/chat/matches
```

### Query Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `filter` | string | No | `ALL` | Filter for inbox items. Accepted values: `ALL`, `UNREAD`. |
| `cursor` | string | No | — | Opaque pagination cursor returned in the previous response's `nextCursor` field. |
| `limit` | int | No | `25` | Maximum number of items to return. Clamped between 1 and 100. |

### Response — `200 OK`

```json
{
  "items": [
    {
      "matchId": "550e8400-e29b-41d4-a716-446655440000",
      "status": "ACTIVE",
      "participant": {
        "userId": "550e8400-e29b-41d4-a716-446655440001",
        "displayName": "Selam",
        "avatarUrl": "https://supabase.example.com/signed/...",
        "isVerified": true,
        "activityStatus": "ONLINE"
      },
      "lastMessage": {
        "id": "550e8400-e29b-41d4-a716-446655440002",
        "sequenceNumber": 42,
        "senderUserId": "550e8400-e29b-41d4-a716-446655440001",
        "messageType": "TEXT",
        "preview": "Hey, how are you?",
        "createdAt": "2026-07-14T01:30:00Z"
      },
      "unreadCount": 3,
      "mutedUntil": null,
      "matchedAt": "2026-07-10T12:00:00Z",
      "lastMessageAt": "2026-07-14T01:30:00Z"
    }
  ],
  "nextCursor": "eyJmaWx0ZXIiOiJBTGwiLCJsYXN0TWVzc2FnZUF0IjoiMjAyNi0wNy0xNFQwMTozMDowMFoifQ=="
}
```

### Fields

**InboxResponse**

| Field | Type | Description |
|-------|------|-------------|
| `items` | `InboxItemDto[]` | List of inbox items. |
| `nextCursor` | `string?` | Opaque cursor for the next page. `null` if there are no more results. |

**InboxItemDto**

| Field | Type | Description |
|-------|------|-------------|
| `matchId` | `UUID` | Unique identifier for the match. |
| `status` | `string` | Match status. Always `ACTIVE` for items returned in the inbox. |
| `participant` | `ParticipantDto` | The other user in the conversation. |
| `lastMessage` | `LastMessageDto?` | Preview of the last message in the conversation. `null` if no messages have been sent. |
| `unreadCount` | `int` | Number of unread messages for the caller in this match. |
| `mutedUntil` | `Instant?` | If the caller has muted this conversation, the UTC timestamp until which notifications are suppressed. `null` if not muted. |
| `matchedAt` | `Instant` | When the match was created. |
| `lastMessageAt` | `Instant?` | When the last message was sent. `null` if no messages exist. |

**ParticipantDto**

| Field | Type | Description |
|-------|------|-------------|
| `userId` | `UUID` | The other participant's user ID. |
| `displayName` | `string` | Display name from the participant's profile. |
| `avatarUrl` | `string?` | Signed URL to the participant's primary profile photo. `null` if no approved photo. |
| `isVerified` | `boolean` | Whether the participant's profile is verified. |
| `activityStatus` | `string` | Activity status. One of: `ONLINE`, `RECENTLY_ACTIVE`, `OFFLINE`. |

**LastMessageDto**

| Field | Type | Description |
|-------|------|-------------|
| `id` | `UUID` | Message ID. |
| `sequenceNumber` | `long` | Monotonically increasing sequence number within the match. |
| `senderUserId` | `UUID` | ID of the user who sent the message. |
| `messageType` | `string` | Message type (e.g. `TEXT`). |
| `preview` | `string` | Truncated preview of the message body (max 100 chars). For attachment-only messages, shows `Photo` or `Voice message`. |
| `createdAt` | `Instant` | When the message was created. |

---

## 2. Get Match Metadata

Retrieves metadata for a specific match, including the participant's profile and the current receipt state (delivered/read sequence numbers for both users).

### Request

```
GET /api/v1/chat/matches/{matchId}
```

### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `matchId` | `UUID` | Unique identifier for the match. |

### Response — `200 OK`

```json
{
  "matchId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "ACTIVE",
  "participant": {
    "userId": "550e8400-e29b-41d4-a716-446655440001",
    "displayName": "Selam",
    "avatarUrl": "https://supabase.example.com/signed/...",
    "isVerified": true,
    "activityStatus": "RECENTLY_ACTIVE"
  },
  "receiptState": {
    "myLastDeliveredSequence": 40,
    "myLastReadSequence": 38,
    "participantLastDeliveredSequence": 42,
    "participantLastReadSequence": 42
  }
}
```

### Fields

**ChatMatchMetadataDto**

| Field | Type | Description |
|-------|------|-------------|
| `matchId` | `UUID` | Unique identifier for the match. |
| `status` | `string` | Match status (e.g. `ACTIVE`). |
| `participant` | `ParticipantDto` | The other user's profile info. See [ParticipantDto](#participantdto) above. |
| `receiptState` | `ReceiptStateDto` | Delivery and read receipt state. |

**ReceiptStateDto**

| Field | Type | Description |
|-------|------|-------------|
| `myLastDeliveredSequence` | `long` | The highest sequence number the caller has marked as delivered. |
| `myLastReadSequence` | `long` | The highest sequence number the caller has marked as read. |
| `participantLastDeliveredSequence` | `long` | The highest sequence number the other participant has marked as delivered. |
| `participantLastReadSequence` | `long` | The highest sequence number the other participant has marked as read. |

---

## 3. Get Messages

Retrieves a paginated list of messages in a match. Messages are ordered by `sequenceNumber` descending (newest first). Supports cursor-based pagination via `beforeSequence` or `afterSequence` (mutually exclusive).

### Request

```
GET /api/v1/chat/matches/{matchId}/messages
```

### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `matchId` | `UUID` | Unique identifier for the match. |

### Query Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `beforeSequence` | `long` | No | — | Return messages with `sequenceNumber < beforeSequence` (older messages). Must be > 0. |
| `afterSequence` | `long` | No | — | Return messages with `sequenceNumber > afterSequence` (newer messages). Must be >= 0. |
| `limit` | `int` | No | `50` | Maximum number of messages to return. Clamped between 1 and 100. |

> **Note:** `beforeSequence` and `afterSequence` are mutually exclusive. If both are provided, the API returns a `400 INVALID_MESSAGE` error. If neither is provided, the latest messages are returned.

### Response — `200 OK`

```json
{
  "matchId": "550e8400-e29b-41d4-a716-446655440000",
  "participantActivityStatus": "ONLINE",
  "items": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440002",
      "matchId": "550e8400-e29b-41d4-a716-446655440000",
      "sequenceNumber": 42,
      "senderUserId": "550e8400-e29b-41d4-a716-446655440001",
      "messageType": "TEXT",
      "body": "Hey, how are you?",
      "deliveryStatus": "READ",
      "createdAt": "2026-07-14T01:30:00Z",
      "attachments": []
    },
    {
      "id": "660e8400-e29b-41d4-a716-446655440003",
      "matchId": "550e8400-e29b-41d4-a716-446655440000",
      "sequenceNumber": 41,
      "senderUserId": "550e8400-e29b-41d4-a716-446655440000",
      "messageType": "TEXT",
      "body": null,
      "deliveryStatus": "READ",
      "createdAt": "2026-07-14T01:25:00Z",
      "attachments": [
        {
          "id": "770e8400-e29b-41d4-a716-446655440010",
          "messageId": "660e8400-e29b-41d4-a716-446655440003",
          "attachmentType": "IMAGE",
          "fileName": "sunset.jpg",
          "contentType": "image/jpeg",
          "fileSizeBytes": 2048576,
          "durationMs": null,
          "downloadUrl": "https://supabase.example.com/signed/...",
          "createdAt": "2026-07-14T01:25:00Z"
        }
      ]
    }
  ],
  "hasMore": true
}
```

### Fields

**MessagesResponse**

| Field | Type | Description |
|-------|------|-------------|
| `matchId` | `UUID` | Unique identifier for the match. |
| `participantActivityStatus` | `string` | The other participant's activity status. One of: `ONLINE`, `RECENTLY_ACTIVE`, `OFFLINE`. |
| `items` | `ChatMessageDto[]` | List of messages ordered by `sequenceNumber` descending. |
| `hasMore` | `boolean` | Whether more messages are available in the requested direction. |

**ChatMessageDto**

| Field | Type | Description |
|-------|------|-------------|
| `id` | `UUID` | Unique message identifier. |
| `matchId` | `UUID` | Match ID this message belongs to. |
| `sequenceNumber` | `long` | Monotonically increasing sequence number within the match. |
| `senderUserId` | `UUID` | ID of the user who sent the message. |
| `messageType` | `string` | Message type (currently always `TEXT`). |
| `body` | `string?` | Message text. `null` for attachment-only messages. |
| `deliveryStatus` | `string` | Delivery status relative to the caller. One of: `SENT`, `DELIVERED`, `READ`. See note below. |
| `createdAt` | `Instant` | When the message was created (UTC). |
| `attachments` | `ChatAttachmentDto[]` | List of attachments on this message. Empty array for text-only messages. |

> **`deliveryStatus` semantics:** For messages sent by the caller, the status reflects the other participant's receipt progress (`SENT` → not yet delivered, `DELIVERED` → delivered but not read, `READ` → read). For messages received by the caller, the status is always `READ` if the caller has read them, otherwise `DELIVERED` or `SENT` depending on the caller's own receipt progress.

**ChatAttachmentDto**

| Field | Type | Description |
|-------|------|-------------|
| `id` | `UUID` | Unique attachment identifier. |
| `messageId` | `UUID` | ID of the message this attachment belongs to. |
| `attachmentType` | `string` | Type of attachment. One of: `IMAGE`, `VOICE`. |
| `fileName` | `string` | Original file name provided by the client. |
| `contentType` | `string` | MIME type of the file (e.g. `image/jpeg`, `audio/m4a`). |
| `fileSizeBytes` | `long` | Size of the file in bytes. |
| `durationMs` | `long?` | Duration of voice attachments in milliseconds. `null` for image attachments. |
| `downloadUrl` | `string?` | Short-lived signed URL for downloading the attachment. Expires after 300 seconds (configurable). Use the [Refresh Attachment Signed URL](#6-refresh-attachment-signed-url) endpoint to get a new URL when expired. |
| `createdAt` | `Instant` | When the attachment was created (UTC). |

---

## 4. Send Text Message

Sends a text message in an active match. Uses `clientMessageId` for idempotency — if a message with the same `clientMessageId` already exists for the caller in this match, the existing message is returned with `200 OK` instead of creating a duplicate.

### Request

```
POST /api/v1/chat/matches/{matchId}/messages
Content-Type: application/json
```

### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `matchId` | `UUID` | Unique identifier for the match. |

### Request Body

```json
{
  "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
  "messageType": "TEXT",
  "body": "Hey, how are you?"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `clientMessageId` | `UUID` | Yes | Client-generated unique ID for idempotency. Retries with the same ID return the original message. |
| `messageType` | `string` | Yes | Message type. Currently only `TEXT` is supported. |
| `body` | `string` | No* | Message text. Required if no attachments are being sent. |

> *For the text-only endpoint, `body` must not be blank. Use the [attachments endpoint](#5-send-message-with-attachments) for messages with files.*

### Response — `201 Created` (new message) or `200 OK` (idempotent retry)

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440002",
  "matchId": "550e8400-e29b-41d4-a716-446655440000",
  "sequenceNumber": 43,
  "senderUserId": "550e8400-e29b-41d4-a716-446655440000",
  "messageType": "TEXT",
  "body": "Hey, how are you?",
  "deliveryStatus": "SENT",
  "createdAt": "2026-07-14T01:35:00Z",
  "attachments": []
}
```

Returns a `ChatMessageDto`. See [ChatMessageDto](#chatmessagedto) for field details.

### Idempotency Behavior

| Scenario | HTTP Status | Behavior |
|----------|-------------|----------|
| New message | `201 Created` | Message is created and returned. |
| Retry with same `clientMessageId` and same `body` | `200 OK` | Original message is returned. No duplicate created. |
| Retry with same `clientMessageId` but different `body` | `409 Conflict` | Returns `IDEMPOTENCY_CONFLICT` error. |

### Rate Limiting

- **Per user:** 30 messages per minute across all matches.
- **Per match:** 12 messages per minute.
- On rate limit exceeded: `429 Too Many Requests` with `Retry-After` header (in seconds).

---

## 5. Send Message with Attachments

Sends a message with image and/or voice attachments in an active match. Supports optional text body alongside attachments. Uses `multipart/form-data` to upload files. Uses `clientMessageId` for idempotency.

### Request

```
POST /api/v1/chat/matches/{matchId}/messages/attachments
Content-Type: multipart/form-data
```

### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `matchId` | `UUID` | Unique identifier for the match. |

### Parts

| Part Name | Type | Required | Description |
|-----------|------|----------|-------------|
| `request` | JSON string (any content-type) | Yes | JSON object containing message metadata. The part name must be `request`. Content-Type header on the part is not required. |
| `files` | `file[]` (file parts) | No* | One or more image/voice files. Use the same part name `files` for each file. |

> *The message must contain at least a non-blank `body` or at least one file. If neither is provided, the API returns `422 INVALID_MESSAGE`.

**`request` JSON object fields:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `clientMessageId` | `UUID` | Yes | Client-generated unique ID for idempotency. |
| `messageType` | `string` | Yes | Message type. Currently only `TEXT` is supported. |
| `body` | `string` | No | Optional text body to accompany attachments. Omit or set to `null` for attachment-only messages. |
| `durations` | `long[]` | No | Duration in milliseconds for each voice file. Required when any voice file is included. Order must correspond to the order of `files` parts. Preferred over the query-param `durations` fallback. |

> **Durations note:** `durations` should be sent inside the `request` JSON body (e.g. `"durations": [5000]`). As a fallback, they can be sent as repeated query params: `?durations=5000&durations=3000`. Do **not** send them as a JSON array string in the query string (i.e. `?durations=[5000]` will not work).

### Example `request` JSON

```json
{
  "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
  "messageType": "TEXT",
  "body": "Check out this photo!",
  "durations": [5000]
}
```

### Example (cURL)

```bash
curl -X POST \
  "https://api.qaliye.com/api/v1/chat/matches/550e8400-e29b-41d4-a716-446655440000/messages/attachments" \
  -H "Authorization: Bearer <token>" \
  -F 'request={"clientMessageId":"550e8400-e29b-41d4-a716-446655440000","messageType":"TEXT","body":"Check this out!","durations":[5000]}' \
  -F "files=@photo.jpg;type=image/jpeg" \
  -F "files=@voice.m4a;type=audio/m4a"
```

### Response — `201 Created` (new message) or `200 OK` (idempotent retry)

```json
{
  "id": "660e8400-e29b-41d4-a716-446655440003",
  "matchId": "550e8400-e29b-41d4-a716-446655440000",
  "sequenceNumber": 44,
  "senderUserId": "550e8400-e29b-41d4-a716-446655440000",
  "messageType": "TEXT",
  "body": "Check this out!",
  "deliveryStatus": "SENT",
  "createdAt": "2026-07-14T01:40:00Z",
  "attachments": [
    {
      "id": "770e8400-e29b-41d4-a716-446655440010",
      "messageId": "660e8400-e29b-41d4-a716-446655440003",
      "attachmentType": "IMAGE",
      "fileName": "photo.jpg",
      "contentType": "image/jpeg",
      "fileSizeBytes": 2048576,
      "durationMs": null,
      "downloadUrl": "https://supabase.example.com/signed/...",
      "createdAt": "2026-07-14T01:40:00Z"
    },
    {
      "id": "880e8400-e29b-41d4-a716-446655440011",
      "messageId": "660e8400-e29b-41d4-a716-446655440003",
      "attachmentType": "VOICE",
      "fileName": "voice.m4a",
      "contentType": "audio/m4a",
      "fileSizeBytes": 512000,
      "durationMs": 5000,
      "downloadUrl": "https://supabase.example.com/signed/...",
      "createdAt": "2026-07-14T01:40:00Z"
    }
  ]
}
```

Returns a `ChatMessageDto` with populated `attachments`. See [ChatMessageDto](#chatmessagedto) and [ChatAttachmentDto](#chatattachmentdto) for field details.

### Validation Rules

See [Attachment Validation Rules](#attachment-validation-rules) for the complete list of file constraints.

### Rollback Behavior

If any file fails to upload to storage, all previously uploaded files in the same request are deleted from storage and the message is not persisted. The caller receives a `422 INVALID_MESSAGE` error.

---

## 6. Refresh Attachment Signed URL

Generates a new short-lived signed download URL for an attachment. Use this when the `downloadUrl` from a previous response has expired.

### Request

```
POST /api/v1/chat/attachments/{attachmentId}/signed-url
```

### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `attachmentId` | `UUID` | Unique identifier for the attachment. |

### Response — `200 OK`

```json
{
  "id": "770e8400-e29b-41d4-a716-446655440010",
  "messageId": "660e8400-e29b-41d4-a716-446655440003",
  "attachmentType": "IMAGE",
  "fileName": "photo.jpg",
  "contentType": "image/jpeg",
  "fileSizeBytes": 2048576,
  "durationMs": null,
  "downloadUrl": "https://supabase.example.com/signed/new-url-...",
  "createdAt": "2026-07-14T01:40:00Z"
}
```

Returns a `ChatAttachmentDto` with a fresh `downloadUrl`. See [ChatAttachmentDto](#chatattachmentdto) for field details.

### Authorization

The caller must be a participant of the match that the attachment's message belongs to. Otherwise, a `403 MATCH_ACCESS_DENIED` error is returned.

### Signed URL TTL

The signed URL is valid for **300 seconds** (5 minutes) by default. This is configurable via the `chat.attachment.signed-url-ttl-seconds` property.

---

## 7. Mark Delivered

Marks all messages up to and including the given sequence number as delivered by the caller. This updates `myLastDeliveredSequence` in the match metadata.

### Request

```
POST /api/v1/chat/matches/{matchId}/receipts/delivered
Content-Type: application/json
```

### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `matchId` | `UUID` | Unique identifier for the match. |

### Request Body

```json
{
  "upToSequence": 42
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `upToSequence` | `long` | Yes | The highest sequence number to mark as delivered. Must be >= 0. |

### Response — `204 No Content`

No response body.

### Validation

- `upToSequence` must not be null and must be >= 0.
- `upToSequence` must not be less than the current `myLastDeliveredSequence`. If it is, a `422 INVALID_RECEIPT_SEQUENCE` error is returned.

---

## 8. Mark Read

Marks all messages up to and including the given sequence number as read by the caller. This also implicitly marks them as delivered. Updates both `myLastDeliveredSequence` and `myLastReadSequence`.

### Request

```
POST /api/v1/chat/matches/{matchId}/receipts/read
Content-Type: application/json
```

### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `matchId` | `UUID` | Unique identifier for the match. |

### Request Body

```json
{
  "upToSequence": 42
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `upToSequence` | `long` | Yes | The highest sequence number to mark as read. Must be >= 0. |

### Response — `204 No Content`

No response body.

### Validation

- `upToSequence` must not be null and must be >= 0.
- `upToSequence` must not be less than the current `myLastReadSequence`. If it is, a `422 INVALID_RECEIPT_SEQUENCE` error is returned.

---

## 9. Update Notification Settings

Updates the caller's notification mute settings for a specific match. When muted, push notifications for new messages in this match are suppressed until the specified time.

### Request

```
PATCH /api/v1/chat/matches/{matchId}/notification-settings
Content-Type: application/json
```

### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `matchId` | `UUID` | Unique identifier for the match. |

### Request Body

```json
{
  "mutedUntil": "2026-07-15T00:00:00Z"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `mutedUntil` | `Instant?` | No | UTC timestamp until which notifications are muted. Set to `null` to unmute. |

### Response — `204 No Content`

No response body.

---

## Data Models

### ParticipantDto

| Field | Type | Description |
|-------|------|-------------|
| `userId` | `UUID` | The other participant's user ID. |
| `displayName` | `string` | Display name from the participant's profile. |
| `avatarUrl` | `string?` | Signed URL to the participant's primary profile photo. `null` if no approved photo. |
| `isVerified` | `boolean` | Whether the participant's profile is verified. |
| `activityStatus` | `string` | Activity status. One of: `ONLINE`, `RECENTLY_ACTIVE`, `OFFLINE`. |

### ChatMessageDto

| Field | Type | Description |
|-------|------|-------------|
| `id` | `UUID` | Unique message identifier. |
| `matchId` | `UUID` | Match ID this message belongs to. |
| `sequenceNumber` | `long` | Monotonically increasing sequence number within the match. |
| `senderUserId` | `UUID` | ID of the user who sent the message. |
| `messageType` | `string` | Message type (currently always `TEXT`). |
| `body` | `string?` | Message text. `null` for attachment-only messages. |
| `deliveryStatus` | `string` | Delivery status relative to the caller. One of: `SENT`, `DELIVERED`, `READ`. |
| `createdAt` | `Instant` | When the message was created (UTC). |
| `attachments` | `ChatAttachmentDto[]` | List of attachments on this message. Empty array for text-only messages. |

### ChatAttachmentDto

| Field | Type | Description |
|-------|------|-------------|
| `id` | `UUID` | Unique attachment identifier. |
| `messageId` | `UUID` | ID of the message this attachment belongs to. |
| `attachmentType` | `string` | Type of attachment. One of: `IMAGE`, `VOICE`. |
| `fileName` | `string` | Original file name provided by the client. |
| `contentType` | `string` | MIME type of the file. |
| `fileSizeBytes` | `long` | Size of the file in bytes. |
| `durationMs` | `long?` | Duration of voice attachments in milliseconds. `null` for image attachments. |
| `downloadUrl` | `string?` | Short-lived signed URL for downloading the attachment. |
| `createdAt` | `Instant` | When the attachment was created (UTC). |

### ReceiptStateDto

| Field | Type | Description |
|-------|------|-------------|
| `myLastDeliveredSequence` | `long` | Highest sequence number the caller has marked as delivered. |
| `myLastReadSequence` | `long` | Highest sequence number the caller has marked as read. |
| `participantLastDeliveredSequence` | `long` | Highest sequence number the other participant has marked as delivered. |
| `participantLastReadSequence` | `long` | Highest sequence number the other participant has marked as read. |

---

## Error Responses

All chat endpoint errors return a standardized `ChatProblemDetail` response body:

```json
{
  "type": "https://api.qaliye.com/problems/MATCH_NOT_FOUND",
  "title": "Match not found",
  "status": 404,
  "code": "MATCH_NOT_FOUND",
  "detail": "Match not found.",
  "instance": "/api/v1/chat/matches/550e8400-...",
  "requestId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

### Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `UNAUTHORIZED` | `401` | Caller is not authenticated. |
| `ACCOUNT_NOT_ACTIVE` | `403` | Caller's account is not active. |
| `MATCH_NOT_FOUND` | `404` | The specified match does not exist. |
| `MATCH_ACCESS_DENIED` | `403` | Caller is not a participant of the match. |
| `MATCH_NOT_ACTIVE` | `409` | The match is no longer active (e.g. unmatched or expired). |
| `USER_BLOCKED` | `403` | A block exists between the participants. |
| `MESSAGE_NOT_FOUND` | `404` | The specified message does not exist. |
| `INVALID_CURSOR` | `400` | The pagination cursor is invalid or has been tampered with. |
| `INVALID_RECEIPT_SEQUENCE` | `422` | The receipt sequence number is invalid (e.g. going backwards). |
| `INVALID_MESSAGE` | `422` | The message or attachment is invalid (empty body, unsupported file type, oversized file, missing voice duration, too many attachments, storage upload failure, etc.). |
| `IDEMPOTENCY_CONFLICT` | `409` | A message with the same `clientMessageId` already exists with different content. |
| `RATE_LIMITED` | `429` | Rate limit exceeded. Includes `Retry-After` header (in seconds). |

---

## Realtime Events

Chat messages are delivered in realtime via the outbox event system. The following event types are published:

### `message.created`

Published when a new message is sent (text or with attachments).

```json
{
  "eventType": "message.created",
  "matchId": "550e8400-e29b-41d4-a716-446655440000",
  "messageId": "660e8400-e29b-41d4-a716-446655440003",
  "sequenceNumber": 44,
  "senderUserId": "550e8400-e29b-41d4-a716-446655440000",
  "messageType": "TEXT",
  "body": "Check this out!",
  "occurredAt": "2026-07-14T01:40:00Z",
  "attachments": [
    {
      "id": "770e8400-e29b-41d4-a716-446655440010",
      "attachmentType": "IMAGE",
      "fileName": "photo.jpg",
      "contentType": "image/jpeg",
      "fileSizeBytes": 2048576,
      "durationMs": null,
      "createdAt": "2026-07-14T01:40:00Z"
    }
  ]
}
```

> **Note:** Realtime events include attachment **metadata** (id, type, fileName, contentType, fileSizeBytes, durationMs, createdAt) but do **not** include signed download URLs or storage paths. Clients must use the [Get Messages](#3-get-messages) endpoint or the [Refresh Attachment Signed URL](#6-refresh-attachment-signed-url) endpoint to obtain download URLs.

### `receipt.updated`

Published when delivery or read receipts are updated.

```json
{
  "eventType": "receipt.updated",
  "matchId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "lastDeliveredSequence": 42,
  "lastReadSequence": 42,
  "occurredAt": "2026-07-14T01:45:00Z"
}
```

---

## Attachment Validation Rules

### Supported File Types

| Category | Allowed MIME Types |
|----------|-------------------|
| Image | `image/jpeg`, `image/png`, `image/webp`, `image/gif`, `image/bmp`, `image/heic`, `image/heif`, `image/avif`, `image/tiff` |
| Voice | `audio/m4a`, `audio/mp4`, `audio/aac`, `audio/mpeg`, `audio/x-m4a`, `audio/mp3`, `audio/ogg`, `audio/wav`, `audio/x-wav`, `audio/webm`, `audio/flac`, `audio/3gpp`, `audio/amr` |

> Video, documents, and other file types are **not** supported.

### Size Limits

| Limit | Default Value | Config Property |
|-------|---------------|-----------------|
| Max image file size | 25 MiB (26,214,400 bytes) | `chat.attachment.image-max-file-size-bytes` |
| Max voice file size | 25 MiB (26,214,400 bytes) | `chat.attachment.voice-max-file-size-bytes` |

### Duration Limits

| Limit | Default Value | Config Property |
|-------|---------------|-----------------|
| Max voice duration | 300 seconds (5 minutes) | `chat.attachment.voice-max-duration-seconds` |

### Attachment Count Limits

| Limit | Default Value | Config Property |
|-------|---------------|-----------------|
| Max images per message | 5 | `chat.attachment.max-image-attachments` |
| Max voice clips per message | 1 | `chat.attachment.max-voice-attachments` |
| Max total attachments per message | 5 | `chat.attachment.max-total-attachments` |

### Voice Duration Requirement

When sending voice attachments, the `durations` query parameter must be provided with a duration value (in milliseconds) for each voice file. If `durations` is missing or `null` for a voice file, the API returns `422 INVALID_MESSAGE`.

### Storage

- Attachments are stored in a private Supabase Storage bucket named `chat-attachments` (configurable via `chat.attachment.bucket`).
- Storage path format: `{matchId}/{messageId}/{uuid}/{sanitizedFileName}`.
- Raw storage paths are never exposed to clients.
- Download access is only via short-lived signed URLs generated by the backend.

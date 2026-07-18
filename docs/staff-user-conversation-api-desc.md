# Support Chat API — Staff & User Conversations

This document describes the backend endpoints for the support chat system.
There are two controllers: **User** (`/api/v1/support`) for regular users and **Staff** (`/api/v1/staff/support`) for admin/moderator staff.

All endpoints require a valid JWT bearer token in the `Authorization` header.
The caller's user ID is extracted server-side from the JWT `sub` claim — no client-supplied user ID is trusted.

All JSON responses use **snake_case** field names.

---

## Table of Contents

- [Shared Data Models](#shared-data-models)
- [User Endpoints](#user-endpoints)
  - [GET /api/v1/support/conversation](#1-get-apiv1supportconversation)
  - [GET /api/v1/support/conversation/messages](#2-get-apiv1supportconversationmessages)
  - [POST /api/v1/support/conversation/messages](#3-post-apiv1supportconversationmessages)
  - [POST /api/v1/support/conversation/read](#4-post-apiv1supportconversationread)
  - [POST /api/v1/support/conversation/close](#5-post-apiv1supportconversationclose)
  - [GET /api/v1/support/attachments/{attachmentId}/download-url](#6-get-apiv1supportattachmentsattachmentiddownload-url)
- [Staff Endpoints](#staff-endpoints)
  - [GET /api/v1/staff/support/conversations](#1-get-apiv1staffsupportconversations)
  - [GET /api/v1/staff/support/conversations/{conversationId}](#2-get-apiv1staffsupportconversationsconversationid)
  - [GET /api/v1/staff/support/conversations/{conversationId}/messages](#3-get-apiv1staffsupportconversationsconversationidmessages)
  - [POST /api/v1/staff/support/conversations/{conversationId}/messages](#4-post-apiv1staffsupportconversationsconversationidmessages)
  - [GET /api/v1/staff/support/conversations/{conversationId}/notes](#5-get-apiv1staffsupportconversationsconversationidnotes)
  - [POST /api/v1/staff/support/conversations/{conversationId}/notes](#6-post-apiv1staffsupportconversationsconversationidnotes)
  - [POST /api/v1/staff/support/conversations/{conversationId}/read](#7-post-apiv1staffsupportconversationsconversationidread)
  - [PATCH /api/v1/staff/support/conversations/{conversationId}/assignment](#8-patch-apiv1staffsupportconversationsconversationidassignment)
  - [PATCH /api/v1/staff/support/conversations/{conversationId}/priority](#9-patch-apiv1staffsupportconversationsconversationidpriority)
  - [POST /api/v1/staff/support/conversations/{conversationId}/close](#10-post-apiv1staffsupportconversationsconversationidclose)
  - [POST /api/v1/staff/support/conversations/{conversationId}/reopen](#11-post-apiv1staffsupportconversationsconversationidreopen)
  - [GET /api/v1/staff/support/conversations/{conversationId}/attachments/{attachmentId}/download-url](#12-get-apiv1staffsupportconversationsconversationidattachmentsattachmentiddownload-url)
- [Attachment Constraints](#attachment-constraints)
- [Error Responses](#error-responses)

---

## Shared Data Models

### SupportConversationDto

Returned to **users** to represent their support conversation.

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Conversation ID |
| `status` | String | Conversation status: `IDLE`, `WAITING_STAFF`, `WAITING_USER`, `CLOSED` |
| `user_last_read_sequence` | long | Last message sequence the user has read |
| `next_public_sequence` | long | Sequence number of the next public message (total count) |
| `last_public_message_at` | OffsetDateTime (nullable) | Timestamp of the most recent public message |
| `last_public_message_sender_type` | String (nullable) | Sender type of the last message: `USER` or `STAFF` |
| `closed_at` | OffsetDateTime (nullable) | When the conversation was closed |
| `created_at` | OffsetDateTime | When the conversation was created |

### StaffConversationSummaryDto

Returned in the **staff conversation list**. One per conversation.

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Conversation ID |
| `user_id` | UUID | ID of the user who owns this conversation |
| `status` | String | `IDLE`, `WAITING_STAFF`, `WAITING_USER`, `CLOSED` |
| `priority` | int | Priority level (1–5, where 5 is highest) |
| `assigned_staff_user_id` | UUID (nullable) | Staff member assigned to this conversation |
| `next_public_sequence` | long | Total public message count |
| `staff_last_read_sequence` | long | Last sequence read by any staff member |
| `waiting_since` | OffsetDateTime (nullable) | When the conversation entered `WAITING_STAFF` |
| `last_public_message_at` | OffsetDateTime (nullable) | Timestamp of the last public message |
| `last_public_message_sender_type` | String (nullable) | `USER` or `STAFF` |
| `created_at` | OffsetDateTime | When the conversation was created |

### StaffConversationDetailDto

Returned when staff **open a single conversation**. Contains more fields than the summary.

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Conversation ID |
| `user_id` | UUID | ID of the user who owns this conversation |
| `status` | String | `IDLE`, `WAITING_STAFF`, `WAITING_USER`, `CLOSED` |
| `priority` | int | Priority level (1–5) |
| `assigned_staff_user_id` | UUID (nullable) | Assigned staff member |
| `next_public_sequence` | long | Total public message count |
| `user_last_read_sequence` | long | Last sequence the user has read |
| `staff_last_read_sequence` | long | Last sequence read by any staff |
| `my_last_read_sequence` | long | Last sequence the calling staff member has read |
| `waiting_since` | OffsetDateTime (nullable) | When the conversation started waiting for staff |
| `first_staff_response_at` | OffsetDateTime (nullable) | When a staff member first responded |
| `last_activity_at` | OffsetDateTime (nullable) | Last activity timestamp |
| `last_public_message_at` | OffsetDateTime (nullable) | Timestamp of the last public message |
| `last_public_message_sender_type` | String (nullable) | `USER` or `STAFF` |
| `closed_at` | OffsetDateTime (nullable) | When the conversation was closed |
| `closed_by_type` | String (nullable) | Who closed it: `USER` or `STAFF` |
| `created_at` | OffsetDateTime | When the conversation was created |
| `updated_at` | OffsetDateTime | When the conversation was last updated |

### SupportMessageDto

Represents a single chat message.

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Message ID |
| `conversation_id` | UUID | Conversation this message belongs to |
| `sequence_number` | long | Monotonically increasing sequence within the conversation |
| `sender_type` | String | `USER` or `STAFF` |
| `sender_display_name` | String (nullable) | Display name of the sender |
| `body` | String (nullable) | Message text (may be null if only attachments) |
| `created_at` | OffsetDateTime | When the message was sent |
| `attachments` | List&lt;SupportAttachmentDto&gt; | Attached files (may be empty) |

### SupportAttachmentDto

Represents a file attached to a message.

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Attachment ID |
| `message_id` | UUID | Message this attachment belongs to |
| `file_name` | String | Sanitized file name |
| `content_type` | String | MIME type |
| `file_size_bytes` | long | File size in bytes |
| `signed_url` | String | Pre-signed URL for downloading the attachment (expires after 300 seconds) |
| `attachment_kind` | String (nullable) | Attachment category: `IMAGE`, `DOCUMENT`, `TEXT`, `VOICE`, `OTHER` |
| `duration_ms` | Long (nullable) | Duration in milliseconds (only for `VOICE` attachments) |
| `created_at` | OffsetDateTime | When the attachment was uploaded |

### SupportMessagePageDto

Paginated message list response.

| Field | Type | Description |
|-------|------|-------------|
| `messages` | List&lt;SupportMessageDto&gt; | Messages in this page (newest first) |
| `next_before_sequence` | Long (nullable) | Cursor for the next page. Pass this as `before_sequence` to load older messages. `null` if there are no more messages. |

### SupportInternalNoteDto

Internal staff note (not visible to users).

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Note ID |
| `conversation_id` | UUID | Conversation this note belongs to |
| `staff_user_id` | UUID | Staff member who wrote the note |
| `staff_display_name` | String (nullable) | Display name of the staff member who wrote the note |
| `body` | String | Note text |
| `created_at` | OffsetDateTime | When the note was created |

---

## User Endpoints

Base path: `/api/v1/support`
Auth: JWT bearer token (user role)

### 1. GET /api/v1/support/conversation

Get the current user's support conversation.

**Purpose:** Retrieves the user's single support conversation. A conversation is auto-provisioned on first access.

**Request:** No parameters.

**Response:** `200 OK`

```json
{
  "id": "uuid",
  "status": "IDLE",
  "user_last_read_sequence": 5,
  "next_public_sequence": 10,
  "last_public_message_at": "2026-07-13T12:00:00Z",
  "last_public_message_sender_type": "STAFF",
  "closed_at": null,
  "created_at": "2026-07-10T09:00:00Z"
}
```

**Errors:**
- `404` — Conversation not found (not yet provisioned)

---

### 2. GET /api/v1/support/conversation/messages

List messages in the user's support conversation with cursor-based pagination.

**Purpose:** Fetches a page of messages ordered newest-first. Use `before_sequence` for infinite scroll / load older messages.

**Query Parameters:**

| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `before_sequence` | long | No | — | Only return messages with `sequence_number < before_sequence`. Omit to get the latest messages. |
| `limit` | int | No | `25` | Max messages per page (capped at 50, min 1) |

**Response:** `200 OK`

```json
{
  "messages": [
    {
      "id": "uuid",
      "conversation_id": "uuid",
      "sequence_number": 10,
      "sender_type": "STAFF",
      "body": "How can I help you?",
      "created_at": "2026-07-13T12:00:00Z",
      "attachments": []
    }
  ],
  "next_before_sequence": 5
}
```

**Pagination logic:** If `next_before_sequence` is `null`, there are no older messages. Otherwise, pass it as `before_sequence` in the next request to fetch the next page.

**Errors:**
- `404` — Conversation not found

---

### 3. POST /api/v1/support/conversation/messages

Send a message in the user's support conversation.

**Purpose:** The user sends a text message and/or file attachments. At least a body or one file is required.

**Content-Type:** `multipart/form-data`

**Form Parameters:**

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `clientMessageId` | UUID | Yes | Client-generated unique ID for idempotency. Duplicate submissions with the same ID return the original message (HTTP 409). |
| `body` | String | No* | Message text. Stripped of leading/trailing whitespace. |
| `files` | File[] (repeated) | No* | Attachment files. Multiple files allowed via repeated `files` parts. |
| `durations` | String (JSON array) | No | JSON array of durations in milliseconds for voice attachments. Must be provided when uploading audio files. Example: `[5000, 3000]`. Non-voice attachments should have `null` entries. |

\* At least one of `body` or `files` must be provided.

**Voice messages:** When uploading audio files (`.m4a`, `.aac`, `.mp3`, `.wav`, `.webm`), the `durations` parameter must be provided as a JSON array string with one duration (in milliseconds) per audio file. Non-audio files should have `null` in their corresponding position. The maximum allowed duration is 300,000 ms (5 minutes).

**Response:** `201 Created`

```json
{
  "id": "uuid",
  "conversation_id": "uuid",
  "sequence_number": 11,
  "sender_type": "USER",
  "body": "I have a billing issue",
  "created_at": "2026-07-13T12:05:00Z",
  "attachments": [
    {
      "id": "uuid",
      "message_id": "uuid",
      "file_name": "screenshot.png",
      "content_type": "image/png",
      "file_size_bytes": 102400,
      "signed_url": "https://storage.example.com/...",
      "attachment_kind": "IMAGE",
      "duration_ms": null,
      "created_at": "2026-07-13T12:05:00Z"
    }
  ]
}
```

**Voice message example:**

```
POST /api/v1/support/conversation/messages
Content-Type: multipart/form-data

clientMessageId: <uuid>
body: (empty)
files: voice.m4a  (audio/m4a, 15 KB)
durations: [18450]
```

**Errors:**
- `400` — Message must contain a body or at least one attachment
- `400` — More than 10 attachments
- `400` — File exceeds 50 MiB (general) or 50 MiB (voice)
- `400` — Content type not in allowed list
- `400` — Voice file duration exceeds 300,000 ms (5 minutes)
- `400` — Voice file missing `durations` parameter
- `400` — Voice file has disallowed file extension
- `404` — Conversation not found
- `409` — Idempotency conflict (duplicate `clientMessageId`)
- `422` — Conversation is closed (cannot send to a closed conversation)

---

### 4. POST /api/v1/support/conversation/read

Mark messages as read up to a given sequence number.

**Purpose:** Advances the user's `last_read_sequence` cursor so the backend knows which messages the user has seen.

**Request Body:** `application/json`

```json
{
  "last_read_sequence": 10
}
```

| Field | Type | Required | Validation | Description |
|-------|------|----------|------------|-------------|
| `last_read_sequence` | long | Yes | `≥ 0` | The highest message sequence the user has read |

**Response:** `204 No Content` (empty body)

**Errors:**
- `400` — `last_read_sequence` is null or negative
- `404` — Conversation not found

---

### 5. POST /api/v1/support/conversation/close

Close the user's own support conversation.

**Purpose:** The user voluntarily closes the conversation. A closed conversation cannot receive new messages until reopened by staff.

**Request:** No body or parameters.

**Response:** `204 No Content` (empty body)

**Errors:**
- `404` — Conversation not found
- `422` — Conversation is already closed or not in a closeable state

---

### 6. GET /api/v1/support/attachments/{attachmentId}/download-url

Get a short-lived signed URL to download an attachment.

**Purpose:** Returns a pre-signed URL the frontend can use to download the file directly from object storage. The URL expires after 300 seconds (5 minutes).

**Path Parameters:**

| Param | Type | Description |
|-------|------|-------------|
| `attachmentId` | UUID | ID of the attachment to download |

**Response:** `200 OK`

```json
{
  "download_url": "https://storage.example.com/support-attachments/...?signature=..."
}
```

**Errors:**
- `403` — Attachment does not belong to the calling user
- `404` — Attachment not found

---

## Staff Endpoints

Base path: `/api/v1/staff/support`
Auth: JWT bearer token (ADMIN or MODERATOR role)

### 1. GET /api/v1/staff/support/conversations

List support conversations with optional filters.

**Purpose:** Returns a paginated list of conversation summaries for the staff dashboard. Supports filtering by status, assignment, and priority.

**Query Parameters:**

| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `status` | String | No | — | Filter by status: `IDLE`, `WAITING_STAFF`, `WAITING_USER`, `CLOSED` |
| `assignedToStaffId` | UUID | No | — | Filter by assigned staff member |
| `unassignedOnly` | boolean | No | — | If `true`, only return conversations with no assigned staff |
| `priority` | int | No | — | Filter by exact priority (1–5) |
| `limit` | int | No | `25` | Max results per page |
| `offset` | int | No | `0` | Pagination offset |

**Response:** `200 OK`

```json
[
  {
    "id": "uuid",
    "user_id": "uuid",
    "status": "WAITING_STAFF",
    "priority": 3,
    "assigned_staff_user_id": null,
    "next_public_sequence": 8,
    "staff_last_read_sequence": 5,
    "waiting_since": "2026-07-13T11:00:00Z",
    "last_public_message_at": "2026-07-13T11:30:00Z",
    "last_public_message_sender_type": "USER",
    "created_at": "2026-07-10T09:00:00Z"
  }
]
```

**Errors:**
- `403` — Caller does not have ADMIN or MODERATOR role

---

### 2. GET /api/v1/staff/support/conversations/{conversationId}

Get detailed information about a single conversation.

**Purpose:** Returns full conversation metadata for the staff chat view, including read cursors for both the user and the calling staff member.

**Path Parameters:**

| Param | Type | Description |
|-------|------|-------------|
| `conversationId` | UUID | ID of the conversation |

**Response:** `200 OK`

```json
{
  "id": "uuid",
  "user_id": "uuid",
  "status": "WAITING_USER",
  "priority": 2,
  "assigned_staff_user_id": "uuid",
  "next_public_sequence": 15,
  "user_last_read_sequence": 12,
  "staff_last_read_sequence": 14,
  "my_last_read_sequence": 14,
  "waiting_since": null,
  "first_staff_response_at": "2026-07-10T10:00:00Z",
  "last_activity_at": "2026-07-13T12:00:00Z",
  "last_public_message_at": "2026-07-13T12:00:00Z",
  "last_public_message_sender_type": "USER",
  "closed_at": null,
  "closed_by_type": null,
  "created_at": "2026-07-10T09:00:00Z",
  "updated_at": "2026-07-13T12:00:00Z"
}
```

**Errors:**
- `403` — Caller does not have ADMIN or MODERATOR role
- `404` — Conversation not found

---

### 3. GET /api/v1/staff/support/conversations/{conversationId}/messages

List messages in a conversation with cursor-based pagination.

**Purpose:** Fetches a page of messages for the staff chat view. Same pagination pattern as the user endpoint.

**Path Parameters:**

| Param | Type | Description |
|-------|------|-------------|
| `conversationId` | UUID | ID of the conversation |

**Query Parameters:**

| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `before_sequence` | long | No | — | Only return messages with `sequence_number < before_sequence` |
| `limit` | int | No | `50` | Max messages per page (capped at 50, min 1) |

**Response:** `200 OK` — same shape as [user list messages](#2-get-apiv1supportconversationmessages)

**Errors:**
- `403` — Caller does not have ADMIN or MODERATOR role
- `404` — Conversation not found

---

### 4. POST /api/v1/staff/support/conversations/{conversationId}/messages

Send a staff message in a conversation.

**Purpose:** Staff sends a reply to the user. Same multipart format as the user endpoint. Staff cannot send the first message in a conversation (the user must initiate).

**Content-Type:** `multipart/form-data`

**Path Parameters:**

| Param | Type | Description |
|-------|------|-------------|
| `conversationId` | UUID | ID of the conversation |

**Form Parameters:**

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `clientMessageId` | UUID | Yes | Client-generated unique ID for idempotency |
| `body` | String | No* | Message text |
| `files` | File[] (repeated) | No* | Attachment files |
| `durations` | String (JSON array) | No | JSON array of durations in milliseconds for voice attachments. Must be provided when uploading audio files. |

\* At least one of `body` or `files` must be provided.

**Response:** `201 Created` — same shape as [user send message](#3-post-apiv1supportconversationmessages), with `sender_type: "STAFF"`

**Errors:**
- `400` — Message must contain a body or at least one attachment
- `400` — More than 10 attachments
- `400` — File exceeds 50 MiB or disallowed content type
- `400` — Voice file duration exceeds 300,000 ms or missing `durations` parameter
- `403` — Caller does not have ADMIN or MODERATOR role
- `404` — Conversation not found
- `409` — Idempotency conflict (duplicate `clientMessageId`)
- `422` — Conversation is closed
- `422` — Staff cannot send the first public message (user must initiate)

---

### 5. GET /api/v1/staff/support/conversations/{conversationId}/notes

List internal staff notes for a conversation.

**Purpose:** Returns notes visible only to staff. Useful for handoff context and internal coordination.

**Path Parameters:**

| Param | Type | Description |
|-------|------|-------------|
| `conversationId` | UUID | ID of the conversation |

**Query Parameters:**

| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `limit` | int | No | `50` | Max notes per page |
| `offset` | int | No | `0` | Pagination offset |

**Response:** `200 OK`

```json
[
  {
    "id": "uuid",
    "conversation_id": "uuid",
    "staff_user_id": "uuid",
    "staff_display_name": "Admin Joe",
    "body": "User reported billing issue, escalated to finance team",
    "created_at": "2026-07-13T11:00:00Z"
  }
]
```

**Errors:**
- `403` — Caller does not have ADMIN or MODERATOR role
- `404` — Conversation not found

---

### 6. POST /api/v1/staff/support/conversations/{conversationId}/notes

Add an internal staff note to a conversation.

**Purpose:** Staff writes a private note that is not visible to the user. Supports idempotency via `clientNoteId`.

**Path Parameters:**

| Param | Type | Description |
|-------|------|-------------|
| `conversationId` | UUID | ID of the conversation |

**Request Body:** `application/json`

```json
{
  "client_note_id": "uuid",
  "body": "Escalating to finance team"
}
```

| Field | Type | Required | Validation | Description |
|-------|------|----------|------------|-------------|
| `client_note_id` | UUID | Yes | Not null | Client-generated unique ID for idempotency |
| `body` | String | Yes | Not blank | Note text |

**Response:** `201 Created`

```json
{
  "id": "uuid",
  "conversation_id": "uuid",
  "staff_user_id": "uuid",
  "staff_display_name": "Admin Joe",
  "body": "Escalating to finance team",
  "created_at": "2026-07-13T11:00:00Z"
}
```

**Errors:**
- `400` — `client_note_id` is null or `body` is blank
- `403` — Caller does not have ADMIN or MODERATOR role
- `404` — Conversation not found
- `409` — Idempotency conflict (duplicate `client_note_id`)

---

### 7. POST /api/v1/staff/support/conversations/{conversationId}/read

Mark messages as read by the calling staff member.

**Purpose:** Advances the calling staff member's `last_read_sequence` cursor for this conversation.

**Path Parameters:**

| Param | Type | Description |
|-------|------|-------------|
| `conversationId` | UUID | ID of the conversation |

**Request Body:** `application/json`

```json
{
  "last_read_sequence": 14
}
```

| Field | Type | Required | Validation | Description |
|-------|------|----------|------------|-------------|
| `last_read_sequence` | long | Yes | `≥ 0` | Highest message sequence the staff member has read |

**Response:** `204 No Content` (empty body)

**Errors:**
- `400` — `last_read_sequence` is null or negative
- `403` — Caller does not have ADMIN or MODERATOR role
- `404` — Conversation not found

---

### 8. PATCH /api/v1/staff/support/conversations/{conversationId}/assignment

Assign or reassign a conversation to a staff member.

**Purpose:** Sets the `assigned_staff_user_id` for the conversation. Pass `null` to unassign.

**Path Parameters:**

| Param | Type | Description |
|-------|------|-------------|
| `conversationId` | UUID | ID of the conversation |

**Request Body:** `application/json`

```json
{
  "assigned_staff_user_id": "uuid"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `assigned_staff_user_id` | UUID (nullable) | No | Staff user ID to assign. `null` to unassign. |

**Response:** `204 No Content` (empty body)

**Errors:**
- `403` — Caller does not have ADMIN or MODERATOR role
- `404` — Conversation not found

---

### 9. PATCH /api/v1/staff/support/conversations/{conversationId}/priority

Set the priority level of a conversation.

**Purpose:** Updates the conversation priority. Higher priority = more urgent.

**Path Parameters:**

| Param | Type | Description |
|-------|------|-------------|
| `conversationId` | UUID | ID of the conversation |

**Request Body:** `application/json`

```json
{
  "priority": 4
}
```

| Field | Type | Required | Validation | Description |
|-------|------|----------|------------|-------------|
| `priority` | int | Yes | `1`–`5` | Priority level (1 = low, 5 = critical) |

**Response:** `204 No Content` (empty body)

**Errors:**
- `400` — `priority` is null, less than 1, or greater than 5
- `403` — Caller does not have ADMIN or MODERATOR role
- `404` — Conversation not found

---

### 10. POST /api/v1/staff/support/conversations/{conversationId}/close

Close a conversation.

**Purpose:** Staff closes a conversation. No new messages can be sent until it is reopened.

**Path Parameters:**

| Param | Type | Description |
|-------|------|-------------|
| `conversationId` | UUID | ID of the conversation |

**Request:** No body or parameters.

**Response:** `204 No Content` (empty body)

**Errors:**
- `403` — Caller does not have ADMIN or MODERATOR role
- `404` — Conversation not found
- `422` — Conversation is already closed or not in a closeable state

---

### 11. POST /api/v1/staff/support/conversations/{conversationId}/reopen

Reopen a previously closed conversation.

**Purpose:** Staff reopens a closed conversation, allowing new messages to be sent again.

**Path Parameters:**

| Param | Type | Description |
|-------|------|-------------|
| `conversationId` | UUID | ID of the conversation |

**Request:** No body or parameters.

**Response:** `204 No Content` (empty body)

**Errors:**
- `403` — Caller does not have ADMIN or MODERATOR role
- `404` — Conversation not found
- `422` — Conversation is not closed (only closed conversations can be reopened)

---

### 12. GET /api/v1/staff/support/conversations/{conversationId}/attachments/{attachmentId}/download-url

Get a short-lived signed URL to download an attachment from a conversation.

**Purpose:** Returns a pre-signed URL for the attachment. The URL expires after 300 seconds (5 minutes). Staff can download attachments from any conversation.

**Path Parameters:**

| Param | Type | Description |
|-------|------|-------------|
| `conversationId` | UUID | ID of the conversation |
| `attachmentId` | UUID | ID of the attachment |

**Response:** `200 OK`

```json
{
  "download_url": "https://storage.example.com/support-attachments/...?signature=..."
}
```

**Errors:**
- `403` — Caller does not have ADMIN or MODERATOR role
- `404` — Attachment not found

---

## Attachment Constraints

These constraints apply to both user and staff message endpoints.

| Constraint | Value |
|------------|-------|
| Max attachments per message | 10 |
| Max file size (general) | 50 MiB (52,428,800 bytes) |
| Max file size (voice) | 50 MiB (52,428,800 bytes) |
| Max voice duration | 300,000 ms (5 minutes) |
| Allowed content types (general) | `image/jpeg`, `image/png`, `image/webp`, `application/pdf`, `text/plain` |
| Allowed audio content types | `audio/m4a`, `audio/mp4`, `audio/aac`, `audio/mpeg`, `audio/wav`, `audio/webm`, `audio/x-m4a` |
| Allowed voice file extensions | `.m4a`, `.aac`, `.mp3`, `.wav`, `.webm` |
| Attachment kinds | `IMAGE`, `DOCUMENT`, `TEXT`, `VOICE`, `OTHER` (auto-detected from content type) |
| Signed URL TTL | 300 seconds (5 minutes) |
| Storage bucket | `support-attachments` |

File names are sanitized: non-alphanumeric characters (except `.`, `_`, `-`) are replaced with `_`, and names are truncated to 200 characters.

### Voice Message Handling

When an audio file is uploaded, the backend:
1. Validates the content type is in the allowed audio list (see above)
2. Validates the file extension matches an allowed audio extension
3. Validates the file size does not exceed the voice max file size
4. Validates the duration (from `durations` parameter) does not exceed 300,000 ms
5. Normalizes the content type: `audio/x-m4a` and `audio/mp4` (with `.m4a` extension) are normalized to `audio/m4a`
6. Auto-detects `attachment_kind` as `VOICE` for all audio content types
7. Stores `duration_ms` in the attachment metadata

---

## Error Responses

All error responses follow the standard Spring `ResponseStatusException` format.

### Error format

```json
{
  "timestamp": "2026-07-13T12:00:00.000+00:00",
  "status": 404,
  "error": "Not Found",
  "message": "Support conversation not found",
  "path": "/api/v1/support/conversation"
}
```

### Status codes

| Status | Meaning |
|--------|---------|
| `400` | Validation error (missing required field, invalid value, empty message, too many attachments, disallowed content type, file too large) |
| `403` | Caller lacks required role (ADMIN or MODERATOR for staff endpoints), or user trying to access another user's attachment |
| `404` | Conversation or attachment not found |
| `409` | Idempotency conflict — a message/note with the same `clientMessageId` / `clientNoteId` already exists |
| `422` | Business rule violation (conversation is closed, staff cannot send first message, conversation not in closeable/reopenable state) |
| `500` | Unexpected server error |

### Idempotency

Both message and note creation endpoints accept a client-generated UUID (`clientMessageId` / `clientNoteId`). If a request with the same ID is submitted again, the backend returns `409 Conflict` rather than creating a duplicate. The frontend should generate a UUID per send action and retry with the same UUID on network failures.

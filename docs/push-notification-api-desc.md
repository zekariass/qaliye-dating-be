# Push Notification API

This document describes the push notification API endpoints for the Qaliye dating app backend. All endpoints require authentication via Supabase JWT.

## Base URL

The base URL depends on the environment:

- **Local Development:** `http://localhost:8080`
- **Production:** `https://api.qaliye.com`

## Authentication

All endpoints require a valid Supabase JWT in the `Authorization` header:

```
Authorization: Bearer <jwt_token>
```

The JWT subject (`sub`) must contain the user's UUID.

---

## Device Registration

### Register Notification Device

Registers a device for push notifications using Expo push tokens.

**Endpoint:** `POST /api/v1/notifications/devices`

**Request Body:**

```json
{
  "expoPushToken": "ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]",
  "platform": "IOS",
  "installationId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Fields:**

| Field | Type | Required | Description |
|------|------|----------|-------------|
| `expoPushToken` | string | Yes | Expo push token for the device |
| `platform` | string | Yes | Platform of the device. Values: `IOS`, `ANDROID` |
| `installationId` | UUID | Yes | Unique identifier for the app installation |

**Response:** `200 OK`

```json
{
  "registered": true,
  "isActive": true
}
```

**Fields:**

| Field | Type | Description |
|------|------|-------------|
| `registered` | boolean | Always `true` after a successful registration call |
| `isActive` | boolean | Whether the device is currently active for notifications. Always `true` after registration |

**Error Responses:**

- `400 Bad Request` - Invalid request body or validation error
- `401 Unauthorized` - Missing or invalid JWT
- `500 Internal Server Error` - Server error

---

### Deactivate Current Device

Deactivates push notifications for the current device installation.

**Endpoint:** `DELETE /api/v1/notifications/devices/current`

**Query Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `installation_id` | UUID | Yes | Installation ID of the device to deactivate |

**Response:** `204 No Content`

**Error Responses:**

- `400 Bad Request` - Missing or invalid installation_id
- `401 Unauthorized` - Missing or invalid JWT
- `404 Not Found` - Device not found
- `500 Internal Server Error` - Server error

---

## Notification Preferences

### Get Notification Preferences

Retrieves the current user's notification preferences.

**Endpoint:** `GET /api/v1/notifications/preferences`

**Response:** `200 OK`

```json
{
  "pushEnabled": true,
  "messageNotificationsEnabled": true,
  "matchNotificationsEnabled": true,
  "likeNotificationsEnabled": true,
  "messagePreviewEnabled": false,
  "marketingNotificationsEnabled": false,
  "marketingNotificationsOptedInAt": null,
  "marketingNotificationsConsentVersion": null
}
```

**Fields:**

| Field | Type | Description |
|------|------|-------------|
| `pushEnabled` | boolean | Master switch for all push notifications |
| `messageNotificationsEnabled` | boolean | Enable notifications for new chat messages |
| `matchNotificationsEnabled` | boolean | Enable notifications for new matches |
| `likeNotificationsEnabled` | boolean | Enable notifications for received likes |
| `messagePreviewEnabled` | boolean | Show message content in notification (vs. "New message") |
| `marketingNotificationsEnabled` | boolean | Enable marketing/promotional notifications |
| `marketingNotificationsOptedInAt` | string (ISO 8601) | Timestamp when marketing consent was given, or null |
| `marketingNotificationsConsentVersion` | string | Version of consent text agreed to, or null |

**Error Responses:**

- `401 Unauthorized` - Missing or invalid JWT
- `404 Not Found` - User preferences not found
- `500 Internal Server Error` - Server error

---

### Update Notification Preferences

Updates the current user's notification preferences. Only provided fields are updated; omitted fields remain unchanged.

**Endpoint:** `PATCH /api/v1/notifications/preferences`

**Request Body:**

```json
{
  "pushEnabled": true,
  "messageNotificationsEnabled": true,
  "matchNotificationsEnabled": true,
  "likeNotificationsEnabled": true,
  "messagePreviewEnabled": false,
  "marketingNotificationsEnabled": false,
  "marketingNotificationsConsentVersion": "1.0"
}
```

**Fields:**

| Field | Type | Required | Description |
|------|------|----------|-------------|
| `pushEnabled` | boolean | No | Master switch for all push notifications |
| `messageNotificationsEnabled` | boolean | No | Enable notifications for new chat messages |
| `matchNotificationsEnabled` | boolean | No | Enable notifications for new matches |
| `likeNotificationsEnabled` | boolean | No | Enable notifications for received likes |
| `messagePreviewEnabled` | boolean | No | Show message content in notification |
| `marketingNotificationsEnabled` | boolean | No | Enable marketing/promotional notifications |
| `marketingNotificationsConsentVersion` | string | No | Version of consent text (required when enabling marketing) |

**Response:** `200 OK`

Returns the updated preferences (same format as GET response).

**Special Behavior:**

- When `marketingNotificationsEnabled` is set to `true`, the `marketingNotificationsConsentVersion` field is required and `marketingNotificationsOptedInAt` is automatically set to the current timestamp.
- When `marketingNotificationsEnabled` is set to `false`, `marketingNotificationsOptedInAt` is cleared.

**Error Responses:**

- `400 Bad Request` - Invalid request or missing consent version when enabling marketing
- `401 Unauthorized` - Missing or invalid JWT
- `500 Internal Server Error` - Server error

---

## Admin Campaign Management

**Note:** All campaign management endpoints require admin role access.

### List Campaigns

Retrieves a paginated list of notification campaigns.

**Endpoint:** `GET /api/v1/admin/notification-campaigns`

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `status` | string | No | - | Filter by status: `DRAFT`, `SCHEDULED`, `SENDING`, `COMPLETED`, `CANCELLED` |
| `page` | integer | No | 0 | Page number (0-indexed) |
| `size` | integer | No | 20 | Page size (max 100) |

**Response:** `200 OK`

```json
{
  "content": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "campaignKey": "welcome_new_users",
      "title": "Welcome to Qaliye!",
      "body": "Start discovering amazing people today.",
      "navigationPayload": { "screen": "home" },
      "audienceDefinition": { "daysSinceSignup": 1 },
      "status": "DRAFT",
      "scheduledAt": null,
      "startedAt": null,
      "completedAt": null,
      "cancelledAt": null,
      "createdByUserId": "550e8400-e29b-41d4-a716-446655440001",
      "createdAt": "2024-01-15T10:30:00Z",
      "updatedAt": "2024-01-15T10:30:00Z"
    }
  ],
  "pageable": { ... },
  "totalElements": 1,
  "totalPages": 1,
  "size": 20,
  "number": 0
}
```

**Error Responses:**

- `401 Unauthorized` - Missing or invalid JWT
- `403 Forbidden` - User does not have admin role
- `500 Internal Server Error` - Server error

---

### Get Campaign

Retrieves a specific notification campaign by ID.

**Endpoint:** `GET /api/v1/admin/notification-campaigns/{campaignId}`

**Path Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `campaignId` | UUID | Yes | Campaign ID |

**Response:** `200 OK`

Returns a single campaign object (same format as list response).

**Error Responses:**

- `401 Unauthorized` - Missing or invalid JWT
- `403 Forbidden` - User does not have admin role
- `404 Not Found` - Campaign not found
- `500 Internal Server Error` - Server error

---

### Create Campaign

Creates a new notification campaign.

**Endpoint:** `POST /api/v1/admin/notification-campaigns`

**Request Body:**

```json
{
  "campaignKey": "weekly_reminder",
  "title": "Don't miss out!",
  "body": "You have new matches waiting for you.",
  "navigationPayload": { "screen": "matches" },
  "audienceDefinition": { "daysSinceLastActive": 7 }
}
```

**Fields:**

| Field | Type | Required | Description |
|------|------|----------|-------------|
| `campaignKey` | string | Yes | Unique key for the campaign (used for idempotency) |
| `title` | string | Yes | Notification title (max 120 characters) |
| `body` | string | Yes | Notification body (max 300 characters) |
| `navigationPayload` | object | No | JSON payload for app navigation when notification is tapped |
| `audienceDefinition` | object | No | JSON criteria defining the target audience |

**Response:** `201 Created`

Returns the created campaign object.

**Error Responses:**

- `400 Bad Request` - Invalid request body or duplicate campaignKey
- `401 Unauthorized` - Missing or invalid JWT
- `403 Forbidden` - User does not have admin role
- `500 Internal Server Error` - Server error

---

### Update Campaign

Updates an existing campaign. Only provided fields are updated.

**Endpoint:** `PATCH /api/v1/admin/notification-campaigns/{campaignId}`

**Path Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `campaignId` | UUID | Yes | Campaign ID |

**Request Body:**

```json
{
  "title": "Updated title",
  "body": "Updated body",
  "navigationPayload": { "screen": "home" },
  "audienceDefinition": { "daysSinceLastActive": 14 },
  "status": "SCHEDULED",
  "scheduledAt": "2024-02-01T10:00:00Z"
}
```

**Fields:**

| Field | Type | Required | Description |
|------|------|----------|-------------|
| `title` | string | No | Notification title |
| `body` | string | No | Notification body |
| `navigationPayload` | object | No | Navigation payload |
| `audienceDefinition` | object | No | Audience criteria |
| `status` | string | No | Campaign status |
| `scheduledAt` | string (ISO 8601) | No | When to start sending (required for SCHEDULED status) |

**Status Transitions (via PATCH):**

- `DRAFT` → `SCHEDULED` (requires `scheduledAt`)
- `SCHEDULED` → `DRAFT` (cancel schedule)

**Status Transitions (via dedicated endpoints):**

- `POST /start` → `SENDING` (from `DRAFT` or `SCHEDULED`)
- `POST /cancel` → `CANCELLED` (from `DRAFT`, `SCHEDULED`, or `SENDING`)
- `SENDING` → `COMPLETED` (set automatically when the campaign finishes)

**Content Immutability:** Once a campaign is `SENDING`, `COMPLETED`, or `CANCELLED`, the following fields cannot be changed: `title`, `body`, `navigationPayload`, `audienceDefinition`.

**Response:** `200 OK`

Returns the updated campaign object.

**Error Responses:**

- `400 Bad Request` - Invalid request or invalid status transition
- `401 Unauthorized` - Missing or invalid JWT
- `403 Forbidden` - User does not have admin role
- `404 Not Found` - Campaign not found
- `500 Internal Server Error` - Server error

---

### Start Campaign

Manually starts a campaign (transitions to `SENDING` status).

**Endpoint:** `POST /api/v1/admin/notification-campaigns/{campaignId}/start`

**Path Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `campaignId` | UUID | Yes | Campaign ID |

**Response:** `200 OK`

Returns the updated campaign object.

**Error Responses:**

- `400 Bad Request` - Campaign cannot be started (invalid current status)
- `401 Unauthorized` - Missing or invalid JWT
- `403 Forbidden` - User does not have admin role
- `404 Not Found` - Campaign not found
- `500 Internal Server Error` - Server error

---

### Cancel Campaign

Cancels a campaign (transitions to `CANCELLED` status).

**Endpoint:** `POST /api/v1/admin/notification-campaigns/{campaignId}/cancel`

**Path Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `campaignId` | UUID | Yes | Campaign ID |

**Response:** `200 OK`

Returns the updated campaign object.

**Error Responses:**

- `400 Bad Request` - Campaign cannot be cancelled (already finalised)
- `401 Unauthorized` - Missing or invalid JWT
- `403 Forbidden` - User does not have admin role
- `404 Not Found` - Campaign not found
- `500 Internal Server Error` - Server error

---

## Notification Types

The backend automatically sends notifications for the following events when the user has appropriate preferences enabled and a registered device:

| Type | Trigger | Preference Required |
|------|---------|---------------------|
| `CHAT_MESSAGE` | New chat message received | `messageNotificationsEnabled` |
| `MATCH_CREATED` | New match created | `matchNotificationsEnabled` |
| `LIKE_RECEIVED` | Like received from another user | `likeNotificationsEnabled` |
| `ACCOUNT_ALERT` | System account alerts | Always sent (no preference) |
| `MARKETING` | Marketing campaigns | `marketingNotificationsEnabled` |

## Notification Payload Format

Push notifications sent to devices follow the Expo push message format. The `data` object keys use snake_case.

```json
{
  "to": "ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]",
  "title": "Qaliye",
  "body": "You have a new message",
  "data": {
    "notification_type": "CHAT_MESSAGE",
    "match_id": "550e8400-e29b-41d4-a716-446655440000",
    "message_id": "550e8400-e29b-41d4-a716-446655440001"
  },
  "collapseId": "collapse",
  "tag": "collapse",
  "ttl": 900,
  "priority": "high",
  "channelId": "chat"
}
```

**Common fields:**

| Field | Type | Description |
|------|------|-------------|
| `to` | string | Expo push token |
| `title` | string | Notification title |
| `body` | string | Notification body |
| `data` | object | Notification metadata |
| `collapseId` | string | Groups replaceable notifications (chat only) |
| `tag` | string | Android grouping tag |
| `ttl` | integer | Time-to-live in seconds |
| `priority` | string | `high` or `normal` |
| `channelId` | string | Notification channel/category |

**Data fields by type:**

| Notification Type | `notification_type` | Additional `data` fields |
|-------------------|---------------------|--------------------------|
| Chat message | `CHAT_MESSAGE` | `match_id`, `message_id` |
| New match | `MATCH_CREATED` | `match_id` |
| Like received | `LIKE_RECEIVED` | `discovery_action_id` |
| Account alert | `ACCOUNT_ALERT` | none |
| Marketing | `MARKETING` | `campaign_id` |

**Default titles and bodies by type:**

| Notification Type | Title | Body |
|-------------------|-------|------|
| `CHAT_MESSAGE` | `Qaliye` | `You have a new message` (or actual message text if `messagePreviewEnabled` is true) |
| `MATCH_CREATED` | `Qaliye` | `It's a Match! 🎉` |
| `LIKE_RECEIVED` | `Qaliye` | `Someone liked your profile!` |
| `ACCOUNT_ALERT` | `Qaliye` | `Important account update` |
| `MARKETING` | Campaign title or `Qaliye` | Campaign body or `Check out what's new` |

## Rate Limiting

Device registration is rate-limited per installation to prevent abuse. Excessive registration attempts may result in throttling.

## Environment Support

The backend supports multiple app environments via the `app_environment` field on devices:

- `DEVELOPMENT` - Development builds
- `PREVIEW` - TestFlight / beta builds
- `PRODUCTION` - App Store / Play Store builds

Notifications are only sent to devices matching the current backend environment configuration.

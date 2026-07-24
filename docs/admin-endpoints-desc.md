# Admin API Endpoints — Qaliye Backend

> All endpoints are prefixed with `/api/v1/admin`.  
> All endpoints require authentication via JWT Bearer token.  
> Most endpoints require the caller to have `ADMIN` role; some accept `MODERATOR` or `ADMIN`.  
> The caller's identity is extracted from the JWT `sub` claim (UUID).  
> All timestamps are ISO-8601 (e.g. `2025-07-22T20:30:00Z`).  
> Monetary amounts are in **minor units** (e.g. cents, birr-centimes).  

---

## Table of Contents

1. [User Management](#1-user-management)
2. [Account Deletion](#2-account-deletion)
3. [Analytics Dashboard](#3-analytics-dashboard)
4. [Audit Log](#4-audit-log)
5. [Photo Moderation Queue](#5-photo-moderation-queue)
6. [Push Notifications](#6-push-notifications)
7. [Notification Campaigns](#7-notification-campaigns)
8. [Billing — Payment Orders](#8-billing--payment-orders)
9. [Billing — Refunds](#9-billing--refunds)
10. [Billing — Promotional Campaigns](#10-billing--promotional-campaigns)
11. [Transactions — Manual Review](#11-transactions--manual-review)
12. [Catalog — Languages & Ethnicities](#12-catalog--languages--ethnicities)

---

## 1. User Management

Base path: `/api/v1/admin/users`

### 1.1 List Users

Retrieves a paginated, filterable list of all users.

**Request**

```
GET /api/v1/admin/users
```

**Query Parameters**

| Parameter  | Type   | Required | Default | Description                                      |
|------------|--------|----------|---------|--------------------------------------------------|
| `status`   | string | No       | —       | Filter by user status: `ACTIVE`, `SUSPENDED`, `DEACTIVATED`, `BANNED`, `DELETED` |
| `role`     | string | No       | —       | Filter by role: `USER`, `MODERATOR`, `ADMIN`    |
| `search`   | string | No       | —       | Partial match on `display_name` (case-insensitive) |
| `page`     | int    | No       | `1`     | Page number (1-based)                            |
| `pageSize` | int    | No       | `20`    | Items per page                                   |

**Response — `200 OK`**

```json
{
  "users": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "displayName": "Selam",
      "status": "ACTIVE",
      "role": "USER",
      "preferredLanguage": "en",
      "isOnboarded": true,
      "isVerified": false,
      "profileCompletionScore": 75,
      "lastActiveAt": "2025-07-22T18:00:00Z",
      "createdAt": "2025-06-01T10:00:00Z"
    }
  ],
  "total": 1543,
  "page": 1,
  "pageSize": 20
}
```

**Response Fields (each user in `users` array)**

| Field                   | Type    | Description                              |
|-------------------------|---------|------------------------------------------|
| `id`                    | UUID    | User ID                                  |
| `displayName`           | string  | Profile display name (may be `null`)     |
| `status`                | string  | Current account status                   |
| `role`                  | string  | User role                                |
| `preferredLanguage`     | string  | Preferred language code                  |
| `isOnboarded`           | boolean | Whether onboarding is complete           |
| `isVerified`            | boolean | Whether profile is verified              |
| `profileCompletionScore`| int     | 0–100 profile completion score           |
| `lastActiveAt`          | string  | Last active timestamp (nullable)         |
| `createdAt`             | string  | Account creation timestamp               |

---

### 1.2 Get User Detail

Retrieves detailed information about a specific user, including photo moderation stats, report stats, verification status, and active match count.

**Request**

```
GET /api/v1/admin/users/{userId}
```

**Path Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `userId`  | UUID | Yes      | The user ID to retrieve |

**Response — `200 OK`**

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "displayName": "Selam",
  "status": "ACTIVE",
  "role": "USER",
  "preferredLanguage": "en",
  "gender": "FEMALE",
  "residencyType": "CITIZEN",
  "relationshipIntention": "SERIOUS_RELATIONSHIP",
  "isOnboarded": true,
  "isVerified": false,
  "isVisible": true,
  "profileCompletionScore": 75,
  "photoCount": 4,
  "pendingPhotoCount": 1,
  "approvedPhotoCount": 2,
  "rejectedPhotoCount": 0,
  "manualReviewPhotoCount": 1,
  "reportCount": 0,
  "pendingReportCount": 0,
  "verificationStatus": null,
  "activeMatchCount": 12,
  "lastActiveAt": "2025-07-22T18:00:00Z",
  "deletedAt": null,
  "createdAt": "2025-06-01T10:00:00Z",
  "updatedAt": "2025-07-22T18:00:00Z"
}
```

**Response Fields**

| Field                    | Type    | Description                                          |
|--------------------------|---------|------------------------------------------------------|
| `id`                     | UUID    | User ID                                              |
| `displayName`            | string  | Profile display name                                 |
| `status`                 | string  | Account status                                       |
| `role`                   | string  | User role                                            |
| `preferredLanguage`      | string  | Preferred language code                              |
| `gender`                 | string  | Gender (`MALE`, `FEMALE`) (nullable)                 |
| `residencyType`          | string  | Residency type (nullable)                            |
| `relationshipIntention`  | string  | Relationship intention (nullable)                    |
| `isOnboarded`            | boolean | Onboarding completed                                 |
| `isVerified`             | boolean | Profile verified                                     |
| `isVisible`              | boolean | Profile visible in discovery                         |
| `profileCompletionScore` | int     | 0–100 score                                          |
| `photoCount`             | int     | Total non-deleted photos                             |
| `pendingPhotoCount`      | int     | Photos pending moderation                            |
| `approvedPhotoCount`     | int     | Photos approved                                      |
| `rejectedPhotoCount`     | int     | Photos rejected                                      |
| `manualReviewPhotoCount` | int     | Photos in manual review                              |
| `reportCount`            | int     | Total reports against this user                      |
| `pendingReportCount`     | int     | Reports still pending                                |
| `verificationStatus`     | string  | Latest verification status (nullable)                |
| `activeMatchCount`       | int     | Number of active matches                             |
| `lastActiveAt`           | string  | Last active timestamp (nullable)                     |
| `deletedAt`              | string  | Deletion timestamp (nullable)                        |
| `createdAt`              | string  | Account creation timestamp                           |
| `updatedAt`              | string  | Last update timestamp                                |

**Errors**

| Status | Error Code              | Description                  |
|--------|-------------------------|------------------------------|
| 404    | `user_not_found`        | User does not exist          |

---

### 1.3 Update User Status

Changes a user's account status. Admins cannot change their own status.

**Request**

```
PATCH /api/v1/admin/users/{userId}/status
```

**Path Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `userId`  | UUID | Yes      | Target user ID |

**Request Body**

```json
{
  "status": "SUSPENDED",
  "reason": "Violated community guidelines"
}
```

| Field    | Type   | Required | Description                                                        |
|----------|--------|----------|--------------------------------------------------------------------|
| `status` | string | Yes      | One of: `ACTIVE`, `SUSPENDED`, `DEACTIVATED`, `BANNED`            |
| `reason` | string | No       | Optional reason for the status change                              |

**Response — `204 No Content`**

No response body.

**Errors**

| Status | Error Code                       | Description                                  |
|--------|----------------------------------|----------------------------------------------|
| 400    | `cannot_change_own_status`       | Admin attempting to change own status        |
| 404    | `user_not_found_or_same_status`  | User not found or status is already the same |
| 403    | `admin_access_required`          | Caller is not an admin                       |

---

### 1.4 Update User Role

Changes a user's role. Admins cannot change their own role.

**Request**

```
PATCH /api/v1/admin/users/{userId}/role
```

**Path Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `userId`  | UUID | Yes      | Target user ID |

**Request Body**

```json
{
  "role": "MODERATOR"
}
```

| Field  | Type   | Required | Description                                        |
|--------|--------|----------|----------------------------------------------------|
| `role` | string | Yes      | One of: `USER`, `MODERATOR`, `ADMIN`              |

**Response — `204 No Content`**

No response body.

**Errors**

| Status | Error Code                    | Description                               |
|--------|-------------------------------|-------------------------------------------|
| 400    | `cannot_change_own_role`      | Admin attempting to change own role       |
| 404    | `user_not_found_or_same_role` | User not found or role is already the same|
| 403    | `admin_access_required`       | Caller is not an admin                    |

---

## 2. Account Deletion

Base path: `/api/v1/admin/users`

### 2.1 Delete User Account

Permanently deletes (soft-delete) a user account. This is idempotent — calling on an already-deleted user is safe. Admins cannot delete their own account.

**Request**

```
DELETE /api/v1/admin/users/{userId}
```

**Path Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `userId`  | UUID | Yes      | Target user ID |

**Request Body (optional)**

```json
{
  "reason": "Spam account creation"
}
```

| Field    | Type   | Required | Description                         |
|----------|--------|----------|-------------------------------------|
| `reason` | string | No       | Reason for deletion (audit logged)  |

**Response — `204 No Content`**

No response body.

**Errors**

| Status | Error Code                | Description                              |
|--------|---------------------------|------------------------------------------|
| 400    | `cannot_delete_own_account` | Admin attempting to delete own account |
| 404    | `user_not_found`          | Target user does not exist               |
| 403    | `admin_access_required`   | Caller is not an admin                   |

---

## 3. Analytics Dashboard

Base path: `/api/v1/admin/analytics`

### 3.1 Get Dashboard

Retrieves platform-wide aggregate statistics. Available to `MODERATOR` or `ADMIN` roles.

**Request**

```
GET /api/v1/admin/analytics/dashboard
```

No query parameters.

**Response — `200 OK`**

```json
{
  "users": {
    "total_users": 15430,
    "active_users": 12000,
    "suspended_users": 230,
    "deactivated_users": 1800,
    "banned_users": 45,
    "deleted_users": 1355,
    "admins": 5,
    "moderators": 3,
    "new_users_24h": 120,
    "new_users_7d": 850,
    "dau": 3200,
    "wau": 8100,
    "mau": 12000
  },
  "profiles": {
    "total_profiles": 14000,
    "onboarded_profiles": 11500,
    "verified_profiles": 3000,
    "visible_profiles": 11000,
    "avg_completion_score": 72
  },
  "matches": {
    "total_matches": 45000,
    "active_matches": 28000,
    "new_matches_24h": 500,
    "new_matches_7d": 3500
  },
  "moderation": {
    "photos_pending": 150,
    "photos_manual_review": 30,
    "photos_approved": 12000,
    "photos_rejected": 800
  },
  "reports": {
    "reports_pending": 12,
    "reports_under_review": 5,
    "reports_resolved_no_action": 200,
    "reports_resolved_banned": 40,
    "new_reports_24h": 8
  },
  "revenue": {
    "verified_orders": 5000,
    "pending_orders": 200,
    "review_orders": 50,
    "cancelled_orders": 100,
    "rejected_orders": 30,
    "revenue_24h": 150000,
    "revenue_7d": 950000,
    "revenue_30d": 3800000
  },
  "subscriptions": {
    "active_subscriptions": 3000,
    "cancelled_subscriptions": 500,
    "pending_subscriptions": 100
  },
  "notifications": {
    "notifications_pending": 50,
    "notifications_fanout_complete": 48000,
    "notifications_processing": 10,
    "notifications_failed": 5,
    "notifications_skipped": 200
  }
}
```

**Response Sections**

| Section          | Description                                                    |
|------------------|----------------------------------------------------------------|
| `users`          | User counts by status/role, new user growth, DAU/WAU/MAU      |
| `profiles`       | Profile counts, onboarding/verification/visibility, avg score |
| `matches`        | Total/active matches, new match counts                        |
| `moderation`     | Photo moderation queue counts by status                       |
| `reports`        | User report counts by status                                   |
| `revenue`        | Order counts by status, revenue in minor units (24h/7d/30d)   |
| `subscriptions`  | Subscription counts by status                                  |
| `notifications`  | Notification outbox event counts by status                     |

**Errors**

| Status | Error Code      | Description                              |
|--------|-----------------|------------------------------------------|
| 403    | `access_denied` | Caller is not MODERATOR or ADMIN         |

---

## 4. Audit Log

Base path: `/api/v1/admin/audit-log`

### 4.1 List Audit Log Entries

Retrieves a paginated, filterable list of audit log entries. Admin-only.

**Request**

```
GET /api/v1/admin/audit-log
```

**Query Parameters**

| Parameter     | Type   | Required | Default | Description                                      |
|---------------|--------|----------|---------|--------------------------------------------------|
| `action`      | string | No       | —       | Filter by action type (e.g. `USER_STATUS_CHANGED`) |
| `targetTable` | string | No       | —       | Filter by target table name                      |
| `actorId`     | UUID   | No       | —       | Filter by the admin who performed the action     |
| `targetId`    | UUID   | No       | —       | Filter by the target entity ID                   |
| `page`        | int    | No       | `1`     | Page number (1-based)                            |
| `pageSize`    | int    | No       | `50`    | Items per page                                   |

**Response — `200 OK`**

```json
{
  "entries": [
    {
      "id": "660e8400-e29b-41d4-a716-446655440001",
      "actorUserId": "550e8400-e29b-41d4-a716-446655440000",
      "actorDisplayName": "AdminUser",
      "action": "USER_STATUS_CHANGED",
      "targetTable": "app_users",
      "targetId": "770e8400-e29b-41d4-a716-446655440002",
      "requestId": null,
      "details": "{\"status\": \"SUSPENDED\", \"reason\": \"Policy violation\"}",
      "createdAt": "2025-07-22T15:30:00Z"
    }
  ],
  "total": 5230,
  "page": 1,
  "pageSize": 50
}
```

**Response Fields (each entry in `entries` array)**

| Field              | Type   | Description                                         |
|--------------------|--------|-----------------------------------------------------|
| `id`               | UUID   | Audit log entry ID                                  |
| `actorUserId`      | UUID   | The user who performed the action (nullable)        |
| `actorDisplayName` | string | Display name of the actor (nullable)                |
| `action`           | string | Action type (e.g. `USER_STATUS_CHANGED`, `APPROVE_ORDER`) |
| `targetTable`      | string | Database table affected (nullable)                  |
| `targetId`         | UUID   | ID of the affected entity (nullable)                |
| `requestId`        | UUID   | Request correlation ID (nullable)                   |
| `details`          | string | JSON string with action-specific details (nullable) |
| `createdAt`        | string | Timestamp of the action                             |

**Errors**

| Status | Error Code            | Description                  |
|--------|-----------------------|------------------------------|
| 403    | `admin_access_required` | Caller is not an admin     |

---

## 5. Photo Moderation Queue

Base path: `/api/v1/admin/moderation`

### 5.1 Get Manual Review Queue

Retrieves photos flagged for manual review (status `MANUAL_REVIEW`). Returns up to 100 items, ordered by creation date (oldest first). Available to `MODERATOR` or `ADMIN`.

**Request**

```
GET /api/v1/admin/moderation/photos/manual-review
```

No query parameters.

**Response — `200 OK`**

```json
{
  "items": [
    {
      "id": "880e8400-e29b-41d4-a716-446655440003",
      "userId": "550e8400-e29b-41d4-a716-446655440000",
      "imageUrl": "https://storage.example.com/signed-url...",
      "moderationStatus": "MANUAL_REVIEW",
      "createdAt": "2025-07-22T12:00:00Z",
      "displayName": "Selam"
    }
  ]
}
```

**Response Fields (each item in `items` array)**

| Field              | Type   | Description                                         |
|--------------------|--------|-----------------------------------------------------|
| `id`               | UUID   | Photo ID                                            |
| `userId`           | UUID   | Owner user ID                                       |
| `imageUrl`         | string | Signed URL to view the photo (1-hour TTL)           |
| `moderationStatus` | string | Moderation status (always `MANUAL_REVIEW` here)     |
| `createdAt`        | string | Photo upload timestamp                              |
| `displayName`      | string | Display name of the photo owner                     |

---

### 5.2 Get Review Queue

Retrieves all photos needing review — both `MANUAL_REVIEW` and `PENDING` statuses. `MANUAL_REVIEW` items are prioritized. Returns up to 100 items. Available to `MODERATOR` or `ADMIN`.

**Request**

```
GET /api/v1/admin/moderation/photos/review-queue
```

No query parameters.

**Response — `200 OK`**

Same shape as [5.1 Get Manual Review Queue](#51-get-manual-review-queue), but `moderationStatus` may be `MANUAL_REVIEW` or `PENDING`.

---

### 5.3 Get Queue Counts

Retrieves counts of photos in each moderation status. Available to `MODERATOR` or `ADMIN`.

**Request**

```
GET /api/v1/admin/moderation/photos/counts
```

No query parameters.

**Response — `200 OK`**

```json
{
  "pending": 150,
  "manual_review": 30,
  "approved": 12000,
  "rejected": 800
}
```

**Response Fields**

| Field           | Type | Description                              |
|-----------------|------|------------------------------------------|
| `pending`       | long | Photos awaiting automated moderation     |
| `manual_review` | long | Photos flagged for manual review         |
| `approved`      | long | Photos approved                          |
| `rejected`      | long | Photos rejected                          |

---

### 5.4 Approve Photo

Approves a photo that is currently in `PENDING` or `MANUAL_REVIEW` status. Sets `moderation_status` to `APPROVED`, records the reviewer and timestamp, and clears any previous `rejection_reason`. If the photo is a primary photo and the user is onboarded, the V14 database trigger automatically sets `is_visible = TRUE` on the profile. The action is audit-logged. Available to `MODERATOR` or `ADMIN`.

**Request**

```
PATCH /api/v1/admin/moderation/photos/{photoId}/approve
```

**Path Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `photoId` | UUID | Yes      | Photo ID to approve |

No request body.

**Response — `200 OK`**

```json
{
  "photoId": "880e8400-e29b-41d4-a716-446655440003",
  "moderationStatus": "APPROVED"
}
```

**Response Fields**

| Field              | Type   | Description                         |
|--------------------|--------|-------------------------------------|
| `photoId`          | UUID   | Photo ID                            |
| `moderationStatus` | string | Always `APPROVED`                   |

**Errors**

| Status | Error Code                  | Description                                    |
|--------|-----------------------------|------------------------------------------------|
| 403    | `access_denied`             | Caller is not MODERATOR or ADMIN               |
| 404    | `photo_not_found`           | Photo does not exist or is soft-deleted        |
| 409    | `photo_not_in_reviewable_state` | Photo is not in `PENDING` or `MANUAL_REVIEW` status |

**Audit Log**

| Action           | Target Table      | Details                                      |
|------------------|-------------------|----------------------------------------------|
| `PHOTO_APPROVED` | `profile_photos`  | `{"moderation_status": "APPROVED"}`          |

---

### 5.5 Reject Photo

Rejects a photo that is currently in `PENDING` or `MANUAL_REVIEW` status. Sets `moderation_status` to `REJECTED`, records the reviewer, timestamp, and optional rejection reason. The action is audit-logged. Available to `MODERATOR` or `ADMIN`.

**Request**

```
PATCH /api/v1/admin/moderation/photos/{photoId}/reject
```

**Path Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `photoId` | UUID | Yes      | Photo ID to reject |

**Request Body (optional)**

```json
{
  "reason": "Inappropriate content"
}
```

| Field     | Type   | Required | Description                         |
|-----------|--------|----------|-------------------------------------|
| `reason`  | string | No       | Reason for rejection (audit logged) |

**Response — `200 OK`**

```json
{
  "photoId": "880e8400-e29b-41d4-a716-446655440003",
  "moderationStatus": "REJECTED"
}
```

**Response Fields**

| Field              | Type   | Description                         |
|--------------------|--------|-------------------------------------|
| `photoId`          | UUID   | Photo ID                            |
| `moderationStatus` | string | Always `REJECTED`                   |

**Errors**

| Status | Error Code                  | Description                                    |
|--------|-----------------------------|------------------------------------------------|
| 403    | `access_denied`             | Caller is not MODERATOR or ADMIN               |
| 404    | `photo_not_found`           | Photo does not exist or is soft-deleted        |
| 409    | `photo_not_in_reviewable_state` | Photo is not in `PENDING` or `MANUAL_REVIEW` status |

**Audit Log**

| Action           | Target Table      | Details                                                        |
|------------------|-------------------|----------------------------------------------------------------|
| `PHOTO_REJECTED` | `profile_photos`  | `{"moderation_status": "REJECTED", "reason": "..."}`           |

---

## 6. Push Notifications

Base path: `/api/v1/admin/notifications`

### 6.1 Send Push Notification to User

Sends a push notification to a specific user. The notification is queued via the outbox system and delivered asynchronously. Admin-only.

**Request**

```
POST /api/v1/admin/notifications/users/{userId}/push
```

**Path Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `userId`  | UUID | Yes      | Target user ID |

**Request Body**

```json
{
  "title": "Account Update",
  "body": "Your subscription has been activated."
}
```

| Field   | Type   | Required | Description                |
|---------|--------|----------|----------------------------|
| `title` | string | Yes      | Notification title         |
| `body`  | string | Yes      | Notification body text     |

**Response — `200 OK`**

```json
{
  "eventId": "990e8400-e29b-41d4-a716-446655440004",
  "recipientUserId": "550e8400-e29b-41d4-a716-446655440000",
  "notificationType": "ACCOUNT_ALERT",
  "status": "QUEUED"
}
```

**Response Fields**

| Field               | Type   | Description                                    |
|---------------------|--------|------------------------------------------------|
| `eventId`           | UUID   | Notification outbox event ID                   |
| `recipientUserId`   | UUID   | Target user ID                                 |
| `notificationType`  | string | Always `ACCOUNT_ALERT`                         |
| `status`            | string | Always `QUEUED` (delivered asynchronously)     |

**Errors**

| Status | Error Code                    | Description                                  |
|--------|-------------------------------|----------------------------------------------|
| 404    | `user_not_found_or_deleted`   | Target user not found or is deleted/banned   |
| 403    | `admin_access_required`       | Caller is not an admin                       |

---

## 7. Notification Campaigns

Base path: `/api/v1/admin/notification-campaigns`

### 7.1 List Campaigns

Retrieves a paginated list of notification campaigns. Admin-only.

**Request**

```
GET /api/v1/admin/notification-campaigns
```

**Query Parameters**

| Parameter | Type   | Required | Default | Description                              |
|-----------|--------|----------|---------|------------------------------------------|
| `status`  | string | No       | —       | Filter by status: `DRAFT`, `RUNNING`, `COMPLETED`, `CANCELLED` |
| `page`    | int    | No       | `0`     | Page number (0-based)                    |
| `size`    | int    | No       | `20`    | Items per page (max 100)                 |

**Response — `200 OK`**

Returns a Spring `Page<NotificationCampaign>` object:

```json
{
  "content": [
    {
      "id": "aa0e8400-e29b-41d4-a716-446655440005",
      "campaignKey": "summer_promo_2025",
      "title": "Summer Special!",
      "body": "Get 50% off premium this summer.",
      "navigationPayload": { "screen": "premium_offers" },
      "audienceDefinition": { "country": "ET", "gender": "FEMALE" },
      "status": "DRAFT",
      "scheduledAt": null,
      "startedAt": null,
      "completedAt": null,
      "cancelledAt": null,
      "createdByUserId": "550e8400-e29b-41d4-a716-446655440000",
      "createdAt": "2025-07-22T10:00:00Z",
      "updatedAt": "2025-07-22T10:00:00Z"
    }
  ],
  "pageable": { "pageNumber": 0, "pageSize": 20 },
  "totalElements": 5,
  "totalPages": 1,
  "number": 0,
  "size": 20
}
```

**Campaign Fields**

| Field                | Type   | Description                                              |
|----------------------|--------|----------------------------------------------------------|
| `id`                 | UUID   | Campaign ID                                              |
| `campaignKey`        | string | Unique campaign identifier                               |
| `title`              | string | Notification title                                       |
| `body`               | string | Notification body                                        |
| `navigationPayload`  | object | JSON object for client-side navigation                   |
| `audienceDefinition` | object | JSON object defining the target audience                 |
| `status`             | string | `DRAFT`, `RUNNING`, `COMPLETED`, `CANCELLED`            |
| `scheduledAt`        | string | Scheduled start time (nullable)                          |
| `startedAt`          | string | Actual start time (nullable)                             |
| `completedAt`        | string | Completion time (nullable)                               |
| `cancelledAt`        | string | Cancellation time (nullable)                             |
| `createdByUserId`    | UUID   | Admin who created the campaign                           |
| `createdAt`          | string | Creation timestamp                                       |
| `updatedAt`          | string | Last update timestamp                                    |

---

### 7.2 Get Campaign

Retrieves a single notification campaign by ID. Admin-only.

**Request**

```
GET /api/v1/admin/notification-campaigns/{campaignId}
```

**Path Parameters**

| Parameter    | Type | Required | Description  |
|--------------|------|----------|--------------|
| `campaignId` | UUID | Yes      | Campaign ID  |

**Response — `200 OK`**

Returns a single `NotificationCampaign` object (same shape as items in [7.1](#71-list-campaigns)).

---

### 7.3 Create Campaign

Creates a new notification campaign in `DRAFT` status. Admin-only.

**Request**

```
POST /api/v1/admin/notification-campaigns
```

**Request Body**

```json
{
  "campaignKey": "summer_promo_2025",
  "title": "Summer Special!",
  "body": "Get 50% off premium this summer.",
  "navigationPayload": { "screen": "premium_offers" },
  "audienceDefinition": { "country": "ET", "gender": "FEMALE" }
}
```

| Field                | Type   | Required | Description                              |
|----------------------|--------|----------|------------------------------------------|
| `campaignKey`        | string | Yes      | Unique campaign key (non-blank)          |
| `title`              | string | Yes      | Notification title (non-blank)           |
| `body`               | string | Yes      | Notification body (non-blank)            |
| `navigationPayload`  | object | No       | JSON object for client navigation        |
| `audienceDefinition` | object | No       | JSON object defining target audience     |

**Response — `201 Created`**

Returns the created `NotificationCampaign` object.

---

### 7.4 Update Campaign

Updates an existing notification campaign. Admin-only.

**Request**

```
PATCH /api/v1/admin/notification-campaigns/{campaignId}
```

**Path Parameters**

| Parameter    | Type | Required | Description  |
|--------------|------|----------|--------------|
| `campaignId` | UUID | Yes      | Campaign ID  |

**Request Body**

```json
{
  "title": "Updated Title",
  "body": "Updated body text",
  "navigationPayload": { "screen": "new_screen" },
  "audienceDefinition": { "country": "ET" },
  "status": "DRAFT",
  "scheduledAt": "2025-08-01T10:00:00Z"
}
```

| Field                | Type   | Required | Description                                        |
|----------------------|--------|----------|----------------------------------------------------|
| `title`              | string | No       | Updated notification title                         |
| `body`               | string | No       | Updated notification body                          |
| `navigationPayload`  | object | No       | Updated navigation payload                         |
| `audienceDefinition` | object | No       | Updated audience definition                        |
| `status`             | string | No       | Updated status                                     |
| `scheduledAt`        | string | No       | Scheduled start time (ISO-8601)                    |

**Response — `200 OK`**

Returns the updated `NotificationCampaign` object.

---

### 7.5 Start Campaign

Starts a campaign (transitions status to `RUNNING`). Admin-only.

**Request**

```
POST /api/v1/admin/notification-campaigns/{campaignId}/start
```

**Path Parameters**

| Parameter    | Type | Required | Description  |
|--------------|------|----------|--------------|
| `campaignId` | UUID | Yes      | Campaign ID  |

**Response — `200 OK`**

Returns the updated `NotificationCampaign` object with `status: "RUNNING"`.

---

### 7.6 Cancel Campaign

Cancels a campaign (transitions status to `CANCELLED`). Admin-only.

**Request**

```
POST /api/v1/admin/notification-campaigns/{campaignId}/cancel
```

**Path Parameters**

| Parameter    | Type | Required | Description  |
|--------------|------|----------|--------------|
| `campaignId` | UUID | Yes      | Campaign ID  |

**Response — `200 OK`**

Returns the updated `NotificationCampaign` object with `status: "CANCELLED"`.

---

## 8. Billing — Payment Orders

Base path: `/api/v1/admin/billing`

### 8.1 List Payment Orders

Retrieves a paginated list of payment orders needing admin review. Admin-only.

**Request**

```
GET /api/v1/admin/billing/orders
```

**Query Parameters**

| Parameter    | Type   | Required | Default                          | Description                                      |
|--------------|--------|----------|----------------------------------|--------------------------------------------------|
| `status`     | string | No       | `MANUAL_REVIEW,RECEIPT_SUBMITTED`| Comma-separated order statuses to filter by      |
| `methodCode` | string | No       | —                                | Filter by payment method code                    |
| `countryCode`| string | No       | —                                | Filter by user's country code (ISO 3166-1 alpha-2)|
| `page`       | int    | No       | `1`                              | Page number (1-based)                            |
| `pageSize`   | int    | No       | `20`                             | Items per page                                   |

**Order Statuses**

| Status                 | Description                                           |
|------------------------|-------------------------------------------------------|
| `PENDING`              | Order created, awaiting payment                       |
| `VERIFICATION_PENDING` | Manual transfer verification submitted                |
| `MANUAL_REVIEW`        | Flagged for admin review (bank/amount mismatch, etc.) |
| `RECEIPT_SUBMITTED`    | Receipt uploaded, awaiting admin review               |
| `VERIFIED`             | Admin approved the order                              |
| `REJECTED`             | Admin rejected the order                              |
| `CANCELLED`            | Order cancelled (e.g. after refund)                   |
| `EXPIRED`              | Order expired before payment                          |

**Response — `200 OK`**

```json
{
  "orders": [
    {
      "id": "bb0e8400-e29b-41d4-a716-446655440006",
      "user_id": "550e8400-e29b-41d4-a716-446655440000",
      "order_reference": "QAL-a1b2c3d4",
      "status": "MANUAL_REVIEW",
      "expected_amount_minor_units": 5000,
      "expected_currency": "ETB",
      "payment_method_id": "cc0e8400-e29b-41d4-a716-446655440007",
      "method_code": "BANK_TRANSFER_CBE",
      "method_display_name": "CBE Bank Transfer",
      "user_display_name": "Selam",
      "created_at": "2025-07-22T09:00:00Z"
    }
  ],
  "total": 50,
  "page": 1,
  "pageSize": 20
}
```

> **Note:** The `orders` array contains raw database rows from `payment_orders` joined with `payment_methods` and `profiles`. The exact fields depend on the `payment_orders` table schema plus `method_code`, `method_display_name`, and `user_display_name`.

---

### 8.2 Get Order Details

Retrieves detailed information about a specific payment order, including a signed URL for the receipt if one was uploaded.

**Request**

```
GET /api/v1/admin/billing/orders/{orderId}
```

**Path Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `orderId` | UUID | Yes      | Payment order ID |

**Response — `200 OK`**

```json
{
  "id": "bb0e8400-e29b-41d4-a716-446655440006",
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "orderReference": "QAL-a1b2c3d4",
  "status": "MANUAL_REVIEW",
  "expectedAmountMinorUnits": 5000,
  "expectedCurrency": "ETB",
  "paymentMethodId": "cc0e8400-e29b-41d4-a716-446655440007",
  "paymentChannel": "BANK_TRANSFER",
  "paymentMethod": "CBE Bank Transfer",
  "methodCode": "BANK_TRANSFER_CBE",
  "paymentMethodDisplayName": "CBE Bank Transfer",
  "createdAt": "2025-07-22T09:00:00Z",
  "receiptUrl": "https://storage.example.com/signed-receipt-url..."
}
```

**Response Fields**

| Field                      | Type   | Description                                          |
|----------------------------|--------|------------------------------------------------------|
| `id`                       | UUID   | Order ID                                             |
| `userId`                   | UUID   | User who placed the order                            |
| `orderReference`           | string | Human-readable order reference (`QAL-XXXXXXXX`)      |
| `status`                   | string | Current order status                                 |
| `expectedAmountMinorUnits` | int    | Expected payment amount in minor units               |
| `expectedCurrency`         | string | Currency code                                        |
| `paymentMethodId`          | UUID   | Payment method ID                                    |
| `paymentChannel`           | string | Channel type (e.g. `BANK_TRANSFER`, `ONLINE_PAYMENT`)|
| `paymentMethod`            | string | Payment method name                                  |
| `methodCode`               | string | Method code                                          |
| `paymentMethodDisplayName` | string | Display name of the payment method                   |
| `createdAt`                | string | Order creation timestamp                             |
| `receiptUrl`               | string | Signed URL to receipt image (only if receipt exists) |

**Errors**

| Status | Error Code       | Description              |
|--------|------------------|--------------------------|
| 404    | `order_not_found` | Order does not exist     |

---

### 8.3 Approve Order

Approves a payment order that is in `MANUAL_REVIEW`, `RECEIPT_SUBMITTED`, or `VERIFICATION_PENDING` status. Upon approval, the order is fulfilled (subscription activated, credits granted, etc.). Admin-only.

**Request**

```
POST /api/v1/admin/billing/orders/{orderId}/approve
```

**Path Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `orderId` | UUID | Yes      | Payment order ID |

**Request Body (optional)**

```json
{
  "decisionNote": "Receipt verified, amount matches."
}
```

| Field          | Type   | Required | Description                         |
|----------------|--------|----------|-------------------------------------|
| `decisionNote` | string | No       | Optional note for audit log         |

**Response — `200 OK`**

```json
{
  "status": "VERIFIED",
  "orderId": "bb0e8400-e29b-41d4-a716-446655440006"
}
```

**Errors**

| Status | Error Code            | Description                                        |
|--------|-----------------------|----------------------------------------------------|
| 404    | `order_not_found`     | Order does not exist                               |
| 400    | `order_not_reviewable`| Order is not in a reviewable status                |
| 403    | `admin_required`      | Caller is not an admin                             |

---

### 8.4 Decline Order

Declines a payment order. Requires a mandatory `decisionNote`. Admin-only.

> **Note:** This endpoint is functionally identical to [8.5 Reject Order](#85-reject-order) but requires a non-blank reason.

**Request**

```
POST /api/v1/admin/billing/orders/{orderId}/decline
```

**Path Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `orderId` | UUID | Yes      | Payment order ID |

**Request Body**

```json
{
  "decisionNote": "Receipt does not match expected amount."
}
```

| Field          | Type   | Required | Description                         |
|----------------|--------|----------|-------------------------------------|
| `decisionNote` | string | Yes      | Mandatory reason (non-blank)        |

**Response — `200 OK`**

```json
{
  "status": "REJECTED",
  "orderId": "bb0e8400-e29b-41d4-a716-446655440006"
}
```

**Errors**

| Status | Error Code            | Description                                        |
|--------|-----------------------|----------------------------------------------------|
| 404    | `order_not_found`     | Order does not exist                               |
| 400    | `order_not_reviewable`| Order is not in a reviewable status                |
| 403    | `admin_required`      | Caller is not an admin                             |

---

### 8.5 Reject Order

Rejects a payment order. The `decisionNote` is optional. Admin-only.

**Request**

```
POST /api/v1/admin/billing/orders/{orderId}/reject
```

**Path Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `orderId` | UUID | Yes      | Payment order ID |

**Request Body (optional)**

```json
{
  "decisionNote": "Invalid receipt."
}
```

| Field          | Type   | Required | Description                         |
|----------------|--------|----------|-------------------------------------|
| `decisionNote` | string | No       | Optional reason for rejection       |

**Response — `200 OK`**

```json
{
  "status": "REJECTED",
  "orderId": "bb0e8400-e29b-41d4-a716-446655440006"
}
```

**Errors**

| Status | Error Code            | Description                                        |
|--------|-----------------------|----------------------------------------------------|
| 404    | `order_not_found`     | Order does not exist                               |
| 400    | `order_not_reviewable`| Order is not in a reviewable status                |
| 403    | `admin_required`      | Caller is not an admin                             |

---

## 9. Billing — Refunds

Base path: `/api/v1/admin/billing`

### 9.1 Refund Order

Refunds a verified payment order. This performs the following:
1. Marks the original transaction as `REFUNDED`
2. Creates a refund transaction record
3. Cancels any associated subscription
4. Expires credit lots and cancels boosts from the original transaction
5. Marks the order as `CANCELLED`
6. Evicts subscription cache

Admin-only. Only orders with status `VERIFIED` can be refunded.

**Request**

```
POST /api/v1/admin/billing/orders/{orderId}/refund
```

**Path Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `orderId` | UUID | Yes      | Payment order ID |

**Request Body (optional)**

```json
{
  "reason": "User requested cancellation within 24h"
}
```

| Field    | Type   | Required | Description                         |
|----------|--------|----------|-------------------------------------|
| `reason` | string | No       | Reason for the refund (audit logged)|

**Response — `200 OK`**

```json
{
  "orderId": "bb0e8400-e29b-41d4-a716-446655440006",
  "status": "CANCELLED",
  "transactionId": "dd0e8400-e29b-41d4-a716-446655440008",
  "refundAmount": 5000,
  "currency": "ETB"
}
```

**Response Fields**

| Field           | Type   | Description                                    |
|-----------------|--------|------------------------------------------------|
| `orderId`       | UUID   | The refunded order ID                          |
| `status`        | string | Always `CANCELLED`                             |
| `transactionId` | UUID   | The original completed transaction ID          |
| `refundAmount`  | int    | Refund amount in minor units                   |
| `currency`      | string | Currency code                                  |

**Errors**

| Status | Error Code                       | Description                                  |
|--------|----------------------------------|----------------------------------------------|
| 404    | `order_not_found`                | Order does not exist                         |
| 400    | `only_verified_orders_can_be_refunded` | Order is not in `VERIFIED` status      |
| 404    | `no_completed_transaction_for_order` | No completed transaction found for order |
| 403    | `admin_required`                 | Caller is not an admin                       |

---

## 10. Billing — Promotional Campaigns

Base path: `/api/v1/admin/billing/campaigns`

### 10.1 Create Campaign

Creates a new promotional/billing campaign in `DRAFT` status. Admin-only.

**Request**

```
POST /api/v1/admin/billing/campaigns
```

**Request Body**

```json
{
  "campaignKey": "summer_discount_2025",
  "name": "Summer Discount 2025",
  "description": "20% off premium for new users",
  "triggerType": "PURCHASE",
  "eligibilityType": "NEW_USER",
  "benefitType": "DISCOUNT",
  "discountType": "PERCENTAGE",
  "discountValue": 20,
  "discountCurrency": "ETB",
  "subscriptionProductId": "ee0e8400-e29b-41d4-a716-446655440009",
  "countryCode": "ET",
  "durationDays": null,
  "newUserWindowDays": 30,
  "maxRedemptions": 1000,
  "maxRedemptionsPerUser": 1,
  "priority": 10,
  "startsAt": "2025-08-01T00:00:00Z",
  "endsAt": "2025-08-31T23:59:59Z",
  "targetGender": "FEMALE"
}
```

**Request Fields**

| Field                  | Type    | Required | Description                                                              |
|------------------------|---------|----------|--------------------------------------------------------------------------|
| `campaignKey`          | string  | Yes      | Unique campaign key (non-blank)                                          |
| `name`                 | string  | Yes      | Campaign name                                                            |
| `description`          | string  | No       | Campaign description                                                     |
| `triggerType`          | string  | Yes      | `PURCHASE` or `AUTO_ON_SIGNUP`                                           |
| `eligibilityType`      | string  | Yes      | `NEW_USER` or `ALL_USERS`                                                |
| `benefitType`          | string  | Yes      | `FREE_PREMIUM` or `DISCOUNT`                                             |
| `discountType`         | string  | Conditional | Required if `benefitType` is `DISCOUNT`: `PERCENTAGE` or `FIXED`     |
| `discountValue`        | long    | Conditional | Required if `benefitType` is `DISCOUNT`                              |
| `discountCurrency`     | string  | No       | Currency code for fixed discounts                                        |
| `subscriptionProductId`| UUID    | Yes      | Subscription product ID                                                  |
| `countryCode`          | string  | Yes      | ISO 3166-1 alpha-2 country code                                          |
| `durationDays`         | int     | Conditional | Required if `benefitType` is `FREE_PREMIUM` (must be > 0)            |
| `newUserWindowDays`    | int     | Conditional | Required if `eligibilityType` is `NEW_USER` (must be > 0)            |
| `maxRedemptions`       | int     | No       | Maximum total redemptions (nullable = unlimited)                         |
| `maxRedemptionsPerUser`| int     | No       | Max redemptions per user (default: 1)                                    |
| `priority`             | int     | No       | Display/priority order (default: 0)                                      |
| `startsAt`             | string  | Yes      | Campaign start time (ISO-8601)                                           |
| `endsAt`               | string  | No       | Campaign end time (must be after `startsAt`)                             |
| `targetGender`         | string  | No       | `MALE`, `FEMALE`, or null (all genders)                                  |

**Response — `201 Created`**

Returns a `CampaignDto` object:

```json
{
  "id": "ff0e8400-e29b-41d4-a716-446655440010",
  "campaignKey": "summer_discount_2025",
  "name": "Summer Discount 2025",
  "description": "20% off premium for new users",
  "triggerType": "PURCHASE",
  "eligibilityType": "NEW_USER",
  "benefitType": "DISCOUNT",
  "discountType": "PERCENTAGE",
  "discountValue": 20,
  "discountCurrency": "ETB",
  "subscriptionProductId": "ee0e8400-e29b-41d4-a716-446655440009",
  "countryCode": "ET",
  "durationDays": null,
  "newUserWindowDays": 30,
  "maxRedemptions": 1000,
  "maxRedemptionsPerUser": 1,
  "reservedCount": 0,
  "fulfilledCount": 0,
  "priority": 10,
  "startsAt": "2025-08-01T00:00:00Z",
  "endsAt": "2025-08-31T23:59:59Z",
  "status": "DRAFT",
  "targetGender": "FEMALE",
  "createdAt": "2025-07-22T10:00:00Z",
  "updatedAt": "2025-07-22T10:00:00Z"
}
```

**CampaignDto Response Fields**

| Field                  | Type    | Description                                              |
|------------------------|---------|----------------------------------------------------------|
| `id`                   | UUID    | Campaign ID                                              |
| `campaignKey`          | string  | Unique campaign key                                      |
| `name`                 | string  | Campaign name                                            |
| `description`          | string  | Campaign description (nullable)                          |
| `triggerType`          | string  | Trigger type                                             |
| `eligibilityType`      | string  | Eligibility type                                         |
| `benefitType`          | string  | Benefit type                                             |
| `discountType`         | string  | Discount type (nullable)                                 |
| `discountValue`        | long    | Discount value (nullable)                                |
| `discountCurrency`     | string  | Discount currency (nullable)                             |
| `subscriptionProductId`| UUID    | Subscription product ID                                  |
| `countryCode`          | string  | Country code                                             |
| `durationDays`         | int     | Duration in days (nullable, for FREE_PREMIUM)            |
| `newUserWindowDays`    | int     | New user window in days (nullable)                       |
| `maxRedemptions`       | int     | Max total redemptions (nullable = unlimited)             |
| `maxRedemptionsPerUser`| int     | Max redemptions per user                                 |
| `reservedCount`        | int     | Number of reserved (in-progress) redemptions             |
| `fulfilledCount`       | int     | Number of fulfilled redemptions                          |
| `priority`             | int     | Priority order                                           |
| `startsAt`             | string  | Start timestamp                                          |
| `endsAt`               | string  | End timestamp (nullable)                                 |
| `status`               | string  | `DRAFT`, `ACTIVE`, `PAUSED`, `EXPIRED`                  |
| `targetGender`         | string  | Target gender (nullable = all)                           |
| `createdAt`            | string  | Creation timestamp                                       |
| `updatedAt`            | string  | Last update timestamp                                    |

**Errors**

| Status | Error Code                     | Description                                          |
|--------|--------------------------------|------------------------------------------------------|
| 400    | `campaign_key_required`        | Missing campaign key                                 |
| 400    | `missing_required_fields`      | Missing required fields (trigger, eligibility, etc.) |
| 400    | `invalid_benefit_type`         | Invalid benefit type for the given trigger type      |
| 400    | `duration_days_required`       | `FREE_PREMIUM` requires `durationDays > 0`           |
| 400    | `discount_fields_required`     | `DISCOUNT` requires `discountType` and `discountValue`|
| 400    | `new_user_window_days_required`| `NEW_USER` eligibility requires `newUserWindowDays > 0`|
| 400    | `ends_at_must_be_after_starts_at` | End time is not after start time                |
| 400    | `invalid_target_gender`        | Gender must be `MALE` or `FEMALE` (if provided)      |

---

### 10.2 List Campaigns

Retrieves a paginated list of promotional campaigns.

**Request**

```
GET /api/v1/admin/billing/campaigns
```

**Query Parameters**

| Parameter  | Type   | Required | Default | Description                              |
|------------|--------|----------|---------|------------------------------------------|
| `status`   | string | No       | —       | Filter by status: `DRAFT`, `ACTIVE`, `PAUSED`, `EXPIRED` |
| `page`     | int    | No       | `1`     | Page number (1-based)                    |
| `pageSize` | int    | No       | `20`    | Items per page (max 100)                 |

**Response — `200 OK`**

```json
{
  "campaigns": [ /* CampaignDto objects */ ],
  "page": 1,
  "pageSize": 20,
  "total": 15,
  "totalPages": 1
}
```

---

### 10.3 Get Campaign

Retrieves a single promotional campaign by ID.

**Request**

```
GET /api/v1/admin/billing/campaigns/{id}
```

**Path Parameters**

| Parameter | Type | Required | Description  |
|-----------|------|----------|--------------|
| `id`      | UUID | Yes      | Campaign ID  |

**Response — `200 OK`**

Returns a `CampaignDto` object.

**Errors**

| Status | Error Code          | Description              |
|--------|---------------------|--------------------------|
| 404    | `campaign_not_found` | Campaign does not exist |

---

### 10.4 Update Campaign

Updates an existing promotional campaign. Only the following fields can be updated: `name`, `description`, `maxRedemptions`, `maxRedemptionsPerUser`, `priority`, `endsAt`, `targetGender`.

**Request**

```
PUT /api/v1/admin/billing/campaigns/{id}
```

**Path Parameters**

| Parameter | Type | Required | Description  |
|-----------|------|----------|--------------|
| `id`      | UUID | Yes      | Campaign ID  |

**Request Body**

```json
{
  "name": "Updated Campaign Name",
  "description": "Updated description",
  "maxRedemptions": 2000,
  "maxRedemptionsPerUser": 2,
  "priority": 5,
  "endsAt": "2025-09-30T23:59:59Z",
  "targetGender": null
}
```

| Field                  | Type    | Required | Description                              |
|------------------------|---------|----------|------------------------------------------|
| `name`                 | string  | No       | Updated campaign name                    |
| `description`          | string  | No       | Updated description                      |
| `maxRedemptions`       | int     | No       | Updated max total redemptions            |
| `maxRedemptionsPerUser`| int     | No       | Updated max per user                     |
| `priority`             | int     | No       | Updated priority                         |
| `endsAt`               | string  | No       | Updated end time (ISO-8601)              |
| `targetGender`         | string  | No       | Updated target gender (`MALE`, `FEMALE`, or null) |

**Response — `200 OK`**

Returns the updated `CampaignDto` object.

**Errors**

| Status | Error Code          | Description              |
|--------|---------------------|--------------------------|
| 404    | `campaign_not_found` | Campaign does not exist |

---

### 10.5 Activate Campaign

Activates a campaign (transitions from `DRAFT` or `PAUSED` to `ACTIVE`).

**Request**

```
POST /api/v1/admin/billing/campaigns/{id}/activate
```

**Path Parameters**

| Parameter | Type | Required | Description  |
|-----------|------|----------|--------------|
| `id`      | UUID | Yes      | Campaign ID  |

**Response — `200 OK`**

Returns the updated `CampaignDto` with `status: "ACTIVE"`.

**Errors**

| Status | Error Code                     | Description                              |
|--------|--------------------------------|------------------------------------------|
| 404    | `campaign_not_found`           | Campaign does not exist                  |
| 400    | `invalid_status_transition`    | Campaign is not in `DRAFT` or `PAUSED`   |

---

### 10.6 Pause Campaign

Pauses an active campaign (transitions from `ACTIVE` to `PAUSED`).

**Request**

```
POST /api/v1/admin/billing/campaigns/{id}/pause
```

**Path Parameters**

| Parameter | Type | Required | Description  |
|-----------|------|----------|--------------|
| `id`      | UUID | Yes      | Campaign ID  |

**Response — `200 OK`**

Returns the updated `CampaignDto` with `status: "PAUSED"`.

**Errors**

| Status | Error Code                     | Description                        |
|--------|--------------------------------|------------------------------------|
| 404    | `campaign_not_found`           | Campaign does not exist            |
| 400    | `invalid_status_transition`    | Campaign is not `ACTIVE`           |

---

### 10.7 Expire Campaign

Expires a campaign (transitions from `ACTIVE`, `PAUSED`, or `DRAFT` to `EXPIRED`).

**Request**

```
POST /api/v1/admin/billing/campaigns/{id}/expire
```

**Path Parameters**

| Parameter | Type | Required | Description  |
|-----------|------|----------|--------------|
| `id`      | UUID | Yes      | Campaign ID  |

**Response — `200 OK`**

Returns the updated `CampaignDto` with `status: "EXPIRED"`.

**Errors**

| Status | Error Code                     | Description                                        |
|--------|--------------------------------|----------------------------------------------------|
| 404    | `campaign_not_found`           | Campaign does not exist                            |
| 400    | `invalid_status_transition`    | Campaign is not in `ACTIVE`, `PAUSED`, or `DRAFT`  |

---

### 10.8 List Campaign Redemptions

Retrieves a paginated list of redemptions for a specific campaign.

**Request**

```
GET /api/v1/admin/billing/campaigns/{id}/redemptions
```

**Path Parameters**

| Parameter | Type | Required | Description  |
|-----------|------|----------|--------------|
| `id`      | UUID | Yes      | Campaign ID  |

**Query Parameters**

| Parameter  | Type | Required | Default | Description              |
|------------|------|----------|---------|--------------------------|
| `page`     | int  | No       | `1`     | Page number (1-based)    |
| `pageSize` | int  | No       | `20`    | Items per page (max 100) |

**Response — `200 OK`**

Returns an array of `RedemptionDto` objects:

```json
[
  {
    "id": "11e8400-e29b-41d4-a716-446655440011",
    "campaignId": "ff0e8400-e29b-41d4-a716-446655440010",
    "campaignKey": null,
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "subscriptionId": "22e8400-e29b-41d4-a716-446655440012",
    "paymentOrderId": "bb0e8400-e29b-41d4-a716-446655440006",
    "status": "FULFILLED",
    "eligibilityCountry": "ET",
    "eligibilityGender": "FEMALE",
    "originalAmountMinor": 5000,
    "discountAmountMinor": 1000,
    "finalAmountMinor": 4000,
    "currency": "ETB",
    "reservedAt": "2025-08-05T10:00:00Z",
    "fulfilledAt": "2025-08-05T10:05:00Z",
    "cancelledAt": null,
    "expiredAt": null,
    "failureCode": null
  }
]
```

**RedemptionDto Fields**

| Field                  | Type   | Description                                            |
|------------------------|--------|--------------------------------------------------------|
| `id`                   | UUID   | Redemption ID                                          |
| `campaignId`           | UUID   | Campaign ID                                            |
| `campaignKey`          | string | Campaign key (always null in this response)            |
| `userId`               | UUID   | User who redeemed                                      |
| `subscriptionId`       | UUID   | Subscription created (nullable)                        |
| `paymentOrderId`       | UUID   | Payment order ID (nullable)                            |
| `status`               | string | `RESERVED`, `FULFILLED`, `CANCELLED`, `EXPIRED`       |
| `eligibilityCountry`   | string | Country used for eligibility check (nullable)          |
| `eligibilityGender`    | string | Gender used for eligibility check (nullable)           |
| `originalAmountMinor`  | long   | Original price in minor units (nullable)               |
| `discountAmountMinor`  | long   | Discount amount in minor units (nullable)              |
| `finalAmountMinor`     | long   | Final amount in minor units (nullable)                 |
| `currency`             | string | Currency code (nullable)                               |
| `reservedAt`           | string | When the redemption was reserved (nullable)            |
| `fulfilledAt`          | string | When the redemption was fulfilled (nullable)           |
| `cancelledAt`          | string | When the redemption was cancelled (nullable)           |
| `expiredAt`            | string | When the redemption expired (nullable)                 |
| `failureCode`          | string | Failure code if redemption failed (nullable)           |

---

## 11. Transactions — Manual Review

Base path: `/api/v1/admin/transactions`

### 11.1 List Transactions

Retrieves a paginated list of transactions for manual review. Admin-only.

**Request**

```
GET /api/v1/admin/transactions
```

**Query Parameters**

| Parameter  | Type   | Required | Default                              | Description                              |
|------------|--------|----------|--------------------------------------|------------------------------------------|
| `status`   | string | No       | `MANUAL_REVIEW`                      | Transaction status to filter by          |
| `provider` | string | No       | `CHAPA,TELEBIRR,CBE_BIRR,BANK_TRANSFER` | Comma-separated provider filter       |
| `page`     | int    | No       | `1`                                  | Page number (1-based)                    |
| `pageSize` | int    | No       | `20`                                 | Items per page                           |

**Response — `200 OK`**

```json
{
  "items": [
    {
      "transactionId": "dd0e8400-e29b-41d4-a716-446655440008",
      "userId": "550e8400-e29b-41d4-a716-446655440000",
      "provider": "CHAPA",
      "amountCents": 5000,
      "currency": "ETB",
      "paymentPurpose": "SUBSCRIPTION",
      "planCode": null,
      "receiptImageUrl": "receipts/user123/tx456.jpg",
      "status": "MANUAL_REVIEW",
      "createdAt": "2025-07-22T09:00:00Z",
      "userDisplayName": "Selam"
    }
  ],
  "total": 50,
  "page": 1,
  "pageSize": 20
}
```

**TransactionItemDto Fields**

| Field              | Type   | Description                                         |
|--------------------|--------|-----------------------------------------------------|
| `transactionId`    | UUID   | Transaction ID                                      |
| `userId`           | UUID   | User ID                                             |
| `provider`         | string | Payment provider (`CHAPA`, `TELEBIRR`, `CBE_BIRR`, `BANK_TRANSFER`, `STRIPE`, etc.) |
| `amountCents`      | int    | Amount in minor units                               |
| `currency`         | string | Currency code                                       |
| `paymentPurpose`   | string | `SUBSCRIPTION`, `PROFILE_BOOST`, etc.               |
| `planCode`         | string | Subscription plan code (nullable, always null in list) |
| `receiptImageUrl`  | string | Receipt storage path (nullable)                     |
| `status`           | string | Transaction status                                  |
| `createdAt`        | string | Transaction creation timestamp                      |
| `userDisplayName`  | string | Display name of the user (nullable)                 |

---

### 11.2 Review Transaction

Reviews a manual transaction (approves or rejects it). When approving (`COMPLETED`), the system activates the subscription or creates a boost depending on the `paymentPurpose`. Admin-only.

**Request**

```
PATCH /api/v1/admin/transactions/{transactionId}
```

**Path Parameters**

| Parameter       | Type | Required | Description      |
|-----------------|------|----------|------------------|
| `transactionId` | UUID | Yes      | Transaction ID   |

**Request Body**

```json
{
  "status": "COMPLETED",
  "adminNotes": "Receipt verified. Amount matches."
}
```

| Field        | Type   | Required | Description                                        |
|--------------|--------|----------|----------------------------------------------------|
| `status`     | string | Yes      | `COMPLETED` or `FAILED`                            |
| `adminNotes` | string | No       | Optional notes for audit log                       |

**Response — `200 OK`**

```json
{
  "transaction_id": "dd0e8400-e29b-41d4-a716-446655440008",
  "status": "COMPLETED"
}
```

**Response Fields**

| Field              | Type   | Description                       |
|--------------------|--------|-----------------------------------|
| `transaction_id`   | UUID   | Transaction ID                    |
| `status`           | string | Updated status (`COMPLETED` or `FAILED`) |

**Errors**

| Status | Error Code                  | Description                                  |
|--------|-----------------------------|----------------------------------------------|
| 404    | `transaction_not_found`     | Transaction does not exist                   |
| 400    | `transaction_already_reviewed` | Transaction is not in `MANUAL_REVIEW` status |
| 403    | `access_denied`             | Caller is not an admin                       |

---

## 12. Catalog — Languages & Ethnicities

Base path: `/api/v1/admin/catalog`

### 12.1 List Languages

Retrieves a list of all languages in the catalog. Admin-only.

**Request**

```
GET /api/v1/admin/catalog/languages
```

**Query Parameters**

| Parameter     | Type   | Required | Default | Description                              |
|---------------|--------|----------|---------|------------------------------------------|
| `countryCode` | string | No       | —       | Filter by country code (ISO 3166-1 alpha-2) |
| `limit`       | int    | No       | `100`   | Maximum number of results                |
| `offset`      | int    | No       | `0`     | Number of results to skip                |

**Response — `200 OK`**

```json
[
  {
    "id": "33e8400-e29b-41d4-a716-446655440013",
    "code": "am",
    "countryCode": "ET",
    "name": "Amharic",
    "nativeName": "አማርኛ"
  }
]
```

**LanguageOption Fields**

| Field         | Type   | Description                |
|---------------|--------|----------------------------|
| `id`          | UUID   | Language ID                |
| `code`        | string | Language code (lowercase)  |
| `countryCode` | string | Country code               |
| `name`        | string | Language name (English)    |
| `nativeName`  | string | Native name (nullable)     |

---

### 12.2 Create Language

Creates a new language in the catalog. Admin-only.

**Request**

```
POST /api/v1/admin/catalog/languages
```

**Request Body**

```json
{
  "code": "or",
  "countryCode": "ET",
  "name": "Oromo",
  "nativeName": "Afaan Oromoo",
  "sortOrder": 5
}
```

| Field         | Type   | Required | Description                                              |
|---------------|--------|----------|----------------------------------------------------------|
| `code`        | string | Yes      | Language code (lowercase, pattern: `[a-z][a-z0-9_-]*`)   |
| `countryCode` | string | Yes      | ISO 3166-1 alpha-2 country code (exactly 2 uppercase letters) |
| `name`        | string | Yes      | Language name (max 100 chars)                            |
| `nativeName`  | string | No       | Native name (max 100 chars)                              |
| `sortOrder`   | int    | Yes      | Sort order                                               |

**Response — `201 Created`**

Returns the created `LanguageOption` object.

---

### 12.3 Update Language

Updates an existing language. Admin-only.

**Request**

```
PATCH /api/v1/admin/catalog/languages/{id}
```

**Path Parameters**

| Parameter | Type | Required | Description  |
|-----------|------|----------|--------------|
| `id`      | UUID | Yes      | Language ID  |

**Request Body**

```json
{
  "name": "Updated Name",
  "nativeName": "Updated Native Name",
  "isActive": true,
  "sortOrder": 10
}
```

| Field         | Type    | Required | Description                          |
|---------------|---------|----------|--------------------------------------|
| `name`        | string  | No       | Updated name (max 100 chars)         |
| `nativeName`  | string  | No       | Updated native name (max 100 chars)  |
| `isActive`    | boolean | No       | Whether the language is active       |
| `sortOrder`   | int     | No       | Updated sort order                   |

**Response — `200 OK`**

Returns the updated `LanguageOption` object.

---

### 12.4 Delete Language

Soft-deletes a language from the catalog. Admin-only.

**Request**

```
DELETE /api/v1/admin/catalog/languages/{id}
```

**Path Parameters**

| Parameter | Type | Required | Description  |
|-----------|------|----------|--------------|
| `id`      | UUID | Yes      | Language ID  |

**Response — `204 No Content`**

No response body.

---

### 12.5 List Ethnicities

Retrieves a list of all ethnicities in the catalog. Admin-only.

**Request**

```
GET /api/v1/admin/catalog/ethnicities
```

**Query Parameters**

| Parameter     | Type   | Required | Default | Description                              |
|---------------|--------|----------|---------|------------------------------------------|
| `countryCode` | string | No       | —       | Filter by country code (ISO 3166-1 alpha-2) |
| `limit`       | int    | No       | `100`   | Maximum number of results                |
| `offset`      | int    | No       | `0`     | Number of results to skip                |

**Response — `200 OK`**

```json
[
  {
    "id": "44e8400-e29b-41d4-a716-446655440014",
    "code": "oromo",
    "countryCode": "ET",
    "name": "Oromo",
    "region": "Oromia"
  }
]
```

**EthnicityOption Fields**

| Field         | Type   | Description                |
|---------------|--------|----------------------------|
| `id`          | UUID   | Ethnicity ID               |
| `code`        | string | Ethnicity code (lowercase) |
| `countryCode` | string | Country code               |
| `name`        | string | Ethnicity name             |
| `region`      | string | Region (nullable)          |

---

### 12.6 Create Ethnicity

Creates a new ethnicity in the catalog. Admin-only.

**Request**

```
POST /api/v1/admin/catalog/ethnicities
```

**Request Body**

```json
{
  "code": "tigray",
  "countryCode": "ET",
  "name": "Tigray",
  "region": "Tigray",
  "sortOrder": 3
}
```

| Field         | Type   | Required | Description                                              |
|---------------|--------|----------|----------------------------------------------------------|
| `code`        | string | Yes      | Ethnicity code (lowercase, pattern: `[a-z][a-z0-9_-]*`)  |
| `countryCode` | string | Yes      | ISO 3166-1 alpha-2 country code (exactly 2 uppercase letters) |
| `name`        | string | Yes      | Ethnicity name (max 100 chars)                           |
| `region`      | string | No       | Region (max 100 chars)                                   |
| `sortOrder`   | int    | Yes      | Sort order                                               |

**Response — `201 Created`**

Returns the created `EthnicityOption` object.

---

### 12.7 Update Ethnicity

Updates an existing ethnicity. Admin-only.

**Request**

```
PATCH /api/v1/admin/catalog/ethnicities/{id}
```

**Path Parameters**

| Parameter | Type | Required | Description   |
|-----------|------|----------|---------------|
| `id`      | UUID | Yes      | Ethnicity ID  |

**Request Body**

```json
{
  "name": "Updated Name",
  "region": "Updated Region",
  "isActive": true,
  "sortOrder": 8
}
```

| Field        | Type    | Required | Description                          |
|--------------|---------|----------|--------------------------------------|
| `name`       | string  | No       | Updated name (max 100 chars)         |
| `region`     | string  | No       | Updated region (max 100 chars)       |
| `isActive`   | boolean | No       | Whether the ethnicity is active      |
| `sortOrder`  | int     | No       | Updated sort order                   |

**Response — `200 OK`**

Returns the updated `EthnicityOption` object.

---

### 12.8 Delete Ethnicity

Soft-deletes an ethnicity from the catalog. Admin-only.

**Request**

```
DELETE /api/v1/admin/catalog/ethnicities/{id}
```

**Path Parameters**

| Parameter | Type | Required | Description   |
|-----------|------|----------|---------------|
| `id`      | UUID | Yes      | Ethnicity ID  |

**Response — `204 No Content`**

No response body.

---

## Common Error Responses

All admin endpoints may return the following standard error responses:

| HTTP Status | Error Code              | Description                                      |
|-------------|-------------------------|--------------------------------------------------|
| `401`       | (no body)               | Missing or invalid JWT token                     |
| `403`       | `admin_access_required` | Caller does not have ADMIN role                  |
| `403`       | `access_denied`         | Caller does not have required role (MODERATOR/ADMIN) |
| `400`       | (varies)                | Validation error — see individual endpoint docs  |
| `404`       | (varies)                | Resource not found — see individual endpoint docs|
| `500`       | (no body)               | Internal server error                            |

Error responses use the Spring `ResponseStatusException` format:

```json
{
  "timestamp": "2025-07-22T20:30:00Z",
  "status": 403,
  "error": "Forbidden",
  "message": "admin_access_required",
  "path": "/api/v1/admin/users/550e8400-e29b-41d4-a716-446655440000/status"
}
```

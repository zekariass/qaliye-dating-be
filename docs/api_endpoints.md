# API Endpoints

This document lists all REST API endpoints implemented in the Qaliye backend.

---

## Actions

Base path: `/api/v1/actions`

- **`POST /api/v1/actions/swipe`**  
  Swipe on a profile. Returns whether a match occurred and the match ID if applicable.

- **`POST /api/v1/actions/rewind`**  
  Rewind the last swipe action. Returns the ID of the rewound user.

---

## Discovery

Base path: `/api/v1/discovery`

- **`GET /api/v1/discovery/cards`**  
  Get discovery cards for the authenticated user.  
  Query parameters:
  - `cursor` (optional) — pagination cursor
  - `limit` (default: 20) — number of cards to return

---

## Health & Routes

- **`GET /api/v1/health`**  
  Health check endpoint. Returns status of the database and Supabase storage.

- **`GET /api/v1/routes`**  
  List all registered routes (admin only).

---

## Messaging

Base path: `/api/v1`

- **`POST /api/v1/messages`**  
  Send a message to a match. Returns `201` for a new conversation or `200` for an existing one.

- **`PATCH /api/v1/matches/{matchId}/read`**  
  Mark messages in a match as read.

- **`DELETE /api/v1/matches/{matchId}`**  
  Unmatch a user. Returns `204` if unmatched, `200` if already unmatched.

---

## Moderation

- **`POST /api/v1/internal/moderation/photo`**  
  Internal webhook for photo moderation events. Requires `X-Webhook-Secret` header.

- **`GET /api/v1/admin/moderation/photos`**  
  Get the photo moderation queue (admin only).  
  Query parameters:
  - `status` (default: `PENDING`)

- **`PATCH /api/v1/admin/moderation/photos/{photoId}`**  
  Review a photo moderation item (admin only).

- **`GET /api/v1/admin/moderation/reports`**  
  Get the user report queue (admin only).  
  Query parameters:
  - `status` (default: `PENDING`)

- **`PATCH /api/v1/admin/moderation/reports/{reportId}`**  
  Resolve a user report (admin only).

---

## Onboarding

Base path: `/api/v1/onboarding`

- **`POST /api/v1/onboarding/complete`**  
  Mark onboarding as complete for the authenticated user. Returns profile completion score.

---

## Payments

Base path: `/api/v1/payments`

- **`POST /api/v1/payments/manual`**  
  Submit a manual payment request for review.

### Payment Webhooks

Base path: `/api/v1/payments/webhooks`

- **`POST /api/v1/payments/webhooks/{provider}`**  
  Handle webhooks from payment providers. Supported providers: `stripe`, `revenuecat`.

### Admin Payments

Base path: `/api/v1/admin/transactions`

- **`GET /api/v1/admin/transactions`**  
  List transactions pending manual review (admin only).  
  Query parameters:
  - `status` (default: `MANUAL_REVIEW`)
  - `provider` (default: `CHAPA,TELEBIRR,CBE_BIRR,BANK_TRANSFER`)
  - `page` (default: 1)
  - `pageSize` (default: 20)

- **`PATCH /api/v1/admin/transactions/{transactionId}`**  
  Review a manual transaction (admin only).

---

## Safety

Base path: `/api/v1/safety`

- **`POST /api/v1/safety/block`**  
  Block another user.

---

## Verification

Base path: `/api/v1`

- **`POST /api/v1/verification/submit`**  
  Submit identity verification (e.g. photo ID). Returns verification ID and `PENDING` status.

- **`GET /api/v1/admin/verification/queue`**  
  Get the verification review queue (admin only).  
  Query parameters:
  - `status` (default: `PENDING`)

- **`PATCH /api/v1/admin/verification/{verificationId}`**  
  Review a verification submission (admin only).

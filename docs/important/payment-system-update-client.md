# Payment System Update — Client Integration Guide

> **Last updated:** 2026-08-15  
> **Audience:** Frontend / mobile client developers  
> **Base URL:** `/api/v1`

---

## Table of Contents

1. [Breaking Changes](#1-breaking-changes)
2. [Platform Parameter Migration](#2-platform-parameter-migration)
3. [Updated Endpoints](#3-updated-endpoints)
4. [New Endpoints](#4-new-endpoints)
5. [Removed / Deprecated](#5-removed--deprecated)
6. [Credit System Changes](#6-credit-system-changes)
7. [Entitlements Response Changes](#7-entitlements-response-changes)
8. [Action Cost & Limit Handling](#8-action-cost--limit-handling)
9. [Recommended Client Flows](#9-recommended-client-flows)
10. [Error Codes Reference](#10-error-codes-reference)
11. [Chat Attachment Endpoints](#11-chat-attachment-endpoints)
12. [Discovery Counts & Likes Endpoints](#12-discovery-counts--likes-endpoints)

---

## 1. Breaking Changes

| Change | Impact | Migration |
|--------|--------|-----------|
| `platform` values `ANDROID`/`IOS` → `MOBILE` | All billing endpoints with `platform` param | Send `MOBILE` instead of `ANDROID`/`IOS`. Backend still accepts legacy values and normalizes them, but clients should update. |
| `GET /payment-channels` response shape changed | Response is now an object, not a flat list | Parse `PaymentChannelsResponse` object with `paymentChannels[]` array |
| `GET /payment-options` response shape changed | Response now includes `paymentChannel` and `activeOnlineMethodCode` | Parse new `PaymentOptionsResponse` fields |
| `POST /orders/verify` renamed | Old manual verify endpoint path changed | Use `POST /manual-transfer/verify` for verify.et submissions; `POST /orders/{orderId}/verify` is now Chapa-only re-verification |
| `EntitlementResponse.credits` fields changed | `boostsAvailable`, `superLikesAvailable`, `rewindsAvailable` are now always `0` | Use `credits.creditBalance` as the single source of truth for credit balance |
| `EntitlementResponse.limits.boosts.used` source changed | Boost usage now tracked via action tracker, not lot balance | No client change needed, but `used` values may differ from previous behavior |
| `OrderResponse` enriched with new fields | New fields added to order response | Parse new fields: `updatedAt`, `paymentOfferId`, `verifyEtRequestId`, `pollAfterMs`, `canRetryVerification`, `canUploadReceipt`, `canContactSupport`, `verificationCount` |
| New order statuses: `REVIEW_REQUIRED`, `RECEIPT_SUBMITTED` | Order status enum expanded | Handle these statuses in client UI (see [Order Status Flow](#92-order-status-flow)) |

---

## 2. Platform Parameter Migration

The database now uses `WEB`, `MOBILE`, and `ALL` instead of `ANDROID`, `IOS`, `WEB`.

### What clients should send

| Client | Send `platform=` |
|--------|-----------------|
| Android app | `MOBILE` |
| iOS app | `MOBILE` |
| Web app | `WEB` |
| Cross-platform / unknown | `MOBILE` (default) |

### Backward compatibility

The backend **still accepts** `ANDROID` and `IOS` and normalizes them to `MOBILE` internally. However, clients should migrate to `MOBILE` to avoid future deprecation.

### Affected endpoints

- `GET /api/v1/billing/offers?platform=MOBILE`
- `GET /api/v1/billing/payment-channels?platform=MOBILE`
- `GET /api/v1/billing/payment-options?platform=MOBILE`
- `POST /api/v1/billing/orders` (body field `platform`)
- `POST /api/v1/billing/manual-transfer/verify` (body field `platform`)
- `POST /api/v1/billing/manual-transfer/receipt` (body field `platform`)

---

## 3. Updated Endpoints

### 3.1 GET `/api/v1/billing/entitlements`

No route change. Response shape updated:

```json
{
  "plan": "PREMIUM",
  "subscription": {
    "status": "ACTIVE",
    "provider": "CHAPA",
    "billingIntervalCount": 1,
    "billingIntervalUnit": "MONTH",
    "expiresAt": "2026-09-15T12:00:00Z",
    "autoRenew": false
  },
  "limits": {
    "likes":         { "used": 12, "limit": 100, "remaining": 88, "resetsAt": "2026-08-16T00:00:00Z" },
    "superLikes":    { "used": 1,  "limit": 5,   "remaining": 4,  "resetsAt": "2026-08-16T00:00:00Z" },
    "rewinds":       { "used": 0,  "limit": 10,  "remaining": 10, "resetsAt": "2026-08-16T00:00:00Z" },
    "boosts":        { "used": 2,  "limit": 3,   "remaining": 1,  "resetsAt": null },
    "voiceChatMsgs": { "used": 0,  "limit": 50,  "remaining": 50, "resetsAt": "2026-08-16T00:00:00Z" },
    "imageChatMsgs": { "used": 3,  "limit": 20,  "remaining": 17, "resetsAt": "2026-08-16T00:00:00Z" }
  },
  "credits": {
    "creditBalance": 150,
    "boostsAvailable": 0,
    "superLikesAvailable": 0,
    "rewindsAvailable": 0
  },
  "activeBoost": {
    "startedAt": "2026-08-15T10:00:00Z",
    "expiresAt": "2026-08-15T10:30:00Z",
    "remainingSeconds": 1200
  },
  "features": {
    "unlimitedLikes": true,
    "seeWhoLikesYou": true,
    "advancedFilters": false
  },
  "planLimits": { "BOOST": 3, "SUPER_LIKE": 5, "REWIND": 10 },
  "boostDurationMinutes": 30,
  "countrySettings": {
    "countryCode": "ET",
    "subscriptionEnabled": true,
    "creditsEnabled": true,
    "identityVerificationRequired": false
  }
}
```

**Key changes:**
- `credits.creditBalance` is now the **single source of truth** for all credit balances
- `credits.boostsAvailable`, `credits.superLikesAvailable`, `credits.rewindsAvailable` are **always 0** (deprecated, kept for backward compat)
- `limits.boosts.used` now comes from the action usage tracker (not lot balances)
- `limits.boosts.resetsAt` is `null` (boosts don't reset on a daily cycle)

### 3.2 GET `/api/v1/billing/offers?platform=MOBILE`

No route change. `platform` default changed from `ANDROID` to `MOBILE`.

Response (`OfferDto[]`):
```json
[
  {
    "id": "uuid",
    "productCode": "PREMIUM_MONTHLY",
    "productType": "SUBSCRIPTION",
    "countryCode": "ET",
    "currency": "ETB",
    "priceMinorUnits": 150000,
    "displayPrice": "1,500 ETB",
    "effectivePriceMinorUnits": 150000,
    "effectiveDisplayPrice": "1,500 ETB",
    "billingIntervalCount": 1,
    "billingIntervalUnit": "MONTH",
    "autoRenew": false,
    "externalProductId": "premium_monthly_et",
    "revenuecatOfferingId": "premium",
    "revenuecatPackageId": "premium_monthly",
    "hasAvailablePaymentMethods": true,
    "availablePaymentMethodCount": 3,
    "promotion": null,
    "claimablePromotions": []
  }
]
```

### 3.3 GET `/api/v1/billing/payment-channels?platform=MOBILE`

**Response shape changed** from flat list to object:

```json
{
  "platform": "MOBILE",
  "billingCountryCode": "ET",
  "resolvedMarketCountryCode": "ET",
  "fallbackToGlobal": false,
  "paymentChannels": [
    {
      "code": "ONLINE_PAYMENT",
      "displayName": "Online Payment",
      "activeOnlineMethodCode": "CHAPA",
      "displayOrder": 1,
      "methodCount": 1
    },
    {
      "code": "MANUAL_TRANSFER",
      "displayName": "Bank Transfer",
      "activeOnlineMethodCode": null,
      "displayOrder": 2,
      "methodCount": 5
    }
  ]
}
```

### 3.4 GET `/api/v1/billing/payment-options?platform=MOBILE`

**Response shape changed** with new fields:

```json
{
  "paymentChannel": "MANUAL_TRANSFER",
  "activeOnlineMethodCode": null,
  "platform": "MOBILE",
  "billingCountryCode": "ET",
  "resolvedMarketCountryCode": "ET",
  "fallbackToGlobal": false,
  "paymentMethods": [
    {
      "id": "uuid",
      "methodCode": "CBE_MANUAL",
      "displayName": "Commercial Bank of Ethiopia",
      "paymentChannel": "MANUAL_TRANSFER",
      "paymentMethod": "BANK_TRANSFER",
      "paymentInstructionsHtml": "<div>Transfer to CBE Account...</div>",
      "displayOrder": 1,
      "verificationParams": [
        { "name": "senderName", "label": "Sender Name", "type": "text", "required": true },
        { "name": "transferReference", "label": "Transfer Reference", "type": "text", "required": true }
      ],
      "logoUrl": "https://cdn.qaliye.com/banks/cbe.png"
    }
  ]
}
```

**New:** Pass `?channel=ONLINE_PAYMENT` to filter by channel:
```
GET /api/v1/billing/payment-options?platform=MOBILE&channel=ONLINE_PAYMENT
```

### 3.5 GET `/api/v1/billing/payment-options?platform=MOBILE&channel=MANUAL_TRANSFER`

Filters payment methods by a specific channel code. Returns the same `PaymentOptionsResponse` shape but filtered.

### 3.6 POST `/api/v1/billing/orders`

Request unchanged:
```json
{
  "paymentOfferId": "uuid",
  "paymentMethodId": "uuid",
  "platform": "MOBILE",
  "idempotencyKey": "client-generated-uuid"
}
```

**Response (`OrderResponse`) enriched:**
```json
{
  "id": "uuid",
  "orderReference": "QAL-a1b2c3d4",
  "status": "CREATED",
  "statusReason": null,
  "paymentOfferId": "uuid",
  "expectedAmountMinorUnits": 150000,
  "expectedCurrency": "ETB",
  "paymentMethodId": "uuid",
  "paymentChannel": "ONLINE_PAYMENT",
  "paymentMethod": "ONLINE_GATEWAY",
  "methodCode": "CHAPA",
  "paymentMethodDisplayName": "Chapa",
  "providerCheckoutUrl": "https://checkout.chapa.com/...",
  "paymentInstructions": null,
  "expiresAt": "2026-08-15T14:00:00Z",
  "createdAt": "2026-08-15T12:00:00Z",
  "updatedAt": "2026-08-15T12:00:00Z",
  "verifyEtRequestId": null,
  "pollAfterMs": null,
  "canRetryVerification": false,
  "canUploadReceipt": false,
  "canContactSupport": false,
  "verificationCount": 0
}
```

### 3.7 GET `/api/v1/billing/orders/{orderId}`

Same enriched `OrderResponse` as above. Use this for polling order status.

### 3.8 GET `/api/v1/billing/orders`

List user orders with optional status filter:
```
GET /api/v1/billing/orders?statuses=CREATED,VERIFICATION_PENDING&page=1&pageSize=20
```

### 3.9 POST `/api/v1/billing/boosts/activate`

Request unchanged:
```json
{
  "idempotencyKey": "client-generated-uuid"
}
```

Response unchanged:
```json
{
  "boostId": "uuid",
  "startedAt": "2026-08-15T12:00:00Z",
  "expiresAt": "2026-08-15T12:30:00Z",
  "creditsRemaining": 148
}
```

**Note:** Boost now checks `ActionCostService` for allowance first. If the user's plan includes unlimited boosts (`limit_value IS NULL`), no credits are consumed. Otherwise, credits are deducted from the central balance.

### 3.10 GET `/api/v1/billing/country-settings`

No change:
```json
{
  "countryCode": "ET",
  "subscriptionEnabled": true,
  "creditsEnabled": true,
  "identityVerificationRequired": false
}
```

---

## 4. New Endpoints

### 4.1 POST `/api/v1/billing/manual-transfer/verify`

Submit a manual bank transfer verification request to verify.et.

**Request:**
```json
{
  "paymentOfferId": "uuid",
  "paymentMethodId": "uuid",
  "platform": "MOBILE",
  "verificationData": {
    "senderName": "John Doe",
    "transferReference": "TRX12345678",
    "transferAmount": "1500",
    "transferDate": "2026-08-15",
    "fromBank": "CBE"
  },
  "idempotencyKey": "client-generated-uuid"
}
```

**Response:** `OrderResponse` with status `VERIFICATION_PENDING` or `REVIEW_REQUIRED`.

Key fields:
- `verifyEtRequestId`: The verify.et request ID (for tracking)
- `pollAfterMs`: `5000` — poll order status after this delay
- `canRetryVerification`: `true` if verification failed and can be retried
- `canUploadReceipt`: `true` if receipt upload is available as alternative
- `canContactSupport`: `true` if order is in manual review

### 4.2 POST `/api/v1/billing/manual-transfer/receipt`

Upload a receipt for manual verification (alternative to verify.et).

**Request:**
```json
{
  "paymentOfferId": "uuid",
  "paymentMethodId": "uuid",
  "platform": "MOBILE",
  "receiptStorageBucket": "qaliye-receipts",
  "receiptStoragePath": "receipts/user-uuid/order-uuid/receipt.pdf",
  "additionalNotes": {
    "transferAmount": "1500",
    "transferDate": "2026-08-15"
  },
  "idempotencyKey": "client-generated-uuid"
}
```

**Response:** `OrderResponse` with status `RECEIPT_SUBMITTED`.

Key fields:
- `canContactSupport`: `true` — admin will manually review
- `pollAfterMs`: `null` — no polling, admin reviews manually

### 4.3 POST `/api/v1/billing/orders/{orderId}/verify` (Chapa re-verification)

Re-verifies a Chapa payment by polling Chapa's API server-side. Use this when the webhook hasn't arrived or the client wants to force a status check.

**Request:** No body required.

**Response:** `OrderResponse` with updated status (`VERIFIED`, `MANUAL_REVIEW`, or `REJECTED`).

> **Note:** This endpoint is for Chapa online payments only. For manual transfer verification, use `POST /manual-transfer/verify`.

### 4.4 POST `/api/v1/discovery/actions/{actionId}/reveal`

Reveals the profile of a user who liked/super-liked the current user. Uses `ActionCostService` with action code `SEE_WHO_LIKED_YOU`.

**Request:** No body required. `actionId` is the discovery action ID from the like/super-like.

**Response (`RevealResponse`):**
```json
{
  "actionId": "uuid",
  "actionType": "LIKE",
  "actorUserId": "uuid",
  "actorDisplayName": "Alice",
  "actorAge": 28,
  "actorPrimaryPhotoUrl": "https://...signed-url...",
  "idempotent": false,
  "creditBalance": 145
}
```

Key fields:
- `idempotent`: `true` if already revealed previously (no charge applied)
- `creditBalance`: updated central credit balance after potential credit deduction
- `actorPrimaryPhotoUrl`: signed URL (1-hour TTL) for the liker's primary photo

### 4.5 POST `/api/v1/profile/identity-verification`

Automated identity verification via selfie-to-profile-photo face comparison (AWS Rekognition).

**Request:** `multipart/form-data`
```
POST /api/v1/profile/identity-verification
Content-Type: multipart/form-data

selfie: <image file, max 5MB>
```

**Response:**
```json
{
  "verification_status": "VERIFIED",
  "message": "Identity verified successfully (similarity: 95%)."
}
```

Possible `verification_status` values:
- `VERIFIED` — Face matched, profile gets verified badge
- `FAILED` — No match or error, includes reason in `message`

**Error responses:**
- `409 verification_in_progress` — Already pending
- `400 no_approved_photo` — No approved primary profile photo
- `400 selfie_required` — No selfie file provided

### 4.6 POST `/api/v1/verification/submit`

Manual verification submission for admin review (alternative to automated selfie match).

**Request:**
```json
{
  "storagePath": "verification-selfies/user-uuid/selfie.jpg"
}
```

**Response:**
```json
{
  "verification_id": "uuid",
  "status": "PENDING"
}
```

**Error responses:**
- `400 no_approved_photo` — Must have at least one approved profile photo
- `409 verification_pending` — A verification request is already under review

> **Note:** No polling — admin reviews manually and dispatches a notification on approval/rejection.

### 4.7 GET `/api/v1/billing/promotions`

Returns promotions the current user is eligible for. Used to show promotional offers in the UI.

**Response (`EligiblePromotionDto[]`):**
```json
[
  {
    "campaignId": "uuid",
    "campaignKey": "WELCOME_FREE_PREMIUM",
    "name": "Welcome Gift",
    "description": "Get 7 days of Premium for free",
    "triggerType": "USER_CLAIM",
    "benefitType": "FREE_PREMIUM",
    "discountType": null,
    "discountValue": null,
    "discountCurrency": null,
    "subscriptionProductId": "uuid",
    "durationDays": 7,
    "maxRedemptions": 1000,
    "reservedCount": 120,
    "fulfilledCount": 98,
    "endsAt": "2026-12-31T23:59:59Z",
    "targetGender": null,
    "canRedeem": true
  }
]
```

Key fields:
- `canRedeem`: `true` if `triggerType=USER_CLAIM` and `benefitType=FREE_PREMIUM` — show a claim button
- `reservedCount` / `fulfilledCount`: can be used to show "X claimed" counters

### 4.8 GET `/api/v1/billing/promotions/{campaignKey}`

Fetch a specific promotion campaign. Returns 403 `promotion_not_eligible` if the user does not qualify.

**Response:** Same `EligiblePromotionDto` shape as above.

### 4.9 POST `/api/v1/billing/promotions/{campaignKey}/redeem`

Claim a free promotion. Only valid for `triggerType=USER_CLAIM` campaigns.

**Request:** No body.

**Response (`RedeemPromotionResponse`):**
```json
{
  "redemptionId": "uuid",
  "subscriptionId": "uuid",
  "campaignKey": "WELCOME_FREE_PREMIUM",
  "planCode": "FREE_PREMIUM",
  "durationDays": 7,
  "periodEnd": "2026-08-22T12:00:00Z",
  "message": "Promotion redeemed successfully."
}
```

After success, refresh entitlements: `GET /api/v1/billing/entitlements` — `plan` will change to `FREE_PREMIUM`.

**Error responses:**
- `403 promotion_not_eligible` — User does not qualify
- `404 promotion_not_found` — Invalid campaign key
- `409` — Already redeemed or max redemptions reached

### 4.10 GET `/api/v1/billing/promotions/redemptions`

List the current user's past and active promotion redemptions.

```
GET /api/v1/billing/promotions/redemptions?page=1&pageSize=20
```

**Response (`UserRedemptionDto[]`):**
```json
[
  {
    "id": "uuid",
    "campaignId": "uuid",
    "campaignKey": "WELCOME_FREE_PREMIUM",
    "campaignName": "Welcome Gift",
    "benefitType": "FREE_PREMIUM",
    "durationDays": 7,
    "subscriptionId": "uuid",
    "paymentOrderId": null,
    "status": "FULFILLED",
    "originalAmountMinor": null,
    "discountAmountMinor": null,
    "finalAmountMinor": null,
    "currency": null,
    "reservedAt": "2026-08-15T12:00:00Z",
    "fulfilledAt": "2026-08-15T12:00:01Z",
    "cancelledAt": null,
    "expiredAt": null,
    "failureCode": null,
    "subscriptionStatus": "ACTIVE",
    "subscriptionPeriodEnd": "2026-08-22T12:00:00Z"
  }
]
```

---

## 5. Removed / Deprecated

| Item | Status | Replacement |
|------|--------|-------------|
| `POST /orders/verify` (manual transfer) | Renamed | `POST /manual-transfer/verify` |
| `credits.boostsAvailable` | Deprecated (always 0) | `credits.creditBalance` |
| `credits.superLikesAvailable` | Deprecated (always 0) | `credits.creditBalance` |
| `credits.rewindsAvailable` | Deprecated (always 0) | `credits.creditBalance` |
| `platform=ANDROID` / `platform=IOS` | Deprecated (still accepted) | `platform=MOBILE` |
| Legacy per-action credit lots | Removed server-side | Central credit balance via `CreditService` |

---

## 6. Credit System Changes

### Before (Legacy)

Credits were stored in per-type lots:
- `BOOST_CREDIT` lots for boosts
- `SUPERLIKE_CREDIT` lots for super likes
- `REWIND_CREDIT` lots for rewinds

Each action checked its specific lot type. Subscription grants created boost lots automatically.

### After (New)

All credits are stored in a **single central balance** (`user_credit_balances` table). The `CreditService` handles:
- `consumeCredits(userId, amount, actionCode, idempotencyKey)` — deduct credits
- `grantPurchasedCredits(userId, amount, transactionId, idempotencyKey)` — add purchased credits
- `grantSubscriptionAllowance(userId, amount, subId, idempotencyKey, expiresAt)` — add subscription-included credits
- `getBalance(userId)` — read current balance

### What this means for the client

1. **`credits.creditBalance`** is the single source of truth — ignore `boostsAvailable`, `superLikesAvailable`, `rewindsAvailable`
2. **Subscription no longer pre-grants boost lots** — boost allowance is tracked via `subscription_plan_limit_and_cost` limits. When a user activates a boost, the system checks if they have remaining boost allowance (limit-based) or deducts credits
3. **Purchased consumable packs** (e.g., "10 Super Likes") still work — they add to `creditBalance`
4. **`superLikeCreditsRemaining`** in `SwipeActionResponse` now reflects the central credit balance, not lot-specific credits

---

## 7. Entitlements Response Changes

### `limits` map — action quotas

| Key | `resetsAt` | Description |
|-----|-----------|-------------|
| `likes` | Next UTC midnight | Daily like quota |
| `superLikes` | Next UTC midnight | Daily super like quota |
| `rewinds` | Next UTC midnight | Daily rewind quota |
| `boosts` | `null` | Boost quota (period-based, not daily) |
| `voiceChatMsgs` | Next UTC midnight | Daily voice message quota |
| `imageChatMsgs` | Next UTC midnight | Daily image message quota |

When `limit` is `null`, the action is **unlimited** for the user's plan. `remaining` will also be `null`.

### `credits` object

```json
{
  "creditBalance": 150,      // ← USE THIS
  "boostsAvailable": 0,       // deprecated, always 0
  "superLikesAvailable": 0,   // deprecated, always 0
  "rewindsAvailable": 0       // deprecated, always 0
}
```

### `activeBoost`

Present when a boost is active. `remainingSeconds` counts down in real-time. Client should display a countdown timer.

---

## 8. Action Cost & Limit Handling

### How actions work now

Every action (LIKE, SUPER_LIKE, REWIND, BOOST, VOICE_MESSAGE, IMAGE_MESSAGE, INCOGNITO_MODE, CHANGE_ADDRESS, RETURN_PASSED_PROFILE, SUPER_MESSAGE) goes through `ActionCostService.evaluate()`:

1. **Check plan limit** — If user's plan has `limit_value = NULL` → unlimited, no credits needed
2. **Check daily usage** — If `used < limit` → allowance covers it, no credits needed
3. **Credits required** — If daily limit exceeded but credits available → deduct from `creditBalance`
4. **Blocked** — If limit exceeded and no credits → return `402 Payment Required`

### Client error handling

When an action fails due to limits/credits, the backend returns:

```json
// HTTP 422 Unprocessable Entity
{
  "error": "limit_exceeded",
  "limit_type": "SUPERLIKES"
}
```

Or for credit-based actions:

```json
// HTTP 402 Payment Required
{
  "error": "insufficient_credits",
  "action": "SUPER_LIKE",
  "credit_cost": 5,
  "balance": 3
}
```

### Action endpoints affected

| Endpoint | Action Code | Cost Behavior | Response |
|----------|-------------|---------------|----------|
| `POST /api/v1/discovery/actions/like` | `LIKE` | Free up to daily limit, then credits | `SwipeActionResponse` |
| `POST /api/v1/discovery/actions/superlike` | `SUPER_LIKE` | Free up to daily limit, then credits | `SwipeActionResponse` |
| `POST /api/v1/discovery/actions/rewind` | `REWIND` | Free up to daily limit, then credits | `RewindResponse` |
| `POST /api/v1/billing/boosts/activate` | `BOOST` | Free up to plan limit, then credits | `BoostActivationResponse` |
| `POST /api/v1/discovery/passes/revisit` | `RETURN_PASSED_PROFILE` | Free up to daily limit, then credits | `RevisitPassesResponse` |
| `POST /api/v1/discovery/actions/{actionId}/reveal` | `SEE_WHO_LIKED_YOU` | Free up to plan limit, then credits | `RevealResponse` |
| `POST /api/v1/discovery/super-messages` | `SUPER_MESSAGE` | Credits required | `SuperMessageResponse` |
| `PUT /api/v1/profiles/me` (incognito) | `INCOGNITO_MODE` | Credits required when enabling | `ProfileMeDto` |
| `PUT /api/v1/profiles/me/location` | `CHANGE_ADDRESS` | Free on first creation, credits required on updates | `ProfileLocationDto` |
| `POST /api/v1/chat/matches/{matchId}/messages/attachments` (voice) | `VOICE_MESSAGE` | Free up to daily limit, then credits | `ChatMessageDto` |
| `POST /api/v1/chat/matches/{matchId}/messages/attachments` (image) | `IMAGE_MESSAGE` | Free up to daily limit, then credits | `ChatMessageDto` |

### Action Response Shapes

**`RewindResponse`** — returned by `POST /api/v1/discovery/actions/rewind`:
```json
{
  "reversedActionId": "uuid",
  "reversedActionType": "LIKE",
  "reversedTargetUserId": "uuid",
  "matchCancelled": false,
  "matchId": null,
  "dailyRewindsRemaining": 9,
  "restoredProfile": { /* DiscoveryProfileDto — the profile put back in the feed */ },
  "reversedAt": "2026-08-15T12:00:00Z"
}
```

**`RevisitPassesResponse`** — returned by `POST /api/v1/discovery/passes/revisit?count=10`:
```json
{
  "success": true,
  "reopenedCount": 8
}
```
- `count` query param must be `10`, `20`, or `30`
- `reopenedCount` may be less than requested if fewer passes are available

**`SuperMessageResponse`** — returned by `POST /api/v1/discovery/super-messages`:
```json
{
  "id": "uuid",
  "senderId": "uuid",
  "receiverId": "uuid",
  "message": "Hey, I noticed you love hiking...",
  "actionType": "SUPER_MESSAGE",
  "creditCost": 10,
  "status": "PENDING",
  "viewedAt": null,
  "respondedAt": null,
  "matchId": null,
  "discoveryActionId": "uuid",
  "createdAt": "2026-08-15T12:00:00Z"
}
```
- `creditCost`: credits deducted from balance for this send
- `status`: `PENDING` → `VIEWED` → `RESPONDED` / `DECLINED`
- `matchId`: populated if receiver responds (creating a match)

---

## 9. Recommended Client Flows

### 9.1 App Launch Flow

```
1. GET /api/v1/billing/entitlements
   → Cache plan, limits, creditBalance, activeBoost
   → If activeBoost present, start countdown timer

2. GET /api/v1/billing/country-settings
   → Determine if subscriptions/credits are enabled for user's country
   → Gate paywall UI accordingly
```

### 9.2 Paywall / Purchase Flow

```
1. GET /api/v1/billing/payment-channels?platform=MOBILE
   → Display available payment channels (Online Payment, Bank Transfer, etc.)
   → Show channel.displayOrder for sorting

2. User selects a channel:
   GET /api/v1/billing/payment-options?platform=MOBILE&channel={channelCode}
   → Display payment methods within the channel

3. GET /api/v1/billing/offers?platform=MOBILE
   → Display available offers (subscriptions + credit packs)
   → Filter out offers where hasAvailablePaymentMethods = false
   → Show promotion/claimablePromotions if present

4. User selects offer + payment method:
   POST /api/v1/billing/orders
   {
     "paymentOfferId": "...",
     "paymentMethodId": "...",
     "platform": "MOBILE",
     "idempotencyKey": "generate-uuid()"
   }

5. Branch by paymentChannel:
```

### 9.3 Online Payment Flow (Chapa)

```
5a. Response has providerCheckoutUrl
    → Open WebView / redirect to providerCheckoutUrl
    → User completes payment on Chapa's page

6a. Poll order status:
    GET /api/v1/billing/orders/{orderId}
    → Repeat every 5 seconds (use pollAfterMs if present)
    → Stop when status is VERIFIED, MANUAL_REVIEW, or REJECTED

  OR (fallback):
    POST /api/v1/billing/orders/{orderId}/verify
    → Forces server-side Chapa re-verification
    → Returns updated OrderResponse

7a. On VERIFIED:
    → Refresh entitlements: GET /api/v1/billing/entitlements
    → Show success UI

7a. On MANUAL_REVIEW:
    → Show "Payment under review" message
    → canContactSupport = true → show support button

7a. On REJECTED:
    → Show "Payment failed" message
    → Allow retry
```

### 9.4 Manual Transfer (verify.et) Flow

```
5b. Response has paymentInstructions (from method)
    → Display bank account details, amount, reference
    → User completes bank transfer in their banking app

6b. User submits verification:
    POST /api/v1/billing/manual-transfer/verify
    {
      "paymentOfferId": "...",
      "paymentMethodId": "...",
      "platform": "MOBILE",
      "verificationData": {
        "senderName": "...",
        "transferReference": "...",
        "transferAmount": "...",
        "transferDate": "...",
        "fromBank": "..."
      },
      "idempotencyKey": "generate-uuid()"
    }

7b. Response status = VERIFICATION_PENDING
    → pollAfterMs = 5000
    → Poll: GET /api/v1/billing/orders/{orderId} every 5s

8b. Possible outcomes:
    → VERIFIED → success, refresh entitlements
    → REVIEW_REQUIRED → bank/amount mismatch, show "under review"
       → canRetryVerification = true → allow retry
       → canUploadReceipt = true → allow receipt upload as alternative
    → REJECTED → show failure, allow retry
    → EXPIRED → transfer too old (>48h), show "verification expired"
```

### 9.5 Manual Receipt Upload Flow

```
5c. User selects "Upload Receipt" instead of verify.et:
    → Upload receipt file to storage (Supabase S3)
    → Get storage bucket + path

6c. POST /api/v1/billing/manual-transfer/receipt
    {
      "paymentOfferId": "...",
      "paymentMethodId": "...",
      "platform": "MOBILE",
      "receiptStorageBucket": "qaliye-receipts",
      "receiptStoragePath": "receipts/.../receipt.pdf",
      "additionalNotes": { "transferAmount": "1500" },
      "idempotencyKey": "generate-uuid()"
    }

7c. Response status = RECEIPT_SUBMITTED
    → No polling (admin reviews manually)
    → canContactSupport = true
    → Show "Receipt submitted, we'll review within 24-48 hours"
```

### 9.6 Order Status Flow

```
CREATED
  ├── ONLINE_PAYMENT → providerCheckoutUrl → user pays
  │     ├── webhook → VERIFIED → fulfilled
  │     ├── webhook → MANUAL_REVIEW
  │     └── webhook → REJECTED
  │
  ├── MANUAL_TRANSFER → user transfers → submit verification
  │     ├── POST /manual-transfer/verify → VERIFICATION_PENDING
  │     │     ├── verify.et → VERIFIED → fulfilled
  │     │     ├── verify.et → REVIEW_REQUIRED (bank/amount mismatch)
  │     │     │     ├── retry verification
  │     │     │     └── upload receipt → RECEIPT_SUBMITTED
  │     │     ├── verify.et → REJECTED
  │     │     └── verify.et → EXPIRED (>48h)
  │     │
  │     └── POST /manual-transfer/receipt → RECEIPT_SUBMITTED
  │           ├── admin approves → VERIFIED → fulfilled
  │           └── admin rejects → REJECTED
  │
  └── EXPIRED (order not completed within expiry window)
```

### 9.7 Boost Activation Flow

```
1. User taps "Activate Boost"
2. POST /api/v1/billing/boosts/activate
   {
     "idempotencyKey": "generate-uuid()"
   }

3. Response:
   → 200 OK with BoostActivationResponse → show boost active UI with countdown
   → 402 Payment Required → show "Insufficient credits" → redirect to paywall
   → 422 limit_exceeded → show "Daily boost limit reached" → redirect to paywall

4. While boost active:
   → GET /api/v1/billing/entitlements periodically to refresh activeBoost.remainingSeconds
   → Or use client-side countdown from expiresAt
```

### 9.8 Action Execution Flow (Like / Super Like / Rewind)

```
1. User performs action
2. Client sends request (e.g., POST /discovery/actions/superlike)
3. Response includes:
   → dailySuperLikesRemaining (quota-based remaining)
   → superLikeCreditsRemaining (central credit balance)

4. On 422 limit_exceeded:
   → Show "Daily limit reached" with option to use credits or upgrade
   → Redirect to paywall

5. On 402 Payment Required:
   → Show "Insufficient credits" → redirect to paywall / credit purchase

6. On success:
   → Update local quota/credit cache from response
   → Or refresh: GET /api/v1/billing/entitlements
```

### 9.9 Reveal (See Who Likes You) Flow

```
1. User views "Who Likes You" list / feed
2. For each hidden profile, user taps to reveal:
   POST /api/v1/discovery/actions/{actionId}/reveal
   → actionId is the discovery action ID (LIKE or SUPERLIKE from another user)

3. Response (RevealResponse):
   {
     "actionId": "uuid",
     "actionType": "LIKE",
     "actorUserId": "uuid",
     "actorDisplayName": "Alice",
     "actorAge": 28,
     "actorPrimaryPhotoUrl": "https://...signed-url...",
     "idempotent": false,
     "creditBalance": 145
   }

4. On 422 limit_exceeded:
   → Show "Daily reveal limit reached" → redirect to paywall

5. On 402 Payment Required:
   → Show "Insufficient credits" → redirect to paywall / credit purchase

6. On success:
   → Display revealed profile (name, age, photo)
   → Update local credit balance from creditBalance field
   → idempotent = true means already revealed previously (no charge)
```

### 9.10 Identity Verification Flow (Selfie Match)

```
1. User navigates to "Verify Identity" in profile settings
2. Client checks if user already has an approved primary profile photo
   → If not, prompt user to upload and get a photo approved first

3. User captures selfie:
   POST /api/v1/profile/identity-verification
   Content-Type: multipart/form-data
   Field: selfie (image file, max 5MB)

4. Response:
   {
     "verification_status": "VERIFIED",
     "message": "Identity verified successfully (similarity: 95%)."
   }

5. Possible outcomes:
   → VERIFIED → show success, profile gets verified badge
   → FAILED → show failure reason, allow retry
   → 409 verification_in_progress → show "already pending"

6. If Rekognition service unavailable:
   → FAILED with "Verification service temporarily unavailable. Please try again."
   → Allow retry
```

### 9.11 Manual Verification Flow (Admin Review)

```
1. User uploads verification selfie to storage (Supabase S3)
   → Get storage path

2. Submit for admin review:
   POST /api/v1/verification/submit
   {
     "storagePath": "verification-selfies/user-uuid/selfie.jpg"
   }

3. Response:
   {
     "verification_id": "uuid",
     "status": "PENDING"
   }

4. User waits for admin review (no polling — notification sent)
   → On approval: notification dispatched, profile.is_verified = true
   → On rejection: notification dispatched with rejection reason

5. Error handling:
   → 400 no_approved_photo → "Upload a profile photo first"
   → 409 verification_pending → "Already under review"
```

### 9.13 Promotion Claim Flow

```
1. On app open or paywall screen:
   GET /api/v1/billing/promotions
   → Filter for canRedeem = true → show claim banner/button

2. Also check OfferDto.claimablePromotions[] from GET /api/v1/billing/offers
   → Offers may list promotions that can be applied

3. User taps "Claim Free Premium":
   POST /api/v1/billing/promotions/{campaignKey}/redeem
   → 201 Created with RedeemPromotionResponse
   → planCode = "FREE_PREMIUM", periodEnd = expiry

4. Refresh entitlements:
   GET /api/v1/billing/entitlements
   → plan = "FREE_PREMIUM"
   → subscription.provider = "PROMOTION"
   → Update UI to show premium features

5. Error handling:
   → 403 promotion_not_eligible → hide claim button for this campaign
   → 409 → show "Already claimed" or "No slots available"
```

### 9.12 RevenueCat (Mobile In-App Purchase) Flow

For iOS/Android native in-app purchases processed via RevenueCat:

```
1. Client fetches offers:
   GET /api/v1/billing/offers?platform=MOBILE
   → Use externalProductId, revenuecatOfferingId, revenuecatPackageId
   → to configure RevenueCat SDK offerings

2. User purchases via RevenueCat SDK (App Store / Play Store)
   → RevenueCat processes payment
   → RevenueCat sends webhook to backend
   → Backend fulfills subscription via FulfillmentService

3. Client polls for entitlement update:
   GET /api/v1/billing/entitlements
   → Repeat every 3-5s after purchase
   → Stop when plan changes to PREMIUM or credits increase

4. No direct order creation needed — RevenueCat handles the transaction
   → Backend matches webhook via app_user_id (user UUID)
   → Backend matches offer via externalProductId → findOfferByExternalProductId
```

---

## 11. Chat Attachment Endpoints

These endpoints are **new** and relevant to the payment system because sending voice/image attachments consumes credits (via `VOICE_MESSAGE` / `IMAGE_MESSAGE` action codes).

### POST `/api/v1/chat/matches/{matchId}/messages/attachments`

Send a message with one or more file attachments.

**Request:** `multipart/form-data`
```
POST /api/v1/chat/matches/{matchId}/messages/attachments
Content-Type: multipart/form-data

request: { "clientMessageId": "uuid", "messageType": "IMAGE", "body": null }
files: [<file1>, <file2>]
durations: [null, null]          // for voice files: duration in ms per file
```

For voice messages:
```
request: { "clientMessageId": "uuid", "messageType": "VOICE", "body": null }
files: [<audio.m4a>]
durations: [45000]               // 45 seconds in ms
```

**Response (`ChatMessageDto`):**
```json
{
  "id": "uuid",
  "matchId": "uuid",
  "senderId": "uuid",
  "body": null,
  "messageType": "IMAGE",
  "sequence": 42,
  "createdAt": "2026-08-15T12:00:00Z",
  "attachments": [
    {
      "id": "uuid",
      "messageId": "uuid",
      "attachmentType": "IMAGE",
      "fileName": "photo.jpg",
      "contentType": "image/jpeg",
      "fileSizeBytes": 204800,
      "durationMs": null,
      "downloadUrl": "https://...signed-url...",
      "createdAt": "2026-08-15T12:00:00Z"
    }
  ]
}
```

**Limits (from config):**
- Max image size: 25 MB per file
- Max voice size: 25 MB per file
- Max voice duration: 300 seconds
- Max images per message: 5
- Max voice messages per message: 1
- Max total attachments per message: 5

**Credit cost:** Sending a voice/image attachment triggers `ActionCostService.evaluate()` for `VOICE_MESSAGE` / `IMAGE_MESSAGE`. If the daily limit is exceeded, credits are consumed from `creditBalance`.

**Error responses:**
- `402 Payment Required` — Insufficient credits
- `422 limit_exceeded` with `limit_type: VOICE_MESSAGES` or `IMAGE_MESSAGES`

**Idempotency:** Re-submitting the same `clientMessageId` returns the existing message without re-uploading or re-charging.

### POST `/api/v1/chat/attachments/{attachmentId}/signed-url`

Refreshes the signed download URL for an attachment (signed URLs expire after ~1 hour).

**Request:** No body.

**Response:** Same `ChatAttachmentDto` with a fresh `downloadUrl`.

---

## 12. Discovery Counts & Likes Endpoints

These endpoints are relevant to the payment system because they power the "Who Likes You" feature that uses the `SEE_WHO_LIKED_YOU` credit action.

### GET `/api/v1/discovery/counts`

Returns the count of received likes, sent likes, and matches. Use this for badge counts in the UI.

**Response:**
```json
{
  "receivedLikesCount": 12,
  "sentLikesCount": 5,
  "matchesCount": 3
}
```

> **Note:** `receivedLikesCount` counts all likes including blurred/hidden ones. Each reveal uses `SEE_WHO_LIKED_YOU` credit action.

### GET `/api/v1/discovery/likes`

Paginated list of likes with optional direction filter.

```
GET /api/v1/discovery/likes?direction=RECEIVED&page=0&size=20
```

- `direction`: `RECEIVED` (default) or `SENT`

**Response (`LikesPageResponse`):**
```json
{
  "items": [
    {
      "actionId": "uuid",
      "userId": "uuid",
      "displayName": "Alice",
      "age": 28,
      "isVerified": true,
      "primaryPhotoUrl": "https://...blurred-or-signed-url...",
      "actionType": "LIKE",
      "likedAt": "2026-08-15T10:00:00Z",
      "distanceKm": 5,
      "city": "Addis Ababa",
      "region": "Addis Ababa",
      "countryName": "Ethiopia",
      "activityStatus": "RECENTLY_ACTIVE"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 12,
  "totalPages": 1,
  "hasNext": false,
  "hasPrevious": false,
  "direction": "RECEIVED"
}
```

Use `actionId` from each item to call `POST /api/v1/discovery/actions/{actionId}/reveal`.

### GET `/api/v1/discovery/matches`

Paginated list of mutual matches.

```
GET /api/v1/discovery/matches?page=0&size=20
```

---

## 10. Error Codes Reference

### Billing errors

| HTTP Status | Error Code | Description |
|-------------|-----------|-------------|
| 400 | `invalid_offer` | Offer ID not found |
| 400 | `invalid_payment_method` | Payment method ID not found |
| 400 | `payment_method_unavailable` | Payment method is not active |
| 400 | `not_manual_transfer_method` | Method is not a manual transfer type |
| 400 | `no_active_online_payment_method` | No online gateway configured for market |
| 400 | `offer_method_market_mismatch` | Offer and method belong to different markets |
| 400 | `invalid_payment_method_for_market` | Method not available in user's market |
| 402 | `insufficient_credits` | Not enough credits for action |
| 402 | `insufficient_boost_credits` | Not enough credits/allowance for boost |
| 402 | `incognito_mode_not_available` | Not enough credits/allowance for incognito |
| 402 | `change_address_not_available` | Not enough credits/allowance for address change |
| 402 | `insufficient_reveal_credits` | Not enough credits/allowance for reveal |
| 409 | `verification_in_progress` | Identity verification already pending |
| 400 | `no_approved_photo` | No approved profile photo for verification |
| 409 | `verification_pending` | Manual verification already under review |
| 403 | `promotion_not_eligible` | User does not qualify for this promotion |
| 404 | `promotion_not_found` | Invalid promotion campaign key |
| 422 | `limit_exceeded` | Daily/period limit reached (see `limit_type` in body) |

### Order statuses

| Status | Description | Client Action |
|--------|-------------|---------------|
| `CREATED` | Order created, awaiting payment | Show payment instructions / checkout URL |
| `VERIFICATION_PENDING` | verify.et checking transfer | Poll every 5s |
| `REVIEW_REQUIRED` | Bank/amount mismatch or manual review | Show review UI, allow retry or receipt upload |
| `RECEIPT_SUBMITTED` | Receipt uploaded, awaiting admin review | Show "under review", no polling |
| `VERIFIED` | Payment confirmed | Refresh entitlements, show success |
| `MANUAL_REVIEW` | Admin reviewing | Show "under review", show support button |
| `REJECTED` | Payment rejected | Show failure, allow retry |
| `EXPIRED` | Order or transfer expired | Show expired, allow new order |
| `CANCELLED` | Order cancelled | Show cancelled |

### `limit_type` values in 422 responses

| `limit_type` | Action |
|--------------|--------|
| `LIKES` | Like action |
| `SUPERLIKES` | Super like action |
| `REWINDS` | Rewind action |
| `BOOSTS` | Boost activation |
| `SEE_WHO_LIKED_YOU` | Reveal action |
| `VOICE_MESSAGES` | Voice chat message |
| `IMAGE_MESSAGES` | Image chat message |

---

## Implementation Checklist for Client Teams

- [ ] Migrate `platform` param from `ANDROID`/`IOS` → `MOBILE`
- [ ] Update `GET /payment-channels` response parsing (object with `paymentChannels[]`)
- [ ] Update `GET /payment-options` response parsing (new fields)
- [ ] Add `?channel=` filter support for payment options
- [ ] Implement `POST /manual-transfer/verify` flow with verify.et
- [ ] Implement `POST /manual-transfer/receipt` flow for receipt uploads
- [ ] Handle new order statuses: `REVIEW_REQUIRED`, `RECEIPT_SUBMITTED`
- [ ] Parse new `OrderResponse` fields: `pollAfterMs`, `canRetryVerification`, `canUploadReceipt`, `canContactSupport`, `verifyEtRequestId`, `verificationCount`
- [ ] Update entitlements parsing: use `credits.creditBalance` only
- [ ] Update boost UI: allowance may come from plan limits, not just credits
- [ ] Implement polling for `VERIFICATION_PENDING` orders (5s interval)
- [ ] Handle `402 Payment Required` for credit-based actions
- [ ] Handle `422 limit_exceeded` for quota-based actions
- [ ] Implement RevenueCat SDK integration using offer `externalProductId` / `revenuecatOfferingId` / `revenuecatPackageId`
- [ ] Add `idempotencyKey` generation for all order/verification/create requests
- [ ] Implement `POST /discovery/actions/{actionId}/reveal` flow (See Who Likes You)
- [ ] Implement `POST /profile/identity-verification` selfie upload flow
- [ ] Implement `POST /verification/submit` manual verification flow
- [ ] Handle identity verification statuses: `VERIFIED`, `FAILED`, `PENDING`
- [ ] Handle reveal `402` and `422` errors with paywall redirect
- [ ] Implement `GET /discovery/counts` for like/match badge counts
- [ ] Implement `GET /discovery/likes` paginated list for "Who Likes You" screen
- [ ] Implement promotions: `GET /billing/promotions`, `POST /billing/promotions/{key}/redeem`
- [ ] Handle `FREE_PREMIUM` plan code in entitlements after promotion redemption
- [ ] Implement chat attachment send: `POST /chat/matches/{matchId}/messages/attachments`
- [ ] Implement signed URL refresh: `POST /chat/attachments/{attachmentId}/signed-url`
- [ ] Handle `VOICE_MESSAGE` / `IMAGE_MESSAGE` credit errors in chat
- [ ] Parse `attachments[]` array in `ChatMessageDto` for image/voice rendering
- [ ] Parse `RewindResponse.restoredProfile` to re-insert profile into feed after rewind
- [ ] Parse `RevisitPassesResponse.reopenedCount` to update feed count
- [ ] Parse `SuperMessageResponse.creditCost` and update `creditBalance` after send

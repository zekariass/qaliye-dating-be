# Promotion System API Description

> Base URL: `/api/v1`

All endpoints require a valid JWT Bearer token in the `Authorization` header.

---

## Table of Contents

1. [User-Facing Endpoints](#user-facing-endpoints)
   - [GET /billing/offers](#get-billingoffers)
   - [GET /billing/promotions](#get-billingpromotions)
   - [GET /billing/promotions/{campaignKey}](#get-billingpromotionscampaignkey)
   - [GET /billing/promotions/redemptions](#get-billingpromotionsredemptions)
   - [POST /billing/orders](#post-billingorders)
   - [POST /billing/promotions/{campaignKey}/redeem](#post-billingpromotionscampaignkeyredeem)
2. [Admin Endpoints](#admin-endpoints)
   - [POST /admin/billing/campaigns](#post-adminbillingcampaigns)
   - [GET /admin/billing/campaigns](#get-adminbillingcampaigns)
   - [GET /admin/billing/campaigns/{id}](#get-adminbillingcampaignsid)
   - [PUT /admin/billing/campaigns/{id}](#put-adminbillingcampaignsid)
   - [POST /admin/billing/campaigns/{id}/activate](#post-adminbillingcampaignsidactivate)
   - [POST /admin/billing/campaigns/{id}/pause](#post-adminbillingcampaignsidpause)
   - [POST /admin/billing/campaigns/{id}/expire](#post-adminbillingcampaignsidexpire)
   - [GET /admin/billing/campaigns/{id}/redemptions](#get-adminbillingcampaignsidredemptions)
3. [Data Models](#data-models)
   - [EligiblePromotionDto](#eligiblepromotiondto)
   - [UserRedemptionDto](#userredemptiondto)
4. [Enum Reference](#enum-reference)
5. [Gender-Targeted Campaigns](#gender-targeted-campaigns)
6. [Error Codes](#error-codes)

---

## User-Facing Endpoints

### GET /billing/offers

Retrieves all active subscription offers for the authenticated user's market. Each offer is enriched with:
- The **best applicable PURCHASE-trigger promotion** (discount), if any — shown as `promotion` with discounted pricing.
- A list of **claimable USER_CLAIM promotions** (free premium trials) the user is eligible for — shown as `claimablePromotions`.

The frontend should display `effectivePriceMinorUnits` / `effectiveDisplayPrice` when a `promotion` is present, and show claimable promotion banners from `claimablePromotions`.

**Query Params:**

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `platform` | string | `ANDROID` | Platform filter (`ANDROID` or `IOS`) |

**Response: `200 OK`** — `OfferDto[]`

```json
[
  {
    "id": "uuid",
    "productCode": "premium_monthly_et",
    "productType": "SUBSCRIPTION",
    "countryCode": "ET",
    "currency": "ETB",
    "priceMinorUnits": 49900,
    "displayPrice": "499.00 ETB",
    "effectivePriceMinorUnits": 39920,
    "effectiveDisplayPrice": "399.20 ETB",
    "billingIntervalCount": 1,
    "billingIntervalUnit": "MONTH",
    "autoRenew": true,
    "externalProductId": "rc_premium_monthly",
    "revenuecatOfferingId": "default",
    "revenuecatPackageId": "$rc_monthly",
    "hasAvailablePaymentMethods": true,
    "availablePaymentMethodCount": 3,
    "promotion": {
      "campaignId": "uuid",
      "campaignKey": "summer_20pct",
      "name": "Summer 20% Off",
      "description": "20% off your first month",
      "discountType": "PERCENTAGE",
      "discountValueBasisPointsOrMinorUnits": 2000,
      "discountCurrency": null,
      "originalAmountMinor": 49900,
      "discountAmountMinor": 9980,
      "finalAmountMinor": 39920,
      "effectiveDisplayPrice": "399.20 ETB",
      "endsAt": "2026-08-31T23:59:59Z"
    },
    "claimablePromotions": [
      {
        "campaignId": "uuid",
        "campaignKey": "free_7day_trial",
        "name": "7-Day Free Premium",
        "description": "Try premium free for 7 days",
        "durationDays": 7,
        "endsAt": "2026-12-31T23:59:59Z"
      }
    ]
  }
]
```

**Field Notes:**

| Field | Description |
|-------|-------------|
| `effectivePriceMinorUnits` | Price after promotion discount (same as `priceMinorUnits` if no promotion) |
| `effectiveDisplayPrice` | Human-readable discounted price string |
| `promotion` | `null` if no PURCHASE promotion applies. Present when a discount campaign matches. |
| `claimablePromotions` | Empty array if no claimable campaigns. Each entry can be redeemed via the redeem endpoint. |
| `promotion.discountValueBasisPointsOrMinorUnits` | For `PERCENTAGE`: basis points (2000 = 20%). For `FIXED`: minor currency units. |
| `promotion.endsAt` | Campaign end timestamp. `null` if no end date. |

---

### GET /billing/promotions

Retrieves all active promotion campaigns that the authenticated user is currently eligible for, across all subscription products. This is a unified view of both:
- **USER_CLAIM** campaigns (FREE_PREMIUM) — the user can redeem these via `POST /billing/promotions/{campaignKey}/redeem`
- **PURCHASE** campaigns (DISCOUNT) — these are automatically applied at checkout, shown here for informational/display purposes

Use this endpoint to build a "Promotions" or "Available Offers" screen in the frontend. The `canRedeem` flag indicates which promotions the user can actively claim (USER_CLAIM + FREE_PREMIUM). PURCHASE discounts are informational only — they are auto-applied during checkout.

**Query Params:** None

**Response: `200 OK`** — `EligiblePromotionDto[]`

```json
[
  {
    "campaignId": "uuid",
    "campaignKey": "free_7day_trial",
    "name": "7-Day Free Premium",
    "description": "Try premium free for 7 days",
    "triggerType": "USER_CLAIM",
    "benefitType": "FREE_PREMIUM",
    "discountType": null,
    "discountValue": null,
    "discountCurrency": null,
    "subscriptionProductId": "uuid",
    "durationDays": 7,
    "maxRedemptions": 1000,
    "reservedCount": 42,
    "fulfilledCount": 318,
    "endsAt": "2026-12-31T23:59:59Z",
    "targetGender": null,
    "canRedeem": true
  },
  {
    "campaignId": "uuid",
    "campaignKey": "summer_20pct",
    "name": "Summer 20% Off",
    "description": "20% off your first month",
    "triggerType": "PURCHASE",
    "benefitType": "DISCOUNT",
    "discountType": "PERCENTAGE",
    "discountValue": 2000,
    "discountCurrency": null,
    "subscriptionProductId": "uuid",
    "durationDays": null,
    "maxRedemptions": 5000,
    "reservedCount": 12,
    "fulfilledCount": 873,
    "endsAt": "2026-08-31T23:59:59Z",
    "targetGender": "MALE",
    "canRedeem": false
  }
]
```

**Field Notes:**

| Field | Description |
|-------|-------------|
| `triggerType` | `USER_CLAIM` (user can redeem) or `PURCHASE` (auto-applied at checkout) |
| `benefitType` | `FREE_PREMIUM` (free subscription days) or `DISCOUNT` (price reduction) |
| `discountType` | `PERCENTAGE` or `FIXED` (null for FREE_PREMIUM) |
| `discountValue` | Basis points (PERCENTAGE: 2000 = 20%) or minor units (FIXED). Null for FREE_PREMIUM. |
| `discountCurrency` | Currency code for FIXED discounts. Null for PERCENTAGE or FREE_PREMIUM. |
| `subscriptionProductId` | The product this promotion applies to — match against offers to display alongside the relevant plan |
| `durationDays` | Free premium duration (for FREE_PREMIUM only) |
| `maxRedemptions` | Total capacity cap. Null = unlimited. |
| `reservedCount` | Currently reserved (pending) count |
| `fulfilledCount` | Completed redemptions count |
| `endsAt` | Campaign end timestamp (ISO-8601). Null = no end date. |
| `targetGender` | `"MALE"`, `"FEMALE"`, or `null` (no gender restriction). See [Gender-Targeted Campaigns](#gender-targeted-campaigns). |
| `canRedeem` | `true` only for USER_CLAIM + FREE_PREMIUM campaigns. Frontend should show a "Claim" button when true. |

**Frontend Integration Tips:**
- Filter by `canRedeem=true` to show only actionable promotions in a "Claim your reward" section.
- Use `reservedCount` and `fulfilledCount` vs `maxRedemptions` to show scarcity/urgency (e.g., "Only 640 left!").
- Match `subscriptionProductId` to the product in `GET /billing/offers` to show the promotion alongside the relevant subscription plan.
- For `PURCHASE` promotions, display the discount info but no claim button — these are automatically applied at checkout.
- `targetGender` indicates the campaign is restricted to a specific gender. The backend already filters this server-side — only eligible promotions are returned. Frontend can optionally show a gender-specific label (e.g., "For women") when `targetGender` is non-null.

---

### GET /billing/promotions/{campaignKey}

Retrieves details of a single promotion campaign by its key, **only if the current user is eligible**. Useful for deep-linked promotion landing pages (e.g., from push notifications, marketing emails, or shared links).

The endpoint validates eligibility at request time. If the campaign is inactive, expired, or the user doesn't meet the eligibility criteria, it returns an error rather than the campaign details.

**Path Params:**

| Param | Type | Description |
|-------|------|-------------|
| `campaignKey` | string | The unique campaign key (e.g., `free_7day_trial`) |

**Response: `200 OK`** — `EligiblePromotionDto`

```json
{
  "campaignId": "uuid",
  "campaignKey": "free_7day_trial",
  "name": "7-Day Free Premium",
  "description": "Try premium free for 7 days",
  "triggerType": "USER_CLAIM",
  "benefitType": "FREE_PREMIUM",
  "discountType": null,
  "discountValue": null,
  "discountCurrency": null,
  "subscriptionProductId": "uuid",
  "durationDays": 7,
  "maxRedemptions": 1000,
  "reservedCount": 42,
  "fulfilledCount": 318,
  "endsAt": "2026-12-31T23:59:59Z",
  "targetGender": null,
  "canRedeem": true
}
```

See [EligiblePromotionDto](#eligiblepromotiondto) for field details.

**Error Responses:**

| Status | Error Code | Description |
|--------|-----------|-------------|
| 404 | `promotion_not_found` | No campaign with that key exists |
| 403 | `promotion_not_eligible` | Campaign exists but user is not eligible (inactive, expired, wrong country, gender mismatch, capacity exhausted, etc.) |

---

### GET /billing/promotions/redemptions

Retrieves the authenticated user's promotion redemption history. This includes all redemptions across all campaigns — both USER_CLAIM (free premium) and PURCHASE (discount) — with their current status.

Use this endpoint to build a "Your Promotions" or "Rewards History" screen showing:
- Active free premium trials (status `FULFILLED`, with `subscriptionId` and `fulfilledAt`)
- Pending redemptions (status `RESERVED`, awaiting order completion)
- Cancelled or expired redemptions

**Query Params:**

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `page` | int | 1 | Page number (1-based) |
| `pageSize` | int | 20 | Items per page (max 100) |

**Response: `200 OK`** — `UserRedemptionDto[]`

```json
[
  {
    "id": "uuid",
    "campaignId": "uuid",
    "campaignKey": "free_7day_trial",
    "campaignName": "7-Day Free Premium",
    "benefitType": "FREE_PREMIUM",
    "durationDays": 7,
    "subscriptionId": "uuid",
    "paymentOrderId": null,
    "status": "FULFILLED",
    "originalAmountMinor": null,
    "discountAmountMinor": null,
    "finalAmountMinor": null,
    "currency": null,
    "reservedAt": "2026-07-20T22:00:00Z",
    "fulfilledAt": "2026-07-20T22:00:01Z",
    "cancelledAt": null,
    "expiredAt": null,
    "failureCode": null
  },
  {
    "id": "uuid",
    "campaignId": "uuid",
    "campaignKey": "summer_20pct",
    "campaignName": "Summer 20% Off",
    "benefitType": "DISCOUNT",
    "durationDays": null,
    "subscriptionId": null,
    "paymentOrderId": "uuid",
    "status": "RESERVED",
    "originalAmountMinor": 49900,
    "discountAmountMinor": 9980,
    "finalAmountMinor": 39920,
    "currency": "ETB",
    "reservedAt": "2026-07-21T10:00:00Z",
    "fulfilledAt": null,
    "cancelledAt": null,
    "expiredAt": null,
    "failureCode": null
  }
]
```

**Field Notes:**

| Field | Description |
|-------|-------------|
| `campaignKey` | The campaign key — can be used to deep-link to the promotion detail page |
| `campaignName` | Display name of the campaign |
| `benefitType` | `FREE_PREMIUM` or `DISCOUNT` |
| `durationDays` | Free premium duration (for FREE_PREMIUM only). Null for DISCOUNT. |
| `subscriptionId` | Created subscription ID (for FULFILLED FREE_PREMIUM redemptions). Null otherwise. |
| `paymentOrderId` | Associated order ID (for PURCHASE DISCOUNT redemptions). Null for USER_CLAIM. |
| `status` | `RESERVED`, `FULFILLED`, `CANCELLED`, or `EXPIRED` (see [Redemption Status](#redemption-status)) |
| `originalAmountMinor` | Original offer price (DISCOUNT only). Null for FREE_PREMIUM. |
| `discountAmountMinor` | Discount amount (DISCOUNT only). Null for FREE_PREMIUM. |
| `finalAmountMinor` | Final charged amount (DISCOUNT only). Null for FREE_PREMIUM. |
| `currency` | Currency code (DISCOUNT only). Null for FREE_PREMIUM. |
| `reservedAt` | When the redemption was created |
| `fulfilledAt` | When the subscription was granted or order completed. Null if not yet fulfilled. |
| `cancelledAt` | When cancelled (grant failure, order cancelled). Null if not cancelled. |
| `expiredAt` | When expired by the stale reservation cleanup worker. Null if not expired. |
| `failureCode` | Machine-readable failure reason. Null if no failure. |

**Frontend Integration Tips:**
- Filter by `status=FULFILLED` and `benefitType=FREE_PREMIUM` to show "Active Rewards" with the subscription end date.
- Filter by `status=RESERVED` to show "Pending" redemptions (awaiting payment confirmation).
- Use `campaignKey` to link to the promotion detail page via `GET /billing/promotions/{campaignKey}`.
- Results are ordered by `reservedAt` descending (most recent first).

---

### POST /billing/orders

Creates a payment order. If the offer has an applicable PURCHASE promotion, the system automatically:
1. Re-validates eligibility at checkout time.
2. Reserves promotion capacity atomically.
3. Applies the discounted `expectedAmountMinorUnits` to the order.
4. Records a `RESERVED` promotion redemption linked to the order.

The promotion is **automatically applied** — the frontend does not pass any promotion parameter. The discounted amount is reflected in `expectedAmountMinorUnits`.

If the gateway checkout fails after reservation, the promotion reservation is released automatically.

**Request Body:** `CreateOrderRequest`

```json
{
  "paymentOfferId": "uuid",
  "paymentMethodId": "uuid",
  "platform": "ANDROID",
  "idempotencyKey": "unique-client-key"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `paymentOfferId` | UUID | Yes | The offer ID from `GET /billing/offers` |
| `paymentMethodId` | UUID | Yes | Payment method ID from `GET /billing/payment-options` |
| `platform` | string | No | `ANDROID` or `IOS` |
| `idempotencyKey` | string | No | Client-generated unique key to prevent duplicate orders |

**Response: `201 Created`** — `OrderResponse`

```json
{
  "id": "uuid",
  "orderReference": "QAL-a1b2c3d4",
  "status": "CREATED",
  "statusReason": null,
  "paymentOfferId": "uuid",
  "expectedAmountMinorUnits": 39920,
  "expectedCurrency": "ETB",
  "paymentMethodId": "uuid",
  "paymentChannel": "TELEBIRR",
  "paymentMethod": "TELEBIRR_ONLINE",
  "methodCode": "telebirr",
  "paymentMethodDisplayName": "Telebirr",
  "providerCheckoutUrl": "https://checkout.example.com/...",
  "paymentInstructions": null,
  "expiresAt": "2026-07-21T01:00:00Z",
  "createdAt": "2026-07-20T22:00:00Z",
  "updatedAt": "2026-07-20T22:00:00Z",
  "verifyEtRequestId": null,
  "pollAfterMs": null,
  "canRetryVerification": false,
  "canUploadReceipt": false,
  "canContactSupport": false,
  "verificationCount": 0
}
```

**Promotion integration notes:**
- `expectedAmountMinorUnits` reflects the post-discount amount when a promotion is applied.
- When the order is later fulfilled (payment confirmed via webhook or manual verification), the promotion redemption transitions from `RESERVED` → `FULFILLED`.
- If the order is cancelled or fails, the redemption transitions to `CANCELLED` and the reserved capacity is released.

---

### POST /billing/promotions/{campaignKey}/redeem

Redeems a `USER_CLAIM` promotion campaign (e.g., a free premium trial). This is a user-initiated claim — the user sees the campaign in `claimablePromotions` on the offers endpoint and calls this to activate it.

The endpoint:
1. Validates the campaign exists, is `USER_CLAIM` trigger, `FREE_PREMIUM` benefit, and `ACTIVE`.
2. Checks eligibility (user type, country, capacity, per-user limits).
3. Atomically reserves capacity.
4. Creates a `RESERVED` redemption record.
5. Grants the free premium subscription immediately (inserts subscription, sets redemption to `FULFILLED`).
6. Returns the new subscription details.

If the grant fails (e.g., user already has an active subscription), the reservation is rolled back.

**Path Params:**

| Param | Type | Description |
|-------|------|-------------|
| `campaignKey` | string | The unique campaign key (e.g., `free_7day_trial`) |

**Response: `201 Created`** — `RedeemPromotionResponse`

```json
{
  "redemptionId": "uuid",
  "subscriptionId": "uuid",
  "campaignKey": "free_7day_trial",
  "planCode": null,
  "durationDays": 7,
  "periodEnd": "2026-07-27T22:00:00Z",
  "message": "Promotion redeemed successfully"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `redemptionId` | UUID | The promotion redemption record ID |
| `subscriptionId` | UUID | The newly created subscription ID |
| `campaignKey` | string | The campaign key that was redeemed |
| `planCode` | string\|null | The plan code (currently null — may be populated in future) |
| `durationDays` | int | Number of days the free premium lasts |
| `periodEnd` | Instant | When the free premium subscription expires |
| `message` | string | Human-readable success message |

**Error Responses:**

| Status | Error Code | Description |
|--------|-----------|-------------|
| 404 | `promotion_not_found` | No campaign with that key exists |
| 400 | `promotion_not_claimable` | Campaign trigger type is not `USER_CLAIM` |
| 400 | `promotion_type_unsupported` | Campaign benefit type is not `FREE_PREMIUM` |
| 410 | `promotion_not_active` | Campaign status is not `ACTIVE` (paused, expired, draft) |
| 410 | `promotion_expired` | Current time is outside `startsAt`–`endsAt` window |
| 422 | `promotion_not_eligible` | User fails eligibility check (see [Eligibility Types](#eligibility-types)) |
| 409 | `promotion_capacity_exhausted` | All redemption slots are reserved/fulfilled or user hit per-user limit |
| 409 | `user_has_active_subscription` | User already has an active subscription (grant failed) |

---

## Admin Endpoints

> Base: `/api/v1/admin/billing/campaigns`
> Requires admin role JWT.

### POST /admin/billing/campaigns

Creates a new promotion campaign in `DRAFT` status. The campaign must be activated via the `/activate` endpoint before it becomes visible to users.

**Request Body:** `CreateCampaignRequest`

```json
{
  "campaignKey": "summer_20pct",
  "name": "Summer 20% Off",
  "description": "20% off premium for summer",
  "triggerType": "PURCHASE",
  "eligibilityType": "ANY_ELIGIBLE_USER",
  "benefitType": "DISCOUNT",
  "discountType": "PERCENTAGE",
  "discountValue": 2000,
  "discountCurrency": null,
  "subscriptionProductId": "uuid",
  "countryCode": "ET",
  "durationDays": null,
  "newUserWindowDays": null,
  "maxRedemptions": 1000,
  "maxRedemptionsPerUser": 1,
  "priority": 10,
  "startsAt": "2026-07-20T00:00:00Z",
  "endsAt": "2026-08-31T23:59:59Z",
  "targetGender": null
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `campaignKey` | string | Yes | Unique slug-like key (e.g., `summer_20pct`). Used in redeem endpoint. |
| `name` | string | Yes | Display name |
| `description` | string | No | Human-readable description |
| `triggerType` | string | Yes | `PURCHASE`, `USER_CLAIM`, or `AUTO_ON_SIGNUP` |
| `eligibilityType` | string | Yes | `ANY_ELIGIBLE_USER`, `NEW_USER`, `NEVER_SUBSCRIBED`, or `NO_ACTIVE_SUBSCRIPTION` |
| `benefitType` | string | Yes | `DISCOUNT` or `FREE_PREMIUM` |
| `discountType` | string | Conditional | `PERCENTAGE` or `FIXED`. Required when `benefitType=DISCOUNT`. |
| `discountValue` | long | Conditional | For `PERCENTAGE`: basis points (2000 = 20%). For `FIXED`: minor currency units. Required when `benefitType=DISCOUNT`. |
| `discountCurrency` | string | Conditional | Currency code for `FIXED` discounts (e.g., `ETB`). `null` for `PERCENTAGE` or currency-agnostic. |
| `subscriptionProductId` | UUID | Yes | The subscription product this campaign applies to |
| `countryCode` | string | Yes | ISO 3166-1 alpha-2 country code, or `GLOBAL` for all markets |
| `durationDays` | int | Conditional | Days the free premium lasts. Required when `benefitType=FREE_PREMIUM`. |
| `newUserWindowDays` | int | Conditional | Days since user registration to count as "new". Required when `eligibilityType=NEW_USER`. |
| `maxRedemptions` | int | No | Total redemption cap across all users. `null` = unlimited. |
| `maxRedemptionsPerUser` | int | No | Per-user cap. Defaults to `1`. |
| `priority` | int | No | Higher = more important. Used for sorting `AUTO_ON_SIGNUP` campaigns and tie-breaking. Defaults to `0`. |
| `startsAt` | Instant | Yes | When the campaign becomes active (UTC ISO-8601) |
| `endsAt` | Instant | No | When the campaign expires. `null` = no expiry. Must be after `startsAt`. |
| `targetGender` | string | No | `"MALE"`, `"FEMALE"`, or `null` (no gender restriction). See [Gender-Targeted Campaigns](#gender-targeted-campaigns). |

**Response: `201 Created`** — `CampaignDto`

```json
{
  "id": "uuid",
  "campaignKey": "summer_20pct",
  "name": "Summer 20% Off",
  "description": "20% off premium for summer",
  "triggerType": "PURCHASE",
  "eligibilityType": "ANY_ELIGIBLE_USER",
  "benefitType": "DISCOUNT",
  "discountType": "PERCENTAGE",
  "discountValue": 2000,
  "discountCurrency": null,
  "subscriptionProductId": "uuid",
  "countryCode": "ET",
  "durationDays": null,
  "newUserWindowDays": null,
  "maxRedemptions": 1000,
  "maxRedemptionsPerUser": 1,
  "reservedCount": 0,
  "fulfilledCount": 0,
  "priority": 10,
  "startsAt": "2026-07-20T00:00:00Z",
  "endsAt": "2026-08-31T23:59:59Z",
  "status": "DRAFT",
  "targetGender": null,
  "createdAt": "2026-07-20T22:00:00Z",
  "updatedAt": "2026-07-20T22:00:00Z"
}
```

**Validation Errors (400):**

| Error Code | Condition |
|-----------|-----------|
| `campaign_key_required` | `campaignKey` is null or blank |
| `missing_required_fields` | Any required field is null |
| `invalid_benefit_type` | `benefitType` not `FREE_PREMIUM` or `DISCOUNT` (for PURCHASE/AUTO_ON_SIGNUP) |
| `duration_days_required` | `benefitType=FREE_PREMIUM` but `durationDays` is null or ≤ 0 |
| `discount_fields_required` | `benefitType=DISCOUNT` but `discountType` or `discountValue` is null |
| `new_user_window_days_required` | `eligibilityType=NEW_USER` but `newUserWindowDays` is null or ≤ 0 |
| `ends_at_must_be_after_starts_at` | `endsAt` is not after `startsAt` |
| `invalid_target_gender` | `targetGender` is not `null`, `"MALE"`, or `"FEMALE"` |

---

### GET /admin/billing/campaigns

Lists campaigns with optional status filter and pagination.

**Query Params:**

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `status` | string | null | Filter by status: `DRAFT`, `ACTIVE`, `PAUSED`, `EXPIRED`. Omit for all. |
| `page` | int | 1 | Page number (1-based) |
| `pageSize` | int | 20 | Items per page (max 100) |

**Response: `200 OK`**

```json
{
  "campaigns": [CampaignDto, ...],
  "page": 1,
  "pageSize": 20,
  "total": 45,
  "totalPages": 3
}
```

---

### GET /admin/billing/campaigns/{id}

Retrieves a single campaign by ID.

**Path Params:**

| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Campaign ID |

**Response: `200 OK`** — `CampaignDto`

**Errors:** `404 campaign_not_found`

---

### PUT /admin/billing/campaigns/{id}

Updates mutable campaign fields. Only `name`, `description`, `maxRedemptions`, `maxRedemptionsPerUser`, `priority`, and `endsAt` can be updated. Trigger type, benefit type, discount type, and product cannot be changed after creation.

**Request Body:** `UpdateCampaignRequest`

```json
{
  "name": "Summer 25% Off (Updated)",
  "description": "Now 25% off!",
  "maxRedemptions": 2000,
  "maxRedemptionsPerUser": 2,
  "priority": 20,
  "endsAt": "2026-09-30T23:59:59Z",
  "targetGender": "FEMALE"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | New display name |
| `description` | string | New description |
| `maxRedemptions` | int | New total cap |
| `maxRedemptionsPerUser` | int | New per-user cap |
| `priority` | int | New priority |
| `endsAt` | Instant | New end date (or null to remove) |
| `targetGender` | string | New target gender (`"MALE"`, `"FEMALE"`, or `null` to remove gender restriction) |

**Response: `200 OK`** — `CampaignDto`

**Errors:** `404 campaign_not_found`

---

### POST /admin/billing/campaigns/{id}/activate

Activates a campaign (transitions from `DRAFT` or `PAUSED` → `ACTIVE`). Only active campaigns are visible to users.

**Response: `200 OK`** — `CampaignDto` (with `status: "ACTIVE"`)

**Errors:**

| Status | Code | Condition |
|--------|------|-----------|
| 404 | `campaign_not_found` | Invalid ID |
| 400 | `invalid_status_transition` | Current status not in `DRAFT`, `PAUSED` |

---

### POST /admin/billing/campaigns/{id}/pause

Pauses an active campaign (`ACTIVE` → `PAUSED`). Paused campaigns are immediately hidden from users.

**Response: `200 OK`** — `CampaignDto` (with `status: "PAUSED"`)

**Errors:**

| Status | Code | Condition |
|--------|------|-----------|
| 404 | `campaign_not_found` | Invalid ID |
| 400 | `invalid_status_transition` | Current status not `ACTIVE` |

---

### POST /admin/billing/campaigns/{id}/expire

Permanently expires a campaign (`ACTIVE`, `PAUSED`, or `DRAFT` → `EXPIRED`). Existing fulfilled redemptions are unaffected. Reserved (non-fulfilled) redemptions remain until cleaned up by the hourly worker.

**Response: `200 OK`** — `CampaignDto` (with `status: "EXPIRED"`)

**Errors:**

| Status | Code | Condition |
|--------|------|-----------|
| 404 | `campaign_not_found` | Invalid ID |
| 400 | `invalid_status_transition` | Current status not in `ACTIVE`, `PAUSED`, `DRAFT` |

---

### GET /admin/billing/campaigns/{id}/redemptions

Lists redemption records for a campaign with pagination.

**Query Params:**

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `page` | int | 1 | Page number (1-based) |
| `pageSize` | int | 20 | Items per page (max 100) |

**Response: `200 OK`** — `RedemptionDto[]`

```json
[
  {
    "id": "uuid",
    "campaignId": "uuid",
    "campaignKey": null,
    "userId": "uuid",
    "subscriptionId": "uuid",
    "paymentOrderId": null,
    "status": "FULFILLED",
    "eligibilityCountry": "ET",
    "eligibilityGender": "MALE",
    "originalAmountMinor": 0,
    "discountAmountMinor": 0,
    "finalAmountMinor": 0,
    "currency": null,
    "reservedAt": "2026-07-20T22:00:00Z",
    "fulfilledAt": "2026-07-20T22:00:01Z",
    "cancelledAt": null,
    "expiredAt": null,
    "failureCode": null
  }
]
```

| Field | Description |
|-------|-------------|
| `status` | `RESERVED`, `FULFILLED`, `CANCELLED`, or `EXPIRED` |
| `originalAmountMinor` | Original offer price (for DISCOUNT promotions) |
| `discountAmountMinor` | Discount amount applied |
| `finalAmountMinor` | Final amount charged |
| `reservedAt` | When the redemption was created |
| `fulfilledAt` | When the subscription was granted / order fulfilled |
| `cancelledAt` | When the redemption was cancelled (grant failure, order cancelled) |
| `expiredAt` | When the stale reservation was expired by the cleanup worker |
| `failureCode` | Machine-readable failure reason (e.g., `grant_failed`) |

---

## Data Models

### EligiblePromotionDto

Returned by `GET /billing/promotions`. Represents a promotion campaign the user is currently eligible for.

| Field | Type | Description |
|-------|------|-------------|
| `campaignId` | UUID | Campaign ID |
| `campaignKey` | string | Unique campaign key (used in redeem endpoint) |
| `name` | string | Campaign display name |
| `description` | string\|null | Campaign description |
| `triggerType` | string | `USER_CLAIM` or `PURCHASE` |
| `benefitType` | string | `FREE_PREMIUM` or `DISCOUNT` |
| `discountType` | string\|null | `PERCENTAGE` or `FIXED` (null for FREE_PREMIUM) |
| `discountValue` | long\|null | Basis points (PERCENTAGE) or minor units (FIXED). Null for FREE_PREMIUM. |
| `discountCurrency` | string\|null | Currency for FIXED discounts |
| `subscriptionProductId` | UUID | Associated subscription product |
| `durationDays` | int\|null | Free premium duration in days (FREE_PREMIUM only) |
| `maxRedemptions` | int\|null | Total redemption cap. Null = unlimited. |
| `reservedCount` | int | Currently reserved (pending) count |
| `fulfilledCount` | int | Fulfilled count |
| `endsAt` | string\|null | Campaign end (ISO-8601). Null = no end. |
| `targetGender` | string\|null | `"MALE"`, `"FEMALE"`, or `null` (no restriction) |
| `canRedeem` | boolean | `true` for USER_CLAIM + FREE_PREMIUM. Indicates the user can call the redeem endpoint. |

### UserRedemptionDto

Returned by `GET /billing/promotions/redemptions`. Represents a user's redemption history entry.

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Redemption ID |
| `campaignId` | UUID | Campaign ID |
| `campaignKey` | string | Campaign key (for deep-linking to promotion detail) |
| `campaignName` | string | Campaign display name |
| `benefitType` | string | `FREE_PREMIUM` or `DISCOUNT` |
| `durationDays` | int\|null | Free premium duration (FREE_PREMIUM only). Null for DISCOUNT. |
| `subscriptionId` | UUID\|null | Created subscription (FULFILLED FREE_PREMIUM only) |
| `paymentOrderId` | UUID\|null | Associated order (PURCHASE DISCOUNT only) |
| `status` | string | `RESERVED`, `FULFILLED`, `CANCELLED`, `EXPIRED` |
| `originalAmountMinor` | long\|null | Original price (DISCOUNT only) |
| `discountAmountMinor` | long\|null | Discount amount (DISCOUNT only) |
| `finalAmountMinor` | long\|null | Final charged amount (DISCOUNT only) |
| `currency` | string\|null | Currency code (DISCOUNT only) |
| `reservedAt` | Instant | When the redemption was created |
| `fulfilledAt` | Instant\|null | When fulfilled. Null if not yet fulfilled. |
| `cancelledAt` | Instant\|null | When cancelled. Null if not cancelled. |
| `expiredAt` | Instant\|null | When expired by cleanup worker. Null if not expired. |
| `failureCode` | string\|null | Machine-readable failure reason. Null if no failure. |

### CampaignDto

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Campaign ID |
| `campaignKey` | string | Unique key for redeem endpoint |
| `name` | string | Display name |
| `description` | string\|null | Description |
| `triggerType` | string | See [Trigger Types](#trigger-types) |
| `eligibilityType` | string | See [Eligibility Types](#eligibility-types) |
| `benefitType` | string | See [Benefit Types](#benefit-types) |
| `discountType` | string\|null | `PERCENTAGE` or `FIXED` (null for FREE_PREMIUM) |
| `discountValue` | long\|null | Basis points (PERCENTAGE) or minor units (FIXED) |
| `discountCurrency` | string\|null | Currency for FIXED discounts |
| `subscriptionProductId` | UUID | Associated subscription product |
| `countryCode` | string | Market country or `GLOBAL` |
| `durationDays` | int\|null | Free premium duration (for FREE_PREMIUM) |
| `newUserWindowDays` | int\|null | New-user window (for NEW_USER eligibility) |
| `maxRedemptions` | int\|null | Total redemption cap |
| `maxRedemptionsPerUser` | int | Per-user cap |
| `reservedCount` | int | Currently reserved (pending) count |
| `fulfilledCount` | int | Fulfilled count |
| `priority` | int | Sort priority (higher = first) |
| `startsAt` | Instant | Campaign start (UTC) |
| `endsAt` | Instant\|null | Campaign end (UTC), null = no end |
| `status` | string | `DRAFT`, `ACTIVE`, `PAUSED`, `EXPIRED` |
| `targetGender` | string\|null | `"MALE"`, `"FEMALE"`, or `null` (no gender restriction) |
| `createdAt` | Instant | Creation timestamp |
| `updatedAt` | Instant | Last update timestamp |

### PromotionDto

Embedded in `OfferDto.promotion` when a PURCHASE discount applies.

| Field | Type | Description |
|-------|------|-------------|
| `campaignId` | UUID | Campaign ID |
| `campaignKey` | string | Campaign key |
| `name` | string | Campaign name |
| `description` | string\|null | Description |
| `discountType` | string | `PERCENTAGE` or `FIXED` |
| `discountValueBasisPointsOrMinorUnits` | long | Basis points (PERCENTAGE) or minor units (FIXED) |
| `discountCurrency` | string\|null | Currency for FIXED |
| `originalAmountMinor` | long | Original price in minor units |
| `discountAmountMinor` | long | Discount amount in minor units |
| `finalAmountMinor` | long | Final price in minor units |
| `effectiveDisplayPrice` | string | Human-readable discounted price |
| `endsAt` | string\|null | Campaign end (ISO-8601) |

### ClaimablePromotionDto

Embedded in `OfferDto.claimablePromotions[]` for USER_CLAIM campaigns.

| Field | Type | Description |
|-------|------|-------------|
| `campaignId` | UUID | Campaign ID |
| `campaignKey` | string | Campaign key (use in redeem endpoint) |
| `name` | string | Campaign name |
| `description` | string\|null | Description |
| `durationDays` | int\|null | Free premium duration in days |
| `endsAt` | string\|null | Campaign end (ISO-8601) |

### RedemptionDto

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Redemption ID |
| `campaignId` | UUID | Campaign ID |
| `campaignKey` | string\|null | Campaign key (null in admin list view) |
| `userId` | UUID | User who redeemed |
| `subscriptionId` | UUID\|null | Created subscription (if FULFILLED) |
| `paymentOrderId` | UUID\|null | Associated order (for PURCHASE promotions) |
| `status` | string | `RESERVED`, `FULFILLED`, `CANCELLED`, `EXPIRED` |
| `eligibilityCountry` | string | User's billing country at time of redemption |
| `eligibilityGender` | string\|null | User's gender at time of redemption (`"MALE"`, `"FEMALE"`, or `null`) |
| `originalAmountMinor` | long\|null | Original price (DISCOUNT only) |
| `discountAmountMinor` | long\|null | Discount amount (DISCOUNT only) |
| `finalAmountMinor` | long\|null | Final charged amount (DISCOUNT only) |
| `currency` | string\|null | Currency code (DISCOUNT only) |
| `reservedAt` | Instant | When reserved |
| `fulfilledAt` | Instant\|null | When fulfilled |
| `cancelledAt` | Instant\|null | When cancelled |
| `expiredAt` | Instant\|null | When expired by cleanup |
| `failureCode` | string\|null | Failure reason code |

---

## Enum Reference

### Trigger Types

| Value | Description |
|-------|-------------|
| `PURCHASE` | Automatically applied at checkout when user purchases a subscription. Discount is reflected in order amount. |
| `USER_CLAIM` | User explicitly claims via `POST /billing/promotions/{campaignKey}/redeem`. Grants FREE_PREMIUM immediately. |
| `AUTO_ON_SIGNUP` | Automatically granted when a new user account is created. No user or frontend action needed. |

### Eligibility Types

| Value | Description |
|-------|-------------|
| `ANY_ELIGIBLE_USER` | Any authenticated user in the matching country. |
| `NEW_USER` | User account created within `newUserWindowDays` days. |
| `NEVER_SUBSCRIBED` | User has never had any subscription (past or present). |
| `NO_ACTIVE_SUBSCRIPTION` | User does not currently have an active subscription. |

### Target Gender

| Value | Description |
|-------|-------------|
| `null` | No gender restriction — campaign is available to all eligible users. |
| `MALE` | Only users whose profile gender is `MALE` are eligible. |
| `FEMALE` | Only users whose profile gender is `FEMALE` are eligible. |

> **Note:** Gender is derived from the user's backend profile only. Users without a gender value set on their profile are **not eligible** for gender-targeted campaigns. The frontend cannot override or pass gender — it is always resolved server-side.

### Benefit Types

| Value | Description |
|-------|-------------|
| `DISCOUNT` | Reduces the purchase price. Used with `PURCHASE` trigger. Requires `discountType` and `discountValue`. |
| `FREE_PREMIUM` | Grants a free premium subscription for `durationDays` days. Used with `USER_CLAIM` and `AUTO_ON_SIGNUP` triggers. |

### Discount Types

| Value | Description |
|-------|-------------|
| `PERCENTAGE` | Percentage discount. `discountValue` is in basis points: 10000 = 100%, 2000 = 20%, 500 = 5%. |
| `FIXED` | Fixed amount off. `discountValue` is in minor currency units (e.g., 10000 = 100.00 ETB). `discountCurrency` must match the offer currency or no discount is applied. |

### Campaign Status

| Value | Description | Transitions |
|-------|-------------|-------------|
| `DRAFT` | Created but not visible to users | → `ACTIVE`, → `EXPIRED` |
| `ACTIVE` | Live and visible to eligible users | → `PAUSED`, → `EXPIRED` |
| `PAUSED` | Temporarily hidden | → `ACTIVE`, → `EXPIRED` |
| `EXPIRED` | Permanently ended | (terminal) |

### Redemption Status

| Value | Description |
|-------|-------------|
| `RESERVED` | Capacity reserved, pending fulfillment (order pending or grant in progress) |
| `FULFILLED` | Subscription granted or order completed successfully |
| `CANCELLED` | Redemption cancelled (grant failed, order cancelled/failed) |
| `EXPIRED` | Stale reservation expired by cleanup worker (after 48 hours) |

---

## Error Codes

### User-Facing Errors

| Code | HTTP | Description |
|------|------|-------------|
| `promotion_not_found` | 404 | Campaign key does not exist |
| `promotion_not_eligible` | 403 | Campaign exists but user is not eligible (used by `GET /billing/promotions/{campaignKey}`) |
| `promotion_not_claimable` | 400 | Campaign is not USER_CLAIM trigger |
| `promotion_type_unsupported` | 400 | Campaign benefit is not FREE_PREMIUM |
| `promotion_not_active` | 410 | Campaign status is not ACTIVE |
| `promotion_expired` | 410 | Current time outside campaign date range |
| `promotion_not_eligible` | 422 | User fails eligibility type check (used by redeem endpoint) |
| `promotion_capacity_exhausted` | 409 | All slots reserved/fulfilled or per-user limit reached |
| `user_has_active_subscription` | 409 | User already has active subscription (FREE_PREMIUM grant blocked) |

### Admin Errors

| Code | HTTP | Description |
|------|------|-------------|
| `campaign_not_found` | 404 | Campaign ID does not exist |
| `campaign_key_required` | 400 | Missing campaign key |
| `missing_required_fields` | 400 | Required fields are null |
| `invalid_benefit_type` | 400 | Invalid benefit type for trigger |
| `duration_days_required` | 400 | FREE_PREMIUM requires durationDays > 0 |
| `discount_fields_required` | 400 | DISCOUNT requires discountType and discountValue |
| `new_user_window_days_required` | 400 | NEW_USER requires newUserWindowDays > 0 |
| `ends_at_must_be_after_starts_at` | 400 | endsAt must be after startsAt |
| `invalid_status_transition` | 400 | Illegal status transition for the campaign |
| `invalid_target_gender` | 400 | `targetGender` is not `null`, `"MALE"`, or `"FEMALE"` |

---

## Gender-Targeted Campaigns

Campaigns can optionally target a specific gender by setting `targetGender` to `"MALE"` or `"FEMALE"`. When `targetGender` is `null`, the campaign is available to all eligible users regardless of gender.

### How It Works

1. **Admin creates** a campaign with `targetGender` set to `"MALE"` or `"FEMALE"` (or `null` for unrestricted).
2. **Eligibility is enforced server-side** in the central `PromotionEligibilityService`. This affects all promotion flows:
   - `GET /billing/promotions` — gender-ineligible campaigns are excluded from the list
   - `GET /billing/offers` — gender-ineligible PURCHASE discounts and USER_CLAIM promotions are excluded
   - `AUTO_ON_SIGNUP` — gender-ineligible campaigns are skipped during signup
   - `POST /billing/promotions/{campaignKey}/redeem` — returns `422 promotion_not_eligible` if gender doesn't match
   - `POST /billing/orders` — gender-ineligible PURCHASE discounts are not applied at checkout
   - Checkout revalidation — gender is re-checked at order creation time
3. **Gender is resolved from the backend profile** (`profiles.gender` field). The frontend cannot pass or override gender.
4. **Users without a gender** on their profile are **not eligible** for any gender-targeted campaign (but are eligible for unrestricted campaigns).
5. **Redemptions record `eligibilityGender`** — the user's gender at the time of redemption is stored in `promotion_redemptions.eligibility_gender` for audit purposes.

### Separate Capacity Per Gender

To run separate capacity pools for male and female users (e.g., 100 male spots + 100 female spots), create **two separate campaigns** with the same parameters but different `targetGender` values and different `campaignKey` values:

```
Campaign A: campaignKey="free_trial_male",   targetGender="MALE",   maxRedemptions=100
Campaign B: campaignKey="free_trial_female", targetGender="FEMALE", maxRedemptions=100
```

Each campaign has its own independent `reservedCount` and `fulfilledCount`. The backend automatically routes users to the correct campaign based on their profile gender.

### Frontend Integration

- **No client-side gender filtering needed** — the backend already excludes ineligible campaigns from `GET /billing/promotions` and `GET /billing/offers`.
- The `targetGender` field is included in `EligiblePromotionDto` and `CampaignDto` responses so the frontend can optionally display a gender-specific label (e.g., "For women", "For men") when `targetGender` is non-null.
- If a user attempts to redeem a gender-targeted campaign they're not eligible for, they'll receive a `422 promotion_not_eligible` error.
- The `eligibilityGender` field in `RedemptionDto` (admin endpoint) shows which gender was matched at redemption time.

---

## Background Processes

### Stale Redemption Cleanup

A Quartz-scheduled job (`PromotionRedemptionCleanupWorker`) runs **every hour** and expires `RESERVED` / `PROVIDER_PENDING` redemptions older than **48 hours**. Expired redemptions:
- Status set to `EXPIRED`
- `expired_at` timestamp recorded
- Reserved campaign capacity released

This prevents stale reservations from permanently consuming campaign capacity.

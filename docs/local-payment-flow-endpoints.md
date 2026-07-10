# Local Payment Flow — API Reference

All endpoints are under base path `/api/v1/billing` and require a valid JWT
(user context resolved from the `Authorization: Bearer <token>` header).

Admin endpoints are under `/api/v1/admin/billing` and additionally require an admin role.

---

## Status Lifecycle

| Status | Channel | Meaning |
|---|---|---|
| `CREATED` | All | Order created; gateway checkout failed (will have no `providerCheckoutUrl`) |
| `AWAITING_PAYMENT` | Online | Checkout URL issued; waiting for user to pay on gateway |
| `VERIFICATION_PENDING` | Manual/verify.et | verify.et request queued; poll for result |
| `RECEIPT_SUBMITTED` | Manual/receipt | User uploaded a receipt; waiting for admin approval |
| `REVIEW_REQUIRED` | Manual/verify.et | verify.et confirmed real transaction but bank or amount mismatch; admin must review |
| `MANUAL_REVIEW` | Manual/verify.et | verify.et failed / not found / duplicate reference; admin must review |
| `VERIFIED` | All | Payment confirmed; entitlement fulfilled |
| `REJECTED` | All | Admin rejected or gateway declined |
| `EXPIRED` | All | Order passed expiry time without resolution |
| `CANCELLED` | All | User or system cancelled |

### Flow diagrams

**Online payment (Chapa / ArifPay)**
```
CREATED ──► AWAITING_PAYMENT ──► VERIFIED
                              └─► REJECTED
                              └─► EXPIRED
                              └─► CANCELLED
```

**Manual transfer — verify.et path**
```
                              ┌─► VERIFIED          (bank + amount match)
VERIFICATION_PENDING ─────────┤─► REVIEW_REQUIRED   (transaction real but mismatch)
                              ├─► MANUAL_REVIEW      (verify.et failed / dup ref)
                              └─► REJECTED
```

**Manual transfer — receipt upload path**
```
RECEIPT_SUBMITTED ──► VERIFIED   (admin approves)
                  └─► REJECTED   (admin rejects)
```

---

## 1. Discover Payment Channels

### `GET /api/v1/billing/payment-channels`

Returns the payment channels available to the caller's billing market.
Call this first to let the user choose a channel (e.g. "Online Payment" vs "Bank Transfer").

**Query params**

| Param | Type | Default | Description |
|---|---|---|---|
| `platform` | string | `ANDROID` | Caller's platform: `ANDROID` or `IOS` |

**Response `200`**

```json
{
  "platform": "ANDROID",
  "billingCountryCode": "ET",
  "resolvedMarketCountryCode": "ET",
  "fallbackToGlobal": false,
  "paymentChannels": [
    {
      "code": "ONLINE_PAYMENT",
      "displayName": "Online Payment",
      "activeOnlineMethodCode": "chapa",
      "displayOrder": 1,
      "methodCount": 1
    },
    {
      "code": "MANUAL_TRANSFER",
      "displayName": "Bank / Mobile Transfer",
      "activeOnlineMethodCode": null,
      "displayOrder": 2,
      "methodCount": 4
    }
  ]
}
```

| Field | Description |
|---|---|
| `billingCountryCode` | Country resolved from the user's profile/IP |
| `resolvedMarketCountryCode` | Effective market country (may differ if fallback applied) |
| `fallbackToGlobal` | `true` if the user's country has no market and global market was used |
| `paymentChannels[].code` | Value to pass as `channel` in `GET /payment-options` |
| `paymentChannels[].activeOnlineMethodCode` | For `ONLINE_PAYMENT` channels: the single active gateway code (e.g. `chapa`). Use this to find `paymentMethodId` when creating an order. `null` for manual-transfer channels. |
| `paymentChannels[].methodCount` | Total active methods in the channel |

---

## 2. Discover Payment Methods for a Channel

### `GET /api/v1/billing/payment-options`

Returns the individual payment methods inside a specific channel, along with the
verification fields the frontend must collect from the user.

**Query params**

| Param | Type | Default | Description |
|---|---|---|---|
| `platform` | string | `ANDROID` | `ANDROID` or `IOS` |
| `channel` | string | _(none)_ | Filter by channel code from step 1 (e.g. `MANUAL_TRANSFER`). If omitted, all channels are returned. |

**Response `200`**

```json
{
  "paymentChannel": "MANUAL_TRANSFER",
  "activeOnlineMethodCode": null,
  "platform": "ANDROID",
  "billingCountryCode": "ET",
  "resolvedMarketCountryCode": "ET",
  "fallbackToGlobal": false,
  "paymentMethods": [
    {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "methodCode": "telebirr",
      "displayName": "TeleBirr",
      "paymentChannel": "MANUAL_TRANSFER",
      "paymentMethod": "MOBILE_MONEY",
      "paymentInstructionsHtml": "<p>Send <strong>499.00 ETB</strong> to 0911000000</p>",
      "displayOrder": 1,
      "verificationParams": {
        "fields": [
          { "key": "transactionOrReference", "label": "Transaction Reference", "required": true }
        ]
      }
    }
  ]
}
```

| Field | Description |
|---|---|
| `paymentMethods[].id` | `paymentMethodId` to use in subsequent order creation calls |
| `paymentMethods[].methodCode` | Internal code passed to verify.et |
| `paymentMethods[].verificationParams` | Frontend form definition — render one input per `fields` entry and collect values into `verificationData` |

---

## 3. List Offers

### `GET /api/v1/billing/offers`

Returns the subscription / product offers available for the user's market.

**Query params**

| Param | Type | Default |
|---|---|---|
| `platform` | string | `ANDROID` |

**Response `200`** — array of offer objects

```json
[
  {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "productCode": "premium_monthly",
    "productType": "SUBSCRIPTION",
    "currency": "ETB",
    "priceMinorUnits": 49900,
    "displayPrice": "ETB 499.00",
    "billingIntervalCount": 1,
    "billingIntervalUnit": "MONTH",
    "autoRenew": true,
    "externalProductId": null,
    "revenuecatOfferingId": null,
    "revenuecatPackageId": null,
    "hasAvailablePaymentMethods": true,
    "availablePaymentMethodCount": 5
  }
]
```

`priceMinorUnits` is the authoritative amount (e.g. `49900` = ETB 499.00).
Never send an amount from the frontend — the backend always derives amounts from the offer.

---

## 4A. Create Order — Online Payment (Chapa / ArifPay)

### `POST /api/v1/billing/orders`

Creates an order and obtains a gateway checkout URL to redirect the user to.

**Request body**

```json
{
  "paymentOfferId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "paymentMethodId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "platform": "ANDROID",
  "idempotencyKey": "client-generated-uuid-v4"
}
```

| Field | Required | Description |
|---|---|---|
| `paymentOfferId` | ✅ | ID from `GET /offers` |
| `paymentMethodId` | ✅ | Must match the single active online gateway method for the user's market. Get the ID from `GET /payment-options?channel=ONLINE_PAYMENT`. |
| `platform` | ❌ | Defaults to `ANDROID` |
| `idempotencyKey` | ❌ | Client-generated UUID. If supplied and an order already exists for this key, the existing order is returned instead of creating a duplicate. Safe to retry on network timeout. |

**Response `201`** — [OrderResponse](#orderresponse-object)

```json
{
  "id": "...",
  "status": "AWAITING_PAYMENT",
  "providerCheckoutUrl": "https://checkout.chapa.co/checkout/payment/...",
  ...
}
```

Redirect the user to `providerCheckoutUrl`. The gateway posts a webhook to the
backend when payment completes. Poll `GET /orders/{orderId}` to detect the final status.

> If the gateway call fails at order creation time, the order is created with
> `status: CREATED` and `providerCheckoutUrl: null`. Display an error and allow
> the user to retry.

**Error codes**

| HTTP | Code | Meaning |
|---|---|---|
| 400 | `invalid_offer` | Offer ID not found or not active |
| 400 | `no_active_online_payment_method` | No active gateway configured for the user's market |
| 400 | `invalid_payment_method_for_market` | Submitted `paymentMethodId` does not match the active gateway method for the market |
| 400 | `payment_method_unavailable` | Method is currently disabled |
| 400 | `offer_method_market_mismatch` | Offer and method belong to different markets |

---

## 4B. Create Order — Manual Transfer with verify.et

### `POST /api/v1/billing/manual-transfer/verify`

Creates an order and submits the user's bank / mobile-money transaction reference
to verify.et for automated verification.

**Request body**

```json
{
  "paymentOfferId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "paymentMethodId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "platform": "ANDROID",
  "verificationData": {
    "transactionOrReference": "AABBCC112233"
  },
  "idempotencyKey": "client-generated-uuid-v4"
}
```

| Field | Required | Description |
|---|---|---|
| `paymentOfferId` | ✅ | ID from `GET /offers` |
| `paymentMethodId` | ✅ | ID of the chosen manual-transfer method from `GET /payment-options` |
| `platform` | ❌ | Defaults to `ANDROID` |
| `verificationData` | ✅ | Map of fields collected from the user. Keys are defined per method (see table below). |
| `idempotencyKey` | ❌ | Deduplication key — safe to retry on network timeout |

**`verificationData` key(s) by method code**

| `methodCode` | Key to include |
|---|---|
| `telebirr`, `mpesa` | `transactionOrReference` |
| `cbe` | `referenceNumber` or `receiptNumber` |
| `cbebirr` | `transactionNumber` |
| `boa`, `awash`, `dashen`, `siinqee`, `kaafiebirr` | `referenceNumber` |

Use `verificationParams.fields` from `GET /payment-options` to render the form dynamically.

**Response `201`** — [OrderResponse](#orderresponse-object)

Handle the response `status` field as follows:

| `status` | Frontend action |
|---|---|
| `VERIFICATION_PENDING` | Poll `GET /orders/{orderId}` every `pollAfterMs` ms (= 5000) until status changes |
| `VERIFIED` | Subscription is active — navigate to success screen |
| `REVIEW_REQUIRED` | Transaction was real but a discrepancy was detected (wrong bank or amount). Show "under review" message. `canContactSupport: true` |
| `MANUAL_REVIEW` | Verification failed (not found, system error, or duplicate reference). Show error. `canUploadReceipt: true` — offer the receipt upload path |
| `REJECTED` | Definitively failed. `canContactSupport: true` |

**Error codes** (same as 4A, plus):

| HTTP | Code | Meaning |
|---|---|---|
| 400 | `invalid_payment_method` | Method ID not found |
| 400 | `not_manual_transfer_method` | Supplied method is not a MANUAL_TRANSFER method |

---

## 4C. Create Order — Manual Transfer with Receipt Upload

### `POST /api/v1/billing/manual-transfer/receipt`

Creates an order and records a receipt that the user has already uploaded to cloud
storage. An admin will review and manually approve.

> **Upload first**: the client uploads the receipt image to the configured cloud
> storage bucket **before** calling this endpoint, then passes the resulting
> `bucket` and `path` in this request.

**Request body**

```json
{
  "paymentOfferId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "paymentMethodId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "platform": "ANDROID",
  "receiptStorageBucket": "qaliye-receipts",
  "receiptStoragePath": "users/abc123/receipts/2025-07-08_telebirr.jpg",
  "additionalNotes": {
    "note": "Paid from my wife's phone"
  },
  "idempotencyKey": "client-generated-uuid-v4"
}
```

| Field | Required | Description |
|---|---|---|
| `paymentOfferId` | ✅ | ID from `GET /offers` |
| `paymentMethodId` | ✅ | ID of the chosen manual-transfer method |
| `platform` | ❌ | Defaults to `ANDROID` |
| `receiptStorageBucket` | ✅ | Cloud storage bucket where the receipt was uploaded |
| `receiptStoragePath` | ✅ | Full path/key of the uploaded receipt file |
| `additionalNotes` | ❌ | Free-form key/value map (e.g. a note to admin) |
| `idempotencyKey` | ❌ | Deduplication key |

**Response `201`** — [OrderResponse](#orderresponse-object)

```json
{ "status": "RECEIPT_SUBMITTED", ... }
```

Show the user a "Your receipt is under review" screen. Poll `GET /orders/{orderId}`
to detect when admin approves (`VERIFIED`) or rejects (`REJECTED`).

---

## 5. Get Order

### `GET /api/v1/billing/orders/{orderId}`

Returns full details of a single order. Only accessible by the order owner.

**Path params**

| Param | Description |
|---|---|
| `orderId` | UUID of the order |

**Response `200`** — [OrderResponse](#orderresponse-object)

**Error codes**

| HTTP | Code |
|---|---|
| 404 | `order_not_found` |
| 403 | `access_denied` |

---

## 6. List Orders

### `GET /api/v1/billing/orders`

**Query params**

| Param | Type | Default | Description |
|---|---|---|---|
| `statuses` | string | _(all)_ | Comma-separated status filter, e.g. `VERIFIED,AWAITING_PAYMENT` |
| `page` | int | `1` | Page number (1-based) |
| `pageSize` | int | `20` | Max `100` |

**Response `200`**

```json
{
  "orders": [
    {
      "id": "...",
      "orderReference": "QAL-A1B2C3D4",
      "status": "VERIFIED",
      "productCode": "premium_monthly",
      "productType": "SUBSCRIPTION",
      "displayName": "Qaliye Premium (1 Month)",
      "expectedAmountMinorUnits": 49900,
      "expectedCurrency": "ETB",
      "displayPrice": "ETB 499.00",
      "paymentMethodId": "...",
      "paymentMethodDisplayName": "TeleBirr",
      "paymentChannel": "MANUAL_TRANSFER",
      "paymentMethod": "MOBILE_MONEY",
      "methodCode": "telebirr",
      "expiresAt": "2025-07-10T12:00:00Z",
      "createdAt": "2025-07-08T12:00:00Z",
      "updatedAt": "2025-07-08T13:00:00Z",
      "canResumePayment": false,
      "canSubmitPayment": false,
      "canCreateNewOrder": false
    }
  ],
  "page": 1,
  "pageSize": 20,
  "total": 1,
  "totalPages": 1
}
```

**Action flags on each order summary**

| Flag | `true` when | Suggested frontend use |
|---|---|---|
| `canResumePayment` | `CREATED` or `AWAITING_PAYMENT` | Show "Continue Payment" button |
| `canSubmitPayment` | _(currently always `false`; reserved)_ | — |
| `canCreateNewOrder` | `REJECTED`, `EXPIRED`, or `CANCELLED` | Show "Try Again" button |

---

## OrderResponse Object

Returned by `POST /orders`, `POST /manual-transfer/verify`,
`POST /manual-transfer/receipt`, and `GET /orders/{orderId}`.

```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "orderReference": "QAL-A1B2C3D4",
  "status": "VERIFICATION_PENDING",
  "paymentOfferId": "...",
  "expectedAmountMinorUnits": 49900,
  "expectedCurrency": "ETB",
  "paymentMethodId": "...",
  "paymentChannel": "MANUAL_TRANSFER",
  "paymentMethod": "MOBILE_MONEY",
  "methodCode": "telebirr",
  "paymentMethodDisplayName": "TeleBirr",
  "providerCheckoutUrl": null,
  "paymentInstructions": {
    "paymentChannel": "MANUAL_TRANSFER",
    "methodCode": "telebirr",
    "displayName": "TeleBirr",
    "instructionText": "Send ETB 499.00 to 0911000000 before 2025-07-10 12:00 UTC"
  },
  "expiresAt": "2025-07-10T12:00:00Z",
  "createdAt": "2025-07-08T12:00:00Z",
  "updatedAt": "2025-07-08T12:00:00Z",
  "verifyEtRequestId": "verifyEt-req-abc123",
  "pollAfterMs": 5000,
  "canRetryVerification": false,
  "canUploadReceipt": false,
  "canContactSupport": false
}
```

| Field | Type | Description |
|---|---|---|
| `id` | UUID | Order ID |
| `orderReference` | string | Human-readable reference (format: `QAL-XXXXXXXX`) |
| `status` | string | Current order status — see [Status Lifecycle](#status-lifecycle) |
| `paymentOfferId` | UUID | The offer this order is for |
| `expectedAmountMinorUnits` | int | Amount in minor units (divide by 100 for display) |
| `expectedCurrency` | string | ISO 4217 currency code, e.g. `ETB` |
| `providerCheckoutUrl` | string\|null | Only set for `AWAITING_PAYMENT` online orders. Redirect the user here. |
| `paymentInstructions` | object\|null | Rendered instruction snapshot. Present for manual transfer orders. Display `instructionText` to the user. |
| `expiresAt` | ISO-8601 | When this order expires |
| `verifyEtRequestId` | string\|null | Set when `status` is `VERIFICATION_PENDING`, `REVIEW_REQUIRED`, or `MANUAL_REVIEW`. For support reference only. |
| `pollAfterMs` | long\|null | `5000` when `status = VERIFICATION_PENDING`. Poll `GET /orders/{id}` after this many ms. `null` otherwise. |
| `canRetryVerification` | bool | Reserved; always `false` |
| `canUploadReceipt` | bool | `true` when `status = MANUAL_REVIEW`. Offer the user to submit a receipt instead. |
| `canContactSupport` | bool | `true` when `status = REJECTED` or `REVIEW_REQUIRED`. Show contact-support CTA. |

---

## Recommended Frontend Integration Flow

```
1. GET /payment-channels?platform=ANDROID
      → present channels to user (Online / Bank Transfer / etc.)

2. GET /payment-options?platform=ANDROID&channel=<chosen>
      → present individual methods; build form from verificationParams.fields

3. GET /offers?platform=ANDROID
      → let user pick offer (product + price)

── Branch A: Online payment ────────────────────────────────────────────────────
4a. POST /orders  { paymentOfferId, paymentMethodId, platform, idempotencyKey }
      → status AWAITING_PAYMENT → redirect to providerCheckoutUrl
5a. Poll GET /orders/{id} every 3–5 s until status ≠ AWAITING_PAYMENT

── Branch B: Manual transfer / verify.et ───────────────────────────────────────
4b. POST /manual-transfer/verify  { paymentOfferId, paymentMethodId, platform,
                                    verificationData, idempotencyKey }
      → VERIFICATION_PENDING → poll GET /orders/{id} every pollAfterMs (5000 ms)
      → VERIFIED             → success screen
      → REVIEW_REQUIRED      → "Payment under review" + contact support CTA
      → MANUAL_REVIEW        → show error + "Upload Receipt" button → go to 4c

── Branch C: Manual transfer / receipt ─────────────────────────────────────────
4c. Upload receipt image to storage → get bucket + path
    POST /manual-transfer/receipt  { paymentOfferId, paymentMethodId, platform,
                                     receiptStorageBucket, receiptStoragePath,
                                     additionalNotes, idempotencyKey }
      → RECEIPT_SUBMITTED → "Receipt under review" screen
5c. Poll GET /orders/{id} every 10–30 s until VERIFIED or REJECTED
```

---

## Admin Endpoints

All require an admin-role JWT.

### `GET /api/v1/admin/billing/orders`

Lists orders pending admin review.

**Query params**

| Param | Type | Default | Description |
|---|---|---|---|
| `status` | string | `MANUAL_REVIEW,REVIEW_REQUIRED,RECEIPT_SUBMITTED` | Comma-separated status filter |
| `methodCode` | string | _(none)_ | Filter by payment method code (e.g. `telebirr`) |
| `countryCode` | string | _(none)_ | Filter by country code (e.g. `ET`) |
| `page` | int | `1` | — |
| `pageSize` | int | `20` | — |

---

### `GET /api/v1/admin/billing/orders/{orderId}`

Returns full order details including verification attempts and proof records.

---

### `POST /api/v1/admin/billing/orders/{orderId}/approve`

Approves an order in `MANUAL_REVIEW`, `REVIEW_REQUIRED`, `RECEIPT_SUBMITTED`, or
`VERIFICATION_PENDING`. Sets status to `VERIFIED` and triggers fulfillment immediately.

**Request body** _(optional)_

```json
{ "decisionNote": "Verified manually — receipt matched." }
```

**Response `200`**

```json
{ "status": "VERIFIED", "orderId": "..." }
```

---

### `POST /api/v1/admin/billing/orders/{orderId}/reject`

Rejects an order in any reviewable status. Sets status to `REJECTED`.

**Request body** _(optional)_

```json
{ "decisionNote": "Amount does not match. Reference number invalid." }
```

**Response `200`**

```json
{ "status": "REJECTED", "orderId": "..." }
```

**Error codes (approve & reject)**

| HTTP | Code | Meaning |
|---|---|---|
| 404 | `order_not_found` | — |
| 400 | `order_not_reviewable` | Order is in a terminal or non-reviewable status |
| 403 | `admin_role_required` | Caller is not an admin |

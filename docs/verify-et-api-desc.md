# Manual Transfer Payment Verification — Frontend Integration Guide

This document describes the full API flow that a frontend (Android, iOS, or Web) must implement to support **MANUAL_TRANSFER** payment verification using the Verify.et backend integration.

---

## Base URL

```
https://api.qaliye.app/api/v1
```

All endpoints require a valid `Authorization: Bearer <jwt>` header unless stated otherwise.

---

## High-Level Flow

```
1.  GET  /billing/offers                                   → list available offers
2.  GET  /billing/payment-channels?platform=               → list available payment channels
3.  User selects a channel (MANUAL_TRANSFER or ONLINE_PAYMENT)
4.  GET  /billing/payment-options?platform=&channel=       → list payment methods for that channel
5.  User selects a payment method
6.  Display payment instructions to the user
7.  Render a form based on verificationParams
8.  User pays at their bank / app
9.  User fills the form and submits
10. POST /billing/orders/verify                             → create order + submit to verify.et in one call
11. Poll GET /billing/orders/{orderId}                      → poll until a terminal status is reached
```

> **Key change:** For `MANUAL_TRANSFER`, there is no need to create an order first. The user transfers money at their bank/app, then submits the verification details in a single `POST /billing/orders/verify` call. The backend creates the order and submits to Verify.et atomically.
>
> For `ONLINE_PAYMENT` (e.g. Chapa), use `POST /billing/orders` to create an order and redirect to `providerCheckoutUrl`.

---

## Step 1 — Get Available Offers

### `GET /billing/offers?platform={platform}`

Returns the subscription / consumable products that can be purchased.

#### Query Parameters

| Parameter  | Type   | Required | Description                            |
|------------|--------|----------|----------------------------------------|
| `platform` | string | No       | `ANDROID` (default), `IOS`, or `WEB`  |

#### Response `200 OK`

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

#### Key Fields

| Field                        | Description                                                     |
|------------------------------|-----------------------------------------------------------------|
| `id`                         | UUID to pass as `paymentOfferId` when creating an order         |
| `priceMinorUnits`            | Price in the smallest currency unit (e.g. ETB centimes/santim) |
| `displayPrice`               | Human-readable price string                                     |
| `hasAvailablePaymentMethods` | `false` means no payment methods exist for this offer           |

---

## Step 2 — Get Payment Channels

### `GET /billing/payment-channels?platform={platform}`

Returns the distinct payment channels available for the authenticated user's billing country and platform. Use this to build the channel selection UI (e.g. tabs or a radio list).

#### Query Parameters

| Parameter  | Type   | Required | Description                            |
|------------|--------|----------|----------------------------------------|
| `platform` | string | No       | `ANDROID` (default), `IOS`, or `WEB`  |

#### Response `200 OK`

```json
[
  {
    "channel": "MANUAL_TRANSFER",
    "displayName": "Bank / Mobile Transfer"
  },
  {
    "channel": "ONLINE_PAYMENT",
    "displayName": "Pay Online"
  }
]
```

| Field         | Description                                                          |
|---------------|----------------------------------------------------------------------|
| `channel`     | Channel identifier — pass as `channel` query param in Step 3        |
| `displayName` | Human-readable label to display as a tab or section heading          |

> Only channels that have at least one active payment method for the user's market are returned. The array may contain only one element if, for example, only `MANUAL_TRANSFER` is configured for that country.

---

## Step 3 — Get Payment Methods for a Channel

### `GET /billing/payment-options?platform={platform}&channel={channel}`

Returns payment methods for a specific channel in the user's billing market. Call this after the user selects a channel from Step 2.

#### Query Parameters

| Parameter  | Type   | Required | Description                                                          |
|------------|--------|----------|----------------------------------------------------------------------|
| `platform` | string | No       | `ANDROID` (default), `IOS`, or `WEB`                                |
| `channel`  | string | No       | `MANUAL_TRANSFER` or `ONLINE_PAYMENT`. Omit to get all methods       |

#### Response `200 OK`

```json
{
  "platform": "ANDROID",
  "billingCountryCode": "ETH",
  "resolvedMarketCountryCode": "ETH",
  "fallbackToGlobal": false,
  "paymentMethods": [
    {
      "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "methodCode": "cbe",
      "displayName": "CBE (Commercial Bank of Ethiopia)",
      "paymentChannel": "MANUAL_TRANSFER",
      "paymentMethod": "BANK_TRANSFER",
      "paymentInstructions": "Transfer to Account: 1000123456789\nAccount Name: Qaliye Technologies\nBank: Commercial Bank of Ethiopia",
      "displayOrder": 1,
      "verificationParams": {
        "fields": [
          {
            "name": "receiptNumber",
            "label": "Receipt Number",
            "type": "text",
            "required": true,
            "hint": "Found on your CBE transaction receipt"
          }
        ]
      }
    },
    {
      "id": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
      "methodCode": "telebirr",
      "displayName": "Telebirr",
      "paymentChannel": "MANUAL_TRANSFER",
      "paymentMethod": "MOBILE_MONEY",
      "paymentInstructions": "Send to Telebirr number: 0911234567\nAccount Name: Qaliye Technologies",
      "displayOrder": 2,
      "verificationParams": {
        "fields": [
          {
            "name": "reference",
            "label": "Transaction Reference",
            "type": "text",
            "required": true,
            "hint": "The reference number from your Telebirr transaction"
          }
        ]
      }
    }
  ]
}
```

#### Payment Method Fields

| Field                       | Description                                                              |
|-----------------------------|--------------------------------------------------------------------------|
| `id`                        | UUID to pass as `paymentMethodId` when creating an order                 |
| `methodCode`                | Identifies the bank/wallet (see bank field reference below)              |
| `paymentChannel`            | `MANUAL_TRANSFER` or `ONLINE_PAYMENT`                                    |
| `paymentInstructions`       | Plain text — **display to user before they pay** (`null` for ONLINE_PAYMENT) |
| `verificationParams.fields` | Dynamic form descriptors — **use to build the verification input form**  |

#### `verificationParams.fields` — Field Descriptor Shape

Each element in the `fields` array describes one input the user must fill:

| Property   | Type    | Description                                      |
|------------|---------|--------------------------------------------------|
| `name`     | string  | Key name to use in `verificationFields` map      |
| `label`    | string  | Display label for the input                      |
| `type`     | string  | Input type: `text`, `tel`, or `number`           |
| `required` | boolean | Whether the field is mandatory                   |
| `hint`     | string  | Helper text to show below the input              |

#### Bank Field Reference

The following shows the fields each bank expects. These will be reflected in `verificationParams.fields`. The frontend must forward exactly these keys inside `verificationFields` when submitting.

| `methodCode` | Fields Required                     | Notes                                               |
|--------------|-------------------------------------|-----------------------------------------------------|
| `cbe`        | `receiptNumber`                     | 10–20 digit receipt number from CBE slip/app        |
| `telebirr`   | `reference`                         | Transaction reference or confirmation code          |
| `cbebirr`    | `phoneNumber`, `reference`          | Sender's phone number and the reference number      |
| `mpesa`      | `reference`                         | M-Pesa transaction code                             |
| `boa`        | `reference`                         | Bank of Abyssinia reference number                  |
| `awash`      | `reference`                         | Awash Bank reference number                         |
| `dashen`     | `reference`                         | Dashen Bank reference number                        |
| `siinqee`    | `reference`                         | Siinqee Bank reference number                       |
| `kaafiebirr` | `reference`                         | KaafiBirr transaction reference                     |

---

## Step 4 — Display Payment Instructions

Before showing the verification form, render the `paymentInstructions` from the payment method to the user. This contains the destination account number, account name, and bank/wallet details that the user must pay to.

The user should complete the actual bank transfer before returning to fill out the form.

> **Important:** For `MANUAL_TRANSFER`, do **not** call `POST /billing/orders` to create an order first. The order is created automatically when the user submits their verification details via `POST /billing/orders/verify` (Step 5).

---

## Step 5 — Verify Manual Payment (Create Order + Submit to Verify.et)

### `POST /billing/orders/verify`

Creates a payment order and submits the user's verification details to Verify.et in a single atomic call. Use this endpoint only for `MANUAL_TRANSFER` payment methods.

#### Request Body

```json
{
  "paymentOfferId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "paymentMethodId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "verificationFields": {
    "receiptNumber": "FT26190123456"
  },
  "submittedAmountMinorUnits": 49900,
  "submittedCurrency": "ETB",
  "idempotencyKey": "550e8400-e29b-41d4-a716-446655440000"
}
```

| Field                       | Type         | Required | Description                                                                              |
|-----------------------------|--------------|----------|------------------------------------------------------------------------------------------|
| `paymentOfferId`            | UUID         | Yes      | The offer the user selected (from Step 1)                                                |
| `paymentMethodId`           | UUID         | Yes      | The MANUAL_TRANSFER payment method the user selected (from Step 3)                       |
| `verificationFields`        | object (map) | Yes      | Key-value map of form inputs; keys must match `name` in `verificationParams.fields`      |
| `submittedAmountMinorUnits` | integer      | Yes      | The amount paid — **must exactly equal the offer's `priceMinorUnits`**                   |
| `submittedCurrency`         | string       | Yes      | The currency paid — **must exactly equal the offer's `currency`**                        |
| `idempotencyKey`            | string       | No       | Unique string to prevent duplicate orders on retry — recommended (use UUID)              |

#### `verificationFields` Examples per Bank

**CBE:**
```json
{ "receiptNumber": "FT26190123456" }
```

**Telebirr:**
```json
{ "reference": "TELE12345678" }
```

**CBEBirr:**
```json
{ "phoneNumber": "0911234567", "reference": "CBEB98765432" }
```

**M-Pesa:**
```json
{ "reference": "MPESA1234ABCDEF" }
```

**BOA / Awash / Dashen / Siinqee / KaafiBirr:**
```json
{ "reference": "TXN20260708XYZ123" }
```

#### Response `201 Created`

Returns the created order object. Check the `status` field immediately:

```json
{
  "id": "c3d4e5f6-0000-0000-0000-000000000001",
  "orderReference": "QAL-ABCD1234",
  "status": "VERIFICATION_PENDING",
  "expectedAmountMinorUnits": 49900,
  "expectedCurrency": "ETB",
  "paymentMethodId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "paymentChannel": "MANUAL_TRANSFER",
  "paymentMethod": "BANK_TRANSFER",
  "methodCode": "cbe",
  "paymentMethodDisplayName": "CBE (Commercial Bank of Ethiopia)",
  "providerCheckoutUrl": null,
  "paymentInstructions": {
    "instructionText": "Transfer to Account: 1000123456789\nAccount Name: Qaliye Technologies\nBank: Commercial Bank of Ethiopia"
  },
  "expiresAt": "2026-07-09T00:00:00Z",
  "createdAt": "2026-07-08T00:00:00Z"
}
```

If verification completed inline (synchronously), `status` may already be `VERIFIED`, `REJECTED`, or `MANUAL_REVIEW` in this response — no polling would be needed in that case.

#### Error Responses

| HTTP Status | Body `message`                 | Cause                                                               |
|-------------|--------------------------------|---------------------------------------------------------------------|
| `400`       | `invalid_offer`                | No offer found with the given `paymentOfferId`                      |
| `400`       | `invalid_payment_method`       | No payment method found with the given `paymentMethodId`            |
| `400`       | `payment_method_unavailable`   | Payment method is not active                                        |
| `400`       | `not_manual_transfer_method`   | Payment method is not a MANUAL_TRANSFER method                      |
| `400`       | `offer_method_market_mismatch` | Offer and payment method belong to different markets                |
| `400`       | `amount_mismatch`              | `submittedAmountMinorUnits` ≠ offer's `priceMinorUnits`             |
| `400`       | `currency_mismatch`            | `submittedCurrency` ≠ offer's `currency`                            |

---

## Step 6 — Poll for Order Status

### `GET /billing/orders/{orderId}`

Retrieves the latest state of the order. Poll this until a terminal status is reached.

#### Path Parameters

| Parameter | Type | Description             |
|-----------|------|-------------------------|
| `orderId` | UUID | The `id` of the order   |

#### Response `200 OK`

Same shape as the `POST /billing/orders` response. Watch the `status` field.

#### Error Responses

| HTTP Status | Body `message` | Cause                                    |
|-------------|----------------|------------------------------------------|
| `403`       | `access_denied`  | Order belongs to a different user      |
| `404`       | `order_not_found` | No order found with this `orderId`    |

---

## Order Status Reference

| Status                 | Meaning                                                                                   | Frontend Action                                                              |
|------------------------|-------------------------------------------------------------------------------------------|------------------------------------------------------------------------------|
| `AWAITING_PAYMENT`     | Order created; user has not submitted payment details yet                                 | Show instructions and verification form                                      |
| `VERIFICATION_PENDING` | Details submitted; Verify.et has queued the request for background processing             | Show "Verifying…" spinner; continue polling                                  |
| `VERIFIED`             | Payment confirmed; entitlements have been granted automatically                           | Show success screen; call `GET /billing/entitlements` to refresh features    |
| `MANUAL_REVIEW`        | Automatic verification inconclusive; waiting for admin to manually review                 | Show "Under review" message; poll every 30s; no user action required         |
| `ADMIN_REVIEW`         | Possible duplicate transaction or settlement account mismatch; admin investigating        | Show "Under review" message; inform user to contact support if urgent        |
| `REJECTED`             | Payment reference not found or verification definitively failed                           | Show failure state; offer user the option to create a new order              |
| `EXPIRED`              | Order passed its `expiresAt` timestamp without being paid                                 | Show expiry message; guide user to start a new order                         |
| `CANCELLED`            | Order was cancelled (by admin or system)                                                  | Redirect user back to payment method selection                               |

### Terminal Statuses — Stop Polling

Stop all polling once `status` is one of:

```
VERIFIED  |  REJECTED  |  EXPIRED  |  CANCELLED
```

For `MANUAL_REVIEW` and `ADMIN_REVIEW`, continue background polling at a lower frequency (every 30 seconds) since an admin decision may arrive later.

### Recommended Polling Strategy

```
Immediately after /verify returns VERIFICATION_PENDING:
  → Poll every 3 seconds for the first 30 seconds
  → Then every 10 seconds for the next 5 minutes
  → Then every 30 seconds until terminal status or order expiry
```

---

## Step 7 — Refresh Entitlements After Verification

Once `status` is `VERIFIED`, call:

### `GET /billing/entitlements`

Returns the user's active entitlements.

```json
{
  "subscriptions": [...],
  "consumables": [...],
  "boosts": [...]
}
```

Use this to unlock premium features in the UI.

---

## Full Example: CBE Bank Transfer

### 1. Fetch offers

```http
GET /billing/offers?platform=ANDROID
Authorization: Bearer <token>
```

### 2. Fetch payment channels

```http
GET /billing/payment-channels?platform=ANDROID
Authorization: Bearer <token>
```

**Response** → `[{"channel": "MANUAL_TRANSFER", "displayName": "Bank / Mobile Transfer"}, ...]`

### 3. User selects MANUAL_TRANSFER → Fetch payment methods for that channel

```http
GET /billing/payment-options?platform=ANDROID&channel=MANUAL_TRANSFER
Authorization: Bearer <token>
```

**Response** → list of bank/mobile-money methods; user selects CBE.

### 4. Show payment instructions to user

```
Transfer to Account: 1000123456789
Account Name: Qaliye Technologies
Bank: Commercial Bank of Ethiopia
Amount: ETB 499.00
```

### 5. Render the form from `verificationParams.fields`

Show one input labelled **"Receipt Number"**, `type="text"`.

### 6. User pays ETB 499 at CBE, copies their receipt number, submits

```http
POST /billing/orders/verify
Authorization: Bearer <token>
Content-Type: application/json

{
  "paymentOfferId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "paymentMethodId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "verificationFields": {
    "receiptNumber": "FT26190123456"
  },
  "submittedAmountMinorUnits": 49900,
  "submittedCurrency": "ETB",
  "idempotencyKey": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response** → `status: "VERIFICATION_PENDING"`, `id: "c3d4e5f6-0000-0000-0000-000000000001"`

### 7. Poll for status

```http
GET /billing/orders/c3d4e5f6-0000-0000-0000-000000000001
Authorization: Bearer <token>
```

Repeat every few seconds until `status` is terminal.

### 8. On `VERIFIED` — refresh entitlements

```http
GET /billing/entitlements
Authorization: Bearer <token>
```

---

## Important Notes for Frontend Developers

- **Do not let the user edit the amount.** The `submittedAmountMinorUnits` must match the offer's `priceMinorUnits` exactly. Prefill it from the selected offer and disable the field.
- **Build forms dynamically.** Always use `verificationParams.fields` to render inputs. Do not hardcode field names per bank in your client code.
- **Use idempotency keys.** Always pass a stable `idempotencyKey` when submitting verification to safely retry failed network requests without creating duplicate orders.
- **Show instructions first.** The user must know where to send money before they see the form. Display `paymentInstructions` from the payment method prominently and ensure the user has paid before submitting.
- **No separate order creation for MANUAL_TRANSFER.** The `POST /billing/orders/verify` endpoint creates the order and submits to Verify.et in one call. Do not call `POST /billing/orders` first.
- **Handle order expiry.** Display a countdown from `expiresAt`. Warn the user 5 minutes before expiry. If the order expires, do not resubmit — start a new verification.
- **Manual/Admin review is normal.** If `status` becomes `MANUAL_REVIEW` or `ADMIN_REVIEW`, reassure the user their payment is being reviewed and they will gain access once confirmed. Do not ask them to re-submit.
- **No direct Verify.et calls.** The frontend has no interaction with Verify.et. All verification is server-to-server. The frontend only communicates with the Qaliye backend.
- **ONLINE_PAYMENT is separate.** This flow is only for `MANUAL_TRANSFER`. For `ONLINE_PAYMENT` methods (e.g. Chapa), use `POST /billing/orders` to create an order and redirect to `providerCheckoutUrl` — that flow is not described here.

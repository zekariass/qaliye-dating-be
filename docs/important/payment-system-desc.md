# Qaliye Payment System — Architecture & Configuration Guide

This document describes the full payment system: the data model, every payment scenario, how configuration maps to runtime behaviour, and how each component interacts.

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Data Model](#2-data-model)
3. [Market Resolution](#3-market-resolution)
4. [Configuration Reference](#4-configuration-reference)
5. [Payment Channels & Methods](#5-payment-channels--methods)
6. [Scenario: Online Payment (Chapa)](#6-scenario-online-payment-chapa)
7. [Scenario: Manual Bank Transfer with verify.et](#7-scenario-manual-bank-transfer-with-verifyet)
8. [Scenario: Manual Receipt Upload](#8-scenario-manual-receipt-upload)
9. [Scenario: In-App Purchase via RevenueCat](#9-scenario-in-app-purchase-via-revenuecat)
10. [Scenario: Admin Review](#10-scenario-admin-review)
11. [Order State Machine](#11-order-state-machine)
12. [Fulfillment](#12-fulfillment)
13. [Entitlements & Limits](#13-entitlements--limits)
14. [Promotions](#14-promotions)
15. [Webhook Endpoints](#15-webhook-endpoints)
16. [Environment Variables Checklist](#16-environment-variables-checklist)

---

## 1. System Overview

Qaliye supports three independent payment pathways, each with its own flow, verification method, and configuration:

| Pathway | Payment Channel | Verification | Config Prefix |
|---|---|---|---|
| Online payment gateway | `ONLINE_PAYMENT` | Gateway webhook (e.g. Chapa callback) | `billing.chapa.*`, `billing.arif-pay.*` |
| Manual bank/mobile transfer | `MANUAL_TRANSFER` | verify.et API + webhook, or admin review | `billing.verifier.*` |
| In-app purchase (iOS/Android) | N/A (RevenueCat) | RevenueCat webhook | `billing.revenuecat.*`, `revenuecat.*` |

All three pathways converge on the same fulfillment pipeline: `FulfillmentService.fulfillVerifiedOrder()` or `FulfillmentService.fulfillRevenueCatSubscription()`.

### Key Services

- **`OfferService`** — resolves available offers and payment methods for a user's market
- **`OrderService`** — creates orders, submits manual transfers, submits receipts
- **`BillingMarketResolver`** — resolves the user's billing country and platform market
- **`LocalGatewayRegistry`** — resolves the correct online gateway by method code
- **`VerifyEtClient`** — submits and polls verify.et bank transfer verifications
- **`VerifyEtWebhookHandler`** — processes async verify.et webhook callbacks
- **`ChapaWebhookHandler`** — processes Chapa payment success/failure webhooks
- **`RevenueCatWebhookHandler`** — processes RevenueCat subscription events
- **`FulfillmentService`** — grants subscriptions, credits, and boost allowances
- **`EntitlementService`** — resolves user's current plan, quotas, and credits
- **`AdminBillingService`** — admin approve/reject of manual review orders

---

## 2. Data Model

### Entity Chain

```
subscription_plans  (plan kind, features, limits, country_code for entitlement scoping)
    ↓ 1:N
subscription_products  (billing intervals: monthly, 3-month, 6-month)
    ↓ 1:N
payment_offers  (country-specific pricing: ET/ETB, GLOBAL/USD, platform-specific)
    ↓ 1:1 at order time
payment_orders  (the actual purchase order with status lifecycle)
    ↓ 1:N
payment_verification_attempts  (verify.et results, admin review records)
payment_proofs  (transaction references, receipt uploads)
```

### Key Tables

- **`subscription_plans`** — defines plan tiers (FREE, PREMIUM). `country_code` here scopes **entitlements** (e.g. country-specific free plan limits). `currency` and `price_minor_units` are legacy from the old design and **not used** by the new billing code.
- **`subscription_products`** — billing interval variants (monthly, quarterly, etc.). Links to `subscription_plans` via `plan_id`.
- **`payment_offers`** — country + platform + channel specific pricing. `country_code` here scopes **commerce** (what price and payment methods the user sees). Links to `subscription_products` or `consumable_products`.
- **`payment_methods`** — available payment methods per country + platform. Each has a `payment_channel` (`ONLINE_PAYMENT` or `MANUAL_TRANSFER`), a `method_code` (e.g. `chapa`, `cbe`, `telebirr`), and optional `payment_instructions` template.
- **`payment_orders`** — the order record with status, expected amount, instruction snapshot, etc.
- **`payment_verification_attempts`** — tracks each verification attempt (verify.et, admin review).
- **`payment_proofs`** — stores transaction references or receipt upload metadata.
- **`payment_events`** — idempotent webhook event log (prevents duplicate processing).

### Why `country_code` Exists on Both `subscription_plans` and `payment_offers`

They serve **different layers**:

- `payment_offers.country_code` → **commerce layer**: what price does the user pay? what payment methods are available? (e.g. ET/ETB 14,900 vs GLOBAL/USD 7.99)
- `subscription_plans.country_code` → **entitlement layer**: what limits does the user get? (e.g. FREE plan with country-specific daily like limits)

Nothing enforces they match through the chain — and they intentionally don't need to. An ET user pays via an `ET` offer but may receive `GLOBAL` plan entitlements.

---

## 3. Market Resolution

`BillingMarketResolver` determines which country's offers and payment methods to show a user.

### Billing Country Resolution (priority order)

1. `app_users.billing_country_code` — admin-set, trusted (e.g. manually set to `ET`)
2. `addresses.country_code` — from user's primary address
3. `'GLOBAL'` — safe fallback

### Market Resolution (two modes)

**`resolveMarket()`** — used for **order creation** and **offer listing**:
- Checks if the user's billing country has **both** active offers AND active payment methods for the platform
- If yes → uses that country
- If no → falls back to `GLOBAL`

**`resolveMethodsMarket()`** — used for **payment method discovery** (channels, options):
- Only checks if active payment methods exist for the country/platform
- Does NOT require offers to exist
- Falls back to `GLOBAL` if no methods found

### Promotion Country Resolution

`resolvePromotionCountry()` returns `"ET"` if the user's billing country is ET, otherwise `"GLOBAL"`. This ensures ET users see only ET campaigns and non-ET users see only GLOBAL campaigns.

---

## 4. Configuration Reference

All payment config lives under the `billing.*` prefix in `application.yml`, with env-var overrides.

### General Billing

```yaml
billing:
  boost-duration-minutes: ${BILLING_BOOST_DURATION_MINUTES:3}
  payment-order-expiry-hours: ${BILLING_ORDER_EXPIRY_HOURS:72}
  receipt-signed-url-ttl-seconds: ${BILLING_RECEIPT_URL_TTL:300}
  manual-transfer-max-age-hours: ${BILLING_MANUAL_TRANSFER_MAX_AGE_HOURS:48000}
```

- **`boost-duration-minutes`** — how long a profile boost lasts when activated
- **`payment-order-expiry-hours`** — orders expire after this many hours if not verified (default 72h)
- **`receipt-signed-url-ttl-seconds`** — TTL for admin receipt download URLs (default 300s)
- **`manual-transfer-max-age-hours`** — reject bank transfers older than this (default ~20 months; set to 48 for production)

### Chapa (Online Payment Gateway)

```yaml
billing:
  chapa:
    secret-key: ${CHAPA_SECRET_KEY:}
    webhook-secret: ${CHAPA_WEBHOOK_SECRET:}
    base-url: ${CHAPA_BASE_URL:https://api.chapa.co/v1}
    callback-url: ${CHAPA_CALLBACK_URL:}
```

- **`secret-key`** — Chapa API secret. If blank, `ChapaClient.isConfigured()` returns false and orders with `chapa` method will fail with `payment_provider_not_configured`.
- **`webhook-secret`** — for verifying Chapa webhook signatures (currently not enforced in handler)
- **`callback-url`** — URL Chapa redirects the user's browser to after payment; also used as webhook if configured

### ArifPay (Online Payment Gateway — scaffold)

```yaml
billing:
  arif-pay:
    secret-key: ${ARIFPAY_SECRET_KEY:}
    webhook-secret: ${ARIFPAY_WEBHOOK_SECRET:}
    base-url: ${ARIFPAY_BASE_URL:https://gateway.arifpay.net}
    callback-url: ${ARIFPAY_CALLBACK_URL:}
```

ArifPay gateway client exists but throws `UnsupportedOperationException`. Not yet operational.

### verify.et (Bank Transfer Verification)

```yaml
billing:
  verifier:
    provider: VERIFY_ET
    api-key: ${VERIFY_ET_API_KEY:}
    base-url: ${VERIFY_ET_BASE_URL:https://verify.et}
    webhook-url: ${VERIFY_ET_WEBHOOK_URL:}
    webhook-secret: ${VERIFY_ET_API_KEY:}
```

- **`api-key`** — sent as `x-api-key` header to verify.et. Required for all verify.et API calls.
- **`base-url`** — verify.et API base URL
- **`webhook-url`** — your backend's public webhook URL for verify.et async callbacks. Must be reachable by verify.et (use ngrok for dev). Sent in the `x-webhook-url` header and in the payload `webhookUrl` field.
- **`webhook-secret`** — used for HMAC-SHA256 signature validation of incoming webhooks. If blank, signature validation is **skipped** (not recommended for production).

### RevenueCat (In-App Purchase)

```yaml
billing:
  revenuecat:
    webhook-authorization-token: ${REVENUECAT_WEBHOOK_AUTH_TOKEN:}
    api-key: ${REVENUECAT_API_KEY:}
    api-base-url: ${REVENUECAT_API_BASE_URL:https://api.revenuecat.com/v1}

revenuecat:
  webhook-secret: ${REVENUECAT_WEBHOOK_SECRET:}
  webhook-authorization: ${REVENUECAT_WEBHOOK_AUTHORIZATION:}
```

- **`webhook-authorization-token`** — expected value of the `Authorization` header on incoming RevenueCat webhooks
- **`api-key`** — RevenueCat REST API key (for server-side subscriber management)
- **`webhook-secret`** — top-level config, used for additional webhook validation if needed

### Payment Instructions (Manual Transfer Display)

```yaml
billing:
  payment-instructions:
    bank-name: ${PAYMENT_BANK_NAME:Commercial Bank of Ethiopia}
    account-number: ${PAYMENT_ACCOUNT_NUMBER:}
    account-name: ${PAYMENT_ACCOUNT_NAME:Qaliye Technologies}
    telebirr-short-code: ${PAYMENT_TELEBIRR_SHORT_CODE:}
```

These values are substituted into `payment_methods.payment_instructions` templates using placeholders:
- `{{EXPECTED_AMOUNT}}`, `{{CURRENCY}}`, `{{ORDER_REFERENCE}}`, `{{ORDER_EXPIRY}}`
- `{{PAYMENT_ACCOUNT_NAME}}`, `{{PAYMENT_ACCOUNT_NUMBER}}`

---

## 5. Payment Channels & Methods

### Channel Types

| Channel | Description | Methods |
|---|---|---|
| `ONLINE_PAYMENT` | Redirects user to gateway checkout page | `chapa` (configured), `arifpay` (scaffold) |
| `MANUAL_TRANSFER` | User transfers to bank/mobile account, then submits reference for verification | `cbe`, `telebirr`, `cbebirr`, `mpesa`, `boa`, `awash`, `dashen`, `siinqee`, `kaafiebirr` |

### API Endpoints for Discovery

1. **`GET /api/v1/billing/payment-channels?platform=ANDROID`** — returns available channels with display order, method counts, and active online method code
2. **`GET /api/v1/billing/payment-options?platform=ANDROID&channel=MANUAL_TRANSFER`** — returns payment methods for a specific channel
3. **`GET /api/v1/billing/offers?platform=ANDROID`** — returns available offers with pricing, promotions, and payment availability flag

### Online Payment Method Resolution

For `ONLINE_PAYMENT` channel, only **one** active method is expected per country/platform. The system:
1. Queries `payment_methods` for the resolved country + platform + `ONLINE_PAYMENT` channel, ordered by `display_order`, `LIMIT 1`
2. The `method_code` of that method determines which gateway client to use (via `LocalGatewayRegistry`)
3. `LocalGatewayRegistry.resolve(methodCode)` checks if the gateway is configured (`isConfigured()`)
4. If not configured → HTTP 503 `payment_provider_not_configured`

### Manual Transfer Method Resolution

For `MANUAL_TRANSFER` channel, all active methods for the country/platform are returned. Each method's `verification_params` JSON column defines what fields the frontend should collect from the user (e.g. `referenceNumber`, `accountSuffix`, `transactionNumber`).

---

## 6. Scenario: Online Payment (Chapa)

### Prerequisites
- `payment_methods` row with `method_code='chapa'`, `payment_channel='ONLINE_PAYMENT'`, `is_active=true` for the user's country/platform
- `payment_offers` row for the same country/platform with pricing
- `CHAPA_SECRET_KEY` env var set
- `CHAPA_CALLBACK_URL` set to your backend's Chapa webhook endpoint

### Flow

```
Frontend                          Backend                      Chapa API
   |                                 |                            |
   |-- GET /payment-channels ------->|                            |
   |<-- channels + activeOnlineMethod|                            |
   |                                 |                            |
   |-- GET /offers ----------------->|                            |
   |<-- offers with prices ----------|                            |
   |                                 |                            |
   |-- POST /orders {offerId, methodId, idempotencyKey} --------->|
   |                                 |-- resolveMarket() -------->|
   |                                 |-- validate offer+method match
   |                                 |-- resolve gateway (chapa) ->|
   |                                 |-- gateway.createCheckout() --> POST /transaction/initialize
   |                                 |<-- checkout_url + tx_ref ---|
   |                                 |-- insert order (AWAITING_PAYMENT)
   |<-- {orderId, orderRef, checkoutUrl, status:AWAITING_PAYMENT}  |
   |                                 |                            |
   |-- redirect user to checkoutUrl ->|                           |
   |                                 |                            |
   |                            Chapa webhook (callback-url) ----->|
   |                                 |-- ChapaWebhookHandler      |
   |                                 |-- find order by tx_ref     |
   |                                 |-- if success: updateOrderStatus(VERIFIED)
   |                                 |-- fulfillVerifiedOrder()   |
   |                                 |   - insert transaction     |
   |                                 |   - upsert subscription    |
   |                                 |   - grant boost allowance  |
   |                                 |   - fulfill promotion      |
```

### What Happens on Gateway Failure

If `gateway.createCheckout()` throws:
- If a promotion was reserved, it's released
- The exception propagates to the caller (HTTP 500)
- No order is created

If `gateway.createCheckout()` fails with a non-ResponseStatusException:
- Order is created with status `CREATED` (not `AWAITING_PAYMENT`)
- No checkout URL is returned
- Frontend can retry by creating a new order

### Idempotency

If `idempotencyKey` is provided and an order already exists for this user + key, the existing order is returned without creating a new one.

---

## 7. Scenario: Manual Bank Transfer with verify.et

### Prerequisites
- `payment_methods` row with `method_code='cbe'` (or `telebirr`, `cbebirr`, etc.), `payment_channel='MANUAL_TRANSFER'`, `is_active=true`
- `payment_offers` row for the same country/platform
- `VERIFY_ET_API_KEY` env var set
- `VERIFY_ET_WEBHOOK_URL` env var set to your backend's public webhook URL

### Supported Banks

verify.et supports: `cbe`, `telebirr`, `cbebirr`, `mpesa`, `boa`, `awash`, `dashen`, `siinqee`, `kaafiebirr`

### Flow

```
Frontend                          Backend                     verify.et API
   |                                 |                            |
   |-- GET /payment-channels ------->|                            |
   |<-- MANUAL_TRANSFER channel -----|                            |
   |-- GET /payment-options?channel=MANUAL_TRANSFER ------------->|
   |<-- methods (cbe, telebirr, etc.)|                            |
   |                                 |                            |
   |-- GET /offers ----------------->|                            |
   |<-- offers with prices ----------|                            |
   |                                 |                            |
   |-- POST /manual-transfer/verify  |                            |
   |   {offerId, methodId, platform, |                            |
   |    verificationFields, idempotencyKey}                       |
   |-------------------------------->|                            |
   |                                 |-- validate offer+method   |
   |                                 |-- extract transaction ref  |
   |                                 |-- dedup: existing order with same ref?
   |                                 |   yes + VERIFICATION_PENDING + queued → poll
   |                                 |   yes + other status → increment verify count, return
   |                                 |-- insert order (VERIFICATION_PENDING)
   |                                 |-- insert proof (TRANSACTION_REFERENCE)
   |                                 |-- insert verification attempt (PENDING)
   |                                 |-- verifyEtClient.submit() --> POST /api/verify?waitMs=8000
   |                                 |                              header: x-api-key, Idempotency-Key, x-webhook-url
   |                                 |<-- 200 (inline result) or 202 (queued) ---|
   |                                 |                            |
   |                                 | if 202 (queued):           |
   |                                 |   store requestId on order  |
   |                                 |   return pollAfterMs=5000  |
   |                                 |                            |
   |                                 | if 200 (inline):           |
   |                                 |   resolveInlineStatus()    |
   |                                 |   → VERIFIED → fulfill     |
   |                                 |   → MANUAL_REVIEW          |
   |                                 |   → REJECTED               |
   |                                 |   → EXPIRED (transfer too old)
   |                                 |                            |
   |<-- {orderId, status, pollAfterMs}                            |
   |                                 |                            |
   |  (if queued, poll every 5s)     |                            |
   |-- POST /manual-transfer/verify (same ref) ------------------>|
   |                                 |-- dedup: existing + queued → pollAndUpdateOrder()
   |                                 |-- verifyEtClient.checkStatus() --> GET /api/verify/{requestId}
   |                                 |<-- result or still queued ---|
   |<-- updated status               |                            |
   |                                 |                            |
   |                            verify.et webhook (async) -------->|
   |                                 |-- VerifyEtWebhookHandler   |
   |                                 |-- validate HMAC signature  |
   |                                 |-- idempotent event log     |
   |                                 |-- resolveOrderStatus()     |
   |                                 |   → VERIFIED → fulfill     |
   |                                 |   → MANUAL_REVIEW          |
   |                                 |   → REJECTED               |
   |                                 |   → EXPIRED                |
```

### verify.et Status Resolution

The inline result (`resolveInlineStatus`) and webhook handler (`resolveOrderStatus`) use the same logic:

| Condition | Resulting Status |
|---|---|
| `processingStatus=failed` or `status=not_found` | `REJECTED` |
| Not fully verified (not `completed` + `success` + `verified=true`) | `MANUAL_REVIEW` |
| Bank mismatch (verify.et bank ≠ method_code) | `MANUAL_REVIEW` |
| Amount mismatch (verified amount ≠ expected amount) | `MANUAL_REVIEW` |
| Settlement account not matched | `MANUAL_REVIEW` |
| Settlement match ambiguous | `MANUAL_REVIEW` |
| Settlement match confidence not `high` | `MANUAL_REVIEW` |
| Transfer timestamp older than `manualTransferMaxAgeHours` | `EXPIRED` |
| Duplicate provider reference (already used by another verified order) | `MANUAL_REVIEW` |
| All checks pass | `VERIFIED` → fulfillment |

### Bank-Specific Payloads

`VerifyEtClient.buildPayload()` maps frontend field names to verify.et's expected field names per bank:

| Method Code | Frontend Fields | verify.et Field |
|---|---|---|
| `cbe` | `receiptNumber` / `referenceNumber` | `recieptNumber` (sic) |
| `telebirr` | `transactionOrReference` / `transactionNumber` | `transactionNumber` + `reference` |
| `cbebirr` | `referenceNumber` / `transactionNumber` | `reference` |
| `mpesa` | `transactionNumber` / `referenceNumber` | `reference` |
| `boa` | `referenceNumber` | `reference` + optional `suffix` |
| `awash` | `referenceNumber` | `reference` |
| `dashen` | `referenceNumber` | `reference` |
| `siinqee` | `referenceNumber` | `reference` |
| `kaafiebirr` | `referenceNumber` | `reference` |

### Deduplication

When a user submits the same transaction reference again:
1. If an order with that normalized reference exists and is `VERIFICATION_PENDING` with a queued verify.et request → treated as a **poll**, calls `checkStatus()`
2. If an order exists in any other status → **increments `verification_count`** on the order and returns current status (no new verify.et call)
3. If no existing order → creates new order and submits to verify.et

### Webhook Security

- Signature: `X-Webhook-Signature: sha256=HMAC-SHA256("${timestamp}.${rawBody}", webhookSecret)`
- Timestamp tolerance: ±5 minutes
- If `webhook-secret` is blank, signature validation is **skipped** (logs a warning)
- Events are idempotent: `payment_events` table with `ON CONFLICT DO NOTHING` on `(provider, provider_event_id)`

---

## 8. Scenario: Manual Receipt Upload

### Prerequisites
- Same as manual transfer, but user uploads a screenshot/photo of their payment receipt instead of entering a transaction reference
- Receipt is uploaded to Supabase Storage by the frontend before calling the API

### Flow

```
Frontend                                  Backend
   |                                         |
   |-- upload receipt to Supabase Storage -->|
   |<-- {bucket, path} ----------------------|
   |                                         |
   |-- POST /manual-transfer/receipt         |
   |   {offerId, methodId, platform,         |
   |    storageBucket, storagePath,          |
   |    idempotencyKey}                      |
   |---------------------------------------->|
   |                                         |-- validate offer+method
   |                                         |-- insert order (RECEIPT_SUBMITTED)
   |                                         |-- insert proof (RECEIPT_UPLOAD with bucket+path)
   |<-- {orderId, status:RECEIPT_SUBMITTED}  |
   |                                         |
   |  (admin reviews in admin panel)         |
   |                                         |
   |-- admin: GET /admin/billing/orders?status=RECEIPT_SUBMITTED -->|
   |                                         |-- list orders         |
   |-- admin: GET /admin/billing/orders/{id} -->|
   |                                         |-- returns receiptUrl (signed URL)
   |-- admin: POST /admin/billing/orders/{id}/approve -->|
   |                                         |-- updateOrderStatus(VERIFIED)
   |                                         |-- fulfillVerifiedOrder()
```

### Key Points

- No verify.et call is made — this is purely admin-reviewed
- The receipt image is stored in Supabase Storage; the backend stores `bucket` and `path` in `payment_proofs`
- Admin gets a time-limited signed URL (TTL = `receipt-signed-url-ttl-seconds`) to view the receipt
- Order starts at `RECEIPT_SUBMITTED` and transitions to `VERIFIED` or `REJECTED` via admin action

---

## 9. Scenario: In-App Purchase via RevenueCat

### Prerequisites
- `payment_offers` rows with `external_product_id` matching RevenueCat product IDs (e.g. `qaliye_premium_monthly`)
- `REVENUECAT_WEBHOOK_AUTH_TOKEN` env var set
- RevenueCat dashboard configured with webhook URL pointing to your backend

### Flow

```
Mobile App                    RevenueCat              Backend
   |                              |                       |
   |-- purchase via StoreKit/    |                       |
   |   Google Play Billing        |                       |
   |<-- purchase token -----------|                       |
   |                              |                       |
   |-- RevenueCat SDK syncs ----->|                       |
   |                              |-- webhook ----------->|
   |                              |   event: INITIAL_PURCHASE
   |                              |                       |
   |                              |   RevenueCatWebhookHandler
   |                              |   |-- lock user row   |
   |                              |   |-- idempotent event log
   |                              |   |-- find offer by external_product_id
   |                              |   |-- fulfillRevenueCatSubscription()
   |                              |   |   - lock all subs for user
   |                              |   |   - find matching by stableSubId
   |                              |   |   - insert/update/replace subscription
   |                              |   |   - insert transaction (idempotent)
   |                              |   |   - grant boost allowance
   |                              |   |-- evict subscription cache
   |                              |<-- 200 OK -------------|
```

### Event Types Handled

| Event Type | Action |
|---|---|
| `INITIAL_PURCHASE` | Create/activate subscription, insert transaction as `PURCHASE` |
| `RENEWAL` | Update subscription period, insert transaction as `RENEWAL` |
| `PRODUCT_CHANGE` | Update subscription (upgrade/downgrade), transaction as `UPGRADE` |
| `CANCELLATION` | Mark subscription `CANCELLED`, reset incognito mode |
| `EXPIRATION` | Mark subscription `EXPIRED`, reset incognito mode |
| `BILLING_ISSUE` | Mark subscription `PAST_DUE`, reset incognito mode |
| `NON_RENEWING_PURCHASE` | Fulfill consumable (credits) |
| `SUBSCRIBER_ALIAS` | Logged, no action |

### Subscription Identity

- **`stableSubId`** = `original_transaction_id` (constant across renewals) — used to find the existing subscription row
- **`providerSubRef`** = `transaction_id` (changes per renewal) — used for transaction dedup

### Subscription Replacement Logic

When a webhook arrives, the handler locks all subscription rows for the user and:
- **Case A**: Matching row found (same provider + stableSubId) → update it; if a different active row exists, mark it `REPLACED`
- **Case B**: No matching row, no active row → insert new `ACTIVE` subscription
- **Case C**: No matching row, but active row has same stableSubId → repair/update it
- **Case D**: No matching row, active row has different stableSubId → mark old as `REPLACED`, insert new

### Consumable Fulfillment

For non-renewing purchases (credit packs):
- `fulfillRevenueCatConsumable()` inserts a ledger entry + creates a credit lot
- Idempotent by `providerTransactionId`

---

## 10. Scenario: Admin Review

### Prerequisites
- User must have `role='ADMIN'` in `app_users`
- Order must be in a reviewable status: `MANUAL_REVIEW`, `RECEIPT_SUBMITTED`, `VERIFICATION_PENDING`, or `REVIEW_REQUIRED`

### API Endpoints

```
GET  /api/v1/admin/billing/orders?status=MANUAL_REVIEW,RECEIPT_SUBMITTED,REVIEW_REQUIRED
GET  /api/v1/admin/billing/orders/{orderId}
POST /api/v1/admin/billing/orders/{orderId}/approve   {decisionNote?}
POST /api/v1/admin/billing/orders/{orderId}/reject    {decisionNote?}
POST /api/v1/admin/billing/orders/{orderId}/decline   {decisionNote}  (legacy alias for reject)
```

### Approve Flow

1. Enforce admin role (`SELECT role FROM app_users WHERE id = :adminId`)
2. Verify order is in an approvable status
3. Create a `payment_verification_attempts` row with `provider='ADMIN_REVIEW'`, `result='VERIFIED'`
4. Update order status to `VERIFIED`
5. Call `fulfillmentService.fulfillVerifiedOrder()` — grants subscription/credits
6. Log to `audit_log`

### Reject Flow

1. Enforce admin role
2. Verify order is in a rejectable status
3. Create a `payment_verification_attempts` row with `provider='ADMIN_REVIEW'`, `result='REJECTED'`
4. Update order status to `REJECTED` with reason
5. Log to `audit_log`
6. If order had a promotion redemption, cancel it

---

## 11. Order State Machine

```
                         ┌──────────────────────────────────────────────┐
                         │                                              │
                         ▼                                              │
    ONLINE_PAYMENT:   CREATED ──→ AWAITING_PAYMENT ──→ VERIFIED ────────┼──→ (fulfilled)
                         │              │              │                 │
                         │              │              ├──→ REJECTED     │
                         │              │              │                 │
                         │              ├──→ EXPIRED   │                 │
                         │              ├──→ CANCELLED │                 │
                         │              │              │                 │
                         │              ▼              │                 │
                         │         (Chapa webhook)    │                 │
                         │              │              │                 │
                         │              └──→ MANUAL_REVIEW              │
                         │                              │              │
                         │                              ├──→ VERIFIED ──┘
                         │                              └──→ REJECTED
                         │
    MANUAL_TRANSFER:  VERIFICATION_PENDING ─────────────┐
                         │                              │
                         ├──→ VERIFIED ─────────────────┤
                         ├──→ MANUAL_REVIEW ────────────┤
                         ├──→ REVIEW_REQUIRED ──────────┤
                         ├──→ REJECTED ─────────────────┤
                         ├──→ EXPIRED ──────────────────┘
                         │
                         (verify.et inline or webhook)
                         │
    RECEIPT_UPLOAD:   RECEIPT_SUBMITTED ──── admin ────→ VERIFIED or REJECTED
```

### Status Definitions

| Status | Meaning | Terminal? |
|---|---|---|
| `CREATED` | Order created but checkout not initialized (gateway error) | No |
| `AWAITING_PAYMENT` | Checkout URL generated, waiting for payment | No |
| `VERIFICATION_PENDING` | Manual transfer submitted, verify.et processing | No |
| `RECEIPT_SUBMITTED` | Receipt uploaded, awaiting admin review | No |
| `REVIEW_REQUIRED` | verify.et flagged for review (bank/amount mismatch) | Yes (admin must act) |
| `MANUAL_REVIEW` | Verification inconclusive, needs admin decision | Yes (admin must act) |
| `VERIFIED` | Payment confirmed, fulfillment triggered | Yes |
| `REJECTED` | Payment failed or admin rejected | Yes |
| `EXPIRED` | Order expired or transfer too old | Yes |
| `CANCELLED` | Order cancelled | Yes |

### Polling

When an order is `VERIFICATION_PENDING` with a queued verify.et request, the frontend should poll by re-submitting the same manual transfer verification request. The `OrderResponse` includes `pollAfterMs=5000` to indicate the polling interval.

---

## 12. Fulfillment

### `fulfillVerifiedOrder(orderId, userId)`

Called when an order reaches `VERIFIED` status (via Chapa webhook, verify.et inline, verify.et webhook, or admin approval).

1. Loads the order and its offer
2. If subscription product:
   - Inserts a `transactions` row (`type=SUBSCRIPTION`, `transaction_type=PURCHASE`)
   - Upserts `user_subscriptions` (status=`ACTIVE`, period end calculated from billing interval)
   - Grants monthly boost allowance via credit lots (idempotent by subscription ID + day)
   - Fulfills any associated PURCHASE promotion redemption
3. If consumable product:
   - Inserts a `transactions` row (`type=CONSUMABLE`)
   - Inserts `user_entitlement_ledger` entry (`reason=PURCHASE`)
   - Creates a credit lot with expiration

### `fulfillRevenueCatSubscription(...)`

Called by RevenueCat webhook handler. Uses row-level locking to serialize concurrent webhooks:
1. Locks user row (`SELECT ... FOR UPDATE`)
2. Locks all subscription rows for the user
3. Finds or creates the matching subscription row (see Case A-D above)
4. Inserts transaction idempotently (dedup by `providerTransactionId`)
5. Grants monthly boost allowance (idempotent by `stableSubId + periodStart day`)

### Boost Allowance

Premium subscriptions grant monthly boost credits:
- Quantity determined by `subscription_plan_limits` for the plan (`limit_type='BOOSTS'`)
- Stored as `BOOST_CREDIT` credit lot with expiration = subscription period end
- Idempotent key prevents double-granting across webhook retries

---

## 13. Entitlements & Limits

### `EntitlementService.getEntitlements(userId)`

Returns the user's current plan, daily quotas, credit balances, and active boost.

### Plan Resolution

1. If user has an active subscription (`ACTIVE` or `PENDING_VERIFICATION`) → use that plan
2. Otherwise → resolve FREE plan:
   - `ORDER BY CASE WHEN country_code = :cc THEN 0 ELSE 1 END` — prefers country-specific FREE plan
   - Falls back to `GLOBAL` FREE plan
   - Cached per user (`subscriptionFeatures` cache, TTL 300s)

### Quota Types

| Quota | Limit Type | Reset |
|---|---|---|
| Likes | `LIKES` | Daily (UTC midnight) |
| Super Likes | `SUPERLIKES` | Daily |
| Rewinds | `REWINDS` | Daily |
| Boosts | `BOOSTS` | Per subscription period (credit lot based) |
| Voice Chat Messages | `VOICE_CHAT_MSGS` | Daily |
| Image Chat Messages | `IMAGE_CHAT_MSGS` | Daily |

### Credits

Separate from daily quotas, users can have purchased credit packs:
- `BOOST_CREDIT` — purchased boosts
- `SUPERLIKE_CREDIT` — purchased super likes
- `REWIND_CREDIT` — purchased rewinds

Credit lots track individual purchases with expiration dates. Consumption debits from the oldest non-expired lot first.

### "Go Premium" Preview

The entitlement response also includes `planLimits` — the premium plan limits for the user's country, used to display the upgrade screen.

---

## 14. Promotions

### Promotion Types

- **PURCHASE** — discount applied at order creation (percentage or fixed amount off)
- **CLAIMABLE** — free promotional subscription period, claimable separately

### Promotion Country Scoping

Promotions are scoped by `eligibility_country`: `ET` or `GLOBAL`. ET users see only ET campaigns; non-ET users see only GLOBAL campaigns. This is resolved by `BillingMarketResolver.resolvePromotionCountry()`.

### Purchase Promotion Flow

1. When listing offers, `OfferService` calls `PromotionEligibilityService.findBestPurchasePromotion()` for each subscription offer
2. If a promotion is found, the offer shows `effectivePrice` and `promotion` details
3. At order creation, `OrderService` re-evaluates the promotion and atomically reserves capacity (`atomicReserveCapacity`)
4. If reservation succeeds, the order uses the discounted amount
5. A `promotion_redemptions` row is inserted with status `RESERVED`
6. On fulfillment, the redemption is updated to `FULFILLED`
7. On rejection/expiry, the redemption is cancelled and capacity released

### Claimable Promotion Flow

Claimable promotions are shown alongside offers but are claimed via a separate endpoint. They grant a temporary premium subscription with `provider='PROMOTION'`.

---

## 15. Webhook Endpoints

| Provider | Endpoint | Handler | Auth |
|---|---|---|---|
| Chapa | `POST /api/v1/billing/webhooks/chapa` | `ChapaWebhookHandler` | `CHAPA_WEBHOOK_SECRET` (not currently enforced) |
| verify.et | `POST /api/v1/billing/webhooks/verify-et` | `VerifyEtWebhookHandler` | HMAC-SHA256 signature via `VERIFY_ET_API_KEY` |
| RevenueCat | `POST /api/v1/billing/webhooks/revenuecat` | `RevenueCatWebhookHandler` | `Authorization` header matches `REVENUECAT_WEBHOOK_AUTH_TOKEN` |

### Webhook Idempotency

All webhook handlers use the `payment_events` table with `ON CONFLICT (provider, provider_event_id) DO NOTHING` to prevent duplicate processing. If a duplicate event arrives, it's logged and skipped.

---

## 16. Environment Variables Checklist

### Required for Production

```bash
# Database
SUPABASE_DB_URL=
SUPABASE_DB_USERNAME=
SUPABASE_DB_PASSWORD=

# Chapa (online payment)
CHAPA_SECRET_KEY=
CHAPA_CALLBACK_URL=

# verify.et (bank transfer verification)
VERIFY_ET_API_KEY=
VERIFY_ET_WEBHOOK_URL=        # must be publicly reachable

# RevenueCat (in-app purchases)
REVENUECAT_WEBHOOK_AUTH_TOKEN=
REVENUECAT_API_KEY=

# Payment instructions (manual transfer display)
PAYMENT_ACCOUNT_NUMBER=
PAYMENT_TELEBIRR_SHORT_CODE=
```

### Optional / Has Defaults

```bash
BILLING_BOOST_DURATION_MINUTES=3
BILLING_ORDER_EXPIRY_HOURS=72
BILLING_RECEIPT_URL_TTL=300
BILLING_MANUAL_TRANSFER_MAX_AGE_HOURS=48000   # set to 48 for production

CHAPA_BASE_URL=https://api.chapa.co/v1
VERIFY_ET_BASE_URL=https://verify.et

PAYMENT_BANK_NAME=Commercial Bank of Ethiopia
PAYMENT_ACCOUNT_NAME=Qaliye Technologies

# ArifPay (not yet operational)
ARIFPAY_SECRET_KEY=
ARIFPAY_CALLBACK_URL=
```

### Security Notes

- **Never leave `VERIFY_ET_WEBHOOK_URL` blank in production** — verify.et needs a reachable URL to call back
- **Set `VERIFY_ET_API_KEY` as `webhook-secret`** — without it, webhook signatures are not validated
- **`CHAPA_SECRET_KEY` blank** → Chapa gateway is considered not configured; all online payment orders will fail with 503
- **`REVENUECAT_WEBHOOK_AUTH_TOKEN` blank** → webhook auth check may pass any request (verify controller logic)

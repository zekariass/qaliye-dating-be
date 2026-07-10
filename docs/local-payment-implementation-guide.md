# Qaliye Local Payments + Verify.et Backend Implementation Guide

This guide refactors Qaliye local payments while keeping the existing RevenueCat Apple/Google implementation intact.

The backend already has the main billing schema: `payment_offers`, `payment_methods`, `payment_orders`, `payment_proofs`, `payment_verification_attempts`, `payment_events`, `transactions`, `user_subscriptions`, and entitlement ledger/credit-lot tables. The implementation must reuse these tables and insert the same fulfillment records local payments need, similar to the existing RevenueCat webhook fulfillment path.

## 1. Scope

Implement or refactor:

```text
Local ONLINE_PAYMENT flow
→ Chapa / ArifPay scaffold
→ order created before checkout
→ gateway webhook completes payment later

Local MANUAL_TRANSFER flow
→ no order before user requests verification
→ user sees active payment methods and HTML instructions first
→ user submits verification data
→ backend creates order, proof, verification attempt
→ backend calls Verify.et
→ backend fulfills only after full verification

Admin moderation
→ list, view, approve, reject manual/review-required orders
```

Do not rewrite working RevenueCat logic unless a small shared fulfillment extraction is needed.

## 2. Important schema facts to respect

Current schema already separates:

```text
payment_offers
→ what is sold, product + price + country + platform

payment_methods
→ how users pay, country + platform + method + payment_channel

payment_orders
→ purchase attempt, expected amount, expected currency, provider URL/reference, status

payment_proofs
→ user-submitted payment proof

payment_verification_attempts
→ Verify.et/admin/gateway verification attempts

payment_events
→ webhook idempotency log

transactions
→ financial audit ledger

user_subscriptions / entitlement ledger / credit lots
→ entitlement fulfillment
```

RevenueCat flows are already implemented and should continue to create `transactions`, `user_subscriptions`, and entitlement records as they do now.

Local payments must use the same fulfillment semantics.

## 3. Definitions

Use these terms consistently:

```text
ONLINE_PAYMENT
→ broad local online checkout channel selected by the user
→ concrete gateway comes from the single active payment_methods row for the user's country/platform
→ examples: Chapa, ArifPay, or another future gateway
→ order is created before checkout
→ authenticated gateway webhook finalizes payment

MANUAL_TRANSFER
→ broad manual-transfer channel selected by the user
→ exact method is selected after the user chooses the channel
→ methods include CBE, Telebirr, CBE Birr, M-Pesa, BOA, Awash, Dashen, Siinqee, Kaafi eBirr
→ no order is created before payment
→ order is created only when user submits verification information or receipt
```

In the existing table, `payment_methods.payment_channel` is the broad channel:

```text
ONLINE_PAYMENT
MANUAL_TRANSFER
```

For the checkout UI, the user must choose only one of these broad channels:

```text
ONLINE_PAYMENT
MANUAL_TRANSFER
```

`CHAPA`, `ARIFPAY`, or a future online gateway is selected by the active `payment_methods` row for the resolved country/platform.

The schema already enforces only one active `ONLINE_PAYMENT` method per market through `unique_active_online_payment_per_market`.

### Offers-first rule

The frontend must first load purchasable offers:

```http
GET /api/v1/billing/offers
```

That endpoint resolves the user platform/country according to the existing backend logic and returns the products/prices the user can buy. The user selects an offer before choosing how to pay.

Strict parameter ownership:

```text
GET /api/v1/billing/offers
→ returns selectable offer_id values

GET /api/v1/billing/payment-channels
→ does not receive offer_id

GET /api/v1/billing/payment-options or /payment-option
→ does not receive offer_id

POST online checkout/order creation
→ receives payment_offer_id

POST manual-transfer verification/receipt
→ receives payment_offer_id
```

The selected offer controls amount, currency, subscription/consumable product, and fulfillment. Payment channels and payment methods only control how the user pays.

## 4. Payment channels endpoint

There is already a payment-channels endpoint. Refactor/improve it instead of creating a duplicate.

```http
GET /api/v1/billing/payment-channels?platform=ANDROID|IOS|WEB
```

Backend behavior:

```text
1. Authenticate user.
2. Resolve user country from the user address using the existing project logic.
3. Query active payment_methods for resolved country + platform.
4. Group by payment_methods.payment_channel.
5. Return only broad checkout channels: ONLINE_PAYMENT and/or MANUAL_TRANSFER.
6. For ONLINE_PAYMENT, derive the concrete gateway from the single active `payment_methods` row for the resolved market.
7. Keep the selectable channel code as ONLINE_PAYMENT.
8. Never let the frontend decide country, fallback, gateway, method availability, or price.
```

Example response for Ethiopia Android:

```json
{
  "platform": "ANDROID",
  "billing_country_code": "ET",
  "resolved_market_country_code": "ET",
  "fallback_to_global": false,
  "payment_channels": [
    {
      "code": "ONLINE_PAYMENT",
      "display_name": "Online Payment",
      "active_online_method_code": "chapa",
      "display_order": 1,
      "method_count": 1
    },
    {
      "code": "MANUAL_TRANSFER",
      "display_name": "Manual Transfer",
      "active_online_method_code": null,
      "display_order": 2,
      "method_count": 8
    }
  ]
}
```

Initial checkout must show only broad channels:

```text
Show:
- Online Payment
- Manual Transfer

Do not initially show:
- Chapa
- ArifPay
- CBE
- Telebirr
- CBE Birr
- M-Pesa
- BOA
```

### Online gateway selection rule

For `ONLINE_PAYMENT`, the backend must find the single active `payment_methods` row where:

```text
country_code = resolved user country
platform = requested platform
payment_channel = ONLINE_PAYMENT
is_active = true
```

That row determines the concrete gateway:

```text
method_code = chapa / arifpay / future gateway code
payment_method = CHAPA / ARIFPAY / future gateway enum
```

If no active online method exists, do not show `ONLINE_PAYMENT` as an available channel. If multiple active online methods are found, return a clear backend configuration error because the schema/design expects one active `ONLINE_PAYMENT` method per country/platform.

## 5. Payment methods/options endpoint for a selected channel

There is already a `/payment-option` or `/payment-options` endpoint. Refactor/improve the existing endpoint instead of creating a duplicate when possible.

Important rule:

```text
payment_offer_id must NOT be sent to:
- payment-channels endpoint
- payment-options / payment-methods endpoint

payment_offer_id is sent only when the user starts an actual payment action:
- online checkout/order creation
- manual-transfer verification submission
- manual-transfer receipt submission
```

Preferred contract:

```http
GET /api/v1/billing/payment-options?platform=ANDROID|IOS|WEB&payment_channel=ONLINE_PAYMENT|MANUAL_TRANSFER
```

Equivalent existing routes may be kept if they already exist, but the behavior must match this design.

### Manual transfer options

For manual transfer:

```http
GET /api/v1/billing/payment-options?platform=ANDROID&payment_channel=MANUAL_TRANSFER
```

Backend behavior:

```text
1. Authenticate user.
2. Resolve user country using existing project logic.
3. Query active payment_methods where:
   - country_code = resolved user country
   - platform = requested platform
   - payment_channel = MANUAL_TRANSFER
   - is_active = true
4. Return payment_instructions as configured rich HTML.
5. Return verification_params for dynamic frontend forms.
6. Return only frontend-safe fields.
7. Do not require or accept payment_offer_id in this endpoint.
```

Example response:

```json
{
  "payment_channel": "MANUAL_TRANSFER",
  "platform": "ANDROID",
  "resolved_market_country_code": "ET",
  "payment_methods": [
    {
      "id": "payment-method-uuid",
      "method_code": "cbe",
      "payment_method": "BANK_TRANSFER",
      "display_name": "CBE Bank Transfer",
      "display_order": 1,
      "payment_instructions_html": "<div>...</div>",
      "verification_params": [
        {
          "name": "referenceNumber",
          "type": "string",
          "label": "Reference number",
          "required": true
        },
        {
          "name": "accountSuffix",
          "type": "string",
          "label": "Account suffix (8 digits)",
          "pattern": "^[0-9]{8}$",
          "required": true,
          "min_length": 8,
          "max_length": 8
        }
      ]
    }
  ]
}
```

### Online payment options

For online payment:

```http
GET /api/v1/billing/payment-options?platform=ANDROID&payment_channel=ONLINE_PAYMENT
```

Backend behavior:

```text
1. Resolve the single active ONLINE_PAYMENT method from payment_methods for the user country/platform.
2. Return only that active online payment method.
3. Do not return inactive gateways.
4. Do not let the frontend choose between multiple online gateways.
5. Do not require or accept payment_offer_id in this endpoint.
```

Example response:

```json
{
  "payment_channel": "ONLINE_PAYMENT",
  "active_online_method_code": "chapa",
  "platform": "ANDROID",
  "resolved_market_country_code": "ET",
  "payment_methods": [
    {
      "id": "payment-method-uuid",
      "method_code": "chapa",
      "payment_method": "CHAPA",
      "display_name": "Online Payment",
      "display_order": 1
    }
  ]
}
```

Do not return:

```text
Verify.et API keys
webhook secrets
gateway secrets
settlement account matching internals
provider raw mappings
admin-only metadata
private backend configuration
```

### HTML payment instructions

`payment_methods.payment_instructions` is rich HTML. The frontend can render it as HTML, but the backend should treat it as trusted/admin-authored configured content and must not accept arbitrary user HTML.

Because `payment_offer_id` is not sent to the payment-options endpoint, this endpoint must not depend on offer-specific rendering. The frontend already has the selected offer from `GET /api/v1/billing/offers`, so it should show the selected offer price separately.

Rules:

```text
- payment-options returns the configured instruction HTML for each manual method.
- Do not require order-specific placeholders in manual instructions because no order exists yet.
- Do not require offer-specific placeholders in this endpoint because offer_id is not sent here.
- Existing manual templates that depend on {{ORDER_REFERENCE}} or backend-rendered {{EXPECTED_AMOUNT}} should be refactored.
- The verified amount is enforced later from payment_offers when the user submits manual verification with payment_offer_id.
```
## 6. Local ONLINE_PAYMENT flow: active online method scaffold

For local gateway payments, the user selects the broad `ONLINE_PAYMENT` channel and taps Pay. The frontend does not choose Chapa or ArifPay directly.

The concrete gateway is determined by the single active `ONLINE_PAYMENT` row in `payment_methods` for the resolved country/platform.

Unlike manual transfer, gateway payments do create a `payment_orders` row before checkout.

Endpoint:

```http
POST /api/v1/billing/orders
```

Request:

```json
{
  "payment_offer_id": "offer-uuid",
  "payment_channel": "ONLINE_PAYMENT",
  "payment_method_id": "configured-online-method-uuid",
  "platform": "ANDROID",
  "idempotency_key": "client-generated-key"
}
```

`payment_method_id` must be the single active method returned by the payment-options endpoint for `payment_channel=ONLINE_PAYMENT`. The backend must still verify it is the single active ONLINE_PAYMENT method for the resolved market.

Backend behavior:

```text
1. Authenticate user.
2. Resolve billing country.
3. Resolve the single active ONLINE_PAYMENT method from payment_methods for the country/platform.
4. Validate payment_offer_id belongs to country/platform and is active.
5. Validate payment_method_id belongs to the same country/platform.
6. Require payment_methods.payment_channel = ONLINE_PAYMENT.
7. Require the method is the single active ONLINE_PAYMENT method for that market.
8. Create payment_orders row with:
   - status = CREATED initially, or AWAITING_PAYMENT after checkout URL is created
   - expected_amount_minor_units from payment_offers.price_minor_units
   - expected_currency from payment_offers.currency
   - provider_checkout_url after gateway request
   - provider_order_reference when gateway returns one
9. Call the gateway client represented by that active ONLINE_PAYMENT method to create the checkout session.
10. Return checkout URL to frontend.
```

Response:

```json
{
  "order_id": "order-uuid",
  "order_reference": "QALIYE-...",
  "status": "AWAITING_PAYMENT",
  "provider_checkout_url": "https://...",
  "expected_amount_minor_units": 3000,
  "expected_currency": "ETB"
}
```

### Gateway scaffolding

Create/refactor a shared interface:

```text
LocalOnlinePaymentGateway
- createCheckout(order, offer, method)
- verifyWebhook(request)
- normalizeWebhook(payload)
```

Implement scaffolds:

```text
ChapaGatewayClient
ArifPayGatewayClient
```

For now, scaffold the clients and webhook routes so the architecture is ready, but do not fake successful payments. If the active ONLINE_PAYMENT method points to a gateway whose credentials/integration are missing, return a clear `PAYMENT_PROVIDER_NOT_CONFIGURED` error.

### Online payment webhook

Webhook handler must:

```text
1. Authenticate/verify gateway webhook.
2. Insert payment_events row using provider + provider_event_id for idempotency.
3. Resolve payment_order by provider_order_reference/order_reference.
4. Verify amount and currency match payment_orders.
5. Mark order VERIFIED only if provider confirms success.
6. Call shared fulfillment service exactly once.
7. Store processing_status/processing_error in payment_events.
```

Do not unlock entitlement from browser return. Browser return only tells frontend to refresh order status.

## 7. Local MANUAL_TRANSFER flow: no order before verification

Manual transfer flow must not create a `payment_orders` row when the user only opens the manual payment page or selects a payment method.

Frontend flow:

```text
1. Frontend gets offers with GET /api/v1/billing/offers.
2. User selects one offer.
3. Frontend fetches payment channels without sending offer_id.
4. User chooses MANUAL_TRANSFER.
5. Frontend fetches manual payment methods for the selected channel/country/platform without sending offer_id.
6. Frontend renders payment_instructions_html for the selected method.
7. Frontend renders verification form from verification_params.
8. User pays outside the app.
9. User submits verification data with payment_offer_id and payment_method_id.
10. Backend creates order + proof + verification attempt and calls Verify.et.
11. Frontend polls order status until terminal or review status.
```

This avoids creating abandoned orders when users only view instructions.

## 8. Manual transfer verification endpoint

Refactor existing reference-submission logic if it exists. Since there is no order before verification, the endpoint should not require `order_id`.

Create or refactor to:

```http
POST /api/v1/billing/manual-transfer/verify
```

Request:

```json
{
  "payment_offer_id": "offer-uuid",
  "payment_method_id": "method-uuid",
  "platform": "ANDROID",
  "verification_data": {
    "referenceNumber": "DCU1ECZEL5",
    "accountSuffix": "12345678"
  },
  "idempotency_key": "client-generated-key"
}
```

Backend transaction before external call:

```text
1. Authenticate user.
2. Resolve billing country from user address.
3. Validate active payment_offer for country/platform.
4. Validate active payment_method for country/platform.
5. Require payment_methods.payment_channel = MANUAL_TRANSFER.
6. Validate verification_data using payment_methods.verification_params.
7. Create payment_orders row:
   - user_id
   - payment_offer_id
   - payment_method_id
   - expected_amount_minor_units = payment_offers.price_minor_units
   - expected_currency = payment_offers.currency
   - status = VERIFICATION_PENDING
   - expires_at = now + small operational TTL, or now + review TTL
   - payment_instruction_snapshot = selected method instruction snapshot only
8. Create payment_proofs row:
   - proof_type = TRANSACTION_REFERENCE
   - payment_network = payment_methods.method_code or payment_method
   - transaction_reference = extracted reference from verification_data
9. Create payment_verification_attempts row:
   - verification_method = VERIFY_ET
   - status = PENDING
   - verify_et_idempotency_key = deterministic key from order/proof/idempotency
10. Commit local rows.
11. Call Verify.et outside or after DB transaction using persisted attempt.
12. Persist Verify.et requestId / response.
```

Response must return the created order so frontend can poll status:

```json
{
  "order_id": "order-uuid",
  "order_reference": "QALIYE-...",
  "status": "VERIFICATION_PENDING",
  "verify_et_request_id": "request-id-or-null",
  "poll_after_ms": 1000
}
```

When Verify.et returns immediate success and fulfillment completes in the same request, `status` may be `VERIFIED`. When Verify.et returns queued, `status` remains `VERIFICATION_PENDING`.

`payment_instruction_snapshot` should snapshot only what matters historically:

```json
{
  "method_code": "telebirr",
  "display_name": "Telebirr",
  "payment_instructions_html": "<div>...</div>",
  "submitted_verification_fields": {
    "transactionOrReference": "DCU1ECZEL5"
  }
}
```

Do not use `payment_instruction_snapshot` as a replacement for live payment-method configuration.

## 9. Manual receipt upload endpoint

For manual transfers that cannot be verified online, create/refactor:

```http
POST /api/v1/billing/manual-transfer/receipt
```

Request:

```json
{
  "payment_offer_id": "offer-uuid",
  "payment_method_id": "method-uuid",
  "platform": "ANDROID",
  "receipt_storage_bucket": "payment-receipts",
  "receipt_storage_path": "receipts/user-id/file.jpg",
  "verification_data": {
    "referenceNumber": "optional"
  },
  "idempotency_key": "client-generated-key"
}
```

Backend behavior:

```text
1. Validate user, offer, method, country, platform.
2. Require method payment_channel = MANUAL_TRANSFER.
3. Create payment_order with status = MANUAL_REVIEW.
4. Create payment_proof with proof_type = RECEIPT_UPLOAD.
5. Create payment_verification_attempts with verification_method = ADMIN_REVIEW and status = MANUAL_REVIEW.
6. Grant nothing.
```

## 10. Verify.et integration requirement

The backend agent must read and follow:

```text
.agents/skills/verify-et-api
```

Use that skill as the source of truth for endpoint paths, authentication, webhook verification, request headers, idempotency, and polling behavior.

Remove existing Verify.et logic if it is incomplete or inconsistent. Otherwise refactor it to this design.

Initially implement Verify.et only.

Do not implement fallback providers in this phase.

```text
No Leul Verify fallback.
No multi-provider retry.
No frontend provider selection.
```

## 11. Verify.et request payload mapping

The user selected payment method must map to Verify.et `bank` value.

The safest rule:

```text
payment_methods.method_code must equal the Verify.et bank code
```

Supported method codes/banks:

```text
cbe
telebirr
cbebirr
mpesa
boa
awash
dashen
siinqee
kaafiebirr
```

If existing method codes differ, add `metadata.verify_et_bank` and use that as the explicit mapping. Still validate that the selected method corresponds to the returned bank.

Build Verify.et payloads from validated `verification_data`:

```json
// cbe
{
  "bank": "cbe",
  "recieptNumber": "<referenceNumber>"
}
```

```json
// telebirr
{
  "bank": "telebirr",
  "reference": "<transactionOrReference>"
}
```

```json
// cbebirr
{
  "bank": "cbebirr",
  "phoneNumber": "<phoneNumber>",
  "reference": "<referenceNumber>"
}
```

```json
// mpesa
{
  "bank": "mpesa",
  "reference": "<referenceNumber>"
}
```

```json
// boa
{
  "bank": "boa",
  "reference": "<referenceNumber>",
  "suffix": "<accountSuffix>"
}
```

```json
// awash / dashen / siinqee / kaafiebirr
{
  "bank": "awash",
  "reference": "<referenceNumber>"
}
```

For `dashen`, `siinqee`, and `kaafiebirr`, replace `bank` accordingly.

Preserve Verify.et's required spelling exactly where documented, including `recieptNumber` if that is the API field in `.agents/skills/verify-et-api`.

## 12. Verify.et response handling

Verify.et may return an immediate completed result or a queued result.

### Immediate response: HTTP 200

When Verify.et returns completed success:

```text
1. Save verify_et_request_id from requestId.
2. Save provider_verification_reference from transactionNumber / receiptNumber / reference.
3. Save raw_response masked where necessary.
4. Normalize the result.
5. Run final verification checks.
6. If all checks pass, set attempt VERIFIED, order VERIFIED, and fulfill.
7. If a later webhook arrives for the same requestId, treat it as idempotent no-op.
```

### Queued response: HTTP 202

When Verify.et returns queued/pending:

```text
1. Save verify_et_request_id.
2. Save statusUrl/pollAfterMs/estimatedWaitMs in raw_response.
3. Keep payment_orders.status = VERIFICATION_PENDING.
4. Keep payment_verification_attempts.status = PENDING.
5. Process the Verify.et webhook or supported polling response later.
```

Do not fulfill queued verification.

## 13. Verify.et webhook / polling finalization

Use one shared finalization method for:

```text
- immediate 200 result
- webhook result
- polling result, if supported by the Verify.et skill
```

```text
finalizeVerifyEtVerification(attemptId, normalizedResult)
```

This method must be idempotent:

```text
- If order is already VERIFIED, return success/no-op.
- If transaction already exists for payment_order_id, do not create another.
- If provider transaction/reference is already used by a completed transaction, reject duplicate.
- If payment_events already processed the same Verify.et requestId, do not process twice.
```

Webhook handler must:

```text
1. Authenticate webhook using Verify.et skill instructions.
2. Insert payment_events with provider = VERIFY_ET and provider_event_id = requestId.
3. If duplicate event, return 2xx no-op.
4. Find payment_verification_attempts by verify_et_request_id.
5. Normalize payload.
6. Call finalizeVerifyEtVerification.
7. Update payment_events processing_status, processed_at, processing_error.
```

## 14. Required final verification checks

Verify.et high-level success is not enough.

The payment is auto-approved only when all checks below pass.

```text
1. Verify.et high-level response says verified = true and status = success.
2. settlementAccountMatch.matched = true.
3. settlementAccountMatch.ambiguous = false.
4. settlementAccountMatch.matchConfidence is acceptable, preferably high.
5. Verify.et result bank matches the selected payment method method_code / metadata.verify_et_bank.
6. Verified amount equals payment_orders.expected_amount_minor_units after normalizing to app minor units.
7. Verified currency equals payment_orders.expected_currency.
8. provider transaction number / receipt number has not already fulfilled another order.
9. Verify.et transaction timestamp is not older than MANUAL_TRANSFER_MAX_AGE_HOURS.
```

For amount comparison:

```text
verified amount from Verify.et
→ normalize using the app money utility
→ compare to payment_orders.expected_amount_minor_units
```

Never compare floating-point money values directly.

Settlement mismatch rule:

```text
If verified = true but settlementAccountMatch.matched is not true:
→ do not fulfill
→ mark attempt RECIPIENT_MISMATCH or FAILED
→ mark order MANUAL_REVIEW or REJECTED according to existing policy
```

Missing settlementAccountMatch:

```text
If settlementAccountMatch is missing or inconclusive:
→ do not fulfill
→ mark order MANUAL_REVIEW
```

## 15. REVIEW_REQUIRED status

Add a new order status if it does not already exist:

```text
REVIEW_REQUIRED
```

Use `REVIEW_REQUIRED` only for this situation:

```text
Verify.et says verification successful
AND settlementAccountMatch is successful
BUT the selected method/bank or selected offer amount does not match
```

Examples:

```text
User selected CBE but submitted a Telebirr reference
→ Verify.et confirms real Telebirr payment
→ settlement account matched
→ bank/method mismatch
→ order REVIEW_REQUIRED
→ user can retry with correct method, or admin can review
```

```text
User selected the wrong offer/amount
→ Verify.et confirms payment to Qaliye
→ settlement account matched
→ amount mismatch
→ order REVIEW_REQUIRED
→ user can retry with correct offer, or admin can review
```

Do not use `REVIEW_REQUIRED` for settlement mismatch.

```text
Settlement mismatch means payment may not have gone to Qaliye.
That should be MANUAL_REVIEW or REJECTED, not REVIEW_REQUIRED.
```

Retryability:

```text
- Do not mark a provider reference as consumed/fulfilled when order is REVIEW_REQUIRED.
- Allow user to submit another verification with the correct method or offer.
- Prevent duplicate fulfillment only after a payment has been VERIFIED/COMPLETED.
```

Migration needed:

```text
- Add REVIEW_REQUIRED to payment_orders.status CHECK constraint.
- Optionally add METHOD_MISMATCH to payment_verification_attempts.status.
- Otherwise use AMOUNT_MISMATCH for amount mismatch and FAILED/MANUAL_REVIEW with raw reason METHOD_MISMATCH.
```

## 16. Manual transfer max age

Add configuration:

```yaml
qaliye:
  payments:
    manual-transfer-max-age-hours: ${MANUAL_TRANSFER_MAX_AGE_HOURS:48}
```

Use this to reject old transfer receipts.

```text
If Verify.et transaction timestamp is older than configured max age:
→ payment_orders.status = EXPIRED
→ payment_verification_attempts.status = FAILED
→ no fulfillment
```

This check uses Verify.et transaction time, not order creation time, because manual orders are created only when the user submits verification.

## 17. Fulfillment service

Extract or reuse the existing RevenueCat fulfillment logic so local payments create the correct records.

Create a shared internal service if missing:

```text
PaymentFulfillmentService.fulfillVerifiedPayment(orderId, provider, providerTransactionId)
```

It must run in one database transaction and lock the order.

For every verified local payment:

```text
1. Lock payment_orders row FOR UPDATE.
2. Confirm order.status is not already VERIFIED.
3. Confirm no completed transaction already exists for payment_order_id.
4. Load payment_offer.
5. Determine if offer is subscription or consumable.
6. Create transactions row with status COMPLETED.
7. For subscription offer:
   - create/update user_subscriptions
   - provider = CHAPA / ARIFPAY / BANK_TRANSFER / TELEBIRR / CBE_BIRR as appropriate
   - auto_renew = false for local manual transfers
   - current_period_start = now
   - current_period_end from subscription_products interval
   - payment_offer_id set
8. For consumable offer:
   - create user_entitlement_ledger PURCHASE row
   - create user_entitlement_credit_lots row
9. Create included subscription allowance credits only if the plan/product grants them.
10. Mark payment_orders.status = VERIFIED.
11. Write audit_log if admin-driven or important payment state transition.
```

Do not create FREE subscription rows.

Do not create ledger rows for Premium access itself unless the existing system intentionally uses `PREMIUM_ACCESS`; subscription access should primarily live in `user_subscriptions`.

## 18. Admin moderation endpoints

Create or refactor admin endpoints.

```http
GET /api/v1/admin/billing/orders
```

Query parameters:

```text
status
user_id
payment_method
payment_channel
from
to
page
page_size
```

```http
GET /api/v1/admin/billing/orders/{order_id}
```

Return order, offer, method, proof, verification attempts, masked raw responses, transaction/entitlement status, and audit history.

```http
POST /api/v1/admin/billing/orders/{order_id}/approve
```

Request:

```json
{
  "note": "Verified manually from bank statement"
}
```

Approve behavior:

```text
1. Require admin role.
2. Lock order FOR UPDATE.
3. Allow statuses MANUAL_REVIEW, REVIEW_REQUIRED, VERIFICATION_PENDING only when policy permits.
4. Create/update payment_verification_attempts as ADMIN_REVIEW / VERIFIED.
5. Mark payment_orders.status = VERIFIED.
6. Call PaymentFulfillmentService exactly once.
7. Write audit_log.
```

```http
POST /api/v1/admin/billing/orders/{order_id}/reject
```

Request:

```json
{
  "note": "Reference could not be matched"
}
```

Reject behavior:

```text
1. Require admin role.
2. Lock order FOR UPDATE.
3. Mark latest/admin verification attempt REJECTED.
4. Mark payment_orders.status = REJECTED.
5. Grant nothing.
6. Write audit_log.
```

## 19. User order status polling

The frontend will poll for online payment and manual verification status. Check the existing order-detail/status endpoint first and improve it. Reimplement only if the existing code is not usable.

Preferred owner-only endpoint:

```http
GET /api/v1/billing/orders/{order_id}
```

The response should be safe for the order owner and include enough information for polling UI:

```json
{
  "id": "order-uuid",
  "order_reference": "QALIYE-...",
  "status": "VERIFICATION_PENDING",
  "payment_channel": "MANUAL_TRANSFER",
  "payment_method": "telebirr",
  "payment_method_display_name": "Telebirr",
  "payment_offer_id": "offer-uuid",
  "expected_amount_minor_units": 3000,
  "expected_currency": "ETB",
  "provider_checkout_url": null,
  "provider_order_reference": null,
  "created_at": "2026-07-08T10:00:00Z",
  "updated_at": "2026-07-08T10:00:20Z",
  "expires_at": "2026-07-08T10:30:00Z",
  "can_retry_verification": false,
  "can_upload_receipt": false,
  "can_contact_support": true
}
```

Polling rules:

```text
ONLINE_PAYMENT:
- After opening the checkout URL, browser return only tells frontend to refresh/poll.
- Entitlement is granted only after authenticated gateway webhook verifies payment and backend fulfills.
- Poll until VERIFIED, REJECTED, EXPIRED, CANCELLED, MANUAL_REVIEW, or REVIEW_REQUIRED.

MANUAL_TRANSFER:
- After submitting verification data, backend returns order_id and current status.
- If Verify.et returns 202 queued, frontend polls the order status while backend waits for webhook/polling finalization.
- Entitlement is granted only after backend sets order VERIFIED and fulfillment succeeds.
- REVIEW_REQUIRED means successful payment/settlement but wrong selected method or amount; UI should let the user retry with correct offer/method or contact support.
```

Do not expose private raw provider responses, settlement internals, Verify.et debug data, provider secrets, or admin-only notes in the owner polling response.

## 20. Frontend contract summary

Manual transfer UI:

```text
1. Get offers with GET /api/v1/billing/offers.
2. User selects one offer.
3. Get payment channels without sending offer_id.
4. User selects MANUAL_TRANSFER.
5. Get payment options/methods for MANUAL_TRANSFER without sending offer_id.
6. User selects one manual method, such as CBE, Telebirr, CBE Birr, M-Pesa, or BOA.
7. Render payment_instructions_html.
8. Render verification form from verification_params.
9. User pays outside app.
10. User submits verification data with payment_offer_id + payment_method_id.
11. Backend creates order and verifies.
12. Frontend polls GET /api/v1/billing/orders/{order_id}.
```

Online payment UI:

```text
1. Get offers with GET /api/v1/billing/offers.
2. User selects one offer.
3. Get payment channels without sending offer_id.
4. User selects ONLINE_PAYMENT.
5. Get payment options/methods for ONLINE_PAYMENT without sending offer_id.
6. Backend returns the single active ONLINE_PAYMENT method from payment_methods.
7. POST /billing/orders with payment_offer_id, payment_channel = ONLINE_PAYMENT, and that returned payment_method_id.
8. Open checkout URL.
9. On return, poll GET /api/v1/billing/orders/{order_id}.
10. Entitlement updates only after backend webhook fulfills.
```

## 21. Tests

Add or update tests for:

```text
1. Payment-channels endpoint reuses/refactors the existing implementation and returns broad channels only.
2. Ethiopia Android receives ONLINE_PAYMENT and MANUAL_TRANSFER, not CHAPA/ARIFPAY/CBE/Telebirr as first-step choices.
3. ONLINE_PAYMENT channel resolves the concrete gateway from the single active payment_methods row for the market.
4. GET /api/v1/billing/offers is the first product/pricing call and returns offers for resolved country/platform.
5. payment_offer_id is not accepted or required by payment-channels.
6. payment_offer_id is not accepted or required by payment-options/payment-methods.
7. Payment-options endpoint reuses/refactors the existing implementation for methods by selected channel, country, and platform.
8. ONLINE_PAYMENT payment-options returns only the single active online gateway method.
9. MANUAL_TRANSFER payment-options returns active methods, HTML instructions, and verification_params.
10. Manual transfer does not create payment_orders before verification submission.
11. Manual transfer verification request requires payment_offer_id and payment_method_id.
12. Online checkout/order creation request requires payment_offer_id and the selected ONLINE_PAYMENT method.
13. Manual instructions do not require order_reference because no order exists before verification.
14. Verify.et payload mapping is correct for cbe, telebirr, cbebirr, mpesa, boa, awash, dashen, siinqee, kaafiebirr.
15. Verify.et HTTP 200 success with settlementAccountMatch matched true fulfills once.
16. Verify.et HTTP 202 queues verification and order polling shows VERIFICATION_PENDING.
17. Later webhook for already fulfilled immediate response is idempotent no-op.
18. settlementAccountMatch missing/false does not fulfill.
19. Bank/method mismatch with successful settlement moves order to REVIEW_REQUIRED.
20. Amount mismatch with successful settlement moves order to REVIEW_REQUIRED.
21. Old transaction timestamp beyond MANUAL_TRANSFER_MAX_AGE_HOURS moves order to EXPIRED.
22. Duplicate provider transaction/reference cannot fulfill twice.
23. GET /api/v1/billing/orders/{order_id} supports owner polling for ONLINE_PAYMENT and MANUAL_TRANSFER.
24. Browser return never unlocks entitlement without webhook fulfillment.
25. Admin approve fulfills subscription offer correctly.
26. Admin approve fulfills consumable offer correctly.
27. Admin reject grants nothing.
28. Chapa/ArifPay scaffold does not fake success when the active online provider is not configured.
29. RevenueCat tests still pass unchanged.
```


## 22. Completion report expected from backend agent

The backend agent should report:

```text
- Existing local payment code refactored
- New endpoints added or existing endpoints reused
- Schema migrations added
- Verify.et integration files changed
- Chapa/ArifPay scaffold files added or refactored
- Admin endpoints added
- Fulfillment service behavior
- Tests added/updated
- Any assumptions or missing Verify.et details from .agents/skills/verify-et-api
```

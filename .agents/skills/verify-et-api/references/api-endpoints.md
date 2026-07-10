# Verify.et API Contract

Use this reference for auth, endpoint behavior, payload shape, response handling, permissions, and source-of-truth files.

## Base URL, Auth, and Headers

- Production base URL: `https://verify.et`
- API-key auth header: `x-api-key: VERIFY_BANK_ET_...`
- Session-cookie auth may exist for first-party dashboard flows. Third-party and backend integrations should use `x-api-key`.
- API-key permissions:
  - `POST /api/verify`: `verification:write`
  - `GET /api/verify/*`: `verification:read`
  - `POST /api/verify/test-webhook`: `verification:read`
- Request headers:
  - `Content-Type: application/json`
  - `x-api-key: <key>`
  - `Idempotency-Key: <stable-attempt-key>` on `POST /api/verify`
  - `x-webhook-url: <public-https-url>` on `POST /api/verify` when using webhooks
- Response headers worth preserving:
  - `x-request-id`
  - `Retry-After`
  - `X-Verify-Cache: HIT`

## Core Endpoints

### `POST /api/verify`

Submits a verification request.

- Requires `verification:write`.
- Uses credits/quota for finite-plan callers.
- `waitMs` is an optional query parameter capped server-side. It is a best-effort inline wait, not a guarantee.
- `200` means the verification completed inline.
- `202` means the request was accepted and queued. Persist `requestId`, then poll, subscribe to SSE, or wait for webhook delivery.
- `409` means the same `Idempotency-Key` was reused with a different payload.
- Cache replay is disabled when an idempotency key or webhook URL is present.

Example:

```bash
curl -X POST "https://verify.et/api/verify?waitMs=5000" \
  -H "x-api-key: VERIFY_BANK_ET_..." \
  -H "Idempotency-Key: checkout_123_attempt_1" \
  -H "Content-Type: application/json" \
  -d '{
    "bank": "cbe",
    "receiptNumber": "RECEIPT_ID"
  }'
```

### `GET /api/verify/:requestId`

Returns the latest status for a verification request.

- Requires `verification:read`.
- The request is scoped to the same user/API-key context that created or owns the request.
- Terminal processing states are `completed` and `failed`.

Example:

```bash
curl "https://verify.et/api/verify/REQUEST_ID" \
  -H "x-api-key: VERIFY_BANK_ET_..."
```

### `GET /api/verify/:requestId/events`

Streams status updates with Server-Sent Events.

- Requires `verification:read`.
- Send `Accept: text/event-stream`.
- Emits `status` events containing status JSON.
- Emits a final `done` event with reason `terminal` or `timeout`.

### `POST /api/verify/test-webhook`

Sends a test webhook to a receiver.

- Requires `verification:read`.
- Body: `{ "webhookUrl": "https://...", "scenario": "success" | "failed" | "not_found" }`
- Returns `200` when the test delivery completes or downstream HTTP failure details are available.
- Returns `400` for invalid request input.
- Returns `502` for network, TLS, DNS, firewall, or timeout failures.

Example:

```bash
curl -X POST "https://verify.et/api/verify/test-webhook" \
  -H "x-api-key: VERIFY_BANK_ET_..." \
  -H "Content-Type: application/json" \
  -d '{
    "webhookUrl": "https://example.com/webhooks/verify",
    "scenario": "success"
  }'
```

### History and Reporting Endpoints

History endpoints are useful for dashboards, reports, and audit trails:

- `GET /api/verify/history`
- `GET /api/verify/history/summary`
- `GET /api/verify/history/activity`
- `GET /api/verify/history/export`
- `GET /api/verify/history/:requestId`
- `DELETE /api/verify/history/:requestId`

Use these only when the user's request involves reporting or historical activity. For checkout fulfillment, use `POST /api/verify`, status polling/SSE, and webhooks.

## Request Payloads

Explicit bank mode is used whenever `bank` is present.

| Bank | Explicit payload |
| --- | --- |
| `cbe` | `receiptNumber`, CBE receipt link, or legacy `referenceNumber` plus `accountSuffix` exactly 8 digits |
| `telebirr` | `transactionNumber` or `reference` |
| `mpesa` | `transactionNumber` or `reference`; known receipt URLs/SMS text can be parsed |
| `dashen` | `referenceNumber` or `reference` |
| `boa` | `referenceNumber` or `reference`, plus `accountSuffix` exactly 5 digits |
| `cbebirr` | `receiptNumber` or `reference`, plus `phone` or `phoneNumber` |
| `awash` | `referenceNumber` or `reference`, full receipt URL, or configured token |
| `siinqee` | `referenceNumber` or `reference`, full receipt URL, or configured token |
| `zemen` | Status/enum surface exists, but direct `POST /api/verify` is not supported yet |

Universal mode omits `bank`:

```json
{
  "reference": "receipt, URL, SMS text, or transaction reference",
  "suffix": "optional CBE/BOA disambiguator",
  "phoneNumber": "optional CBE Birr disambiguator"
}
```

Universal routing notes:

- `FT...` references need `suffix`: 8 digits routes CBE, 5 digits routes Bank of Abyssinia.
- Ten-character alphanumeric references route to Telebirr unless a valid phone routes them to CBE Birr.
- `09...` Ethiopian local phone numbers can normalize to `251...` for CBE Birr routing.
- Supported receipt URL origins can route CBE, Awash, Siinqee, or M-Pesa.
- Ambiguous references should use explicit `bank` payloads.

## Response Shapes

Inline terminal response:

```json
{
  "success": true,
  "message": "Verification completed.",
  "data": [
    {
      "bank": "cbe",
      "status": "success",
      "verified": true
    }
  ],
  "requestId": "uuid",
  "verification": {
    "requestId": "uuid",
    "processingStatus": "completed",
    "status": "success",
    "verified": true,
    "result": {}
  },
  "links": {
    "statusUrl": "/api/verify/uuid"
  }
}
```

Queued response:

```json
{
  "success": true,
  "message": "Verification queued.",
  "data": [],
  "requestId": "uuid",
  "statusUrl": "/api/verify/uuid",
  "estimatedWaitMs": 5000,
  "verification": {
    "requestId": "uuid",
    "processingStatus": "queued",
    "status": "pending",
    "verified": false
  },
  "links": {
    "statusUrl": "/api/verify/uuid",
    "pollAfterMs": 1500,
    "webhookRegistered": true
  }
}
```

Status response:

```json
{
  "success": true,
  "message": "Verification status.",
  "data": {
    "requestId": "uuid",
    "bank": "cbe",
    "processingStatus": "running",
    "status": "pending",
    "verified": false
  }
}
```

Error response with verification error:

```json
{
  "success": false,
  "message": "Verification failed.",
  "requestId": "uuid",
  "error": {
    "code": "upstream_timeout",
    "message": "Request timed out",
    "retryable": true
  }
}
```

`data[0].confirmationHistory` can appear on successful verified results. `confirmedBefore: true` means Verify.et has previously confirmed the same canonical transaction.

## Status Interpretation

- `processingStatus: queued` or `running`: not terminal; keep pending UX and poll/SSE/wait for webhook.
- `processingStatus: completed`, `status: success`, `verified: true`: transaction verified.
- `processingStatus: completed`, `status: not_found`: terminal negative bank/provider result.
- `processingStatus: completed`, `status: failed`: terminal provider/platform failure; inspect `error`.
- `processingStatus: failed`: queue/worker failure; inspect `errorMessage` and `error`.

## Source-of-Truth Files

When working inside the Verify.et repository, prefer these files over stale notes:

- Routes: `apps/server/src/modules/verify/routes.ts`
- Controllers: `apps/server/src/modules/verify/controller.ts`
- Request parsing and caps: `apps/server/src/modules/verify/request.ts`
- Universal smart router: `apps/server/src/modules/verify/smart-router.ts`
- Service, cache, idempotency, and queue flow: `apps/server/src/modules/verify/service.ts`
- Contracts: `packages/contracts/src/verification.ts`, `packages/contracts/src/banks.ts`, `packages/contracts/src/api-key.ts`
- Error taxonomy: `packages/verification/src/errors.ts`
- Webhook delivery: `apps/worker/src/webhook-worker.ts`, `packages/queue/src/webhook-url.ts`

## Related References

- Bank payload details: `bank-specs.md`
- Error handling: `error-codes.md`
- Webhooks: `../patterns/webhooks.md`

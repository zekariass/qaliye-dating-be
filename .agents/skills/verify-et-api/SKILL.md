---
name: verify-et-api
description: Integrate, test, debug, and harden Verify.et transaction verification API implementations. Use when building Verify.et server-side adapters, payment or checkout verification, webhook receivers, polling or SSE status flows, API-key auth, idempotency, credits or quota handling, bank-specific payloads, or production error handling.
license: MIT
metadata:
  author: Verify.et
  version: "1.0.0"
  category: integration
  tags:
    - api
    - verification
    - banking
    - webhooks
    - idempotency
---

# Verify.et API

This portable skill applies to backend integrations in Node.js, Python, PHP, Go, Java, Ruby, .NET, Rust, and mobile apps that proxy through a trusted backend.

## Core Workflow

1. Identify the integration surface: backend REST adapter, checkout/payment verification, webhook receiver, polling/SSE status UI, dashboard/reporting usage, or framework-specific wrapper.
2. Load only the references needed:
   - `references/api-endpoints.md` for auth, endpoints, payloads, responses, permissions, and status flow.
   - `references/bank-specs.md` for explicit bank payloads and universal-router disambiguators.
   - `references/error-codes.md` for HTTP failures, verification error codes, retry decisions, credits, quotas, and idempotency conflicts.
   - `patterns/integration-methods.md` for choosing sync, queued, webhook, polling, or SSE flows.
   - `patterns/webhooks.md` for receiver security, signatures, retries, payloads, and test-webhook debugging.
   - `debugging/common-mistakes.md` for production anti-patterns.
   - `debugging/production-checklist.md` for pre-launch validation.
3. Inspect the user's code before changing it. Find where API keys are stored, where `POST /api/verify` is called, how `202` is handled, whether duplicate submissions are possible, and whether Verify.et calls run server-side.
4. Keep Verify.et API keys server-side. Browser and mobile clients should call the user's backend, not Verify.et directly.
5. Prefer one small server-side Verify.et client/adapter over scattered raw `fetch` calls.
6. Send an `Idempotency-Key` for payment and checkout attempts, and persist the returned Verify.et `requestId`.
7. Treat `200` as an inline terminal response and `202` as queued work. For `202`, store `requestId`, then poll `GET /api/verify/:requestId`, open SSE for interactive UIs, or rely on webhooks for server-to-server fulfillment.
8. Treat `confirmationHistory.confirmedBefore === true` as a repeat-confirmation signal. Do not fulfill blindly without applying the product's duplicate-payment policy.
9. Add focused tests at the user's boundary: request builder tests, webhook signature tests, polling tests, idempotency conflict tests, and error mapping tests.

## Integration Decisions

- Use `POST /api/verify?waitMs=...` only as a best-effort convenience. Production workflows must still work when Verify.et returns `202`.
- Use webhooks when the backend must learn the result even if the browser or mobile app closes.
- Use polling when the client needs a simple pending-status screen.
- Use SSE when an authenticated web UI needs lower-latency updates without frequent polling.
- Use both webhooks and polling/SSE for robust checkout UX: webhook updates server truth; polling/SSE updates the visible user flow.
- Use explicit `bank` payloads when the product already knows the provider. Use universal `reference` payloads when the UI accepts mixed receipts or links.
- Put irreversible fulfillment behind terminal state checks and duplicate-confirmation policy checks.

## Debugging Baseline

Before diagnosing, capture:

- Request method, URL, body shape, headers used, and whether the API key was server-side.
- HTTP status, response body, `x-request-id`, `Retry-After`, and `X-Verify-Cache`.
- Verify.et `requestId`, `processingStatus`, `status`, `error.code`, and `error.retryable`.
- Bank/provider, raw receipt/reference, suffix/phone disambiguators, and webhook URL.
- Whether the same checkout attempt reused the same `Idempotency-Key`.

## Security Rules

- Do not place `x-api-key` values in frontend, mobile app, logs, screenshots, or committed files.
- Do not trust webhook payloads before validating the signature when a signature is present.
- Verify webhook signatures against the raw body, not parsed JSON.
- Make webhook processing idempotent by `requestId` and/or delivery ID.
- Honor `Retry-After` for rate limits and temporary unavailability.
- Keep logs useful but redacted: include `requestId`, bank, status, and error code; omit API keys, cookies, and full authorization headers.

## Sources

Use the current public docs when internet access is available:

- `https://verify.et/docs/get-started`
- `https://verify.et/docs/api`
- `https://verify.et/docs/sdk`

If working inside the Verify.et repository, prefer implementation source over stale docs. Relevant source paths are listed in `references/api-endpoints.md`.

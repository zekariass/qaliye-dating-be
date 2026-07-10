# Integration Methods

Use this reference when choosing or refactoring the user's Verify.et integration shape.

## Recommended Module Shape

Create one server-side integration boundary:

- `verifyEtClient`: low-level HTTP calls, headers, timeouts, response parsing, and retry helpers.
- `verificationService`: domain orchestration, idempotency key generation, persistence, and duplicate-confirmation policy.
- `webhookController`: raw-body signature verification, idempotent persistence, and queue handoff.
- `statusController`: returns the user's local payment/verification state to frontend or mobile clients.

Avoid:

- Exposing Verify.et API keys to browser or mobile apps.
- Calling Verify.et directly from React, Vue, mobile, or other untrusted client code.
- Treating `202` as an error.
- Polling forever with a tight fixed interval.
- Fulfilling an order before terminal state and duplicate-confirmation checks pass.

## Method Selection

| Method | Use when | Requirements | Main risk |
| --- | --- | --- | --- |
| Inline wait (`POST /api/verify?waitMs=...`) | Admin checks, demos, low-volume flows that can still handle `202` | Backend call and pending-state fallback | Provider latency can exceed the inline wait. |
| Queued + polling | Web or mobile status screens with simple infrastructure | Persist `requestId`; poll local/server status | Excessive polling if no deadline/backoff. |
| Queued + SSE | Authenticated web UI needs low-latency status updates | SSE endpoint support and reconnect handling | Long-lived connections must be scaled intentionally. |
| Queued + webhook | Backend must update state even after the user leaves | Public HTTPS receiver, raw body access, idempotency | Receiver security and duplicate delivery handling. |
| Webhook + polling/SSE | Checkout UX where server truth and user-visible state both matter | Webhook receiver plus client status endpoint | More moving parts, but best resilience. |

## Checkout Flow

1. User submits receipt/reference to the user's backend.
2. Backend validates and normalizes input.
3. Backend creates or reuses a checkout/payment attempt with a stable idempotency key.
4. Backend calls `POST /api/verify` with `x-api-key`, `Idempotency-Key`, and optional `webhookUrl` or `x-webhook-url`.
5. Backend persists Verify.et `requestId`, normalized payload, status URL, and current local state.
6. If Verify.et returns `200`, apply terminal-state and duplicate-confirmation policy immediately.
7. If Verify.et returns `202`, return a local pending state to the client.
8. Polling/SSE updates the visible UI; webhook updates the backend source of truth.
9. Heavy fulfillment work runs asynchronously after terminal policy checks pass.

## Polling Strategy

- Start with `links.pollAfterMs` when returned; otherwise use a conservative interval such as 1.5-3 seconds.
- Stop on `processingStatus: completed` or `failed`.
- Apply a max-attempts limit or wall-clock deadline.
- Back off after several pending checks.
- Honor `Retry-After` for `429` and `503`.
- Prefer polling the user's own backend status endpoint rather than exposing Verify.et auth to clients.

## SSE Strategy

- Use SSE for authenticated web clients that need near-live status updates.
- Send `Accept: text/event-stream`.
- Handle reconnects and terminal `done` events.
- Keep a polling fallback for networks that block streaming.
- Do not use SSE as the only source of backend truth; persist state server-side.

## Webhook Strategy

- Use webhooks for durable server-to-server updates.
- Receiver must be public HTTPS in production.
- Verify signatures when present.
- Persist by `requestId` and/or delivery ID before side effects.
- Return `2xx` quickly, then enqueue fulfillment or notification work.

## Framework Notes

Node/Express/Hono:

- Use server-side `fetch`/undici with explicit timeout handling.
- Keep the Verify.et client in a service module, not route handlers.
- Read the raw webhook body before JSON parsing.
- Use timing-safe comparison when hardening webhook signature checks.

Next.js/React:

- Route handlers or server actions call Verify.et.
- Client components call the app's own API.
- Use TanStack Query or route loaders to poll local status.

Python/FastAPI/Django:

- Use `httpx`/`requests` from backend code only.
- Preserve raw request body for webhook signatures.
- Use Celery, RQ, or background tasks for fulfillment side effects.

PHP/Laravel:

- Use the Laravel HTTP client from a service class.
- Verify webhook signatures against raw request content.
- Dispatch jobs from the webhook controller and return `204`.

Mobile apps:

- Do not ship Verify.et API keys in the app.
- Mobile calls the user's backend and polls a local status endpoint.
- Expose Verify.et `requestId` to mobile only if it is part of the product's support model.

## Related References

- Endpoint contract: `../references/api-endpoints.md`
- Webhook details: `webhooks.md`
- Production checklist: `../debugging/production-checklist.md`

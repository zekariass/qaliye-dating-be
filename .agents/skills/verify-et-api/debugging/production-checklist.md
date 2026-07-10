# Production Checklist

Use this before shipping a Verify.et integration.

## API Boundary

- [ ] Verify.et API key is stored only in backend/server environment variables.
- [ ] Backend uses `x-api-key`, not bearer-style auth, for Verify.et API-key auth.
- [ ] Frontend and mobile clients call the user's backend, not Verify.et directly.
- [ ] One adapter/client owns Verify.et HTTP calls.
- [ ] Request body validation exists before calling Verify.et.
- [ ] Explicit bank payloads use the required fields in `../references/bank-specs.md`.
- [ ] Universal payloads collect `suffix` or `phoneNumber` when needed.

## Idempotency and State

- [ ] Each checkout/payment attempt has one stable `Idempotency-Key`.
- [ ] Retries reuse the same key with the same normalized payload.
- [ ] The Verify.et `requestId` is persisted.
- [ ] Local state distinguishes pending, success, not found, failed, and queue failure.
- [ ] Fulfillment requires a terminal state.
- [ ] `confirmationHistory.confirmedBefore` has an explicit product policy.

## Async Flow

- [ ] `202` responses are handled as queued work, not errors.
- [ ] Polling stops on terminal state and has a max attempts or deadline.
- [ ] Polling honors `links.pollAfterMs` and `Retry-After`.
- [ ] SSE clients handle terminal `done` events and reconnects.
- [ ] Webhooks update server truth when browser/mobile clients close.

## Webhooks

- [ ] Production webhook URL is public HTTPS.
- [ ] Receiver can access the raw request body.
- [ ] Signature validation uses `${timestamp}.${rawBody}` when a signature is present.
- [ ] Timestamp tolerance is enforced to reduce replay risk.
- [ ] Processing is idempotent by `requestId` and/or delivery ID.
- [ ] Handler persists the event and returns `2xx` quickly.
- [ ] Heavy side effects run in a queue or background job.
- [ ] `POST /api/verify/test-webhook` succeeds for `success`, `failed`, and `not_found` scenarios.

## Errors and Retries

- [ ] HTTP `400`, `401`, `402`, `403`, `404`, `409`, `422`, `429`, `500`, `502`, and `503` are mapped intentionally.
- [ ] Retry logic checks `error.retryable`.
- [ ] Retry logic uses exponential backoff with jitter.
- [ ] Retry logic honors `Retry-After`.
- [ ] Non-retryable errors surface actionable messages to users or operators.
- [ ] Credits/quota failures do not retry until the user can act.

## Observability

- [ ] Logs include local attempt ID, Verify.et `requestId`, `x-request-id`, bank, status, and error code.
- [ ] Logs redact API keys, cookies, authorization headers, phone numbers when required by policy, and full raw payloads when they contain PII.
- [ ] Metrics track request count, latency, terminal status, retry count, webhook delivery success/failure, and duplicate webhook deliveries.
- [ ] Alerts cover elevated 5xx/503, webhook failure spikes, sustained latency, rate-limit spikes, and credit/quota exhaustion.

## Tests

- [ ] Request builder tests cover each supported bank.
- [ ] Universal-router tests cover ambiguous references, CBE/BOA suffixes, CBE Birr phone routing, and URL routing.
- [ ] `200` inline response tests cover terminal fulfillment.
- [ ] `202` tests cover persistence, pending UI state, polling, and webhook fallback.
- [ ] Idempotency tests cover same-key same-payload retry and same-key different-payload conflict.
- [ ] Webhook tests cover valid signature, invalid signature, stale timestamp, duplicate delivery, and async processing.
- [ ] Error mapping tests cover retryable and non-retryable cases.

## Related References

- Endpoint contract: `../references/api-endpoints.md`
- Webhooks: `../patterns/webhooks.md`
- Common mistakes: `common-mistakes.md`

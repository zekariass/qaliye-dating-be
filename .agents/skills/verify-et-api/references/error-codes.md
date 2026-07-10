# Error Codes and Recovery

Use this reference for HTTP failures, verification error codes, retry decisions, quota issues, and idempotency conflicts.

## Capture First

- HTTP status, response JSON, `x-request-id`, `Retry-After`, and `X-Verify-Cache`.
- Verify.et `requestId`, `processingStatus`, `status`, `error.code`, and `error.retryable`.
- Payload mode: explicit bank or universal router.
- Bank/reference/suffix/phone/webhook URL.
- Whether the request used an API key, session cookie, idempotency key, webhook URL, and credits/quota.

## HTTP Error Matrix

| Status | Common cause | Recovery |
| --- | --- | --- |
| `400` | Invalid `Idempotency-Key`, invalid webhook URL, invalid webhook test body/scenario | Fix header/body shape. Keep idempotency keys non-empty and `<=255` chars. Use public HTTPS webhook URLs. |
| `401` | Missing, invalid, disabled, or expired API key/session | Use server-side `x-api-key`, rotate key, or fix session/env loading. |
| `402` | `INSUFFICIENT_CREDITS` or `NO_CREDITS` | Prompt top-up or plan change. Do not retry until credits exist. |
| `403` | API key lacks `verification:write` or `verification:read` | Update key permissions. |
| `404` | Wrong `requestId` or different caller scope | Persist returned `requestId`; poll with the same account/API key scope. |
| `409` | `Idempotency-Key` reused with a different payload | Use a stable key per checkout attempt and keep payload stable for retries. |
| `422` | Missing fields, ambiguous universal payload, unsupported direct bank path | Fix bank-specific fields or add `suffix`, `phoneNumber`, or explicit `bank`. |
| `429` | API-key quota/rate limit or queue/backlog protection | Honor `Retry-After`, reduce concurrency, and apply jittered backoff. |
| `500` | Unexpected platform failure | Capture `x-request-id`; retry with backoff only when the operation is idempotent. |
| `502` | Test webhook delivery failed due to DNS/TLS/firewall/network/timeout | Inspect receiver logs, public HTTPS reachability, raw-body handling, and timeout behavior. |
| `503` | Queue unavailable after request record creation | Persist `requestId`, honor `Retry-After`, and poll later. |

## Verification Error Codes

| `error.code` | Retryable | Meaning | User-facing guidance |
| --- | --- | --- | --- |
| `not_found` | false | Bank/provider did not find the transaction | Ask the user to verify receipt/reference, suffix, phone, and settlement timing. |
| `invalid_input` | false | Input accepted by API shape but invalid for provider | Fix normalization or validation before retrying. |
| `parse_error` | usually false | Provider response changed or could not be parsed | Capture requestId and escalate; avoid retry spam. |
| `upstream_invalid_response` | false unless advised | Provider returned unexpected non-retryable response | Capture requestId and bank; retry only after investigation. |
| `upstream_timeout` | true unless terminal path says otherwise | Provider or proxy timed out | Retry with backoff or use queued flow; show pending/retry UI. |
| `upstream_rate_limited` | true | Provider or platform throttled requests | Back off and reduce per-bank concurrency. |
| `upstream_unavailable` | true | Provider/network unavailable or 5xx | Retry later and show temporary outage message. |
| `unsupported_bank` | false | Bank unsupported by the verification path | Use a supported bank or fallback flow. |
| `unknown` | often true | Unclassified error | Capture requestId, response body, bank, and logs before deciding retry. |

## Retry Rules

- Retry only when `error.retryable === true` or the HTTP status clearly indicates a transient condition.
- Use exponential backoff with jitter for retryable failures.
- Honor `Retry-After` when present.
- Do not retry validation errors, unsupported-bank errors, idempotency conflicts, or insufficient-credit responses until the underlying condition changes.
- Do not create a new idempotency key for a retry of the same checkout attempt. Reusing the same key with the same payload is the protection.

## Fast Triage

1. If the request fails before reaching Verify.et, check API key location, CORS assumptions, and browser/mobile direct calls.
2. If `401` or `403`, inspect key permissions and server environment loading.
3. If `402`, resolve credits or plan entitlements.
4. If `409`, verify the user's retry path keeps the same payload for the same idempotency key.
5. If `422`, compare the payload to `bank-specs.md`.
6. If `202` is treated as failure, fix the code to persist `requestId` and poll/SSE/webhook.
7. If duplicates happen, use stable idempotency keys and a policy for `confirmationHistory.confirmedBefore`.
8. If webhooks fail, use `POST /api/verify/test-webhook` and `../patterns/webhooks.md`.
9. If latency is high, stop depending on inline `waitMs`; use queued UX and provider-status-aware retries.

## Related References

- Endpoint contract: `api-endpoints.md`
- Bank payloads: `bank-specs.md`
- Common anti-patterns: `../debugging/common-mistakes.md`

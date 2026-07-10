# Webhook Implementation

Use this reference when implementing or debugging Verify.et webhook receivers.

## Delivery Model

- Register a webhook per verification request via body `webhookUrl` or header `x-webhook-url`.
- Verify.et sends `POST` with event `verification.completed`.
- Delivery succeeds when the receiver returns any `2xx`.
- Default retries: up to 5 attempts with backoff `0s`, `30s`, `2m`, `8m`, `30m`.
- Retryable downstream responses include `500+`, `429`, and `408`.
- Network errors and timeouts are retried until attempts are exhausted.
- Webhook timeout defaults to 30 seconds.
- Receiver response bodies are not part of the business contract.

## Security Headers

Common delivery headers:

- `Content-Type: application/json`
- `User-Agent: VerifyTransactions-Webhook/1.0`
- `X-Webhook-Event: verification.completed`
- `X-Webhook-Timestamp`
- `X-Webhook-Delivery-Id`
- `X-Webhook-Signature: sha256=<hmac>` when signing is configured
- `X-Webhook-Test: true` for test webhook deliveries

Signature payload:

```text
${timestamp}.${rawBody}
```

Verify with HMAC-SHA256 and the shared webhook signing secret. Use the raw request body, not parsed JSON.

## Receiver Requirements

- Expose a public URL. Production webhook URLs must use HTTPS.
- Do not use localhost, private IPs, `.local`, bare hostnames, URL credentials, or URLs longer than 2048 characters.
- Verify the signature before trusting the payload when `X-Webhook-Signature` is present.
- Enforce a timestamp tolerance, commonly 5 minutes, to reduce replay risk.
- Persist by `requestId` and/or `X-Webhook-Delivery-Id` idempotently.
- Return `204` or `200` quickly and process heavy work asynchronously.
- Do not make fulfillment irreversible until `data.processingStatus` is terminal and product policy checks pass.

## Payload Shape

```json
{
  "event": "verification.completed",
  "requestId": "uuid",
  "timestamp": "2026-02-20T12:00:00.000Z",
  "data": {
    "processingStatus": "completed",
    "status": "success",
    "verified": true,
    "bank": "cbe",
    "amount": "1500.00",
    "currency": "ETB",
    "referenceNumber": "FT...",
    "accountSuffix": "12345678",
    "confirmationHistory": {
      "scope": "platform",
      "confirmedBefore": false,
      "confirmationCount": 1
    },
    "error": {
      "code": "upstream_timeout",
      "message": "Request timed out",
      "retryable": true
    },
    "durationMs": 2340,
    "completedAt": "2026-02-20T12:00:02.340Z"
  }
}
```

## Node/Express Raw-Body Example

```ts
import crypto from "node:crypto";
import express from "express";

const app = express();

app.post("/webhooks/verify", express.raw({ type: "application/json" }), async (req, res) => {
  const timestamp = req.header("X-Webhook-Timestamp") ?? "";
  const signature = req.header("X-Webhook-Signature") ?? "";
  const rawBody = req.body.toString("utf8");
  const secret = process.env.VERIFY_ET_WEBHOOK_SECRET;

  if (signature && secret) {
    const expected = crypto
      .createHmac("sha256", secret)
      .update(`${timestamp}.${rawBody}`)
      .digest("hex");

    const expectedHeader = `sha256=${expected}`;
    const valid =
      signature.length === expectedHeader.length &&
      crypto.timingSafeEqual(Buffer.from(signature), Buffer.from(expectedHeader));

    if (!valid) return res.sendStatus(401);
  }

  const event = JSON.parse(rawBody);
  // Persist event.requestId idempotently, then enqueue fulfillment work.
  return res.sendStatus(204);
});
```

## Debugging Sequence

1. Call `POST /api/verify/test-webhook` with `scenario: "success"`, then `"failed"`, then `"not_found"`.
2. Confirm the receiver sees raw body, timestamp, delivery ID, event name, and optional signature.
3. If Verify.et returns `400`, fix webhook URL shape or public reachability.
4. If Verify.et returns `502`, inspect DNS, TLS, firewall, timeout, and raw-body handling.
5. If delivery succeeds but user state is wrong, check idempotency, terminal state handling, and policy for `confirmationHistory.confirmedBefore`.

## Related References

- Integration choices: `integration-methods.md`
- Endpoint contract: `../references/api-endpoints.md`
- Common mistakes: `../debugging/common-mistakes.md`

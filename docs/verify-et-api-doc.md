API Reference
Endpoint contracts, permissions, request shapes, response envelopes, webhooks, and platform surfaces verified against the current server and worker code.

Overview
API responses use a predictable envelope with success, message, and data. Verification submissions also include requestId, verification, and links when a status URL is available.

Base URL: https://verify.et

Authentication
Send API keys in the x-api-key header.
Session cookies work for dashboard-owned routes. Server-to-server integrations should use API keys with explicit permissions.

Request Headers
x-api-key: VERIFY_BANK_ET_your_key_here
Idempotency-Key: verify-request-001
X-Webhook-Url: https://your-app.com/webhooks/verify
Response Headers
x-request-id: <request-id>
X-Verify-Cache: HIT
Retry-After: 30
x-api-key
Idempotency-Key
X-Webhook-Url
x-request-id
Retry-After
Base URLs
Verify.ET API traffic uses the hosted production domain.
Production: https://verify.et
Selected: https://verify.et
Supported Banks & Required Fields
Field	Required	Description
cbe	Required	referenceNumber/reference plus accountSuffix/suffix exactly 8 digits.
boa	Required	referenceNumber/reference plus accountSuffix/suffix exactly 5 digits.
telebirr	Required	transactionNumber or reference.
mpesa	Required	transactionNumber or reference. Supported receipt URLs/SMS text can be parsed.
cbebirr	Required	receiptNumber/reference plus phone/phoneNumber.
dashen	Required	referenceNumber or reference.
awash	Required	referenceNumber or reference. Universal detection supports configured receipt host or token.
siinqee	Required	referenceNumber or reference. Universal detection supports configured receipt host or token.
kaafiebirr	Required	referenceNumber or reference. Full receipt URLs auto-route; bare tokens require explicit bank. phone is optional.
zemen	Optional	Enum/status surface only. POST /api/verify returns unsupported for direct Zemen submissions.
Universal smart-router fields

You may omit bank and send reference, optional suffix, and optional phoneNumber. Explicit bank payloads always win when bank is present.
Settlement Account Matching
New Feature
Settlement account matching verifies that a valid receipt was paid into the intended wallet or bank account. This protects checkout, fulfillment, and support workflows from accepting a real receipt that belongs to a different receiver.

How matching works

Send settlementAccount for a one-off receiver check. If it is omitted, Verify.ET checks the authenticated user's saved /api/bank-accounts records and registered business settlement accounts for the request bank. Wallet-like banks such as Telebirr and M-Pesa use Ethiopian phone canonicalization, while regular banks prefer normalized account-number matching.
Field	Required	Description
settlementAccount	Optional	Expected receiver account or phone number for this request. Manual values are checked before saved accounts.
settlementAccountMatch	Optional	Stable response object returned on completed result items, including matched, confidence, source, candidate count, ambiguity, reason, and safe debug data.
receiverAccount	Optional	Provider receiver field used for matching. Masked values such as 2519****0897 or 1****7441 are matched by visible digits.
Manual settlementAccount
{
"bank": "telebirr",
"transactionNumber": "DET8FJGUJ4",
"settlementAccount": "0939080897"
}
Saved-account fallback
{
"bank": "telebirr",
"transactionNumber": "DET8FJGUJ4"
}
Matched Response Object
{
"matched": true,
"matchType": "masked_pattern",
"matchConfidence": "high",
"source": "account_registry",
"bank": "telebirr",
"receiverAccount": "2519****0897",
"matchedSettlementAccount": "0939080897",
"matchedUserBankAccountId": "58807b8e-ff38-49c9-a836-304c6b88bc29",
"matchedBusinessBankAccountId": null,
"candidateCount": 1,
"ambiguous": false,
"reason": "receiver_mask_matches_visible_digits",
"debug": {
"receiverAccount": "2519****0897",
"candidateCount": 1,
"matchType": "masked_pattern",
"visiblePrefix": "2519",
"visibleSuffix": "0897",
"visibleCharacterCount": 8,
"matchedUserBankAccountId": "58807b8e-ff38-49c9-a836-304c6b88bc29",
"matchedBusinessBankAccountId": null
}
}
No Saved Account Response Object
{
"matched": false,
"matchType": "unmatched",
"matchConfidence": "none",
"source": "account_registry",
"bank": "telebirr",
"matchedUserBankAccountId": null,
"matchedBusinessBankAccountId": null,
"candidateCount": 0,
"ambiguous": false,
"reason": "no_registered_accounts"
}
Stable Reason Codes

receiver_account_exact_match
receiver_mask_matches_visible_digits
receiver_suffix_matches
no_registered_accounts
missing_receiver_account
candidate_account_mismatch
ambiguous_registered_accounts
verification_not_successful
Ambiguous masked matches return matched: false, ambiguous: true, and a safe candidate count instead of silently picking one saved account.

Verification Endpoints
POST
/api/verify
Submit a verification
Queues a verification request. If waitMs is present, the API waits briefly for completion and returns 200 when it finishes in time; otherwise it returns 202 with a status URL.
Requires verification:write
Headers

x-api-key
content-type: application/json
idempotency-key (optional)
x-webhook-url (optional)
Query Parameters

waitMs (optional; capped by server and bank defaults)
Request Body
{
"bank": "cbe",
"referenceNumber": "FT1234567890",
"accountSuffix": "12345678",
"webhookUrl": "https://your-app.com/webhooks/verify"
}
Responses

200
Completed
200 Completed
{
"success": true,
"message": "Transaction verified successfully.",
"data": [
{
"bank": "cbe",
"status": "success",
"verified": true,
"amount": 1500,
"currency": "ETB",
"senderName": "Abebe Bikila",
"receiverName": "Acme Trading",
"receiverAccount": "1****7441",
"referenceNumber": "FT1234567890",
"accountSuffix": "12345678",
"timestamp": "2026-02-20T12:00:00.000Z",
"confirmationHistory": {
"scope": "platform",
"isFirstConfirmation": true,
"confirmedBefore": false,
"firstConfirmedAt": "2026-02-20T12:00:00.000Z",
"lastConfirmedAt": "2026-02-20T12:00:00.000Z",
"confirmationCount": 1
},
"settlementAccountMatch": {
"matched": true,
"matchType": "masked_pattern",
"matchConfidence": "high",
"source": "account_registry",
"bank": "cbe",
"receiverAccount": "1****7441",
"matchedSettlementAccount": "1000303997441",
"matchedUserBankAccountId": "bank_account_123",
"matchedBusinessBankAccountId": null,
"candidateCount": 1,
"ambiguous": false,
"reason": "receiver_mask_matches_visible_digits"
}
}
],
"requestId": "550e8400-e29b-41d4-a716-446655440000",
"verification": {
"requestId": "550e8400-e29b-41d4-a716-446655440000",
"processingStatus": "completed",
"status": "success",
"verified": true
},
"links": {
"statusUrl": "/api/verify/550e8400-e29b-41d4-a716-446655440000"
}
}
202
Queued
202 Queued
{
"success": true,
"message": "Verification queued.",
"data": [],
"requestId": "550e8400-e29b-41d4-a716-446655440000",
"statusUrl": "/api/verify/550e8400-e29b-41d4-a716-446655440000",
"estimatedWaitMs": 5000,
"verification": {
"requestId": "550e8400-e29b-41d4-a716-446655440000",
"bank": "cbe",
"processingStatus": "queued",
"status": "pending",
"verified": false
},
"links": {
"statusUrl": "/api/verify/550e8400-e29b-41d4-a716-446655440000",
"pollAfterMs": 1500,
"webhookRegistered": true
}
}
Examples

Explicit Bank cURL
curl -X POST \
"https://verify.et/api/verify?waitMs=5000" \
-H "Content-Type: application/json" \
-H "x-api-key: $VERIFY_ET_API_KEY" \
-H "Idempotency-Key: verify-demo-001" \
-d '{
"bank": "cbe",
"referenceNumber": "FT1234567890",
"accountSuffix": "12345678",
"webhookUrl": "https://your-app.com/webhooks/verify"
}'
Universal Request Body
{
"reference": "FT1234567890",
"suffix": "12345678",
"phoneNumber": "0911223344"
}
Manual Settlement Account Body
{
"bank": "telebirr",
"transactionNumber": "DET8FJGUJ4",
"settlementAccount": "0939080897"
}
GET
/api/verify/:requestId
Fetch verification status
Returns the latest status visible to the current session or API key scope.
Requires verification:read
Headers

x-api-key
Responses

200
Status
200 Status
{
"success": true,
"message": "Verification status.",
"data": {
"requestId": "550e8400-e29b-41d4-a716-446655440000",
"bank": "cbe",
"processingStatus": "completed",
"status": "success",
"verified": true,
"completedAt": "2026-02-20T12:00:02.340Z"
}
}
Examples

cURL
curl \
-H "x-api-key: $VERIFY_ET_API_KEY" \
"https://verify.et/api/verify/550e8400-e29b-41d4-a716-446655440000"
GET
/api/verify/:requestId/events
Stream verification status
Server-sent events stream that emits status changes and closes with a done event.
Requires verification:read
Headers

x-api-key
accept: text/event-stream
Responses

200
SSE Events
200 SSE Events
event: status
data: {"requestId":"550e8400-e29b-41d4-a716-446655440000","processingStatus":"running","status":"pending","verified":false}

event: status
data: {"requestId":"550e8400-e29b-41d4-a716-446655440000","processingStatus":"completed","status":"success","verified":true}

event: done
data: {"requestId":"550e8400-e29b-41d4-a716-446655440000","reason":"terminal"}
GET
/api/verify/history
Verification history
Lists historical verification requests for the authenticated user or API key scope.
Requires verification:read
Headers

x-api-key
Query Parameters

bank
status
processingStatus
startDate
endDate
limit
offset
Responses

200
History
200 History
{
"success": true,
"message": "Verification history.",
"data": [
{
"requestId": "550e8400-e29b-41d4-a716-446655440000",
"bank": "cbe",
"processingStatus": "completed",
"status": "success",
"verified": true,
"durationMs": 2340,
"createdAt": "2026-02-20T12:00:00.000Z"
}
],
"meta": {
"total": 120,
"limit": 50,
"offset": 0
}
}
POST
/api/verify/test-webhook
Test a webhook endpoint
Sends a sample webhook payload to validate your endpoint before using it in live verification requests.
Requires verification:read
Headers

x-api-key
content-type: application/json
Request Body
{
"webhookUrl": "https://your-app.com/webhooks/verify",
"scenario": "success"
}
Responses

200
Delivered or delivery attempted
Webhook Delivery
Delivered by the worker after a verification reaches a terminal state.
Payload
{
"event": "verification.completed",
"requestId": "550e8400-e29b-41d4-a716-446655440000",
"timestamp": "2026-02-20T12:00:02.500Z",
"data": {
"processingStatus": "completed",
"status": "success",
"verified": true,
"bank": "cbe",
"amount": "1500",
"currency": "ETB",
"senderName": "Abebe Bikila",
"receiverName": "Acme Trading",
"referenceNumber": "FT1234567890",
"accountSuffix": "12345678",
"durationMs": 2340,
"completedAt": "2026-02-20T12:00:02.340Z"
}
}
Event

X-Webhook-Event: verification.completed

Correlation

X-Webhook-Delivery-Id and requestId

Timeout

30 seconds by default

Attempts

5 attempts by default

Backoff

0s, 30s, 2m, 8m, 30m

Signature

X-Webhook-Signature only when a secret is configured

URL safety

In production, webhook URLs must be HTTPS, cannot include credentials, must resolve in DNS, and cannot point at private or reserved network addresses.
Rate Limits and Credits
Several guardrails can reject a request before it enters a queue.
API-key rate limits return 429 with Retry-After. Verification credit exhaustion is checked against the user's shared account balance and returns 402. Queue saturation returns 429, and queue unavailability returns 503 with a status URL when a request row was created.

Common Error Cases
401 Invalid API key
403 Permission denied: verification:write required
409 Idempotency key already used for a different verification request
422 Invalid bank-specific payload
429 API key rate limit exceeded or queue saturated
503 Verification queue unavailable
Permissions & API Keys
API keys are scoped by resource and action.
Endpoint	Permission
POST /api/verify	verification:write
GET /api/verify/:requestId	verification:read
GET /api/verify/:requestId/events	verification:read
GET /api/verify/history	verification:read
POST /api/verify/test-webhook	verification:read
GET /api/metrics	metrics:read
GET /api/reports/*	metrics:read
GET /api/uptime	uptime:read
GET /api/uptime/client/*	public
POST
/api/api-keys
Create API key
Creates a user API key. The full secret is returned once.
Headers

session cookie
content-type: application/json
Request Body
{
"name": "Production Key",
"expiresInDays": 365,
"permissions": {
"verification": ["read", "write"],
"metrics": ["read"],
"uptime": ["read"]
},
"metadata": {
"environment": "production",
"project": "checkout"
}
}
Responses

201
Created
201 Created
{
"success": true,
"message": "API key created. Save this key - it will not be shown again.",
"data": {
"key": "VERIFY_BANK_ET_your_key_here",
"apiKey": {
"id": "key_123",
"name": "Production Key",
"prefix": "VERIFY_BANK_ET_",
"enabled": true,
"permissions": {
"verification": ["read", "write"],
"metrics": ["read"],
"uptime": ["read"]
},
"requestCount": 0
}
}
}
Other Platform Endpoints
Additional surfaces supported by the server.
Metrics

GET /api/metrics

Reports

GET /api/reports/verification/overview and bank detail routes

Uptime

GET /api/uptime, /overview, /v2/overview

Public uptime

GET /api/uptime/client and overview variants

Credits

GET /api/credits/balance, /usage, /transactions, /packages

Profile

GET/PATCH /api/profile and profile logo routes

Bank accounts

GET/POST/PATCH/DELETE /api/bank-accounts

Examples

GET /api/examples

Health

GET /health/live and /health/ready

Prometheus

GET /metrics
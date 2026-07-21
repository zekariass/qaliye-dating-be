# Account Deletion API

## `DELETE /api/v1/me`

Permanently deletes the authenticated user's account. The deletion is immediate, transactional, and idempotent.

### Authentication

Requires a valid JWT bearer token. The token **must have been issued within the last 1 hour**. If the token is older, the request is rejected with `401 Unauthorized` (`recent_auth_required`).

### Request

#### Headers

| Header           | Required | Description                                              |
|------------------|----------|----------------------------------------------------------|
| `Authorization`  | Yes      | `Bearer <access_token>` — must be issued within 1 hour. |
| `Content-Type`   | No       | `application/json` — required only if sending a JSON body. |

#### Query Parameters

| Parameter | Type      | Required | Description                                                                    |
|-----------|-----------|----------|--------------------------------------------------------------------------------|
| `confirm` | `boolean` | No       | Must be `true`. Alternative to the JSON body field. |

#### Body (optional)

```json
{
  "confirm": true
}
```

| Field     | Type      | Required | Description                                                                 |
|-----------|-----------|----------|-----------------------------------------------------------------------------|
| `confirm` | `boolean` | No       | Must be `true`. Alternative to the `confirm` query parameter. |

At least one of the query parameter or the body field must be `true`; otherwise returns `400` (`confirmation_required`).

#### Examples

```bash
# Via query parameter (no body needed)
curl -X DELETE "https://api.qaliye.com/api/v1/me?confirm=true" \
  -H "Authorization: Bearer eyJhbGciOi..."

# Via JSON body
curl -X DELETE https://api.qaliye.com/api/v1/me \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"confirm": true}'
```

### Response

#### Success — `204 No Content`

Returned only after the full deletion flow has completed:

1. **Transactional phase** (single DB transaction):
   - User status set to `DELETED`, `deleted_at` timestamped.
   - Profile anonymized (`display_name = 'Deleted user'`, all personal fields nulled, `is_visible = false`).
   - All active matches ended (`end_reason = 'ACCOUNT_DELETED'`).
   - Notification data purged (deliveries, outbox events, devices, preferences).
   - Discovery preferences and user blocks deleted. Discovery actions not referenced by matches are deleted (actions referenced by matches are retained due to `ON DELETE RESTRICT` + immutability trigger).
   - Profile photos rows deleted (storage paths collected for cleanup).
   - Chat message bodies anonymized (`body = '[deleted]'`). Chat attachments deleted (storage paths collected for cleanup). `storage_bucket`/`storage_path` on `messages` are retained due to the `check_message_content_by_type` constraint.
   - Support messages anonymized, support attachments deleted.
   - Audit log entry inserted.
2. **Best-effort storage cleanup** — collected files deleted from Supabase Storage buckets (`profile-photos`, `chat-attachments`, `support-attachments`, `verification-selfies`). Failures are logged but do not block the response.
3. **Cache eviction** — `userStatus` cache entry evicted so existing JWTs are immediately rejected by `UserStatusFilter` (which returns `account_deleted` 403 on subsequent requests to any endpoint).
4. **Supabase Auth soft-deletion** — the Supabase Auth user is soft-deleted via the Admin API (`DELETE /auth/v1/admin/users/{id}` with `should_soft_delete: true`). Supabase retains the `auth.users` row (satisfying the FK from `app_users`) while: anonymizing the email and phone so they can be reused for a new registration; removing all linked OAuth identities (Google, Apple, …) from `auth.identities`; revoking all active sessions and MFA factors. A `404` is treated as idempotent. **This phase is best-effort**: if the Supabase API call fails (network error, temporary outage), the failure is logged and a retry task is persisted in `auth_anonymization_tasks`. The background `AuthAnonymizationRetryWorker` retries with exponential backoff (up to 10 attempts). The `204` response is returned regardless — the account is already deleted from the application's perspective.

No response body.

#### Error Responses

| Status | Error code              | Condition                                                      |
|--------|-------------------------|----------------------------------------------------------------|
| `400`  | `confirmation_required`  | `confirm` is missing or not `true`; malformed JWT subject.     |
| `401`  | `recent_auth_required`  | JWT `iat` is missing or older than 1 hour.                     |
| `403`  | `account_suspended`     | User is `SUSPENDED` or `DEACTIVATED` — returned by `UserStatusFilter` on **other** endpoints, not `DELETE /me`. Even suspended users can delete their own account. |
| `403`  | `account_deleted`       | Returned by `UserStatusFilter` on **other** endpoints when the user is already `DELETED`. `DELETE /me` itself bypasses the filter for idempotent retries. |
| `404`  | —                       | User not found in `app_users`.                                 |
| `500`  | —                       | Unexpected server error during the transactional DB phase. Supabase Auth soft-deletion failures do **not** produce a 500 — they are retried in the background. |

### Idempotency

If the user is already in `DELETED` status, the transactional DB phase is skipped. The Supabase Auth soft-deletion is still attempted (idempotent via `404` handling), and the `userStatus` cache is evicted. If a previous soft-deletion attempt had failed, its retry task is still picked up by the worker independently. Repeated calls always return `204`.

### Path Parameters

None. The user ID is derived from the JWT `sub` claim.

### Notes

- **Shared conversations are preserved** — messages are anonymized (`[deleted]`) but not hard-deleted, so the other participant's conversation history remains intact.
- **Billing, fraud, safety, and legal records are retained** per regulatory requirements; only personal identifiers are anonymized where applicable.
- **Existing JWTs are immediately invalidated** via the `UserStatusFilter` checking the `userStatus` cache, which is evicted before the response is returned. The frontend **must clear all stored tokens and session state** on receiving a `204` from this endpoint, or on receiving a `403 account_deleted` from any other endpoint, and redirect to the login/signup screen.
- The `DataDeletionJob` background job skips users with `DELETED` status, since they have already been fully processed by this endpoint.
- **Re-registration** — after soft-deletion, Supabase releases the original email, phone, Google identity, and Apple identity. The same person can sign up again and will receive a completely new `auth.users` UUID and a fresh `app_users` record; the deleted account is never reconnected automatically.

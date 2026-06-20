# Qaliye / Habesha Drive — Backend Implementation Guide
## Updated Schema, Discovery, Plan Limits, Payments, and Entitlements

## Purpose

This guide explains the updated baseline schema and the Spring Boot behavior required to implement it correctly. It is written for a **fresh database baseline**. Do not apply the baseline file as an `ALTER` script to an existing production database; create ordered Flyway/Liquibase migrations, backfill data, then deploy backend support before enabling new restrictive constraints.

The database is a source of integrity guarantees. Spring Boot remains responsible for API validation, authorization, service logic, transactions, signed URLs, ranking, and user-friendly errors.

---

## 1. Architecture and ownership

### Ownership model

- **Supabase Auth** owns credentials, sessions, OTP, OAuth identity, and JWT issuance.
- **Spring Boot** owns all application reads and writes: profiles, discovery, swipes, matches, billing, moderation, storage operations, and admin operations.
- The mobile client may access only the explicitly allowed chat-message read/realtime path through Supabase.
- All object storage buckets are private. Database rows store only `storage_bucket` and `storage_path`; Spring Boot creates short-lived signed URLs only for authorized DTO responses.

### Backend rules

1. Validate the Supabase JWT for every authenticated API request and resolve `app_users.id`.
2. Reject all business operations when `app_users.status` is not `ACTIVE`.
3. Do not expose precise coordinates, private storage paths, payment-provider payloads, or internal moderation fields.
4. Use one database transaction for each multi-table business operation.
5. Use idempotency keys for externally retried client/payment requests.

---

## 2. User, address, and profile ownership

### Updated data model

- `addresses` does **not** contain `user_id`.
- `app_users.address_id` references `addresses.id`.
- `app_users.address_id` is unique, so one address is assigned to at most one user.
- `profiles` does not contain `address_id`.
- A user has at most one current address.

### Backend behavior

When creating or replacing a user address:

1. Validate the location request.
2. Create or update the `addresses` row.
3. Set `app_users.address_id` to that address in the same transaction.
4. Update the profile/location freshness timestamp as required.

For manual selection:

- Receive `location_place_id`.
- Load trusted country/city/region/centroid from `location_places`.
- Copy trusted values to `addresses`.

For GPS:

- Store only backend-owned coordinates and `accuracy_m`.
- Never return latitude/longitude to another user.
- Return city/region/country and a rounded distance only.

Suggested endpoint:

```http
PUT /api/v1/me/address
```

Manual request:

```json
{
  "source": "MANUAL",
  "locationPlaceId": "uuid"
}
```

GPS request:

```json
{
  "source": "GPS",
  "latitude": 51.5074,
  "longitude": -0.1278,
  "accuracyMeters": 35
}
```

---

## 3. Gender, profile, and discovery preferences

### Updated gender rules

- `profiles.gender` accepts only `MALE` or `FEMALE`.
- `discovery_preferences.interested_in_gender` accepts exactly one value: `MALE` or `FEMALE`.
- Do not accept `ALL`, `OTHER`, null, or multiple target genders.
- `preferred_residency_types` must be a non-empty list drawn from `ETHIOPIA`, `ERITREA`, and `DIASPORA`.

### Onboarding/visibility requirements

Before setting a profile to `is_onboarded = true` and `is_visible = true`, Spring Boot must confirm:

- Account is active.
- Display name, gender, date of birth, relationship intention, and other required profile data are valid.
- Date of birth complies with the 18–120 age rule.
- `interested_in_gender`, age range, and residency preferences are set.
- `app_users.address_id` exists.
- At least one approved, non-deleted primary photo exists.

The database has integrity checks for key states, but backend validation should produce clear error responses before commit.

---

## 4. Discovery feed

### Required endpoint

```http
GET /api/v1/discovery?scope=NEARBY&cursor=<opaque-cursor>
```

Supported scopes:

```text
NEARBY | ETHIOPIA | ERITREA | DIASPORA
```

The selected scope narrows saved preferences. It must never bypass the user’s persisted discovery preferences.

### Batch size and pagination

- Return **10 cards** per fetch.
- Use opaque cursor pagination; do not use offset pagination for the swipe queue.
- Use a stable ordering tuple, for example: ranking score, distance, and target user ID.
- Start prefetching the next batch before the client reaches the end of the current queue.

### Candidate eligibility

A candidate can be returned only when all conditions are true:

1. Candidate is not the requester.
2. Candidate `app_users.status = ACTIVE`.
3. Candidate profile is onboarded and visible.
4. Candidate has an approved, non-deleted primary photo.
5. Candidate has an address through `app_users.address_id`.
6. Candidate gender equals the requester’s `interested_in_gender`.
7. Candidate age is within the requester’s saved min/max age range.
8. Candidate residency type is included in saved preferences and selected scope.
9. Candidate satisfies verified-only preference when enabled.
10. Candidate is within `max_distance_km` for `NEARBY`, unless the product’s long-distance policy permits the selected non-nearby scope.
11. No active block exists in either direction.
12. No active discovery action exists from requester to candidate.
13. No active match exists between the pair.
14. The pair is not excluded because of an earlier unmatch, unless product policy explicitly permits rediscovery.

### Distance and age

- Use `ST_DWithin` for nearby eligibility:

```sql
ST_DWithin(requester.coords, candidate.coords, :maxDistanceKm * 1000)
```

- Use `ST_Distance(...) / 1000.0` only for returned/ranked eligible candidates.
- Compute age from date of birth using `public.calculate_age(date_of_birth)` or equivalent backend projection.
- For efficient SQL filtering, translate min/max age into date-of-birth boundaries so the profile DOB index remains useful.
- Round only for display, for example `12.4 km`; never expose coordinates.

### Discovery DTO essentials

```json
{
  "items": [
    {
      "userId": "uuid",
      "displayName": "Example",
      "age": 29,
      "gender": "FEMALE",
      "residencyType": "DIASPORA",
      "city": "London",
      "countryCode": "GB",
      "distanceKm": 12.4,
      "isVerified": true,
      "bio": "...",
      "relationshipIntention": "MARRIAGE",
      "photos": [
        {
          "id": "uuid",
          "order": 0,
          "isPrimary": true,
          "signedUrl": "https://..."
        }
      ],
      "prompts": [
        {
          "promptId": "uuid",
          "promptText": "...",
          "answerText": "..."
        }
      ]
    }
  ],
  "nextCursor": "opaque-value-or-null"
}
```

---

## 5. Effective plan resolution and quota configuration

This section is the key addition for enforcing daily action limits.

### Why two tables are needed

```text
subscription_plan_limits = the allowed quota for a plan
user_daily_limits        = the amount a user consumed today
user_entitlement_ledger  = separately purchased, granted, expired, refunded, or consumed credits
```

`user_daily_limits` alone does **not** define a limit. It is only a per-user, per-day usage counter.

### Plan configuration tables

`subscription_plans` now has:

- `plan_kind`: `FREE` or `PAID`.
- `billing_interval`: `NONE`, `WEEKLY`, `MONTHLY`, or `YEARLY`.
- `price_minor_units`: currency minor units; do not use `*_cents` terminology.

Rules:

- A `FREE` plan must have `price_minor_units = 0` and `billing_interval = NONE`.
- A `PAID` plan must use `WEEKLY`, `MONTHLY`, or `YEARLY` billing.
- Do **not** create a `user_subscriptions` row for a free user.
- `user_subscriptions.plan_id` may reference only a `PAID` plan.

`subscription_plan_limits` contains one row per plan and limit type:

```text
DAILY_LIKES
DAILY_SUPERLIKES
DAILY_REWINDS
```

`limit_value = NULL` means unlimited.

### Default Free plan

The baseline seeds a global free fallback plan with this initial policy:

```text
DAILY_LIKES       = 50
DAILY_SUPERLIKES  = 1
DAILY_REWINDS     = 1
```

These values are product configuration. Change them before deployment if your business policy differs.

### Effective plan resolution order

For every limited action, resolve the plan in this order:

1. Find the user’s active, current paid subscription.
2. If none exists, find an active `FREE` plan for the user’s address country.
3. If none exists, use the active global `FREE` plan (`country_code = GLOBAL`).
4. Load all three limit rows for the resolved plan.
5. Reject a plan configuration that does not contain all required limit rows.

### Example SQL shape for effective plan

Use a repository query or equivalent service logic. The precise query may vary, but the precedence must be consistent:

```text
active current PAID subscription
    → country-specific FREE fallback
        → GLOBAL FREE fallback
```

### Quota evaluation rules

For a daily action:

- Read the plan’s `limit_value`.
- Read/create the current user’s UTC `user_daily_limits` row.
- Compare the relevant counter against the plan limit.
- `NULL` plan limit means unlimited.
- Increment the daily counter only after the action is valid and actually created.

Example for free likes:

```text
Plan DAILY_LIKES = 50
Current likes_used = 49
49 < 50 → allow a new Like
Increment likes_used to 50

Next request:
50 < 50 → reject with DAILY_LIKE_LIMIT_REACHED
```

### Paid consumable credits versus daily allowance

For `SUPERLIKE` and `REWIND`, your product can allow either source:

```text
free/paid daily allowance remains
OR
an unexpired purchased/granted entitlement credit exists
```

Define one clear precedence rule. Recommended:

1. Use the daily plan allowance first.
2. When the daily allowance is exhausted, consume an eligible credit from `user_entitlement_ledger`.
3. If neither is available, reject the request.

A boost normally consumes a `BOOST_CREDIT` because it is not a daily counter action.

---

## 6. Safe quota enforcement transaction

### UTC daily counter

`user_daily_limits.limit_date` is a UTC date. All backend code must calculate the date in UTC, not device time or server-local time.

### Required transaction sequence

For Like, Super Like, or Rewind:

1. Resolve the effective plan.
2. Load the appropriate configured plan limit.
3. Insert today’s `user_daily_limits` row if missing.
4. Lock that row using `SELECT ... FOR UPDATE` or use an atomic conditional update.
5. Check the daily quota.
6. When required, lock and consume an entitlement credit.
7. Create or reverse the action.
8. Create/end a match if applicable.
9. Increment the counter only if the business action is new and succeeds.
10. Commit all changes together.

### Safe counter-row initialization

```sql
INSERT INTO public.user_daily_limits (user_id, limit_date)
VALUES (:userId, (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::DATE)
ON CONFLICT (user_id, limit_date) DO NOTHING;

SELECT likes_used, super_likes_used, rewinds_used
FROM public.user_daily_limits
WHERE user_id = :userId
  AND limit_date = (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::DATE
FOR UPDATE;
```

### Atomic conditional increment

A conditional update can also protect against concurrent requests:

```sql
UPDATE public.user_daily_limits
SET likes_used = likes_used + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE user_id = :userId
  AND limit_date = (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::DATE
  AND likes_used < :configuredDailyLikeLimit;
```

Interpret the affected-row count:

```text
1 row updated = quota consumed successfully
0 rows updated = quota reached
```

For an unlimited plan (`limit_value IS NULL`), increment the counter for analytics if desired, but do not reject based on the counter.

### Credit-consumption safety

`user_entitlement_ledger` is append-only. Never overwrite a balance.

Before consuming a credit:

1. Lock or otherwise serialize the user’s ledger balance calculation.
2. Sum unexpired ledger entries for that entitlement type.
3. Confirm balance is positive.
4. Insert a negative `CONSUMPTION` entry with an idempotency key.
5. Link it to the related discovery action when applicable.

Do this in the same transaction as the swipe/rewind/boost action. A request must not consume a credit if the action ultimately fails.

---

## 7. Discovery actions: Like, Pass, and Super Like

### Action model

`user_discovery_actions` is historical and idempotent:

- `action_type`: `LIKE`, `PASS`, or `SUPERLIKE`.
- `status`: `ACTIVE` or `REVERSED`.
- `client_action_id`: mobile-generated UUID; unique per acting user.
- Reversed actions remain in history with `reversed_at` and `reversed_reason`.
- Only one active action may exist for an actor/target pair.
- A new action can be inserted after an earlier action has been reversed.

### Required endpoint

```http
POST /api/v1/discovery/actions
```

```json
{
  "targetUserId": "uuid",
  "actionType": "LIKE",
  "clientActionId": "uuid"
}
```

### Swipe transaction

Run atomically:

1. Authenticate the actor and verify active status.
2. Validate the target remains eligible for discovery.
3. Check `(actor_user_id, client_action_id)`.
    - Same ID already stored: return the original result; do not charge again.
    - Different ID but an active action already exists for the pair: return `409 ACTIVE_ACTION_ALREADY_EXISTS`.
4. Resolve and enforce daily plan quota/credit rules.
5. Insert the discovery action.
6. Increment the applicable daily counter and/or consume credit only because the action is newly inserted.
7. For `LIKE` or `SUPERLIKE`, search for a reciprocal active `LIKE` or `SUPERLIKE`.
8. Create a match when reciprocal interest exists.

### Limit mapping

```text
LIKE       → DAILY_LIKES
SUPERLIKE  → DAILY_SUPERLIKES and possibly SUPERLIKE_CREDIT
PASS       → no quota by default
REWIND     → DAILY_REWINDS and possibly REWIND_CREDIT
BOOST      → BOOST_CREDIT; not user_daily_limits
```

---

## 8. Match creation

### Match lifecycle

Matches are historical records:

- `status`: `ACTIVE` or `ENDED`.
- `end_reason`: `USER_UNMATCH`, `CANCELLED_BY_REWIND`, `BLOCKED`, or `ADMIN_ACTION`.
- One active match can exist for a pair.
- Ended rows remain for audit and allow a future rematch if product policy permits it.
- The match stores both matching discovery-action IDs and the `created_by_action_id`.
- `rewind_eligible_until` defines a short cancellation window, for example 60 seconds after the match.
- `first_message_at` permanently closes ordinary match-rewind eligibility.

### Mutual-like transaction

When a reciprocal Like/Super Like exists:

1. Sort user UUIDs so `user_one_id < user_two_id`.
2. Associate each user’s active like action with the correct match action field.
3. Set `created_by_action_id` to the latest action that triggered matching.
4. Insert one `ACTIVE` match with `rewind_eligible_until`.
5. Return `matched: true`, `matchId`, and the rewind deadline.
6. Treat a unique-active-match conflict as an idempotent result.

---

## 9. Rewind

### Required endpoint

```http
POST /api/v1/discovery/rewind
```

### General rule

Rewind does not delete history. It reverses the user’s latest eligible active action. Repeated rewinds walk backward through the user’s uninterrupted eligible swipe history.

### Transaction behavior

1. Resolve the effective plan and `DAILY_REWINDS` limit.
2. Find and lock the current user’s latest reversible active action.
3. Enforce daily rewind quota or consume a `REWIND_CREDIT`.
4. If the action did not create an active match:

```text
status = REVERSED
reversed_at = now()
reversed_reason = USER_REWIND
```

5. If the action created an active match, permit rewind only when:
    - `now() <= rewind_eligible_until`; and
    - `first_message_at IS NULL`.
6. In the matched case, set:

```text
match.status = ENDED
match.end_reason = CANCELLED_BY_REWIND
match.ended_by_user_id = current user
match.ended_at = now()
```

7. Preserve the other user’s original active Like.
8. Increase `rewinds_used` only when rewind succeeds.
9. Return the restored profile card when it still passes discovery eligibility.

### Mutual-like rewind example

```text
B likes A              → remains ACTIVE
A likes B              → becomes REVERSED
Match between A and B  → ENDED / CANCELLED_BY_REWIND
```

Do not allow ordinary rewind after the grace window or after either user sends a message. Use Unmatch for established matches.

---

## 10. Unmatch and block

### Unmatch

```http
DELETE /api/v1/matches/{matchId}
```

- End the active match with `end_reason = USER_UNMATCH`.
- Do not delete or reverse discovery actions.
- Normally exclude the pair from future Discovery. Only change this if product policy explicitly permits rediscovery.

### Block

```http
POST /api/v1/users/{targetUserId}/block
```

- Create or reactivate a directional block.
- End an active match for the pair with `end_reason = BLOCKED` in the same transaction.
- Add an audit-log entry.
- Exclude active blocks in both directions from Discovery and profile access.
- Stop messaging immediately.

---

## 11. Messaging

### Rules

- Only active match participants can send messages.
- Message creation is idempotent through `(sender_user_id, client_message_id)`.
- `TEXT`, `ICEBREAKER`, and `PROMPT_REPLY` require non-empty text.
- `IMAGE` and `VOICE` require a private storage bucket/path.
- First message sets `matches.first_message_at`.
- Each message updates `matches.last_message_at`.
- Direct Supabase read/realtime is limited to approved, non-deleted messages for an active match participant.

### Backend requirements

- Use Spring Boot for all message creates/updates/deletes.
- Return signed media URLs only to authorized match participants.
- Soft-delete messages.
- Do not permit match rewind after `first_message_at` is set.

---

## 12. Photos, verification, and visibility

- Store only private photo bucket/path, never permanent public image URLs.
- Return only approved, non-deleted photos in profile/discovery DTOs.
- A rejected photo cannot be primary.
- One active primary photo and one active photo per order are allowed per profile.
- `profiles.is_verified` is a backend-maintained cached value; detailed source records remain in `user_verifications`.
- Before making a profile visible, validate approved primary photo, address, preferences, and required profile data.

---

## 13. Reports, audit, account status, and deletion

- `app_users.status` supports `ACTIVE`, `SUSPENDED`, `DEACTIVATED`, and `BANNED`.
- Reports require a reported user; reporter can be null only for automated reports.
- `audit_log` is append-only and includes `request_id`.
- Prefer soft deletion: set account to `DEACTIVATED`, set `deleted_at`, hide the profile, and revoke sessions.
- Do not delete Auth/app-user data until a separately defined retention/anonymization process runs.
- Admin/moderator actions must check role and write audit entries.

---

## 14. Payments, payment events, entitlements, and boosts

### Why `payment_events` and `transactions` both exist

```text
payment_events = what the external provider told the application
transactions   = the app’s payment/business record
```

One transaction can receive multiple provider events, especially for subscriptions. Store webhooks in `payment_events` for audit and idempotency; update/create the related application transaction or subscription according to the validated provider event.

### Payment webhook rules

- Deduplicate using `(provider, provider_event_id)`.
- Verify provider signatures before processing the webhook.
- Store the raw provider payload for reconciliation.
- Process transaction/subscription updates and entitlement grants atomically where possible.
- Do not grant entitlement twice when the same event is retried.

### Entitlement ledger

`user_entitlement_ledger` is the append-only record for:

```text
SUPERLIKE_CREDIT
REWIND_CREDIT
BOOST_CREDIT
PREMIUM_ACCESS
```

Typical entries:

```text
Purchase of three Super Likes → +3 SUPERLIKE_CREDIT / PURCHASE
User sends Super Like         → -1 SUPERLIKE_CREDIT / CONSUMPTION
Refund                         → negative adjustment / REFUND
Admin goodwill grant           → positive adjustment / ADMIN_GRANT
Expired promotion              → negative adjustment / EXPIRY
```

Derive the current balance from valid, unexpired entries. Do not try to infer a consumable balance only from `transactions`.

### What a Boost is

A boost temporarily improves how prominently a user appears in **other users’** eligible Discovery queues. It does not bypass safety, gender, age, block, visibility, residency, or distance rules.

Example policy:

```text
Normal user → standard ranking
Boosted user → ranking priority for eligible viewers while boost is active
```

### Boost activation

`active_boosts` stores boost windows and retains history. The exclusion constraint prevents overlapping boost periods for one user.

Recommended flow:

1. Validate the user has a usable `BOOST_CREDIT` or has completed a direct `PROFILE_BOOST` purchase.
2. Consume one `BOOST_CREDIT` when using a credit-based model.
3. Create an `active_boosts` row with `started_at` and `expires_at`.
4. If a boost is already active, either reject, extend it, or schedule the new start after the current expiry. Choose and document one product rule.
5. Use the active boost as one factor in Discovery ranking only after all eligibility filters are satisfied.

For a direct `PROFILE_BOOST` transaction, `active_boosts.transaction_id` should reference that same user’s completed transaction. For a consumable-pack credit, the row may link to the originating completed transaction where known or leave `transaction_id` null and rely on the ledger consumption entry.

---

## 15. Suggested Spring Boot module ownership

```text
security/
  SupabaseJwtAuthenticationFilter
  CurrentUserResolver

onboarding/
  ProfileService
  AddressService
  DiscoveryPreferenceService
  PhotoService

discovery/
  DiscoveryQueryRepository
  DiscoveryService
  EffectivePlanService
  DailyLimitService
  EntitlementService
  DiscoveryActionService
  RewindService

matching/
  MatchService
  UnmatchService
  BlockService

messaging/
  MessageService
  SignedMediaService

billing/
  PaymentWebhookService
  SubscriptionService
  PaymentTransactionService
  BoostService

moderation/
  ReportService
  VerificationService
  AuditService
```

Use `@Transactional` on every service method that changes more than one table.

---

## 16. Existing database migration checklist

1. Add `app_users.address_id`, backfill from prior address ownership, validate one-to-one assignment, then remove obsolete `profiles.address_id` and `addresses.user_id` only after verification.
2. Resolve unsupported gender data before applying the strict `MALE`/`FEMALE` constraint.
3. Add discovery action status/reversal/idempotency fields; backfill prior actions as `ACTIVE`; replace permanent action uniqueness with the partial active-action index.
4. Convert old match statuses to `ACTIVE`/`ENDED`; backfill known end reasons; replace permanent pair uniqueness with a partial active-match index.
5. Add `plan_kind` and `subscription_plan_limits`; seed the global Free plan and all required quota rows; validate all paid plans have all limit rows before use.
6. Ensure `user_subscriptions` only references paid plans; do not create free-user subscriptions.
7. Backfill storage paths before removing legacy image/media URL columns.
8. Deploy backend behavior before enabling restrictive triggers and `NOT NULL` constraints.
9. Test concurrency and retries: duplicate swipes, limit-boundary swipes, entitlement consumption, mutual likes, repeated rewind, match rewind, unmatch, block, and payment-webhook retries.

---

## 17. Minimum test scenarios

- User cannot become visible without address, preferences, and approved primary photo.
- Discovery returns at most ten eligible cards and never returns exact coordinates.
- Discovery respects gender, age, residency, distance, verification, blocks, previous actions, and active matches.
- Same `clientActionId` retry does not duplicate a swipe or consume quota twice.
- Free plan with 50 likes permits actions 1–50 and rejects action 51.
- Unlimited plan does not reject based on usage counters.
- Country-specific Free plan is selected before the Global Free plan.
- Super Like uses allowance first, then credit only according to the documented product rule.
- Rewind uses allowance/credit only when reversal succeeds.
- Mutual likes create one active match only.
- Rewind of latest pass restores the profile.
- Rewind of latest matching like cancels only a new, message-free match inside the grace period.
- Rewind after grace expiry or first message is rejected.
- Unmatch preserves swipe history.
- Block ends active match and stops messaging.
- Boost cannot bypass discovery eligibility filters.
- Two overlapping boosts for the same user cannot be created.
- Payment-webhook retries do not create duplicate transactions, ledger grants, or subscriptions.

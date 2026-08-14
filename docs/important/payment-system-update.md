# Credit System, Subscription, Pre-Match Messaging, Identity Verification, and Payment System Updates

## 1. Payment and Credit Model

The payment system should use a **hybrid Subscription + Credits model**.

A user may obtain value through:

1. **Subscription**

   * Provides subscription-specific benefits and action limits/costs.
   * May include a recurring credit allowance.
   * The included credit allowance is added directly to the user's central credit balance.

2. **Credits**

   * Credits are the central consumable currency of the platform.
   * Users can optionally purchase credit packages.
   * Credits can be used for actions/features that are not free under the user's current subscription benefits.
   * Credit purchases may be available to both subscribers and non-subscribers, depending on country configuration.

The system must maintain **one central credit balance per user**.

There should not be separate spendable credit balances for actions such as Super Like, Rewind, Boost, Incognito Mode, Change Address, or See Who Liked You.

### 1.1 Overall Model

```text
                         USER
                          │
             ┌────────────┴────────────┐
             │                         │
       SUBSCRIPTION                CREDITS
             │                         │
   Subscription benefits       Credit purchases
             │                         │
   Included credit allowance          │
             │                         │
             └────────────┬────────────┘
                          ↓
                  USER CREDIT BALANCE
                          │
                          ↓
                     USER ACTION
                          │
                          ↓
              ACTION COST / SUBSCRIPTION RULE
                          │
                  ┌───────┴───────┐
                  ↓               ↓
          Subscription benefit   Credit cost
             available?           required?
                  │               │
                 YES             YES
                  │               │
          Perform action     Deduct credits
```

The important distinction is:

* **Subscription determines the user's benefits and action rules.**
* **Credits provide a central consumable balance.**
* **A subscription may grant credits into that same central balance.**
* **Credit purchases add additional credits to that same central balance.**

---

# 2. Centralised Credit System

There should be one central credit balance for each user.

### 2.1 Credit Balance

Add a table preferably named:

```text
user_credit_balances
```

It should maintain the user's current available credit balance.

Example:

| Column     | Description                      |
| ---------- | -------------------------------- |
| id         | UUID                             |
| user_id    | User reference                   |
| balance    | Current available credit balance |
| created_at | Creation timestamp               |
| updated_at | Last update timestamp            |

Recommended datatype for `balance`:

```text
BIGINT
```

There should be a unique constraint on `user_id`.

The balance must never become negative.

All credit balance changes must be performed transactionally.

The stored balance should be treated as the efficient current-state representation of the user's credits. The credit ledger and credit lots provide the audit and allocation history.

---

# 3. Credit Sources

Credits can be added to a user's balance from multiple sources.

Supported sources should include at least:

```text
SUBSCRIPTION_ALLOWANCE
CREDIT_PURCHASE
PROMOTION
ADMIN_ADJUSTMENT
```

Credits from all sources are available through the same central `user_credit_balances` balance.

However, every credit grant must remain distinguishable by its source because the source determines its expiration and allocation behaviour.

For example:

```text
Subscription purchased
        ↓
+10,000 subscription allowance credits
        ↓
user_credit_balances
        ↓
10,000 credits
```

A later credit purchase:

```text
+5,000 purchased credits
        ↓
user_credit_balances
        ↓
15,000 credits
```

The system must not maintain separate spendable balances such as:

```text
subscription_credits
purchased_credits
boost_credits
super_like_credits
```

There is one central spendable balance.

The distinction between credit sources is maintained through credit lots and ledger records.

---

# 4. Credit Ledger

The existing `user_entitlement_ledger` should be reviewed and adapted as the audit ledger for credit movements.

A suitable name is:

```text
user_credit_ledger
```

The ledger should record every credit addition, consumption, expiration, refund, reversal, and other relevant credit movement.

Suggested columns include:

```text
id
user_id
transaction_type
amount
balance_after
source_type
source_id
action_type
idempotency_key
created_at
```

Recommended datatype for `amount`:

```text
BIGINT
```

`amount` should be signed:

```text
positive  = credits added
negative  = credits consumed/removed
```

Examples:

```text
+10,000  SUBSCRIPTION_ALLOWANCE
+5,000   CREDIT_PURCHASE
-100     ACTION_CONSUMPTION
+100     REFUND
-8,000   EXPIRATION
```

The `balance_after` column should contain the user's central credit balance immediately after the transaction.

### 4.1 Transaction Types

The following transaction types should be supported:

```text
SUBSCRIPTION_ALLOWANCE
CREDIT_PURCHASE
ACTION_CONSUMPTION
REFUND
EXPIRATION
PROMOTION
ADMIN_ADJUSTMENT
REVERSAL
```

For `ACTION_CONSUMPTION`, `action_type` should identify the consumed action/feature.

Examples include:

```text
LIKE
SUPER_LIKE
BOOST
REWIND
SUPER_MESSAGE
IMAGE_MESSAGE
VOICE_MESSAGE
RETURN_PASSED_PROFILE
INCOGNITO_MODE
CHANGE_ADDRESS
SEE_WHO_LIKED_YOU
```

The ledger should be treated as an **immutable audit/history record**.

Historical ledger entries must never be modified or deleted to correct a transaction.

Corrections should be represented by compensating transactions such as `REFUND` or `REVERSAL`.

---

# 5. Credit Lots

The credit-lot mechanism should be used to track the source, remaining quantity, and expiration of **every credit grant**, including purchased credits.

Use:

```text
user_entitlement_credit_lots
```

Suggested columns:

| Column                 | Description                                      |
| ---------------------- | ------------------------------------------------ |
| id                     | UUID                                             |
| user_id                | User reference                                   |
| credit_source_type     | Source of the credit lot                         |
| source_ledger_entry_id | Ledger entry that granted the credits            |
| quantity_granted       | Total credits granted by this lot                |
| quantity_remaining     | Credits from this lot that remain available      |
| expires_at             | Expiration timestamp; `NULL` means never expires |
| created_at             | Creation timestamp                               |

`quantity_granted` and `quantity_remaining` should use:

```text
BIGINT
```

### 5.1 Credit Source Types

Supported credit source types should include:

```text
SUBSCRIPTION_ALLOWANCE
CREDIT_PURCHASE
PROMOTION
ADMIN_ADJUSTMENT
```

The expiration behaviour is:

```text
SUBSCRIPTION_ALLOWANCE → expires according to allowance policy
CREDIT_PURCHASE        → never expires
PROMOTION              → according to configured promotion expiry
ADMIN_ADJUSTMENT       → according to explicitly configured expiry
```

A non-expiring credit lot has:

```text
expires_at = NULL
```

Therefore, purchased credits **do have a credit lot**, but the lot does not expire.

This provides one consistent mechanism for tracking all credit sources.

---

# 6. Credit Lot Consumption / Allocation

When credits are consumed, the system must identify exactly which credit lots supplied the consumed credits.

Create:

```text
user_credit_lot_consumptions
```

Suggested columns:

```text
id
user_id
credit_lot_id
ledger_entry_id
amount
created_at
```

This table records the allocation of a credit consumption across one or more credit lots.

For example:

```text
Subscription lot:
50 credits remaining

Purchased lot:
100 credits remaining

Action cost:
75 credits
```

The system consumes:

```text
Subscription lot → 50
Purchased lot    → 25
```

The records become:

```text
ACTION_CONSUMPTION
        │
        ├── Subscription lot → 50
        └── Purchased lot    → 25
```

This allows the system to reconstruct exactly which credits were consumed.

It also makes expiration, refund, reconciliation, and auditing significantly more reliable.

---

# 7. Credit Expiration

The system must explicitly define the expiration policy for credits.

The rule is:

> **Purchased credits do not expire. Non-purchased credits expire according to their individual credit lot's `expires_at` value.**

Examples:

```text
Subscription allowance
→ expires according to the subscription allowance policy

Promotional credits
→ expire according to the configured promotion expiry

Purchased credits
→ do not expire

Admin-adjusted credits
→ expire only if an expiration date was explicitly configured
```

The system must never simply subtract an arbitrary amount from:

```text
user_credit_balances.balance
```

when credits expire.

Instead, expiration must be determined from the applicable credit lots.

When a credit lot expires:

```text
quantity_remaining → 0
```

The corresponding amount must be removed from:

```text
user_credit_balances.balance
```

and an `EXPIRATION` ledger transaction must be created.

The expiration process must be transactional.

---

# 8. Credit Consumption Order

When credits are consumed, the system should use a deterministic allocation order.

A suitable default is:

```text
1. Expiring non-purchased credits with the earliest expires_at
2. Later-expiring non-purchased credits
3. Non-expiring purchased/admin credits
```

More generally:

```text
ORDER BY
    expires_at IS NULL,
    expires_at,
    created_at,
    id
```

This prevents credits that are close to expiration from being unnecessarily left unused while non-expiring credits are consumed.

Example:

```text
Subscription lot:
10,000 credits
expires_at = 2026-09-01

Purchased lot:
5,000 credits
expires_at = NULL

Current balance:
15,000
```

If the user spends 2,000:

```text
Subscription lot:
10,000 → 8,000

Purchased lot:
5,000 → 5,000

Central balance:
15,000 → 13,000
```

If the subscription lot later expires:

```text
Subscription lot:
8,000 → 0

Purchased lot:
5,000 remain

Central balance:
13,000 → 5,000
```

The expiration transaction must be recorded in the credit ledger.

---

# 9. Credit Balance Reconciliation

The system maintains:

```text
user_credit_balances
```

as the current balance and:

```text
user_credit_ledger
```

as the transaction history.

The system should maintain the invariant that the stored balance is consistent with the effective ledger movements.

Reconciliation is **on-demand only**. There is no automatic batch process or scheduled job.

An administrator or system operator may trigger a reconciliation check for a specific user on demand.

The on-demand check should compare:

```text
stored balance != ledger-derived balance
```

Both the `user_credit_balances` and the `user_credit_ledger` must be retained as permanent historical records. The ledger is never purged.

Any discrepancy should be treated as a system integrity issue and investigated rather than silently corrected.

Administrative corrections should create explicit adjustment/reversal ledger entries.

---

# 10. Subscription Credit Allowance

Subscription products may include a credit allowance.

Add a column to:

```text
subscription_products
```

```text
included_credits BIGINT NOT NULL DEFAULT 0
```

Example:

```text
Monthly Premium
included_credits = 10,000
```

When a subscription product is purchased or renewed, the configured credit allowance should be added to the user's central credit balance.

The system must:

1. Create a credit ledger entry.
2. Create the corresponding credit lot.
3. Add the credits to `user_credit_balances`.

The credit lot must contain the appropriate `expires_at`.

The subscription itself must not create a separate subscription credit balance.

---

# 11. Subscription Allowance Expiration

Subscription allowances are represented as individual credit lots.

Each allowance grant should have its own expiration date.

For example:

```text
August subscription allowance:
10,000 credits
expires_at = 2026-09-01

September subscription allowance:
10,000 credits
expires_at = 2026-10-01
```

If the user does not consume the August allowance, it expires according to the August lot's expiration date.

This ensures that each subscription allowance can be independently identified and expired.

---

# 12. Subscription Benefits and Action Costs

The:

```text
subscription_plan_limit_and_cost
```

table defines how a subscription plan affects the cost and availability of each action/feature.

It should contain:

```text
subscription_plan_id
feature_action_id
member_credit_cost
actual_credit_cost
limit_value
period_type
apply_credit_after_limit
created_at
updated_at
```

The configuration applies to both actions and features that can incur credit costs.

Examples include:

```text
LIKE
SUPER_LIKE
BOOST
REWIND
SUPER_MESSAGE
RETURN_PASSED_PROFILE
INCOGNITO_MODE
CHANGE_ADDRESS
SEE_WHO_LIKED_YOU
```

---

# 13. `member_credit_cost`

Defines the credit cost for a user while the subscription-specific rule applies.

If:

```text
member_credit_cost = 0
```

the action/feature is free while the subscription-specific allowance applies.

If:

```text
member_credit_cost > 0
```

the user must spend that number of credits when using the action/feature under the subscription rule.

---

# 14. `actual_credit_cost`

Defines the normal credit cost that applies when the subscription-specific free/discounted allowance is unavailable.

For example:

```text
member_credit_cost = 0
actual_credit_cost = 100
limit_value = 5
apply_credit_after_limit = true
```

means:

```text
First 5 uses:
    0 credits

After the limit:
    100 credits per use
```

However:

```text
member_credit_cost = 0
actual_credit_cost = 0
limit_value = 5
apply_credit_after_limit = true
```

means the action remains free after the subscription-specific limit is exhausted.

Therefore:

> `apply_credit_after_limit = true` means that the system should continue to allow the action after the subscription-specific limit is exhausted and use `actual_credit_cost` as the applicable cost.

It does **not** imply that `actual_credit_cost` must be greater than zero.

---

# 15. `limit_value`

Defines the number of times the subscription-specific rule can be used during the configured period.

```text
NULL     = unlimited
0        = zero subscription-specific allowance
positive = limited number of uses
```

When:

```text
limit_value = 0
```

there are zero free/subscription-specific uses.

However, if:

```text
apply_credit_after_limit = true
```

the action may still be performed using:

```text
actual_credit_cost
```

For example:

```text
member_credit_cost = 0
actual_credit_cost = 100
limit_value = 0
apply_credit_after_limit = true
```

means:

```text
No free uses
→ immediately charge 100 credits per use
```

If:

```text
limit_value = NULL
```

the subscription-specific rule is unlimited.

In that case `apply_credit_after_limit` has no practical effect because the limit can never be exhausted.

---

# 16. `period_type`

Supported values:

```text
DAY
MONTH
BILLING_CYCLE
```

### `DAY`

Represents a calendar day.

The period should be determined using the appropriate user/application timezone.

### `MONTH`

Represents a **calendar month**.

For example:

```text
2026-08-01 → 2026-08-31
```

It does **not** represent the user's subscription billing period.

### `BILLING_CYCLE`

Represents the user's current subscription billing period.

For example:

```text
Subscription starts:
2026-08-14

Billing cycle:
2026-08-14 → 2026-09-13
```

This is different from:

```text
MONTH:
2026-08-01 → 2026-08-31
```

### `BILLING_CYCLE` is only valid for paid plans

`BILLING_CYCLE` requires an active paid subscription with a defined billing period. The `FREE` plan has no billing period.

Therefore:

```text
FREE plan → only DAY and MONTH are valid period_type values
PAID plan → DAY, MONTH, and BILLING_CYCLE are valid
```

If a `FREE` plan rule is configured with `period_type = BILLING_CYCLE`, the system must reject it at configuration time.

---

# 17. `apply_credit_after_limit`

Determines what happens after `limit_value` is exhausted.

If `true`:

```text
Subscription-specific limit exhausted
        ↓
Use actual_credit_cost
        ↓
If actual_credit_cost > 0
        → deduct credits
        ↓
Perform action
```

If:

```text
actual_credit_cost = 0
```

the action remains free.

If `false`:

```text
Subscription-specific limit exhausted
        ↓
Action unavailable
```

The system must not automatically interpret:

```text
apply_credit_after_limit = true
```

as requiring a positive credit cost.

---

# 18. Examples of Subscription Action Rules

### Example 1 — Unlimited Likes

```text
LIKE

member_credit_cost = 0
actual_credit_cost = 1
limit_value = NULL
period_type = DAY
apply_credit_after_limit = true
```

Meaning:

```text
Subscriber → Likes are free and unlimited
Non-subscriber → 1 credit per Like if credits are enabled
```

### Example 2 — Limited Free Likes

```text
LIKE

member_credit_cost = 0
actual_credit_cost = 1
limit_value = 100
period_type = DAY
apply_credit_after_limit = true
```

Meaning:

```text
First 100 Likes/day → free
After 100 → 1 credit per Like
```

### Example 3 — Limited Free Super Likes

```text
SUPER_LIKE

member_credit_cost = 0
actual_credit_cost = 100
limit_value = 5
period_type = MONTH
apply_credit_after_limit = true
```

Meaning:

```text
First 5 Super Likes/calendar month → free
After 5 → 100 credits each
```

### Example 4 — Feature with a Limited Free Allowance

```text
SEE_WHO_LIKED_YOU

member_credit_cost = 0
actual_credit_cost = 100
limit_value = 1
period_type = MONTH
apply_credit_after_limit = true
```

Meaning:

```text
First reveal/access → free
After the limit → 100 credits
```

### Example 5 — Feature Always Costing Credits

```text
INCOGNITO_MODE

member_credit_cost = 500
actual_credit_cost = 500
limit_value = NULL
```

Meaning:

```text
Subscriber → 500 credits
Non-subscriber → 500 credits
```

---

# 19. Feature Actions

Create or retain:

```text
feature_actions
```

Suggested columns:

| Column | Description                |
| ------ | -------------------------- |
| id     | UUID                       |
| code   | Unique action/feature code |
| name   | Display name               |
| type   | `ACTION` or `FEATURE`      |

Examples:

| Code                  | Name                  | Type    |
| --------------------- | --------------------- | ------- |
| LIKE                  | Like                  | ACTION  |
| SUPER_LIKE            | Super Like            | ACTION  |
| BOOST                 | Boost                 | ACTION  |
| REWIND                | Rewind                | ACTION  |
| SUPER_MESSAGE         | Super Message         | ACTION  |
| IMAGE_MESSAGE         | Image Message         | ACTION  |
| VOICE_MESSAGE         | Voice Message         | ACTION  |
| RETURN_PASSED_PROFILE | Return Passed Profile | ACTION  |
| CHANGE_ADDRESS        | Change Address        | ACTION  |
| SEE_WHO_LIKED_YOU     | See Who Liked You     | FEATURE |
| INCOGNITO_MODE        | Incognito Mode        | FEATURE |

Even though `SEE_WHO_LIKED_YOU`, `INCOGNITO_MODE`, and `CHANGE_ADDRESS` may conceptually be features rather than one-time actions, they must still be represented in `feature_actions` because their access/use may incur a credit cost.

The `type` value is primarily used to distinguish their runtime behaviour.

---

# 20. User Action Limit Tracker

The existing daily-limit tables must be **dropped** and replaced with:

```text
user_action_limits_tracker
```

The following existing tables must be removed:

```text
user_daily_limits
user_quota_usage
```

All code referencing these tables must be migrated to use `user_action_limits_tracker`.

This table tracks usage against subscription-specific action/feature limits.

Suggested columns:

```text
id
user_id
subscription_plan_limit_and_cost_id
used_count
period_start_date
period_end_date
created_at
updated_at
```

A record represents one user, one subscription rule, and one usage period.

Example:

```text
User
Subscription Plan
LIKE
100/day
```

may have:

```text
used_count = 42
period_start_date = 2026-08-14
period_end_date = 2026-08-14
```

The tracker must be reset or recreated according to `period_type`.

For:

```text
DAY
```

the period is a calendar day.

For:

```text
MONTH
```

the period is a calendar month.

For:

```text
BILLING_CYCLE
```

the period corresponds to the user's current subscription billing period.

The tracker must have a unique constraint preventing duplicate records for the same:

```text
user_id
subscription_plan_limit_and_cost_id
period
```

---

# 21. Action-Limit Concurrency

Updating `used_count` must be concurrency-safe.

For example, if:

```text
limit_value = 5
used_count = 4
```

and two requests arrive simultaneously, both requests must not be allowed to consume the fifth allowance.

The implementation must use transactional locking or an equivalent atomic update strategy.

The action-limit check and allowance consumption must happen atomically with the action authorization.

---

# 22. Action Consumption Logic

Whenever a user attempts an action or credit-based feature, the backend must determine how it should be paid for.

The general logic is:

```text
1. Determine the feature_action.

2. Determine the user's country.

3. Determine whether credit usage is enabled for the user's country.

4. Determine whether the user has an active subscription.

5. If the user has an active subscription:
       use the subscription_plan_limit_and_cost
       configuration for the user's subscription plan.

6. If the user has no active subscription:
       use the FREE subscription plan configuration.

7. If the subscription rule has a limit:
       determine whether the user has remaining
       subscription allowance.

8. If the subscription allowance is available:
       use member_credit_cost.

9. If the subscription allowance is exhausted:
       if apply_credit_after_limit = true:
           use actual_credit_cost.
       otherwise:
           reject the action.

10. If a credit cost is required:
       verify that credits are enabled for the user's country.

11. If credit cost > 0:
       verify sufficient credit balance.

12. If sufficient:
       deduct credits transactionally.
       allocate the deduction across credit lots.
       update user_credit_balances.
       record ACTION_CONSUMPTION.
       record credit-lot consumption allocations.

13. If credit cost = 0:
       no credit deduction is performed.

14. Update the user's subscription action-limit tracker
    when the subscription rule has a limit.

15. Perform the requested action.
```

The credit deduction, credit-lot allocation, ledger entry, action-limit update, and action authorization must be transactionally safe to prevent concurrent requests from spending the same credits or allowance.

---

# 23. Free Subscription Plan

The system should use:

```text
subscription_plans.plan_code = FREE
```

as the default subscription plan configuration for users who do not have an active paid subscription.

This allows the same:

```text
subscription_plan_limit_and_cost
```

configuration mechanism to determine free-user action costs and limits.

For example:

```text
FREE
SEE_WHO_LIKED_YOU
member_credit_cost = 100
actual_credit_cost = 100
limit_value = NULL
```

means a free user must spend 100 credits to reveal a Like.

---

# 24. Country Settings

Add:

```text
country_settings
```

to manage country-level monetisation and onboarding configuration.

Suggested fields include:

```text
id
country_code
subscription_enabled
credits_enabled
identity_verification_required
created_at
updated_at
```

Example:

| Country | Subscription | Credits | Identity Verification |
| ------- | -----------: | ------: | --------------------: |
| ET      |        false |    true |                 false |
| US      |         true |    true |                  true |
| GB      |         true |    true |                  true |
| GLOBAL  |         true |    true |                 false |

`GLOBAL` is a reserved fallback configuration value and is not an actual country.

### Configuration Rules

1. A country-specific record takes precedence over `GLOBAL`.
2. If no country-specific record exists, use `GLOBAL`.
3. `country_code` must be unique.
4. A `GLOBAL` fallback record must exist.
5. `subscription_enabled` determines whether users in that country may purchase a subscription.
6. `credits_enabled` determines whether users in that country may purchase/use credit-based actions.
7. `identity_verification_required` determines whether identity verification is required during onboarding.

If the business later requires credit purchasing and credit usage to be controlled independently, these should be represented by separate configuration fields.

This allows:

### Subscription + Credits

```text
Subscription
+
Credit purchases
```

### Credits only

```text
Credit purchases
```

### Subscription only

```text
Subscription
```

---

# 25. Payment Platform

The `platform` column in:

```text
payment_offers
payment_methods
```

should use:

```text
WEB
MOBILE
ALL
```

`ANDROID` and `IOS` should not be used as backend platform values.

When:

```text
platform = MOBILE
```

the backend treats the payment platform generically as mobile.

The client determines whether it is operating on:

```text
ANDROID
```

or:

```text
IOS
```

and handles platform-specific implementation accordingly.

Android and iOS use the same backend mobile offers/products, so separate backend offer/product definitions are not required merely because the client platform differs.

Therefore:

```text
Backend:
MOBILE

Android client:
ANDROID-specific implementation

iOS client:
IOS-specific implementation
```

The platform model should remain separate from the payment provider.

For example:

```text
WEB    + STRIPE
MOBILE + APPLE_IAP
MOBILE + GOOGLE_PLAY
```

The existing offer/product structures should continue to define the applicable payment provider and product information.

The backend should not require separate `ANDROID` and `IOS` platform values unless there is a specific future backend requirement to distinguish them.

### Index Redesign for `MOBILE` Platform

The existing partial unique index:

```text
unique_active_online_payment_per_market
ON payment_methods(country_code, platform)
WHERE payment_channel = 'ONLINE_PAYMENT' AND is_active = TRUE
```

was designed for separate `ANDROID` and `IOS` platforms, where each market had at most one active online payment method per platform. With the migration to `MOBILE`, both `apple` and `google` online payment methods would share the same `(country_code, platform)` key, violating this constraint.

This index must be **redesigned** to allow multiple active online payment methods per market on `MOBILE`. The recommended approach is to drop the existing index and replace it with a unique constraint on `(country_code, platform, method_code)` for active online payment methods:

```sql
DROP INDEX IF EXISTS unique_active_online_payment_per_market;

CREATE UNIQUE INDEX unique_active_online_payment_method_per_market
    ON payment_methods(country_code, platform, method_code)
    WHERE payment_channel = 'ONLINE_PAYMENT' AND is_active = TRUE;
```

This ensures that the same method is not duplicated per market, while allowing both `apple` and `google` to coexist as active online payment methods on `MOBILE`.

### Migration of Existing Data

All existing `platform = 'ANDROID'` rows in `payment_offers` and `payment_methods` must be updated to `platform = 'MOBILE'`.

All existing `platform = 'IOS'` rows must also be updated to `platform = 'MOBILE'`.

The `platform` CHECK constraint on both tables must be updated to:

```text
platform IN ('WEB', 'MOBILE', 'ALL')
```

The market-matching trigger `validate_payment_order_market` must be reviewed to ensure it continues to function correctly with the new platform values.

Review all payment-related tables and services to ensure platform semantics are consistent.

---

# 26. Subscription Products

The existing subscription system should be retained.

```text
subscription_plans
subscription_products
```

should remain, with the necessary changes to support the new credit allowance.

The subscription product should contain:

```text
included_credits
```

Subscription pricing and billing information should remain in the existing product/offer/payment structures rather than being unnecessarily duplicated.

Subscription action benefits should not be copied into individual subscription purchases.

They are configured centrally in:

```text
subscription_plan_limit_and_cost
```

---

# 27. Subscription Purchase Flow

When a user purchases a subscription:

```text
Payment succeeds
       ↓
Subscription activated
       ↓
Grant included credit allowance
       ↓
Create credit ledger entry
       ↓
Create credit lot with expiration
       ↓
Update user_credit_balances
```

The subscription purchase does not need to "apply subscription benefits" to the user.

Subscription benefits such as:

```text
Unlimited Likes
5 free Super Likes
2 free Boosts
See Who Liked You
```

are configured through:

```text
subscription_plan_limit_and_cost
```

Those rules are evaluated dynamically when the user performs the relevant action.

For example:

```text
User subscribes
       ↓
Subscription becomes ACTIVE
       ↓
No individual LIKE entitlement is created
       ↓
User performs LIKE
       ↓
System reads subscription_plan_limit_and_cost
       ↓
Applies configured LIKE rule
```

The same applies to:

```text
SUPER_LIKE
BOOST
REWIND
SEE_WHO_LIKED_YOU
INCOGNITO_MODE
CHANGE_ADDRESS
SUPER_MESSAGE
```

---

# 28. Subscription Purchase Idempotency

Subscription credit grants must be idempotent.

The system must ensure that the same successful payment/subscription transaction cannot grant included credits more than once.

The implementation should use a unique external transaction/event identifier or an equivalent idempotency mechanism.

For example:

```text
provider_transaction_id
```

or:

```text
provider_event_id
```

must not be processed twice as an effective credit grant.

The same principle applies to subscription webhook retries.

---

# 29. Subscription Renewal

On a successful subscription renewal:

1. Renew the subscription/billing period.
2. Grant the configured `included_credits`.
3. Create a credit ledger entry.
4. Create the corresponding expiring credit lot.
5. Update `user_credit_balances`.

The subscription action benefits do not need to be separately granted or copied because they remain configured in:

```text
subscription_plan_limit_and_cost
```

The system must ensure that credits are not granted twice for the same successful subscription renewal transaction.

---

# 30. Subscription Cancellation and Expiration

Subscription cancellation and expiration are different events.

If the user cancels auto-renewal:

```text
Subscription remains active until the paid period ends.
```

When the subscription expires:

```text
Subscription status → EXPIRED
```

Subscription-specific benefits stop being available immediately after the subscription expires.

The user's existing credits remain available according to the credit-expiration rules.

If subscription allowance credits have an expiration date and that date has not yet been reached, they remain available.

If they have expired, they must be removed according to the credit-lot expiration process.

---

# 31. Subscription Plan Changes

If a user changes subscription plans, the system must define the usage-tracking behaviour for the new plan.

The recommended approach is:

```text
Old subscription plan
        ↓
Existing usage tracker remains associated with old rule
        ↓
New subscription plan becomes active
        ↓
New plan rules become effective
        ↓
New usage tracking is created for the new plan/rule
```

The system should not silently combine usage counters from different subscription-plan rules unless explicitly required by the business rules.

If plan changes occur within a billing cycle, the new plan's rules should be evaluated according to the effective subscription period and configured business rules.

### Same-Plan Renewal

When a subscription **renews with the same plan**, the existing usage trackers should be **updated** rather than recreated:

```text
Same-plan renewal
        ↓
Update existing tracker period_end_date to new billing cycle end
        ↓
Reset used_count to 0
        ↓
Update period_start_date to new billing cycle start
```

This preserves tracker continuity for the same plan while resetting the usage period.

### Plan Change

When a user **changes to a different plan**, new trackers are created for the new plan's rules as described above. Old trackers remain as historical records.

---

# 32. Credit Products

The `consumable_products` table should represent **credit packages** rather than individual actions such as Boosts or Super Likes.

Examples:

```text
1,000 Credits
5,000 Credits
10,000 Credits
25,000 Credits
```

A credit purchase should:

1. Complete the payment.
2. Add the purchased credits to `user_credit_balances`.
3. Create a `CREDIT_PURCHASE` ledger entry.
4. Create a non-expiring credit lot.
5. Purchased credits do not expire.

The credit purchase must not create a separate action-specific credit balance.

---

# 33. Credit Purchase Idempotency

Credit purchases must be idempotent.

The same successful payment transaction must never result in multiple credit grants.

The implementation should use the payment provider's unique transaction/event identifier.

For example:

```text
payment_transaction_id
```

should have an appropriate uniqueness constraint.

If the payment provider sends the same successful event multiple times, only the first processing attempt should create the credit grant.

---

# 34. Refunds and Reversals

Refunds must be represented as new ledger transactions.

Historical credit ledger entries must never be modified.

A refund should reference the original transaction where applicable.

For example:

```text
Original:
+5,000 CREDIT_PURCHASE

Refund:
-5,000 REVERSAL
```

or, where credits are being returned to the user:

```text
Original:
-100 ACTION_CONSUMPTION

Refund:
+100 REFUND
```

The exact refund amount must follow the applicable business rule.

A refund must not directly modify historical transactions.

### Refund of a Credit Purchase with Already-Consumed Credits

If a refund reverses a credit purchase and some credits from that purchase have already been consumed, the refund amount must be capped at the user's **current credit balance**.

The system must **never** allow a refund to create a negative `user_credit_balances.balance`.

The business rule is:

```text
refund_amount = min(original_purchase_amount, current_credit_balance)
```

If the current balance is 0, the refund cannot be processed as a credit reversal. The refund must be handled through an external payment channel (e.g., bank refund) rather than as a credit ledger transaction.

If the current balance is less than the original purchase amount, only the remaining balance is reversed in the credit ledger. The difference is tracked as an external refund in the payment/transaction records.

### Reversal of a Consumption from an Expired Lot

If a consumption reversal (e.g., undoing a Super Message charge) needs to return credits to a lot that has already expired, the system must create a **new non-expiring credit lot** for the reversed amount rather than restoring the expired lot.

This ensures that expired lots remain expired for audit purposes, while the user receives usable credits back.

---

# 35. Likes Visibility and Credit Access

The:

```text
SEE_WHO_LIKED_YOU
```

feature determines whether a received Like is revealed automatically or must be revealed on demand using credits.

Add:

```text
revealed_at TIMESTAMP NULL
```

to:

```text
user_discovery_actions
```

Meaning:

```text
NULL
→ Like is currently hidden

timestamp
→ Like has been revealed
```

---

# 36. When a Like Is Created

When User A Likes User B, the system must **always** save the Like with:

```text
revealed_at = NULL
```

The system does **not** evaluate `SEE_WHO_LIKED_YOU` configuration at Like creation time.

The Like is always created as hidden, regardless of User B's subscription status or plan configuration.

This reduces unnecessary complexity by eliminating cross-user coupling in the Like creation path. The receiver's subscription state, plan configuration, and usage tracker are only evaluated when User B accesses the Likes screen or explicitly requests a reveal.

This means:

```text
User A likes User B
        ↓
Create user_discovery_actions record
        ↓
Set revealed_at = NULL (always)
        ↓
No evaluation of User B's subscription or SEE_WHO_LIKED_YOU config
        ↓
Done
```

The reveal decision is deferred entirely to access time (see §38 and §39).

---

# 37. Important Rule for Hidden Likes

The system must **not automatically deduct credits when the Like is created** simply because:

```text
SEE_WHO_LIKED_YOU
```

has a credit cost.

If the Like is not covered by the subscription/free allowance, it remains hidden.

The user explicitly chooses to reveal it through the dedicated endpoint, at which point credits may be deducted.

This ensures that the user controls when a credit-consuming reveal occurs.

---

# 38. Reveal Endpoint

Example:

```http
POST /api/discovery-actions/{actionId}/reveal
```

The backend must:

1. Verify that the discovery action belongs to the current user as the receiver.
2. Verify that `revealed_at IS NULL`.
3. Determine the user's effective subscription plan:

   * active paid subscription plan, or
   * `FREE` plan if there is no active subscription.
4. Find the `SEE_WHO_LIKED_YOU` configuration.
5. Determine whether the user has an available subscription allowance.
6. If a subscription allowance is available, consume the allowance.
7. Otherwise evaluate `apply_credit_after_limit`.
8. If credits are required, verify sufficient credits.
9. Deduct credits transactionally.
10. Update the relevant credit lots if credits were consumed.
11. Record `ACTION_CONSUMPTION` in the credit ledger if credits were consumed.
12. Record credit-lot consumption allocations if credits were consumed.
13. Set `revealed_at = NOW()`.
14. Return the revealed Like/profile.

If the action has already been revealed, it must not charge credits or consume another allowance.

---

# 39. SEE_WHO_LIKED_YOU Usage Allocation

When multiple hidden Likes exist, the system must not automatically consume the user's free allowance merely because Likes are received.

The recommended behaviour is:

```text
Like received
    ↓
Always save as hidden (revealed_at = NULL)
    ↓
User opens Likes screen / chooses reveal
    ↓
Evaluate current entitlement
    ↓
Consume free allowance or credits
    ↓
Reveal Like
```

This prevents the system from consuming a user's limited reveal allowance without an explicit user interaction.

---

# 40. Subscription Changes and Previously Hidden Likes

If a user obtains a subscription that provides access to:

```text
SEE_WHO_LIKED_YOU
```

existing hidden Likes should not necessarily require a separate purchase.

When the user attempts to access the Likes screen, the backend should evaluate the current subscription plan and action rules.

Existing hidden Likes can therefore become revealable under the user's new subscription benefits.

New Likes received while the subscription is active are also created with `revealed_at = NULL` and evaluated using the same current rules when the user accesses them.

Previously revealed Likes remain revealed even if the subscription later expires.

---

# 41. Pre-Match Message / Super Message

A:

```text
SUPER_MESSAGE
```

allows a user to send a message to another user before they match.

Create:

```text
pre_match_messages
```

Suggested structure:

```sql
CREATE TABLE pre_match_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    sender_id UUID NOT NULL REFERENCES users(id),
    receiver_id UUID NOT NULL REFERENCES users(id),

    message TEXT NOT NULL,

    action_type VARCHAR(30) NOT NULL DEFAULT 'SUPER_MESSAGE',

    credit_cost BIGINT NOT NULL,

    status VARCHAR(30) NOT NULL DEFAULT 'SENT',

    viewed_at TIMESTAMPTZ NULL,
    responded_at TIMESTAMPTZ NULL,

    match_id UUID NULL REFERENCES matches(id),

    idempotency_key VARCHAR(255) NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pre_match_message_status_check CHECK (
        status IN (
            'SENT',
            'VIEWED',
            'ACCEPTED',
            'PASSED',
            'BLOCKED',
            'EXPIRED'
        )
    ),

    CONSTRAINT pre_match_message_credit_cost_check
        CHECK (credit_cost >= 0)
);
```

`credit_cost` records the **historical credit cost actually charged for that specific Super Message**.

The current action price must continue to come from:

```text
subscription_plan_limit_and_cost
```

The database record stores the historical cost so future configuration changes do not alter the historical transaction.

---

# 42. Sending a Super Message

When a user sends a Super Message:

```text
1. Determine SUPER_MESSAGE action configuration.

2. Determine the user's effective subscription plan.

3. Check the subscription-specific allowance/cost.

4. If the subscription benefit is available:
       use member_credit_cost.

5. If the subscription allowance is exhausted:
       if apply_credit_after_limit = true:
           use actual_credit_cost.
       otherwise:
           reject the action.

6. If the resulting credit cost is greater than zero:
       verify sufficient credits.
       deduct credits transactionally.
       update credit lots.
       record ACTION_CONSUMPTION.
       record credit-lot consumption allocations.

7. Create pre_match_messages record.

8. Store the actual historical credit cost in
   pre_match_messages.credit_cost.

9. Automatically create the associated LIKE
   discovery action.
   - The auto-generated Like uses the Super Message's `idempotency_key`
     as its `client_action_id` to ensure that retries do not create
     duplicate Likes.
   - The Like is created with `revealed_at = NULL` (always hidden,
     per §36).
```

The automatically generated Like must not be charged separately if it is part of the Super Message transaction.

The entire operation should execute within one database transaction.

---

# 43. Super Message Idempotency

Sending a Super Message must support idempotency.

A client request may be retried if the network fails after the server has processed the request.

The same idempotency key must therefore not:

```text
charge credits twice
create two Super Messages
create two Likes
```

The idempotency key should be unique for the relevant user/action operation.

If an identical request is received again, the existing successful result should be returned instead of processing the action again.

---

# 44. Link Super Message to Discovery Action

Add a nullable field to:

```text
user_discovery_actions
```

```text
pre_match_message_id UUID NULL
```

If populated, the discovery action represents the Like generated by a Super Message.

The auto-generated Like's `client_action_id` is set to the Super Message's `idempotency_key`. This ensures that client retries (due to network timeouts) do not create duplicate Likes or charge credits twice.

The Super Message and associated Like should therefore be treated as one user action for billing purposes.

---

# 45. Receiver Response to Super Message

### Accept

When the receiver accepts:

1. Set `pre_match_messages.status = ACCEPTED`.
2. Create a match.
3. Set `match_id`.
4. Move the original Super Message into the normal matched conversation.
5. From that point onward, the conversation behaves like a normal matched conversation.

### Pass

```text
status = PASSED
```

No match is created.

### Block

```text
status = BLOCKED
```

No match is created.

### Expired

```text
status = EXPIRED
```

No match is created.

---

# 46. Identity Verification

If:

```text
country_settings.identity_verification_required = true
```

identity verification should be required during onboarding.

If it is false, verification should not be mandatory.

If no country-specific configuration exists, use the `GLOBAL` configuration.

The purpose is to verify that:

* the person creating the profile is the person represented by the profile photo;
* users cannot easily create fake profiles using another person's photograph;
* the platform can reduce the use of stolen or misleading profile pictures.

---

# 47. Identity Verification State

Instead of using only:

```text
is_verified BOOLEAN
```

the profile should maintain an explicit verification state.

Recommended fields:

```text
verification_status
verification_result_message
verified_at
```

Supported states:

```text
NOT_STARTED
PENDING
VERIFIED
FAILED
```

The final verified state can still be exposed to application code through an appropriate boolean/helper if needed, but the persisted status should distinguish between:

```text
Never attempted
Verification in progress
Successfully verified
Verification failed
```

This is important for retries and onboarding flow management.

---

# 48. Identity Verification Process

During onboarding:

1. The user uploads a profile picture.
2. The user captures a selfie.
3. The system compares the images using the configured identity-verification provider.
4. The provider/system returns a verification result.
5. Save the verification status.
6. Save the verification result message where appropriate.
7. Save `verified_at` when verification succeeds.
8. If verification is required, the user cannot complete the relevant onboarding step until verification succeeds.
9. Failed verification attempts must support an appropriate retry process.

Suggested profile fields:

```text
verification_status
verification_result_message
verified_at
```

---

# 49. Identity Verification Endpoint

Add an identity verification endpoint that:

* accepts the selfie;
* verifies that a profile picture exists;
* performs the comparison;
* sets the verification status;
* saves the verification result message;
* sets `verified_at` when successful;
* returns the verification status.

The verification provider's raw response should not be stored unnecessarily if the application only needs the final verification status and result message.

The implementation should support failed verification attempts and an appropriate retry process.

---

# 50. Payment and Credit Transaction Idempotency

All payment-driven credit operations must be idempotent.

This includes:

```text
Subscription purchase
Subscription renewal
Credit purchase
Refund
Payment webhook
Subscription webhook
```

The same external payment/event identifier must never produce multiple effective financial transactions.

Where appropriate, enforce uniqueness at the database level.

Examples include:

```text
provider_transaction_id
provider_event_id
idempotency_key
```

The application must not rely solely on in-memory checks to prevent duplicate processing.

---

# 51. Payment Database Review

The existing database schema must be **modified and redesigned** according to the new requirements. This is not a purely additive migration — existing tables must be altered, constraints updated, and obsolete tables dropped.

### Tables to Create

```text
user_credit_balances
user_credit_ledger (rename/replace user_entitlement_ledger)
user_credit_lot_consumptions (replace user_entitlement_credit_consumptions)
subscription_plan_limit_and_cost
feature_actions
user_action_limits_tracker
country_settings
pre_match_messages
```

### Tables to Drop

```text
user_daily_limits
user_quota_usage
user_entitlement_credit_consumptions (replaced by user_credit_lot_consumptions)
```

### Tables to Modify

```text
user_entitlement_ledger
    → Rename to user_credit_ledger
    → Add columns: transaction_type, amount (BIGINT), balance_after, source_type, source_id, action_type
    → Change entitlement_type to source_type or credit_source_type
    → Update reason values to transaction_type values
    → Change quantity_delta (INTEGER) to amount (BIGINT, signed)

user_entitlement_credit_lots
    → Rename to user_credit_lots (or keep name, update columns)
    → Change entitlement_type to credit_source_type
    → Expand CHECK to include: SUBSCRIPTION_ALLOWANCE, CREDIT_PURCHASE, PROMOTION, ADMIN_ADJUSTMENT
    → Change quantity_granted/quantity_remaining from INTEGER to BIGINT

consumable_products
    → Change entitlement_type CHECK to include CREDIT_PURCHASE
    → Change quantity_granted from INTEGER to BIGINT
    → Update seed data: replace BOOST_CREDIT/SUPERLIKE_CREDIT/REWIND_CREDIT products with credit packages

subscription_products
    → Add column: included_credits BIGINT NOT NULL DEFAULT 0

subscription_plan_limits
    → Replace with subscription_plan_limit_and_cost
    → Migrate existing limit data into the new table
    → Drop subscription_plan_limits after migration

payment_offers
    → Update platform CHECK: ('ANDROID', 'IOS', 'WEB') → ('WEB', 'MOBILE', 'ALL')
    → Update all existing ANDROID/IOS rows to MOBILE
    → Drop payment_channel and payment_method columns (already done in V21)

payment_methods
    → Update platform CHECK: ('ANDROID', 'IOS', 'WEB') → ('WEB', 'MOBILE', 'ALL')
    → Update all existing ANDROID/IOS rows to MOBILE
    → Drop and recreate unique_active_online_payment_per_market index
      to allow multiple online payment methods per MOBILE market

user_discovery_actions
    → Add column: revealed_at TIMESTAMPTZ NULL
    → Add column: pre_match_message_id UUID NULL REFERENCES pre_match_messages(id)

app_users
    → Add columns: verification_status, verification_result_message, verified_at
```

### Additional Requirements

* credit products should represent credit packages rather than individual actions;
* adapt `user_entitlement_credit_lots` to track all credit grants, including purchased credits;
* use `expires_at = NULL` for non-expiring purchased credit lots;
* replace the existing daily-limit mechanism with `user_action_limits_tracker`;
* update `user_entitlement_ledger` into the credit transaction/audit ledger;
* add `user_credit_balances`;
* add `user_credit_lot_consumptions`;
* add subscription credit allowances;
* update all related code to use the central credit balance;
* ensure subscription action benefits are configured through `subscription_plan_limit_and_cost`;
* ensure action/feature credit costs are not hard-coded;
* ensure payment and credit grants are idempotent;
* ensure credit deductions and allowance consumption are concurrency-safe;
* ensure all historical financial transactions remain auditable.

The existing subscription system should not be unnecessarily redesigned.

The main change is to integrate subscriptions and credit allowances into the central credit system.

---

# 52. Overall Payment and Action Flow

The resulting architecture should work as follows:

```text
                          USER ACTION
                              ↓
                    Determine feature_action
                              ↓
                    Determine user's country
                              ↓
                       country_settings
                              ↓
                    Determine effective plan
                              ↓
              ┌───────────────┴────────────────┐
              │                                │
      Active subscription?              No subscription
              │                                ↓
             YES                           FREE plan
              │                                │
      Paid subscription plan                   │
              │                                │
              └──────────────┬─────────────────┘
                             ↓
              subscription_plan_limit_and_cost
                             ↓
                    Check subscription limit
                             │
                    ┌────────┴────────┐
                    ↓                 ↓
             Allowance available   Exhausted
                    │                 │
                    ↓                 ↓
           member_credit_cost    apply_credit_after_limit?
                                      │
                              ┌───────┴───────┐
                              ↓               ↓
                             YES              NO
                              │               │
                              ↓               ↓
                      actual_credit_cost   Reject action
                              │
                              ↓
                       credit cost > 0?
                              │
                       ┌──────┴──────┐
                       ↓             ↓
                      YES            NO
                       │             │
                Check credits   Perform action
                       │
                  Sufficient?
                       │
                ┌──────┴──────┐
                ↓             ↓
               YES            NO
                │             │
                ↓             ↓
         Deduct credits   Reject action
                │
                ↓
        Allocate credit lots
                │
                ↓
       Update central balance
                │
                ↓
         Record credit ledger
                │
                ↓
      Update action-limit tracker
                │
                ↓
          Perform action
```

---

# 53. Core Principles

The implementation should follow these principles:

1. **One central credit balance per user.**
2. **Subscriptions and credits are separate monetization mechanisms.**
3. **A subscription may grant an included credit allowance.**
4. **Included subscription credits are added to the same central credit balance as purchased credits.**
5. **Subscription action/feature benefits are controlled by `subscription_plan_limit_and_cost`.**
6. **Subscription action allowances are separate from the user's credit balance.**
7. **`member_credit_cost` determines the cost while the subscription-specific rule applies.**
8. **`actual_credit_cost` determines the fallback cost when the subscription-specific allowance is unavailable and `apply_credit_after_limit = true`.**
9. **`actual_credit_cost = 0` is valid and means the fallback action remains free.**
10. **`apply_credit_after_limit = true` does not imply that a positive credit cost exists.**
11. **`limit_value = NULL` means unlimited.**
12. **`limit_value = 0` means zero subscription-specific allowance; if fallback charging is enabled, `actual_credit_cost` applies immediately.**
13. **`DAY` represents a calendar day.**
14. **`MONTH` represents a calendar month.**
15. **`BILLING_CYCLE` represents the user's subscription billing period and is only valid for paid plans. `FREE` plan rules may only use `DAY` and `MONTH`.**
16. **Users can purchase additional credit packages where credits are enabled.**
17. **A country may support subscription only, credits only, or both.**
18. **Purchased credits do not expire.**
19. **All credit grants are represented by credit lots.**
20. **A credit lot with `expires_at = NULL` never expires.**
21. **Non-purchased credits expire according to their individual credit lots.**
22. **Credit consumption must use a deterministic lot allocation order.**
23. **Credit consumption must record exactly which lots supplied the consumed credits.**
24. **`user_entitlement_credit_lots` must be used to determine and process credit expiration.**
25. **Credit consumption must update both the central balance and applicable credit lots transactionally.**
26. **All credit changes must be recorded in the credit ledger.**
27. **Historical credit transactions must remain auditable and immutable.**
28. **Refunds and reversals must be represented as compensating ledger transactions. A refund must never create a negative credit balance.**
29. **Action/feature pricing must be configurable rather than hard-coded.**
30. **The system must not maintain separate spendable balances for individual actions.**
31. **Subscription benefits are configured once in `subscription_plan_limit_and_cost` and evaluated when the user performs an action.**
32. **Subscription purchase/renewal does not create individual action entitlements.**
33. **The `FREE` subscription plan is used as the effective plan for users without an active paid subscription.**
34. **Subscription action-limit tracking must be concurrency-safe.**
35. **Credit deduction must be concurrency-safe.**
36. **Payment and credit-grant operations must be idempotent.**
37. **The same payment transaction or provider event must never grant credits twice.**
38. **Likes are always created with `revealed_at = NULL`. The `SEE_WHO_LIKED_YOU` configuration is evaluated only when the receiver accesses the Likes screen or explicitly requests a reveal.**
39. **A Like that requires credits must not automatically consume credits when it is received.**
40. **Users explicitly choose when to consume credits to reveal a hidden Like.**
41. **The same action engine must work regardless of whether the user obtained credits through a subscription allowance or a credit purchase.**
42. **The central balance must remain reconcilable against the credit transaction history. Reconciliation is on-demand only; there is no automatic batch process.**
43. **Payment platform values are `WEB`, `MOBILE`, and `ALL`.**
44. **iOS and Android use the same backend mobile offers/products and therefore do not require separate backend platform values.**
45. **The payment platform must remain conceptually separate from the payment provider.**
46. **Identity verification must distinguish between not started, pending, verified, and failed states.**
47. **Previously revealed Likes remain revealed even if subscription benefits later expire.**
48. **Credit deduction, credit-lot updates, ledger entries, action-limit updates, and action authorization must be transactionally safe.**
49. **The existing database schema must be modified and redesigned according to the new requirements. Obsolete tables (`user_daily_limits`, `user_quota_usage`) must be dropped.**
50. **Same-plan subscription renewal updates existing usage trackers (reset `used_count`, update period dates). Plan changes create new trackers.**
51. **Reversals of consumptions from expired credit lots create new non-expiring credit lots rather than restoring expired ones.**
52. **The auto-generated Like from a Super Message uses the Super Message's `idempotency_key` as its `client_action_id` to prevent duplicates on retry.**
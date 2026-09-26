# Implement Configurable Action Variant System

Implement a configurable **action variant system** in the existing Spring Boot backend for the dating application.

The application already has:

* a discovery-action system;
* a `feature_actions` table containing multiple actions such as `LIKE`, `PASS`, `SUPERLIKE`, etc.;
* subscription-plan-specific action pricing and limits;
* an existing credit system;
* existing discovery-action endpoints;
* existing matching, notification, reversal/rewind, and idempotency behavior.

The goal is to extend the existing architecture so that certain feature actions can optionally have **variants**.

The first and only action that should use this new variant functionality is:

```text
LIKE
```

The initial LIKE variants are:

```text
HEART
ROSE
BUNA
CHOCOLATE
FLOWERS
RING
```

These are all fundamentally `LIKE` actions.

For example:

```text
action_type = LIKE
action_variant_code = ROSE
```

The variant describes how the user expressed the Like.

---

# 1. CRITICAL: Preserve Existing Non-LIKE Actions

The existing `feature_actions` table contains actions that are not related to LIKE.

Examples may include:

```text
PASS
SUPERLIKE
SUPER_MESSAGE
REWIND
BOOST
or other existing actions
```

Inspect the actual codebase and database to determine the complete set of existing actions.

Do **not**:

* remove existing actions;
* rename existing action codes;
* change existing action types;
* reinterpret existing actions;
* convert existing actions into variants;
* change existing credit pricing;
* change existing limits;
* change existing API behavior;
* require `actionVariantCode` for non-LIKE actions;
* change existing business logic.

All existing non-LIKE actions must continue to work exactly as they do today.

The new variant architecture must be an additive extension.

---

# 2. Existing `feature_actions` Table

The existing table is conceptually:

```sql
CREATE TABLE feature_actions (
    id uuid NOT NULL,
    code varchar(50) NULL,
    "name" varchar(100) NULL,
    "type" varchar(20) NULL
);
```

Extend it with the following configuration fields:

```sql
ALTER TABLE feature_actions
ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE,
ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0,
ADD COLUMN icon VARCHAR(500),
ADD COLUMN description VARCHAR(255),
ADD COLUMN has_variants BOOLEAN NOT NULL DEFAULT FALSE;
```

Use the project's existing naming conventions if they differ.

### Important

`feature_actions` remains the table representing the **fundamental feature action**.

Do NOT create separate `feature_actions` records for:

```text
HEART
ROSE
BUNA
CHOCOLATE
FLOWERS
RING
```

The variants must be stored in the new `action_feature_variants` table described below.

For example:

```text
feature_actions
----------------
LIKE
PASS
SUPERLIKE
REWIND
...
```

and:

```text
action_feature_variants
-----------------------
LIKE → HEART
LIKE → ROSE
LIKE → BUNA
LIKE → CHOCOLATE
LIKE → FLOWERS
LIKE → RING
```

---

# 3. `has_variants`

Add:

```text
feature_actions.has_variants
```

This indicates whether an action supports the variant mechanism.

Initial configuration:

```text
LIKE       → TRUE
PASS       → FALSE
SUPERLIKE  → FALSE
REWIND     → FALSE
...
```

Only set `has_variants = TRUE` for LIKE as part of this implementation.

However, the database design and implementation must be generic enough that another action can support variants in the future without requiring a redesign.

For example, in the future:

```text
SUPERLIKE
    ├── FIRE
    └── CROWN
```

could be supported simply by setting:

```text
feature_actions.has_variants = TRUE
```

and adding records to `action_feature_variants`.

Do not implement those future variants now.

---

# 4. New Table: `action_feature_variants`

Create a new table:

```sql
CREATE TABLE action_feature_variants (
    id UUID NOT NULL,
    feature_action_id UUID NOT NULL,

    code VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    icon VARCHAR(500),

    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
```

Adjust the exact types, constraints, ID generation, timestamps, and naming to match the project's existing database conventions.

Add an appropriate foreign key:

```text
action_feature_variants.feature_action_id
    →
feature_actions.id
```

Add a uniqueness constraint so that variant codes are unique within an action:

```text
UNIQUE(feature_action_id, code)
```

Do not assume that a variant code must be globally unique across every possible action.

For example, this should theoretically be possible:

```text
LIKE + HEART
SOME_OTHER_ACTION + HEART
```

although only LIKE variants are being created now.

---

# 5. Initial LIKE Variants

Create the following initial records under the existing `LIKE` feature action:

| Code        | Name      | Description     | Sort Order |
| ----------- | --------- | --------------- | ---------: |
| `HEART`     | Heart     | Strong feelings |          1 |
| `ROSE`      | Rose      | Notice me       |          2 |
| `BUNA`      | Buna      | Let's talk      |          3 |
| `CHOCOLATE` | Chocolate | I like you      |          4 |
| `FLOWERS`   | Flowers   | You're special  |          5 |
| `RING`      | Ring      | I'm serious     |          6 |

These must be inserted using the project's normal migration/seed mechanism.

Do not hardcode these variants in Java business logic.

They are configuration data.

The system must allow additional variants to be added later without Java code changes.

---

# 6. HEART Represents the Normal Like

`HEART` represents the normal/default Like.

Do not represent a normal Like with:

```text
action_variant_code = NULL
```

New LIKE actions must use:

```text
action_type = LIKE
action_variant_code = HEART
```

or another configured LIKE variant.

### Important

Do NOT assume HEART is free.

Do not hardcode:

```text
HEART = 0 credits
```

HEART must be configurable in exactly the same way as the other variants.

For example, an administrator may configure:

```text
HEART = 0 credits
```

or:

```text
HEART = 5 credits
```

or any other valid amount.

The backend must obtain the configured amount from the database.

---

# 7. Icon Configuration

The variant icon must be stored as a URL, not as binary data.

Example:

```text
https://cdn.qal.app/actions/rose.webp
```

The `icon` field should therefore contain the complete HTTPS URL.

Do not store:

* binary image data;
* Base64 data;
* image files directly in PostgreSQL.

The actual images should be hosted by the existing CDN/object-storage infrastructure.

Use:

```text
VARCHAR(500)
```

or an equivalent size consistent with project conventions.

The icon must be configurable without a mobile-app release.

Changing:

```text
icon
```

in the database should change what the mobile client receives.

---

# 8. Existing `user_discovery_actions` Table

The existing table is conceptually:

```sql
CREATE TABLE user_discovery_actions (
    id uuid NOT NULL,
    actor_user_id uuid NOT NULL,
    target_user_id uuid NOT NULL,
    action_type varchar(20) NULL,
    status varchar(20) NULL,
    client_action_id uuid NOT NULL,
    reversed_at timestamptz NULL,
    reversed_reason varchar(30) NULL,
    metadata jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    revealed_at timestamptz NULL,
    pre_match_message_id uuid NULL
);
```

Add:

```sql
ALTER TABLE user_discovery_actions
ADD COLUMN action_variant_code VARCHAR(50) NULL;
```

The field stores the stable variant code.

Examples:

```text
action_type = LIKE
action_variant_code = HEART
```

```text
action_type = LIKE
action_variant_code = ROSE
```

```text
action_type = LIKE
action_variant_code = RING
```

Do not store the variant database UUID in this field.

Store the stable configuration code.

---

# 9. `action_variant_code` Applies to Variant Actions Only

The new request field:

```text
actionVariantCode
```

is only meaningful when the action supports variants.

For the initial implementation:

```text
LIKE → actionVariantCode required
```

For existing non-LIKE actions:

```text
PASS
SUPERLIKE
REWIND
BOOST
...
```

`actionVariantCode` must not be required.

If the existing API uses one endpoint for multiple action types:

### Valid

```text
LIKE + HEART
LIKE + ROSE
LIKE + BUNA
LIKE + CHOCOLATE
LIKE + FLOWERS
LIKE + RING
```

### Invalid

```text
LIKE without actionVariantCode
PASS + ROSE
PASS + HEART
SUPERLIKE + ROSE
SUPERLIKE + RING
```

Also reject:

```text
LIKE + unknown variant
LIKE + inactive variant
LIKE + variant belonging to another feature action
LIKE + variant when LIKE.has_variants = false
```

Follow the existing API's standard validation and error-response conventions.

---

# 10. Modify the Existing Discovery Action Endpoint

Do NOT create a separate endpoint for sending gifts/variants.

Modify the existing discovery-action request DTO to add:

```text
actionVariantCode
```

For example:

```json
{
    "targetUserId": "...",
    "actionType": "LIKE",
    "actionVariantCode": "ROSE",
    "clientActionId": "..."
}
```

The exact DTO and JSON structure must follow the existing API conventions.

### Rules

When:

```text
actionType = LIKE
```

then:

```text
actionVariantCode
```

is mandatory.

When:

```text
actionType != LIKE
```

preserve the existing request behavior.

Do not redesign the existing endpoint unnecessarily.

Follow the existing conventions for:

* controller;
* DTO;
* validation;
* authentication;
* service;
* transactions;
* response;
* idempotency;
* error handling.

---

# 11. Processing a Variant LIKE

When the endpoint receives:

```text
actionType = LIKE
actionVariantCode = ROSE
```

the backend must:

1. Validate that `actionVariantCode` is present.
2. Resolve the fundamental feature action `LIKE`.
3. Verify that the LIKE feature action exists.
4. Verify that `LIKE.type = LIKE`.
5. Verify that `LIKE.active = TRUE`.
6. Verify that `LIKE.has_variants = TRUE`.
7. Resolve the variant using:

   ```text
   feature_action_id = LIKE.id
   code = ROSE
   ```
8. Verify that the variant exists.
9. Verify that the variant is active.
10. Resolve the applicable pricing configuration.
11. Resolve the applicable usage-limit configuration.
12. Apply credits using the existing credit system.
13. Execute the existing LIKE/discovery business logic.
14. Save:

```text
action_type = LIKE
action_variant_code = ROSE
```

15. Preserve all existing:

* matching behavior;
* action status;
* reveal behavior;
* notification behavior;
* pre-match message behavior;
* reversal behavior;
* idempotency behavior;
* transaction behavior.

A variant LIKE is still fundamentally a `LIKE`.

The variant is additional information about how the Like was expressed.

---

# 12. Variant Pricing and Limits

Create a second new table:

```text
subscription_plan_variant_limit_and_cost
```

This table stores subscription-plan-specific pricing and limit configuration for variants.

Conceptually:

```sql
CREATE TABLE subscription_plan_variant_limit_and_cost (
    id UUID NOT NULL,

    subscription_plan_id UUID NOT NULL,
    action_feature_variant_id UUID NOT NULL,

    member_credit_cost BIGINT NOT NULL,
    actual_credit_cost BIGINT NOT NULL,

    limit_value INTEGER NULL,
    period_type VARCHAR(20) NULL,
    apply_credit_after_limit BOOLEAN NOT NULL,

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
```

Adjust foreign-key names, ID generation, timestamps, and exact conventions to match the existing project.

Add appropriate foreign keys to:

```text
subscription_plan
action_feature_variants
```

Add a uniqueness constraint:

```text
UNIQUE(subscription_plan_id, action_feature_variant_id)
```

There should be at most one variant pricing/limit configuration for a particular:

```text
subscription plan + action variant
```

---

# 13. Keep Existing `subscription_plan_limit_and_cost`

Do NOT remove or redesign the existing:

```text
subscription_plan_limit_and_cost
```

It continues to represent the normal feature-action pricing and limit configuration.

It is still required for existing actions.

The existing structure is conceptually:

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

Add these configuration flags:

```sql
ALTER TABLE subscription_plan_limit_and_cost
ADD COLUMN variant_pricing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
ADD COLUMN variant_limits_enabled BOOLEAN NOT NULL DEFAULT FALSE;
```

Use project naming conventions if appropriate.

---

# 14. Meaning of `variant_pricing_enabled`

For a subscription plan + feature action configuration:

```text
variant_pricing_enabled = FALSE
```

means:

> Use the existing `subscription_plan_limit_and_cost` pricing fields.

Therefore:

```text
member_credit_cost
actual_credit_cost
```

come from the existing action-level configuration.

If:

```text
variant_pricing_enabled = TRUE
```

and the action request contains a variant, then:

```text
subscription_plan_variant_limit_and_cost
```

must be used to determine the variant-specific cost.

For example:

```text
LIKE
variant_pricing_enabled = TRUE
```

means:

```text
HEART → its configured cost
ROSE → its configured cost
BUNA → its configured cost
RING → its configured cost
```

The client must never provide the credit cost.

The client provides only:

```text
actionVariantCode
```

The backend determines the price.

---

# 15. Meaning of `variant_limits_enabled`

For a subscription plan + feature action configuration:

```text
variant_limits_enabled = FALSE
```

means:

> Use the existing action-level limit configuration.

For example:

```text
LIKE
limit_value = 20
period_type = DAILY
variant_limits_enabled = FALSE
```

means all LIKE variants share the same:

```text
20 LIKE actions/day
```

So:

```text
5 HEART
5 ROSE
3 BUNA
2 CHOCOLATE
1 FLOWERS
1 RING
```

equals:

```text
17 LIKE actions
```

not six independent quotas.

This is the intended initial behavior.

If:

```text
variant_limits_enabled = TRUE
```

then the backend must resolve:

```text
limit_value
period_type
apply_credit_after_limit
```

from:

```text
subscription_plan_variant_limit_and_cost
```

for the selected variant.

This allows future configurations such as:

```text
HEART   → 20/day
ROSE    → 10/day
BUNA    → 5/day
FLOWERS → 3/day
RING    → 1/day
```

without requiring a database redesign.

---

# 16. Current Initial Configuration

For the initial LIKE implementation, configure:

```text
LIKE.has_variants = TRUE
```

and for the relevant subscription-plan LIKE configuration:

```text
variant_pricing_enabled = TRUE
variant_limits_enabled = FALSE
```

Therefore:

```text
Variant → determines price
LIKE    → determines shared usage limit
```

This is the intended initial business behavior.

However, the implementation must support both flags independently.

Do not assume they will always have the same value.

For example, these must all be supported:

```text
variant_pricing_enabled = FALSE
variant_limits_enabled = FALSE
```

```text
variant_pricing_enabled = TRUE
variant_limits_enabled = FALSE
```

```text
variant_pricing_enabled = FALSE
variant_limits_enabled = TRUE
```

```text
variant_pricing_enabled = TRUE
variant_limits_enabled = TRUE
```

---

# 17. Important: Cost and Limit Must Be Evaluated Separately

Because the existing:

```text
subscription_plan_limit_and_cost
```

table combines cost and limit information, do not assume that both values must come from the same configuration row.

The service layer must conceptually support:

```text
Variant pricing source
+
Variant/shared limit source
```

independently.

For example:

```text
LIKE + ROSE
```

could resolve as:

```text
Price:
subscription_plan_variant_limit_and_cost
        ↓
ROSE = 5 credits

Limit:
subscription_plan_limit_and_cost
        ↓
LIKE = 20/day
```

because:

```text
variant_pricing_enabled = TRUE
variant_limits_enabled = FALSE
```

Alternatively:

```text
LIKE + ROSE
```

could later resolve as:

```text
Price:
subscription_plan_variant_limit_and_cost
        ↓
ROSE = 5 credits

Limit:
subscription_plan_variant_limit_and_cost
        ↓
ROSE = 10/day
```

when:

```text
variant_pricing_enabled = TRUE
variant_limits_enabled = TRUE
```

Do not duplicate credit deduction logic.

---

# 18. Existing Credit System Must Be Reused

This is a critical requirement.

Inspect the existing:

* action cost service;
* action pricing resolution;
* subscription plan resolution;
* credit balance checking;
* credit deduction;
* credit transaction/ledger;
* insufficient-credit handling;
* transaction/rollback behavior;
* idempotency behavior.

Reuse the existing implementation and conventions.

Do NOT create:

* a separate credit service;
* a separate credit deduction mechanism;
* hardcoded prices;
* a `credits` column on `action_feature_variants`;
* a second credit ledger;
* a parallel payment mechanism.

The backend must determine the actual applicable credit cost.

The client must never be trusted to provide a price.

---

# 19. Recommended Pricing Resolution

For a variant action:

```text
1. Resolve subscription plan.
2. Resolve feature action.
3. Determine whether the action supports variants.
4. Resolve variant.
5. Load subscription_plan_limit_and_cost for:
       subscription_plan + feature_action
6. If variant_pricing_enabled:
       load subscription_plan_variant_limit_and_cost
       for subscription_plan + variant
       use its pricing fields.
   Otherwise:
       use feature-action pricing fields.
7. Determine usage limit:
       if variant_limits_enabled:
           use variant configuration
       else:
           use feature-action configuration.
8. Apply existing credit/limit logic.
9. Execute action.
```

The exact implementation should follow the existing architecture.

Do not blindly create a new service if the existing services can be cleanly extended.

---

# 20. `apply_credit_after_limit`

Preserve the semantics of:

```text
apply_credit_after_limit
```

for both:

```text
subscription_plan_limit_and_cost
```

and:

```text
subscription_plan_variant_limit_and_cost
```

If:

```text
variant_limits_enabled = FALSE
```

use the existing action-level:

```text
apply_credit_after_limit
```

If:

```text
variant_limits_enabled = TRUE
```

use the variant-specific:

```text
apply_credit_after_limit
```

Do not silently change existing limit behavior.

---

# 21. GET Endpoint for LIKE Variants

Create an endpoint that allows the mobile application to retrieve the currently available LIKE variants.

Preferred endpoint:

```http
GET /api/v1/discovery/like-actions
```

Follow the project's existing URL/versioning conventions if different.

The endpoint should return active variants for the LIKE action only.

Conceptually:

```text
feature_action.code = LIKE
feature_action.has_variants = TRUE
variant.active = TRUE
```

ordered by:

```text
variant.sort_order ASC
```

The response should include information needed by the client.

Conceptually:

```json
{
    "actions": [
        {
            "code": "HEART",
            "name": "Heart",
            "description": "Strong feelings",
            "icon": "https://cdn.qal.app/actions/heart.webp",
            "credits": 5,
            "sortOrder": 1
        },
        {
            "code": "ROSE",
            "name": "Rose",
            "description": "Notice me",
            "icon": "https://cdn.qal.app/actions/rose.webp",
            "credits": 10,
            "sortOrder": 2
        }
    ]
}
```

The exact response format must follow the project's existing API/DTO conventions.

The credit amount must be calculated from the authenticated user's applicable subscription-plan configuration.

Do not hardcode prices.

Do not return inactive variants from this endpoint.

Do not return unrelated feature actions.

---

# 22. Variant Availability and Pricing

The mobile client should not have to understand the pricing tables.

The endpoint should return the **effective price applicable to the current user**.

For example, if:

```text
Free plan + ROSE = 10 credits
Premium plan + ROSE = 5 credits
```

the endpoint should return the appropriate value for the authenticated user.

Do not expose unnecessary internal pricing fields unless the existing API convention requires them.

The client only needs the information necessary to display the variant and its effective cost.

---

# 23. Sent LIKE Endpoint

Modify the existing GET endpoint that returns sent LIKE actions.

For each:

```text
action_type = LIKE
```

include the variant information.

At minimum, expose:

```text
actionVariantCode
variant code
variant name
icon URL
description
```

Conceptually:

```json
{
    "actionType": "LIKE",
    "actionVariantCode": "ROSE",
    "actionVariant": {
        "code": "ROSE",
        "name": "Rose",
        "description": "Notice me",
        "icon": "https://cdn.qal.app/actions/rose.webp"
    }
}
```

Use the existing response DTO structure.

Do not unnecessarily redesign the existing endpoint.

Do not hardcode variant metadata.

Resolve the variant from:

```text
action_feature_variants
```

using:

```text
feature_action_id
+
action_variant_code
```

---

# 24. Received LIKE Endpoint

Modify the existing GET endpoint that returns received LIKE actions.

For each:

```text
action_type = LIKE
```

include:

```text
actionVariantCode
variant code
variant name
icon URL
description
```

Conceptually:

```json
{
    "actionType": "LIKE",
    "actionVariantCode": "ROSE",
    "actionVariant": {
        "code": "ROSE",
        "name": "Rose",
        "description": "Notice me",
        "icon": "https://cdn.qal.app/actions/rose.webp"
    }
}
```

Follow the project's existing DTO/API conventions.

Do not expose unrelated non-LIKE feature-action information.

---

# 25. Historical LIKE Actions

Historical LIKE actions must remain readable even if a variant is later disabled.

For example:

```text
Today:
LIKE + ROSE
```

Later:

```text
ROSE.active = FALSE
```

The old action must still display:

```text
ROSE
Rose
Notice me
icon
```

Therefore:

* disabling a variant must not delete it;
* inactive variants must not appear in the current available-variants endpoint;
* historical actions must still resolve their metadata;
* sent LIKE responses must still show historical variant information;
* received LIKE responses must still show historical variant information.

Use:

```text
active = FALSE
```

to disable a variant.

Do not physically delete variants that may already have been used.

---

# 26. Variant Codes Must Be Stable

The following field is the historical identifier:

```text
action_variant_code
```

Therefore variant codes should be treated as stable identifiers.

For example:

```text
ROSE
```

should not be casually renamed to:

```text
ROSE_GIFT
```

after historical actions exist.

Display properties may change:

```text
name
description
icon
sort_order
```

without changing the code.

If a fundamentally different variant is needed, create a new variant code.

---

# 27. Dynamic Configuration

Do not hardcode the initial six variants into Java code.

They must be database configuration.

The backend should support:

* adding a variant;
* disabling a variant;
* enabling a variant;
* changing its name;
* changing its description;
* changing its icon;
* changing its sort order;
* configuring its pricing;
* configuring its limits;

without requiring a mobile-app release.

For example, adding:

```text
TEDDY
```

later should require only database/configuration changes.

It should automatically appear in:

```http
GET /api/v1/discovery/like-actions
```

when active and correctly configured.

No Java business-logic change should be required.

---

# 28. Matching Behavior

A variant Like is still a normal Like.

Do NOT change matching logic to treat:

```text
ROSE
```

as a different action from:

```text
HEART
```

The discovery system should continue to identify:

```text
action_type = LIKE
```

as the fundamental Like.

For example:

```text
LIKE + HEART
```

and:

```text
LIKE + ROSE
```

must both be eligible for the same existing Like/matching logic.

The variant only communicates how the Like was expressed.

Do not create a separate matching system.

---

# 29. Notifications

Inspect the existing notification system.

Where the existing Like notification can naturally expose variant information, make the variant available to the notification layer.

For example, the UI may eventually display:

```text
Someone sent you a Rose
```

instead of simply:

```text
Someone liked you
```

However:

* do not create a separate notification system;
* preserve existing notification behavior;
* use existing notification metadata conventions;
* do not hardcode variant names;
* do not make notification changes that are unrelated to this feature.

If the existing notification metadata JSON can accommodate the variant code, prefer reusing that mechanism.

---

# 30. Reversal/Rewind

Inspect the existing reversal/rewind implementation.

A reversed variant Like must continue to be handled exactly like an existing Like.

For example:

```text
LIKE + ROSE
```

is still:

```text
action_type = LIKE
```

The reversal should operate on the discovery action itself.

Do not create a separate reversal mechanism for variants.

Preserve existing:

```text
reversed_at
reversed_reason
```

behavior.

---

# 31. Idempotency

The existing:

```text
client_action_id
```

idempotency behavior must remain unchanged.

If the client retries:

```text
LIKE + ROSE
```

because of a network failure, the backend must not:

* deduct credits twice;
* create duplicate discovery actions;
* create duplicate notifications;
* process the Like twice.

Reuse the existing idempotency mechanism.

Do not create a second idempotency system.

Variant information must be part of the same existing action-processing transaction.

---

# 32. Credit Transaction Integrity

Credit deduction and action creation must remain transactionally consistent with the existing implementation.

For example, if:

```text
ROSE costs 10 credits
```

and the action fails after credit processing, follow the existing rollback/transaction behavior.

Do not introduce a separate transaction model.

Verify:

* insufficient credits are handled by the existing mechanism;
* validation failures do not deduct credits;
* duplicate requests do not deduct credits twice;
* configured prices affect future actions;
* historical credit transactions remain correct.

---

# 33. Non-LIKE Actions

Explicitly test that all existing non-LIKE actions continue to behave exactly as before.

For example:

```text
PASS
SUPERLIKE
REWIND
BOOST
SUPER_MESSAGE
other existing actions
```

must:

* continue to use their existing pricing;
* continue to use their existing limits;
* continue to use their existing business logic;
* continue to accept their existing request format;
* not require `actionVariantCode`;
* not be returned by the LIKE variants endpoint.

Do not change their behavior merely because `feature_actions` now has:

```text
has_variants
active
sort_order
icon
description
```

---

# 34. JPA / Entity Implementation

Before implementing entities, inspect the existing codebase.

Follow the project's existing conventions for:

* UUIDs;
* entity relationships;
* `@ManyToOne`;
* `@OneToMany`;
* lazy/eager loading;
* Lombok;
* constructors;
* auditing;
* repositories;
* naming;
* enum/string mappings;
* validation.

Do not introduce a new JPA style.

Conceptually, the entities should include:

### `FeatureAction`

```text
active
sortOrder
icon
description
hasVariants
```

### `ActionFeatureVariant`

```text
id
featureAction
code
name
description
icon
active
sortOrder
createdAt
updatedAt
```

### `SubscriptionPlanVariantLimitAndCost`

```text
id
subscriptionPlan
actionFeatureVariant
memberCreditCost
actualCreditCost
limitValue
periodType
applyCreditAfterLimit
createdAt
updatedAt
```

### `SubscriptionPlanLimitAndCost`

Add:

```text
variantPricingEnabled
variantLimitsEnabled
```

### `UserDiscoveryAction`

Add:

```text
actionVariantCode
```

Use the project's existing JPA relationship conventions rather than blindly implementing the conceptual model above.

---

# 35. Avoid N+1 Queries

Pay attention to how the sent/received LIKE endpoints retrieve variant metadata.

Do not introduce an N+1 query pattern such as:

```text
load 100 LIKE actions
+
100 individual variant queries
```

Use the existing repository/query conventions to efficiently retrieve the variant information.

A join/fetch strategy or projection may be appropriate depending on the existing architecture.

Follow the project's existing performance patterns.

---

# 36. Caching

Inspect the existing caching implementation.

If action configuration is already cached, follow the same convention.

The following data is relatively static:

```text
action_feature_variants
feature action configuration
```

Do not introduce a completely new caching framework.

If the project already uses Caffeine or another cache, use the existing mechanism where appropriate.

Make sure configuration changes eventually become visible without requiring a mobile release.

Do not cache user-specific pricing incorrectly across different subscription plans.

---

# 37. Database Indexes

Consider appropriate indexes based on the actual query patterns.

At minimum, the implementation should efficiently support:

```text
action_feature_variants.feature_action_id
action_feature_variants.code
action_feature_variants.active
```

and:

```text
subscription_plan_variant_limit_and_cost.subscription_plan_id
subscription_plan_variant_limit_and_cost.action_feature_variant_id
```

The unique constraint on:

```text
(feature_action_id, code)
```

should provide an appropriate index where supported.

Also inspect existing indexes before adding duplicates.

---

# 38. Database Migration

Use the project's existing migration framework and naming conventions.

The migration should:

### `feature_actions`

Add:

```text
active
sort_order
icon
description
has_variants
```

### `user_discovery_actions`

Add:

```text
action_variant_code
```

### `subscription_plan_limit_and_cost`

Add:

```text
variant_pricing_enabled
variant_limits_enabled
```

### New table

Create:

```text
action_feature_variants
```

### New table

Create:

```text
subscription_plan_variant_limit_and_cost
```

Then seed the six initial LIKE variants.

Do not:

* delete existing feature actions;
* replace the existing LIKE feature action;
* create HEART/ROSE/etc. as `feature_actions`;
* change existing non-LIKE action codes;
* change existing non-LIKE prices;
* change existing non-LIKE limits;
* remove existing columns;
* create a separate gifts table;
* create a second credit system.

---

# 39. Existing LIKE Data Migration

Before making any data migration affecting existing LIKE actions, inspect the database.

Existing records may currently contain:

```text
action_type = LIKE
action_variant_code = NULL
```

Because HEART is now the normal Like, determine whether these historical NULL LIKE records should be migrated to:

```text
action_variant_code = HEART
```

Do not blindly modify historical data.

First inspect:

* existing row counts;
* existing NULL values;
* constraints;
* application assumptions;
* existing historical behavior.

If migrating historical NULL LIKE records to HEART is safe and consistent with the existing data model, perform the migration.

If not, preserve the historical NULL values and make the new behavior apply only to newly created actions.

The final implementation must not corrupt historical discovery data.

---

# 40. Validation Rules

Implement service/API validation for at least the following.

### Valid

```text
LIKE + HEART
LIKE + ROSE
LIKE + BUNA
LIKE + CHOCOLATE
LIKE + FLOWERS
LIKE + RING
```

### Invalid

```text
LIKE + NULL
LIKE + UNKNOWN
LIKE + INACTIVE_VARIANT
LIKE + VARIANT_BELONGING_TO_OTHER_ACTION
PASS + ROSE
PASS + HEART
SUPERLIKE + ROSE
SUPERLIKE + RING
```

If an action does not support variants:

```text
has_variants = FALSE
```

then a supplied variant must be rejected according to the project's existing validation/error conventions.

---

# 41. Configuration Validation

The backend should fail safely when configuration is invalid.

For example, if:

```text
variant_pricing_enabled = TRUE
```

but no corresponding:

```text
subscription_plan_variant_limit_and_cost
```

record exists for the selected variant and plan, do not silently charge the generic price unless that behavior is explicitly part of the existing configuration rules.

Return the project's appropriate configuration/business error.

Likewise, if:

```text
variant_limits_enabled = TRUE
```

but the required variant limit configuration is missing, do not silently create an unlimited action or bypass the existing limit mechanism.

The implementation must avoid accidental free actions or limit bypasses caused by incomplete configuration.

---

# 42. GET Endpoint Configuration Errors

The startup/configuration endpoint should not expose unusable variants.

For example, if a variant is active but the current subscription plan has no required pricing configuration while:

```text
variant_pricing_enabled = TRUE
```

the endpoint should follow a safe, consistent policy based on the existing API conventions.

Prefer not returning a variant that the user cannot actually use, or return an appropriate configuration state if the existing API supports that concept.

Do not expose an incorrect price such as:

```text
0
```

merely because pricing configuration is missing.

---

# 43. Test the Shared-Limit Model

Because cost and limit can now come from different sources, explicitly test this.

Example configuration:

```text
LIKE
variant_pricing_enabled = TRUE
variant_limits_enabled = FALSE
```

Variant configuration:

```text
ROSE = 10 credits
RING = 50 credits
```

LIKE configuration:

```text
20/day
```

Verify:

```text
ROSE consumes 10 credits
RING consumes 50 credits
```

but both count toward:

```text
20 LIKE actions/day
```

---

# 44. Test Variant-Specific Limits

Also test:

```text
variant_pricing_enabled = TRUE
variant_limits_enabled = TRUE
```

Example:

```text
ROSE = 10 credits, 10/day
RING = 50 credits, 1/day
```

Verify that:

```text
ROSE
```

and:

```text
RING
```

use their respective configured limits.

This functionality must work even though it is not the initial default configuration.

---

# 45. Test Shared Pricing

Test:

```text
variant_pricing_enabled = FALSE
variant_limits_enabled = FALSE
```

Verify that variants can still exist as display/semantic variants while pricing and limits are obtained from the existing action-level configuration.

This confirms that the two flags operate independently.

---

# 46. Testing

Add or update tests following the project's existing test conventions.

## Variant configuration

Verify:

* LIKE has `has_variants = TRUE`;
* non-LIKE actions retain `has_variants = FALSE`;
* active variants are returned;
* inactive variants are excluded;
* variants belonging to other actions are excluded;
* results are ordered by `sort_order`;
* code is returned;
* name is returned;
* description is returned;
* icon URL is returned;
* effective credit cost is returned correctly.

## Sending LIKE

Test:

```text
LIKE + HEART
LIKE + ROSE
LIKE + BUNA
LIKE + CHOCOLATE
LIKE + FLOWERS
LIKE + RING
```

## Validation

Test rejection of:

```text
LIKE without actionVariantCode
LIKE with unknown variant
LIKE with inactive variant
LIKE with variant belonging to another action
PASS + ROSE
PASS + HEART
SUPERLIKE + ROSE
SUPERLIKE + RING
```

## Credit handling

Verify:

* configured variant price is used when variant pricing is enabled;
* existing action-level price is used when variant pricing is disabled;
* insufficient credits use existing handling;
* credits are not deducted when validation fails;
* credits are not deducted twice during idempotent retries;
* changing a configured price affects future actions;
* historical credit transactions remain correct.

## Shared limit

Verify:

```text
variant_limits_enabled = FALSE
```

causes all variants to count against the existing shared LIKE limit.

## Variant-specific limit

Verify:

```text
variant_limits_enabled = TRUE
```

uses variant-specific limits.

## Sent LIKE

Verify responses include:

```text
actionVariantCode
variant code
variant name
icon URL
description
```

## Received LIKE

Verify responses include:

```text
actionVariantCode
variant code
variant name
icon URL
description
```

## Historical data

Verify that a previously created:

```text
LIKE + ROSE
```

continues to display ROSE information after:

```text
ROSE.active = FALSE
```

## Non-LIKE actions

Explicitly verify that existing:

```text
PASS
SUPERLIKE
REWIND
BOOST
other existing actions
```

continue to work exactly as before.

---

# 47. Implementation Process

Before writing code, inspect the existing implementation of:

* `FeatureAction`;
* `UserDiscoveryAction`;
* existing feature-action repository;
* existing discovery-action repository;
* discovery-action controller;
* discovery-action service;
* action request DTO;
* action response DTOs;
* existing subscription-plan entities;
* existing `subscription_plan_limit_and_cost` entity;
* existing repositories;
* existing `ActionCostService`;
* existing credit deduction service;
* existing credit transaction/ledger;
* existing limit evaluation;
* sent LIKE endpoint;
* received LIKE endpoint;
* matching logic;
* notification logic;
* reversal/rewind logic;
* idempotency handling;
* migration framework;
* JPA conventions;
* caching conventions;
* API response conventions.

Reuse the existing architecture.

Do not duplicate existing functionality.

Do not create a parallel gift system.

Do not introduce a separate credit system.

Do not modify unrelated action behavior.

---

# 48. Important Architectural Principle

The final architecture should clearly separate four concepts.

### Fundamental action

```text
feature_actions
```

Example:

```text
LIKE
```

### Variant metadata

```text
action_feature_variants
```

Example:

```text
LIKE → ROSE
```

providing:

```text
code
name
description
icon
active
sort_order
```

### Variant pricing/limit configuration

```text
subscription_plan_variant_limit_and_cost
```

providing:

```text
subscription plan
variant
credit cost
limit
```

### Existing action pricing/limit configuration

```text
subscription_plan_limit_and_cost
```

providing:

```text
subscription plan
fundamental action
credit cost
limit
```

The configuration flags determine which source is used.

---

# 49. Final Resolution Model

The intended architecture is:

```text
                         feature_actions
                              │
                              │
                             LIKE
                              │
                       has_variants = TRUE
                              │
                              ▼
                 action_feature_variants
                              │
             ┌────────────────┼────────────────┐
             │                │                │
           HEART            ROSE             RING
             │                │                │
             └────────────────┼────────────────┘
                              │
                              ▼
          subscription_plan_variant_limit_and_cost
                              │
                     variant-specific
                       price / limit
```

While the existing action-level configuration remains:

```text
          subscription_plan_limit_and_cost
                       │
                    LIKE
                       │
             shared/default price
                   and/or limit
```

The effective configuration is:

```text
                 LIKE + ROSE
                       │
              ┌────────┴────────┐
              │                 │
         Price source       Limit source
              │                 │
              ▼                 ▼
      variant config       variant config
      OR action config     OR action config
```

Specifically:

```text
variant_pricing_enabled
        │
        ├── TRUE  → variant pricing
        └── FALSE → existing action pricing
```

and:

```text
variant_limits_enabled
        │
        ├── TRUE  → variant limit
        └── FALSE → existing action limit
```

---

# 50. Final Requirements

The implementation must satisfy these rules:

1. `feature_actions` continues to represent fundamental actions.
2. Existing non-LIKE actions remain unchanged.
3. `LIKE.has_variants = TRUE`.
4. LIKE variants are stored in `action_feature_variants`.
5. Variant pricing/limits are stored in `subscription_plan_variant_limit_and_cost`.
6. Existing `subscription_plan_limit_and_cost` remains intact and continues serving existing actions.
7. `variant_pricing_enabled` controls whether variant pricing is used.
8. `variant_limits_enabled` controls whether variant-specific limits are used.
9. These two flags operate independently.
10. The initial configuration should use variant-specific pricing but the existing shared LIKE limit.
11. HEART represents the normal Like.
12. HEART must not be assumed to be free.
13. Credit prices must never be hardcoded.
14. Existing credit handling must be reused.
15. `actionVariantCode` is mandatory for new LIKE actions.
16. `actionVariantCode` remains optional/unused for non-LIKE actions.
17. A variant Like is still fundamentally `action_type = LIKE`.
18. Historical variant codes must remain readable.
19. Variants should be disabled using `active = FALSE`, not deleted.
20. Variant metadata must be dynamically configurable.
21. Icons are stored as URLs, not binary data.
22. The mobile app retrieves active LIKE variants from a dedicated GET endpoint.
23. Sent and received LIKE endpoints expose variant information.
24. Matching continues to operate on `action_type = LIKE`.
25. Existing idempotency behavior must be preserved.
26. Existing notification behavior must be preserved.
27. Existing reversal/rewind behavior must be preserved.
28. New variants must be addable without Java business-logic changes.
29. JPA implementation must follow existing project conventions.
30. Do not introduce unnecessary architectural changes.

The implementation should prioritize **backward compatibility, configuration flexibility, reuse of existing services, transactional integrity, and minimal changes to unrelated functionality**.

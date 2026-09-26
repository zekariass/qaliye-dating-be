# LIKE Action Variants API

The LIKE action supports configurable variants (HEART, ROSE, BUNA, CHOCOLATE, FLOWERS, RING) with per-variant credit pricing and limits. Variants are managed in the database (`action_feature_variants`) and can be added, deactivated, or repriced without app changes.

## New Endpoint

### GET /api/v1/discovery/like-actions

Returns the active LIKE variants with their effective credit cost and limit/usage for the authenticated user (based on their current subscription plan).

**Auth:** Bearer JWT (same as other discovery endpoints)

**Response `200`:**

```json
{
  "actions": [
    {
      "code": "HEART",
      "name": "Heart",
      "description": "Send a like",
      "icon": "https://cdn.qal.app/actions/heart.webp",
      "credits": 1,
      "sortOrder": 1,
      "limit": 10,
      "used": 4,
      "remaining": 6,
      "resetsAt": "2026-09-19T00:00:00Z",
      "periodType": "DAY",
      "blocked": false
    },
    {
      "code": "ROSE",
      "name": "Rose",
      "description": "Send a rose",
      "icon": "https://cdn.qal.app/actions/rose.webp",
      "credits": 3,
      "sortOrder": 2,
      "limit": null,
      "used": 0,
      "remaining": null,
      "resetsAt": "2026-09-19T00:00:00Z",
      "periodType": "DAY",
      "blocked": false
    }
  ]
}
```

- `actions` is sorted by `sortOrder` ascending.
- `credits` is the effective per-action credit cost for the user's current plan.
- `limit` is the effective per-period allowance for the user's plan (variant-scoped when `variantLimitsEnabled`, otherwise the shared LIKE limit). `null` means unlimited.
- `used` is the user's usage count in the current period.
- `remaining` is `max(0, limit - used)`; `null` when `limit` is `null` (unlimited).
- `resetsAt` is when the current period ends and usage resets (start of the next period, UTC). `null` for `LIFETIME` periods, which never reset.
- `periodType` is `DAY`, `MONTH`, `BILLING_CYCLE`, or `LIFETIME`.
- `blocked` is `true` when the limit is exhausted and credits cannot be charged after the limit (`applyCreditAfterLimit = false`). Clients should disable the variant button in that case.
- Only active variants are returned.

## Changed Endpoints

### POST /api/v1/discovery/actions/like

**Request body — `actionVariantCode` is now required:**

```json
{
  "targetUserId": "uuid",
  "clientActionId": "uuid",
  "actionVariantCode": "ROSE"
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `targetUserId` | UUID | yes | Target user |
| `clientActionId` | UUID | yes | Client-generated idempotency key |
| `actionVariantCode` | string | yes | Variant code from `GET /like-actions` (e.g. `HEART`, `ROSE`) |

**Validation errors:**

| Status | Condition |
|---|---|
| `400` | `actionVariantCode` missing, unknown, or inactive |
| `409` | Variant pricing not configured for the user's plan |
| `402`/`429` | Variant limit exceeded or insufficient credits (existing behavior) |

**Response — new fields (additive, all existing fields unchanged):**

```json
{
  "actionId": "uuid",
  "actionType": "LIKE",
  "status": "ACTIVE",
  "isMatch": false,
  "match": null,
  "dailyLikesRemaining": 8,
  "dailySuperLikesRemaining": 5,
  "superLikeCreditsRemaining": 0,
  "createdAt": "2026-09-18T19:34:00Z",
  "idempotent": false,
  "actionVariantCode": "ROSE",
  "actionVariant": {
    "code": "ROSE",
    "name": "Rose",
    "description": "Send a rose",
    "icon": "https://cdn.qal.app/actions/rose.webp"
  }
}
```

`actionVariant` may be `null` for historical likes recorded before variants existed (or if the variant row was hard-deleted).

### POST /api/v1/discovery/actions/pass and /actions/superlike

Sending `actionVariantCode` in the body now returns `400` (`actionVariantCode is only supported for LIKE actions.`). Omit the field entirely.

### GET /api/v1/discovery/likes

Each item in `items[]` now includes two additive fields:

```json
{
  "actionId": "uuid",
  "userId": "uuid",
  "displayName": "...",
  "actionVariantCode": "ROSE",
  "actionVariant": {
    "code": "ROSE",
    "name": "Rose",
    "description": "Send a rose",
    "icon": "https://cdn.qal.app/actions/rose.webp"
  }
}
```

Applies to both `direction=SENT` and `direction=RECEIVED`.

### GET /api/v1/billing/entitlements

The LIKE entry in `limitsAndCosts` now carries variant-aware fields (additive — existing fields unchanged):

```json
"LIKE": {
  "used": 4,
  "limit": 10,
  "remaining": 6,
  "resetsAt": "2026-09-19T00:00:00Z",
  "memberCreditCost": 1,
  "actualCreditCost": 1,
  "periodType": "DAY",
  "applyCreditAfterLimit": true,
  "variantPricingEnabled": true,
  "variantLimitsEnabled": false,
  "variants": {
    "HEART": {
      "used": 4,
      "limit": 10,
      "remaining": 6,
      "resetsAt": "2026-09-19T00:00:00Z",
      "memberCreditCost": 1,
      "actualCreditCost": 1,
      "periodType": "DAY",
      "applyCreditAfterLimit": true
    },
    "ROSE": {
      "used": 4,
      "limit": 10,
      "remaining": 6,
      "resetsAt": "2026-09-19T00:00:00Z",
      "memberCreditCost": 10,
      "actualCreditCost": 10,
      "periodType": "DAY",
      "applyCreditAfterLimit": true
    }
  }
}
```

Semantics:

- `variantPricingEnabled` / `variantLimitsEnabled` mirror the action-level plan rule (`subscription_plan_limit_and_cost`).
- Each entry in `variants` reports the **effective** values for that variant:
  - Cost fields come from the variant row when `variantPricingEnabled` is `true`, otherwise they mirror the action-level cost.
  - `used` / `limit` / `remaining` / `resetsAt` / `periodType` / `applyCreditAfterLimit` come from the variant row and its own usage tracker when `variantLimitsEnabled` is `true`; otherwise they mirror the shared action-level limit and usage.
- `variants` is `null` for actions without variant configuration.
- Actions other than LIKE are unaffected.

## Admin Config Endpoints

### /api/v1/admin/payment-config/plan-limit-costs (updated)

Requests and responses now include `variantPricingEnabled` and `variantLimitsEnabled` (booleans, default `false` for create, omitted = unchanged on update). When `variantPricingEnabled`/`variantLimitsEnabled` is `true`, the corresponding values are resolved from `subscription_plan_variant_limit_and_cost` per variant — a missing variant row fails safe with `409` at action time (no silent fallback to free/unlimited).

### /api/v1/admin/payment-config/plan-variant-limit-costs (new)

CRUD for `subscription_plan_variant_limit_and_cost` (per-plan, per-variant pricing/limits):

| Method | Path | Notes |
|---|---|---|
| `GET` | `/plan-variant-limit-costs` | List all rows |
| `GET` | `/plan-variant-limit-costs/{id}` | Get one row |
| `POST` | `/plan-variant-limit-costs` | Create row |
| `PUT` | `/plan-variant-limit-costs/{id}` | Update row (partial, omitted fields unchanged) |
| `DELETE` | `/plan-variant-limit-costs/{id}` | Delete row (blocked with `409` if usage tracker rows reference it) |

Row shape:

```json
{
  "id": "uuid",
  "subscriptionPlanId": "uuid",
  "actionFeatureVariantId": "uuid",
  "memberCreditCost": 10,
  "actualCreditCost": 10,
  "limitValue": null,
  "periodType": "DAY",
  "applyCreditAfterLimit": false
}
```

- `limitValue: null` means unlimited.
- `periodType`: `DAY` | `MONTH` | `BILLING_CYCLE`.
- Unique per (`subscriptionPlanId`, `actionFeatureVariantId`).
- Costs/limits in a variant row only take effect when the matching action-level flags (`variantPricingEnabled` / `variantLimitsEnabled`) are enabled.

## Mobile Integration Notes

1. On app start (or before rendering the swipe screen), call `GET /like-actions` and render the returned variants (code, name, icon, credits, sortOrder). Do not hardcode the variant list client-side.
2. When the user taps a variant button, send its `code` as `actionVariantCode` in `POST /actions/like`.
3. Pass `clientActionId` as before for idempotency; retries with the same `clientActionId` return the original action (idempotent, no double charge).
4. Handle `400` (invalid variant) by refreshing `GET /like-actions` — the variant may have been deactivated or renamed.
5. Historical likes without a variant have `actionVariantCode: null` — render them as a regular like (heart).

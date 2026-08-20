# AI Prompt: Qaliye Backend API Update — CREDITS Promotion Benefit & Entitlements Refactor

> Paste the prompt below into your AI coding assistant (Cursor, Copilot, etc.) to integrate the client app with the updated Qaliye backend API.

---

## Prompt

You are integrating the Qaliye mobile client app with the updated backend billing API. The backend has introduced a new `CREDITS` promotion benefit type and refactored the entitlements and boost endpoints. Below is the full specification of what changed and what the client needs to do.

### 1. New `CREDITS` Promotion Benefit Type

The backend now supports a third promotion benefit type called `CREDITS` alongside the existing `FREE_PREMIUM` and `DISCOUNT`. When a user redeems a `CREDITS` promotion, they receive credits (the in-app currency) instead of a free premium subscription or a discount.

#### Affected Endpoints

**GET `/api/v1/billing/promotions`** — List Eligible Promotions

The response array items (`EligiblePromotionDto`) now include a new field:

```json
{
  "campaign_id": "uuid",
  "campaign_key": "string",
  "name": "string",
  "description": "string",
  "trigger_type": "USER_CLAIM | PURCHASE | AUTO_ON_SIGNUP",
  "benefit_type": "FREE_PREMIUM | DISCOUNT | CREDITS",
  "discount_type": "string | null",
  "discount_value": "long | null",
  "discount_currency": "string | null",
  "subscription_product_id": "uuid | null",
  "duration_days": "int | null",
  "max_redemptions": "int | null",
  "reserved_count": 0,
  "fulfilled_count": 0,
  "ends_at": "ISO-8601 string | null",
  "target_gender": "MALE | FEMALE | null",
  "included_credits": "long | null",
  "can_redeem": true
}
```

**New field:**
- `included_credits` (`Long | null`): The number of credits granted when the benefit type is `CREDITS`. Will be `null` for `FREE_PREMIUM` and `DISCOUNT` promotions.

**`can_redeem`** is now `true` for both `FREE_PREMIUM` and `CREDITS` benefit types when `trigger_type` is `USER_CLAIM`.

**Client action:**
- Update the promotion card UI to handle `benefit_type: "CREDITS"`.
- Display the `included_credits` value (e.g., "Get 50 credits!") instead of "Free Premium" or "X% off".
- Keep the existing "Redeem" button visible when `can_redeem` is `true`.

---

**GET `/api/v1/billing/promotions/{campaignKey}`** — Get Single Campaign

Same new `included_credits` field in the response. Same handling as above.

---

**POST `/api/v1/billing/promotions/{campaignKey}/redeem`** — Redeem Promotion

The response (`RedeemPromotionResponse`) now includes a new field:

```json
{
  "redemption_id": "uuid",
  "subscription_id": "uuid | null",
  "campaign_key": "string",
  "plan_code": "string | null",
  "duration_days": "int | null",
  "period_end": "ISO-8601 | null",
  "credits_granted": "long | null",
  "message": "string"
}
```

**New field:**
- `credits_granted` (`Long | null`): The number of credits actually granted. Present (non-null) when `benefit_type` is `CREDITS`. For `FREE_PREMIUM` redemptions, this may also be non-null if the campaign includes bonus credits alongside the free premium subscription.

**Behavior by benefit type:**
- `FREE_PREMIUM`: `subscription_id`, `plan_code`, `duration_days`, and `period_end` are populated. `credits_granted` may be non-null if the campaign also grants included credits.
- `CREDITS`: `subscription_id`, `plan_code`, `duration_days`, and `period_end` are all `null`. `credits_granted` contains the number of credits granted.
- `DISCOUNT`: Not redeemable via this endpoint (only `USER_CLAIM` trigger with `FREE_PREMIUM` or `CREDITS` is supported).

**Client action:**
- After a successful redeem, check `credits_granted`:
  - If non-null and `subscription_id` is `null`: show a success message like "You received {credits_granted} credits!"
  - If `subscription_id` is non-null: show the existing "Free Premium activated" message, and optionally mention bonus credits if `credits_granted` is also non-null.
- Handle the `409 Conflict` response with `promotion_capacity_exhausted` or `promotion_already_redeemed` error codes (existing behavior, unchanged).
- Handle the `409 Conflict` response with a `grant_failed` reason message if the credit grant fails server-side.

---

### 2. Entitlements Response — Updated `credits` Object

**GET `/api/v1/billing/entitlements`**

The `credits` field in the `EntitlementResponse` has been refactored. The old per-action credit balances (`boosts_available`, `super_likes_available`, `rewinds_available`) are now always `0` — all actions draw from a single central credit balance.

**Before (old):**
```json
{
  "credits": {
    "credit_balance": 0,
    "boosts_available": 2,
    "super_likes_available": 5,
    "rewinds_available": 3
  }
}
```

**After (new):**
```json
{
  "credits": {
    "credit_balance": 42,
    "boosts_available": 0,
    "super_likes_available": 0,
    "rewinds_available": 0
  }
}
```

**Full `EntitlementResponse` shape:**
```json
{
  "plan": "FREE | PREMIUM | FREE_PREMIUM",
  "subscription": {
    "status": "string",
    "provider": "string",
    "billing_interval_count": "int | null",
    "billing_interval_unit": "string | null",
    "expires_at": "ISO-8601 | null",
    "auto_renew": true
  },
  "limits": {
    "likes": { "used": 10, "limit": 30, "remaining": 20, "resets_at": "ISO-8601" },
    "super_likes": { "used": 0, "limit": 5, "remaining": 5, "resets_at": "ISO-8601" },
    "rewinds": { "used": 0, "limit": null, "remaining": null, "resets_at": "ISO-8601" },
    "boosts": { "used": 0, "limit": 1, "remaining": 1, "resets_at": null },
    "voice_chat_msgs": { "used": 0, "limit": null, "remaining": null, "resets_at": "ISO-8601" },
    "image_chat_msgs": { "used": 0, "limit": null, "remaining": null, "resets_at": "ISO-8601" }
  },
  "credits": {
    "credit_balance": 42,
    "boosts_available": 0,
    "super_likes_available": 0,
    "rewinds_available": 0
  },
  "active_boost": {
    "started_at": "ISO-8601",
    "expires_at": "ISO-8601",
    "remaining_seconds": 1200
  },
  "features": {
    "see_who_liked_you": true,
    "advanced_filters": true,
    "incognito_mode": false
  },
  "plan_limits": {
    "LIKE": 100,
    "SUPER_LIKE": 10,
    "BOOST": 5,
    "REWIND": null,
    "VOICE_MESSAGE": null,
    "IMAGE_MESSAGE": null
  },
  "boost_duration_minutes": 30,
  "country_settings": {
    "country_code": "ET",
    "subscription_enabled": true,
    "credits_enabled": true,
    "identity_verification_required": false
  },
  "costs": {
    "BOOST": {
      "member_credit_cost": 1,
      "actual_credit_cost": 1,
      "limit_value": 5,
      "period_type": "DAY",
      "apply_credit_after_limit": true
    }
  }
}
```

**Client action:**
- Replace all usages of `credits.boosts_available`, `credits.super_likes_available`, and `credits.rewinds_available` with `credits.credit_balance`.
- The credit balance shown in the UI should come from `credits.credit_balance`. This is the single source of truth for how many credits the user has.
- The `limits` object still shows per-action quotas (daily limits, etc.) — these are informational and show how many free actions the user has from their subscription plan. The `costs` object shows the credit cost for each action beyond the free limit.
- The `country_settings` object tells the client whether subscriptions and credits are enabled for the user's country. Use `subscription_enabled` and `credits_enabled` to show/hide the respective UI sections.

---

### 3. Boost Activation — Updated `credits_remaining`

**POST `/api/v1/billing/boosts/activate`**

The response (`BoostActivationResponse`) is unchanged in shape but the `credits_remaining` field now reflects the **central credit balance** after the boost is activated, not the old per-type boost credit balance.

```json
{
  "boost_id": "uuid",
  "started_at": "ISO-8601",
  "expires_at": "ISO-8601",
  "credits_remaining": 41
}
```

**Client action:**
- `credits_remaining` now represents the user's total credit balance after the boost consumed its credit cost. Update any UI that displays remaining credits after boosting to use this value directly.
- If the user has insufficient credits, the endpoint returns `402 Payment Required` with:
  ```json
  { "error": { "code": "insufficient_credits", "message": "You don't have enough credits for this action." } }
  ```
  Handle this by prompting the user to buy credits or upgrade their plan.

---

### 4. Admin API — Create Campaign with CREDITS Benefit

**POST `/api/v1/admin/billing/campaigns`**

The `CreateCampaignRequest` body now accepts an `included_credits` field:

```json
{
  "campaign_key": "string",
  "name": "string",
  "description": "string",
  "trigger_type": "USER_CLAIM | PURCHASE | AUTO_ON_SIGNUP",
  "eligibility_type": "ALL_USERS | NEW_USER",
  "benefit_type": "FREE_PREMIUM | DISCOUNT | CREDITS",
  "discount_type": "PERCENTAGE | FIXED | null",
  "discount_value": "long | null",
  "discount_currency": "string | null",
  "subscription_product_id": "uuid | null",
  "consumable_product_id": "uuid | null",
  "country_code": "string",
  "duration_days": "int | null",
  "new_user_window_days": "int | null",
  "max_redemptions": "int | null",
  "max_redemptions_per_user": "int | null",
  "priority": "int | null",
  "starts_at": "ISO-8601",
  "ends_at": "ISO-8601 | null",
  "target_gender": "MALE | FEMALE | null",
  "included_credits": "long | null"
}
```

**Validation rules for `CREDITS` benefit type:**
- `included_credits` is **required** and must be `> 0`.
- `subscription_product_id` and `consumable_product_id` must **not** be set (must be `null`).
- `duration_days` is not required (credits don't have a duration).
- `trigger_type` can be `USER_CLAIM`, `PURCHASE`, or `AUTO_ON_SIGNUP`.
- `eligibility_type`, `country_code`, and `starts_at` are still required (same as other benefit types).

**The `CampaignDto` response** (returned by create, get, list, activate, pause, expire) now includes `included_credits`:

```json
{
  "id": "uuid",
  "campaign_key": "string",
  "name": "string",
  "description": "string",
  "trigger_type": "string",
  "eligibility_type": "string",
  "benefit_type": "FREE_PREMIUM | DISCOUNT | CREDITS",
  "discount_type": "string | null",
  "discount_value": "long | null",
  "discount_currency": "string | null",
  "subscription_product_id": "uuid | null",
  "consumable_product_id": "uuid | null",
  "country_code": "string",
  "duration_days": "int | null",
  "new_user_window_days": "int | null",
  "max_redemptions": "int | null",
  "max_redemptions_per_user": 1,
  "reserved_count": 0,
  "fulfilled_count": 0,
  "priority": 0,
  "starts_at": "ISO-8601",
  "ends_at": "ISO-8601 | null",
  "status": "DRAFT | ACTIVE | PAUSED | EXPIRED",
  "target_gender": "string | null",
  "included_credits": "long | null",
  "created_at": "ISO-8601",
  "updated_at": "ISO-8601"
}
```

---

### 5. Country Settings Endpoint (unchanged but now more relevant)

**GET `/api/v1/billing/country-settings`**

```json
{
  "country_code": "ET",
  "subscription_enabled": true,
  "credits_enabled": true,
  "identity_verification_required": false
}
```

**Client action:**
- Call this on app launch to determine which features to show.
- If `credits_enabled` is `false`, hide credit-purchase UI and credit-based promotion cards.
- If `subscription_enabled` is `false`, hide subscription/premium purchase UI.
- This data is also embedded in the `GET /entitlements` response under `country_settings`.

---

### 6. Signup Promotions

When a new user signs up, the backend may automatically grant them a `CREDITS` promotion (if an `AUTO_ON_SIGNUP` campaign with `CREDITS` benefit type is active for their country). The signup response or the first entitlements fetch will reflect the granted credits in `credits.credit_balance`. No additional client-side call is needed — the credits appear automatically.

---

### Summary of Client Changes Required

1. **Promotion list/detail UI**: Handle `benefit_type: "CREDITS"` with `included_credits` display.
2. **Redeem flow**: Check `credits_granted` in the redeem response. Show appropriate success message.
3. **Entitlements**: Use `credits.credit_balance` as the single source of truth for credit balance. Ignore `boosts_available`/`super_likes_available`/`rewinds_available` (always 0 now).
4. **Boost activation**: Handle `402 Payment Required` with `insufficient_credits` error code. Display `credits_remaining` from the response.
5. **Admin panel**: Add `included_credits` field to campaign creation form. Show it in campaign details. Validate per the rules above.
6. **Country settings**: Use `credits_enabled` and `subscription_enabled` flags to gate UI sections.

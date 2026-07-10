# Payment Related Tables

This document describes every database table involved in the Qaliye payment, billing, subscription, entitlement, and boost system. Tables are grouped by functional area. Column definitions reflect the **final state** after all migrations (V1–V25) have been applied.

---

## 1. Subscription Plans & Limits

### `subscription_plans`

Defines the high-level subscription tiers (e.g. Free, Premium). Each plan has a `plan_kind` of `FREE` or `PAID`. A user on a free plan never gets a `user_subscriptions` row — the backend simply falls back to the country-specific or global FREE plan. Paid plans are referenced by `user_subscriptions`.

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `name` | VARCHAR(100) | NOT NULL, length 1–100 |
| `plan_code` | VARCHAR(50) | NOT NULL, length 1–50 |
| `country_code` | VARCHAR(10) | NOT NULL, default `'GLOBAL'` |
| `plan_kind` | VARCHAR(20) | NOT NULL, CHECK in (`'FREE'`, `'PAID'`) |
| `price_minor_units` | INTEGER | NOT NULL, CHECK `>= 0` |
| `currency` | VARCHAR(3) | NOT NULL, default `'USD'` |
| `billing_interval` | VARCHAR(20) | NOT NULL, CHECK in (`'NONE'`, `'WEEKLY'`, `'MONTHLY'`, `'YEARLY'`) |
| `features` | JSONB | NOT NULL, default `'{}'`, must be object |
| `is_active` | BOOLEAN | NOT NULL, default `TRUE` |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |

**Unique constraints:** `unique_plan_code_per_country (plan_code, country_code)`, `unique_active_free_plan_per_country` (partial — one active FREE plan per country).

**Why needed:** This is the root catalog for all subscription tiers. Every user's entitlements are derived from which plan they are on (either explicitly via `user_subscriptions` or implicitly via the FREE fallback).

---

### `subscription_plan_limits`

Stores per-plan quota limits for actions like likes, super-likes, rewinds, and boosts. Each active plan must have one row for every supported `limit_type`. A `NULL` `limit_value` means unlimited.

| Column | Type | Constraints |
|---|---|---|
| `plan_id` | UUID | NOT NULL, FK → `subscription_plans(id)` ON DELETE CASCADE |
| `limit_type` | VARCHAR(30) | NOT NULL, CHECK in (`'LIKES'`, `'SUPERLIKES'`, `'REWINDS'`, `'BOOSTS'`) |
| `limit_value` | INTEGER | CHECK `IS NULL OR >= 0` |
| `period_type` | VARCHAR(30) | default `'DAILY'`, CHECK in (`'DAILY'`, `'SUBSCRIPTION_MONTH'`, `'BILLING_CYCLE'`) |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |

**Primary key:** `(plan_id, limit_type)`.

**Why needed:** Decouples quota configuration from the plan's `features` JSON. The application reads these rows to enforce daily and per-cycle limits on user actions.

---

### `subscription_products`

Separates billing periods (1-month, 3-month, 6-month) from the base plan. While `subscription_plans` defines *what tier* a user gets, `subscription_products` defines *how long* the billing cycle is. Multiple products can map to the same plan (e.g. Premium Monthly and Premium 6-Month both map to the PREMIUM plan).

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `plan_id` | UUID | NOT NULL, FK → `subscription_plans(id)` ON DELETE RESTRICT |
| `product_code` | VARCHAR(100) | NOT NULL, UNIQUE |
| `billing_interval_unit` | VARCHAR(20) | NOT NULL, CHECK in (`'DAY'`, `'WEEK'`, `'MONTH'`, `'YEAR'`) |
| `billing_interval_count` | SMALLINT | NOT NULL, CHECK `> 0` |
| `auto_renew_supported` | BOOLEAN | NOT NULL, default `TRUE` |
| `is_active` | BOOLEAN | NOT NULL, default `TRUE` |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |

**Why needed:** Enables offering multiple billing durations for the same plan (e.g. monthly vs. 6-month prepay) without duplicating plan definitions. Referenced by `payment_offers` to link a purchasable offer to a specific billing cycle.

---

### `consumable_products`

Defines one-time purchase products that grant consumable credits (boosts, super-likes, rewinds). Unlike subscriptions, these are not recurring and grants expire if an `expires_after_days` is set.

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `product_code` | VARCHAR(100) | NOT NULL, UNIQUE |
| `name` | VARCHAR(100) | NOT NULL |
| `entitlement_type` | VARCHAR(30) | NOT NULL, CHECK in (`'BOOST_CREDIT'`, `'SUPERLIKE_CREDIT'`, `'REWIND_CREDIT'`) |
| `quantity_granted` | INTEGER | NOT NULL, CHECK `> 0` |
| `expires_after_days` | INTEGER | CHECK `IS NULL OR > 0` |
| `is_active` | BOOLEAN | NOT NULL, default `TRUE` |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |

**Why needed:** Catalog of purchasable consumable packs. Referenced by `payment_offers` to link a purchasable offer to a specific consumable product. When a purchase is verified, the `quantity_granted` is posted to the `user_entitlement_ledger`.

---

## 2. Payment Offers & Methods

### `payment_offers`

Represents **what is sold** — a specific product (subscription or consumable) priced for a particular country and platform. An offer references exactly one product (either a `subscription_product_id` or a `consumable_product_id`, never both). The actual **how to pay** is determined by `payment_methods`, which are joined at order creation time.

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `subscription_product_id` | UUID | FK → `subscription_products(id)` ON DELETE SET NULL |
| `consumable_product_id` | UUID | FK → `consumable_products(id)` ON DELETE SET NULL |
| `country_code` | VARCHAR(10) | NOT NULL, default `'GLOBAL'` |
| `platform` | VARCHAR(20) | NOT NULL, CHECK in (`'ANDROID'`, `'IOS'`, `'WEB'`) |
| `currency` | VARCHAR(3) | NOT NULL |
| `price_minor_units` | INTEGER | NOT NULL, CHECK `>= 0` |
| `external_product_id` | VARCHAR(255) | nullable (store product ID for RevenueCat/Stripe) |
| `revenuecat_offering_id` | VARCHAR(100) | nullable |
| `revenuecat_package_id` | VARCHAR(100) | nullable |
| `auto_renew` | BOOLEAN | NOT NULL, default `FALSE` |
| `is_active` | BOOLEAN | NOT NULL, default `TRUE` |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |

**Check constraint:** `check_offer_has_exactly_one_product` — exactly one of `subscription_product_id` / `consumable_product_id` must be non-NULL.

**Unique indexes:** `unique_payment_offer_subscription (country_code, platform, subscription_product_id)` (partial), `unique_payment_offer_consumable (country_code, platform, consumable_product_id)` (partial).

**Why needed:** This is the pricing catalog. The mobile app queries offers by country + platform to display available purchase options. Each offer is paired with a `payment_method` when a `payment_order` is created.

> **Note:** V20 originally included `payment_channel` and `payment_method` columns on `payment_offers`. V21 removed them — payment routing is now handled by `payment_methods`.

---

### `payment_methods`

Defines **how users pay** in a given market (country + platform). Each method specifies a `payment_channel` (either `ONLINE_PAYMENT` for gateway-processed or `MANUAL_TRANSFER` for bank/mobile-money transfers) and a `payment_method` code. Manual transfer methods include templated `payment_instructions` with placeholders like `{{EXPECTED_AMOUNT}}`, `{{ORDER_REFERENCE}}`, etc.

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `country_code` | VARCHAR(10) | NOT NULL, default `'GLOBAL'` |
| `platform` | VARCHAR(20) | NOT NULL, CHECK in (`'ANDROID'`, `'IOS'`, `'WEB'`) |
| `method_code` | VARCHAR(100) | NOT NULL (lowercase, e.g. `'apple'`, `'telebirr'`, `'cbe'`) |
| `display_name` | VARCHAR(150) | NOT NULL |
| `payment_channel` | VARCHAR(50) | NOT NULL, CHECK in (`'ONLINE_PAYMENT'`, `'MANUAL_TRANSFER'`) |
| `payment_method` | VARCHAR(50) | NOT NULL (e.g. `'APPLE_IAP'`, `'TELEBIRR'`, `'BANK_TRANSFER'`) |
| `payment_instructions` | TEXT | nullable, template with `{{...}}` placeholders |
| `verification_params` | JSONB | nullable (added in V24) |
| `is_active` | BOOLEAN | NOT NULL, default `TRUE` |
| `display_order` | SMALLINT | NOT NULL, default `0` |
| `metadata` | JSONB | NOT NULL, default `'{}'` |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |

**Unique constraint:** `unique_payment_method_market (country_code, platform, method_code)`.

**Unique index:** `unique_active_online_payment_per_market` — only one active `ONLINE_PAYMENT` channel per country + platform.

**Why needed:** Separates payment routing from product pricing. A `payment_order` references both a `payment_offer` (what to buy) and a `payment_method` (how to pay). The market-matching trigger ensures the offer and method belong to the same country + platform.

---

## 3. Payment Orders, Proofs & Verification

### `payment_orders`

Created when a user initiates a purchase. Captures the offer being purchased, the payment method selected, the expected amount, and the order lifecycle status. The order transitions through statuses: `CREATED` → `AWAITING_PAYMENT` → `RECEIPT_SUBMITTED` → `VERIFICATION_PENDING` → `VERIFIED` (or `REJECTED` / `EXPIRED` / `CANCELLED`).

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `user_id` | UUID | NOT NULL, FK → `app_users(id)` ON DELETE RESTRICT |
| `payment_offer_id` | UUID | NOT NULL, FK → `payment_offers(id)` ON DELETE RESTRICT |
| `payment_method_id` | UUID | NOT NULL, FK → `payment_methods(id)` ON DELETE RESTRICT |
| `order_reference` | VARCHAR(100) | NOT NULL, UNIQUE |
| `status` | VARCHAR(40) | NOT NULL, default `'CREATED'`, CHECK in (`'CREATED'`, `'AWAITING_PAYMENT'`, `'RECEIPT_SUBMITTED'`, `'VERIFICATION_PENDING'`, `'MANUAL_REVIEW'`, `'VERIFIED'`, `'REJECTED'`, `'EXPIRED'`, `'CANCELLED'`) |
| `expected_amount_minor_units` | INTEGER | NOT NULL, CHECK `> 0` |
| `expected_currency` | VARCHAR(3) | NOT NULL |
| `payment_instruction_snapshot` | JSONB | NOT NULL, default `'{}'` |
| `provider_checkout_url` | TEXT | nullable |
| `provider_order_reference` | VARCHAR(255) | nullable |
| `expires_at` | TIMESTAMPTZ | NOT NULL |
| `idempotency_key` | VARCHAR(255) | nullable |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |

**Indexes:** `idx_payment_orders_user_status (user_id, status)`, `idx_payment_orders_status_created (status, created_at DESC)`, `idx_payment_orders_idempotency (user_id, idempotency_key)` (partial, unique).

**Trigger:** `trg_validate_payment_order_market` — ensures the offer's country/platform matches the payment method's country/platform.

**Why needed:** Central order record that tracks the full lifecycle of a user's purchase attempt. Links the offer (product + price) to the payment method (how to pay) and drives the verification workflow.

---

### `payment_proofs`

Stores the proof of payment submitted by the user for a manual transfer order. A proof can be either a transaction reference number (e.g. Telebirr transfer reference) or an uploaded receipt file.

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `payment_order_id` | UUID | NOT NULL, FK → `payment_orders(id)` ON DELETE RESTRICT |
| `proof_type` | VARCHAR(30) | NOT NULL, CHECK in (`'TRANSACTION_REFERENCE'`, `'RECEIPT_UPLOAD'`) |
| `payment_network` | VARCHAR(50) | nullable (e.g. `'TELEBIRR'`, `'CBE_BIRR'`) |
| `transaction_reference` | VARCHAR(255) | nullable |
| `receipt_storage_bucket` | VARCHAR(100) | nullable |
| `receipt_storage_path` | TEXT | nullable |
| `submitted_amount_minor_units` | INTEGER | nullable |
| `submitted_currency` | VARCHAR(3) | nullable |
| `submitted_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |

**Index:** `idx_payment_proofs_order (payment_order_id)`.

**Why needed:** When a user makes a manual bank/mobile-money transfer, they must submit proof. This table stores that proof — either a transaction reference string (for automated verification via Verify.et) or a receipt file path (for admin review). A verification attempt is then created from this proof.

---

### `payment_verification_attempts`

Records each attempt to verify a payment proof. An attempt can use automated verification (Verify.et API, Chapa API) or manual admin review. Multiple attempts can be made for the same order if earlier ones fail.

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `payment_order_id` | UUID | NOT NULL, FK → `payment_orders(id)` ON DELETE RESTRICT |
| `payment_proof_id` | UUID | FK → `payment_proofs(id)` ON DELETE SET NULL |
| `verification_method` | VARCHAR(50) | NOT NULL, CHECK in (`'CHAPA_API'`, `'VERIFY_ET'`, `'ADMIN_REVIEW'`) |
| `provider_request_id` | VARCHAR(255) | nullable |
| `provider_verification_reference` | VARCHAR(255) | nullable |
| `status` | VARCHAR(30) | NOT NULL, default `'PENDING'`, CHECK in (`'PENDING'`, `'VERIFIED'`, `'NOT_FOUND'`, `'AMOUNT_MISMATCH'`, `'RECIPIENT_MISMATCH'`, `'DUPLICATE_PAYMENT'`, `'MANUAL_REVIEW'`, `'REJECTED'`, `'FAILED'`) |
| `verified_amount_minor_units` | INTEGER | nullable |
| `verified_currency` | VARCHAR(3) | nullable |
| `verified_recipient_reference` | VARCHAR(255) | nullable |
| `verified_paid_at` | TIMESTAMPTZ | nullable |
| `raw_response` | JSONB | NOT NULL, default `'{}'` |
| `verified_by_admin_id` | UUID | FK → `app_users(id)` ON DELETE SET NULL |
| `admin_decision_note` | TEXT | nullable |
| `verify_et_request_id` | VARCHAR(36) | nullable (added in V25) |
| `verify_et_idempotency_key` | VARCHAR(255) | nullable (added in V25) |
| `settlement_account_matched` | BOOLEAN | nullable (added in V25) |
| `confirmed_before` | BOOLEAN | nullable (added in V25) |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |

**Indexes:** `idx_payment_verification_order (payment_order_id)`, `idx_pva_verify_et_request_id (verify_et_request_id)` (partial), `idx_unique_verified_provider_reference (verification_method, provider_verification_reference)` (partial, unique — prevents re-use of a verified transfer reference).

**Why needed:** Tracks the verification lifecycle for each payment proof. Supports both automated (Verify.et, Chapa) and manual (admin review) verification paths. The Verify.et-specific columns enable webhook correlation and idempotent verification requests.

---

## 4. User Subscriptions

### `user_subscriptions`

Records a user's active or historical paid subscription. A user can have at most one active subscription at a time. The `provider_subscription_id` links to the external provider's subscription identifier (e.g. Stripe subscription ID, RevenueCat original transaction ID).

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `user_id` | UUID | NOT NULL, FK → `app_users(id)` ON DELETE RESTRICT |
| `plan_id` | UUID | NOT NULL, FK → `subscription_plans(id)` ON DELETE RESTRICT |
| `provider` | VARCHAR(50) | NOT NULL, CHECK in (`'STRIPE'`, `'APPLE_APP_STORE'`, `'GOOGLE_PLAY'`, `'TELEBIRR'`, `'CBE_BIRR'`, `'CHAPA'`, `'ARIFPAY'`, `'BANK_TRANSFER'`) |
| `provider_subscription_id` | VARCHAR(255) | nullable |
| `status` | VARCHAR(30) | NOT NULL, CHECK in (`'ACTIVE'`, `'PAST_DUE'`, `'CANCELED'`, `'UNPAID'`, `'PENDING_VERIFICATION'`, `'GRACE_PERIOD'`, `'EXPIRED'`, `'REVOKED'`) |
| `started_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |
| `current_period_start` | TIMESTAMPTZ | NOT NULL |
| `current_period_end` | TIMESTAMPTZ | NOT NULL |
| `cancelled_at` | TIMESTAMPTZ | nullable |
| `payment_offer_id` | UUID | FK → `payment_offers(id)` ON DELETE SET NULL (added in V20) |
| `provider_subscription_reference` | VARCHAR(512) | nullable (added in V20) |
| `auto_renew` | BOOLEAN | NOT NULL, default `FALSE` (added in V20) |
| `ended_at` | TIMESTAMPTZ | nullable (added in V20) |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |

**Check constraint:** `check_subscription_period` — `current_period_end > current_period_start`.

**Unique indexes:** `unique_provider_subscription_reference (provider, provider_subscription_id)` (partial), `unique_active_subscription_per_user (user_id)` (partial — only one ACTIVE or PENDING_VERIFICATION per user).

**Trigger:** `validate_user_subscription_paid_plan` — ensures `plan_id` references a `PAID` plan (FREE plans never get a subscription row).

**Why needed:** The authoritative record of who has an active paid subscription. The application checks this table to determine whether a user has Premium features. Webhook handlers from Stripe/RevenueCat update the status and period dates here.

---

## 5. Transactions

### `transactions`

The general-purpose payment transaction ledger. Every monetary event (subscription purchase, consumable pack purchase, profile boost, refund) creates a transaction row. This is the financial audit trail.

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `user_id` | UUID | NOT NULL, FK → `app_users(id)` ON DELETE RESTRICT |
| `subscription_id` | UUID | FK → `user_subscriptions(id)` ON DELETE SET NULL |
| `plan_id` | UUID | FK → `subscription_plans(id)` ON DELETE RESTRICT (added in V2) |
| `payment_order_id` | UUID | FK → `payment_orders(id)` ON DELETE SET NULL (added in V20) |
| `payment_offer_id` | UUID | FK → `payment_offers(id)` ON DELETE SET NULL (added in V20) |
| `related_transaction_id` | UUID | FK → `transactions(id)` ON DELETE SET NULL (added in V20, for refunds/reversals) |
| `payment_purpose` | VARCHAR(30) | NOT NULL, CHECK in (`'SUBSCRIPTION'`, `'CONSUMABLE_PACK'`, `'PROFILE_BOOST'`, `'CONSUMABLE'`) |
| `transaction_type` | VARCHAR(30) | default `'PURCHASE'` (added in V20) |
| `amount_minor_units` | INTEGER | NOT NULL, CHECK `>= 0` |
| `currency` | VARCHAR(3) | NOT NULL |
| `provider` | VARCHAR(50) | NOT NULL, CHECK in (`'STRIPE'`, `'APPLE_APP_STORE'`, `'GOOGLE_PLAY'`, `'TELEBIRR'`, `'CBE_BIRR'`, `'CHAPA'`, `'ARIFPAY'`, `'BANK_TRANSFER'`, `'REVENUECAT'`, `'ADMIN'`, `'cbe'`, `'telebirr'`, `'cbebirr'`, `'mpesa'`, `'boa'`, `'awash'`, `'dashen'`, `'siinqee'`, `'kaafiebirr'`, `'zemen'`) |
| `provider_transaction_id` | VARCHAR(255) | nullable |
| `verification_provider` | VARCHAR(50) | nullable (added in V20) |
| `country_code` | VARCHAR(10) | nullable (added in V20) |
| `status` | VARCHAR(30) | NOT NULL, default `'PENDING'`, CHECK in (`'PENDING'`, `'COMPLETED'`, `'FAILED'`, `'MANUAL_REVIEW'`, `'REFUNDED'`, `'PARTIALLY_REFUNDED'`, `'REVERSED'`) |
| `receipt_storage_bucket` | VARCHAR(100) | nullable |
| `receipt_storage_path` | TEXT | nullable |
| `admin_notes` | TEXT | nullable |
| `tax_amount_minor_units` | INTEGER | nullable (added in V20) |
| `provider_fee_minor_units` | INTEGER | nullable (added in V20) |
| `merchant_net_amount_minor_units` | INTEGER | nullable (added in V20) |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |

**Check constraint:** `check_receipt_bucket_and_path_together` — both bucket and path must be NULL or both non-NULL.

**Unique index:** `unique_provider_transaction_reference (provider, provider_transaction_id)` (partial).

**Indexes:** `idx_transactions_user (user_id, created_at DESC)`, `idx_transactions_status_created (status, created_at DESC)`.

**Why needed:** The financial ledger of the application. Every payment — whether from Stripe, RevenueCat, manual bank transfer, or admin grant — is recorded here. The `related_transaction_id` enables refund/reversal chains. Admin tools query this table to review manual payments and issue refunds.

---

### `payment_events`

Idempotency log for incoming webhook events from payment providers. Each event is stored with its raw JSON payload. The unique constraint on `(provider, provider_event_id)` prevents duplicate processing of the same webhook delivery.

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `user_id` | UUID | FK → `app_users(id)` ON DELETE SET NULL |
| `subscription_id` | UUID | FK → `user_subscriptions(id)` ON DELETE SET NULL |
| `transaction_id` | UUID | FK → `transactions(id)` ON DELETE SET NULL (added in V20) |
| `payment_order_id` | UUID | FK → `payment_orders(id)` ON DELETE SET NULL (added in V20) |
| `provider` | VARCHAR(50) | NOT NULL, CHECK in (`'STRIPE'`, `'REVENUECAT'`, `'APPLE_APP_STORE'`, `'GOOGLE_PLAY'`, `'TELEBIRR'`, `'CBE_BIRR'`, `'CHAPA'`, `'ARIFPAY'`, `'BANK_TRANSFER'`, `'VERIFY_ET'`) |
| `provider_event_id` | VARCHAR(255) | NOT NULL |
| `event_type` | VARCHAR(100) | NOT NULL |
| `amount_minor_units` | INTEGER | CHECK `IS NULL OR >= 0` |
| `currency` | VARCHAR(3) | nullable |
| `raw_payload` | JSONB | NOT NULL, default `'{}'`, must be object |
| `processing_status` | VARCHAR(30) | NOT NULL, default `'PROCESSED'` (added in V20) |
| `signature_verified_at` | TIMESTAMPTZ | nullable (added in V20) |
| `processed_at` | TIMESTAMPTZ | nullable (added in V20) |
| `processing_error` | TEXT | nullable (added in V20) |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |

**Unique index:** `unique_provider_payment_event (provider, provider_event_id)`.

**Index:** `idx_payment_events_subscription (subscription_id, created_at DESC)`.

**Why needed:** Webhook idempotency. When a provider retries a webhook delivery (which is common), the unique constraint on `(provider, provider_event_id)` ensures the event is processed only once. The raw payload is preserved for debugging and audit.

---

## 6. Billing Customers

### `billing_customers`

Maps internal Qaliye users to external billing provider customer accounts (e.g. RevenueCat app user IDs, Stripe customer IDs). This is needed when a provider's webhook references a customer ID that must be resolved to a Qaliye `app_users.id`.

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `user_id` | UUID | NOT NULL, FK → `app_users(id)` ON DELETE RESTRICT |
| `provider` | VARCHAR(50) | NOT NULL |
| `external_customer_id` | VARCHAR(255) | NOT NULL |
| `original_external_customer_id` | VARCHAR(255) | nullable |
| `metadata` | JSONB | NOT NULL, default `'{}'` |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |

**Unique constraints:** `unique_billing_customer_provider_external (provider, external_customer_id)`, `unique_billing_customer_user_provider (user_id, provider)`.

**Why needed:** When a user purchases via RevenueCat or Stripe, those providers create their own customer records. This mapping table allows webhook handlers to resolve external customer IDs back to Qaliye user IDs. The `original_external_customer_id` preserves the initial ID even if the provider reassigns it.

---

## 7. Entitlements & Credits

### `user_entitlement_ledger`

Append-only ledger for all entitlement credit movements. Every time a user gains credits (purchase, subscription allowance, admin grant) or consumes credits (using a super-like, rewind, boost), a ledger entry is recorded. The balance is derived by summing `quantity_delta` — never by mutating existing rows.

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `user_id` | UUID | NOT NULL, FK → `app_users(id)` ON DELETE RESTRICT |
| `entitlement_type` | VARCHAR(30) | NOT NULL, CHECK in (`'SUPERLIKE_CREDIT'`, `'REWIND_CREDIT'`, `'BOOST_CREDIT'`, `'PREMIUM_ACCESS'`) |
| `quantity_delta` | INTEGER | NOT NULL, CHECK `<> 0` (positive = credit, negative = debit) |
| `reason` | VARCHAR(30) | NOT NULL, CHECK in (`'PURCHASE'`, `'SUBSCRIPTION_ALLOWANCE'`, `'CONSUMPTION'`, `'REFUND'`, `'EXPIRY'`, `'ADMIN_GRANT'`, `'ADJUSTMENT'`, `'REVERSAL'`) |
| `transaction_id` | UUID | FK → `transactions(id)` ON DELETE SET NULL |
| `subscription_id` | UUID | FK → `user_subscriptions(id)` ON DELETE SET NULL (added in V20) |
| `related_discovery_action_id` | UUID | FK → `user_discovery_actions(id)` ON DELETE SET NULL |
| `idempotency_key` | VARCHAR(255) | nullable (changed from UUID in V20) |
| `expires_at` | TIMESTAMPTZ | nullable |
| `metadata` | JSONB | NOT NULL, default `'{}'`, must be object |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |

**Indexes:** `idx_entitlement_ledger_user_type_created (user_id, entitlement_type, created_at DESC)`, `unique_entitlement_idempotency_key_per_user (user_id, idempotency_key)` (partial, unique).

**Why needed:** Provides an auditable, immutable record of all credit movements. Avoids the complexity of deriving balances from payment rows alone (which may have refunds, partial refunds, and expiry). The idempotency key prevents double-crediting when webhooks are retried.

---

### `user_entitlement_credit_lots`

Tracks individual batches (lots) of credits granted to a user. Each lot starts with a `quantity_granted` and is decremented as credits are consumed. Lots can have expiry dates. This enables FIFO consumption (use oldest credits first) and proper expiry handling.

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `user_id` | UUID | NOT NULL, FK → `app_users(id)` ON DELETE RESTRICT |
| `entitlement_type` | VARCHAR(30) | NOT NULL, CHECK in (`'BOOST_CREDIT'`, `'SUPERLIKE_CREDIT'`, `'REWIND_CREDIT'`) |
| `source_ledger_entry_id` | UUID | NOT NULL, FK → `user_entitlement_ledger(id)` ON DELETE RESTRICT |
| `quantity_granted` | INTEGER | NOT NULL, CHECK `> 0` |
| `quantity_remaining` | INTEGER | NOT NULL, CHECK `>= 0` |
| `expires_at` | TIMESTAMPTZ | nullable |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |

**Check constraint:** `check_remaining_not_exceed_granted` — `quantity_remaining <= quantity_granted`.

**Unique constraint:** `unique_credit_lot_source (source_ledger_entry_id)` — one lot per ledger credit entry.

**Index:** `idx_credit_lots_user_type_remaining (user_id, entitlement_type, expires_at)` (partial — only where `quantity_remaining > 0`).

**Why needed:** Enables precise credit tracking with expiry. When a user consumes a credit, the system finds the oldest non-expired lot with remaining quantity and decrements it. This prevents the "derive balance from ledger" problem for consumable credits that may expire.

---

### `user_entitlement_credit_consumptions`

Records which specific credit lot was consumed by a given consumption ledger entry. This provides a many-to-many link between consumption actions and the credit lots they drew from.

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `consumption_ledger_entry_id` | UUID | NOT NULL, FK → `user_entitlement_ledger(id)` ON DELETE RESTRICT |
| `credit_lot_id` | UUID | NOT NULL, FK → `user_entitlement_credit_lots(id)` ON DELETE RESTRICT |
| `quantity_consumed` | INTEGER | NOT NULL, CHECK `> 0` |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |

**Index:** `idx_credit_consumptions_lot (credit_lot_id)`.

**Why needed:** Provides full auditability of credit consumption. For any consumption action (ledger entry), you can trace exactly which credit lots were drawn from and how much. This is essential for refund/reversal scenarios where consumed credits must be restored to their original lots.

---

## 8. Boosts

### `active_boosts`

Records profile boost periods. A boost makes the user's profile more visible in discovery for a limited time (typically 30 minutes). A user cannot have overlapping boost periods — the EXCLUDE constraint enforces this at the database level.

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `user_id` | UUID | NOT NULL, FK → `app_users(id)` ON DELETE RESTRICT |
| `transaction_id` | UUID | FK → `transactions(id)` ON DELETE SET NULL |
| `consumption_ledger_entry_id` | UUID | FK → `user_entitlement_ledger(id)` ON DELETE SET NULL (added in V20) |
| `status` | VARCHAR(20) | NOT NULL, default `'ACTIVE'`, CHECK in (`'ACTIVE'`, `'EXPIRED'`, `'CANCELLED'`, `'REVOKED'`) (added in V20) |
| `started_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |
| `expires_at` | TIMESTAMPTZ | NOT NULL |
| `ended_at` | TIMESTAMPTZ | nullable (added in V20) |
| `end_reason` | VARCHAR(30) | nullable (added in V20) |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |

**Check constraint:** `check_boost_period` — `expires_at > started_at`.

**Exclusion constraint:** `no_overlapping_boosts_per_user` — uses GIST to prevent overlapping `tstzrange` periods for the same `user_id`.

**Trigger:** `validate_boost_transaction_owner` — if `transaction_id` is set, the transaction must belong to the same user, be `COMPLETED`, and have `payment_purpose = 'PROFILE_BOOST'`.

**Indexes:** `idx_active_boosts_user_expiry (user_id, expires_at)`, `idx_active_boosts_expires_at (expires_at)`.

**Why needed:** Controls the boost feature. The discovery query service checks this table to boost a user's profile in the discovery deck during their active boost period. The non-overlapping constraint prevents stacking multiple simultaneous boosts.

---

## 9. Quota & Daily Limits

### `user_daily_limits`

Tracks daily usage of likes, super-likes, and rewinds per user per UTC date. The application locks and updates this row in the same transaction as the discovery action.

| Column | Type | Constraints |
|---|---|---|
| `user_id` | UUID | NOT NULL, FK → `app_users(id)` ON DELETE RESTRICT |
| `limit_date` | DATE | NOT NULL, default `(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::DATE` |
| `likes_used` | INTEGER | NOT NULL, default `0`, CHECK `>= 0` |
| `super_likes_used` | INTEGER | NOT NULL, default `0`, CHECK `>= 0` |
| `rewinds_used` | INTEGER | NOT NULL, default `0`, CHECK `>= 0` |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |

**Primary key:** `(user_id, limit_date)`.

**Index:** `idx_user_daily_limits_date (limit_date)`.

**Why needed:** Enforces daily action quotas. Before performing a like/super-like/rewind, the application checks this table against the user's plan limits (from `subscription_plan_limits`). This is the legacy quota table — `user_quota_usage` provides a more flexible period-based alternative.

---

### `user_quota_usage`

Period-based quota tracking that coexists with `user_daily_limits`. Supports configurable period types (daily, subscription month, billing cycle) and resource types (likes, super-likes, rewinds, boosts). This is the newer, more flexible quota system introduced in V20.

| Column | Type | Constraints |
|---|---|---|
| `user_id` | UUID | NOT NULL, FK → `app_users(id)` ON DELETE RESTRICT |
| `plan_id` | UUID | NOT NULL, FK → `subscription_plans(id)` ON DELETE RESTRICT |
| `resource_type` | VARCHAR(30) | NOT NULL, CHECK in (`'LIKES'`, `'SUPERLIKES'`, `'REWINDS'`, `'BOOSTS'`) |
| `period_start` | TIMESTAMPTZ | NOT NULL |
| `period_end` | TIMESTAMPTZ | NOT NULL |
| `used_count` | INTEGER | NOT NULL, default `0`, CHECK `>= 0` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |

**Primary key:** `(user_id, resource_type, period_start)`.

**Why needed:** Supports quota enforcement beyond daily limits. For example, Premium plans may have a monthly boost allowance (`period_type = 'SUBSCRIPTION_MONTH'`). This table tracks usage within the current billing period, resetting when the period rolls over.

---

## 10. User Billing Country

### `app_users.billing_country_code` (column addition)

Added in V21 to `app_users` to determine which payment market a user belongs to. This drives which `payment_offers` and `payment_methods` are shown to the user.

| Column | Type | Constraints |
|---|---|---|
| `billing_country_code` | VARCHAR(10) | nullable (added in V21) |

**Why needed:** Determines the user's billing market. When a user opens the paywall, the app queries offers and payment methods for `billing_country_code` + platform. If NULL, the app may infer the country from the user's address or IP.

---

## 11. Audit Log (Payment-Related Usage)

### `audit_log` (payment-related usage)

While not exclusively a payment table, the audit log records payment-related admin actions such as subscription activations, manual transaction approvals, and refunds.

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `actor_user_id` | UUID | FK → `app_users(id)` ON DELETE SET NULL |
| `action` | VARCHAR(100) | NOT NULL (e.g. `'SUBSCRIPTION_ACTIVATED'`) |
| `target_table` | VARCHAR(100) | NOT NULL (e.g. `'user_subscriptions'`, `'transactions'`) |
| `target_id` | UUID | nullable |
| `request_id` | UUID | nullable |
| `details` | JSONB | NOT NULL, default `'{}'`, must be object |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `CURRENT_TIMESTAMP` |

**Trigger:** `prevent_audit_log_mutation` — prevents UPDATE or DELETE on audit log rows (append-only).

**Indexes:** `idx_audit_log_target (target_table, target_id, created_at DESC)`, `idx_audit_log_actor (actor_user_id, created_at DESC)`.

**Why needed:** Compliance and debugging. Every admin action on a payment (approving a manual transfer, refunding, granting credits) is logged here with the actor, action, and target. The append-only trigger ensures the audit trail cannot be tampered with.

---

## Table Relationships Summary

```
subscription_plans
├── subscription_plan_limits
├── subscription_products
│   └── payment_offers
│       └── payment_orders
│           ├── payment_proofs
│           │   └── payment_verification_attempts
│           ├── transactions
│           │   ├── payment_events
│           │   ├── user_entitlement_ledger
│           │   │   ├── user_entitlement_credit_lots
│           │   │   │   └── user_entitlement_credit_consumptions
│           │   │   └── active_boosts
│           │   └── active_boosts
│           └── user_subscriptions
│               ├── payment_events
│               └── user_entitlement_ledger
└── user_quota_usage

payment_methods
└── payment_orders

billing_customers
└── (links app_users to external provider customer IDs)

app_users
├── billing_country_code (column)
├── user_daily_limits
└── user_quota_usage
```

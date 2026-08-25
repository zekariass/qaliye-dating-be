# Tables Requiring Initial Data for Production

## Billing & Payments

* `subscription_plans` — FREE + PREMIUM plans
* `subscription_products` — billing periods (monthly, 3-month, 6-month)
* `consumable_products` — credit packages (1000/5000/10000/25000)
* `payment_offers` — pricing per country/platform/product (incl. `apple_product_id`/`google_product_id` for IAP)
* `payment_methods` — available payment channels per market/platform
* `country_settings` — per-country toggles (`subscription_enabled`, `credits_enabled`, `identity_verification_required`)

## Plan Limits & Actions

* `feature_actions` — all action/feature codes (LIKE, SUPER_LIKE, BOOST, etc.)
* `subscription_plan_limit_and_cost` — limits + credit costs per plan × action

## Catalogs

* `languages` — supported languages per country
* `ethnicities` — supported ethnicities per country

## Promotions

* `promotion_campaigns` — active campaigns (trials, discounts, signup bonuses)

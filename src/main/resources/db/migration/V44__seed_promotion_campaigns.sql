-- =============================================================================
-- V44: Seed default promotion campaigns
-- =============================================================================

-- 1. Free 7-day premium trial — USER_CLAIM, any eligible user, global
INSERT INTO public.promotion_campaigns (
    campaign_key, name, description, trigger_type, eligibility_type, benefit_type,
    discount_type, discount_value, discount_currency,
    subscription_product_id, country_code,
    duration_days, new_user_window_days,
    max_redemptions, max_redemptions_per_user, priority,
    starts_at, ends_at, status
) VALUES (
    'free_7day_trial',
    '7-Day Free Premium',
    'Try Qaliye Premium free for 7 days. No payment required.',
    'USER_CLAIM', 'ANY_ELIGIBLE_USER', 'FREE_PREMIUM',
    NULL, NULL, NULL,
    'b0000000-0000-0000-0000-000000000001', 'GLOBAL',
    7, NULL,
    10000, 1, 50,
    NOW(), NULL, 'ACTIVE'
) ON CONFLICT (campaign_key) DO NOTHING;

-- 2. Free 14-day premium trial — USER_CLAIM, never subscribed only, Ethiopia
INSERT INTO public.promotion_campaigns (
    campaign_key, name, description, trigger_type, eligibility_type, benefit_type,
    discount_type, discount_value, discount_currency,
    subscription_product_id, country_code,
    duration_days, new_user_window_days,
    max_redemptions, max_redemptions_per_user, priority,
    starts_at, ends_at, status
) VALUES (
    'free_14day_trial_et',
    '14-Day Free Premium',
    'New to Qaliye? Enjoy 14 days of Premium features on us.',
    'USER_CLAIM', 'NEVER_SUBSCRIBED', 'FREE_PREMIUM',
    NULL, NULL, NULL,
    'b0000000-0000-0000-0000-000000000001', 'ET',
    14, NULL,
    5000, 1, 60,
    NOW(), NULL, 'ACTIVE'
) ON CONFLICT (campaign_key) DO NOTHING;

-- 3. 20% off monthly — PURCHASE, any eligible user, global
INSERT INTO public.promotion_campaigns (
    campaign_key, name, description, trigger_type, eligibility_type, benefit_type,
    discount_type, discount_value, discount_currency,
    subscription_product_id, country_code,
    duration_days, new_user_window_days,
    max_redemptions, max_redemptions_per_user, priority,
    starts_at, ends_at, status
) VALUES (
    'summer_20pct_monthly',
    'Summer 20% Off Monthly',
    'Get 20% off your first month of Premium.',
    'PURCHASE', 'ANY_ELIGIBLE_USER', 'DISCOUNT',
    'PERCENTAGE', 2000, NULL,
    'b0000000-0000-0000-0000-000000000001', 'GLOBAL',
    NULL, NULL,
    50000, 1, 40,
    NOW(), '2026-12-31T23:59:59Z', 'ACTIVE'
) ON CONFLICT (campaign_key) DO NOTHING;

-- 4. 15% off 3-month plan — PURCHASE, no active subscription, Ethiopia
INSERT INTO public.promotion_campaigns (
    campaign_key, name, description, trigger_type, eligibility_type, benefit_type,
    discount_type, discount_value, discount_currency,
    subscription_product_id, country_code,
    duration_days, new_user_window_days,
    max_redemptions, max_redemptions_per_user, priority,
    starts_at, ends_at, status
) VALUES (
    'lucky_15pct_3month_et',
    '15% Off 3-Month Plan',
    'Save 15% on our 3-month Premium plan. Limited time offer.',
    'PURCHASE', 'NO_ACTIVE_SUBSCRIPTION', 'DISCOUNT',
    'PERCENTAGE', 1500, NULL,
    'b0000000-0000-0000-0000-000000000002', 'ET',
    NULL, NULL,
    20000, 1, 30,
    NOW(), '2026-12-31T23:59:59Z', 'ACTIVE'
) ON CONFLICT (campaign_key) DO NOTHING;

-- 5. Free 3-day premium on signup — AUTO_ON_SIGNUP, new users within 7 days, global
INSERT INTO public.promotion_campaigns (
    campaign_key, name, description, trigger_type, eligibility_type, benefit_type,
    discount_type, discount_value, discount_currency,
    subscription_product_id, country_code,
    duration_days, new_user_window_days,
    max_redemptions, max_redemptions_per_user, priority,
    starts_at, ends_at, status
) VALUES (
    'signup_3day_free',
    '3-Day Premium on Sign Up',
    'New users get 3 days of Premium for free automatically.',
    'AUTO_ON_SIGNUP', 'NEW_USER', 'FREE_PREMIUM',
    NULL, NULL, NULL,
    'b0000000-0000-0000-0000-000000000001', 'GLOBAL',
    3, 7,
    100000, 1, 70,
    NOW(), NULL, 'ACTIVE'
) ON CONFLICT (campaign_key) DO NOTHING;

-- 6. 25% off 6-month plan — PURCHASE, any eligible user, Ethiopia
INSERT INTO public.promotion_campaigns (
    campaign_key, name, description, trigger_type, eligibility_type, benefit_type,
    discount_type, discount_value, discount_currency,
    subscription_product_id, country_code,
    duration_days, new_user_window_days,
    max_redemptions, max_redemptions_per_user, priority,
    starts_at, ends_at, status
) VALUES (
    'holiday_25pct_6month_et',
    'Holiday 25% Off 6-Month Plan',
    'Celebrate with 25% off our best value 6-month Premium plan.',
    'PURCHASE', 'ANY_ELIGIBLE_USER', 'DISCOUNT',
    'PERCENTAGE', 2500, NULL,
    'b0000000-0000-0000-0000-000000000003', 'ET',
    NULL, NULL,
    10000, 1, 45,
    NOW(), '2026-12-31T23:59:59Z', 'ACTIVE'
) ON CONFLICT (campaign_key) DO NOTHING;

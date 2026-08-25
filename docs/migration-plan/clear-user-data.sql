-- =============================================================================
-- Clear User Data Script (Production Reset)
-- =============================================================================
-- Removes ALL user-generated data while preserving seed/reference data:
--   ✓ subscription_plans, subscription_products, consumable_products
--   ✓ payment_offers, payment_methods
--   ✓ subscription_plan_limits, subscription_plan_limit_and_cost, feature_actions
--   ✓ country_settings, languages, ethnicities
--   ✓ profile_prompts, profile_prompt_translations
--   ✓ promotion_campaigns
--
-- Run in Supabase Dashboard → SQL Editor.
--
-- IMPORTANT: Also delete Auth users manually afterwards:
--   Dashboard → Authentication → Users → select all → Delete
-- =============================================================================

-- promotion_campaigns has a nullable FK to app_users (created_by_user_id ON DELETE SET NULL).
-- NULL it out so we can truncate app_users without CASCADE-wiping the seed campaigns.
UPDATE public.promotion_campaigns SET created_by_user_id = NULL WHERE created_by_user_id IS NOT NULL;

TRUNCATE TABLE
    -- Credit & billing (truncate before payment_orders due to cross-refs)
    public.user_credit_lot_consumptions,
    public.user_credit_ledger,
    public.user_credit_balances,
    public.user_action_limits_tracker,
    public.user_entitlement_credit_lots,
    public.user_entitlement_ledger,
    public.promotion_redemptions,
    public.payment_verification_attempts,
    public.payment_proofs,
    public.payment_orders,
    public.billing_customers,
    public.transactions,
    public.payment_events,
    public.active_boosts,
    public.user_subscriptions,

    -- Support chat
    public.support_conversation_staff_reads,
    public.support_internal_notes,
    public.support_attachments,
    public.support_messages,
    public.support_conversations,

    -- Chat
    public.chat_attachments,
    public.chat_outbox_events,
    public.match_notification_settings,
    public.messages,
    public.pre_match_messages,
    public.matches,

    -- Notifications
    public.notification_deliveries,
    public.notification_outbox_events,
    public.notification_campaigns,
    public.user_notification_preferences,
    public.notification_devices,

    -- Discovery & interactions
    public.user_discovery_actions,
    public.user_blocks,
    public.user_reports,

    -- Profile & identity
    public.identity_verification_reviews,
    public.user_verifications,
    public.image_moderation_results,
    public.profile_prompt_answers,
    public.profile_photos,
    public.discovery_preferences,
    public.profiles,
    public.auth_anonymization_tasks,
    public.audit_log,
    public.addresses,

    -- Location cache (optional — remove this line to keep cached places)
    public.location_places,

    -- Root user table
    public.app_users
CASCADE;


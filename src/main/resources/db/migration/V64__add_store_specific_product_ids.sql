-- =============================================================================
-- V64: Add store-specific product ID columns to payment_offers
--
-- Google Play and Apple App Store use different product IDs for the same
-- in-app purchase. The existing external_product_id column is kept for
-- non-IAP channels (Chapa, manual transfer, etc.). Two new columns allow
-- a single offer row to carry both store-specific IDs.
-- =============================================================================

ALTER TABLE public.payment_offers
    ADD COLUMN IF NOT EXISTS apple_product_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS google_product_id VARCHAR(255);

-- =============================================================================
-- V29: Add verification_count to payment_orders
--
-- Tracks how many verify requests have been submitted from the frontend
-- for a given order. Incremented only by the manual-transfer verify endpoint,
-- not by status checks or order listing.
-- =============================================================================

ALTER TABLE public.payment_orders
    ADD COLUMN IF NOT EXISTS verification_count INTEGER NOT NULL DEFAULT 0;

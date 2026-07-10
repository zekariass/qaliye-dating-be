-- =============================================================================
-- V24: Add verification_params JSONB column to payment_methods
--
-- Stores payment verification parameters (e.g. account numbers, instructions
-- specific to verification) to be rendered in the frontend for the user.
-- =============================================================================

ALTER TABLE public.payment_methods
    ADD COLUMN IF NOT EXISTS verification_params JSONB NULL;

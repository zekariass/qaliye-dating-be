-- Add logo_url column to payment_methods for displaying provider logos
ALTER TABLE public.payment_methods
    ADD COLUMN IF NOT EXISTS logo_url TEXT;

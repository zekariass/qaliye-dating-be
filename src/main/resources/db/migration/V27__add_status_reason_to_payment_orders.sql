ALTER TABLE public.payment_orders
    ADD COLUMN IF NOT EXISTS status_reason TEXT;

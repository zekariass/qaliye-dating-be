ALTER TABLE public.payment_orders
    ADD COLUMN IF NOT EXISTS manual_payment_reference          VARCHAR(255),
    ADD COLUMN IF NOT EXISTS manual_payment_reference_normalized VARCHAR(255),
    ADD COLUMN IF NOT EXISTS provider_verification_request_id  VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS unique_manual_transfer_reference_per_method
    ON public.payment_orders (payment_method_id, manual_payment_reference_normalized)
    WHERE manual_payment_reference_normalized IS NOT NULL
      AND status NOT IN ('CANCELLED', 'EXPIRED');

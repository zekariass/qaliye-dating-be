-- Add PROMOTION_GRANT to the allowed reason values on user_entitlement_ledger
ALTER TABLE public.user_entitlement_ledger
    DROP CONSTRAINT IF EXISTS user_entitlement_ledger_reason_check;

ALTER TABLE public.user_entitlement_ledger
    ADD CONSTRAINT user_entitlement_ledger_reason_check CHECK (
        reason IN (
            'PURCHASE', 'SUBSCRIPTION_ALLOWANCE', 'CONSUMPTION',
            'REFUND', 'EXPIRY', 'ADMIN_GRANT', 'ADJUSTMENT', 'REVERSAL',
            'PROMOTION_GRANT'
        )
    );

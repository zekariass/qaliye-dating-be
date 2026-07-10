-- =============================================================================
-- V26: Add RECEIPT_SUBMITTED to payment_orders.status
--
-- Status lifecycle by channel:
--
--   Online payment (Chapa/ArifPay):
--     CREATED → AWAITING_PAYMENT → VERIFIED | REJECTED | EXPIRED | CANCELLED
--
--   Manual transfer – verify.et path:
--     CREATED → VERIFICATION_PENDING
--       → VERIFIED          (verify.et confirmed, bank+amount match)
--       → MANUAL_REVIEW     (verify.et confirmed but bank or amount mismatch,
--                             or settlement not matched, or failed / not_found /
--                             confirmedBefore, or duplicate providerRef)
--       → REJECTED | EXPIRED
--
--   Manual transfer – receipt upload path:
--     CREATED → RECEIPT_SUBMITTED → VERIFIED | REJECTED  (admin decision)
--
-- ADMIN_REVIEW has been removed – it was never set as an order status.
-- REVIEW_REQUIRED has been merged into MANUAL_REVIEW to simplify the lifecycle.
-- =============================================================================

ALTER TABLE public.payment_orders
    DROP CONSTRAINT IF EXISTS payment_orders_status_check;

ALTER TABLE public.payment_orders
    ADD CONSTRAINT payment_orders_status_check CHECK (
        status IN (
            'CREATED',
            'AWAITING_PAYMENT',
            'RECEIPT_SUBMITTED',
            'VERIFICATION_PENDING',
            'MANUAL_REVIEW',
            'VERIFIED',
            'REJECTED',
            'EXPIRED',
            'CANCELLED'
        )
    );

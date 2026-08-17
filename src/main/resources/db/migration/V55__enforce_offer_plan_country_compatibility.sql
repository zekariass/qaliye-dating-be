-- =============================================================================
-- V55: Enforce payment_offers country_code compatibility with subscription_plans
--
-- Rule: A non-GLOBAL plan can only be used in offers with the same country_code.
--       A GLOBAL plan can be used in offers with any country_code.
--
-- In other words:
--   plan.country_code = 'GLOBAL'  →  offer.country_code can be anything
--   plan.country_code = 'ET'      →  offer.country_code must be 'ET'
--   plan.country_code = 'GB'      →  offer.country_code must be 'GB'
--   plan.country_code = 'X'       →  offer.country_code must be 'X'
--
-- This trigger checks the constraint on:
--   1. INSERT/UPDATE of payment_offers
--   2. UPDATE of subscription_products.plan_id
--   3. UPDATE of subscription_plans.country_code
-- =============================================================================

-- 1. Trigger function for payment_offers INSERT/UPDATE
--    Checks that if the offer references a subscription_product, the linked
--    plan's country_code is either GLOBAL or matches the offer's country_code.

CREATE OR REPLACE FUNCTION public.check_offer_plan_country_compatibility()
RETURNS TRIGGER AS $$
DECLARE
    v_plan_country_code VARCHAR(10);
BEGIN
    -- Only check when subscription_product_id is set (consumable offers are unaffected)
    IF NEW.subscription_product_id IS NOT NULL THEN
        SELECT sp.country_code
          INTO v_plan_country_code
          FROM subscription_products sprod
          JOIN subscription_plans sp ON sp.id = sprod.plan_id
         WHERE sprod.id = NEW.subscription_product_id;

        IF v_plan_country_code IS NOT NULL
           AND v_plan_country_code <> 'GLOBAL'
           AND v_plan_country_code <> NEW.country_code
        THEN
            RAISE EXCEPTION
                'Offer country_code "%" does not match plan country_code "%". '
                'Non-GLOBAL plans can only be used in offers with the same country_code.',
                NEW.country_code, v_plan_country_code
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 2. Trigger on payment_offers
DROP TRIGGER IF EXISTS trg_payment_offers_plan_country ON public.payment_offers;

CREATE TRIGGER trg_payment_offers_plan_country
    BEFORE INSERT OR UPDATE OF subscription_product_id, country_code
    ON public.payment_offers
    FOR EACH ROW
    EXECUTE FUNCTION public.check_offer_plan_country_compatibility();

-- 3. Trigger function for subscription_products UPDATE (when plan_id changes)
--    Re-checks all linked payment_offers against the new plan's country_code.

CREATE OR REPLACE FUNCTION public.check_product_plan_country_compatibility()
RETURNS TRIGGER AS $$
DECLARE
    v_plan_country_code VARCHAR(10);
    v_offer_country_code VARCHAR(10);
BEGIN
    IF NEW.plan_id IS DISTINCT FROM OLD.plan_id THEN
        SELECT sp.country_code
          INTO v_plan_country_code
          FROM subscription_plans sp
         WHERE sp.id = NEW.plan_id;

        IF v_plan_country_code IS NOT NULL AND v_plan_country_code <> 'GLOBAL' THEN
            -- Check if any linked offer has a mismatched country_code
            SELECT po.country_code
              INTO v_offer_country_code
              FROM payment_offers po
             WHERE po.subscription_product_id = NEW.id
               AND po.country_code <> v_plan_country_code
               AND po.is_active = TRUE
             LIMIT 1;

            IF v_offer_country_code IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot change plan_id: linked payment_offer with country_code "%" '
                    'is incompatible with new plan country_code "%".',
                    v_offer_country_code, v_plan_country_code
                    USING ERRCODE = 'check_violation';
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 4. Trigger on subscription_products
DROP TRIGGER IF EXISTS trg_subscription_products_plan_country ON public.subscription_products;

CREATE TRIGGER trg_subscription_products_plan_country
    BEFORE UPDATE OF plan_id
    ON public.subscription_products
    FOR EACH ROW
    EXECUTE FUNCTION public.check_product_plan_country_compatibility();

-- 5. Trigger function for subscription_plans UPDATE (when country_code changes)
--    Re-checks all linked payment_offers (through subscription_products) against
--    the new plan country_code. This catches the case where a plan's country_code
--    is changed directly (e.g. GLOBAL → ET) while linked offers still reference
--    a different country_code.

CREATE OR REPLACE FUNCTION public.check_plan_country_compatibility()
RETURNS TRIGGER AS $$
DECLARE
    v_offer_country_code VARCHAR(10);
BEGIN
    IF NEW.country_code IS DISTINCT FROM OLD.country_code THEN
        IF NEW.country_code IS NOT NULL AND NEW.country_code <> 'GLOBAL' THEN
            -- Find any linked offer with a mismatched country_code
            SELECT po.country_code
              INTO v_offer_country_code
              FROM payment_offers po
              JOIN subscription_products sprod ON sprod.id = po.subscription_product_id
             WHERE sprod.plan_id = NEW.id
               AND po.country_code <> NEW.country_code
               AND po.is_active = TRUE
             LIMIT 1;

            IF v_offer_country_code IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot change plan country_code to "%": linked payment_offer with '
                    'country_code "%" is incompatible. Non-GLOBAL plans can only be used '
                    'in offers with the same country_code.',
                    NEW.country_code, v_offer_country_code
                    USING ERRCODE = 'check_violation';
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 6. Trigger on subscription_plans
DROP TRIGGER IF EXISTS trg_subscription_plans_country ON public.subscription_plans;

CREATE TRIGGER trg_subscription_plans_country
    BEFORE UPDATE OF country_code
    ON public.subscription_plans
    FOR EACH ROW
    EXECUTE FUNCTION public.check_plan_country_compatibility();

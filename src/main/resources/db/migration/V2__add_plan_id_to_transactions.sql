ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS plan_id UUID REFERENCES subscription_plans(id) ON DELETE RESTRICT;

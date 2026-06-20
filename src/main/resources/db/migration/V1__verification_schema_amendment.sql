ALTER TABLE user_verifications
    ADD COLUMN IF NOT EXISTS storage_path      TEXT,
    ADD COLUMN IF NOT EXISTS reviewed_by       UUID REFERENCES app_users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS rejection_reason  TEXT,
    ADD COLUMN IF NOT EXISTS metadata          JSONB DEFAULT '{}';

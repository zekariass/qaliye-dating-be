-- Tracks Supabase Auth soft-deletion tasks so transient failures can be
-- retried by AuthAnonymizationRetryWorker without losing the obligation.
CREATE TABLE auth_anonymization_tasks (
    user_id         UUID        PRIMARY KEY
                                REFERENCES public.app_users(id) ON DELETE CASCADE,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                                CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED_PERMANENT')),
    attempts        INT         NOT NULL DEFAULT 0,
    last_error      TEXT,
    next_retry_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Partial index so the retry worker can quickly find overdue tasks.
CREATE INDEX idx_auth_anon_tasks_retry
    ON auth_anonymization_tasks (next_retry_at)
    WHERE status = 'PENDING';

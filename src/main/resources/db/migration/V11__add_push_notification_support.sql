-- ============================================================================
-- V11__add_push_notification_support.sql
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Extend notification_devices
-- ---------------------------------------------------------------------------

ALTER TABLE public.notification_devices
    ADD COLUMN installation_id UUID,
    ADD COLUMN app_environment VARCHAR(20) NOT NULL DEFAULT 'PRODUCTION'
        CHECK (app_environment IN ('DEVELOPMENT', 'PREVIEW', 'PRODUCTION')),
    ADD COLUMN disabled_at TIMESTAMPTZ,
    ADD COLUMN last_error_code VARCHAR(100),
    ADD COLUMN last_error_at TIMESTAMPTZ;

CREATE INDEX idx_notification_devices_active_environment
    ON public.notification_devices(user_id, app_environment)
    WHERE is_active = TRUE;

CREATE UNIQUE INDEX unique_active_notification_installation
    ON public.notification_devices(app_environment, installation_id)
    WHERE installation_id IS NOT NULL
      AND is_active = TRUE;


-- ---------------------------------------------------------------------------
-- 2. User notification preferences
-- ---------------------------------------------------------------------------

CREATE TABLE public.user_notification_preferences (
    user_id UUID PRIMARY KEY
        REFERENCES public.app_users(id) ON DELETE RESTRICT,

    push_enabled BOOLEAN NOT NULL DEFAULT TRUE,

    message_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    match_notifications_enabled   BOOLEAN NOT NULL DEFAULT TRUE,
    like_notifications_enabled    BOOLEAN NOT NULL DEFAULT TRUE,

    message_preview_enabled BOOLEAN NOT NULL DEFAULT FALSE,

    marketing_notifications_enabled        BOOLEAN NOT NULL DEFAULT FALSE,
    marketing_notifications_opted_in_at    TIMESTAMPTZ,
    marketing_notifications_consent_version VARCHAR(50),

    last_marketing_sent_at            TIMESTAMPTZ,
    marketing_reservation_event_id    UUID,
    marketing_reservation_expires_at  TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_marketing_opt_in CHECK (
        NOT marketing_notifications_enabled
        OR (
            marketing_notifications_opted_in_at IS NOT NULL
            AND NULLIF(BTRIM(marketing_notifications_consent_version), '') IS NOT NULL
        )
    )
);

CREATE TRIGGER set_timestamp_user_notification_preferences
BEFORE UPDATE ON public.user_notification_preferences
FOR EACH ROW
EXECUTE FUNCTION public.update_updated_at_column();

ALTER TABLE public.user_notification_preferences ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.user_notification_preferences
FROM anon, authenticated;

-- Backfill preference rows for existing users
INSERT INTO public.user_notification_preferences (user_id)
SELECT id
FROM public.app_users
ON CONFLICT (user_id) DO NOTHING;

-- Trigger: create default preference row for every future app_users row
CREATE OR REPLACE FUNCTION public.create_default_notification_preferences()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO public.user_notification_preferences (user_id)
    VALUES (NEW.id)
    ON CONFLICT (user_id) DO NOTHING;

    RETURN NEW;
END;
$$;

CREATE TRIGGER create_default_notification_preferences_after_user_insert
AFTER INSERT ON public.app_users
FOR EACH ROW
EXECUTE FUNCTION public.create_default_notification_preferences();


-- ---------------------------------------------------------------------------
-- 3. Notification campaigns
-- ---------------------------------------------------------------------------

CREATE TABLE public.notification_campaigns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    campaign_key VARCHAR(100) NOT NULL UNIQUE,

    title VARCHAR(120) NOT NULL,
    body  VARCHAR(300) NOT NULL,

    navigation_payload JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (jsonb_typeof(navigation_payload) = 'object'),

    audience_definition JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (jsonb_typeof(audience_definition) = 'object'),

    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN (
            'DRAFT',
            'SCHEDULED',
            'SENDING',
            'COMPLETED',
            'CANCELLED'
        )),

    scheduled_at  TIMESTAMPTZ,
    started_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    cancelled_at  TIMESTAMPTZ,

    created_by_user_id UUID
        REFERENCES public.app_users(id) ON DELETE SET NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER set_timestamp_notification_campaigns
BEFORE UPDATE ON public.notification_campaigns
FOR EACH ROW
EXECUTE FUNCTION public.update_updated_at_column();

-- Enforce campaign lifecycle rules:
--   * Content is immutable once SENDING/COMPLETED/CANCELLED.
--   * Invalid status transitions are rejected.
CREATE OR REPLACE FUNCTION public.enforce_notification_campaign_lifecycle()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- Reject invalid status transitions
    IF OLD.status = 'COMPLETED' OR OLD.status = 'CANCELLED' THEN
        IF NEW.status IS DISTINCT FROM OLD.status THEN
            RAISE EXCEPTION
                'Campaign status cannot change from % once finalised.', OLD.status;
        END IF;
    END IF;

    IF OLD.status = 'SENDING' AND NEW.status NOT IN ('COMPLETED', 'CANCELLED') THEN
        RAISE EXCEPTION
            'A SENDING campaign can only transition to COMPLETED or CANCELLED.';
    END IF;

    IF OLD.status = 'SCHEDULED' AND NEW.status NOT IN ('SENDING', 'CANCELLED', 'DRAFT') THEN
        RAISE EXCEPTION
            'A SCHEDULED campaign can only transition to SENDING, CANCELLED, or DRAFT.';
    END IF;

    -- SCHEDULED requires scheduled_at
    IF NEW.status = 'SCHEDULED' AND NEW.scheduled_at IS NULL THEN
        RAISE EXCEPTION 'scheduled_at is required when status is SCHEDULED.';
    END IF;

    -- SENDING requires started_at
    IF NEW.status = 'SENDING' AND NEW.started_at IS NULL THEN
        RAISE EXCEPTION 'started_at is required when status is SENDING.';
    END IF;

    -- COMPLETED requires started_at and completed_at
    IF NEW.status = 'COMPLETED'
       AND (NEW.started_at IS NULL OR NEW.completed_at IS NULL) THEN
        RAISE EXCEPTION
            'started_at and completed_at are required when status is COMPLETED.';
    END IF;

    -- CANCELLED requires cancelled_at
    IF NEW.status = 'CANCELLED' AND NEW.cancelled_at IS NULL THEN
        RAISE EXCEPTION 'cancelled_at is required when status is CANCELLED.';
    END IF;

    -- Content is immutable after SENDING / COMPLETED / CANCELLED
    IF OLD.status IN ('SENDING', 'COMPLETED', 'CANCELLED') THEN
        IF NEW.title IS DISTINCT FROM OLD.title THEN
            RAISE EXCEPTION 'Campaign title is immutable after %s.', OLD.status;
        END IF;
        IF NEW.body IS DISTINCT FROM OLD.body THEN
            RAISE EXCEPTION 'Campaign body is immutable after %s.', OLD.status;
        END IF;
        IF NEW.navigation_payload IS DISTINCT FROM OLD.navigation_payload THEN
            RAISE EXCEPTION
                'Campaign navigation_payload is immutable after %s.', OLD.status;
        END IF;
        IF NEW.audience_definition IS DISTINCT FROM OLD.audience_definition THEN
            RAISE EXCEPTION
                'Campaign audience_definition is immutable after %s.', OLD.status;
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER enforce_notification_campaign_lifecycle
BEFORE UPDATE ON public.notification_campaigns
FOR EACH ROW
EXECUTE FUNCTION public.enforce_notification_campaign_lifecycle();

ALTER TABLE public.notification_campaigns ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.notification_campaigns
FROM anon, authenticated;


-- ---------------------------------------------------------------------------
-- 4. Notification outbox events
-- ---------------------------------------------------------------------------

CREATE TABLE public.notification_outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    notification_type VARCHAR(30) NOT NULL CHECK (
        notification_type IN (
            'CHAT_MESSAGE',
            'MATCH_CREATED',
            'LIKE_RECEIVED',
            'ACCOUNT_ALERT',
            'MARKETING'
        )
    ),

    recipient_user_id UUID NOT NULL
        REFERENCES public.app_users(id) ON DELETE RESTRICT,

    actor_user_id UUID
        REFERENCES public.app_users(id) ON DELETE SET NULL,

    match_id UUID
        REFERENCES public.matches(id) ON DELETE SET NULL,

    message_id UUID
        REFERENCES public.messages(id) ON DELETE SET NULL,

    discovery_action_id UUID
        REFERENCES public.user_discovery_actions(id) ON DELETE SET NULL,

    campaign_id UUID
        REFERENCES public.notification_campaigns(id) ON DELETE SET NULL,

    dedupe_key VARCHAR(255) NOT NULL UNIQUE,
    collapse_key VARCHAR(255),

    payload JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (jsonb_typeof(payload) = 'object'),

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN (
            'PENDING',
            'PROCESSING',
            'FANOUT_COMPLETE',
            'SKIPPED',
            'FAILED'
        )
    ),

    attempt_count INTEGER NOT NULL DEFAULT 0
        CHECK (attempt_count >= 0),

    available_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at        TIMESTAMPTZ,

    locked_at         TIMESTAMPTZ,
    locked_by         VARCHAR(100),
    lease_expires_at  TIMESTAMPTZ,

    fanout_completed_at TIMESTAMPTZ,
    last_error        TEXT,

    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT check_notification_outbox_processing_lease CHECK (
        status <> 'PROCESSING'
        OR (
            locked_at IS NOT NULL
            AND locked_by IS NOT NULL
            AND lease_expires_at IS NOT NULL
        )
    )
);

CREATE INDEX idx_notification_outbox_claim_pending
    ON public.notification_outbox_events(available_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_notification_outbox_processing_lease
    ON public.notification_outbox_events(lease_expires_at)
    WHERE status = 'PROCESSING';

CREATE INDEX idx_notification_outbox_recipient_created
    ON public.notification_outbox_events(recipient_user_id, created_at DESC);

CREATE INDEX idx_notification_outbox_campaign
    ON public.notification_outbox_events(campaign_id, created_at)
    WHERE campaign_id IS NOT NULL;

ALTER TABLE public.notification_outbox_events ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.notification_outbox_events
FROM anon, authenticated;


-- ---------------------------------------------------------------------------
-- 5. Per-device notification deliveries
-- ---------------------------------------------------------------------------

CREATE TABLE public.notification_deliveries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    notification_outbox_event_id UUID NOT NULL
        REFERENCES public.notification_outbox_events(id) ON DELETE RESTRICT,

    notification_device_id UUID NOT NULL
        REFERENCES public.notification_devices(id) ON DELETE RESTRICT,

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN (
            'PENDING',
            'PROCESSING',
            'SUBMITTED',
            'CONFIRMED',
            'UNKNOWN',
            'FAILED',
            'SKIPPED'
        )
    ),

    resolution_code VARCHAR(100),

    attempt_count INTEGER NOT NULL DEFAULT 0
        CHECK (attempt_count >= 0),

    available_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    locked_at        TIMESTAMPTZ,
    locked_by        VARCHAR(100),
    lease_expires_at TIMESTAMPTZ,

    provider_ticket_id  TEXT,
    submitted_at        TIMESTAMPTZ,

    next_receipt_check_at TIMESTAMPTZ,
    receipt_deadline_at   TIMESTAMPTZ,
    receipt_checked_at    TIMESTAMPTZ,
    confirmed_at          TIMESTAMPTZ,

    last_error_code VARCHAR(100),
    last_error      TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_notification_delivery_per_device
        UNIQUE (notification_outbox_event_id, notification_device_id),

    CONSTRAINT check_notification_delivery_processing_lease CHECK (
        status <> 'PROCESSING'
        OR (
            locked_at IS NOT NULL
            AND locked_by IS NOT NULL
            AND lease_expires_at IS NOT NULL
        )
    )
);

CREATE INDEX idx_notification_deliveries_claim_pending
    ON public.notification_deliveries(available_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_notification_deliveries_processing_lease
    ON public.notification_deliveries(lease_expires_at)
    WHERE status = 'PROCESSING';

CREATE INDEX idx_notification_deliveries_receipt_check
    ON public.notification_deliveries(next_receipt_check_at)
    WHERE status = 'SUBMITTED';

CREATE UNIQUE INDEX unique_notification_delivery_provider_ticket
    ON public.notification_deliveries(provider_ticket_id)
    WHERE provider_ticket_id IS NOT NULL;

CREATE TRIGGER set_timestamp_notification_deliveries
BEFORE UPDATE ON public.notification_deliveries
FOR EACH ROW
EXECUTE FUNCTION public.update_updated_at_column();

ALTER TABLE public.notification_deliveries ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.notification_deliveries
FROM anon, authenticated;

-- Also apply RLS to notification_devices (already has rows; add without dropping)
ALTER TABLE public.notification_devices ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.notification_devices
FROM anon, authenticated;

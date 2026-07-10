-- =============================================================================
-- V17: Update allowed report_type values on user_reports
-- =============================================================================

ALTER TABLE public.user_reports
    DROP CONSTRAINT IF EXISTS user_reports_report_type_check;

ALTER TABLE public.user_reports
    ADD CONSTRAINT user_reports_report_type_check CHECK (
        report_type IN (
            'FAKE_PROFILE',
            'HARASSMENT',
            'HATE_SPEECH',
            'INAPPROPRIATE_CONTENT',
            'SCAM',
            'UNDERAGE',
            'VIOLENCE_OR_THREATS',
            'PRIVACY_VIOLATION',
            'OFF_PLATFORM_SOLICITATION',
            'SPAM',
            'AUTO_FLAGGED',
            'OTHER'
        )
    );

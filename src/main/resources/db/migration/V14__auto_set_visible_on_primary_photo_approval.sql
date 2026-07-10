-- When a primary photo is approved for an onboarded user whose profile is not
-- yet visible, automatically make their profile visible so they can enter
-- discovery without any extra API call.
--
-- Gap this closes:
--   1. User completes onboarding with a PENDING primary photo.
--      OnboardingService.complete() correctly sets is_onboarded=TRUE but
--      keeps is_visible=FALSE (photo not yet approved).
--   2. Admin approves the photo later via the moderation endpoint.
--   3. Without this trigger is_visible stays FALSE permanently, causing the
--      discovery service to return ACCOUNT_INELIGIBLE even though the user
--      has fully satisfied all requirements.

CREATE OR REPLACE FUNCTION public.auto_set_visible_on_primary_photo_approval()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF NEW.is_primary       = TRUE
       AND NEW.moderation_status = 'APPROVED'
       AND NEW.deleted_at        IS NULL
       AND (
           TG_OP = 'INSERT'
           OR OLD.moderation_status IS DISTINCT FROM 'APPROVED'
           OR OLD.is_primary        IS DISTINCT FROM TRUE
       )
    THEN
        UPDATE public.profiles
        SET is_visible  = TRUE,
            updated_at  = CURRENT_TIMESTAMP
        WHERE user_id    = NEW.user_id
          AND is_onboarded = TRUE
          AND is_visible   = FALSE;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER auto_set_visible_on_primary_photo_approval
AFTER INSERT OR UPDATE OF moderation_status, is_primary, deleted_at
ON public.profile_photos
FOR EACH ROW
EXECUTE FUNCTION public.auto_set_visible_on_primary_photo_approval();

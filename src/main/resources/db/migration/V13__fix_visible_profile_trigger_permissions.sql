-- Fix: the deferred constraint trigger function public.validate_visible_profile_dependencies()
-- runs as the invoker. When Supabase Auth modifies auth.users (or cascading app_users
-- changes) the invoker is supabase_auth_admin, which lacks SELECT on public.profiles and
-- the other related tables. Make the function SECURITY DEFINER so it executes with the
-- privileges of its owner (the role that created the tables), matching the pattern used
-- for public.handle_new_auth_user().

CREATE OR REPLACE FUNCTION public.validate_visible_profile_dependencies()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_user_id UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        CASE TG_TABLE_NAME
            WHEN 'profiles' THEN v_user_id := OLD.user_id;
            WHEN 'profile_photos' THEN v_user_id := OLD.user_id;
            WHEN 'discovery_preferences' THEN v_user_id := OLD.user_id;
            WHEN 'app_users' THEN v_user_id := OLD.id;
            ELSE
                RAISE EXCEPTION 'Unsupported table for visible-profile validation: %', TG_TABLE_NAME;
        END CASE;
    ELSE
        CASE TG_TABLE_NAME
            WHEN 'profiles' THEN v_user_id := NEW.user_id;
            WHEN 'profile_photos' THEN v_user_id := NEW.user_id;
            WHEN 'discovery_preferences' THEN v_user_id := NEW.user_id;
            WHEN 'app_users' THEN v_user_id := NEW.id;
            ELSE
                RAISE EXCEPTION 'Unsupported table for visible-profile validation: %', TG_TABLE_NAME;
        END CASE;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.profiles p
        WHERE p.user_id = v_user_id
          AND p.is_visible = TRUE
          AND p.is_onboarded = TRUE
    ) THEN
        IF NOT EXISTS (
            SELECT 1
            FROM public.app_users au
            WHERE au.id = v_user_id
              AND au.status = 'ACTIVE'
              AND au.address_id IS NOT NULL
        ) THEN
            RAISE EXCEPTION
                'A visible profile requires an active user account with one address.';
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM public.discovery_preferences dp
            WHERE dp.user_id = v_user_id
        ) THEN
            RAISE EXCEPTION
                'A visible profile requires discovery preferences.';
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM public.profile_photos pp
            WHERE pp.user_id = v_user_id
              AND pp.deleted_at IS NULL
              AND pp.is_primary = TRUE
              AND pp.moderation_status = 'APPROVED'
        ) THEN
            RAISE EXCEPTION
                'A visible profile requires an approved primary photo.';
        END IF;
    END IF;

    RETURN NULL;
END;
$$;

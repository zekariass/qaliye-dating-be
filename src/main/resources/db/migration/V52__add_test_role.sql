-- Add TEST role to app_users role constraint (if a CHECK constraint exists)
-- The app_users table is created in Supabase, so the constraint name may vary.
-- We use a DO block to find and replace any CHECK constraint on the role column.

DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    SELECT con.conname
    INTO constraint_name
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    JOIN pg_attribute att ON att.attrelid = rel.oid AND att.attnum = ANY(con.conkey)
    WHERE rel.relname = 'app_users'
      AND con.contype = 'c'
      AND att.attname = 'role';

    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE public.app_users DROP CONSTRAINT %I', constraint_name);
        EXECUTE 'ALTER TABLE public.app_users ADD CONSTRAINT ' || constraint_name ||
                ' CHECK (role IN (''USER'', ''MODERATOR'', ''ADMIN'', ''TEST''))';
        RAISE NOTICE 'Updated role constraint % to include TEST', constraint_name;
    ELSE
        -- No existing constraint found; add one
        ALTER TABLE public.app_users
            ADD CONSTRAINT app_users_role_check CHECK (role IN ('USER', 'MODERATOR', 'ADMIN', 'TEST'));
        RAISE NOTICE 'Created new role constraint app_users_role_check with TEST';
    END IF;
END $$;

--
-- PostgreSQL database dump
--

-- Dumped from database version 17.6
-- Dumped by pg_dump version 17.3

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: auth; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA auth;


--
-- Name: extensions; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA extensions;


--
-- Name: graphql; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA graphql;


--
-- Name: graphql_public; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA graphql_public;


--
-- Name: pgbouncer; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA pgbouncer;


--
-- Name: realtime; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA realtime;


--
-- Name: storage; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA storage;


--
-- Name: vault; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA vault;


--
-- Name: btree_gist; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;


--
-- Name: EXTENSION btree_gist; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION btree_gist IS 'support for indexing common datatypes in GiST';


--
-- Name: pg_stat_statements; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_stat_statements WITH SCHEMA extensions;


--
-- Name: EXTENSION pg_stat_statements; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pg_stat_statements IS 'track planning and execution statistics of all SQL statements executed';


--
-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;


--
-- Name: EXTENSION pg_trgm; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pg_trgm IS 'text similarity measurement and index searching based on trigrams';


--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;


--
-- Name: EXTENSION pgcrypto; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';


--
-- Name: postgis; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;


--
-- Name: EXTENSION postgis; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION postgis IS 'PostGIS geometry and geography spatial types and functions';


--
-- Name: supabase_vault; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS supabase_vault WITH SCHEMA vault;


--
-- Name: EXTENSION supabase_vault; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION supabase_vault IS 'Supabase Vault Extension';


--
-- Name: uuid-ossp; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA extensions;


--
-- Name: EXTENSION "uuid-ossp"; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION "uuid-ossp" IS 'generate universally unique identifiers (UUIDs)';


--
-- Name: aal_level; Type: TYPE; Schema: auth; Owner: -
--

CREATE TYPE auth.aal_level AS ENUM (
    'aal1',
    'aal2',
    'aal3'
);


--
-- Name: code_challenge_method; Type: TYPE; Schema: auth; Owner: -
--

CREATE TYPE auth.code_challenge_method AS ENUM (
    's256',
    'plain'
);


--
-- Name: factor_status; Type: TYPE; Schema: auth; Owner: -
--

CREATE TYPE auth.factor_status AS ENUM (
    'unverified',
    'verified'
);


--
-- Name: factor_type; Type: TYPE; Schema: auth; Owner: -
--

CREATE TYPE auth.factor_type AS ENUM (
    'totp',
    'webauthn',
    'phone'
);


--
-- Name: oauth_authorization_status; Type: TYPE; Schema: auth; Owner: -
--

CREATE TYPE auth.oauth_authorization_status AS ENUM (
    'pending',
    'approved',
    'denied',
    'expired'
);


--
-- Name: oauth_client_type; Type: TYPE; Schema: auth; Owner: -
--

CREATE TYPE auth.oauth_client_type AS ENUM (
    'public',
    'confidential'
);


--
-- Name: oauth_registration_type; Type: TYPE; Schema: auth; Owner: -
--

CREATE TYPE auth.oauth_registration_type AS ENUM (
    'dynamic',
    'manual'
);


--
-- Name: oauth_response_type; Type: TYPE; Schema: auth; Owner: -
--

CREATE TYPE auth.oauth_response_type AS ENUM (
    'code'
);


--
-- Name: one_time_token_type; Type: TYPE; Schema: auth; Owner: -
--

CREATE TYPE auth.one_time_token_type AS ENUM (
    'confirmation_token',
    'reauthentication_token',
    'recovery_token',
    'email_change_token_new',
    'email_change_token_current',
    'phone_change_token'
);


--
-- Name: action; Type: TYPE; Schema: realtime; Owner: -
--

CREATE TYPE realtime.action AS ENUM (
    'INSERT',
    'UPDATE',
    'DELETE',
    'TRUNCATE',
    'ERROR'
);


--
-- Name: equality_op; Type: TYPE; Schema: realtime; Owner: -
--

CREATE TYPE realtime.equality_op AS ENUM (
    'eq',
    'neq',
    'lt',
    'lte',
    'gt',
    'gte',
    'in',
    'like',
    'ilike',
    'is',
    'match',
    'imatch',
    'isdistinct'
);


--
-- Name: user_defined_filter; Type: TYPE; Schema: realtime; Owner: -
--

CREATE TYPE realtime.user_defined_filter AS (
	column_name text,
	op realtime.equality_op,
	value text,
	negate boolean
);


--
-- Name: wal_column; Type: TYPE; Schema: realtime; Owner: -
--

CREATE TYPE realtime.wal_column AS (
	name text,
	type_name text,
	type_oid oid,
	value jsonb,
	is_pkey boolean,
	is_selectable boolean
);


--
-- Name: wal_rls; Type: TYPE; Schema: realtime; Owner: -
--

CREATE TYPE realtime.wal_rls AS (
	wal jsonb,
	is_rls_enabled boolean,
	subscription_ids uuid[],
	errors text[]
);


--
-- Name: buckettype; Type: TYPE; Schema: storage; Owner: -
--

CREATE TYPE storage.buckettype AS ENUM (
    'STANDARD',
    'ANALYTICS',
    'VECTOR'
);


--
-- Name: email(); Type: FUNCTION; Schema: auth; Owner: -
--

CREATE FUNCTION auth.email() RETURNS text
    LANGUAGE sql STABLE
    AS $$
  select 
  coalesce(
    nullif(current_setting('request.jwt.claim.email', true), ''),
    (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'email')
  )::text
$$;


--
-- Name: FUNCTION email(); Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON FUNCTION auth.email() IS 'Deprecated. Use auth.jwt() -> ''email'' instead.';


--
-- Name: jwt(); Type: FUNCTION; Schema: auth; Owner: -
--

CREATE FUNCTION auth.jwt() RETURNS jsonb
    LANGUAGE sql STABLE
    AS $$
  select 
    coalesce(
        nullif(current_setting('request.jwt.claim', true), ''),
        nullif(current_setting('request.jwt.claims', true), '')
    )::jsonb
$$;


--
-- Name: role(); Type: FUNCTION; Schema: auth; Owner: -
--

CREATE FUNCTION auth.role() RETURNS text
    LANGUAGE sql STABLE
    AS $$
  select 
  coalesce(
    nullif(current_setting('request.jwt.claim.role', true), ''),
    (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'role')
  )::text
$$;


--
-- Name: FUNCTION role(); Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON FUNCTION auth.role() IS 'Deprecated. Use auth.jwt() -> ''role'' instead.';


--
-- Name: uid(); Type: FUNCTION; Schema: auth; Owner: -
--

CREATE FUNCTION auth.uid() RETURNS uuid
    LANGUAGE sql STABLE
    AS $$
  select 
  coalesce(
    nullif(current_setting('request.jwt.claim.sub', true), ''),
    (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub')
  )::uuid
$$;


--
-- Name: FUNCTION uid(); Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON FUNCTION auth.uid() IS 'Deprecated. Use auth.jwt() -> ''sub'' instead.';


--
-- Name: grant_pg_cron_access(); Type: FUNCTION; Schema: extensions; Owner: -
--

CREATE FUNCTION extensions.grant_pg_cron_access() RETURNS event_trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF EXISTS (
    SELECT
    FROM pg_event_trigger_ddl_commands() AS ev
    JOIN pg_extension AS ext
    ON ev.objid = ext.oid
    WHERE ext.extname = 'pg_cron'
  )
  THEN
    grant usage on schema cron to postgres with grant option;

    alter default privileges in schema cron grant all on tables to postgres with grant option;
    alter default privileges in schema cron grant all on functions to postgres with grant option;
    alter default privileges in schema cron grant all on sequences to postgres with grant option;

    alter default privileges for user supabase_admin in schema cron grant all
        on sequences to postgres with grant option;
    alter default privileges for user supabase_admin in schema cron grant all
        on tables to postgres with grant option;
    alter default privileges for user supabase_admin in schema cron grant all
        on functions to postgres with grant option;

    grant all privileges on all tables in schema cron to postgres with grant option;
    revoke all on table cron.job from postgres;
    grant select on table cron.job to postgres with grant option;
  END IF;
END;
$$;


--
-- Name: FUNCTION grant_pg_cron_access(); Type: COMMENT; Schema: extensions; Owner: -
--

COMMENT ON FUNCTION extensions.grant_pg_cron_access() IS 'Grants access to pg_cron';


--
-- Name: grant_pg_graphql_access(); Type: FUNCTION; Schema: extensions; Owner: -
--

CREATE FUNCTION extensions.grant_pg_graphql_access() RETURNS event_trigger
    LANGUAGE plpgsql
    AS $_$
begin
    if not exists (
        select 1
        from pg_event_trigger_ddl_commands() ev
        join pg_catalog.pg_extension e on ev.objid = e.oid
        where e.extname = 'pg_graphql'
    ) then
        return;
    end if;

    drop function if exists graphql_public.graphql;
    create or replace function graphql_public.graphql(
        "operationName" text default null,
        query text default null,
        variables jsonb default null,
        extensions jsonb default null
    )
        returns jsonb
        language sql
    as $$
        select graphql.resolve(
            query := query,
            variables := coalesce(variables, '{}'),
            "operationName" := "operationName",
            extensions := extensions
        );
    $$;

    -- Attach the wrapper to the extension so DROP EXTENSION cascades to it,
    -- which in turn triggers set_graphql_placeholder to reinstall the "not enabled" stub.
    alter extension pg_graphql add function graphql_public.graphql(text, text, jsonb, jsonb);

    grant usage on schema graphql to postgres, anon, authenticated, service_role;
    grant execute on function graphql.resolve to postgres, anon, authenticated, service_role;
    grant usage on schema graphql to postgres with grant option;
    grant usage on schema graphql_public to postgres with grant option;
end;
$_$;


--
-- Name: FUNCTION grant_pg_graphql_access(); Type: COMMENT; Schema: extensions; Owner: -
--

COMMENT ON FUNCTION extensions.grant_pg_graphql_access() IS 'Grants access to pg_graphql';


--
-- Name: grant_pg_net_access(); Type: FUNCTION; Schema: extensions; Owner: -
--

CREATE FUNCTION extensions.grant_pg_net_access() RETURNS event_trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM pg_event_trigger_ddl_commands() AS ev
    JOIN pg_extension AS ext
    ON ev.objid = ext.oid
    WHERE ext.extname = 'pg_net'
  )
  THEN
    IF NOT EXISTS (
      SELECT 1
      FROM pg_roles
      WHERE rolname = 'supabase_functions_admin'
    )
    THEN
      CREATE USER supabase_functions_admin NOINHERIT CREATEROLE LOGIN NOREPLICATION;
    END IF;

    GRANT USAGE ON SCHEMA net TO supabase_functions_admin, postgres, anon, authenticated, service_role;

    IF EXISTS (
      SELECT FROM pg_extension
      WHERE extname = 'pg_net'
      -- all versions in use on existing projects as of 2025-02-20
      -- version 0.12.0 onwards don't need these applied
      AND extversion IN ('0.2', '0.6', '0.7', '0.7.1', '0.8', '0.10.0', '0.11.0')
    ) THEN
      ALTER function net.http_get(url text, params jsonb, headers jsonb, timeout_milliseconds integer) SECURITY DEFINER;
      ALTER function net.http_post(url text, body jsonb, params jsonb, headers jsonb, timeout_milliseconds integer) SECURITY DEFINER;

      ALTER function net.http_get(url text, params jsonb, headers jsonb, timeout_milliseconds integer) SET search_path = net;
      ALTER function net.http_post(url text, body jsonb, params jsonb, headers jsonb, timeout_milliseconds integer) SET search_path = net;

      REVOKE ALL ON FUNCTION net.http_get(url text, params jsonb, headers jsonb, timeout_milliseconds integer) FROM PUBLIC;
      REVOKE ALL ON FUNCTION net.http_post(url text, body jsonb, params jsonb, headers jsonb, timeout_milliseconds integer) FROM PUBLIC;

      GRANT EXECUTE ON FUNCTION net.http_get(url text, params jsonb, headers jsonb, timeout_milliseconds integer) TO supabase_functions_admin, postgres, anon, authenticated, service_role;
      GRANT EXECUTE ON FUNCTION net.http_post(url text, body jsonb, params jsonb, headers jsonb, timeout_milliseconds integer) TO supabase_functions_admin, postgres, anon, authenticated, service_role;
    END IF;
  END IF;
END;
$$;


--
-- Name: FUNCTION grant_pg_net_access(); Type: COMMENT; Schema: extensions; Owner: -
--

COMMENT ON FUNCTION extensions.grant_pg_net_access() IS 'Grants access to pg_net';


--
-- Name: pgrst_ddl_watch(); Type: FUNCTION; Schema: extensions; Owner: -
--

CREATE FUNCTION extensions.pgrst_ddl_watch() RETURNS event_trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  cmd record;
BEGIN
  FOR cmd IN SELECT * FROM pg_event_trigger_ddl_commands()
  LOOP
    IF cmd.command_tag IN (
      'CREATE SCHEMA', 'ALTER SCHEMA'
    , 'CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO', 'ALTER TABLE'
    , 'CREATE FOREIGN TABLE', 'ALTER FOREIGN TABLE'
    , 'CREATE VIEW', 'ALTER VIEW'
    , 'CREATE MATERIALIZED VIEW', 'ALTER MATERIALIZED VIEW'
    , 'CREATE FUNCTION', 'ALTER FUNCTION'
    , 'CREATE TRIGGER'
    , 'CREATE TYPE', 'ALTER TYPE'
    , 'CREATE RULE'
    , 'COMMENT'
    )
    -- don't notify in case of CREATE TEMP table or other objects created on pg_temp
    AND cmd.schema_name is distinct from 'pg_temp'
    THEN
      NOTIFY pgrst, 'reload schema';
    END IF;
  END LOOP;
END; $$;


--
-- Name: pgrst_drop_watch(); Type: FUNCTION; Schema: extensions; Owner: -
--

CREATE FUNCTION extensions.pgrst_drop_watch() RETURNS event_trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  obj record;
BEGIN
  FOR obj IN SELECT * FROM pg_event_trigger_dropped_objects()
  LOOP
    IF obj.object_type IN (
      'schema'
    , 'table'
    , 'foreign table'
    , 'view'
    , 'materialized view'
    , 'function'
    , 'trigger'
    , 'type'
    , 'rule'
    )
    AND obj.is_temporary IS false -- no pg_temp objects
    THEN
      NOTIFY pgrst, 'reload schema';
    END IF;
  END LOOP;
END; $$;


--
-- Name: set_graphql_placeholder(); Type: FUNCTION; Schema: extensions; Owner: -
--

CREATE FUNCTION extensions.set_graphql_placeholder() RETURNS event_trigger
    LANGUAGE plpgsql
    AS $_$
    DECLARE
    graphql_is_dropped bool;
    BEGIN
    graphql_is_dropped = (
        SELECT ev.schema_name = 'graphql_public'
        FROM pg_event_trigger_dropped_objects() AS ev
        WHERE ev.schema_name = 'graphql_public'
    );

    IF graphql_is_dropped
    THEN
        create or replace function graphql_public.graphql(
            "operationName" text default null,
            query text default null,
            variables jsonb default null,
            extensions jsonb default null
        )
            returns jsonb
            language plpgsql
        as $$
            DECLARE
                server_version float;
            BEGIN
                server_version = (SELECT (SPLIT_PART((select version()), ' ', 2))::float);

                IF server_version >= 14 THEN
                    RETURN jsonb_build_object(
                        'errors', jsonb_build_array(
                            jsonb_build_object(
                                'message', 'pg_graphql extension is not enabled.'
                            )
                        )
                    );
                ELSE
                    RETURN jsonb_build_object(
                        'errors', jsonb_build_array(
                            jsonb_build_object(
                                'message', 'pg_graphql is only available on projects running Postgres 14 onwards.'
                            )
                        )
                    );
                END IF;
            END;
        $$;
    END IF;

    END;
$_$;


--
-- Name: FUNCTION set_graphql_placeholder(); Type: COMMENT; Schema: extensions; Owner: -
--

COMMENT ON FUNCTION extensions.set_graphql_placeholder() IS 'Reintroduces placeholder function for graphql_public.graphql';


--
-- Name: graphql(text, text, jsonb, jsonb); Type: FUNCTION; Schema: graphql_public; Owner: -
--

CREATE FUNCTION graphql_public.graphql("operationName" text DEFAULT NULL::text, query text DEFAULT NULL::text, variables jsonb DEFAULT NULL::jsonb, extensions jsonb DEFAULT NULL::jsonb) RETURNS jsonb
    LANGUAGE plpgsql
    AS $$
            DECLARE
                server_version float;
            BEGIN
                server_version = (SELECT (SPLIT_PART((select version()), ' ', 2))::float);

                IF server_version >= 14 THEN
                    RETURN jsonb_build_object(
                        'errors', jsonb_build_array(
                            jsonb_build_object(
                                'message', 'pg_graphql extension is not enabled.'
                            )
                        )
                    );
                ELSE
                    RETURN jsonb_build_object(
                        'errors', jsonb_build_array(
                            jsonb_build_object(
                                'message', 'pg_graphql is only available on projects running Postgres 14 onwards.'
                            )
                        )
                    );
                END IF;
            END;
        $$;


--
-- Name: get_auth(text); Type: FUNCTION; Schema: pgbouncer; Owner: -
--

CREATE FUNCTION pgbouncer.get_auth(p_usename text) RETURNS TABLE(username text, password text)
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $_$
  BEGIN
      RAISE DEBUG 'PgBouncer auth request: %', p_usename;

      RETURN QUERY
      SELECT
          rolname::text,
          CASE WHEN rolvaliduntil < now()
              THEN null
              ELSE rolpassword::text
          END
      FROM pg_authid
      WHERE rolname=$1 and rolcanlogin;
  END;
  $_$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: support_internal_notes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.support_internal_notes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    conversation_id uuid NOT NULL,
    staff_user_id uuid NOT NULL,
    client_note_id uuid NOT NULL,
    body text NOT NULL,
    request_hash character(64) NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT support_internal_notes_body_check CHECK (((NULLIF(btrim(body), ''::text) IS NOT NULL) AND (char_length(body) <= 10000))),
    CONSTRAINT support_internal_notes_metadata_check CHECK (((jsonb_typeof(metadata) = 'object'::text) AND (octet_length((metadata)::text) <= 16384))),
    CONSTRAINT support_internal_notes_request_hash_check CHECK ((request_hash ~ '^[0-9a-f]{64}$'::text))
);


--
-- Name: append_support_internal_note(uuid, uuid, uuid, text, jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.append_support_internal_note(p_conversation_id uuid, p_staff_user_id uuid, p_client_note_id uuid, p_body text, p_metadata jsonb DEFAULT '{}'::jsonb) RETURNS public.support_internal_notes
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $$
DECLARE
    conversation_row         public.support_conversations%ROWTYPE;
    inserted_note            public.support_internal_notes%ROWTYPE;
    normalized_body          TEXT;
    request_fingerprint      CHAR(64);
BEGIN
    IF p_conversation_id IS NULL OR p_client_note_id IS NULL THEN
        RAISE EXCEPTION 'Conversation ID and client note ID are required';
    END IF;

    PERFORM public.assert_support_staff_role(p_staff_user_id);

    normalized_body := NULLIF(BTRIM(p_body), '');

    IF normalized_body IS NULL THEN
        RAISE EXCEPTION 'Internal note body is required';
    END IF;

    IF char_length(normalized_body) > 10000 THEN
        RAISE EXCEPTION 'Internal note exceeds 10000 characters';
    END IF;

    IF p_metadata IS NULL OR jsonb_typeof(p_metadata) <> 'object' THEN
        RAISE EXCEPTION 'Internal-note metadata must be a JSON object';
    END IF;

    SELECT *
    INTO conversation_row
    FROM public.support_conversations AS c
    WHERE c.id = p_conversation_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Support conversation not found';
    END IF;

    IF conversation_row.status = 'IDLE' THEN
        RAISE EXCEPTION 'Cannot add an internal note to an idle conversation';
    END IF;

    request_fingerprint := public.sha256_hex(
        jsonb_build_object(
            'body', normalized_body,
            'metadata', p_metadata
        )::TEXT
    );

    SELECT *
    INTO inserted_note
    FROM public.support_internal_notes AS n
    WHERE n.conversation_id = p_conversation_id
      AND n.staff_user_id = p_staff_user_id
      AND n.client_note_id = p_client_note_id;

    IF FOUND THEN
        IF inserted_note.request_hash <> request_fingerprint THEN
            RAISE EXCEPTION
                'Idempotency conflict: client note ID was reused with a different payload';
        END IF;

        RETURN inserted_note;
    END IF;

    INSERT INTO public.support_internal_notes (
        conversation_id,
        staff_user_id,
        client_note_id,
        body,
        request_hash,
        metadata
    )
    VALUES (
        p_conversation_id,
        p_staff_user_id,
        p_client_note_id,
        normalized_body,
        request_fingerprint,
        p_metadata
    )
    RETURNING *
    INTO inserted_note;

    UPDATE public.support_conversations
    SET last_activity_at = inserted_note.created_at
    WHERE id = p_conversation_id;

    RETURN inserted_note;
END;
$$;


--
-- Name: support_messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.support_messages (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    conversation_id uuid NOT NULL,
    sequence_number bigint NOT NULL,
    sender_type character varying(10) NOT NULL,
    sender_user_id uuid NOT NULL,
    body text,
    client_message_id uuid NOT NULL,
    request_hash character(64) NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT check_support_message_body_format CHECK (((body IS NULL) OR ((NULLIF(btrim(body), ''::text) IS NOT NULL) AND (char_length(body) <= 10000)))),
    CONSTRAINT support_messages_metadata_check CHECK (((jsonb_typeof(metadata) = 'object'::text) AND (octet_length((metadata)::text) <= 16384))),
    CONSTRAINT support_messages_request_hash_check CHECK ((request_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT support_messages_sender_type_check CHECK (((sender_type)::text = ANY ((ARRAY['USER'::character varying, 'STAFF'::character varying])::text[]))),
    CONSTRAINT support_messages_sequence_number_check CHECK ((sequence_number > 0))
);


--
-- Name: append_support_staff_message(uuid, uuid, uuid, text, jsonb, jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.append_support_staff_message(p_conversation_id uuid, p_staff_user_id uuid, p_client_message_id uuid, p_body text, p_attachments jsonb DEFAULT '[]'::jsonb, p_metadata jsonb DEFAULT '{}'::jsonb) RETURNS public.support_messages
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $$
DECLARE
    conversation_row         public.support_conversations%ROWTYPE;
    inserted_message         public.support_messages%ROWTYPE;
    normalized_body          TEXT;
    normalized_attachments   JSONB;
    request_fingerprint      CHAR(64);
    allocated_sequence       BIGINT;
BEGIN
    IF p_conversation_id IS NULL OR p_client_message_id IS NULL THEN
        RAISE EXCEPTION 'Conversation ID and client message ID are required';
    END IF;

    PERFORM public.assert_support_staff_role(p_staff_user_id);

    IF p_metadata IS NULL OR jsonb_typeof(p_metadata) <> 'object' THEN
        RAISE EXCEPTION 'Message metadata must be a JSON object';
    END IF;

    normalized_attachments := COALESCE(p_attachments, '[]'::JSONB);

    IF jsonb_typeof(normalized_attachments) <> 'array' THEN
        RAISE EXCEPTION 'Attachments must be a JSON array';
    END IF;

    IF jsonb_array_length(normalized_attachments) > 10 THEN
        RAISE EXCEPTION 'A message may contain at most 10 attachments';
    END IF;

    normalized_body := NULLIF(BTRIM(p_body), '');

    IF normalized_body IS NULL
       AND jsonb_array_length(normalized_attachments) = 0 THEN
        RAISE EXCEPTION
            'A support message must contain a body or at least one attachment';
    END IF;

    IF normalized_body IS NOT NULL
       AND char_length(normalized_body) > 10000 THEN
        RAISE EXCEPTION 'Message body exceeds 10000 characters';
    END IF;

    SELECT *
    INTO conversation_row
    FROM public.support_conversations AS c
    WHERE c.id = p_conversation_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Support conversation not found';
    END IF;

    request_fingerprint := public.sha256_hex(
        jsonb_build_object(
            'sender_type', 'STAFF',
            'body', normalized_body,
            'attachments', normalized_attachments,
            'metadata', p_metadata
        )::TEXT
    );

    SELECT *
    INTO inserted_message
    FROM public.support_messages AS m
    WHERE m.conversation_id = p_conversation_id
      AND m.sender_user_id = p_staff_user_id
      AND m.client_message_id = p_client_message_id;

    IF FOUND THEN
        IF inserted_message.request_hash <> request_fingerprint THEN
            RAISE EXCEPTION
                'Idempotency conflict: client message ID was reused with a different payload';
        END IF;

        RETURN inserted_message;
    END IF;

    IF conversation_row.status = 'IDLE' THEN
        RAISE EXCEPTION
            'Staff cannot send the first public message in an idle conversation';
    END IF;

    IF conversation_row.status = 'CLOSED' THEN
        RAISE EXCEPTION
            'Conversation is closed; reopen it explicitly before replying';
    END IF;

    allocated_sequence := conversation_row.next_public_sequence;

    INSERT INTO public.support_messages (
        conversation_id,
        sequence_number,
        sender_type,
        sender_user_id,
        body,
        client_message_id,
        request_hash,
        metadata
    )
    VALUES (
        p_conversation_id,
        allocated_sequence,
        'STAFF',
        p_staff_user_id,
        normalized_body,
        p_client_message_id,
        request_fingerprint,
        p_metadata
    )
    RETURNING *
    INTO inserted_message;

    PERFORM public.insert_support_attachment_metadata(
        inserted_message.id,
        normalized_attachments
    );

    UPDATE public.support_conversations
    SET
        next_public_sequence = allocated_sequence + 1,
        status = 'WAITING_USER',
        assigned_staff_user_id = COALESCE(
            assigned_staff_user_id,
            p_staff_user_id
        ),
        last_public_message_at = inserted_message.created_at,
        last_public_message_sender_type = 'STAFF',
        waiting_since = NULL,
        first_staff_response_at = COALESCE(
            first_staff_response_at,
            inserted_message.created_at
        ),
        last_activity_at = inserted_message.created_at
    WHERE id = p_conversation_id;

    RETURN inserted_message;
END;
$$;


--
-- Name: append_support_user_message(uuid, uuid, text, jsonb, jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.append_support_user_message(p_user_id uuid, p_client_message_id uuid, p_body text, p_attachments jsonb DEFAULT '[]'::jsonb, p_metadata jsonb DEFAULT '{}'::jsonb) RETURNS public.support_messages
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $$
DECLARE
    conversation_row         public.support_conversations%ROWTYPE;
    inserted_message         public.support_messages%ROWTYPE;
    normalized_body          TEXT;
    normalized_attachments   JSONB;
    request_fingerprint      CHAR(64);
    allocated_sequence       BIGINT;
BEGIN
    IF p_user_id IS NULL OR p_client_message_id IS NULL THEN
        RAISE EXCEPTION 'User ID and client message ID are required';
    END IF;

    IF p_metadata IS NULL OR jsonb_typeof(p_metadata) <> 'object' THEN
        RAISE EXCEPTION 'Message metadata must be a JSON object';
    END IF;

    normalized_attachments := COALESCE(p_attachments, '[]'::JSONB);

    IF jsonb_typeof(normalized_attachments) <> 'array' THEN
        RAISE EXCEPTION 'Attachments must be a JSON array';
    END IF;

    IF jsonb_array_length(normalized_attachments) > 10 THEN
        RAISE EXCEPTION 'A message may contain at most 10 attachments';
    END IF;

    normalized_body := NULLIF(BTRIM(p_body), '');

    IF normalized_body IS NULL
       AND jsonb_array_length(normalized_attachments) = 0 THEN
        RAISE EXCEPTION
            'A support message must contain a body or at least one attachment';
    END IF;

    IF normalized_body IS NOT NULL
       AND char_length(normalized_body) > 10000 THEN
        RAISE EXCEPTION 'Message body exceeds 10000 characters';
    END IF;

    SELECT *
    INTO conversation_row
    FROM public.support_conversations AS c
    WHERE c.user_id = p_user_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Default support conversation was not provisioned for this user';
    END IF;

    request_fingerprint := public.sha256_hex(
        jsonb_build_object(
            'sender_type', 'USER',
            'body', normalized_body,
            'attachments', normalized_attachments,
            'metadata', p_metadata
        )::TEXT
    );

    SELECT *
    INTO inserted_message
    FROM public.support_messages AS m
    WHERE m.conversation_id = conversation_row.id
      AND m.sender_user_id = p_user_id
      AND m.client_message_id = p_client_message_id;

    IF FOUND THEN
        IF inserted_message.request_hash <> request_fingerprint THEN
            RAISE EXCEPTION
                'Idempotency conflict: client message ID was reused with a different payload';
        END IF;

        RETURN inserted_message;
    END IF;

    allocated_sequence := conversation_row.next_public_sequence;

    INSERT INTO public.support_messages (
        conversation_id,
        sequence_number,
        sender_type,
        sender_user_id,
        body,
        client_message_id,
        request_hash,
        metadata
    )
    VALUES (
        conversation_row.id,
        allocated_sequence,
        'USER',
        p_user_id,
        normalized_body,
        p_client_message_id,
        request_fingerprint,
        p_metadata
    )
    RETURNING *
    INTO inserted_message;

    PERFORM public.insert_support_attachment_metadata(
        inserted_message.id,
        normalized_attachments
    );

    UPDATE public.support_conversations
    SET
        next_public_sequence = allocated_sequence + 1,
        status = 'WAITING_STAFF',
        last_public_message_at = inserted_message.created_at,
        last_public_message_sender_type = 'USER',
        waiting_since = CASE
            WHEN conversation_row.status = 'WAITING_STAFF'
                THEN conversation_row.waiting_since
            ELSE inserted_message.created_at
        END,
        last_activity_at = inserted_message.created_at,
        closed_at = NULL,
        closed_by_app_user_id = NULL,
        closed_by_type = NULL
    WHERE id = conversation_row.id;

    RETURN inserted_message;
END;
$$;


--
-- Name: assert_support_staff_role(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.assert_support_staff_role(p_staff_user_id uuid) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $$
BEGIN
    IF p_staff_user_id IS NULL OR NOT EXISTS (
        SELECT 1
        FROM public.app_users AS u
        WHERE u.id = p_staff_user_id
          AND u.role::TEXT IN ('ADMIN', 'MODERATOR')
    ) THEN
        RAISE EXCEPTION
            'ADMIN or MODERATOR role is required for support staff operations';
    END IF;
END;
$$;


--
-- Name: assign_support_conversation(uuid, uuid, uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.assign_support_conversation(p_conversation_id uuid, p_actor_staff_user_id uuid, p_assigned_staff_user_id uuid) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $$
BEGIN
    PERFORM public.assert_support_staff_role(p_actor_staff_user_id);

    IF p_assigned_staff_user_id IS NOT NULL THEN
        PERFORM public.assert_support_staff_role(p_assigned_staff_user_id);
    END IF;

    UPDATE public.support_conversations
    SET assigned_staff_user_id = p_assigned_staff_user_id
    WHERE id = p_conversation_id
      AND status <> 'IDLE';

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Active support conversation not found';
    END IF;
END;
$$;


--
-- Name: auto_set_visible_on_primary_photo_approval(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.auto_set_visible_on_primary_photo_approval() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
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


--
-- Name: calculate_age(date, date); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.calculate_age(p_date_of_birth date, p_as_of_date date DEFAULT CURRENT_DATE) RETURNS integer
    LANGUAGE sql STABLE
    AS $$
    SELECT EXTRACT(YEAR FROM age(p_as_of_date, p_date_of_birth))::INTEGER;
$$;


--
-- Name: chat_realtime_is_active_match_member(text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.chat_realtime_is_active_match_member(p_topic text, p_kind text) RETURNS boolean
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $_$
DECLARE
    v_match_id UUID;
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RETURN FALSE;
    END IF;

    IF p_kind NOT IN ('events', 'typing', 'presence') THEN
        RETURN FALSE;
    END IF;

    IF p_topic !~ (
        '^match:[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-'
        || '[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}:'
        || p_kind
        || '$'
    ) THEN
        RETURN FALSE;
    END IF;

    v_match_id := split_part(p_topic, ':', 2)::UUID;

    RETURN EXISTS (
        SELECT 1
        FROM public.matches m
        JOIN public.app_users au
            ON au.id = v_user_id
           AND au.status = 'ACTIVE'
        WHERE m.id = v_match_id
          AND m.status = 'ACTIVE'
          AND (
              m.user_one_id = v_user_id
              OR m.user_two_id = v_user_id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM public.user_blocks ub
              WHERE ub.status = 'ACTIVE'
                AND (
                    (
                        ub.blocker_user_id = m.user_one_id
                        AND ub.blocked_user_id = m.user_two_id
                    )
                    OR
                    (
                        ub.blocker_user_id = m.user_two_id
                        AND ub.blocked_user_id = m.user_one_id
                    )
                )
          )
    );
END;
$_$;


--
-- Name: chat_realtime_is_own_inbox_topic(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.chat_realtime_is_own_inbox_topic(p_topic text) RETURNS boolean
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $_$
DECLARE
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RETURN FALSE;
    END IF;

    IF p_topic !~ (
        '^user:[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-'
        || '[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}:inbox$'
    ) THEN
        RETURN FALSE;
    END IF;

    IF split_part(p_topic, ':', 2)::UUID <> v_user_id THEN
        RETURN FALSE;
    END IF;

    RETURN EXISTS (
        SELECT 1
        FROM public.app_users au
        WHERE au.id = v_user_id
          AND au.status = 'ACTIVE'
    );
END;
$_$;


--
-- Name: close_support_conversation_by_staff(uuid, uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.close_support_conversation_by_staff(p_conversation_id uuid, p_staff_user_id uuid) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $$
BEGIN
    PERFORM public.assert_support_staff_role(p_staff_user_id);

    UPDATE public.support_conversations
    SET
        status = 'CLOSED',
        waiting_since = NULL,
        closed_at = CURRENT_TIMESTAMP,
        closed_by_app_user_id = p_staff_user_id,
        closed_by_type = 'STAFF',
        last_activity_at = CURRENT_TIMESTAMP
    WHERE id = p_conversation_id
      AND status <> 'IDLE';

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Active support conversation not found';
    END IF;
END;
$$;


--
-- Name: close_support_conversation_by_user(uuid, uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.close_support_conversation_by_user(p_conversation_id uuid, p_user_id uuid) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $$
BEGIN
    UPDATE public.support_conversations
    SET
        status = 'CLOSED',
        waiting_since = NULL,
        closed_at = CURRENT_TIMESTAMP,
        closed_by_app_user_id = p_user_id,
        closed_by_type = 'USER',
        last_activity_at = CURRENT_TIMESTAMP
    WHERE id = p_conversation_id
      AND user_id = p_user_id
      AND status <> 'IDLE';

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Active support conversation not found';
    END IF;
END;
$$;


--
-- Name: create_default_notification_preferences(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.create_default_notification_preferences() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    INSERT INTO public.user_notification_preferences (user_id)
    VALUES (NEW.id)
    ON CONFLICT (user_id) DO NOTHING;

    RETURN NEW;
END;
$$;


--
-- Name: create_default_support_conversation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.create_default_support_conversation() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $$
BEGIN
    INSERT INTO public.support_conversations (
        user_id,
        status
    )
    VALUES (
        NEW.id,
        'IDLE'
    )
    ON CONFLICT (user_id) DO NOTHING;

    RETURN NEW;
END;
$$;


--
-- Name: end_active_matches_when_blocked(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.end_active_matches_when_blocked() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.status = 'ACTIVE'
       AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM 'ACTIVE') THEN

        UPDATE public.matches
        SET status = 'ENDED',
            end_reason = 'BLOCKED',
            ended_by_user_id = NEW.blocker_user_id,
            ended_at = CURRENT_TIMESTAMP,
            updated_at = CURRENT_TIMESTAMP
        WHERE status = 'ACTIVE'
          AND (
              (user_one_id = NEW.blocker_user_id AND user_two_id = NEW.blocked_user_id)
              OR
              (user_one_id = NEW.blocked_user_id AND user_two_id = NEW.blocker_user_id)
          );
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: enforce_discovery_action_immutability(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_discovery_action_immutability() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.actor_user_id IS DISTINCT FROM OLD.actor_user_id
       OR NEW.target_user_id IS DISTINCT FROM OLD.target_user_id
       OR NEW.action_type IS DISTINCT FROM OLD.action_type
       OR NEW.client_action_id IS DISTINCT FROM OLD.client_action_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Discovery action identity fields are immutable.';
    END IF;

    IF OLD.status = 'REVERSED' AND NEW.status <> 'REVERSED' THEN
        RAISE EXCEPTION 'A reversed discovery action cannot be reactivated.';
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: enforce_match_immutability(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_match_immutability() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.user_one_id IS DISTINCT FROM OLD.user_one_id
       OR NEW.user_two_id IS DISTINCT FROM OLD.user_two_id
       OR NEW.user_one_like_action_id IS DISTINCT FROM OLD.user_one_like_action_id
       OR NEW.user_two_like_action_id IS DISTINCT FROM OLD.user_two_like_action_id
       OR NEW.created_by_action_id IS DISTINCT FROM OLD.created_by_action_id
       OR NEW.matched_at IS DISTINCT FROM OLD.matched_at
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Match identity fields are immutable.';
    END IF;

    IF OLD.status = 'ENDED' AND NEW.status <> 'ENDED' THEN
        RAISE EXCEPTION 'An ended match cannot be reactivated.';
    END IF;

    IF OLD.status = 'ENDED'
       AND (
           NEW.end_reason IS DISTINCT FROM OLD.end_reason
           OR NEW.ended_at IS DISTINCT FROM OLD.ended_at
           OR NEW.ended_by_user_id IS DISTINCT FROM OLD.ended_by_user_id
       ) THEN
        RAISE EXCEPTION 'An ended match cannot have its end state rewritten.';
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: enforce_message_identity_immutability(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_message_identity_immutability() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.match_id IS DISTINCT FROM OLD.match_id
       OR NEW.sender_user_id IS DISTINCT FROM OLD.sender_user_id
       OR NEW.client_message_id IS DISTINCT FROM OLD.client_message_id
       OR NEW.sequence_number IS DISTINCT FROM OLD.sequence_number
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION
            'Message identity and sequence fields are immutable.';
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: enforce_notification_campaign_lifecycle(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_notification_campaign_lifecycle() RETURNS trigger
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


--
-- Name: handle_new_auth_user(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.handle_new_auth_user() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public', 'auth'
    AS $$
BEGIN
    INSERT INTO public.app_users (id)
    VALUES (NEW.id)
    ON CONFLICT (id) DO NOTHING;

    RETURN NEW;
END;
$$;


--
-- Name: insert_support_attachment_metadata(uuid, jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.insert_support_attachment_metadata(p_message_id uuid, p_attachments jsonb) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $$
DECLARE
    attachment_item   JSONB;
    v_bucket          TEXT;
    v_path            TEXT;
    v_file_name       TEXT;
    v_content_type    TEXT;
    v_size            BIGINT;
    v_kind            TEXT;
    v_duration        BIGINT;
BEGIN
    IF p_attachments IS NULL THEN
        p_attachments := '[]'::JSONB;
    END IF;

    IF jsonb_typeof(p_attachments) <> 'array' THEN
        RAISE EXCEPTION 'Attachments must be a JSON array';
    END IF;

    IF jsonb_array_length(p_attachments) > 10 THEN
        RAISE EXCEPTION 'A message may contain at most 10 attachments';
    END IF;

    FOR attachment_item IN
        SELECT value
        FROM jsonb_array_elements(p_attachments)
    LOOP
        IF jsonb_typeof(attachment_item) <> 'object' THEN
            RAISE EXCEPTION 'Every attachment entry must be a JSON object';
        END IF;

        IF NOT (
            attachment_item ? 'storage_bucket'
            AND attachment_item ? 'storage_path'
            AND attachment_item ? 'file_name'
            AND attachment_item ? 'content_type'
            AND attachment_item ? 'file_size_bytes'
        ) THEN
            RAISE EXCEPTION 'Attachment metadata is missing required fields';
        END IF;

        v_bucket       := NULLIF(BTRIM(attachment_item ->> 'storage_bucket'), '');
        v_path         := NULLIF(BTRIM(attachment_item ->> 'storage_path'), '');
        v_file_name    := NULLIF(BTRIM(attachment_item ->> 'file_name'), '');
        v_content_type := NULLIF(BTRIM(attachment_item ->> 'content_type'), '');

        BEGIN
            v_size := (attachment_item ->> 'file_size_bytes')::BIGINT;
        EXCEPTION
            WHEN invalid_text_representation OR numeric_value_out_of_range THEN
                RAISE EXCEPTION 'Attachment file_size_bytes must be an integer';
        END;

        v_kind := NULLIF(BTRIM(attachment_item ->> 'attachment_kind'), '');

        IF v_kind IS NOT NULL AND v_kind NOT IN ('IMAGE', 'DOCUMENT', 'TEXT', 'VOICE', 'OTHER') THEN
            RAISE EXCEPTION 'Invalid attachment_kind: %', v_kind;
        END IF;

        BEGIN
            v_duration := NULLIF(attachment_item ->> 'duration_ms', '')::BIGINT;
        EXCEPTION
            WHEN invalid_text_representation OR numeric_value_out_of_range THEN
                v_duration := NULL;
        END;

        IF v_kind = 'VOICE' THEN
            IF v_duration IS NULL OR v_duration <= 0 THEN
                RAISE EXCEPTION 'Voice attachments must have a positive duration_ms';
            END IF;
        ELSIF v_kind IS NOT NULL AND v_kind <> 'VOICE' AND v_duration IS NOT NULL THEN
            v_duration := NULL;
        END IF;

        INSERT INTO public.support_attachments (
            message_id,
            storage_bucket,
            storage_path,
            file_name,
            content_type,
            file_size_bytes,
            attachment_kind,
            duration_ms
        )
        VALUES (
            p_message_id,
            v_bucket,
            v_path,
            v_file_name,
            v_content_type,
            v_size,
            v_kind,
            v_duration
        );
    END LOOP;
END;
$$;


--
-- Name: mark_support_conversation_read_by_staff(uuid, uuid, bigint); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.mark_support_conversation_read_by_staff(p_conversation_id uuid, p_staff_user_id uuid, p_last_read_sequence bigint) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $$
DECLARE
    maximum_sequence BIGINT;
    bounded_sequence BIGINT;
BEGIN
    IF p_last_read_sequence IS NULL OR p_last_read_sequence < 0 THEN
        RAISE EXCEPTION 'Read sequence must be a non-negative integer';
    END IF;

    PERFORM public.assert_support_staff_role(p_staff_user_id);

    SELECT c.next_public_sequence - 1
    INTO maximum_sequence
    FROM public.support_conversations AS c
    WHERE c.id = p_conversation_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Support conversation not found';
    END IF;

    bounded_sequence := LEAST(
        p_last_read_sequence,
        maximum_sequence
    );

    INSERT INTO public.support_conversation_staff_reads (
        conversation_id,
        staff_user_id,
        last_read_sequence,
        last_read_at
    )
    VALUES (
        p_conversation_id,
        p_staff_user_id,
        bounded_sequence,
        CURRENT_TIMESTAMP
    )
    ON CONFLICT (conversation_id, staff_user_id)
    DO UPDATE SET
        last_read_sequence = GREATEST(
            public.support_conversation_staff_reads.last_read_sequence,
            EXCLUDED.last_read_sequence
        ),
        last_read_at = CURRENT_TIMESTAMP;

    UPDATE public.support_conversations
    SET staff_last_read_sequence = GREATEST(
        staff_last_read_sequence,
        bounded_sequence
    )
    WHERE id = p_conversation_id;
END;
$$;


--
-- Name: mark_support_conversation_read_by_user(uuid, uuid, bigint); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.mark_support_conversation_read_by_user(p_conversation_id uuid, p_user_id uuid, p_last_read_sequence bigint) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $$
DECLARE
    maximum_sequence BIGINT;
BEGIN
    IF p_last_read_sequence IS NULL OR p_last_read_sequence < 0 THEN
        RAISE EXCEPTION 'Read sequence must be a non-negative integer';
    END IF;

    SELECT c.next_public_sequence - 1
    INTO maximum_sequence
    FROM public.support_conversations AS c
    WHERE c.id = p_conversation_id
      AND c.user_id = p_user_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Support conversation not found';
    END IF;

    UPDATE public.support_conversations
    SET user_last_read_sequence = GREATEST(
        user_last_read_sequence,
        LEAST(p_last_read_sequence, maximum_sequence)
    )
    WHERE id = p_conversation_id;
END;
$$;


--
-- Name: prevent_audit_log_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.prevent_audit_log_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only and cannot be updated or deleted.';
END;
$$;


--
-- Name: reopen_support_conversation_by_staff(uuid, uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reopen_support_conversation_by_staff(p_conversation_id uuid, p_staff_user_id uuid) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $$
DECLARE
    conversation_row public.support_conversations%ROWTYPE;
BEGIN
    PERFORM public.assert_support_staff_role(p_staff_user_id);

    SELECT *
    INTO conversation_row
    FROM public.support_conversations AS c
    WHERE c.id = p_conversation_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Support conversation not found';
    END IF;

    IF conversation_row.status <> 'CLOSED' THEN
        RAISE EXCEPTION 'Only a closed conversation may be reopened';
    END IF;

    UPDATE public.support_conversations
    SET
        status = CASE
            WHEN conversation_row.last_public_message_sender_type = 'USER'
                THEN 'WAITING_STAFF'
            ELSE 'WAITING_USER'
        END,
        waiting_since = CASE
            WHEN conversation_row.last_public_message_sender_type = 'USER'
                THEN CURRENT_TIMESTAMP
            ELSE NULL
        END,
        assigned_staff_user_id = COALESCE(
            assigned_staff_user_id,
            p_staff_user_id
        ),
        closed_at = NULL,
        closed_by_app_user_id = NULL,
        closed_by_type = NULL,
        last_activity_at = CURRENT_TIMESTAMP
    WHERE id = p_conversation_id;
END;
$$;


--
-- Name: rls_auto_enable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.rls_auto_enable() RETURNS event_trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
DECLARE
  cmd record;
BEGIN
  FOR cmd IN
    SELECT *
    FROM pg_event_trigger_ddl_commands()
    WHERE command_tag IN ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
      AND object_type IN ('table','partitioned table')
  LOOP
     IF cmd.schema_name IS NOT NULL AND cmd.schema_name IN ('public') AND cmd.schema_name NOT IN ('pg_catalog','information_schema') AND cmd.schema_name NOT LIKE 'pg_toast%' AND cmd.schema_name NOT LIKE 'pg_temp%' THEN
      BEGIN
        EXECUTE format('alter table if exists %s enable row level security', cmd.object_identity);
        RAISE LOG 'rls_auto_enable: enabled RLS on %', cmd.object_identity;
      EXCEPTION
        WHEN OTHERS THEN
          RAISE LOG 'rls_auto_enable: failed to enable RLS on %', cmd.object_identity;
      END;
     ELSE
        RAISE LOG 'rls_auto_enable: skip % (either system schema or not in enforced list: %.)', cmd.object_identity, cmd.schema_name;
     END IF;
  END LOOP;
END;
$$;


--
-- Name: set_ethnicities_updated_at(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.set_ethnicities_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END; $$;


--
-- Name: set_languages_updated_at(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.set_languages_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END; $$;


--
-- Name: set_support_conversation_priority(uuid, uuid, smallint); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.set_support_conversation_priority(p_conversation_id uuid, p_actor_staff_user_id uuid, p_priority smallint) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $$
BEGIN
    PERFORM public.assert_support_staff_role(p_actor_staff_user_id);

    IF p_priority IS NULL OR p_priority NOT BETWEEN 1 AND 5 THEN
        RAISE EXCEPTION 'Priority must be between 1 and 5';
    END IF;

    UPDATE public.support_conversations
    SET priority = p_priority
    WHERE id = p_conversation_id
      AND status <> 'IDLE';

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Active support conversation not found';
    END IF;
END;
$$;


--
-- Name: set_support_updated_at(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.set_support_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO ''
    AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;


--
-- Name: sha256_hex(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.sha256_hex(input text) RETURNS text
    LANGUAGE plpgsql IMMUTABLE SECURITY DEFINER PARALLEL SAFE
    SET search_path TO 'extensions'
    AS $$
        BEGIN
            RETURN encode(digest(convert_to(input, 'UTF8'), 'sha256'), 'hex');
        END;
        $$;


--
-- Name: touch_match_last_message_at(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.touch_match_last_message_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    UPDATE public.matches
    SET last_message_at = COALESCE(NEW.created_at, CURRENT_TIMESTAMP),
        updated_at = CURRENT_TIMESTAMP
    WHERE id = NEW.match_id;

    RETURN NEW;
END;
$$;


--
-- Name: touch_match_message_timestamps(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.touch_match_message_timestamps() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.deleted_at IS NULL
       AND NEW.moderation_status = 'APPROVED' THEN

        UPDATE public.matches
        SET
            first_message_at = COALESCE(first_message_at, NEW.created_at),
            last_message_at = CASE
                WHEN last_message_at IS NULL THEN NEW.created_at
                WHEN NEW.created_at > last_message_at THEN NEW.created_at
                ELSE last_message_at
            END,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = NEW.match_id;
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: update_updated_at_column(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_updated_at_column() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;


--
-- Name: validate_active_match_action_states(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_active_match_action_states() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.matches m
        JOIN public.user_discovery_actions a1
            ON a1.id = m.user_one_like_action_id
        JOIN public.user_discovery_actions a2
            ON a2.id = m.user_two_like_action_id
        WHERE m.status = 'ACTIVE'
          AND (a1.status <> 'ACTIVE' OR a2.status <> 'ACTIVE')
    ) THEN
        RAISE EXCEPTION
            'An active match cannot reference a reversed discovery action.';
    END IF;

    RETURN NULL;
END;
$$;


--
-- Name: validate_boost_transaction_owner(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_boost_transaction_owner() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.transaction_id IS NOT NULL
       AND NOT EXISTS (
            SELECT 1
            FROM public.transactions t
            WHERE t.id = NEW.transaction_id
              AND t.user_id = NEW.user_id
              AND t.payment_purpose = 'PROFILE_BOOST'
              AND t.status = 'COMPLETED'
       ) THEN
        RAISE EXCEPTION
            'A boost transaction must belong to the same user, be completed, and be a PROFILE_BOOST purchase.';
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: validate_match_like_actions(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_match_like_actions() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_user_one_action public.user_discovery_actions%ROWTYPE;
    v_user_two_action public.user_discovery_actions%ROWTYPE;
BEGIN
    SELECT *
    INTO v_user_one_action
    FROM public.user_discovery_actions
    WHERE id = NEW.user_one_like_action_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'user_one_like_action_id must exist.';
    END IF;

    SELECT *
    INTO v_user_two_action
    FROM public.user_discovery_actions
    WHERE id = NEW.user_two_like_action_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'user_two_like_action_id must exist.';
    END IF;

    IF v_user_one_action.actor_user_id <> NEW.user_one_id
       OR v_user_one_action.target_user_id <> NEW.user_two_id
       OR v_user_one_action.action_type NOT IN ('LIKE', 'SUPERLIKE')
       OR v_user_one_action.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'user_one_like_action_id is not an active like for this match pair.';
    END IF;

    IF v_user_two_action.actor_user_id <> NEW.user_two_id
       OR v_user_two_action.target_user_id <> NEW.user_one_id
       OR v_user_two_action.action_type NOT IN ('LIKE', 'SUPERLIKE')
       OR v_user_two_action.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'user_two_like_action_id is not an active like for this match pair.';
    END IF;

    IF NEW.created_by_action_id NOT IN (
        NEW.user_one_like_action_id,
        NEW.user_two_like_action_id
    ) THEN
        RAISE EXCEPTION 'created_by_action_id must be one of the two match like actions.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.user_blocks ub
        WHERE ub.status = 'ACTIVE'
          AND (
              (ub.blocker_user_id = NEW.user_one_id
               AND ub.blocked_user_id = NEW.user_two_id)
              OR
              (ub.blocker_user_id = NEW.user_two_id
               AND ub.blocked_user_id = NEW.user_one_id)
          )
    ) THEN
        RAISE EXCEPTION 'A match cannot be created for a blocked user pair.';
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: validate_match_notification_settings_member(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_match_notification_settings_member() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.matches m
        WHERE m.id = NEW.match_id
          AND (
              m.user_one_id = NEW.user_id
              OR m.user_two_id = NEW.user_id
          )
    ) THEN
        RAISE EXCEPTION
            'Notification settings user must be a participant of the match.';
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: validate_message_sender_is_match_participant(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_message_sender_is_match_participant() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.matches m
        JOIN public.app_users sender
            ON sender.id = NEW.sender_user_id
           AND sender.status = 'ACTIVE'
        WHERE m.id = NEW.match_id
          AND m.status = 'ACTIVE'
          AND (
              m.user_one_id = NEW.sender_user_id
              OR m.user_two_id = NEW.sender_user_id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM public.user_blocks ub
              WHERE ub.status = 'ACTIVE'
                AND (
                    (
                        ub.blocker_user_id = m.user_one_id
                        AND ub.blocked_user_id = m.user_two_id
                    )
                    OR
                    (
                        ub.blocker_user_id = m.user_two_id
                        AND ub.blocked_user_id = m.user_one_id
                    )
                )
          )
    ) THEN
        RAISE EXCEPTION
            'Message sender must be an active participant in an active, unblocked match.';
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: validate_payment_order_market(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_payment_order_market() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_offer_country  VARCHAR(10);
    v_offer_platform VARCHAR(20);
    v_method_country VARCHAR(10);
    v_method_platform VARCHAR(20);
BEGIN
    IF NEW.payment_method_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT country_code, platform
        INTO v_offer_country, v_offer_platform
        FROM public.payment_offers
        WHERE id = NEW.payment_offer_id;

    SELECT country_code, platform
        INTO v_method_country, v_method_platform
        FROM public.payment_methods
        WHERE id = NEW.payment_method_id;

    IF v_offer_country IS DISTINCT FROM v_method_country
    OR v_offer_platform IS DISTINCT FROM v_method_platform THEN
        RAISE EXCEPTION
            'payment_order_market_mismatch: offer(country=%, platform=%) vs method(country=%, platform=%)',
            v_offer_country, v_offer_platform,
            v_method_country, v_method_platform;
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: validate_public_support_message_sender(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_public_support_message_sender() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO ''
    AS $$
DECLARE
    conversation_owner_id UUID;
BEGIN
    SELECT c.user_id
    INTO conversation_owner_id
    FROM public.support_conversations AS c
    WHERE c.id = NEW.conversation_id;

    IF conversation_owner_id IS NULL THEN
        RAISE EXCEPTION 'Support conversation does not exist';
    END IF;

    IF NEW.sender_type = 'USER' THEN
        IF NEW.sender_user_id <> conversation_owner_id THEN
            RAISE EXCEPTION
                'User sender does not own the support conversation';
        END IF;
    ELSE
        IF NOT EXISTS (
            SELECT 1
            FROM public.app_users AS u
            WHERE u.id = NEW.sender_user_id
              AND u.role::TEXT IN ('ADMIN', 'MODERATOR')
        ) THEN
            RAISE EXCEPTION
                'Sender must have ADMIN or MODERATOR role';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: validate_user_subscription_paid_plan(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_user_subscription_paid_plan() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.subscription_plans sp
        WHERE sp.id = NEW.plan_id
          AND sp.plan_kind = 'PAID'
    ) THEN
        RAISE EXCEPTION 'user_subscriptions.plan_id must reference a PAID subscription plan.';
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: validate_visible_profile_dependencies(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_visible_profile_dependencies() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public', 'auth'
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


--
-- Name: verify_profile_age_compliance(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.verify_profile_age_compliance() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.date_of_birth > (CURRENT_DATE - INTERVAL '18 years')::DATE THEN
        RAISE EXCEPTION
            'Age Compliance Violation: User profile registration requires a minimum age of 18 years.';
    END IF;

    IF NEW.date_of_birth < (CURRENT_DATE - INTERVAL '120 years')::DATE THEN
        RAISE EXCEPTION
            'Age Compliance Violation: Date of birth is outside the supported range.';
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: apply_rls(jsonb, integer); Type: FUNCTION; Schema: realtime; Owner: -
--

CREATE FUNCTION realtime.apply_rls(wal jsonb, max_record_bytes integer DEFAULT (1024 * 1024)) RETURNS SETOF realtime.wal_rls
    LANGUAGE plpgsql
    AS $$
declare
    -- Regclass of the table e.g. public.notes
    entity_ regclass = (quote_ident(wal ->> 'schema') || '.' || quote_ident(wal ->> 'table'))::regclass;

    -- I, U, D, T: insert, update ...
    action realtime.action = (
        case wal ->> 'action'
            when 'I' then 'INSERT'
            when 'U' then 'UPDATE'
            when 'D' then 'DELETE'
            else 'ERROR'
        end
    );

    -- Is row level security enabled for the table
    is_rls_enabled bool = relrowsecurity from pg_class where oid = entity_;

    subscriptions realtime.subscription[] = array_agg(subs)
        from
            realtime.subscription subs
        where
            subs.entity = entity_
            -- Filter by action early - only get subscriptions interested in this action
            -- action_filter column can be: '*' (all), 'INSERT', 'UPDATE', or 'DELETE'
            and (subs.action_filter = '*' or subs.action_filter = action::text);

    -- Subscription vars
    working_role regrole;
    working_selected_columns text[];
    claimed_role regrole;
    claims jsonb;

    subscription_id uuid;
    subscription_has_access bool;
    visible_to_subscription_ids uuid[] = '{}';

    -- structured info for wal's columns
    columns realtime.wal_column[];
    -- previous identity values for update/delete
    old_columns realtime.wal_column[];

    error_record_exceeds_max_size boolean = octet_length(wal::text) > max_record_bytes;

    -- Primary jsonb output for record
    output jsonb;

    -- Loop record for iterating unique roles (outer loop)
    role_record record;
    -- Loop record for iterating unique selected_columns within a role (inner loop)
    cols_record record;
    -- Subscription ids visible at the role level (before fanning out by selected_columns)
    visible_role_sub_ids uuid[] = '{}';

begin
    perform set_config('role', null, true);

    columns =
        array_agg(
            (
                x->>'name',
                x->>'type',
                x->>'typeoid',
                realtime.cast(
                    (x->'value') #>> '{}',
                    coalesce(
                        (x->>'typeoid')::regtype, -- null when wal2json version <= 2.4
                        (x->>'type')::regtype
                    )
                ),
                (pks ->> 'name') is not null,
                true
            )::realtime.wal_column
        )
        from
            jsonb_array_elements(wal -> 'columns') x
            left join jsonb_array_elements(wal -> 'pk') pks
                on (x ->> 'name') = (pks ->> 'name');

    old_columns =
        array_agg(
            (
                x->>'name',
                x->>'type',
                x->>'typeoid',
                realtime.cast(
                    (x->'value') #>> '{}',
                    coalesce(
                        (x->>'typeoid')::regtype, -- null when wal2json version <= 2.4
                        (x->>'type')::regtype
                    )
                ),
                (pks ->> 'name') is not null,
                true
            )::realtime.wal_column
        )
        from
            jsonb_array_elements(wal -> 'identity') x
            left join jsonb_array_elements(wal -> 'pk') pks
                on (x ->> 'name') = (pks ->> 'name');

    for role_record in
        select claims_role
        from (select distinct claims_role from unnest(subscriptions)) t
        order by claims_role::text
    loop
        working_role := role_record.claims_role;

        -- Update `is_selectable` for columns and old_columns (once per role)
        columns =
            array_agg(
                (
                    c.name,
                    c.type_name,
                    c.type_oid,
                    c.value,
                    c.is_pkey,
                    pg_catalog.has_column_privilege(working_role, entity_, c.name, 'SELECT')
                )::realtime.wal_column
            )
            from
                unnest(columns) c;

        old_columns =
                array_agg(
                    (
                        c.name,
                        c.type_name,
                        c.type_oid,
                        c.value,
                        c.is_pkey,
                        pg_catalog.has_column_privilege(working_role, entity_, c.name, 'SELECT')
                    )::realtime.wal_column
                )
                from
                    unnest(old_columns) c;

        if action <> 'DELETE' and count(1) = 0 from unnest(columns) c where c.is_pkey then
            -- Fan out 400 error per distinct selected_columns for this role
            for cols_record in
                select selected_columns
                from (select distinct selected_columns from unnest(subscriptions) s where s.claims_role = working_role) t
                order by coalesce(array_to_string(selected_columns, ','), '')
            loop
                working_selected_columns := cols_record.selected_columns;
                return next (
                    jsonb_build_object(
                        'schema', wal ->> 'schema',
                        'table', wal ->> 'table',
                        'type', action
                    ),
                    is_rls_enabled,
                    (select array_agg(s.subscription_id) from unnest(subscriptions) as s where s.claims_role = working_role and (s.selected_columns is not distinct from working_selected_columns)),
                    array['Error 400: Bad Request, no primary key']
                )::realtime.wal_rls;
            end loop;

        -- The claims role does not have SELECT permission to the primary key of entity
        elsif action <> 'DELETE' and sum(c.is_selectable::int) <> count(1) from unnest(columns) c where c.is_pkey then
            -- Fan out 401 error per distinct selected_columns for this role
            for cols_record in
                select selected_columns
                from (select distinct selected_columns from unnest(subscriptions) s where s.claims_role = working_role) t
                order by coalesce(array_to_string(selected_columns, ','), '')
            loop
                working_selected_columns := cols_record.selected_columns;
                return next (
                    jsonb_build_object(
                        'schema', wal ->> 'schema',
                        'table', wal ->> 'table',
                        'type', action
                    ),
                    is_rls_enabled,
                    (select array_agg(s.subscription_id) from unnest(subscriptions) as s where s.claims_role = working_role and (s.selected_columns is not distinct from working_selected_columns)),
                    array['Error 401: Unauthorized']
                )::realtime.wal_rls;
            end loop;

        else
            -- Create the prepared statement (once per role)
            if is_rls_enabled and action <> 'DELETE' then
                if (select 1 from pg_prepared_statements where name = 'walrus_rls_stmt' limit 1) > 0 then
                    deallocate walrus_rls_stmt;
                end if;
                execute realtime.build_prepared_statement_sql('walrus_rls_stmt', entity_, columns);
            end if;

            -- Collect all visible subscription IDs for this role (filter check + RLS check)
            visible_role_sub_ids = '{}';

            for subscription_id, claims in (
                    select
                        subs.subscription_id,
                        subs.claims
                    from
                        unnest(subscriptions) subs
                    where
                        subs.entity = entity_
                        and subs.claims_role = working_role
                        and (
                            realtime.is_visible_through_filters(columns, subs.filters)
                            or (
                              action = 'DELETE'
                              and realtime.is_visible_through_filters(old_columns, subs.filters)
                            )
                        )
            ) loop

                if not is_rls_enabled or action = 'DELETE' then
                    visible_role_sub_ids = visible_role_sub_ids || subscription_id;
                else
                    -- Check if RLS allows the role to see the record
                    perform
                        -- Trim leading and trailing quotes from working_role because set_config
                        -- doesn't recognize the role as valid if they are included
                        set_config('role', trim(both '"' from working_role::text), true),
                        set_config('request.jwt.claims', claims::text, true);

                    execute 'execute walrus_rls_stmt' into subscription_has_access;

                    -- Reset the role on every FOR..LOOP batch execution.
                    -- The first batch of 10 rows is pre-fetched using the current connection role (PG internal behaviour)
                    -- then we have to reset it again otherwise it would use the role defined in the `set_config` above
                    -- to fetch the remaining rows when rows>10, which could be a user-defined role that lacks execution grants.
                    -- The flow is:
                    --   1. run batch with conn role
                    --   2. set_config working_role
                    --   3. execute walrus
                    --   4. reset role (revert)
                    --   5. repeat
                    perform set_config('role', null, true);

                    if subscription_has_access then
                        visible_role_sub_ids = visible_role_sub_ids || subscription_id;
                    end if;
                end if;
            end loop;

            perform set_config('role', null, true);

            -- Inner loop: per distinct selected_columns for this role
            for cols_record in
                select selected_columns
                from (select distinct selected_columns from unnest(subscriptions) s where s.claims_role = working_role) t
                order by coalesce(array_to_string(selected_columns, ','), '')
            loop
                working_selected_columns := cols_record.selected_columns;

                output = jsonb_build_object(
                    'schema', wal ->> 'schema',
                    'table', wal ->> 'table',
                    'type', action,
                    'commit_timestamp', to_char(
                        ((wal ->> 'timestamp')::timestamptz at time zone 'utc'),
                        'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'
                    ),
                    'columns', (
                        select
                            jsonb_agg(
                                jsonb_build_object(
                                    'name', pa.attname,
                                    'type', pt.typname
                                )
                                order by pa.attnum asc
                            )
                        from
                            pg_attribute pa
                            join pg_type pt
                                on pa.atttypid = pt.oid
                            left join (
                                select unnest(conkey) as pkey_attnum
                                from pg_constraint
                                where conrelid = entity_ and contype = 'p'
                            ) pk on pk.pkey_attnum = pa.attnum
                        where
                            attrelid = entity_
                            and attnum > 0
                            and pg_catalog.has_column_privilege(working_role, entity_, pa.attname, 'SELECT')
                            and (working_selected_columns is null or pa.attname = any(working_selected_columns) or pk.pkey_attnum is not null)
                    )
                )
                -- Add "record" key for insert and update
                || case
                    when action in ('INSERT', 'UPDATE') then
                        jsonb_build_object(
                            'record',
                            (
                                select
                                    jsonb_object_agg(
                                        -- if unchanged toast, get column name and value from old record
                                        coalesce((c).name, (oc).name),
                                        case
                                            when (c).name is null then (oc).value
                                            else (c).value
                                        end
                                    )
                                from
                                    unnest(columns) c
                                    full outer join unnest(old_columns) oc
                                        on (c).name = (oc).name
                                where
                                    coalesce((c).is_selectable, (oc).is_selectable)
                                    and (working_selected_columns is null or coalesce((c).name, (oc).name) = any(working_selected_columns) or coalesce((c).is_pkey, (oc).is_pkey))
                                    and ( not error_record_exceeds_max_size or (octet_length((c).value::text) <= 64))
                            )
                        )
                    else '{}'::jsonb
                end
                -- Add "old_record" key for update and delete
                || case
                    when action = 'UPDATE' then
                        jsonb_build_object(
                                'old_record',
                                (
                                    select jsonb_object_agg((c).name, (c).value)
                                    from unnest(old_columns) c
                                    where
                                        (c).is_selectable
                                        and (working_selected_columns is null or (c).name = any(working_selected_columns) or (c).is_pkey)
                                        and ( not error_record_exceeds_max_size or (octet_length((c).value::text) <= 64))
                                )
                            )
                    when action = 'DELETE' then
                        jsonb_build_object(
                            'old_record',
                            (
                                select jsonb_object_agg((c).name, (c).value)
                                from unnest(old_columns) c
                                where
                                    (c).is_selectable
                                    and (working_selected_columns is null or (c).name = any(working_selected_columns) or (c).is_pkey)
                                    and ( not error_record_exceeds_max_size or (octet_length((c).value::text) <= 64))
                                    and ( not is_rls_enabled or (c).is_pkey ) -- if RLS enabled, we can't secure deletes so filter to pkey
                            )
                        )
                    else '{}'::jsonb
                end;

                -- Filter visible_role_sub_ids to those matching the current selected_columns group
                visible_to_subscription_ids = coalesce(
                    (
                        select array_agg(s.subscription_id)
                        from unnest(subscriptions) s
                        where s.claims_role = working_role
                          and (s.selected_columns is not distinct from working_selected_columns)
                          and s.subscription_id = any(visible_role_sub_ids)
                    ),
                    '{}'::uuid[]
                );

                return next (
                    output,
                    is_rls_enabled,
                    visible_to_subscription_ids,
                    case
                        when error_record_exceeds_max_size then array['Error 413: Payload Too Large']
                        else '{}'
                    end
                )::realtime.wal_rls;
            end loop;

        end if;
    end loop;

    perform set_config('role', null, true);
end;
$$;


--
-- Name: broadcast_changes(text, text, text, text, text, record, record, text); Type: FUNCTION; Schema: realtime; Owner: -
--

CREATE FUNCTION realtime.broadcast_changes(topic_name text, event_name text, operation text, table_name text, table_schema text, new record, old record, level text DEFAULT 'ROW'::text) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
    -- Declare a variable to hold the JSONB representation of the row
    row_data jsonb := '{}'::jsonb;
BEGIN
    IF level = 'STATEMENT' THEN
        RAISE EXCEPTION 'function can only be triggered for each row, not for each statement';
    END IF;
    -- Check the operation type and handle accordingly
    IF operation = 'INSERT' OR operation = 'UPDATE' OR operation = 'DELETE' THEN
        row_data := jsonb_build_object('old_record', OLD, 'record', NEW, 'operation', operation, 'table', table_name, 'schema', table_schema);
        PERFORM realtime.send (row_data, event_name, topic_name);
    ELSE
        RAISE EXCEPTION 'Unexpected operation type: %', operation;
    END IF;
EXCEPTION
    WHEN OTHERS THEN
        RAISE EXCEPTION 'Failed to process the row: %', SQLERRM;
END;

$$;


--
-- Name: build_prepared_statement_sql(text, regclass, realtime.wal_column[]); Type: FUNCTION; Schema: realtime; Owner: -
--

CREATE FUNCTION realtime.build_prepared_statement_sql(prepared_statement_name text, entity regclass, columns realtime.wal_column[]) RETURNS text
    LANGUAGE sql
    AS $$
      /*
      Builds a sql string that, if executed, creates a prepared statement to
      tests retrive a row from *entity* by its primary key columns.
      Example
          select realtime.build_prepared_statement_sql('public.notes', '{"id"}'::text[], '{"bigint"}'::text[])
      */
          select
      'prepare ' || prepared_statement_name || ' as
          select
              exists(
                  select
                      1
                  from
                      ' || entity || '
                  where
                      ' || string_agg(quote_ident(pkc.name) || '=' || quote_nullable(pkc.value #>> '{}') , ' and ') || '
              )'
          from
              unnest(columns) pkc
          where
              pkc.is_pkey
          group by
              entity
      $$;


--
-- Name: cast(text, regtype); Type: FUNCTION; Schema: realtime; Owner: -
--

CREATE FUNCTION realtime."cast"(val text, type_ regtype) RETURNS jsonb
    LANGUAGE plpgsql IMMUTABLE
    AS $$
declare
  res jsonb;
begin
  if type_::text = 'bytea' then
    return to_jsonb(val);
  end if;
  execute format('select to_jsonb(%L::'|| type_::text || ')', val) into res;
  return res;
end
$$;


--
-- Name: check_equality_op(realtime.equality_op, regtype, text, text); Type: FUNCTION; Schema: realtime; Owner: -
--

CREATE FUNCTION realtime.check_equality_op(op realtime.equality_op, type_ regtype, val_1 text, val_2 text) RETURNS boolean
    LANGUAGE plpgsql IMMUTABLE
    AS $$
/*
Casts *val_1* and *val_2* as type *type_* and check the *op* condition for truthiness
*/
declare
    op_symbol text = (
        case
            when op = 'eq' then '='
            when op = 'neq' then '!='
            when op = 'lt' then '<'
            when op = 'lte' then '<='
            when op = 'gt' then '>'
            when op = 'gte' then '>='
            when op = 'in' then '= any'
            else 'UNKNOWN OP'
        end
    );
    res boolean;
begin
    execute format(
        'select %L::'|| type_::text || ' ' || op_symbol
        || ' ( %L::'
        || (
            case
                when op = 'in' then type_::text || '[]'
                else type_::text end
        )
        || ')', val_1, val_2) into res;
    return res;
end;
$$;


--
-- Name: check_equality_op(realtime.equality_op, regtype, text, text, boolean); Type: FUNCTION; Schema: realtime; Owner: -
--

CREATE FUNCTION realtime.check_equality_op(op realtime.equality_op, type_ regtype, val_1 text, val_2 text, negate boolean) RETURNS boolean
    LANGUAGE plpgsql STABLE
    AS $$
declare
    op_symbol text;
    res boolean;
begin
    -- IS DISTINCT FROM / IS NOT DISTINCT FROM: infix, both sides typed literals
    if op = 'isdistinct' then
        execute format(
            'select %L::%s %s %L::%s',
            val_1,
            type_::text,
            case when negate then 'IS NOT DISTINCT FROM' else 'IS DISTINCT FROM' end,
            val_2,
            type_::text
        ) into res;
        return res;
    end if;

    -- IS requires a keyword RHS (NULL, TRUE, FALSE, UNKNOWN), not a typed literal
    if op = 'is' then
        if val_2 not in ('null', 'true', 'false', 'unknown') then
            raise exception 'invalid value for is filter: must be null, true, false, or unknown';
        end if;
        execute format(
            'select %L::%s %s %s',
            val_1,
            type_::text,
            case when negate then 'IS NOT' else 'IS' end,
            upper(val_2)
        ) into res;
        return res;
    end if;

    op_symbol = case
        when op = 'eq'    then '='
        when op = 'neq'   then '!='
        when op = 'lt'    then '<'
        when op = 'lte'   then '<='
        when op = 'gt'    then '>'
        when op = 'gte'   then '>='
        when op = 'in'    then '= any'
        when op = 'like'   then 'LIKE'
        when op = 'ilike'  then 'ILIKE'
        when op = 'match'  then '~'
        when op = 'imatch' then '~*'
        else null
    end;

    if op_symbol is null then
        raise exception 'unsupported equality operator: %', op::text;
    end if;

    execute format(
        'select %L::%s %s (%L::%s)',
        val_1,
        type_::text,
        op_symbol,
        val_2,
        case when op = 'in' then type_::text || '[]' else type_::text end
    ) into res;

    return case when negate then not res else res end;
end;
$$;


--
-- Name: is_visible_through_filters(realtime.wal_column[], realtime.user_defined_filter[]); Type: FUNCTION; Schema: realtime; Owner: -
--

CREATE FUNCTION realtime.is_visible_through_filters(columns realtime.wal_column[], filters realtime.user_defined_filter[]) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
    select
        filters is null
        or array_length(filters, 1) is null
        or coalesce(
            count(col.name) = count(1)
            and sum(
                realtime.check_equality_op(
                    op:=f.op,
                    type_:=coalesce(col.type_oid::regtype, col.type_name::regtype),
                    val_1:=col.value #>> '{}',
                    val_2:=f.value,
                    negate:=coalesce(f.negate, false)
                )::int
            ) filter (where col.name is not null) = count(col.name),
            false
        )
    from
        unnest(filters) f
        left join unnest(columns) col
            on f.column_name = col.name;
$$;


--
-- Name: list_changes(name, name, integer, integer); Type: FUNCTION; Schema: realtime; Owner: -
--

CREATE FUNCTION realtime.list_changes(publication name, slot_name name, max_changes integer, max_record_bytes integer) RETURNS TABLE(wal jsonb, is_rls_enabled boolean, subscription_ids uuid[], errors text[], slot_changes_count bigint)
    LANGUAGE sql
    SET log_min_messages TO 'fatal'
    AS $$
  WITH pub AS (
    SELECT
      concat_ws(
        ',',
        CASE WHEN bool_or(pubinsert) THEN 'insert' ELSE NULL END,
        CASE WHEN bool_or(pubupdate) THEN 'update' ELSE NULL END,
        CASE WHEN bool_or(pubdelete) THEN 'delete' ELSE NULL END
      ) AS w2j_actions,
      coalesce(
        string_agg(
          realtime.quote_wal2json(format('%I.%I', schemaname, tablename)::regclass),
          ','
        ) filter (WHERE ppt.tablename IS NOT NULL),
        ''
      ) AS w2j_add_tables
    FROM pg_publication pp
    LEFT JOIN pg_publication_tables ppt ON pp.pubname = ppt.pubname
    WHERE pp.pubname = publication
    GROUP BY pp.pubname
    LIMIT 1
  ),
  -- MATERIALIZED ensures pg_logical_slot_get_changes is called exactly once
  w2j AS MATERIALIZED (
    SELECT x.*, pub.w2j_add_tables
    FROM pub,
         pg_logical_slot_get_changes(
           slot_name, null, max_changes,
           'include-pk', 'true',
           'include-transaction', 'false',
           'include-timestamp', 'true',
           'include-type-oids', 'true',
           'format-version', '2',
           'actions', pub.w2j_actions,
           'add-tables', pub.w2j_add_tables
         ) x
  ),
  slot_count AS (
    SELECT count(*)::bigint AS cnt
    FROM w2j
    WHERE w2j.w2j_add_tables <> ''
  ),
  rls_filtered AS (
    SELECT xyz.wal, xyz.is_rls_enabled, xyz.subscription_ids, xyz.errors
    FROM w2j,
         realtime.apply_rls(
           wal := w2j.data::jsonb,
           max_record_bytes := max_record_bytes
         ) xyz(wal, is_rls_enabled, subscription_ids, errors)
    WHERE w2j.w2j_add_tables <> ''
      AND xyz.subscription_ids[1] IS NOT NULL
  )
  SELECT rf.wal, rf.is_rls_enabled, rf.subscription_ids, rf.errors, sc.cnt
  FROM rls_filtered rf, slot_count sc

  UNION ALL

  SELECT null, null, null, null, sc.cnt
  FROM slot_count sc
  WHERE NOT EXISTS (SELECT 1 FROM rls_filtered)
$$;


--
-- Name: quote_wal2json(regclass); Type: FUNCTION; Schema: realtime; Owner: -
--

CREATE FUNCTION realtime.quote_wal2json(entity regclass) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT
    AS $$
  SELECT
    realtime.wal2json_escape_identifier(nsp.nspname::text)
    || '.'
    || realtime.wal2json_escape_identifier(pc.relname::text)
  FROM pg_class pc
  JOIN pg_namespace nsp ON pc.relnamespace = nsp.oid
  WHERE pc.oid = entity
$$;


--
-- Name: send(jsonb, text, text, boolean); Type: FUNCTION; Schema: realtime; Owner: -
--

CREATE FUNCTION realtime.send(payload jsonb, event text, topic text, private boolean DEFAULT true) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
  generated_id uuid;
  final_payload jsonb;
BEGIN
  BEGIN
    generated_id := gen_random_uuid();

    -- Check if payload has an 'id' key, if not, add the generated UUID
    IF payload ? 'id' THEN
      final_payload := payload;
    ELSE
      final_payload := jsonb_set(payload, '{id}', to_jsonb(generated_id));
    END IF;

    -- Set the topic configuration
    EXECUTE format('SET LOCAL realtime.topic TO %L', topic);

    INSERT INTO realtime.messages (id, payload, event, topic, private, extension)
    VALUES (generated_id, final_payload, event, topic, private, 'broadcast');
  EXCEPTION
    WHEN OTHERS THEN
      RAISE WARNING 'WarnSendingBroadcastMessage: %', SQLERRM;
  END;
END;
$$;


--
-- Name: send_binary(bytea, text, text, boolean); Type: FUNCTION; Schema: realtime; Owner: -
--

CREATE FUNCTION realtime.send_binary(payload bytea, event text, topic text, private boolean DEFAULT true) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
  generated_id uuid;
BEGIN
  BEGIN
    generated_id := gen_random_uuid();

    EXECUTE format('SET LOCAL realtime.topic TO %L', topic);

    INSERT INTO realtime.messages (id, binary_payload, event, topic, private, extension)
    VALUES (generated_id, payload, event, topic, private, 'broadcast');
  EXCEPTION
    WHEN OTHERS THEN
      RAISE WARNING 'WarnSendingBroadcastMessage: %', SQLERRM;
  END;
END;
$$;


--
-- Name: subscription_check_filters(); Type: FUNCTION; Schema: realtime; Owner: -
--

CREATE FUNCTION realtime.subscription_check_filters() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
declare
    col_names text[] = coalesce(
            array_agg(a.attname order by a.attnum),
            '{}'::text[]
        )
        from
            pg_catalog.pg_attribute a
        where
            a.attrelid = new.entity
            and a.attnum > 0
            and not a.attisdropped
            and pg_catalog.has_column_privilege(
                (new.claims ->> 'role'),
                a.attrelid,
                a.attnum,
                'SELECT'
            );
    filter realtime.user_defined_filter;
    col_type regtype;
    in_val jsonb;
    selected_col text;
begin
    for filter in select * from unnest(new.filters) loop
        if not filter.column_name = any(col_names) then
            raise exception 'invalid column for filter %', filter.column_name;
        end if;

        col_type = (
            select atttypid::regtype
            from pg_catalog.pg_attribute
            where attrelid = new.entity
                  and attname = filter.column_name
        );
        if col_type is null then
            raise exception 'failed to lookup type for column %', filter.column_name;
        end if;

        if filter.op = 'in'::realtime.equality_op then
            in_val = realtime.cast(filter.value, (col_type::text || '[]')::regtype);
            if coalesce(jsonb_array_length(in_val), 0) > 100 then
                raise exception 'too many values for `in` filter. Maximum 100';
            end if;
        elsif filter.op = 'is'::realtime.equality_op then
            -- `is` requires a keyword RHS rather than a typed literal
            if filter.value not in ('null', 'true', 'false', 'unknown') then
                raise exception 'invalid value for is filter: must be null, true, false, or unknown';
            end if;
            -- IS NULL works for any type, but IS TRUE/FALSE/UNKNOWN require a boolean
            -- operand. Reject the non-null keywords on non-boolean columns here so they
            -- don't abort apply_rls at WAL time.
            if filter.value <> 'null' and col_type <> 'boolean'::regtype then
                raise exception 'is % filter requires a boolean column, got %', filter.value, col_type::text;
            end if;
        elsif filter.op in ('like'::realtime.equality_op, 'ilike'::realtime.equality_op) then
            -- like/ilike apply the text pattern operator (~~); reject column types that
            -- have no such operator instead of failing at WAL time
            if not exists (
                select 1 from pg_catalog.pg_operator
                where oprname = '~~' and oprleft = col_type
            ) then
                raise exception 'operator % requires a text-compatible column type, got %', filter.op::text, col_type::text;
            end if;
        elsif filter.op in ('match'::realtime.equality_op, 'imatch'::realtime.equality_op) then
            -- match/imatch apply the regex operators ~ / ~*; reject column types that have
            -- no such operator (e.g. integer) instead of failing at WAL time, mirroring the
            -- like/ilike guard above.
            if not exists (
                select 1 from pg_catalog.pg_operator
                where oprname = case when filter.op = 'imatch'::realtime.equality_op then '~*' else '~' end
                  and oprleft = col_type
                  and oprright = col_type
                  and oprresult = 'boolean'::regtype
            ) then
                raise exception 'operator % requires a text-compatible column type, got %', filter.op::text, col_type::text;
            end if;
            -- validate the regex eagerly so a bad pattern is rejected here, not inside
            -- apply_rls where it would abort the WAL stream for the entity
            begin
                perform '' ~ filter.value;
            exception when others then
                raise exception 'invalid regular expression for % filter: %', filter.op::text, sqlerrm;
            end;
        else
            -- eq/neq/lt/lte/gt/gte: value must be coercable to the type
            perform realtime.cast(filter.value, col_type);
        end if;
    end loop;

    if new.selected_columns is not null then
        for selected_col in select * from unnest(new.selected_columns) loop
            if not selected_col = any(col_names) then
                raise exception 'invalid column for select %', selected_col;
            end if;
        end loop;
    end if;

    -- Apply consistent order to filters so the unique constraint can't be tricked by a
    -- different filter order. negate is part of the sort key.
    new.filters = coalesce(
        array_agg(f order by f.column_name, f.op, f.value, f.negate),
        '{}'
    ) from unnest(new.filters) f;

    new.selected_columns = (
        select array_agg(c order by c)
        from unnest(new.selected_columns) c
    );

    return new;
end;
$$;


--
-- Name: to_regrole(text); Type: FUNCTION; Schema: realtime; Owner: -
--

CREATE FUNCTION realtime.to_regrole(role_name text) RETURNS regrole
    LANGUAGE sql IMMUTABLE
    AS $$ select role_name::regrole $$;


--
-- Name: topic(); Type: FUNCTION; Schema: realtime; Owner: -
--

CREATE FUNCTION realtime.topic() RETURNS text
    LANGUAGE sql STABLE
    AS $$
select nullif(current_setting('realtime.topic', true), '')::text;
$$;


--
-- Name: wal2json_escape_identifier(text); Type: FUNCTION; Schema: realtime; Owner: -
--

CREATE FUNCTION realtime.wal2json_escape_identifier(name text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT
    AS $$
  -- Prefix `\`, `,`, `.`, and any whitespace with `\`
  SELECT regexp_replace(name, '([\\,.[:space:]])', '\\\1', 'g')
$$;


--
-- Name: allow_any_operation(text[]); Type: FUNCTION; Schema: storage; Owner: -
--

CREATE FUNCTION storage.allow_any_operation(expected_operations text[]) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
  WITH current_operation AS (
    SELECT storage.operation() AS raw_operation
  ),
  normalized AS (
    SELECT CASE
      WHEN raw_operation LIKE 'storage.%' THEN substr(raw_operation, 9)
      ELSE raw_operation
    END AS current_operation
    FROM current_operation
  )
  SELECT EXISTS (
    SELECT 1
    FROM normalized n
    CROSS JOIN LATERAL unnest(expected_operations) AS expected_operation
    WHERE expected_operation IS NOT NULL
      AND expected_operation <> ''
      AND n.current_operation = CASE
        WHEN expected_operation LIKE 'storage.%' THEN substr(expected_operation, 9)
        ELSE expected_operation
      END
  );
$$;


--
-- Name: allow_only_operation(text); Type: FUNCTION; Schema: storage; Owner: -
--

CREATE FUNCTION storage.allow_only_operation(expected_operation text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
  WITH current_operation AS (
    SELECT storage.operation() AS raw_operation
  ),
  normalized AS (
    SELECT
      CASE
        WHEN raw_operation LIKE 'storage.%' THEN substr(raw_operation, 9)
        ELSE raw_operation
      END AS current_operation,
      CASE
        WHEN expected_operation LIKE 'storage.%' THEN substr(expected_operation, 9)
        ELSE expected_operation
      END AS requested_operation
    FROM current_operation
  )
  SELECT CASE
    WHEN requested_operation IS NULL OR requested_operation = '' THEN FALSE
    ELSE COALESCE(current_operation = requested_operation, FALSE)
  END
  FROM normalized;
$$;


--
-- Name: can_insert_object(text, text, uuid, jsonb); Type: FUNCTION; Schema: storage; Owner: -
--

CREATE FUNCTION storage.can_insert_object(bucketid text, name text, owner uuid, metadata jsonb) RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
  INSERT INTO "storage"."objects" ("bucket_id", "name", "owner", "metadata") VALUES (bucketid, name, owner, metadata);
  -- hack to rollback the successful insert
  RAISE sqlstate 'PT200' using
  message = 'ROLLBACK',
  detail = 'rollback successful insert';
END
$$;


--
-- Name: enforce_bucket_name_length(); Type: FUNCTION; Schema: storage; Owner: -
--

CREATE FUNCTION storage.enforce_bucket_name_length() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    if length(new.name) > 100 then
        raise exception 'bucket name "%" is too long (% characters). Max is 100.', new.name, length(new.name);
    end if;
    return new;
end;
$$;


--
-- Name: extension(text); Type: FUNCTION; Schema: storage; Owner: -
--

CREATE FUNCTION storage.extension(name text) RETURNS text
    LANGUAGE plpgsql IMMUTABLE
    AS $$
DECLARE
    _parts text[];
    _filename text;
BEGIN
    -- Split on "/" to get path segments
    SELECT string_to_array(name, '/') INTO _parts;
    -- Get the last path segment (the actual filename)
    SELECT _parts[array_length(_parts, 1)] INTO _filename;
    -- Extract extension: reverse, split on '.', then reverse again
    RETURN reverse(split_part(reverse(_filename), '.', 1));
END
$$;


--
-- Name: filename(text); Type: FUNCTION; Schema: storage; Owner: -
--

CREATE FUNCTION storage.filename(name text) RETURNS text
    LANGUAGE plpgsql
    AS $$
DECLARE
_parts text[];
BEGIN
	select string_to_array(name, '/') into _parts;
	return _parts[array_length(_parts,1)];
END
$$;


--
-- Name: foldername(text); Type: FUNCTION; Schema: storage; Owner: -
--

CREATE FUNCTION storage.foldername(name text) RETURNS text[]
    LANGUAGE plpgsql IMMUTABLE
    AS $$
DECLARE
    _parts text[];
BEGIN
    -- Split on "/" to get path segments
    SELECT string_to_array(name, '/') INTO _parts;
    -- Return everything except the last segment
    RETURN _parts[1 : array_length(_parts,1) - 1];
END
$$;


--
-- Name: get_common_prefix(text, text, text); Type: FUNCTION; Schema: storage; Owner: -
--

CREATE FUNCTION storage.get_common_prefix(p_key text, p_prefix text, p_delimiter text) RETURNS text
    LANGUAGE sql IMMUTABLE
    AS $$
SELECT CASE
    WHEN position(p_delimiter IN substring(p_key FROM length(p_prefix) + 1)) > 0
    THEN left(p_key, length(p_prefix) + position(p_delimiter IN substring(p_key FROM length(p_prefix) + 1)))
    ELSE NULL
END;
$$;


--
-- Name: get_size_by_bucket(); Type: FUNCTION; Schema: storage; Owner: -
--

CREATE FUNCTION storage.get_size_by_bucket() RETURNS TABLE(size bigint, bucket_id text)
    LANGUAGE plpgsql STABLE
    AS $$
BEGIN
    return query
        select sum((metadata->>'size')::bigint)::bigint as size, obj.bucket_id
        from "storage".objects as obj
        group by obj.bucket_id;
END
$$;


--
-- Name: list_multipart_uploads_with_delimiter(text, text, text, integer, text, text); Type: FUNCTION; Schema: storage; Owner: -
--

CREATE FUNCTION storage.list_multipart_uploads_with_delimiter(bucket_id text, prefix_param text, delimiter_param text, max_keys integer DEFAULT 100, next_key_token text DEFAULT ''::text, next_upload_token text DEFAULT ''::text) RETURNS TABLE(key text, id text, created_at timestamp with time zone)
    LANGUAGE plpgsql
    AS $_$
BEGIN
    RETURN QUERY EXECUTE
        'SELECT DISTINCT ON(key COLLATE "C") * from (
            SELECT
                CASE
                    WHEN position($2 IN substring(key from length($1) + 1)) > 0 THEN
                        substring(key from 1 for length($1) + position($2 IN substring(key from length($1) + 1)))
                    ELSE
                        key
                END AS key, id, created_at
            FROM
                storage.s3_multipart_uploads
            WHERE
                bucket_id = $5 AND
                key ILIKE $1 || ''%'' AND
                CASE
                    WHEN $4 != '''' AND $6 = '''' THEN
                        CASE
                            WHEN position($2 IN substring(key from length($1) + 1)) > 0 THEN
                                substring(key from 1 for length($1) + position($2 IN substring(key from length($1) + 1))) COLLATE "C" > $4
                            ELSE
                                key COLLATE "C" > $4
                            END
                    ELSE
                        true
                END AND
                CASE
                    WHEN $6 != '''' THEN
                        id COLLATE "C" > $6
                    ELSE
                        true
                    END
            ORDER BY
                key COLLATE "C" ASC, created_at ASC) as e order by key COLLATE "C" LIMIT $3'
        USING prefix_param, delimiter_param, max_keys, next_key_token, bucket_id, next_upload_token;
END;
$_$;


--
-- Name: list_objects_with_delimiter(text, text, text, integer, text, text, text); Type: FUNCTION; Schema: storage; Owner: -
--

CREATE FUNCTION storage.list_objects_with_delimiter(_bucket_id text, prefix_param text, delimiter_param text, max_keys integer DEFAULT 100, start_after text DEFAULT ''::text, next_token text DEFAULT ''::text, sort_order text DEFAULT 'asc'::text) RETURNS TABLE(name text, id uuid, metadata jsonb, updated_at timestamp with time zone, created_at timestamp with time zone, last_accessed_at timestamp with time zone)
    LANGUAGE plpgsql STABLE
    AS $_$
DECLARE
    v_peek_name TEXT;
    v_current RECORD;
    v_common_prefix TEXT;

    -- Configuration
    v_is_asc BOOLEAN;
    v_prefix TEXT;
    v_start TEXT;
    v_upper_bound TEXT;
    v_file_batch_size INT;

    -- Seek state
    v_next_seek TEXT;
    v_count INT := 0;

    -- Dynamic SQL for batch query only
    v_batch_query TEXT;

BEGIN
    -- ========================================================================
    -- INITIALIZATION
    -- ========================================================================
    v_is_asc := lower(coalesce(sort_order, 'asc')) = 'asc';
    v_prefix := coalesce(prefix_param, '');
    v_start := CASE WHEN coalesce(next_token, '') <> '' THEN next_token ELSE coalesce(start_after, '') END;
    v_file_batch_size := LEAST(GREATEST(max_keys * 2, 100), 1000);

    -- Calculate upper bound for prefix filtering (bytewise, using COLLATE "C")
    IF v_prefix = '' THEN
        v_upper_bound := NULL;
    ELSIF right(v_prefix, 1) = delimiter_param THEN
        v_upper_bound := left(v_prefix, -1) || chr(ascii(delimiter_param) + 1);
    ELSE
        v_upper_bound := left(v_prefix, -1) || chr(ascii(right(v_prefix, 1)) + 1);
    END IF;

    -- Build batch query (dynamic SQL - called infrequently, amortized over many rows)
    IF v_is_asc THEN
        IF v_upper_bound IS NOT NULL THEN
            v_batch_query := 'SELECT o.name, o.id, o.updated_at, o.created_at, o.last_accessed_at, o.metadata ' ||
                'FROM storage.objects o WHERE o.bucket_id = $1 AND o.name COLLATE "C" >= $2 ' ||
                'AND o.name COLLATE "C" < $3 ORDER BY o.name COLLATE "C" ASC LIMIT $4';
        ELSE
            v_batch_query := 'SELECT o.name, o.id, o.updated_at, o.created_at, o.last_accessed_at, o.metadata ' ||
                'FROM storage.objects o WHERE o.bucket_id = $1 AND o.name COLLATE "C" >= $2 ' ||
                'ORDER BY o.name COLLATE "C" ASC LIMIT $4';
        END IF;
    ELSE
        IF v_upper_bound IS NOT NULL THEN
            v_batch_query := 'SELECT o.name, o.id, o.updated_at, o.created_at, o.last_accessed_at, o.metadata ' ||
                'FROM storage.objects o WHERE o.bucket_id = $1 AND o.name COLLATE "C" < $2 ' ||
                'AND o.name COLLATE "C" >= $3 ORDER BY o.name COLLATE "C" DESC LIMIT $4';
        ELSE
            v_batch_query := 'SELECT o.name, o.id, o.updated_at, o.created_at, o.last_accessed_at, o.metadata ' ||
                'FROM storage.objects o WHERE o.bucket_id = $1 AND o.name COLLATE "C" < $2 ' ||
                'ORDER BY o.name COLLATE "C" DESC LIMIT $4';
        END IF;
    END IF;

    -- ========================================================================
    -- SEEK INITIALIZATION: Determine starting position
    -- ========================================================================
    IF v_start = '' THEN
        IF v_is_asc THEN
            v_next_seek := v_prefix;
        ELSE
            -- DESC without cursor: find the last item in range
            IF v_upper_bound IS NOT NULL THEN
                SELECT o.name INTO v_next_seek FROM storage.objects o
                WHERE o.bucket_id = _bucket_id AND o.name COLLATE "C" >= v_prefix AND o.name COLLATE "C" < v_upper_bound
                ORDER BY o.name COLLATE "C" DESC LIMIT 1;
            ELSIF v_prefix <> '' THEN
                SELECT o.name INTO v_next_seek FROM storage.objects o
                WHERE o.bucket_id = _bucket_id AND o.name COLLATE "C" >= v_prefix
                ORDER BY o.name COLLATE "C" DESC LIMIT 1;
            ELSE
                SELECT o.name INTO v_next_seek FROM storage.objects o
                WHERE o.bucket_id = _bucket_id
                ORDER BY o.name COLLATE "C" DESC LIMIT 1;
            END IF;

            IF v_next_seek IS NOT NULL THEN
                v_next_seek := v_next_seek || delimiter_param;
            ELSE
                RETURN;
            END IF;
        END IF;
    ELSE
        -- Cursor provided: determine if it refers to a folder or leaf
        IF EXISTS (
            SELECT 1 FROM storage.objects o
            WHERE o.bucket_id = _bucket_id
              AND o.name COLLATE "C" LIKE v_start || delimiter_param || '%'
            LIMIT 1
        ) THEN
            -- Cursor refers to a folder
            IF v_is_asc THEN
                v_next_seek := v_start || chr(ascii(delimiter_param) + 1);
            ELSE
                v_next_seek := v_start || delimiter_param;
            END IF;
        ELSE
            -- Cursor refers to a leaf object
            IF v_is_asc THEN
                v_next_seek := v_start || delimiter_param;
            ELSE
                v_next_seek := v_start;
            END IF;
        END IF;
    END IF;

    -- ========================================================================
    -- MAIN LOOP: Hybrid peek-then-batch algorithm
    -- Uses STATIC SQL for peek (hot path) and DYNAMIC SQL for batch
    -- ========================================================================
    LOOP
        EXIT WHEN v_count >= max_keys;

        -- STEP 1: PEEK using STATIC SQL (plan cached, very fast)
        IF v_is_asc THEN
            IF v_upper_bound IS NOT NULL THEN
                SELECT o.name INTO v_peek_name FROM storage.objects o
                WHERE o.bucket_id = _bucket_id AND o.name COLLATE "C" >= v_next_seek AND o.name COLLATE "C" < v_upper_bound
                ORDER BY o.name COLLATE "C" ASC LIMIT 1;
            ELSE
                SELECT o.name INTO v_peek_name FROM storage.objects o
                WHERE o.bucket_id = _bucket_id AND o.name COLLATE "C" >= v_next_seek
                ORDER BY o.name COLLATE "C" ASC LIMIT 1;
            END IF;
        ELSE
            IF v_upper_bound IS NOT NULL THEN
                SELECT o.name INTO v_peek_name FROM storage.objects o
                WHERE o.bucket_id = _bucket_id AND o.name COLLATE "C" < v_next_seek AND o.name COLLATE "C" >= v_prefix
                ORDER BY o.name COLLATE "C" DESC LIMIT 1;
            ELSIF v_prefix <> '' THEN
                SELECT o.name INTO v_peek_name FROM storage.objects o
                WHERE o.bucket_id = _bucket_id AND o.name COLLATE "C" < v_next_seek AND o.name COLLATE "C" >= v_prefix
                ORDER BY o.name COLLATE "C" DESC LIMIT 1;
            ELSE
                SELECT o.name INTO v_peek_name FROM storage.objects o
                WHERE o.bucket_id = _bucket_id AND o.name COLLATE "C" < v_next_seek
                ORDER BY o.name COLLATE "C" DESC LIMIT 1;
            END IF;
        END IF;

        EXIT WHEN v_peek_name IS NULL;

        -- STEP 2: Check if this is a FOLDER or FILE
        v_common_prefix := storage.get_common_prefix(v_peek_name, v_prefix, delimiter_param);

        IF v_common_prefix IS NOT NULL THEN
            -- FOLDER: Emit and skip to next folder (no heap access needed)
            name := rtrim(v_common_prefix, delimiter_param);
            id := NULL;
            updated_at := NULL;
            created_at := NULL;
            last_accessed_at := NULL;
            metadata := NULL;
            RETURN NEXT;
            v_count := v_count + 1;

            -- Advance seek past the folder range
            IF v_is_asc THEN
                v_next_seek := left(v_common_prefix, -1) || chr(ascii(delimiter_param) + 1);
            ELSE
                v_next_seek := v_common_prefix;
            END IF;
        ELSE
            -- FILE: Batch fetch using DYNAMIC SQL (overhead amortized over many rows)
            -- For ASC: upper_bound is the exclusive upper limit (< condition)
            -- For DESC: prefix is the inclusive lower limit (>= condition)
            FOR v_current IN EXECUTE v_batch_query USING _bucket_id, v_next_seek,
                CASE WHEN v_is_asc THEN COALESCE(v_upper_bound, v_prefix) ELSE v_prefix END, v_file_batch_size
            LOOP
                v_common_prefix := storage.get_common_prefix(v_current.name, v_prefix, delimiter_param);

                IF v_common_prefix IS NOT NULL THEN
                    -- Hit a folder: exit batch, let peek handle it
                    v_next_seek := v_current.name;
                    EXIT;
                END IF;

                -- Emit file
                name := v_current.name;
                id := v_current.id;
                updated_at := v_current.updated_at;
                created_at := v_current.created_at;
                last_accessed_at := v_current.last_accessed_at;
                metadata := v_current.metadata;
                RETURN NEXT;
                v_count := v_count + 1;

                -- Advance seek past this file
                IF v_is_asc THEN
                    v_next_seek := v_current.name || delimiter_param;
                ELSE
                    v_next_seek := v_current.name;
                END IF;

                EXIT WHEN v_count >= max_keys;
            END LOOP;
        END IF;
    END LOOP;
END;
$_$;


--
-- Name: operation(); Type: FUNCTION; Schema: storage; Owner: -
--

CREATE FUNCTION storage.operation() RETURNS text
    LANGUAGE plpgsql STABLE
    AS $$
BEGIN
    RETURN current_setting('storage.operation', true);
END;
$$;


--
-- Name: protect_delete(); Type: FUNCTION; Schema: storage; Owner: -
--

CREATE FUNCTION storage.protect_delete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- Check if storage.allow_delete_query is set to 'true'
    IF COALESCE(current_setting('storage.allow_delete_query', true), 'false') != 'true' THEN
        RAISE EXCEPTION 'Direct deletion from storage tables is not allowed. Use the Storage API instead.'
            USING HINT = 'This prevents accidental data loss from orphaned objects.',
                  ERRCODE = '42501';
    END IF;
    RETURN NULL;
END;
$$;


--
-- Name: search(text, text, integer, integer, integer, text, text, text); Type: FUNCTION; Schema: storage; Owner: -
--

CREATE FUNCTION storage.search(prefix text, bucketname text, limits integer DEFAULT 100, levels integer DEFAULT 1, offsets integer DEFAULT 0, search text DEFAULT ''::text, sortcolumn text DEFAULT 'name'::text, sortorder text DEFAULT 'asc'::text) RETURNS TABLE(name text, id uuid, updated_at timestamp with time zone, created_at timestamp with time zone, last_accessed_at timestamp with time zone, metadata jsonb)
    LANGUAGE plpgsql STABLE
    AS $_$
DECLARE
    v_peek_name TEXT;
    v_current RECORD;
    v_common_prefix TEXT;
    v_delimiter CONSTANT TEXT := '/';

    -- Configuration
    v_limit INT;
    v_prefix TEXT;
    v_prefix_lower TEXT;
    v_is_asc BOOLEAN;
    v_order_by TEXT;
    v_sort_order TEXT;
    v_upper_bound TEXT;
    v_file_batch_size INT;

    -- Dynamic SQL for batch query only
    v_batch_query TEXT;

    -- Seek state
    v_next_seek TEXT;
    v_count INT := 0;
    v_skipped INT := 0;
BEGIN
    -- ========================================================================
    -- INITIALIZATION
    -- ========================================================================
    v_limit := LEAST(coalesce(limits, 100), 1500);
    v_prefix := coalesce(prefix, '') || coalesce(search, '');
    v_prefix_lower := lower(v_prefix);
    v_is_asc := lower(coalesce(sortorder, 'asc')) = 'asc';
    v_file_batch_size := LEAST(GREATEST(v_limit * 2, 100), 1000);

    -- Validate sort column
    CASE lower(coalesce(sortcolumn, 'name'))
        WHEN 'name' THEN v_order_by := 'name';
        WHEN 'updated_at' THEN v_order_by := 'updated_at';
        WHEN 'created_at' THEN v_order_by := 'created_at';
        WHEN 'last_accessed_at' THEN v_order_by := 'last_accessed_at';
        ELSE v_order_by := 'name';
    END CASE;

    v_sort_order := CASE WHEN v_is_asc THEN 'asc' ELSE 'desc' END;

    -- ========================================================================
    -- NON-NAME SORTING: Use path_tokens approach (unchanged)
    -- ========================================================================
    IF v_order_by != 'name' THEN
        RETURN QUERY EXECUTE format(
            $sql$
            WITH folders AS (
                SELECT path_tokens[$1] AS folder
                FROM storage.objects
                WHERE objects.name ILIKE $2 || '%%'
                  AND bucket_id = $3
                  AND array_length(objects.path_tokens, 1) <> $1
                GROUP BY folder
                ORDER BY folder %s
            )
            (SELECT folder AS "name",
                   NULL::uuid AS id,
                   NULL::timestamptz AS updated_at,
                   NULL::timestamptz AS created_at,
                   NULL::timestamptz AS last_accessed_at,
                   NULL::jsonb AS metadata FROM folders)
            UNION ALL
            (SELECT path_tokens[$1] AS "name",
                   id, updated_at, created_at, last_accessed_at, metadata
             FROM storage.objects
             WHERE objects.name ILIKE $2 || '%%'
               AND bucket_id = $3
               AND array_length(objects.path_tokens, 1) = $1
             ORDER BY %I %s)
            LIMIT $4 OFFSET $5
            $sql$, v_sort_order, v_order_by, v_sort_order
        ) USING levels, v_prefix, bucketname, v_limit, offsets;
        RETURN;
    END IF;

    -- ========================================================================
    -- NAME SORTING: Hybrid skip-scan with batch optimization
    -- ========================================================================

    -- Calculate upper bound for prefix filtering
    IF v_prefix_lower = '' THEN
        v_upper_bound := NULL;
    ELSIF right(v_prefix_lower, 1) = v_delimiter THEN
        v_upper_bound := left(v_prefix_lower, -1) || chr(ascii(v_delimiter) + 1);
    ELSE
        v_upper_bound := left(v_prefix_lower, -1) || chr(ascii(right(v_prefix_lower, 1)) + 1);
    END IF;

    -- Build batch query (dynamic SQL - called infrequently, amortized over many rows)
    IF v_is_asc THEN
        IF v_upper_bound IS NOT NULL THEN
            v_batch_query := 'SELECT o.name, o.id, o.updated_at, o.created_at, o.last_accessed_at, o.metadata ' ||
                'FROM storage.objects o WHERE o.bucket_id = $1 AND lower(o.name) COLLATE "C" >= $2 ' ||
                'AND lower(o.name) COLLATE "C" < $3 ORDER BY lower(o.name) COLLATE "C" ASC LIMIT $4';
        ELSE
            v_batch_query := 'SELECT o.name, o.id, o.updated_at, o.created_at, o.last_accessed_at, o.metadata ' ||
                'FROM storage.objects o WHERE o.bucket_id = $1 AND lower(o.name) COLLATE "C" >= $2 ' ||
                'ORDER BY lower(o.name) COLLATE "C" ASC LIMIT $4';
        END IF;
    ELSE
        IF v_upper_bound IS NOT NULL THEN
            v_batch_query := 'SELECT o.name, o.id, o.updated_at, o.created_at, o.last_accessed_at, o.metadata ' ||
                'FROM storage.objects o WHERE o.bucket_id = $1 AND lower(o.name) COLLATE "C" < $2 ' ||
                'AND lower(o.name) COLLATE "C" >= $3 ORDER BY lower(o.name) COLLATE "C" DESC LIMIT $4';
        ELSE
            v_batch_query := 'SELECT o.name, o.id, o.updated_at, o.created_at, o.last_accessed_at, o.metadata ' ||
                'FROM storage.objects o WHERE o.bucket_id = $1 AND lower(o.name) COLLATE "C" < $2 ' ||
                'ORDER BY lower(o.name) COLLATE "C" DESC LIMIT $4';
        END IF;
    END IF;

    -- Initialize seek position
    IF v_is_asc THEN
        v_next_seek := v_prefix_lower;
    ELSE
        -- DESC: find the last item in range first (static SQL)
        IF v_upper_bound IS NOT NULL THEN
            SELECT o.name INTO v_peek_name FROM storage.objects o
            WHERE o.bucket_id = bucketname AND lower(o.name) COLLATE "C" >= v_prefix_lower AND lower(o.name) COLLATE "C" < v_upper_bound
            ORDER BY lower(o.name) COLLATE "C" DESC LIMIT 1;
        ELSIF v_prefix_lower <> '' THEN
            SELECT o.name INTO v_peek_name FROM storage.objects o
            WHERE o.bucket_id = bucketname AND lower(o.name) COLLATE "C" >= v_prefix_lower
            ORDER BY lower(o.name) COLLATE "C" DESC LIMIT 1;
        ELSE
            SELECT o.name INTO v_peek_name FROM storage.objects o
            WHERE o.bucket_id = bucketname
            ORDER BY lower(o.name) COLLATE "C" DESC LIMIT 1;
        END IF;

        IF v_peek_name IS NOT NULL THEN
            v_next_seek := lower(v_peek_name) || v_delimiter;
        ELSE
            RETURN;
        END IF;
    END IF;

    -- ========================================================================
    -- MAIN LOOP: Hybrid peek-then-batch algorithm
    -- Uses STATIC SQL for peek (hot path) and DYNAMIC SQL for batch
    -- ========================================================================
    LOOP
        EXIT WHEN v_count >= v_limit;

        -- STEP 1: PEEK using STATIC SQL (plan cached, very fast)
        IF v_is_asc THEN
            IF v_upper_bound IS NOT NULL THEN
                SELECT o.name INTO v_peek_name FROM storage.objects o
                WHERE o.bucket_id = bucketname AND lower(o.name) COLLATE "C" >= v_next_seek AND lower(o.name) COLLATE "C" < v_upper_bound
                ORDER BY lower(o.name) COLLATE "C" ASC LIMIT 1;
            ELSE
                SELECT o.name INTO v_peek_name FROM storage.objects o
                WHERE o.bucket_id = bucketname AND lower(o.name) COLLATE "C" >= v_next_seek
                ORDER BY lower(o.name) COLLATE "C" ASC LIMIT 1;
            END IF;
        ELSE
            IF v_upper_bound IS NOT NULL THEN
                SELECT o.name INTO v_peek_name FROM storage.objects o
                WHERE o.bucket_id = bucketname AND lower(o.name) COLLATE "C" < v_next_seek AND lower(o.name) COLLATE "C" >= v_prefix_lower
                ORDER BY lower(o.name) COLLATE "C" DESC LIMIT 1;
            ELSIF v_prefix_lower <> '' THEN
                SELECT o.name INTO v_peek_name FROM storage.objects o
                WHERE o.bucket_id = bucketname AND lower(o.name) COLLATE "C" < v_next_seek AND lower(o.name) COLLATE "C" >= v_prefix_lower
                ORDER BY lower(o.name) COLLATE "C" DESC LIMIT 1;
            ELSE
                SELECT o.name INTO v_peek_name FROM storage.objects o
                WHERE o.bucket_id = bucketname AND lower(o.name) COLLATE "C" < v_next_seek
                ORDER BY lower(o.name) COLLATE "C" DESC LIMIT 1;
            END IF;
        END IF;

        EXIT WHEN v_peek_name IS NULL;

        -- STEP 2: Check if this is a FOLDER or FILE
        v_common_prefix := storage.get_common_prefix(lower(v_peek_name), v_prefix_lower, v_delimiter);

        IF v_common_prefix IS NOT NULL THEN
            -- FOLDER: Handle offset, emit if needed, skip to next folder
            IF v_skipped < offsets THEN
                v_skipped := v_skipped + 1;
            ELSE
                name := split_part(rtrim(storage.get_common_prefix(v_peek_name, v_prefix, v_delimiter), v_delimiter), v_delimiter, levels);
                id := NULL;
                updated_at := NULL;
                created_at := NULL;
                last_accessed_at := NULL;
                metadata := NULL;
                RETURN NEXT;
                v_count := v_count + 1;
            END IF;

            -- Advance seek past the folder range
            IF v_is_asc THEN
                v_next_seek := lower(left(v_common_prefix, -1)) || chr(ascii(v_delimiter) + 1);
            ELSE
                v_next_seek := lower(v_common_prefix);
            END IF;
        ELSE
            -- FILE: Batch fetch using DYNAMIC SQL (overhead amortized over many rows)
            -- For ASC: upper_bound is the exclusive upper limit (< condition)
            -- For DESC: prefix_lower is the inclusive lower limit (>= condition)
            FOR v_current IN EXECUTE v_batch_query
                USING bucketname, v_next_seek,
                    CASE WHEN v_is_asc THEN COALESCE(v_upper_bound, v_prefix_lower) ELSE v_prefix_lower END, v_file_batch_size
            LOOP
                v_common_prefix := storage.get_common_prefix(lower(v_current.name), v_prefix_lower, v_delimiter);

                IF v_common_prefix IS NOT NULL THEN
                    -- Hit a folder: exit batch, let peek handle it
                    v_next_seek := lower(v_current.name);
                    EXIT;
                END IF;

                -- Handle offset skipping
                IF v_skipped < offsets THEN
                    v_skipped := v_skipped + 1;
                ELSE
                    -- Emit file
                    name := split_part(v_current.name, v_delimiter, levels);
                    id := v_current.id;
                    updated_at := v_current.updated_at;
                    created_at := v_current.created_at;
                    last_accessed_at := v_current.last_accessed_at;
                    metadata := v_current.metadata;
                    RETURN NEXT;
                    v_count := v_count + 1;
                END IF;

                -- Advance seek past this file
                IF v_is_asc THEN
                    v_next_seek := lower(v_current.name) || v_delimiter;
                ELSE
                    v_next_seek := lower(v_current.name);
                END IF;

                EXIT WHEN v_count >= v_limit;
            END LOOP;
        END IF;
    END LOOP;
END;
$_$;


--
-- Name: search_by_timestamp(text, text, integer, integer, text, text, text, text); Type: FUNCTION; Schema: storage; Owner: -
--

CREATE FUNCTION storage.search_by_timestamp(p_prefix text, p_bucket_id text, p_limit integer, p_level integer, p_start_after text, p_sort_order text, p_sort_column text, p_sort_column_after text) RETURNS TABLE(key text, name text, id uuid, updated_at timestamp with time zone, created_at timestamp with time zone, last_accessed_at timestamp with time zone, metadata jsonb)
    LANGUAGE plpgsql STABLE
    AS $_$
DECLARE
    v_cursor_op text;
    v_query text;
    v_prefix text;
BEGIN
    v_prefix := coalesce(p_prefix, '');

    IF p_sort_order = 'asc' THEN
        v_cursor_op := '>';
    ELSE
        v_cursor_op := '<';
    END IF;

    v_query := format($sql$
        WITH raw_objects AS (
            SELECT
                o.name AS obj_name,
                o.id AS obj_id,
                o.updated_at AS obj_updated_at,
                o.created_at AS obj_created_at,
                o.last_accessed_at AS obj_last_accessed_at,
                o.metadata AS obj_metadata,
                storage.get_common_prefix(o.name, $1, '/') AS common_prefix
            FROM storage.objects o
            WHERE o.bucket_id = $2
              AND o.name COLLATE "C" LIKE $1 || '%%'
        ),
        -- Aggregate common prefixes (folders)
        -- Both created_at and updated_at use MIN(obj_created_at) to match the old prefixes table behavior
        aggregated_prefixes AS (
            SELECT
                rtrim(common_prefix, '/') AS name,
                NULL::uuid AS id,
                MIN(obj_created_at) AS updated_at,
                MIN(obj_created_at) AS created_at,
                NULL::timestamptz AS last_accessed_at,
                NULL::jsonb AS metadata,
                TRUE AS is_prefix
            FROM raw_objects
            WHERE common_prefix IS NOT NULL
            GROUP BY common_prefix
        ),
        leaf_objects AS (
            SELECT
                obj_name AS name,
                obj_id AS id,
                obj_updated_at AS updated_at,
                obj_created_at AS created_at,
                obj_last_accessed_at AS last_accessed_at,
                obj_metadata AS metadata,
                FALSE AS is_prefix
            FROM raw_objects
            WHERE common_prefix IS NULL
        ),
        combined AS (
            SELECT * FROM aggregated_prefixes
            UNION ALL
            SELECT * FROM leaf_objects
        ),
        filtered AS (
            SELECT *
            FROM combined
            WHERE (
                $5 = ''
                OR ROW(
                    date_trunc('milliseconds', %I),
                    name COLLATE "C"
                ) %s ROW(
                    COALESCE(NULLIF($6, '')::timestamptz, 'epoch'::timestamptz),
                    $5
                )
            )
        )
        SELECT
            split_part(name, '/', $3) AS key,
            name,
            id,
            updated_at,
            created_at,
            last_accessed_at,
            metadata
        FROM filtered
        ORDER BY
            COALESCE(date_trunc('milliseconds', %I), 'epoch'::timestamptz) %s,
            name COLLATE "C" %s
        LIMIT $4
    $sql$,
        p_sort_column,
        v_cursor_op,
        p_sort_column,
        p_sort_order,
        p_sort_order
    );

    RETURN QUERY EXECUTE v_query
    USING v_prefix, p_bucket_id, p_level, p_limit, p_start_after, p_sort_column_after;
END;
$_$;


--
-- Name: search_v2(text, text, integer, integer, text, text, text, text); Type: FUNCTION; Schema: storage; Owner: -
--

CREATE FUNCTION storage.search_v2(prefix text, bucket_name text, limits integer DEFAULT 100, levels integer DEFAULT 1, start_after text DEFAULT ''::text, sort_order text DEFAULT 'asc'::text, sort_column text DEFAULT 'name'::text, sort_column_after text DEFAULT ''::text) RETURNS TABLE(key text, name text, id uuid, updated_at timestamp with time zone, created_at timestamp with time zone, last_accessed_at timestamp with time zone, metadata jsonb)
    LANGUAGE plpgsql STABLE
    AS $$
DECLARE
    v_sort_col text;
    v_sort_ord text;
    v_limit int;
BEGIN
    -- Cap limit to maximum of 1500 records
    v_limit := LEAST(coalesce(limits, 100), 1500);

    -- Validate and normalize sort_order
    v_sort_ord := lower(coalesce(sort_order, 'asc'));
    IF v_sort_ord NOT IN ('asc', 'desc') THEN
        v_sort_ord := 'asc';
    END IF;

    -- Validate and normalize sort_column
    v_sort_col := lower(coalesce(sort_column, 'name'));
    IF v_sort_col NOT IN ('name', 'updated_at', 'created_at') THEN
        v_sort_col := 'name';
    END IF;

    -- Route to appropriate implementation
    IF v_sort_col = 'name' THEN
        -- Use list_objects_with_delimiter for name sorting (most efficient: O(k * log n))
        RETURN QUERY
        SELECT
            split_part(l.name, '/', levels) AS key,
            l.name AS name,
            l.id,
            l.updated_at,
            l.created_at,
            l.last_accessed_at,
            l.metadata
        FROM storage.list_objects_with_delimiter(
            bucket_name,
            coalesce(prefix, ''),
            '/',
            v_limit,
            start_after,
            '',
            v_sort_ord
        ) l;
    ELSE
        -- Use aggregation approach for timestamp sorting
        -- Not efficient for large datasets but supports correct pagination
        RETURN QUERY SELECT * FROM storage.search_by_timestamp(
            prefix, bucket_name, v_limit, levels, start_after,
            v_sort_ord, v_sort_col, sort_column_after
        );
    END IF;
END;
$$;


--
-- Name: update_updated_at_column(); Type: FUNCTION; Schema: storage; Owner: -
--

CREATE FUNCTION storage.update_updated_at_column() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW; 
END;
$$;


--
-- Name: audit_log_entries; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.audit_log_entries (
    instance_id uuid,
    id uuid NOT NULL,
    payload json,
    created_at timestamp with time zone,
    ip_address character varying(64) DEFAULT ''::character varying NOT NULL
);


--
-- Name: TABLE audit_log_entries; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON TABLE auth.audit_log_entries IS 'Auth: Audit trail for user actions.';


--
-- Name: custom_oauth_providers; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.custom_oauth_providers (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    provider_type text NOT NULL,
    identifier text NOT NULL,
    name text NOT NULL,
    client_id text NOT NULL,
    client_secret text NOT NULL,
    acceptable_client_ids text[] DEFAULT '{}'::text[] NOT NULL,
    scopes text[] DEFAULT '{}'::text[] NOT NULL,
    pkce_enabled boolean DEFAULT true NOT NULL,
    attribute_mapping jsonb DEFAULT '{}'::jsonb NOT NULL,
    authorization_params jsonb DEFAULT '{}'::jsonb NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    email_optional boolean DEFAULT false NOT NULL,
    issuer text,
    discovery_url text,
    skip_nonce_check boolean DEFAULT false NOT NULL,
    cached_discovery jsonb,
    discovery_cached_at timestamp with time zone,
    authorization_url text,
    token_url text,
    userinfo_url text,
    jwks_uri text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    custom_claims_allowlist text[] DEFAULT '{}'::text[] NOT NULL,
    CONSTRAINT custom_oauth_providers_authorization_url_https CHECK (((authorization_url IS NULL) OR (authorization_url ~~ 'https://%'::text))),
    CONSTRAINT custom_oauth_providers_authorization_url_length CHECK (((authorization_url IS NULL) OR (char_length(authorization_url) <= 2048))),
    CONSTRAINT custom_oauth_providers_client_id_length CHECK (((char_length(client_id) >= 1) AND (char_length(client_id) <= 512))),
    CONSTRAINT custom_oauth_providers_discovery_url_length CHECK (((discovery_url IS NULL) OR (char_length(discovery_url) <= 2048))),
    CONSTRAINT custom_oauth_providers_identifier_format CHECK ((identifier ~ '^[a-z0-9][a-z0-9:-]{0,48}[a-z0-9]$'::text)),
    CONSTRAINT custom_oauth_providers_issuer_length CHECK (((issuer IS NULL) OR ((char_length(issuer) >= 1) AND (char_length(issuer) <= 2048)))),
    CONSTRAINT custom_oauth_providers_jwks_uri_https CHECK (((jwks_uri IS NULL) OR (jwks_uri ~~ 'https://%'::text))),
    CONSTRAINT custom_oauth_providers_jwks_uri_length CHECK (((jwks_uri IS NULL) OR (char_length(jwks_uri) <= 2048))),
    CONSTRAINT custom_oauth_providers_name_length CHECK (((char_length(name) >= 1) AND (char_length(name) <= 100))),
    CONSTRAINT custom_oauth_providers_oauth2_requires_endpoints CHECK (((provider_type <> 'oauth2'::text) OR ((authorization_url IS NOT NULL) AND (token_url IS NOT NULL) AND (userinfo_url IS NOT NULL)))),
    CONSTRAINT custom_oauth_providers_oidc_discovery_url_https CHECK (((provider_type <> 'oidc'::text) OR (discovery_url IS NULL) OR (discovery_url ~~ 'https://%'::text))),
    CONSTRAINT custom_oauth_providers_oidc_issuer_https CHECK (((provider_type <> 'oidc'::text) OR (issuer IS NULL) OR (issuer ~~ 'https://%'::text))),
    CONSTRAINT custom_oauth_providers_oidc_requires_issuer CHECK (((provider_type <> 'oidc'::text) OR (issuer IS NOT NULL))),
    CONSTRAINT custom_oauth_providers_provider_type_check CHECK ((provider_type = ANY (ARRAY['oauth2'::text, 'oidc'::text]))),
    CONSTRAINT custom_oauth_providers_token_url_https CHECK (((token_url IS NULL) OR (token_url ~~ 'https://%'::text))),
    CONSTRAINT custom_oauth_providers_token_url_length CHECK (((token_url IS NULL) OR (char_length(token_url) <= 2048))),
    CONSTRAINT custom_oauth_providers_userinfo_url_https CHECK (((userinfo_url IS NULL) OR (userinfo_url ~~ 'https://%'::text))),
    CONSTRAINT custom_oauth_providers_userinfo_url_length CHECK (((userinfo_url IS NULL) OR (char_length(userinfo_url) <= 2048)))
);


--
-- Name: flow_state; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.flow_state (
    id uuid NOT NULL,
    user_id uuid,
    auth_code text,
    code_challenge_method auth.code_challenge_method,
    code_challenge text,
    provider_type text NOT NULL,
    provider_access_token text,
    provider_refresh_token text,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    authentication_method text NOT NULL,
    auth_code_issued_at timestamp with time zone,
    invite_token text,
    referrer text,
    oauth_client_state_id uuid,
    linking_target_id uuid,
    email_optional boolean DEFAULT false NOT NULL
);


--
-- Name: TABLE flow_state; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON TABLE auth.flow_state IS 'Stores metadata for all OAuth/SSO login flows';


--
-- Name: identities; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.identities (
    provider_id text NOT NULL,
    user_id uuid NOT NULL,
    identity_data jsonb NOT NULL,
    provider text NOT NULL,
    last_sign_in_at timestamp with time zone,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    email text GENERATED ALWAYS AS (lower((identity_data ->> 'email'::text))) STORED,
    id uuid DEFAULT gen_random_uuid() NOT NULL
);


--
-- Name: TABLE identities; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON TABLE auth.identities IS 'Auth: Stores identities associated to a user.';


--
-- Name: COLUMN identities.email; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON COLUMN auth.identities.email IS 'Auth: Email is a generated column that references the optional email property in the identity_data';


--
-- Name: instances; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.instances (
    id uuid NOT NULL,
    uuid uuid,
    raw_base_config text,
    created_at timestamp with time zone,
    updated_at timestamp with time zone
);


--
-- Name: TABLE instances; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON TABLE auth.instances IS 'Auth: Manages users across multiple sites.';


--
-- Name: mfa_amr_claims; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.mfa_amr_claims (
    session_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    authentication_method text NOT NULL,
    id uuid NOT NULL
);


--
-- Name: TABLE mfa_amr_claims; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON TABLE auth.mfa_amr_claims IS 'auth: stores authenticator method reference claims for multi factor authentication';


--
-- Name: mfa_challenges; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.mfa_challenges (
    id uuid NOT NULL,
    factor_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    verified_at timestamp with time zone,
    ip_address inet NOT NULL,
    otp_code text,
    web_authn_session_data jsonb
);


--
-- Name: TABLE mfa_challenges; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON TABLE auth.mfa_challenges IS 'auth: stores metadata about challenge requests made';


--
-- Name: mfa_factors; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.mfa_factors (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    friendly_name text,
    factor_type auth.factor_type NOT NULL,
    status auth.factor_status NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    secret text,
    phone text,
    last_challenged_at timestamp with time zone,
    web_authn_credential jsonb,
    web_authn_aaguid uuid,
    last_webauthn_challenge_data jsonb
);


--
-- Name: TABLE mfa_factors; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON TABLE auth.mfa_factors IS 'auth: stores metadata about factors';


--
-- Name: COLUMN mfa_factors.last_webauthn_challenge_data; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON COLUMN auth.mfa_factors.last_webauthn_challenge_data IS 'Stores the latest WebAuthn challenge data including attestation/assertion for customer verification';


--
-- Name: oauth_authorizations; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.oauth_authorizations (
    id uuid NOT NULL,
    authorization_id text NOT NULL,
    client_id uuid NOT NULL,
    user_id uuid,
    redirect_uri text NOT NULL,
    scope text NOT NULL,
    state text,
    resource text,
    code_challenge text,
    code_challenge_method auth.code_challenge_method,
    response_type auth.oauth_response_type DEFAULT 'code'::auth.oauth_response_type NOT NULL,
    status auth.oauth_authorization_status DEFAULT 'pending'::auth.oauth_authorization_status NOT NULL,
    authorization_code text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone DEFAULT (now() + '00:03:00'::interval) NOT NULL,
    approved_at timestamp with time zone,
    nonce text,
    CONSTRAINT oauth_authorizations_authorization_code_length CHECK ((char_length(authorization_code) <= 255)),
    CONSTRAINT oauth_authorizations_code_challenge_length CHECK ((char_length(code_challenge) <= 128)),
    CONSTRAINT oauth_authorizations_expires_at_future CHECK ((expires_at > created_at)),
    CONSTRAINT oauth_authorizations_nonce_length CHECK ((char_length(nonce) <= 255)),
    CONSTRAINT oauth_authorizations_redirect_uri_length CHECK ((char_length(redirect_uri) <= 2048)),
    CONSTRAINT oauth_authorizations_resource_length CHECK ((char_length(resource) <= 2048)),
    CONSTRAINT oauth_authorizations_scope_length CHECK ((char_length(scope) <= 4096)),
    CONSTRAINT oauth_authorizations_state_length CHECK ((char_length(state) <= 4096))
);


--
-- Name: oauth_client_states; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.oauth_client_states (
    id uuid NOT NULL,
    provider_type text NOT NULL,
    code_verifier text,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: TABLE oauth_client_states; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON TABLE auth.oauth_client_states IS 'Stores OAuth states for third-party provider authentication flows where Supabase acts as the OAuth client.';


--
-- Name: oauth_clients; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.oauth_clients (
    id uuid NOT NULL,
    client_secret_hash text,
    registration_type auth.oauth_registration_type NOT NULL,
    redirect_uris text NOT NULL,
    grant_types text NOT NULL,
    client_name text,
    client_uri text,
    logo_uri text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    client_type auth.oauth_client_type DEFAULT 'confidential'::auth.oauth_client_type NOT NULL,
    token_endpoint_auth_method text NOT NULL,
    CONSTRAINT oauth_clients_client_name_length CHECK ((char_length(client_name) <= 1024)),
    CONSTRAINT oauth_clients_client_uri_length CHECK ((char_length(client_uri) <= 2048)),
    CONSTRAINT oauth_clients_logo_uri_length CHECK ((char_length(logo_uri) <= 2048)),
    CONSTRAINT oauth_clients_token_endpoint_auth_method_check CHECK ((token_endpoint_auth_method = ANY (ARRAY['client_secret_basic'::text, 'client_secret_post'::text, 'none'::text])))
);


--
-- Name: oauth_consents; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.oauth_consents (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    client_id uuid NOT NULL,
    scopes text NOT NULL,
    granted_at timestamp with time zone DEFAULT now() NOT NULL,
    revoked_at timestamp with time zone,
    CONSTRAINT oauth_consents_revoked_after_granted CHECK (((revoked_at IS NULL) OR (revoked_at >= granted_at))),
    CONSTRAINT oauth_consents_scopes_length CHECK ((char_length(scopes) <= 2048)),
    CONSTRAINT oauth_consents_scopes_not_empty CHECK ((char_length(TRIM(BOTH FROM scopes)) > 0))
);


--
-- Name: one_time_tokens; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.one_time_tokens (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    token_type auth.one_time_token_type NOT NULL,
    token_hash text NOT NULL,
    relates_to text NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT one_time_tokens_token_hash_check CHECK ((char_length(token_hash) > 0))
);


--
-- Name: refresh_tokens; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.refresh_tokens (
    instance_id uuid,
    id bigint NOT NULL,
    token character varying(255),
    user_id character varying(255),
    revoked boolean,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    parent character varying(255),
    session_id uuid
);


--
-- Name: TABLE refresh_tokens; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON TABLE auth.refresh_tokens IS 'Auth: Store of tokens used to refresh JWT tokens once they expire.';


--
-- Name: refresh_tokens_id_seq; Type: SEQUENCE; Schema: auth; Owner: -
--

CREATE SEQUENCE auth.refresh_tokens_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: refresh_tokens_id_seq; Type: SEQUENCE OWNED BY; Schema: auth; Owner: -
--

ALTER SEQUENCE auth.refresh_tokens_id_seq OWNED BY auth.refresh_tokens.id;


--
-- Name: saml_providers; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.saml_providers (
    id uuid NOT NULL,
    sso_provider_id uuid NOT NULL,
    entity_id text NOT NULL,
    metadata_xml text NOT NULL,
    metadata_url text,
    attribute_mapping jsonb,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    name_id_format text,
    CONSTRAINT "entity_id not empty" CHECK ((char_length(entity_id) > 0)),
    CONSTRAINT "metadata_url not empty" CHECK (((metadata_url = NULL::text) OR (char_length(metadata_url) > 0))),
    CONSTRAINT "metadata_xml not empty" CHECK ((char_length(metadata_xml) > 0))
);


--
-- Name: TABLE saml_providers; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON TABLE auth.saml_providers IS 'Auth: Manages SAML Identity Provider connections.';


--
-- Name: saml_relay_states; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.saml_relay_states (
    id uuid NOT NULL,
    sso_provider_id uuid NOT NULL,
    request_id text NOT NULL,
    for_email text,
    redirect_to text,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    flow_state_id uuid,
    CONSTRAINT "request_id not empty" CHECK ((char_length(request_id) > 0))
);


--
-- Name: TABLE saml_relay_states; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON TABLE auth.saml_relay_states IS 'Auth: Contains SAML Relay State information for each Service Provider initiated login.';


--
-- Name: schema_migrations; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.schema_migrations (
    version character varying(255) NOT NULL
);


--
-- Name: TABLE schema_migrations; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON TABLE auth.schema_migrations IS 'Auth: Manages updates to the auth system.';


--
-- Name: sessions; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.sessions (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    factor_id uuid,
    aal auth.aal_level,
    not_after timestamp with time zone,
    refreshed_at timestamp without time zone,
    user_agent text,
    ip inet,
    tag text,
    oauth_client_id uuid,
    refresh_token_hmac_key text,
    refresh_token_counter bigint,
    scopes text,
    CONSTRAINT sessions_scopes_length CHECK ((char_length(scopes) <= 4096))
);


--
-- Name: TABLE sessions; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON TABLE auth.sessions IS 'Auth: Stores session data associated to a user.';


--
-- Name: COLUMN sessions.not_after; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON COLUMN auth.sessions.not_after IS 'Auth: Not after is a nullable column that contains a timestamp after which the session should be regarded as expired.';


--
-- Name: COLUMN sessions.refresh_token_hmac_key; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON COLUMN auth.sessions.refresh_token_hmac_key IS 'Holds a HMAC-SHA256 key used to sign refresh tokens for this session.';


--
-- Name: COLUMN sessions.refresh_token_counter; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON COLUMN auth.sessions.refresh_token_counter IS 'Holds the ID (counter) of the last issued refresh token.';


--
-- Name: sso_domains; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.sso_domains (
    id uuid NOT NULL,
    sso_provider_id uuid NOT NULL,
    domain text NOT NULL,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    CONSTRAINT "domain not empty" CHECK ((char_length(domain) > 0))
);


--
-- Name: TABLE sso_domains; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON TABLE auth.sso_domains IS 'Auth: Manages SSO email address domain mapping to an SSO Identity Provider.';


--
-- Name: sso_providers; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.sso_providers (
    id uuid NOT NULL,
    resource_id text,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    disabled boolean,
    CONSTRAINT "resource_id not empty" CHECK (((resource_id = NULL::text) OR (char_length(resource_id) > 0)))
);


--
-- Name: TABLE sso_providers; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON TABLE auth.sso_providers IS 'Auth: Manages SSO identity provider information; see saml_providers for SAML.';


--
-- Name: COLUMN sso_providers.resource_id; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON COLUMN auth.sso_providers.resource_id IS 'Auth: Uniquely identifies a SSO provider according to a user-chosen resource ID (case insensitive), useful in infrastructure as code.';


--
-- Name: users; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.users (
    instance_id uuid,
    id uuid NOT NULL,
    aud character varying(255),
    role character varying(255),
    email character varying(255),
    encrypted_password character varying(255),
    email_confirmed_at timestamp with time zone,
    invited_at timestamp with time zone,
    confirmation_token character varying(255),
    confirmation_sent_at timestamp with time zone,
    recovery_token character varying(255),
    recovery_sent_at timestamp with time zone,
    email_change_token_new character varying(255),
    email_change character varying(255),
    email_change_sent_at timestamp with time zone,
    last_sign_in_at timestamp with time zone,
    raw_app_meta_data jsonb,
    raw_user_meta_data jsonb,
    is_super_admin boolean,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    phone text DEFAULT NULL::character varying,
    phone_confirmed_at timestamp with time zone,
    phone_change text DEFAULT ''::character varying,
    phone_change_token character varying(255) DEFAULT ''::character varying,
    phone_change_sent_at timestamp with time zone,
    confirmed_at timestamp with time zone GENERATED ALWAYS AS (LEAST(email_confirmed_at, phone_confirmed_at)) STORED,
    email_change_token_current character varying(255) DEFAULT ''::character varying,
    email_change_confirm_status smallint DEFAULT 0,
    banned_until timestamp with time zone,
    reauthentication_token character varying(255) DEFAULT ''::character varying,
    reauthentication_sent_at timestamp with time zone,
    is_sso_user boolean DEFAULT false NOT NULL,
    deleted_at timestamp with time zone,
    is_anonymous boolean DEFAULT false NOT NULL,
    CONSTRAINT users_email_change_confirm_status_check CHECK (((email_change_confirm_status >= 0) AND (email_change_confirm_status <= 2)))
);


--
-- Name: TABLE users; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON TABLE auth.users IS 'Auth: Stores user login data within a secure schema.';


--
-- Name: COLUMN users.is_sso_user; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON COLUMN auth.users.is_sso_user IS 'Auth: Set this column to true when the account comes from SSO. These accounts can have duplicate emails.';


--
-- Name: webauthn_challenges; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.webauthn_challenges (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid,
    challenge_type text NOT NULL,
    session_data jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    CONSTRAINT webauthn_challenges_challenge_type_check CHECK ((challenge_type = ANY (ARRAY['signup'::text, 'registration'::text, 'authentication'::text])))
);


--
-- Name: webauthn_credentials; Type: TABLE; Schema: auth; Owner: -
--

CREATE TABLE auth.webauthn_credentials (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    credential_id bytea NOT NULL,
    public_key bytea NOT NULL,
    attestation_type text DEFAULT ''::text NOT NULL,
    aaguid uuid,
    sign_count bigint DEFAULT 0 NOT NULL,
    transports jsonb DEFAULT '[]'::jsonb NOT NULL,
    backup_eligible boolean DEFAULT false NOT NULL,
    backed_up boolean DEFAULT false NOT NULL,
    friendly_name text DEFAULT ''::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    last_used_at timestamp with time zone
);


--
-- Name: active_boosts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.active_boosts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    transaction_id uuid,
    started_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    consumption_ledger_entry_id uuid,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    ended_at timestamp with time zone,
    end_reason character varying(30),
    CONSTRAINT active_boosts_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'EXPIRED'::character varying, 'CANCELLED'::character varying, 'REVOKED'::character varying])::text[]))),
    CONSTRAINT check_boost_period CHECK ((expires_at > started_at))
);


--
-- Name: addresses; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.addresses (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    location_place_id uuid,
    country_code character varying(2) NOT NULL,
    country_name character varying(100) NOT NULL,
    city character varying(100) NOT NULL,
    region character varying(100),
    coords public.geography(Point,4326) NOT NULL,
    formatted_address text,
    location_source character varying(50) DEFAULT 'GPS'::character varying NOT NULL,
    location_precision character varying(20) DEFAULT 'EXACT'::character varying NOT NULL,
    accuracy_m numeric(10,2),
    location_updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT addresses_accuracy_m_check CHECK (((accuracy_m IS NULL) OR (accuracy_m >= (0)::numeric))),
    CONSTRAINT addresses_city_check CHECK (((char_length(btrim((city)::text)) >= 1) AND (char_length(btrim((city)::text)) <= 100))),
    CONSTRAINT addresses_country_name_check CHECK (((char_length(btrim((country_name)::text)) >= 1) AND (char_length(btrim((country_name)::text)) <= 100))),
    CONSTRAINT addresses_location_precision_check CHECK (((location_precision)::text = ANY ((ARRAY['EXACT'::character varying, 'CITY'::character varying, 'REGION'::character varying, 'COUNTRY'::character varying, 'APPROXIMATE'::character varying])::text[]))),
    CONSTRAINT addresses_location_source_check CHECK (((location_source)::text = ANY ((ARRAY['GPS'::character varying, 'MANUAL'::character varying, 'IP'::character varying])::text[]))),
    CONSTRAINT check_manual_location_has_place CHECK ((((location_source)::text <> 'MANUAL'::text) OR (location_place_id IS NOT NULL))),
    CONSTRAINT check_manual_location_not_exact CHECK ((((location_source)::text <> 'MANUAL'::text) OR ((location_precision)::text = ANY ((ARRAY['CITY'::character varying, 'REGION'::character varying, 'COUNTRY'::character varying])::text[])))),
    CONSTRAINT check_non_gps_has_no_accuracy CHECK ((((location_source)::text = 'GPS'::text) OR (accuracy_m IS NULL)))
);


--
-- Name: app_users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_users (
    id uuid NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    role character varying(20) DEFAULT 'USER'::character varying NOT NULL,
    preferred_language character varying(10) DEFAULT 'en'::character varying NOT NULL,
    last_active_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    address_id uuid,
    show_activity_status boolean DEFAULT true NOT NULL,
    billing_country_code character varying(10),
    CONSTRAINT app_users_preferred_language_check CHECK (((preferred_language)::text = ANY ((ARRAY['en'::character varying, 'am'::character varying, 'ti'::character varying, 'om'::character varying])::text[]))),
    CONSTRAINT app_users_role_check CHECK (((role)::text = ANY ((ARRAY['USER'::character varying, 'MODERATOR'::character varying, 'ADMIN'::character varying])::text[]))),
    CONSTRAINT app_users_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUSPENDED'::character varying, 'DEACTIVATED'::character varying, 'BANNED'::character varying, 'DELETED'::character varying])::text[])))
);


--
-- Name: COLUMN app_users.show_activity_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.app_users.show_activity_status IS 'Whether other authorized users may see this user''s derived activity status.';


--
-- Name: audit_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    actor_user_id uuid,
    action character varying(100) NOT NULL,
    target_table character varying(100) NOT NULL,
    target_id uuid,
    request_id uuid,
    details jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT audit_log_details_check CHECK ((jsonb_typeof(details) = 'object'::text))
);


--
-- Name: auth_anonymization_tasks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.auth_anonymization_tasks (
    user_id uuid NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    last_error text,
    next_retry_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT auth_anonymization_tasks_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'COMPLETED'::character varying, 'FAILED_PERMANENT'::character varying])::text[])))
);


--
-- Name: billing_customers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.billing_customers (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    provider character varying(50) NOT NULL,
    external_customer_id character varying(255) NOT NULL,
    original_external_customer_id character varying(255),
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: chat_attachments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_attachments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    message_id uuid NOT NULL,
    attachment_type text NOT NULL,
    file_name text NOT NULL,
    content_type text NOT NULL,
    file_size_bytes bigint NOT NULL,
    storage_bucket text NOT NULL,
    storage_path text NOT NULL,
    duration_ms bigint,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chat_attachments_attachment_type_check CHECK ((attachment_type = ANY (ARRAY['IMAGE'::text, 'VOICE'::text]))),
    CONSTRAINT chat_attachments_duration_ms_check CHECK (((duration_ms IS NULL) OR (duration_ms > 0))),
    CONSTRAINT chat_attachments_file_size_bytes_check CHECK ((file_size_bytes > 0)),
    CONSTRAINT check_chat_attachment_duration CHECK ((((attachment_type = 'IMAGE'::text) AND (duration_ms IS NULL)) OR ((attachment_type = 'VOICE'::text) AND (duration_ms IS NOT NULL) AND (duration_ms > 0))))
);


--
-- Name: chat_outbox_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_outbox_events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    event_type character varying(100) NOT NULL,
    match_id uuid,
    recipient_user_id uuid,
    topic text NOT NULL,
    payload jsonb NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    available_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    occurred_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    locked_at timestamp with time zone,
    locked_by character varying(100),
    lease_expires_at timestamp with time zone,
    last_attempt_at timestamp with time zone,
    published_at timestamp with time zone,
    last_error text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chat_outbox_events_attempt_count_check CHECK ((attempt_count >= 0)),
    CONSTRAINT chat_outbox_events_event_type_check CHECK (((event_type)::text = ANY ((ARRAY['chat.message.created'::character varying, 'chat.receipt.updated'::character varying, 'chat.match.ended'::character varying, 'inbox.match.updated'::character varying, 'inbox.match.removed'::character varying])::text[]))),
    CONSTRAINT chat_outbox_events_payload_check CHECK ((jsonb_typeof(payload) = 'object'::text)),
    CONSTRAINT chat_outbox_events_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying, 'PUBLISHED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT chat_outbox_events_topic_check CHECK (((char_length(btrim(topic)) >= 1) AND (char_length(btrim(topic)) <= 500))),
    CONSTRAINT check_chat_outbox_processing_lease CHECK ((((status)::text <> 'PROCESSING'::text) OR ((locked_at IS NOT NULL) AND (locked_by IS NOT NULL) AND (lease_expires_at IS NOT NULL)))),
    CONSTRAINT check_chat_outbox_recipient_shape CHECK (((((event_type)::text = ANY ((ARRAY['inbox.match.updated'::character varying, 'inbox.match.removed'::character varying])::text[])) AND (recipient_user_id IS NOT NULL)) OR (((event_type)::text = ANY ((ARRAY['chat.message.created'::character varying, 'chat.receipt.updated'::character varying, 'chat.match.ended'::character varying])::text[])) AND (recipient_user_id IS NULL))))
);


--
-- Name: consumable_products; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.consumable_products (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    product_code character varying(100) NOT NULL,
    name character varying(100) NOT NULL,
    entitlement_type character varying(30) NOT NULL,
    quantity_granted integer NOT NULL,
    expires_after_days integer,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT consumable_products_entitlement_type_check CHECK (((entitlement_type)::text = ANY ((ARRAY['BOOST_CREDIT'::character varying, 'SUPERLIKE_CREDIT'::character varying, 'REWIND_CREDIT'::character varying])::text[]))),
    CONSTRAINT consumable_products_expires_after_days_check CHECK (((expires_after_days IS NULL) OR (expires_after_days > 0))),
    CONSTRAINT consumable_products_quantity_granted_check CHECK ((quantity_granted > 0))
);


--
-- Name: discovery_preferences; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.discovery_preferences (
    user_id uuid NOT NULL,
    preferred_residency_types text[] DEFAULT ARRAY['ETHIOPIA'::text, 'ERITREA'::text, 'DIASPORA'::text] NOT NULL,
    interested_in_gender character varying(20) NOT NULL,
    min_age integer DEFAULT 18 NOT NULL,
    max_age integer DEFAULT 99,
    max_distance_km integer DEFAULT 50,
    show_verified_only boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    location_mode text DEFAULT 'anywhere'::text NOT NULL,
    specific_country_codes text[],
    expand_search_when_limited boolean DEFAULT false NOT NULL,
    has_children_preference text DEFAULT 'any'::text NOT NULL,
    wants_children_preference text DEFAULT 'any'::text NOT NULL,
    religion_preferences text[],
    language_preference_ids uuid[] DEFAULT '{}'::uuid[] NOT NULL,
    ethnicity_preference_ids uuid[] DEFAULT '{}'::uuid[] NOT NULL,
    preferences_version integer DEFAULT 1 NOT NULL,
    CONSTRAINT check_discovery_age_range CHECK (((max_age IS NULL) OR (min_age <= max_age))),
    CONSTRAINT discovery_preferences_has_children_preference_check CHECK ((has_children_preference = ANY (ARRAY['any'::text, 'yes'::text, 'no'::text]))),
    CONSTRAINT discovery_preferences_interested_in_gender_check CHECK (((interested_in_gender)::text = ANY ((ARRAY['MALE'::character varying, 'FEMALE'::character varying])::text[]))),
    CONSTRAINT discovery_preferences_location_mode_check CHECK ((location_mode = ANY (ARRAY['nearby'::text, 'diaspora'::text, 'specific_countries'::text, 'anywhere'::text]))),
    CONSTRAINT discovery_preferences_max_age_check CHECK (((max_age IS NULL) OR (max_age <= 120))),
    CONSTRAINT discovery_preferences_max_distance_km_check CHECK (((max_distance_km IS NULL) OR (max_distance_km > 0))),
    CONSTRAINT discovery_preferences_min_age_check CHECK ((min_age >= 18)),
    CONSTRAINT discovery_preferences_preferred_residency_types_check CHECK ((((cardinality(preferred_residency_types) >= 1) AND (cardinality(preferred_residency_types) <= 3)) AND (preferred_residency_types <@ ARRAY['ETHIOPIA'::text, 'ERITREA'::text, 'DIASPORA'::text]) AND (array_position(preferred_residency_types, NULL::text) IS NULL))),
    CONSTRAINT discovery_preferences_wants_children_preference_check CHECK ((wants_children_preference = ANY (ARRAY['any'::text, 'yes'::text, 'no'::text, 'not_sure'::text, 'open_to_discussion'::text])))
);


--
-- Name: ethnicities; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ethnicities (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code text NOT NULL,
    country_code character(2) NOT NULL,
    name text NOT NULL,
    region text,
    is_active boolean DEFAULT true NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ethnicities_code_lowercase_check CHECK ((code = lower(code))),
    CONSTRAINT ethnicities_country_code_uppercase_check CHECK (((country_code)::text = upper((country_code)::text)))
);


--
-- Name: image_moderation_results; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.image_moderation_results (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    image_id uuid NOT NULL,
    profile_id uuid NOT NULL,
    provider character varying(50) DEFAULT 'REKOGNITION'::character varying NOT NULL,
    status character varying(30) DEFAULT 'PENDING'::character varying NOT NULL,
    face_detection_enabled boolean DEFAULT false NOT NULL,
    nudity_moderation_enabled boolean DEFAULT false NOT NULL,
    face_count integer,
    selected_face_confidence double precision,
    brightness double precision,
    sharpness double precision,
    face_area_percentage double precision,
    face_occluded boolean,
    nudity_detected boolean,
    sexual_content_detected boolean,
    moderation_labels jsonb,
    decision_reasons text[] DEFAULT '{}'::text[] NOT NULL,
    manual_review_reason text,
    provider_request_id character varying(255),
    image_storage_path character varying(500) NOT NULL,
    image_hash character varying(64),
    config_version character varying(255),
    attempt_count integer DEFAULT 0 NOT NULL,
    retry_after timestamp with time zone,
    last_error_code character varying(100),
    last_error_message text,
    processed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: languages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.languages (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code text NOT NULL,
    country_code character(2) NOT NULL,
    name text NOT NULL,
    native_name text,
    is_active boolean DEFAULT true NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT languages_code_lowercase_check CHECK ((code = lower(code))),
    CONSTRAINT languages_country_code_uppercase_check CHECK (((country_code)::text = upper((country_code)::text)))
);


--
-- Name: location_places; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.location_places (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    country_code character varying(2) NOT NULL,
    country_name character varying(100) NOT NULL,
    region character varying(100),
    city character varying(100) NOT NULL,
    display_name text NOT NULL,
    alternative_names text,
    coords public.geography(Point,4326) NOT NULL,
    location_precision character varying(20) DEFAULT 'CITY'::character varying NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT location_places_city_check CHECK (((char_length(btrim((city)::text)) >= 1) AND (char_length(btrim((city)::text)) <= 100))),
    CONSTRAINT location_places_country_name_check CHECK (((char_length(btrim((country_name)::text)) >= 1) AND (char_length(btrim((country_name)::text)) <= 100))),
    CONSTRAINT location_places_display_name_check CHECK (((char_length(btrim(display_name)) >= 1) AND (char_length(btrim(display_name)) <= 300))),
    CONSTRAINT location_places_location_precision_check CHECK (((location_precision)::text = ANY ((ARRAY['CITY'::character varying, 'REGION'::character varying, 'COUNTRY'::character varying])::text[])))
);


--
-- Name: match_notification_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.match_notification_settings (
    match_id uuid NOT NULL,
    user_id uuid NOT NULL,
    muted_until timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: matches; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.matches (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_one_id uuid NOT NULL,
    user_two_id uuid NOT NULL,
    user_one_like_action_id uuid NOT NULL,
    user_two_like_action_id uuid NOT NULL,
    created_by_action_id uuid NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    end_reason character varying(30),
    ended_by_user_id uuid,
    matched_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    ended_at timestamp with time zone,
    rewind_eligible_until timestamp with time zone,
    first_message_at timestamp with time zone,
    last_message_at timestamp with time zone,
    user_one_last_read_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    user_two_last_read_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    next_message_sequence bigint DEFAULT 1 NOT NULL,
    user_one_last_delivered_sequence bigint DEFAULT 0 NOT NULL,
    user_two_last_delivered_sequence bigint DEFAULT 0 NOT NULL,
    user_one_last_read_sequence bigint DEFAULT 0 NOT NULL,
    user_two_last_read_sequence bigint DEFAULT 0 NOT NULL,
    user_one_last_delivered_at timestamp with time zone,
    user_two_last_delivered_at timestamp with time zone,
    user_one_cleared_sequence bigint DEFAULT 0 NOT NULL,
    user_two_cleared_sequence bigint DEFAULT 0 NOT NULL,
    CONSTRAINT check_distinct_match_users CHECK ((user_one_id <> user_two_id)),
    CONSTRAINT check_match_status_end_state CHECK (((((status)::text = 'ACTIVE'::text) AND (ended_at IS NULL) AND (end_reason IS NULL) AND (ended_by_user_id IS NULL)) OR (((status)::text = 'ENDED'::text) AND (ended_at IS NOT NULL) AND (end_reason IS NOT NULL)))),
    CONSTRAINT check_match_user_order CHECK ((user_one_id < user_two_id)),
    CONSTRAINT check_matches_receipt_sequence_state CHECK (((next_message_sequence >= 1) AND (user_one_last_delivered_sequence >= 0) AND (user_two_last_delivered_sequence >= 0) AND (user_one_last_read_sequence >= 0) AND (user_two_last_read_sequence >= 0) AND (user_one_last_read_sequence <= user_one_last_delivered_sequence) AND (user_two_last_read_sequence <= user_two_last_delivered_sequence) AND (user_one_last_delivered_sequence < next_message_sequence) AND (user_two_last_delivered_sequence < next_message_sequence))),
    CONSTRAINT matches_end_reason_check CHECK (((end_reason)::text = ANY ((ARRAY['USER_UNMATCH'::character varying, 'CANCELLED_BY_REWIND'::character varying, 'BLOCKED'::character varying, 'ADMIN_ACTION'::character varying, 'ACCOUNT_DELETED'::character varying])::text[]))),
    CONSTRAINT matches_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'ENDED'::character varying])::text[])))
);


--
-- Name: messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.messages (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    match_id uuid NOT NULL,
    sender_user_id uuid NOT NULL,
    client_message_id uuid NOT NULL,
    message_type character varying(20) DEFAULT 'TEXT'::character varying NOT NULL,
    body text,
    storage_bucket character varying(100),
    storage_path text,
    moderation_status character varying(20) DEFAULT 'APPROVED'::character varying NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    edited_at timestamp with time zone,
    deleted_at timestamp with time zone,
    deleted_by_user_id uuid,
    sequence_number bigint NOT NULL,
    CONSTRAINT check_message_content_by_type CHECK (((body IS NULL) OR ((NULLIF(btrim(body), ''::text) IS NOT NULL) AND (char_length(body) <= 10000)))),
    CONSTRAINT check_message_storage_bucket_and_path_together CHECK ((((storage_bucket IS NULL) AND (storage_path IS NULL)) OR ((storage_bucket IS NOT NULL) AND (storage_path IS NOT NULL)))),
    CONSTRAINT check_messages_sequence_number_positive CHECK ((sequence_number > 0)),
    CONSTRAINT messages_message_type_check CHECK (((message_type)::text = ANY ((ARRAY['TEXT'::character varying, 'IMAGE'::character varying, 'VOICE'::character varying, 'ICEBREAKER'::character varying, 'PROMPT_REPLY'::character varying])::text[]))),
    CONSTRAINT messages_metadata_check CHECK ((jsonb_typeof(metadata) = 'object'::text)),
    CONSTRAINT messages_moderation_status_check CHECK (((moderation_status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED_FLAGGED'::character varying])::text[])))
);


--
-- Name: notification_campaigns; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notification_campaigns (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    campaign_key character varying(100) NOT NULL,
    title character varying(120) NOT NULL,
    body character varying(300) NOT NULL,
    navigation_payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    audience_definition jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    scheduled_at timestamp with time zone,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    cancelled_at timestamp with time zone,
    created_by_user_id uuid,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT notification_campaigns_audience_definition_check CHECK ((jsonb_typeof(audience_definition) = 'object'::text)),
    CONSTRAINT notification_campaigns_navigation_payload_check CHECK ((jsonb_typeof(navigation_payload) = 'object'::text)),
    CONSTRAINT notification_campaigns_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SCHEDULED'::character varying, 'SENDING'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: notification_deliveries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notification_deliveries (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    notification_outbox_event_id uuid NOT NULL,
    notification_device_id uuid NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    resolution_code character varying(100),
    attempt_count integer DEFAULT 0 NOT NULL,
    available_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    locked_at timestamp with time zone,
    locked_by character varying(100),
    lease_expires_at timestamp with time zone,
    provider_ticket_id text,
    submitted_at timestamp with time zone,
    next_receipt_check_at timestamp with time zone,
    receipt_deadline_at timestamp with time zone,
    receipt_checked_at timestamp with time zone,
    confirmed_at timestamp with time zone,
    last_error_code character varying(100),
    last_error text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT check_notification_delivery_processing_lease CHECK ((((status)::text <> 'PROCESSING'::text) OR ((locked_at IS NOT NULL) AND (locked_by IS NOT NULL) AND (lease_expires_at IS NOT NULL)))),
    CONSTRAINT notification_deliveries_attempt_count_check CHECK ((attempt_count >= 0)),
    CONSTRAINT notification_deliveries_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying, 'SUBMITTED'::character varying, 'CONFIRMED'::character varying, 'UNKNOWN'::character varying, 'FAILED'::character varying, 'SKIPPED'::character varying])::text[])))
);


--
-- Name: notification_devices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notification_devices (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    device_token text NOT NULL,
    platform character varying(20) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    last_seen_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    installation_id uuid,
    app_environment character varying(20) DEFAULT 'PRODUCTION'::character varying NOT NULL,
    disabled_at timestamp with time zone,
    last_error_code character varying(100),
    last_error_at timestamp with time zone,
    CONSTRAINT notification_devices_app_environment_check CHECK (((app_environment)::text = ANY ((ARRAY['DEVELOPMENT'::character varying, 'PREVIEW'::character varying, 'PRODUCTION'::character varying])::text[]))),
    CONSTRAINT notification_devices_platform_check CHECK (((platform)::text = ANY ((ARRAY['IOS'::character varying, 'ANDROID'::character varying, 'WEB'::character varying])::text[])))
);


--
-- Name: notification_outbox_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notification_outbox_events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    notification_type character varying(30) NOT NULL,
    recipient_user_id uuid NOT NULL,
    actor_user_id uuid,
    match_id uuid,
    message_id uuid,
    discovery_action_id uuid,
    campaign_id uuid,
    dedupe_key character varying(255) NOT NULL,
    collapse_key character varying(255),
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    available_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at timestamp with time zone,
    locked_at timestamp with time zone,
    locked_by character varying(100),
    lease_expires_at timestamp with time zone,
    fanout_completed_at timestamp with time zone,
    last_error text,
    occurred_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT check_notification_outbox_processing_lease CHECK ((((status)::text <> 'PROCESSING'::text) OR ((locked_at IS NOT NULL) AND (locked_by IS NOT NULL) AND (lease_expires_at IS NOT NULL)))),
    CONSTRAINT notification_outbox_events_attempt_count_check CHECK ((attempt_count >= 0)),
    CONSTRAINT notification_outbox_events_notification_type_check CHECK (((notification_type)::text = ANY ((ARRAY['CHAT_MESSAGE'::character varying, 'MATCH_CREATED'::character varying, 'LIKE_RECEIVED'::character varying, 'ACCOUNT_ALERT'::character varying, 'MARKETING'::character varying])::text[]))),
    CONSTRAINT notification_outbox_events_payload_check CHECK ((jsonb_typeof(payload) = 'object'::text)),
    CONSTRAINT notification_outbox_events_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying, 'FANOUT_COMPLETE'::character varying, 'SKIPPED'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: payment_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid,
    subscription_id uuid,
    provider character varying(50) NOT NULL,
    provider_event_id character varying(255) NOT NULL,
    event_type character varying(100) NOT NULL,
    amount_minor_units integer,
    currency character varying(3),
    raw_payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    transaction_id uuid,
    payment_order_id uuid,
    processing_status character varying(30) DEFAULT 'PROCESSED'::character varying NOT NULL,
    signature_verified_at timestamp with time zone,
    processed_at timestamp with time zone,
    processing_error text,
    CONSTRAINT payment_events_amount_minor_units_check CHECK (((amount_minor_units IS NULL) OR (amount_minor_units >= 0))),
    CONSTRAINT payment_events_provider_check CHECK (((provider)::text = ANY ((ARRAY['STRIPE'::character varying, 'REVENUECAT'::character varying, 'APPLE_APP_STORE'::character varying, 'GOOGLE_PLAY'::character varying, 'TELEBIRR'::character varying, 'CBE_BIRR'::character varying, 'CHAPA'::character varying, 'ARIFPAY'::character varying, 'BANK_TRANSFER'::character varying, 'VERIFY_ET'::character varying])::text[]))),
    CONSTRAINT payment_events_raw_payload_check CHECK ((jsonb_typeof(raw_payload) = 'object'::text))
);


--
-- Name: payment_methods; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_methods (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    country_code character varying(10) DEFAULT 'GLOBAL'::character varying NOT NULL,
    platform character varying(20) NOT NULL,
    method_code character varying(100) NOT NULL,
    display_name character varying(150) NOT NULL,
    payment_channel character varying(50) NOT NULL,
    payment_method character varying(50) NOT NULL,
    payment_instructions text,
    is_active boolean DEFAULT true NOT NULL,
    display_order smallint DEFAULT 0 NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    verification_params jsonb,
    CONSTRAINT payment_methods_payment_channel_check CHECK (((payment_channel)::text = ANY ((ARRAY['ONLINE_PAYMENT'::character varying, 'MANUAL_TRANSFER'::character varying])::text[]))),
    CONSTRAINT payment_methods_platform_check CHECK (((platform)::text = ANY ((ARRAY['ANDROID'::character varying, 'IOS'::character varying, 'WEB'::character varying])::text[])))
);


--
-- Name: payment_offers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_offers (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    subscription_product_id uuid,
    consumable_product_id uuid,
    country_code character varying(10) DEFAULT 'GLOBAL'::character varying NOT NULL,
    platform character varying(20) NOT NULL,
    currency character varying(3) NOT NULL,
    price_minor_units integer NOT NULL,
    external_product_id character varying(255),
    revenuecat_offering_id character varying(100),
    revenuecat_package_id character varying(100),
    auto_renew boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT check_offer_has_exactly_one_product CHECK ((((subscription_product_id IS NOT NULL) AND (consumable_product_id IS NULL)) OR ((subscription_product_id IS NULL) AND (consumable_product_id IS NOT NULL)))),
    CONSTRAINT payment_offers_platform_check CHECK (((platform)::text = ANY ((ARRAY['ANDROID'::character varying, 'IOS'::character varying, 'WEB'::character varying])::text[]))),
    CONSTRAINT payment_offers_price_minor_units_check CHECK ((price_minor_units >= 0))
);


--
-- Name: payment_orders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_orders (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    payment_offer_id uuid NOT NULL,
    order_reference character varying(100) NOT NULL,
    status character varying(40) DEFAULT 'CREATED'::character varying NOT NULL,
    expected_amount_minor_units integer NOT NULL,
    expected_currency character varying(3) NOT NULL,
    payment_instruction_snapshot jsonb DEFAULT '{}'::jsonb NOT NULL,
    provider_checkout_url text,
    provider_order_reference character varying(255),
    expires_at timestamp with time zone NOT NULL,
    idempotency_key character varying(255),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    payment_method_id uuid NOT NULL,
    status_reason text,
    manual_payment_reference character varying(255),
    manual_payment_reference_normalized character varying(255),
    provider_verification_request_id character varying(255),
    verification_count integer DEFAULT 0 NOT NULL,
    CONSTRAINT payment_orders_expected_amount_minor_units_check CHECK ((expected_amount_minor_units > 0)),
    CONSTRAINT payment_orders_status_check CHECK (((status)::text = ANY ((ARRAY['CREATED'::character varying, 'AWAITING_PAYMENT'::character varying, 'RECEIPT_SUBMITTED'::character varying, 'VERIFICATION_PENDING'::character varying, 'MANUAL_REVIEW'::character varying, 'VERIFIED'::character varying, 'REJECTED'::character varying, 'EXPIRED'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: payment_proofs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_proofs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    payment_order_id uuid NOT NULL,
    proof_type character varying(30) NOT NULL,
    payment_network character varying(50),
    transaction_reference character varying(255),
    receipt_storage_bucket character varying(100),
    receipt_storage_path text,
    submitted_amount_minor_units integer,
    submitted_currency character varying(3),
    submitted_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT payment_proofs_proof_type_check CHECK (((proof_type)::text = ANY ((ARRAY['TRANSACTION_REFERENCE'::character varying, 'RECEIPT_UPLOAD'::character varying])::text[])))
);


--
-- Name: payment_verification_attempts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_verification_attempts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    payment_order_id uuid NOT NULL,
    payment_proof_id uuid,
    verification_method character varying(50) NOT NULL,
    provider_request_id character varying(255),
    provider_verification_reference character varying(255),
    status character varying(30) DEFAULT 'PENDING'::character varying NOT NULL,
    verified_amount_minor_units integer,
    verified_currency character varying(3),
    verified_recipient_reference character varying(255),
    verified_paid_at timestamp with time zone,
    raw_response jsonb DEFAULT '{}'::jsonb NOT NULL,
    verified_by_admin_id uuid,
    admin_decision_note text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    verify_et_request_id character varying(36),
    verify_et_idempotency_key character varying(255),
    settlement_account_matched boolean,
    confirmed_before boolean,
    CONSTRAINT payment_verification_attempts_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'VERIFIED'::character varying, 'NOT_FOUND'::character varying, 'AMOUNT_MISMATCH'::character varying, 'RECIPIENT_MISMATCH'::character varying, 'DUPLICATE_PAYMENT'::character varying, 'MANUAL_REVIEW'::character varying, 'REJECTED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT payment_verification_attempts_verification_method_check CHECK (((verification_method)::text = ANY ((ARRAY['CHAPA_API'::character varying, 'VERIFY_ET'::character varying, 'ADMIN_REVIEW'::character varying])::text[])))
);


--
-- Name: profile_photos; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_photos (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    storage_bucket character varying(100) DEFAULT 'profile-photos'::character varying NOT NULL,
    storage_path text NOT NULL,
    photo_order integer NOT NULL,
    is_primary boolean DEFAULT false NOT NULL,
    moderation_status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    reviewed_by uuid,
    reviewed_at timestamp with time zone,
    rejection_reason text,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    deleted_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT profile_photos_metadata_check CHECK ((jsonb_typeof(metadata) = 'object'::text)),
    CONSTRAINT profile_photos_moderation_status_check CHECK (((moderation_status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'MANUAL_REVIEW'::character varying])::text[]))),
    CONSTRAINT profile_photos_photo_order_check CHECK ((photo_order >= 0)),
    CONSTRAINT rejected_photo_cannot_be_primary CHECK (((NOT is_primary) OR ((moderation_status)::text <> 'REJECTED'::text)))
);


--
-- Name: profile_prompt_answers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_prompt_answers (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    prompt_id uuid NOT NULL,
    answer_text text NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT profile_prompt_answers_answer_text_check CHECK (((char_length(btrim(answer_text)) >= 1) AND (char_length(btrim(answer_text)) <= 300)))
);


--
-- Name: profile_prompt_translations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_prompt_translations (
    prompt_id uuid NOT NULL,
    locale character varying(10) NOT NULL,
    prompt_text text NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT profile_prompt_translations_locale_check CHECK (((locale)::text = ANY ((ARRAY['en'::character varying, 'am'::character varying, 'ti'::character varying, 'om'::character varying])::text[]))),
    CONSTRAINT profile_prompt_translations_prompt_text_check CHECK (((char_length(btrim(prompt_text)) >= 1) AND (char_length(btrim(prompt_text)) <= 500)))
);


--
-- Name: profile_prompts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_prompts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    prompt_text text NOT NULL,
    category character varying(50) NOT NULL,
    display_order integer DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT profile_prompts_prompt_text_check CHECK (((char_length(btrim(prompt_text)) >= 1) AND (char_length(btrim(prompt_text)) <= 500)))
);


--
-- Name: profiles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profiles (
    user_id uuid NOT NULL,
    display_name character varying(100) NOT NULL,
    gender character varying(20) NOT NULL,
    date_of_birth date NOT NULL,
    bio text,
    height_cm integer,
    residency_type character varying(20) NOT NULL,
    nationality character varying(100),
    religion character varying(50),
    education_level character varying(50),
    occupation character varying(100),
    relationship_intention character varying(50) NOT NULL,
    marital_status character varying(50),
    has_children boolean,
    wants_children boolean,
    smoking boolean,
    drinking boolean,
    is_visible boolean DEFAULT false NOT NULL,
    is_onboarded boolean DEFAULT false NOT NULL,
    is_verified boolean DEFAULT false NOT NULL,
    profile_completion_score integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    activity_level character varying(20),
    interests text[] DEFAULT '{}'::text[],
    smoking_detail character varying(50),
    drinking_detail character varying(50),
    discovery_mode character varying(20) DEFAULT 'PUBLIC'::character varying NOT NULL,
    language_ids uuid[] DEFAULT '{}'::uuid[] NOT NULL,
    ethnicity_ids uuid[] DEFAULT '{}'::uuid[] NOT NULL,
    ethnicity_other_text text,
    CONSTRAINT chk_profiles_drinking_detail CHECK (((drinking_detail IS NULL) OR ((drinking_detail)::text = ANY ((ARRAY['NO'::character varying, 'SOCIALLY'::character varying, 'OCCASIONALLY'::character varying, 'YES'::character varying])::text[])))),
    CONSTRAINT chk_profiles_ethnicity_ids_limit CHECK ((cardinality(ethnicity_ids) <= 10)),
    CONSTRAINT chk_profiles_language_ids_limit CHECK ((cardinality(language_ids) <= 20)),
    CONSTRAINT chk_profiles_lifestyle_array_limits CHECK (((interests IS NULL) OR (cardinality(interests) <= 20))),
    CONSTRAINT chk_profiles_marital_status CHECK (((marital_status IS NULL) OR ((marital_status)::text = ANY ((ARRAY['NEVER_MARRIED'::character varying, 'DIVORCED'::character varying, 'WIDOWED'::character varying, 'SEPARATED'::character varying])::text[])))),
    CONSTRAINT chk_profiles_smoking_detail CHECK (((smoking_detail IS NULL) OR ((smoking_detail)::text = ANY ((ARRAY['NO'::character varying, 'YES'::character varying, 'OCCASIONALLY'::character varying, 'TRYING_TO_QUIT'::character varying])::text[])))),
    CONSTRAINT profiles_activity_level_check CHECK (((activity_level IS NULL) OR ((activity_level)::text = ANY ((ARRAY['SEDENTARY'::character varying, 'LIGHT'::character varying, 'MODERATE'::character varying, 'ACTIVE'::character varying, 'VERY_ACTIVE'::character varying])::text[])))),
    CONSTRAINT profiles_bio_check CHECK (((bio IS NULL) OR (char_length(btrim(bio)) <= 2000))),
    CONSTRAINT profiles_discovery_mode_check CHECK (((discovery_mode)::text = ANY ((ARRAY['PUBLIC'::character varying, 'INCOGNITO'::character varying])::text[]))),
    CONSTRAINT profiles_display_name_check CHECK (((char_length(btrim((display_name)::text)) >= 1) AND (char_length(btrim((display_name)::text)) <= 100))),
    CONSTRAINT profiles_ethnicity_other_text_check CHECK (((ethnicity_other_text IS NULL) OR (char_length(TRIM(BOTH FROM ethnicity_other_text)) <= 200))),
    CONSTRAINT profiles_gender_check CHECK (((gender)::text = ANY ((ARRAY['MALE'::character varying, 'FEMALE'::character varying])::text[]))),
    CONSTRAINT profiles_height_cm_check CHECK (((height_cm >= 100) AND (height_cm <= 250))),
    CONSTRAINT profiles_profile_completion_score_check CHECK (((profile_completion_score >= 0) AND (profile_completion_score <= 100))),
    CONSTRAINT profiles_relationship_intention_check CHECK (((relationship_intention)::text = ANY ((ARRAY['MARRIAGE'::character varying, 'SERIOUS_RELATIONSHIP'::character varying, 'LONG_TERM'::character varying, 'FRIENDSHIP'::character varying, 'NOT_SURE_YET'::character varying])::text[]))),
    CONSTRAINT profiles_residency_type_check CHECK (((residency_type)::text = ANY ((ARRAY['ETHIOPIA'::character varying, 'ERITREA'::character varying, 'DIASPORA'::character varying])::text[]))),
    CONSTRAINT visible_profile_must_be_onboarded CHECK (((NOT is_visible) OR is_onboarded))
);


--
-- Name: promotion_campaigns; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.promotion_campaigns (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    campaign_key character varying(100) NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    trigger_type character varying(30) NOT NULL,
    eligibility_type character varying(40) NOT NULL,
    benefit_type character varying(30) NOT NULL,
    discount_type character varying(20),
    discount_value bigint,
    discount_currency character varying(3),
    subscription_product_id uuid NOT NULL,
    country_code character varying(10) NOT NULL,
    duration_days integer,
    new_user_window_days integer,
    max_redemptions integer,
    max_redemptions_per_user integer DEFAULT 1 NOT NULL,
    reserved_count integer DEFAULT 0 NOT NULL,
    fulfilled_count integer DEFAULT 0 NOT NULL,
    priority integer DEFAULT 0 NOT NULL,
    starts_at timestamp with time zone NOT NULL,
    ends_at timestamp with time zone,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    created_by_user_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    target_gender character varying(10),
    CONSTRAINT chk_campaign_benefit_type CHECK (((benefit_type)::text = ANY ((ARRAY['FREE_PREMIUM'::character varying, 'DISCOUNT'::character varying])::text[]))),
    CONSTRAINT chk_campaign_discount_required CHECK ((((benefit_type)::text <> 'DISCOUNT'::text) OR ((discount_type IS NOT NULL) AND (discount_value IS NOT NULL)))),
    CONSTRAINT chk_campaign_discount_type CHECK (((discount_type IS NULL) OR ((discount_type)::text = ANY ((ARRAY['FIXED'::character varying, 'PERCENTAGE'::character varying])::text[])))),
    CONSTRAINT chk_campaign_eligibility_type CHECK (((eligibility_type)::text = ANY ((ARRAY['ANY_ELIGIBLE_USER'::character varying, 'NEW_USER'::character varying, 'NEVER_SUBSCRIBED'::character varying, 'NO_ACTIVE_SUBSCRIPTION'::character varying])::text[]))),
    CONSTRAINT chk_campaign_ends_after_starts CHECK (((ends_at IS NULL) OR (ends_at > starts_at))),
    CONSTRAINT chk_campaign_fixed_currency CHECK ((((discount_type)::text <> 'FIXED'::text) OR ((discount_value > 0) AND (discount_currency IS NOT NULL)))),
    CONSTRAINT chk_campaign_free_premium CHECK ((((benefit_type)::text <> 'FREE_PREMIUM'::text) OR ((duration_days > 0) AND (discount_type IS NULL) AND (discount_value IS NULL) AND (discount_currency IS NULL)))),
    CONSTRAINT chk_campaign_fulfilled_count CHECK ((fulfilled_count >= 0)),
    CONSTRAINT chk_campaign_max_per_user CHECK ((max_redemptions_per_user > 0)),
    CONSTRAINT chk_campaign_max_redemptions CHECK (((max_redemptions IS NULL) OR (max_redemptions > 0))),
    CONSTRAINT chk_campaign_new_user_window CHECK ((((eligibility_type)::text <> 'NEW_USER'::text) OR (new_user_window_days > 0))),
    CONSTRAINT chk_campaign_percentage_value CHECK ((((discount_type)::text <> 'PERCENTAGE'::text) OR ((discount_value > 0) AND (discount_value <= 10000) AND (discount_currency IS NULL)))),
    CONSTRAINT chk_campaign_reserved_count CHECK ((reserved_count >= 0)),
    CONSTRAINT chk_campaign_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'ACTIVE'::character varying, 'PAUSED'::character varying, 'EXPIRED'::character varying])::text[]))),
    CONSTRAINT chk_campaign_target_gender CHECK (((target_gender IS NULL) OR ((target_gender)::text = ANY ((ARRAY['MALE'::character varying, 'FEMALE'::character varying])::text[])))),
    CONSTRAINT chk_campaign_trigger_type CHECK (((trigger_type)::text = ANY ((ARRAY['AUTO_ON_SIGNUP'::character varying, 'USER_CLAIM'::character varying, 'PURCHASE'::character varying])::text[])))
);


--
-- Name: promotion_redemptions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.promotion_redemptions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    campaign_id uuid NOT NULL,
    user_id uuid NOT NULL,
    subscription_id uuid,
    payment_offer_id uuid,
    payment_order_id uuid,
    status character varying(20) NOT NULL,
    eligibility_country character varying(10) NOT NULL,
    original_amount_minor bigint,
    discount_amount_minor bigint,
    final_amount_minor bigint,
    currency character varying(3),
    reserved_at timestamp with time zone DEFAULT now() NOT NULL,
    fulfilled_at timestamp with time zone,
    cancelled_at timestamp with time zone,
    expired_at timestamp with time zone,
    failed_at timestamp with time zone,
    failure_code character varying(100),
    failure_reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    eligibility_gender character varying(10),
    CONSTRAINT chk_redemption_eligibility_gender CHECK (((eligibility_gender IS NULL) OR ((eligibility_gender)::text = ANY ((ARRAY['MALE'::character varying, 'FEMALE'::character varying])::text[])))),
    CONSTRAINT chk_redemption_status CHECK (((status)::text = ANY ((ARRAY['RESERVED'::character varying, 'PROVIDER_PENDING'::character varying, 'FULFILLED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[])))
);


--
-- Name: subscription_plan_limits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.subscription_plan_limits (
    plan_id uuid NOT NULL,
    limit_type character varying(30) NOT NULL,
    limit_value integer,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    period_type character varying(30) DEFAULT 'DAILY'::character varying,
    CONSTRAINT subscription_plan_limits_limit_type_check CHECK (((limit_type)::text = ANY ((ARRAY['LIKES'::character varying, 'SUPERLIKES'::character varying, 'REWINDS'::character varying, 'BOOSTS'::character varying, 'VOICE_CHAT_MSGS'::character varying, 'IMAGE_CHAT_MSGS'::character varying])::text[]))),
    CONSTRAINT subscription_plan_limits_limit_value_check CHECK (((limit_value IS NULL) OR (limit_value >= 0))),
    CONSTRAINT subscription_plan_limits_period_type_check CHECK (((period_type)::text = ANY ((ARRAY['DAILY'::character varying, 'SUBSCRIPTION_MONTH'::character varying, 'BILLING_CYCLE'::character varying])::text[])))
);


--
-- Name: subscription_plans; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.subscription_plans (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying(100) NOT NULL,
    plan_code character varying(50) NOT NULL,
    country_code character varying(10) DEFAULT 'GLOBAL'::character varying NOT NULL,
    plan_kind character varying(20) NOT NULL,
    price_minor_units integer NOT NULL,
    currency character varying(3) DEFAULT 'USD'::character varying NOT NULL,
    billing_interval character varying(20) NOT NULL,
    features jsonb DEFAULT '{}'::jsonb NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT check_subscription_plan_kind_and_billing CHECK (((((plan_kind)::text = 'FREE'::text) AND (price_minor_units = 0) AND ((billing_interval)::text = 'NONE'::text)) OR (((plan_kind)::text = 'PAID'::text) AND ((billing_interval)::text = ANY ((ARRAY['WEEKLY'::character varying, 'MONTHLY'::character varying, 'YEARLY'::character varying])::text[]))))),
    CONSTRAINT subscription_plans_billing_interval_check CHECK (((billing_interval)::text = ANY ((ARRAY['NONE'::character varying, 'WEEKLY'::character varying, 'MONTHLY'::character varying, 'YEARLY'::character varying])::text[]))),
    CONSTRAINT subscription_plans_features_check CHECK ((jsonb_typeof(features) = 'object'::text)),
    CONSTRAINT subscription_plans_name_check CHECK (((char_length(btrim((name)::text)) >= 1) AND (char_length(btrim((name)::text)) <= 100))),
    CONSTRAINT subscription_plans_plan_code_check CHECK (((char_length(btrim((plan_code)::text)) >= 1) AND (char_length(btrim((plan_code)::text)) <= 50))),
    CONSTRAINT subscription_plans_plan_kind_check CHECK (((plan_kind)::text = ANY ((ARRAY['FREE'::character varying, 'PAID'::character varying])::text[]))),
    CONSTRAINT subscription_plans_price_minor_units_check CHECK ((price_minor_units >= 0))
);


--
-- Name: subscription_products; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.subscription_products (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    plan_id uuid NOT NULL,
    product_code character varying(100) NOT NULL,
    billing_interval_unit character varying(20) NOT NULL,
    billing_interval_count smallint NOT NULL,
    auto_renew_supported boolean DEFAULT true NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT subscription_products_billing_interval_count_check CHECK ((billing_interval_count > 0)),
    CONSTRAINT subscription_products_billing_interval_unit_check CHECK (((billing_interval_unit)::text = ANY ((ARRAY['DAY'::character varying, 'WEEK'::character varying, 'MONTH'::character varying, 'YEAR'::character varying])::text[])))
);


--
-- Name: support_attachments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.support_attachments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    message_id uuid NOT NULL,
    storage_bucket character varying(100) NOT NULL,
    storage_path text NOT NULL,
    file_name character varying(255) NOT NULL,
    content_type character varying(100) NOT NULL,
    file_size_bytes bigint NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    attachment_kind character varying(20),
    duration_ms bigint,
    CONSTRAINT check_support_attachment_content_type CHECK ((NULLIF(btrim((content_type)::text), ''::text) IS NOT NULL)),
    CONSTRAINT check_support_attachment_file_name CHECK ((NULLIF(btrim((file_name)::text), ''::text) IS NOT NULL)),
    CONSTRAINT check_support_attachment_storage_path CHECK (((NULLIF(btrim(storage_path), ''::text) IS NOT NULL) AND (char_length(storage_path) <= 1024) AND (storage_path !~ '(^|/)\.\.(/|$)'::text))),
    CONSTRAINT support_attachments_attachment_kind_check CHECK (((attachment_kind)::text = ANY ((ARRAY['IMAGE'::character varying, 'DOCUMENT'::character varying, 'TEXT'::character varying, 'VOICE'::character varying, 'OTHER'::character varying])::text[]))),
    CONSTRAINT support_attachments_duration_ms_check CHECK (((duration_ms IS NULL) OR (duration_ms > 0))),
    CONSTRAINT support_attachments_file_size_bytes_check CHECK (((file_size_bytes > 0) AND (file_size_bytes <= 26214400))),
    CONSTRAINT support_attachments_storage_bucket_check CHECK (((storage_bucket)::text = 'support-attachments'::text))
);


--
-- Name: support_conversation_staff_reads; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.support_conversation_staff_reads (
    conversation_id uuid NOT NULL,
    staff_user_id uuid NOT NULL,
    last_read_sequence bigint DEFAULT 0 NOT NULL,
    last_read_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT support_conversation_staff_reads_last_read_sequence_check CHECK ((last_read_sequence >= 0))
);


--
-- Name: support_conversations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.support_conversations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    status character varying(20) DEFAULT 'IDLE'::character varying NOT NULL,
    priority smallint DEFAULT 3 NOT NULL,
    assigned_staff_user_id uuid,
    next_public_sequence bigint DEFAULT 1 NOT NULL,
    user_last_read_sequence bigint DEFAULT 0 NOT NULL,
    staff_last_read_sequence bigint DEFAULT 0 NOT NULL,
    last_public_message_at timestamp with time zone,
    last_public_message_sender_type character varying(10),
    waiting_since timestamp with time zone,
    first_staff_response_at timestamp with time zone,
    last_activity_at timestamp with time zone,
    closed_at timestamp with time zone,
    closed_by_app_user_id uuid,
    closed_by_type character varying(10),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT check_support_conv_public_state CHECK (((((status)::text = 'IDLE'::text) AND (last_public_message_at IS NULL) AND (last_public_message_sender_type IS NULL) AND (waiting_since IS NULL) AND (closed_at IS NULL) AND (closed_by_app_user_id IS NULL) AND (closed_by_type IS NULL)) OR (((status)::text = 'WAITING_STAFF'::text) AND (last_public_message_at IS NOT NULL) AND ((last_public_message_sender_type)::text = 'USER'::text) AND (waiting_since IS NOT NULL) AND (closed_at IS NULL) AND (closed_by_app_user_id IS NULL) AND (closed_by_type IS NULL)) OR (((status)::text = 'WAITING_USER'::text) AND (last_public_message_at IS NOT NULL) AND ((last_public_message_sender_type)::text = 'STAFF'::text) AND (waiting_since IS NULL) AND (closed_at IS NULL) AND (closed_by_app_user_id IS NULL) AND (closed_by_type IS NULL)) OR (((status)::text = 'CLOSED'::text) AND (last_public_message_at IS NOT NULL) AND (last_public_message_sender_type IS NOT NULL) AND (waiting_since IS NULL) AND (closed_at IS NOT NULL) AND (closed_by_type IS NOT NULL) AND ((((closed_by_type)::text = 'SYSTEM'::text) AND (closed_by_app_user_id IS NULL)) OR (((closed_by_type)::text = ANY ((ARRAY['USER'::character varying, 'STAFF'::character varying])::text[])) AND (closed_by_app_user_id IS NOT NULL)))))),
    CONSTRAINT check_support_conv_read_state CHECK (((user_last_read_sequence < next_public_sequence) AND (staff_last_read_sequence < next_public_sequence))),
    CONSTRAINT support_conversations_closed_by_type_check CHECK (((closed_by_type)::text = ANY ((ARRAY['USER'::character varying, 'STAFF'::character varying, 'SYSTEM'::character varying])::text[]))),
    CONSTRAINT support_conversations_last_public_message_sender_type_check CHECK (((last_public_message_sender_type)::text = ANY ((ARRAY['USER'::character varying, 'STAFF'::character varying])::text[]))),
    CONSTRAINT support_conversations_next_public_sequence_check CHECK ((next_public_sequence > 0)),
    CONSTRAINT support_conversations_priority_check CHECK (((priority >= 1) AND (priority <= 5))),
    CONSTRAINT support_conversations_staff_last_read_sequence_check CHECK ((staff_last_read_sequence >= 0)),
    CONSTRAINT support_conversations_status_check CHECK (((status)::text = ANY ((ARRAY['IDLE'::character varying, 'WAITING_STAFF'::character varying, 'WAITING_USER'::character varying, 'CLOSED'::character varying])::text[]))),
    CONSTRAINT support_conversations_user_last_read_sequence_check CHECK ((user_last_read_sequence >= 0))
);


--
-- Name: transactions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.transactions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    subscription_id uuid,
    payment_purpose character varying(30) NOT NULL,
    amount_minor_units integer NOT NULL,
    currency character varying(3) NOT NULL,
    provider character varying(50) NOT NULL,
    provider_transaction_id character varying(255),
    status character varying(30) DEFAULT 'PENDING'::character varying NOT NULL,
    receipt_storage_bucket character varying(100),
    receipt_storage_path text,
    admin_notes text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    payment_order_id uuid,
    payment_offer_id uuid,
    related_transaction_id uuid,
    transaction_type character varying(30) DEFAULT 'PURCHASE'::character varying,
    verification_provider character varying(50),
    country_code character varying(10),
    tax_amount_minor_units integer,
    provider_fee_minor_units integer,
    merchant_net_amount_minor_units integer,
    CONSTRAINT check_receipt_bucket_and_path_together CHECK ((((receipt_storage_bucket IS NULL) AND (receipt_storage_path IS NULL)) OR ((receipt_storage_bucket IS NOT NULL) AND (receipt_storage_path IS NOT NULL)))),
    CONSTRAINT transactions_amount_minor_units_check CHECK ((amount_minor_units >= 0)),
    CONSTRAINT transactions_payment_purpose_check CHECK (((payment_purpose)::text = ANY ((ARRAY['SUBSCRIPTION'::character varying, 'CONSUMABLE_PACK'::character varying, 'PROFILE_BOOST'::character varying, 'CONSUMABLE'::character varying])::text[]))),
    CONSTRAINT transactions_provider_check CHECK (((provider)::text = ANY ((ARRAY['STRIPE'::character varying, 'APPLE_APP_STORE'::character varying, 'GOOGLE_PLAY'::character varying, 'TELEBIRR'::character varying, 'CBE_BIRR'::character varying, 'CHAPA'::character varying, 'ARIFPAY'::character varying, 'BANK_TRANSFER'::character varying, 'REVENUECAT'::character varying, 'ADMIN'::character varying, 'cbe'::character varying, 'telebirr'::character varying, 'cbebirr'::character varying, 'mpesa'::character varying, 'boa'::character varying, 'awash'::character varying, 'dashen'::character varying, 'siinqee'::character varying, 'kaafiebirr'::character varying, 'zemen'::character varying])::text[]))),
    CONSTRAINT transactions_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'MANUAL_REVIEW'::character varying, 'REFUNDED'::character varying, 'PARTIALLY_REFUNDED'::character varying, 'REVERSED'::character varying])::text[])))
);


--
-- Name: user_blocks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_blocks (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    blocker_user_id uuid NOT NULL,
    blocked_user_id uuid NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    reason text,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT check_block_revocation_state CHECK (((((status)::text = 'ACTIVE'::text) AND (revoked_at IS NULL)) OR (((status)::text = 'REVOKED'::text) AND (revoked_at IS NOT NULL)))),
    CONSTRAINT check_not_self_block CHECK ((blocker_user_id <> blocked_user_id)),
    CONSTRAINT user_blocks_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REVOKED'::character varying])::text[])))
);


--
-- Name: user_daily_limits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_daily_limits (
    user_id uuid NOT NULL,
    limit_date date DEFAULT ((CURRENT_TIMESTAMP AT TIME ZONE 'UTC'::text))::date NOT NULL,
    likes_used integer DEFAULT 0 NOT NULL,
    super_likes_used integer DEFAULT 0 NOT NULL,
    rewinds_used integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    voice_chat_msgs_used integer DEFAULT 0 NOT NULL,
    image_chat_msgs_used integer DEFAULT 0 NOT NULL,
    CONSTRAINT user_daily_limits_likes_used_check CHECK ((likes_used >= 0)),
    CONSTRAINT user_daily_limits_rewinds_used_check CHECK ((rewinds_used >= 0)),
    CONSTRAINT user_daily_limits_super_likes_used_check CHECK ((super_likes_used >= 0))
);


--
-- Name: user_discovery_actions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_discovery_actions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    actor_user_id uuid NOT NULL,
    target_user_id uuid NOT NULL,
    action_type character varying(20) NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    client_action_id uuid NOT NULL,
    reversed_at timestamp with time zone,
    reversed_reason character varying(30),
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT check_not_self_discovery_action CHECK ((actor_user_id <> target_user_id)),
    CONSTRAINT check_reversal_state CHECK (((((status)::text = 'ACTIVE'::text) AND (reversed_at IS NULL) AND (reversed_reason IS NULL)) OR (((status)::text = 'REVERSED'::text) AND (reversed_at IS NOT NULL) AND (reversed_reason IS NOT NULL)))),
    CONSTRAINT user_discovery_actions_action_type_check CHECK (((action_type)::text = ANY ((ARRAY['LIKE'::character varying, 'PASS'::character varying, 'SUPERLIKE'::character varying])::text[]))),
    CONSTRAINT user_discovery_actions_metadata_check CHECK ((jsonb_typeof(metadata) = 'object'::text)),
    CONSTRAINT user_discovery_actions_reversed_reason_check CHECK (((reversed_reason)::text = ANY ((ARRAY['USER_REWIND'::character varying, 'SYSTEM'::character varying, 'ADMIN'::character varying, 'REVISIT_PASSES'::character varying, 'BLOCK'::character varying])::text[]))),
    CONSTRAINT user_discovery_actions_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REVERSED'::character varying])::text[])))
);


--
-- Name: user_entitlement_credit_consumptions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_entitlement_credit_consumptions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    consumption_ledger_entry_id uuid NOT NULL,
    credit_lot_id uuid NOT NULL,
    quantity_consumed integer NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT user_entitlement_credit_consumptions_quantity_consumed_check CHECK ((quantity_consumed > 0))
);


--
-- Name: user_entitlement_credit_lots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_entitlement_credit_lots (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    entitlement_type character varying(30) NOT NULL,
    source_ledger_entry_id uuid NOT NULL,
    quantity_granted integer NOT NULL,
    quantity_remaining integer NOT NULL,
    expires_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT check_remaining_not_exceed_granted CHECK ((quantity_remaining <= quantity_granted)),
    CONSTRAINT user_entitlement_credit_lots_entitlement_type_check CHECK (((entitlement_type)::text = ANY ((ARRAY['BOOST_CREDIT'::character varying, 'SUPERLIKE_CREDIT'::character varying, 'REWIND_CREDIT'::character varying])::text[]))),
    CONSTRAINT user_entitlement_credit_lots_quantity_granted_check CHECK ((quantity_granted > 0)),
    CONSTRAINT user_entitlement_credit_lots_quantity_remaining_check CHECK ((quantity_remaining >= 0))
);


--
-- Name: user_entitlement_ledger; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_entitlement_ledger (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    entitlement_type character varying(30) NOT NULL,
    quantity_delta integer NOT NULL,
    reason character varying(30) NOT NULL,
    transaction_id uuid,
    related_discovery_action_id uuid,
    idempotency_key character varying(255),
    expires_at timestamp with time zone,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    subscription_id uuid,
    CONSTRAINT user_entitlement_ledger_entitlement_type_check CHECK (((entitlement_type)::text = ANY ((ARRAY['SUPERLIKE_CREDIT'::character varying, 'REWIND_CREDIT'::character varying, 'BOOST_CREDIT'::character varying, 'PREMIUM_ACCESS'::character varying])::text[]))),
    CONSTRAINT user_entitlement_ledger_metadata_check CHECK ((jsonb_typeof(metadata) = 'object'::text)),
    CONSTRAINT user_entitlement_ledger_quantity_delta_check CHECK ((quantity_delta <> 0)),
    CONSTRAINT user_entitlement_ledger_reason_check CHECK (((reason)::text = ANY ((ARRAY['PURCHASE'::character varying, 'SUBSCRIPTION_ALLOWANCE'::character varying, 'CONSUMPTION'::character varying, 'REFUND'::character varying, 'EXPIRY'::character varying, 'ADMIN_GRANT'::character varying, 'ADJUSTMENT'::character varying, 'REVERSAL'::character varying])::text[])))
);


--
-- Name: user_notification_preferences; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_notification_preferences (
    user_id uuid NOT NULL,
    push_enabled boolean DEFAULT true NOT NULL,
    message_notifications_enabled boolean DEFAULT true NOT NULL,
    match_notifications_enabled boolean DEFAULT true NOT NULL,
    like_notifications_enabled boolean DEFAULT true NOT NULL,
    message_preview_enabled boolean DEFAULT false NOT NULL,
    marketing_notifications_enabled boolean DEFAULT false NOT NULL,
    marketing_notifications_opted_in_at timestamp with time zone,
    marketing_notifications_consent_version character varying(50),
    last_marketing_sent_at timestamp with time zone,
    marketing_reservation_event_id uuid,
    marketing_reservation_expires_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    superlike_notifications_enabled boolean DEFAULT true NOT NULL,
    CONSTRAINT check_marketing_opt_in CHECK (((NOT marketing_notifications_enabled) OR ((marketing_notifications_opted_in_at IS NOT NULL) AND (NULLIF(btrim((marketing_notifications_consent_version)::text), ''::text) IS NOT NULL))))
);


--
-- Name: COLUMN user_notification_preferences.superlike_notifications_enabled; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_notification_preferences.superlike_notifications_enabled IS 'Whether the user wants to receive push notifications when someone superlikes their profile';


--
-- Name: user_quota_usage; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_quota_usage (
    user_id uuid NOT NULL,
    plan_id uuid NOT NULL,
    resource_type character varying(30) NOT NULL,
    period_start timestamp with time zone NOT NULL,
    period_end timestamp with time zone NOT NULL,
    used_count integer DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT user_quota_usage_resource_type_check CHECK (((resource_type)::text = ANY ((ARRAY['LIKES'::character varying, 'SUPERLIKES'::character varying, 'REWINDS'::character varying, 'BOOSTS'::character varying])::text[]))),
    CONSTRAINT user_quota_usage_used_count_check CHECK ((used_count >= 0))
);


--
-- Name: user_reports; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_reports (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    reporter_user_id uuid,
    reported_user_id uuid NOT NULL,
    report_type character varying(50) NOT NULL,
    description text,
    related_message_id uuid,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    reviewed_by uuid,
    reviewed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT check_not_self_report CHECK (((reporter_user_id IS NULL) OR (reporter_user_id <> reported_user_id))),
    CONSTRAINT check_reporter_presence CHECK (((((report_type)::text = 'AUTO_FLAGGED'::text) AND (reporter_user_id IS NULL)) OR (((report_type)::text <> 'AUTO_FLAGGED'::text) AND (reporter_user_id IS NOT NULL)))),
    CONSTRAINT user_reports_report_type_check CHECK (((report_type)::text = ANY ((ARRAY['FAKE_PROFILE'::character varying, 'HARASSMENT'::character varying, 'HATE_SPEECH'::character varying, 'INAPPROPRIATE_CONTENT'::character varying, 'SCAM'::character varying, 'UNDERAGE'::character varying, 'VIOLENCE_OR_THREATS'::character varying, 'PRIVACY_VIOLATION'::character varying, 'OFF_PLATFORM_SOLICITATION'::character varying, 'SPAM'::character varying, 'AUTO_FLAGGED'::character varying, 'OTHER'::character varying])::text[]))),
    CONSTRAINT user_reports_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'UNDER_REVIEW'::character varying, 'RESOLVED_NO_ACTION'::character varying, 'RESOLVED_BANNED'::character varying])::text[])))
);


--
-- Name: user_subscriptions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_subscriptions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    plan_id uuid NOT NULL,
    provider character varying(50) NOT NULL,
    provider_subscription_id character varying(255),
    status character varying(30) NOT NULL,
    started_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    current_period_start timestamp with time zone NOT NULL,
    current_period_end timestamp with time zone NOT NULL,
    cancelled_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    payment_offer_id uuid,
    provider_subscription_reference character varying(512),
    auto_renew boolean DEFAULT false NOT NULL,
    ended_at timestamp with time zone,
    CONSTRAINT check_subscription_period CHECK ((current_period_end > current_period_start)),
    CONSTRAINT user_subscriptions_provider_check CHECK (((provider)::text = ANY ((ARRAY['STRIPE'::character varying, 'APPLE_APP_STORE'::character varying, 'GOOGLE_PLAY'::character varying, 'TELEBIRR'::character varying, 'CBE_BIRR'::character varying, 'CHAPA'::character varying, 'BANK_TRANSFER'::character varying, 'REVENUECAT'::character varying, 'ADMIN'::character varying, 'ARIFPAY'::character varying, 'PROMOTION'::character varying])::text[]))),
    CONSTRAINT user_subscriptions_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'PAST_DUE'::character varying, 'CANCELED'::character varying, 'UNPAID'::character varying, 'PENDING_VERIFICATION'::character varying, 'GRACE_PERIOD'::character varying, 'EXPIRED'::character varying, 'REVOKED'::character varying])::text[])))
);


--
-- Name: user_verifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_verifications (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    verification_type character varying(30) NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    provider character varying(50) DEFAULT 'MANUAL_ADMIN'::character varying NOT NULL,
    provider_reference_id character varying(255),
    storage_bucket character varying(100) DEFAULT 'verification-selfies'::character varying NOT NULL,
    storage_path text NOT NULL,
    submitted_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    reviewed_by uuid,
    reviewed_at timestamp with time zone,
    rejection_reason text,
    expires_at timestamp with time zone,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT user_verifications_metadata_check CHECK ((jsonb_typeof(metadata) = 'object'::text)),
    CONSTRAINT user_verifications_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[]))),
    CONSTRAINT user_verifications_verification_type_check CHECK (((verification_type)::text = ANY ((ARRAY['SELFIE_MATCH'::character varying, 'GOVERNMENT_ID'::character varying])::text[])))
);


--
-- Name: messages; Type: TABLE; Schema: realtime; Owner: -
--

CREATE TABLE realtime.messages (
    topic text NOT NULL,
    extension text NOT NULL,
    payload jsonb,
    event text,
    private boolean DEFAULT false,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    inserted_at timestamp without time zone DEFAULT now() NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    binary_payload bytea
)
PARTITION BY RANGE (inserted_at);


--
-- Name: messages_2026_07_22; Type: TABLE; Schema: realtime; Owner: -
--

CREATE TABLE realtime.messages_2026_07_22 (
    topic text NOT NULL,
    extension text NOT NULL,
    payload jsonb,
    event text,
    private boolean DEFAULT false,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    inserted_at timestamp without time zone DEFAULT now() NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    binary_payload bytea,
    CONSTRAINT messages_payload_exclusive CHECK (((payload IS NULL) OR (binary_payload IS NULL)))
);


--
-- Name: messages_2026_07_23; Type: TABLE; Schema: realtime; Owner: -
--

CREATE TABLE realtime.messages_2026_07_23 (
    topic text NOT NULL,
    extension text NOT NULL,
    payload jsonb,
    event text,
    private boolean DEFAULT false,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    inserted_at timestamp without time zone DEFAULT now() NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    binary_payload bytea,
    CONSTRAINT messages_payload_exclusive CHECK (((payload IS NULL) OR (binary_payload IS NULL)))
);


--
-- Name: messages_2026_07_24; Type: TABLE; Schema: realtime; Owner: -
--

CREATE TABLE realtime.messages_2026_07_24 (
    topic text NOT NULL,
    extension text NOT NULL,
    payload jsonb,
    event text,
    private boolean DEFAULT false,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    inserted_at timestamp without time zone DEFAULT now() NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    binary_payload bytea,
    CONSTRAINT messages_payload_exclusive CHECK (((payload IS NULL) OR (binary_payload IS NULL)))
);


--
-- Name: messages_2026_07_25; Type: TABLE; Schema: realtime; Owner: -
--

CREATE TABLE realtime.messages_2026_07_25 (
    topic text NOT NULL,
    extension text NOT NULL,
    payload jsonb,
    event text,
    private boolean DEFAULT false,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    inserted_at timestamp without time zone DEFAULT now() NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    binary_payload bytea,
    CONSTRAINT messages_payload_exclusive CHECK (((payload IS NULL) OR (binary_payload IS NULL)))
);


--
-- Name: messages_2026_07_26; Type: TABLE; Schema: realtime; Owner: -
--

CREATE TABLE realtime.messages_2026_07_26 (
    topic text NOT NULL,
    extension text NOT NULL,
    payload jsonb,
    event text,
    private boolean DEFAULT false,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    inserted_at timestamp without time zone DEFAULT now() NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    binary_payload bytea,
    CONSTRAINT messages_payload_exclusive CHECK (((payload IS NULL) OR (binary_payload IS NULL)))
);


--
-- Name: messages_2026_07_27; Type: TABLE; Schema: realtime; Owner: -
--

CREATE TABLE realtime.messages_2026_07_27 (
    topic text NOT NULL,
    extension text NOT NULL,
    payload jsonb,
    event text,
    private boolean DEFAULT false,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    inserted_at timestamp without time zone DEFAULT now() NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    binary_payload bytea,
    CONSTRAINT messages_payload_exclusive CHECK (((payload IS NULL) OR (binary_payload IS NULL)))
);


--
-- Name: messages_2026_07_28; Type: TABLE; Schema: realtime; Owner: -
--

CREATE TABLE realtime.messages_2026_07_28 (
    topic text NOT NULL,
    extension text NOT NULL,
    payload jsonb,
    event text,
    private boolean DEFAULT false,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    inserted_at timestamp without time zone DEFAULT now() NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    binary_payload bytea,
    CONSTRAINT messages_payload_exclusive CHECK (((payload IS NULL) OR (binary_payload IS NULL)))
);


--
-- Name: messages_2026_07_29; Type: TABLE; Schema: realtime; Owner: -
--

CREATE TABLE realtime.messages_2026_07_29 (
    topic text NOT NULL,
    extension text NOT NULL,
    payload jsonb,
    event text,
    private boolean DEFAULT false,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    inserted_at timestamp without time zone DEFAULT now() NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    binary_payload bytea,
    CONSTRAINT messages_payload_exclusive CHECK (((payload IS NULL) OR (binary_payload IS NULL)))
);


--
-- Name: schema_migrations; Type: TABLE; Schema: realtime; Owner: -
--

CREATE TABLE realtime.schema_migrations (
    version bigint NOT NULL,
    inserted_at timestamp(0) without time zone
);


--
-- Name: subscription; Type: TABLE; Schema: realtime; Owner: -
--

CREATE TABLE realtime.subscription (
    id bigint NOT NULL,
    subscription_id uuid NOT NULL,
    entity regclass NOT NULL,
    filters realtime.user_defined_filter[] DEFAULT '{}'::realtime.user_defined_filter[] NOT NULL,
    claims jsonb NOT NULL,
    claims_role regrole GENERATED ALWAYS AS (realtime.to_regrole((claims ->> 'role'::text))) STORED NOT NULL,
    created_at timestamp without time zone DEFAULT timezone('utc'::text, now()) NOT NULL,
    action_filter text DEFAULT '*'::text,
    selected_columns text[],
    CONSTRAINT subscription_action_filter_check CHECK ((action_filter = ANY (ARRAY['*'::text, 'INSERT'::text, 'UPDATE'::text, 'DELETE'::text])))
);


--
-- Name: subscription_id_seq; Type: SEQUENCE; Schema: realtime; Owner: -
--

ALTER TABLE realtime.subscription ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME realtime.subscription_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: buckets; Type: TABLE; Schema: storage; Owner: -
--

CREATE TABLE storage.buckets (
    id text NOT NULL,
    name text NOT NULL,
    owner uuid,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone DEFAULT now(),
    public boolean DEFAULT false,
    avif_autodetection boolean DEFAULT false,
    file_size_limit bigint,
    allowed_mime_types text[],
    owner_id text,
    type storage.buckettype DEFAULT 'STANDARD'::storage.buckettype NOT NULL
);


--
-- Name: COLUMN buckets.owner; Type: COMMENT; Schema: storage; Owner: -
--

COMMENT ON COLUMN storage.buckets.owner IS 'Field is deprecated, use owner_id instead';


--
-- Name: buckets_analytics; Type: TABLE; Schema: storage; Owner: -
--

CREATE TABLE storage.buckets_analytics (
    name text NOT NULL,
    type storage.buckettype DEFAULT 'ANALYTICS'::storage.buckettype NOT NULL,
    format text DEFAULT 'ICEBERG'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: buckets_vectors; Type: TABLE; Schema: storage; Owner: -
--

CREATE TABLE storage.buckets_vectors (
    id text NOT NULL,
    type storage.buckettype DEFAULT 'VECTOR'::storage.buckettype NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: migrations; Type: TABLE; Schema: storage; Owner: -
--

CREATE TABLE storage.migrations (
    id integer NOT NULL,
    name character varying(100) NOT NULL,
    hash character varying(40) NOT NULL,
    executed_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: objects; Type: TABLE; Schema: storage; Owner: -
--

CREATE TABLE storage.objects (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    bucket_id text,
    name text,
    owner uuid,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone DEFAULT now(),
    last_accessed_at timestamp with time zone DEFAULT now(),
    metadata jsonb,
    path_tokens text[] GENERATED ALWAYS AS (string_to_array(name, '/'::text)) STORED,
    version text,
    owner_id text,
    user_metadata jsonb
);


--
-- Name: COLUMN objects.owner; Type: COMMENT; Schema: storage; Owner: -
--

COMMENT ON COLUMN storage.objects.owner IS 'Field is deprecated, use owner_id instead';


--
-- Name: s3_multipart_uploads; Type: TABLE; Schema: storage; Owner: -
--

CREATE TABLE storage.s3_multipart_uploads (
    id text NOT NULL,
    in_progress_size bigint DEFAULT 0 NOT NULL,
    upload_signature text NOT NULL,
    bucket_id text NOT NULL,
    key text NOT NULL COLLATE pg_catalog."C",
    version text NOT NULL,
    owner_id text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    user_metadata jsonb,
    metadata jsonb
);


--
-- Name: s3_multipart_uploads_parts; Type: TABLE; Schema: storage; Owner: -
--

CREATE TABLE storage.s3_multipart_uploads_parts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    upload_id text NOT NULL,
    size bigint DEFAULT 0 NOT NULL,
    part_number integer NOT NULL,
    bucket_id text NOT NULL,
    key text NOT NULL COLLATE pg_catalog."C",
    etag text NOT NULL,
    owner_id text,
    version text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: vector_indexes; Type: TABLE; Schema: storage; Owner: -
--

CREATE TABLE storage.vector_indexes (
    id text DEFAULT gen_random_uuid() NOT NULL,
    name text NOT NULL COLLATE pg_catalog."C",
    bucket_id text NOT NULL,
    data_type text NOT NULL,
    dimension integer NOT NULL,
    distance_metric text NOT NULL,
    metadata_configuration jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: messages_2026_07_22; Type: TABLE ATTACH; Schema: realtime; Owner: -
--

ALTER TABLE ONLY realtime.messages ATTACH PARTITION realtime.messages_2026_07_22 FOR VALUES FROM ('2026-07-22 00:00:00') TO ('2026-07-23 00:00:00');


--
-- Name: messages_2026_07_23; Type: TABLE ATTACH; Schema: realtime; Owner: -
--

ALTER TABLE ONLY realtime.messages ATTACH PARTITION realtime.messages_2026_07_23 FOR VALUES FROM ('2026-07-23 00:00:00') TO ('2026-07-24 00:00:00');


--
-- Name: messages_2026_07_24; Type: TABLE ATTACH; Schema: realtime; Owner: -
--

ALTER TABLE ONLY realtime.messages ATTACH PARTITION realtime.messages_2026_07_24 FOR VALUES FROM ('2026-07-24 00:00:00') TO ('2026-07-25 00:00:00');


--
-- Name: messages_2026_07_25; Type: TABLE ATTACH; Schema: realtime; Owner: -
--

ALTER TABLE ONLY realtime.messages ATTACH PARTITION realtime.messages_2026_07_25 FOR VALUES FROM ('2026-07-25 00:00:00') TO ('2026-07-26 00:00:00');


--
-- Name: messages_2026_07_26; Type: TABLE ATTACH; Schema: realtime; Owner: -
--

ALTER TABLE ONLY realtime.messages ATTACH PARTITION realtime.messages_2026_07_26 FOR VALUES FROM ('2026-07-26 00:00:00') TO ('2026-07-27 00:00:00');


--
-- Name: messages_2026_07_27; Type: TABLE ATTACH; Schema: realtime; Owner: -
--

ALTER TABLE ONLY realtime.messages ATTACH PARTITION realtime.messages_2026_07_27 FOR VALUES FROM ('2026-07-27 00:00:00') TO ('2026-07-28 00:00:00');


--
-- Name: messages_2026_07_28; Type: TABLE ATTACH; Schema: realtime; Owner: -
--

ALTER TABLE ONLY realtime.messages ATTACH PARTITION realtime.messages_2026_07_28 FOR VALUES FROM ('2026-07-28 00:00:00') TO ('2026-07-29 00:00:00');


--
-- Name: messages_2026_07_29; Type: TABLE ATTACH; Schema: realtime; Owner: -
--

ALTER TABLE ONLY realtime.messages ATTACH PARTITION realtime.messages_2026_07_29 FOR VALUES FROM ('2026-07-29 00:00:00') TO ('2026-07-30 00:00:00');


--
-- Name: refresh_tokens id; Type: DEFAULT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.refresh_tokens ALTER COLUMN id SET DEFAULT nextval('auth.refresh_tokens_id_seq'::regclass);


--
-- Name: mfa_amr_claims amr_id_pk; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.mfa_amr_claims
    ADD CONSTRAINT amr_id_pk PRIMARY KEY (id);


--
-- Name: audit_log_entries audit_log_entries_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.audit_log_entries
    ADD CONSTRAINT audit_log_entries_pkey PRIMARY KEY (id);


--
-- Name: custom_oauth_providers custom_oauth_providers_identifier_key; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.custom_oauth_providers
    ADD CONSTRAINT custom_oauth_providers_identifier_key UNIQUE (identifier);


--
-- Name: custom_oauth_providers custom_oauth_providers_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.custom_oauth_providers
    ADD CONSTRAINT custom_oauth_providers_pkey PRIMARY KEY (id);


--
-- Name: flow_state flow_state_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.flow_state
    ADD CONSTRAINT flow_state_pkey PRIMARY KEY (id);


--
-- Name: identities identities_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.identities
    ADD CONSTRAINT identities_pkey PRIMARY KEY (id);


--
-- Name: identities identities_provider_id_provider_unique; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.identities
    ADD CONSTRAINT identities_provider_id_provider_unique UNIQUE (provider_id, provider);


--
-- Name: instances instances_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.instances
    ADD CONSTRAINT instances_pkey PRIMARY KEY (id);


--
-- Name: mfa_amr_claims mfa_amr_claims_session_id_authentication_method_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.mfa_amr_claims
    ADD CONSTRAINT mfa_amr_claims_session_id_authentication_method_pkey UNIQUE (session_id, authentication_method);


--
-- Name: mfa_challenges mfa_challenges_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.mfa_challenges
    ADD CONSTRAINT mfa_challenges_pkey PRIMARY KEY (id);


--
-- Name: mfa_factors mfa_factors_last_challenged_at_key; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.mfa_factors
    ADD CONSTRAINT mfa_factors_last_challenged_at_key UNIQUE (last_challenged_at);


--
-- Name: mfa_factors mfa_factors_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.mfa_factors
    ADD CONSTRAINT mfa_factors_pkey PRIMARY KEY (id);


--
-- Name: oauth_authorizations oauth_authorizations_authorization_code_key; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.oauth_authorizations
    ADD CONSTRAINT oauth_authorizations_authorization_code_key UNIQUE (authorization_code);


--
-- Name: oauth_authorizations oauth_authorizations_authorization_id_key; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.oauth_authorizations
    ADD CONSTRAINT oauth_authorizations_authorization_id_key UNIQUE (authorization_id);


--
-- Name: oauth_authorizations oauth_authorizations_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.oauth_authorizations
    ADD CONSTRAINT oauth_authorizations_pkey PRIMARY KEY (id);


--
-- Name: oauth_client_states oauth_client_states_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.oauth_client_states
    ADD CONSTRAINT oauth_client_states_pkey PRIMARY KEY (id);


--
-- Name: oauth_clients oauth_clients_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.oauth_clients
    ADD CONSTRAINT oauth_clients_pkey PRIMARY KEY (id);


--
-- Name: oauth_consents oauth_consents_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.oauth_consents
    ADD CONSTRAINT oauth_consents_pkey PRIMARY KEY (id);


--
-- Name: oauth_consents oauth_consents_user_client_unique; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.oauth_consents
    ADD CONSTRAINT oauth_consents_user_client_unique UNIQUE (user_id, client_id);


--
-- Name: one_time_tokens one_time_tokens_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.one_time_tokens
    ADD CONSTRAINT one_time_tokens_pkey PRIMARY KEY (id);


--
-- Name: refresh_tokens refresh_tokens_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);


--
-- Name: refresh_tokens refresh_tokens_token_unique; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.refresh_tokens
    ADD CONSTRAINT refresh_tokens_token_unique UNIQUE (token);


--
-- Name: saml_providers saml_providers_entity_id_key; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.saml_providers
    ADD CONSTRAINT saml_providers_entity_id_key UNIQUE (entity_id);


--
-- Name: saml_providers saml_providers_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.saml_providers
    ADD CONSTRAINT saml_providers_pkey PRIMARY KEY (id);


--
-- Name: saml_relay_states saml_relay_states_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.saml_relay_states
    ADD CONSTRAINT saml_relay_states_pkey PRIMARY KEY (id);


--
-- Name: schema_migrations schema_migrations_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.schema_migrations
    ADD CONSTRAINT schema_migrations_pkey PRIMARY KEY (version);


--
-- Name: sessions sessions_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.sessions
    ADD CONSTRAINT sessions_pkey PRIMARY KEY (id);


--
-- Name: sso_domains sso_domains_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.sso_domains
    ADD CONSTRAINT sso_domains_pkey PRIMARY KEY (id);


--
-- Name: sso_providers sso_providers_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.sso_providers
    ADD CONSTRAINT sso_providers_pkey PRIMARY KEY (id);


--
-- Name: users users_phone_key; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.users
    ADD CONSTRAINT users_phone_key UNIQUE (phone);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: webauthn_challenges webauthn_challenges_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.webauthn_challenges
    ADD CONSTRAINT webauthn_challenges_pkey PRIMARY KEY (id);


--
-- Name: webauthn_credentials webauthn_credentials_pkey; Type: CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.webauthn_credentials
    ADD CONSTRAINT webauthn_credentials_pkey PRIMARY KEY (id);


--
-- Name: active_boosts active_boosts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.active_boosts
    ADD CONSTRAINT active_boosts_pkey PRIMARY KEY (id);


--
-- Name: addresses addresses_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.addresses
    ADD CONSTRAINT addresses_pkey PRIMARY KEY (id);


--
-- Name: app_users app_users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_users
    ADD CONSTRAINT app_users_pkey PRIMARY KEY (id);


--
-- Name: audit_log audit_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_pkey PRIMARY KEY (id);


--
-- Name: auth_anonymization_tasks auth_anonymization_tasks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_anonymization_tasks
    ADD CONSTRAINT auth_anonymization_tasks_pkey PRIMARY KEY (user_id);


--
-- Name: billing_customers billing_customers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_customers
    ADD CONSTRAINT billing_customers_pkey PRIMARY KEY (id);


--
-- Name: chat_attachments chat_attachments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_attachments
    ADD CONSTRAINT chat_attachments_pkey PRIMARY KEY (id);


--
-- Name: chat_outbox_events chat_outbox_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_outbox_events
    ADD CONSTRAINT chat_outbox_events_pkey PRIMARY KEY (id);


--
-- Name: consumable_products consumable_products_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consumable_products
    ADD CONSTRAINT consumable_products_pkey PRIMARY KEY (id);


--
-- Name: discovery_preferences discovery_preferences_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.discovery_preferences
    ADD CONSTRAINT discovery_preferences_pkey PRIMARY KEY (user_id);


--
-- Name: ethnicities ethnicities_code_country_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ethnicities
    ADD CONSTRAINT ethnicities_code_country_unique UNIQUE (code, country_code);


--
-- Name: ethnicities ethnicities_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ethnicities
    ADD CONSTRAINT ethnicities_pkey PRIMARY KEY (id);


--
-- Name: image_moderation_results image_moderation_results_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.image_moderation_results
    ADD CONSTRAINT image_moderation_results_pkey PRIMARY KEY (id);


--
-- Name: languages languages_code_country_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.languages
    ADD CONSTRAINT languages_code_country_unique UNIQUE (code, country_code);


--
-- Name: languages languages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.languages
    ADD CONSTRAINT languages_pkey PRIMARY KEY (id);


--
-- Name: location_places location_places_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.location_places
    ADD CONSTRAINT location_places_pkey PRIMARY KEY (id);


--
-- Name: match_notification_settings match_notification_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.match_notification_settings
    ADD CONSTRAINT match_notification_settings_pkey PRIMARY KEY (match_id, user_id);


--
-- Name: matches matches_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.matches
    ADD CONSTRAINT matches_pkey PRIMARY KEY (id);


--
-- Name: messages messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_pkey PRIMARY KEY (id);


--
-- Name: active_boosts no_overlapping_boosts_per_user; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.active_boosts
    ADD CONSTRAINT no_overlapping_boosts_per_user EXCLUDE USING gist (user_id WITH =, tstzrange(started_at, expires_at, '[)'::text) WITH &&);


--
-- Name: notification_campaigns notification_campaigns_campaign_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_campaigns
    ADD CONSTRAINT notification_campaigns_campaign_key_key UNIQUE (campaign_key);


--
-- Name: notification_campaigns notification_campaigns_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_campaigns
    ADD CONSTRAINT notification_campaigns_pkey PRIMARY KEY (id);


--
-- Name: notification_deliveries notification_deliveries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_deliveries
    ADD CONSTRAINT notification_deliveries_pkey PRIMARY KEY (id);


--
-- Name: notification_devices notification_devices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_devices
    ADD CONSTRAINT notification_devices_pkey PRIMARY KEY (id);


--
-- Name: notification_outbox_events notification_outbox_events_dedupe_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_outbox_events
    ADD CONSTRAINT notification_outbox_events_dedupe_key_key UNIQUE (dedupe_key);


--
-- Name: notification_outbox_events notification_outbox_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_outbox_events
    ADD CONSTRAINT notification_outbox_events_pkey PRIMARY KEY (id);


--
-- Name: payment_events payment_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_events
    ADD CONSTRAINT payment_events_pkey PRIMARY KEY (id);


--
-- Name: payment_methods payment_methods_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_methods
    ADD CONSTRAINT payment_methods_pkey PRIMARY KEY (id);


--
-- Name: payment_offers payment_offers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_offers
    ADD CONSTRAINT payment_offers_pkey PRIMARY KEY (id);


--
-- Name: payment_orders payment_orders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_orders
    ADD CONSTRAINT payment_orders_pkey PRIMARY KEY (id);


--
-- Name: payment_proofs payment_proofs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_proofs
    ADD CONSTRAINT payment_proofs_pkey PRIMARY KEY (id);


--
-- Name: payment_verification_attempts payment_verification_attempts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_verification_attempts
    ADD CONSTRAINT payment_verification_attempts_pkey PRIMARY KEY (id);


--
-- Name: profile_photos profile_photos_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profile_photos
    ADD CONSTRAINT profile_photos_pkey PRIMARY KEY (id);


--
-- Name: profile_prompt_answers profile_prompt_answers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profile_prompt_answers
    ADD CONSTRAINT profile_prompt_answers_pkey PRIMARY KEY (id);


--
-- Name: profile_prompt_translations profile_prompt_translations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profile_prompt_translations
    ADD CONSTRAINT profile_prompt_translations_pkey PRIMARY KEY (prompt_id, locale);


--
-- Name: profile_prompts profile_prompts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profile_prompts
    ADD CONSTRAINT profile_prompts_pkey PRIMARY KEY (id);


--
-- Name: profiles profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profiles
    ADD CONSTRAINT profiles_pkey PRIMARY KEY (user_id);


--
-- Name: promotion_campaigns promotion_campaigns_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.promotion_campaigns
    ADD CONSTRAINT promotion_campaigns_pkey PRIMARY KEY (id);


--
-- Name: promotion_redemptions promotion_redemptions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.promotion_redemptions
    ADD CONSTRAINT promotion_redemptions_pkey PRIMARY KEY (id);


--
-- Name: subscription_plan_limits subscription_plan_limits_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subscription_plan_limits
    ADD CONSTRAINT subscription_plan_limits_pkey PRIMARY KEY (plan_id, limit_type);


--
-- Name: subscription_plans subscription_plans_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subscription_plans
    ADD CONSTRAINT subscription_plans_pkey PRIMARY KEY (id);


--
-- Name: subscription_products subscription_products_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subscription_products
    ADD CONSTRAINT subscription_products_pkey PRIMARY KEY (id);


--
-- Name: support_attachments support_attachments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_attachments
    ADD CONSTRAINT support_attachments_pkey PRIMARY KEY (id);


--
-- Name: support_conversation_staff_reads support_conversation_staff_reads_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_conversation_staff_reads
    ADD CONSTRAINT support_conversation_staff_reads_pkey PRIMARY KEY (conversation_id, staff_user_id);


--
-- Name: support_conversations support_conversations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_conversations
    ADD CONSTRAINT support_conversations_pkey PRIMARY KEY (id);


--
-- Name: support_conversations support_conversations_user_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_conversations
    ADD CONSTRAINT support_conversations_user_id_key UNIQUE (user_id);


--
-- Name: support_internal_notes support_internal_notes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_internal_notes
    ADD CONSTRAINT support_internal_notes_pkey PRIMARY KEY (id);


--
-- Name: support_messages support_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_messages
    ADD CONSTRAINT support_messages_pkey PRIMARY KEY (id);


--
-- Name: transactions transactions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transactions
    ADD CONSTRAINT transactions_pkey PRIMARY KEY (id);


--
-- Name: billing_customers unique_billing_customer_provider_external; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_customers
    ADD CONSTRAINT unique_billing_customer_provider_external UNIQUE (provider, external_customer_id);


--
-- Name: billing_customers unique_billing_customer_user_provider; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_customers
    ADD CONSTRAINT unique_billing_customer_user_provider UNIQUE (user_id, provider);


--
-- Name: consumable_products unique_consumable_product_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consumable_products
    ADD CONSTRAINT unique_consumable_product_code UNIQUE (product_code);


--
-- Name: user_entitlement_credit_lots unique_credit_lot_source; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_entitlement_credit_lots
    ADD CONSTRAINT unique_credit_lot_source UNIQUE (source_ledger_entry_id);


--
-- Name: notification_devices unique_device_token; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_devices
    ADD CONSTRAINT unique_device_token UNIQUE (device_token);


--
-- Name: user_discovery_actions unique_discovery_client_action; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_discovery_actions
    ADD CONSTRAINT unique_discovery_client_action UNIQUE (actor_user_id, client_action_id);


--
-- Name: location_places unique_location_place; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.location_places
    ADD CONSTRAINT unique_location_place UNIQUE NULLS NOT DISTINCT (country_code, region, city);


--
-- Name: matches unique_match_creator_action; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.matches
    ADD CONSTRAINT unique_match_creator_action UNIQUE (created_by_action_id);


--
-- Name: messages unique_message_storage_object; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT unique_message_storage_object UNIQUE (storage_bucket, storage_path);


--
-- Name: messages unique_messages_match_sequence; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT unique_messages_match_sequence UNIQUE (match_id, sequence_number);


--
-- Name: notification_deliveries unique_notification_delivery_per_device; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_deliveries
    ADD CONSTRAINT unique_notification_delivery_per_device UNIQUE (notification_outbox_event_id, notification_device_id);


--
-- Name: payment_methods unique_payment_method_market; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_methods
    ADD CONSTRAINT unique_payment_method_market UNIQUE (country_code, platform, method_code);


--
-- Name: payment_orders unique_payment_order_reference; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_orders
    ADD CONSTRAINT unique_payment_order_reference UNIQUE (order_reference);


--
-- Name: subscription_plans unique_plan_code_per_country; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subscription_plans
    ADD CONSTRAINT unique_plan_code_per_country UNIQUE (plan_code, country_code);


--
-- Name: profile_photos unique_profile_photo_storage_object; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profile_photos
    ADD CONSTRAINT unique_profile_photo_storage_object UNIQUE (storage_bucket, storage_path);


--
-- Name: messages unique_sender_client_message; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT unique_sender_client_message UNIQUE (sender_user_id, client_message_id);


--
-- Name: subscription_products unique_subscription_product_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subscription_products
    ADD CONSTRAINT unique_subscription_product_code UNIQUE (product_code);


--
-- Name: support_attachments unique_support_attachment_object; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_attachments
    ADD CONSTRAINT unique_support_attachment_object UNIQUE (storage_bucket, storage_path);


--
-- Name: support_internal_notes unique_support_internal_note_client; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_internal_notes
    ADD CONSTRAINT unique_support_internal_note_client UNIQUE (conversation_id, staff_user_id, client_note_id);


--
-- Name: support_messages unique_support_msg_client; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_messages
    ADD CONSTRAINT unique_support_msg_client UNIQUE (conversation_id, sender_user_id, client_message_id);


--
-- Name: support_messages unique_support_msg_sequence; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_messages
    ADD CONSTRAINT unique_support_msg_sequence UNIQUE (conversation_id, sequence_number);


--
-- Name: app_users unique_user_address; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_users
    ADD CONSTRAINT unique_user_address UNIQUE (address_id);


--
-- Name: profile_prompt_answers unique_user_prompt; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profile_prompt_answers
    ADD CONSTRAINT unique_user_prompt UNIQUE (user_id, prompt_id);


--
-- Name: user_verifications unique_verification_storage_object; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_verifications
    ADD CONSTRAINT unique_verification_storage_object UNIQUE (storage_bucket, storage_path);


--
-- Name: image_moderation_results uq_imr_image_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.image_moderation_results
    ADD CONSTRAINT uq_imr_image_id UNIQUE (image_id);


--
-- Name: promotion_campaigns uq_promotion_campaign_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.promotion_campaigns
    ADD CONSTRAINT uq_promotion_campaign_key UNIQUE (campaign_key);


--
-- Name: user_blocks user_blocks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_blocks
    ADD CONSTRAINT user_blocks_pkey PRIMARY KEY (id);


--
-- Name: user_daily_limits user_daily_limits_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_daily_limits
    ADD CONSTRAINT user_daily_limits_pkey PRIMARY KEY (user_id, limit_date);


--
-- Name: user_discovery_actions user_discovery_actions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_discovery_actions
    ADD CONSTRAINT user_discovery_actions_pkey PRIMARY KEY (id);


--
-- Name: user_entitlement_credit_consumptions user_entitlement_credit_consumptions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_entitlement_credit_consumptions
    ADD CONSTRAINT user_entitlement_credit_consumptions_pkey PRIMARY KEY (id);


--
-- Name: user_entitlement_credit_lots user_entitlement_credit_lots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_entitlement_credit_lots
    ADD CONSTRAINT user_entitlement_credit_lots_pkey PRIMARY KEY (id);


--
-- Name: user_entitlement_ledger user_entitlement_ledger_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_entitlement_ledger
    ADD CONSTRAINT user_entitlement_ledger_pkey PRIMARY KEY (id);


--
-- Name: user_notification_preferences user_notification_preferences_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_notification_preferences
    ADD CONSTRAINT user_notification_preferences_pkey PRIMARY KEY (user_id);


--
-- Name: user_quota_usage user_quota_usage_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_quota_usage
    ADD CONSTRAINT user_quota_usage_pkey PRIMARY KEY (user_id, resource_type, period_start);


--
-- Name: user_reports user_reports_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_reports
    ADD CONSTRAINT user_reports_pkey PRIMARY KEY (id);


--
-- Name: user_subscriptions user_subscriptions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_subscriptions
    ADD CONSTRAINT user_subscriptions_pkey PRIMARY KEY (id);


--
-- Name: user_verifications user_verifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_verifications
    ADD CONSTRAINT user_verifications_pkey PRIMARY KEY (id);


--
-- Name: messages messages_pkey; Type: CONSTRAINT; Schema: realtime; Owner: -
--

ALTER TABLE ONLY realtime.messages
    ADD CONSTRAINT messages_pkey PRIMARY KEY (id, inserted_at);


--
-- Name: messages_2026_07_22 messages_2026_07_22_pkey; Type: CONSTRAINT; Schema: realtime; Owner: -
--

ALTER TABLE ONLY realtime.messages_2026_07_22
    ADD CONSTRAINT messages_2026_07_22_pkey PRIMARY KEY (id, inserted_at);


--
-- Name: messages_2026_07_23 messages_2026_07_23_pkey; Type: CONSTRAINT; Schema: realtime; Owner: -
--

ALTER TABLE ONLY realtime.messages_2026_07_23
    ADD CONSTRAINT messages_2026_07_23_pkey PRIMARY KEY (id, inserted_at);


--
-- Name: messages_2026_07_24 messages_2026_07_24_pkey; Type: CONSTRAINT; Schema: realtime; Owner: -
--

ALTER TABLE ONLY realtime.messages_2026_07_24
    ADD CONSTRAINT messages_2026_07_24_pkey PRIMARY KEY (id, inserted_at);


--
-- Name: messages_2026_07_25 messages_2026_07_25_pkey; Type: CONSTRAINT; Schema: realtime; Owner: -
--

ALTER TABLE ONLY realtime.messages_2026_07_25
    ADD CONSTRAINT messages_2026_07_25_pkey PRIMARY KEY (id, inserted_at);


--
-- Name: messages_2026_07_26 messages_2026_07_26_pkey; Type: CONSTRAINT; Schema: realtime; Owner: -
--

ALTER TABLE ONLY realtime.messages_2026_07_26
    ADD CONSTRAINT messages_2026_07_26_pkey PRIMARY KEY (id, inserted_at);


--
-- Name: messages_2026_07_27 messages_2026_07_27_pkey; Type: CONSTRAINT; Schema: realtime; Owner: -
--

ALTER TABLE ONLY realtime.messages_2026_07_27
    ADD CONSTRAINT messages_2026_07_27_pkey PRIMARY KEY (id, inserted_at);


--
-- Name: messages_2026_07_28 messages_2026_07_28_pkey; Type: CONSTRAINT; Schema: realtime; Owner: -
--

ALTER TABLE ONLY realtime.messages_2026_07_28
    ADD CONSTRAINT messages_2026_07_28_pkey PRIMARY KEY (id, inserted_at);


--
-- Name: messages_2026_07_29 messages_2026_07_29_pkey; Type: CONSTRAINT; Schema: realtime; Owner: -
--

ALTER TABLE ONLY realtime.messages_2026_07_29
    ADD CONSTRAINT messages_2026_07_29_pkey PRIMARY KEY (id, inserted_at);


--
-- Name: messages messages_payload_exclusive; Type: CHECK CONSTRAINT; Schema: realtime; Owner: -
--

ALTER TABLE realtime.messages
    ADD CONSTRAINT messages_payload_exclusive CHECK (((payload IS NULL) OR (binary_payload IS NULL))) NOT VALID;


--
-- Name: subscription pk_subscription; Type: CONSTRAINT; Schema: realtime; Owner: -
--

ALTER TABLE ONLY realtime.subscription
    ADD CONSTRAINT pk_subscription PRIMARY KEY (id);


--
-- Name: schema_migrations schema_migrations_pkey; Type: CONSTRAINT; Schema: realtime; Owner: -
--

ALTER TABLE ONLY realtime.schema_migrations
    ADD CONSTRAINT schema_migrations_pkey PRIMARY KEY (version);


--
-- Name: buckets_analytics buckets_analytics_pkey; Type: CONSTRAINT; Schema: storage; Owner: -
--

ALTER TABLE ONLY storage.buckets_analytics
    ADD CONSTRAINT buckets_analytics_pkey PRIMARY KEY (id);


--
-- Name: buckets buckets_pkey; Type: CONSTRAINT; Schema: storage; Owner: -
--

ALTER TABLE ONLY storage.buckets
    ADD CONSTRAINT buckets_pkey PRIMARY KEY (id);


--
-- Name: buckets_vectors buckets_vectors_pkey; Type: CONSTRAINT; Schema: storage; Owner: -
--

ALTER TABLE ONLY storage.buckets_vectors
    ADD CONSTRAINT buckets_vectors_pkey PRIMARY KEY (id);


--
-- Name: migrations migrations_name_key; Type: CONSTRAINT; Schema: storage; Owner: -
--

ALTER TABLE ONLY storage.migrations
    ADD CONSTRAINT migrations_name_key UNIQUE (name);


--
-- Name: migrations migrations_pkey; Type: CONSTRAINT; Schema: storage; Owner: -
--

ALTER TABLE ONLY storage.migrations
    ADD CONSTRAINT migrations_pkey PRIMARY KEY (id);


--
-- Name: objects objects_pkey; Type: CONSTRAINT; Schema: storage; Owner: -
--

ALTER TABLE ONLY storage.objects
    ADD CONSTRAINT objects_pkey PRIMARY KEY (id);


--
-- Name: s3_multipart_uploads_parts s3_multipart_uploads_parts_pkey; Type: CONSTRAINT; Schema: storage; Owner: -
--

ALTER TABLE ONLY storage.s3_multipart_uploads_parts
    ADD CONSTRAINT s3_multipart_uploads_parts_pkey PRIMARY KEY (id);


--
-- Name: s3_multipart_uploads s3_multipart_uploads_pkey; Type: CONSTRAINT; Schema: storage; Owner: -
--

ALTER TABLE ONLY storage.s3_multipart_uploads
    ADD CONSTRAINT s3_multipart_uploads_pkey PRIMARY KEY (id);


--
-- Name: vector_indexes vector_indexes_pkey; Type: CONSTRAINT; Schema: storage; Owner: -
--

ALTER TABLE ONLY storage.vector_indexes
    ADD CONSTRAINT vector_indexes_pkey PRIMARY KEY (id);


--
-- Name: audit_logs_instance_id_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX audit_logs_instance_id_idx ON auth.audit_log_entries USING btree (instance_id);


--
-- Name: confirmation_token_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE UNIQUE INDEX confirmation_token_idx ON auth.users USING btree (confirmation_token) WHERE ((confirmation_token)::text !~ '^[0-9 ]*$'::text);


--
-- Name: custom_oauth_providers_created_at_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX custom_oauth_providers_created_at_idx ON auth.custom_oauth_providers USING btree (created_at);


--
-- Name: custom_oauth_providers_enabled_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX custom_oauth_providers_enabled_idx ON auth.custom_oauth_providers USING btree (enabled);


--
-- Name: custom_oauth_providers_identifier_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX custom_oauth_providers_identifier_idx ON auth.custom_oauth_providers USING btree (identifier);


--
-- Name: custom_oauth_providers_provider_type_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX custom_oauth_providers_provider_type_idx ON auth.custom_oauth_providers USING btree (provider_type);


--
-- Name: email_change_token_current_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE UNIQUE INDEX email_change_token_current_idx ON auth.users USING btree (email_change_token_current) WHERE ((email_change_token_current)::text !~ '^[0-9 ]*$'::text);


--
-- Name: email_change_token_new_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE UNIQUE INDEX email_change_token_new_idx ON auth.users USING btree (email_change_token_new) WHERE ((email_change_token_new)::text !~ '^[0-9 ]*$'::text);


--
-- Name: factor_id_created_at_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX factor_id_created_at_idx ON auth.mfa_factors USING btree (user_id, created_at);


--
-- Name: flow_state_created_at_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX flow_state_created_at_idx ON auth.flow_state USING btree (created_at DESC);


--
-- Name: identities_email_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX identities_email_idx ON auth.identities USING btree (email text_pattern_ops);


--
-- Name: INDEX identities_email_idx; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON INDEX auth.identities_email_idx IS 'Auth: Ensures indexed queries on the email column';


--
-- Name: identities_user_id_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX identities_user_id_idx ON auth.identities USING btree (user_id);


--
-- Name: idx_auth_code; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX idx_auth_code ON auth.flow_state USING btree (auth_code);


--
-- Name: idx_oauth_client_states_created_at; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX idx_oauth_client_states_created_at ON auth.oauth_client_states USING btree (created_at);


--
-- Name: idx_user_id_auth_method; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX idx_user_id_auth_method ON auth.flow_state USING btree (user_id, authentication_method);


--
-- Name: idx_users_created_at_desc; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX idx_users_created_at_desc ON auth.users USING btree (created_at DESC);


--
-- Name: idx_users_email; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX idx_users_email ON auth.users USING btree (email);


--
-- Name: idx_users_last_sign_in_at_desc; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX idx_users_last_sign_in_at_desc ON auth.users USING btree (last_sign_in_at DESC);


--
-- Name: idx_users_name; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX idx_users_name ON auth.users USING btree (((raw_user_meta_data ->> 'name'::text))) WHERE ((raw_user_meta_data ->> 'name'::text) IS NOT NULL);


--
-- Name: mfa_challenge_created_at_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX mfa_challenge_created_at_idx ON auth.mfa_challenges USING btree (created_at DESC);


--
-- Name: mfa_factors_user_friendly_name_unique; Type: INDEX; Schema: auth; Owner: -
--

CREATE UNIQUE INDEX mfa_factors_user_friendly_name_unique ON auth.mfa_factors USING btree (friendly_name, user_id) WHERE (TRIM(BOTH FROM friendly_name) <> ''::text);


--
-- Name: mfa_factors_user_id_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX mfa_factors_user_id_idx ON auth.mfa_factors USING btree (user_id);


--
-- Name: oauth_auth_pending_exp_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX oauth_auth_pending_exp_idx ON auth.oauth_authorizations USING btree (expires_at) WHERE (status = 'pending'::auth.oauth_authorization_status);


--
-- Name: oauth_clients_deleted_at_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX oauth_clients_deleted_at_idx ON auth.oauth_clients USING btree (deleted_at);


--
-- Name: oauth_consents_active_client_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX oauth_consents_active_client_idx ON auth.oauth_consents USING btree (client_id) WHERE (revoked_at IS NULL);


--
-- Name: oauth_consents_active_user_client_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX oauth_consents_active_user_client_idx ON auth.oauth_consents USING btree (user_id, client_id) WHERE (revoked_at IS NULL);


--
-- Name: oauth_consents_user_order_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX oauth_consents_user_order_idx ON auth.oauth_consents USING btree (user_id, granted_at DESC);


--
-- Name: one_time_tokens_relates_to_hash_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX one_time_tokens_relates_to_hash_idx ON auth.one_time_tokens USING hash (relates_to);


--
-- Name: one_time_tokens_token_hash_hash_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX one_time_tokens_token_hash_hash_idx ON auth.one_time_tokens USING hash (token_hash);


--
-- Name: one_time_tokens_user_id_token_type_key; Type: INDEX; Schema: auth; Owner: -
--

CREATE UNIQUE INDEX one_time_tokens_user_id_token_type_key ON auth.one_time_tokens USING btree (user_id, token_type);


--
-- Name: reauthentication_token_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE UNIQUE INDEX reauthentication_token_idx ON auth.users USING btree (reauthentication_token) WHERE ((reauthentication_token)::text !~ '^[0-9 ]*$'::text);


--
-- Name: recovery_token_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE UNIQUE INDEX recovery_token_idx ON auth.users USING btree (recovery_token) WHERE ((recovery_token)::text !~ '^[0-9 ]*$'::text);


--
-- Name: refresh_tokens_instance_id_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX refresh_tokens_instance_id_idx ON auth.refresh_tokens USING btree (instance_id);


--
-- Name: refresh_tokens_instance_id_user_id_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX refresh_tokens_instance_id_user_id_idx ON auth.refresh_tokens USING btree (instance_id, user_id);


--
-- Name: refresh_tokens_parent_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX refresh_tokens_parent_idx ON auth.refresh_tokens USING btree (parent);


--
-- Name: refresh_tokens_session_id_revoked_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX refresh_tokens_session_id_revoked_idx ON auth.refresh_tokens USING btree (session_id, revoked);


--
-- Name: refresh_tokens_updated_at_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX refresh_tokens_updated_at_idx ON auth.refresh_tokens USING btree (updated_at DESC);


--
-- Name: saml_providers_sso_provider_id_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX saml_providers_sso_provider_id_idx ON auth.saml_providers USING btree (sso_provider_id);


--
-- Name: saml_relay_states_created_at_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX saml_relay_states_created_at_idx ON auth.saml_relay_states USING btree (created_at DESC);


--
-- Name: saml_relay_states_for_email_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX saml_relay_states_for_email_idx ON auth.saml_relay_states USING btree (for_email);


--
-- Name: saml_relay_states_sso_provider_id_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX saml_relay_states_sso_provider_id_idx ON auth.saml_relay_states USING btree (sso_provider_id);


--
-- Name: sessions_not_after_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX sessions_not_after_idx ON auth.sessions USING btree (not_after DESC);


--
-- Name: sessions_oauth_client_id_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX sessions_oauth_client_id_idx ON auth.sessions USING btree (oauth_client_id);


--
-- Name: sessions_user_id_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX sessions_user_id_idx ON auth.sessions USING btree (user_id);


--
-- Name: sso_domains_domain_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE UNIQUE INDEX sso_domains_domain_idx ON auth.sso_domains USING btree (lower(domain));


--
-- Name: sso_domains_sso_provider_id_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX sso_domains_sso_provider_id_idx ON auth.sso_domains USING btree (sso_provider_id);


--
-- Name: sso_providers_resource_id_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE UNIQUE INDEX sso_providers_resource_id_idx ON auth.sso_providers USING btree (lower(resource_id));


--
-- Name: sso_providers_resource_id_pattern_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX sso_providers_resource_id_pattern_idx ON auth.sso_providers USING btree (resource_id text_pattern_ops);


--
-- Name: unique_phone_factor_per_user; Type: INDEX; Schema: auth; Owner: -
--

CREATE UNIQUE INDEX unique_phone_factor_per_user ON auth.mfa_factors USING btree (user_id, phone);


--
-- Name: user_id_created_at_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX user_id_created_at_idx ON auth.sessions USING btree (user_id, created_at);


--
-- Name: users_email_partial_key; Type: INDEX; Schema: auth; Owner: -
--

CREATE UNIQUE INDEX users_email_partial_key ON auth.users USING btree (email) WHERE (is_sso_user = false);


--
-- Name: INDEX users_email_partial_key; Type: COMMENT; Schema: auth; Owner: -
--

COMMENT ON INDEX auth.users_email_partial_key IS 'Auth: A partial unique index that applies only when is_sso_user is false';


--
-- Name: users_instance_id_email_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX users_instance_id_email_idx ON auth.users USING btree (instance_id, lower((email)::text));


--
-- Name: users_instance_id_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX users_instance_id_idx ON auth.users USING btree (instance_id);


--
-- Name: users_is_anonymous_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX users_is_anonymous_idx ON auth.users USING btree (is_anonymous);


--
-- Name: webauthn_challenges_expires_at_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX webauthn_challenges_expires_at_idx ON auth.webauthn_challenges USING btree (expires_at);


--
-- Name: webauthn_challenges_user_id_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX webauthn_challenges_user_id_idx ON auth.webauthn_challenges USING btree (user_id);


--
-- Name: webauthn_credentials_credential_id_key; Type: INDEX; Schema: auth; Owner: -
--

CREATE UNIQUE INDEX webauthn_credentials_credential_id_key ON auth.webauthn_credentials USING btree (credential_id);


--
-- Name: webauthn_credentials_user_id_idx; Type: INDEX; Schema: auth; Owner: -
--

CREATE INDEX webauthn_credentials_user_id_idx ON auth.webauthn_credentials USING btree (user_id);


--
-- Name: ethnicities_active_country_sort_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ethnicities_active_country_sort_idx ON public.ethnicities USING btree (is_active, country_code, sort_order, name);


--
-- Name: ethnicities_code_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ethnicities_code_idx ON public.ethnicities USING btree (code);


--
-- Name: idx_active_boosts_expires_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_active_boosts_expires_at ON public.active_boosts USING btree (expires_at);


--
-- Name: idx_active_boosts_user_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_active_boosts_user_expiry ON public.active_boosts USING btree (user_id, expires_at);


--
-- Name: idx_addresses_coords; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_addresses_coords ON public.addresses USING gist (coords);


--
-- Name: idx_addresses_location_place_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_addresses_location_place_id ON public.addresses USING btree (location_place_id);


--
-- Name: idx_addresses_location_updated_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_addresses_location_updated_at ON public.addresses USING btree (location_updated_at DESC);


--
-- Name: idx_app_users_last_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_users_last_active ON public.app_users USING btree (last_active_at DESC);


--
-- Name: idx_app_users_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_users_status ON public.app_users USING btree (status);


--
-- Name: idx_audit_log_actor; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_log_actor ON public.audit_log USING btree (actor_user_id, created_at DESC);


--
-- Name: idx_audit_log_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_log_target ON public.audit_log USING btree (target_table, target_id, created_at DESC);


--
-- Name: idx_auth_anon_tasks_retry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_auth_anon_tasks_retry ON public.auth_anonymization_tasks USING btree (next_retry_at) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: idx_chat_attachments_message; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_attachments_message ON public.chat_attachments USING btree (message_id);


--
-- Name: idx_chat_outbox_claim_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_outbox_claim_pending ON public.chat_outbox_events USING btree (available_at, created_at) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: idx_chat_outbox_failed; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_outbox_failed ON public.chat_outbox_events USING btree (created_at DESC) WHERE ((status)::text = 'FAILED'::text);


--
-- Name: idx_chat_outbox_match_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_outbox_match_created ON public.chat_outbox_events USING btree (match_id, created_at DESC);


--
-- Name: idx_chat_outbox_processing_lease; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_outbox_processing_lease ON public.chat_outbox_events USING btree (lease_expires_at) WHERE ((status)::text = 'PROCESSING'::text);


--
-- Name: idx_credit_consumptions_lot; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_credit_consumptions_lot ON public.user_entitlement_credit_consumptions USING btree (credit_lot_id);


--
-- Name: idx_credit_lots_user_type_remaining; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_credit_lots_user_type_remaining ON public.user_entitlement_credit_lots USING btree (user_id, entitlement_type, expires_at) WHERE (quantity_remaining > 0);


--
-- Name: idx_discovery_actions_actor_pass_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_discovery_actions_actor_pass_active ON public.user_discovery_actions USING btree (actor_user_id, created_at DESC) WHERE (((action_type)::text = 'PASS'::text) AND ((status)::text = 'ACTIVE'::text));


--
-- Name: idx_discovery_actions_actor_rewind_stack; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_discovery_actions_actor_rewind_stack ON public.user_discovery_actions USING btree (actor_user_id, status, created_at DESC);


--
-- Name: idx_discovery_actions_target_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_discovery_actions_target_active ON public.user_discovery_actions USING btree (target_user_id, status, created_at DESC);


--
-- Name: idx_entitlement_ledger_user_type_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_entitlement_ledger_user_type_created ON public.user_entitlement_ledger USING btree (user_id, entitlement_type, created_at DESC);


--
-- Name: idx_imr_image_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_imr_image_hash ON public.image_moderation_results USING btree (image_hash) WHERE (image_hash IS NOT NULL);


--
-- Name: idx_imr_profile_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_imr_profile_id ON public.image_moderation_results USING btree (profile_id);


--
-- Name: idx_imr_retry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_imr_retry ON public.image_moderation_results USING btree (retry_after) WHERE ((status)::text = 'ERROR'::text);


--
-- Name: idx_imr_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_imr_status ON public.image_moderation_results USING btree (status);


--
-- Name: idx_location_places_active_country_city; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_location_places_active_country_city ON public.location_places USING btree (country_code, city) WHERE (is_active = true);


--
-- Name: idx_location_places_alternative_names_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_location_places_alternative_names_trgm ON public.location_places USING gin (lower(COALESCE(alternative_names, ''::text)) public.gin_trgm_ops) WHERE (is_active = true);


--
-- Name: idx_location_places_city_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_location_places_city_trgm ON public.location_places USING gin (lower((city)::text) public.gin_trgm_ops) WHERE (is_active = true);


--
-- Name: idx_location_places_coords; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_location_places_coords ON public.location_places USING gist (coords);


--
-- Name: idx_location_places_display_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_location_places_display_trgm ON public.location_places USING gin (lower(display_name) public.gin_trgm_ops) WHERE (is_active = true);


--
-- Name: idx_match_notification_settings_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_match_notification_settings_user ON public.match_notification_settings USING btree (user_id, muted_until);


--
-- Name: idx_matches_status_matched_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_matches_status_matched_at ON public.matches USING btree (status, matched_at DESC);


--
-- Name: idx_matches_user_one_active_inbox; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_matches_user_one_active_inbox ON public.matches USING btree (user_one_id, last_message_at DESC NULLS LAST, matched_at DESC, id DESC) WHERE ((status)::text = 'ACTIVE'::text);


--
-- Name: idx_matches_user_one_status_last_message; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_matches_user_one_status_last_message ON public.matches USING btree (user_one_id, status, last_message_at DESC);


--
-- Name: idx_matches_user_two_active_inbox; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_matches_user_two_active_inbox ON public.matches USING btree (user_two_id, last_message_at DESC NULLS LAST, matched_at DESC, id DESC) WHERE ((status)::text = 'ACTIVE'::text);


--
-- Name: idx_matches_user_two_status_last_message; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_matches_user_two_status_last_message ON public.matches USING btree (user_two_id, status, last_message_at DESC);


--
-- Name: idx_messages_match_sender_visible_sequence; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_match_sender_visible_sequence ON public.messages USING btree (match_id, sender_user_id, sequence_number) WHERE ((deleted_at IS NULL) AND ((moderation_status)::text = 'APPROVED'::text));


--
-- Name: idx_messages_match_sequence; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_match_sequence ON public.messages USING btree (match_id, sequence_number DESC) WHERE (deleted_at IS NULL);


--
-- Name: idx_messages_moderation_scan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_moderation_scan ON public.messages USING btree (moderation_status, created_at DESC);


--
-- Name: idx_messages_sender_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_sender_created ON public.messages USING btree (sender_user_id, created_at DESC);


--
-- Name: idx_notification_deliveries_claim_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_deliveries_claim_pending ON public.notification_deliveries USING btree (available_at, created_at) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: idx_notification_deliveries_processing_lease; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_deliveries_processing_lease ON public.notification_deliveries USING btree (lease_expires_at) WHERE ((status)::text = 'PROCESSING'::text);


--
-- Name: idx_notification_deliveries_receipt_check; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_deliveries_receipt_check ON public.notification_deliveries USING btree (next_receipt_check_at) WHERE ((status)::text = 'SUBMITTED'::text);


--
-- Name: idx_notification_devices_active_environment; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_devices_active_environment ON public.notification_devices USING btree (user_id, app_environment) WHERE (is_active = true);


--
-- Name: idx_notification_devices_user_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_devices_user_active ON public.notification_devices USING btree (user_id) WHERE (is_active = true);


--
-- Name: idx_notification_outbox_campaign; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_outbox_campaign ON public.notification_outbox_events USING btree (campaign_id, created_at) WHERE (campaign_id IS NOT NULL);


--
-- Name: idx_notification_outbox_claim_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_outbox_claim_pending ON public.notification_outbox_events USING btree (available_at, created_at) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: idx_notification_outbox_processing_lease; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_outbox_processing_lease ON public.notification_outbox_events USING btree (lease_expires_at) WHERE ((status)::text = 'PROCESSING'::text);


--
-- Name: idx_notification_outbox_recipient_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_outbox_recipient_created ON public.notification_outbox_events USING btree (recipient_user_id, created_at DESC);


--
-- Name: idx_payment_events_subscription; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_events_subscription ON public.payment_events USING btree (subscription_id, created_at DESC);


--
-- Name: idx_payment_methods_country_platform_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_methods_country_platform_active ON public.payment_methods USING btree (country_code, platform) WHERE (is_active = true);


--
-- Name: idx_payment_offers_country_platform_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_offers_country_platform_active ON public.payment_offers USING btree (country_code, platform) WHERE (is_active = true);


--
-- Name: idx_payment_orders_idempotency; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_payment_orders_idempotency ON public.payment_orders USING btree (user_id, idempotency_key) WHERE (idempotency_key IS NOT NULL);


--
-- Name: idx_payment_orders_method_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_orders_method_id ON public.payment_orders USING btree (payment_method_id);


--
-- Name: idx_payment_orders_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_orders_status_created ON public.payment_orders USING btree (status, created_at DESC);


--
-- Name: idx_payment_orders_user_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_orders_user_status ON public.payment_orders USING btree (user_id, status);


--
-- Name: idx_payment_proofs_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_proofs_order ON public.payment_proofs USING btree (payment_order_id);


--
-- Name: idx_payment_verification_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_verification_order ON public.payment_verification_attempts USING btree (payment_order_id);


--
-- Name: idx_profile_photos_approved_primary; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profile_photos_approved_primary ON public.profile_photos USING btree (user_id) WHERE ((deleted_at IS NULL) AND (is_primary = true) AND ((moderation_status)::text = 'APPROVED'::text));


--
-- Name: idx_profile_photos_moderation_queue; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profile_photos_moderation_queue ON public.profile_photos USING btree (moderation_status, created_at) WHERE (deleted_at IS NULL);


--
-- Name: idx_profile_photos_user_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profile_photos_user_order ON public.profile_photos USING btree (user_id, photo_order) WHERE (deleted_at IS NULL);


--
-- Name: idx_profile_prompt_answers_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profile_prompt_answers_user ON public.profile_prompt_answers USING btree (user_id);


--
-- Name: idx_profile_prompts_active_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profile_prompts_active_order ON public.profile_prompts USING btree (is_active, display_order);


--
-- Name: idx_profiles_date_of_birth; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profiles_date_of_birth ON public.profiles USING btree (date_of_birth);


--
-- Name: idx_profiles_discovery_bundle; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profiles_discovery_bundle ON public.profiles USING btree (gender, residency_type, date_of_birth) WHERE ((is_visible = true) AND (is_onboarded = true));


--
-- Name: idx_profiles_verified_discovery; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profiles_verified_discovery ON public.profiles USING btree (is_verified) WHERE ((is_visible = true) AND (is_onboarded = true));


--
-- Name: idx_promotion_campaigns_country_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_promotion_campaigns_country_status ON public.promotion_campaigns USING btree (country_code, status);


--
-- Name: idx_promotion_campaigns_gender_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_promotion_campaigns_gender_status ON public.promotion_campaigns USING btree (target_gender, status) WHERE (target_gender IS NOT NULL);


--
-- Name: idx_promotion_campaigns_key; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_promotion_campaigns_key ON public.promotion_campaigns USING btree (campaign_key);


--
-- Name: idx_promotion_campaigns_product_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_promotion_campaigns_product_status ON public.promotion_campaigns USING btree (subscription_product_id, status);


--
-- Name: idx_promotion_campaigns_status_dates; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_promotion_campaigns_status_dates ON public.promotion_campaigns USING btree (status, starts_at, ends_at);


--
-- Name: idx_promotion_redemptions_campaign; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_promotion_redemptions_campaign ON public.promotion_redemptions USING btree (campaign_id);


--
-- Name: idx_promotion_redemptions_campaign_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_promotion_redemptions_campaign_user ON public.promotion_redemptions USING btree (campaign_id, user_id);


--
-- Name: idx_promotion_redemptions_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_promotion_redemptions_order ON public.promotion_redemptions USING btree (payment_order_id) WHERE (payment_order_id IS NOT NULL);


--
-- Name: idx_promotion_redemptions_stale; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_promotion_redemptions_stale ON public.promotion_redemptions USING btree (status, reserved_at) WHERE ((status)::text = ANY ((ARRAY['RESERVED'::character varying, 'PROVIDER_PENDING'::character varying])::text[]));


--
-- Name: idx_promotion_redemptions_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_promotion_redemptions_status ON public.promotion_redemptions USING btree (status);


--
-- Name: idx_promotion_redemptions_subscription; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_promotion_redemptions_subscription ON public.promotion_redemptions USING btree (subscription_id) WHERE (subscription_id IS NOT NULL);


--
-- Name: idx_promotion_redemptions_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_promotion_redemptions_user ON public.promotion_redemptions USING btree (user_id);


--
-- Name: idx_pva_verify_et_request_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pva_verify_et_request_id ON public.payment_verification_attempts USING btree (verify_et_request_id) WHERE (verify_et_request_id IS NOT NULL);


--
-- Name: idx_reports_reported_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reports_reported_user ON public.user_reports USING btree (reported_user_id, created_at DESC);


--
-- Name: idx_reports_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reports_status_created ON public.user_reports USING btree (status, created_at DESC);


--
-- Name: idx_subscription_plan_limits_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_subscription_plan_limits_type ON public.subscription_plan_limits USING btree (limit_type, plan_id);


--
-- Name: idx_subscription_plans_plan_kind; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_subscription_plans_plan_kind ON public.subscription_plans USING btree (plan_kind);


--
-- Name: idx_support_attachment_message; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_support_attachment_message ON public.support_attachments USING btree (message_id);


--
-- Name: idx_support_conv_assigned_staff; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_support_conv_assigned_staff ON public.support_conversations USING btree (assigned_staff_user_id, status, last_activity_at DESC NULLS LAST) WHERE (assigned_staff_user_id IS NOT NULL);


--
-- Name: idx_support_conv_waiting_staff_queue; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_support_conv_waiting_staff_queue ON public.support_conversations USING btree (priority, waiting_since, id) WHERE ((status)::text = 'WAITING_STAFF'::text);


--
-- Name: idx_support_conv_waiting_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_support_conv_waiting_user ON public.support_conversations USING btree (assigned_staff_user_id, last_public_message_at DESC, id) WHERE ((status)::text = 'WAITING_USER'::text);


--
-- Name: idx_support_internal_note_conversation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_support_internal_note_conversation ON public.support_internal_notes USING btree (conversation_id, created_at DESC, id);


--
-- Name: idx_support_msg_conv_sender_sequence; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_support_msg_conv_sender_sequence ON public.support_messages USING btree (conversation_id, sender_type, sequence_number DESC);


--
-- Name: idx_support_staff_reads_staff; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_support_staff_reads_staff ON public.support_conversation_staff_reads USING btree (staff_user_id, last_read_at DESC);


--
-- Name: idx_transactions_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_transactions_status_created ON public.transactions USING btree (status, created_at DESC);


--
-- Name: idx_transactions_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_transactions_user ON public.transactions USING btree (user_id, created_at DESC);


--
-- Name: idx_unique_verified_provider_reference; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_unique_verified_provider_reference ON public.payment_verification_attempts USING btree (verification_method, provider_verification_reference) WHERE (((status)::text = 'VERIFIED'::text) AND (provider_verification_reference IS NOT NULL));


--
-- Name: idx_user_blocks_reverse_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_blocks_reverse_active ON public.user_blocks USING btree (blocked_user_id, blocker_user_id) WHERE ((status)::text = 'ACTIVE'::text);


--
-- Name: idx_user_daily_limits_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_daily_limits_date ON public.user_daily_limits USING btree (limit_date);


--
-- Name: idx_user_subscriptions_user_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_subscriptions_user_status ON public.user_subscriptions USING btree (user_id, status);


--
-- Name: idx_user_verifications_status_submitted; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_verifications_status_submitted ON public.user_verifications USING btree (status, submitted_at);


--
-- Name: idx_user_verifications_user_submitted; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_verifications_user_submitted ON public.user_verifications USING btree (user_id, submitted_at DESC);


--
-- Name: languages_active_country_sort_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX languages_active_country_sort_idx ON public.languages USING btree (is_active, country_code, sort_order, name);


--
-- Name: languages_code_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX languages_code_idx ON public.languages USING btree (code);


--
-- Name: profiles_ethnicity_ids_gin_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX profiles_ethnicity_ids_gin_idx ON public.profiles USING gin (ethnicity_ids);


--
-- Name: profiles_language_ids_gin_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX profiles_language_ids_gin_idx ON public.profiles USING gin (language_ids);


--
-- Name: unique_active_block_per_direction; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX unique_active_block_per_direction ON public.user_blocks USING btree (blocker_user_id, blocked_user_id) WHERE ((status)::text = 'ACTIVE'::text);


--
-- Name: unique_active_discovery_action_per_pair; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX unique_active_discovery_action_per_pair ON public.user_discovery_actions USING btree (actor_user_id, target_user_id) WHERE ((status)::text = 'ACTIVE'::text);


--
-- Name: unique_active_free_plan_per_country; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX unique_active_free_plan_per_country ON public.subscription_plans USING btree (country_code) WHERE (((plan_kind)::text = 'FREE'::text) AND (is_active = true));


--
-- Name: unique_active_match_pair; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX unique_active_match_pair ON public.matches USING btree (user_one_id, user_two_id) WHERE ((status)::text = 'ACTIVE'::text);


--
-- Name: unique_active_notification_installation; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX unique_active_notification_installation ON public.notification_devices USING btree (app_environment, installation_id) WHERE ((installation_id IS NOT NULL) AND (is_active = true));


--
-- Name: unique_active_online_payment_per_market; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX unique_active_online_payment_per_market ON public.payment_methods USING btree (country_code, platform) WHERE (((payment_channel)::text = 'ONLINE_PAYMENT'::text) AND (is_active = true));


--
-- Name: unique_active_primary_photo_per_user; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX unique_active_primary_photo_per_user ON public.profile_photos USING btree (user_id) WHERE ((is_primary = true) AND (deleted_at IS NULL));


--
-- Name: unique_active_profile_photo_order; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX unique_active_profile_photo_order ON public.profile_photos USING btree (user_id, photo_order) WHERE (deleted_at IS NULL);


--
-- Name: unique_active_subscription_per_user; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX unique_active_subscription_per_user ON public.user_subscriptions USING btree (user_id) WHERE ((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'PENDING_VERIFICATION'::character varying])::text[]));


--
-- Name: unique_entitlement_idempotency_key_per_user; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX unique_entitlement_idempotency_key_per_user ON public.user_entitlement_ledger USING btree (user_id, idempotency_key) WHERE (idempotency_key IS NOT NULL);


--
-- Name: unique_manual_transfer_reference_per_method; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX unique_manual_transfer_reference_per_method ON public.payment_orders USING btree (payment_method_id, manual_payment_reference_normalized) WHERE ((manual_payment_reference_normalized IS NOT NULL) AND ((status)::text <> ALL ((ARRAY['CANCELLED'::character varying, 'EXPIRED'::character varying])::text[])));


--
-- Name: unique_notification_delivery_provider_ticket; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX unique_notification_delivery_provider_ticket ON public.notification_deliveries USING btree (provider_ticket_id) WHERE (provider_ticket_id IS NOT NULL);


--
-- Name: unique_payment_offer_consumable; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX unique_payment_offer_consumable ON public.payment_offers USING btree (country_code, platform, consumable_product_id) WHERE (consumable_product_id IS NOT NULL);


--
-- Name: unique_payment_offer_subscription; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX unique_payment_offer_subscription ON public.payment_offers USING btree (country_code, platform, subscription_product_id) WHERE (subscription_product_id IS NOT NULL);


--
-- Name: unique_provider_payment_event; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX unique_provider_payment_event ON public.payment_events USING btree (provider, provider_event_id);


--
-- Name: unique_provider_subscription_reference; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX unique_provider_subscription_reference ON public.user_subscriptions USING btree (provider, provider_subscription_id) WHERE (provider_subscription_id IS NOT NULL);


--
-- Name: unique_provider_transaction_reference; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX unique_provider_transaction_reference ON public.transactions USING btree (provider, provider_transaction_id) WHERE (provider_transaction_id IS NOT NULL);


--
-- Name: ix_realtime_subscription_entity; Type: INDEX; Schema: realtime; Owner: -
--

CREATE INDEX ix_realtime_subscription_entity ON realtime.subscription USING btree (entity);


--
-- Name: messages_inserted_at_topic_index; Type: INDEX; Schema: realtime; Owner: -
--

CREATE INDEX messages_inserted_at_topic_index ON ONLY realtime.messages USING btree (inserted_at DESC, topic) WHERE ((extension = 'broadcast'::text) AND (private IS TRUE));


--
-- Name: messages_2026_07_22_inserted_at_topic_idx; Type: INDEX; Schema: realtime; Owner: -
--

CREATE INDEX messages_2026_07_22_inserted_at_topic_idx ON realtime.messages_2026_07_22 USING btree (inserted_at DESC, topic) WHERE ((extension = 'broadcast'::text) AND (private IS TRUE));


--
-- Name: messages_2026_07_23_inserted_at_topic_idx; Type: INDEX; Schema: realtime; Owner: -
--

CREATE INDEX messages_2026_07_23_inserted_at_topic_idx ON realtime.messages_2026_07_23 USING btree (inserted_at DESC, topic) WHERE ((extension = 'broadcast'::text) AND (private IS TRUE));


--
-- Name: messages_2026_07_24_inserted_at_topic_idx; Type: INDEX; Schema: realtime; Owner: -
--

CREATE INDEX messages_2026_07_24_inserted_at_topic_idx ON realtime.messages_2026_07_24 USING btree (inserted_at DESC, topic) WHERE ((extension = 'broadcast'::text) AND (private IS TRUE));


--
-- Name: messages_2026_07_25_inserted_at_topic_idx; Type: INDEX; Schema: realtime; Owner: -
--

CREATE INDEX messages_2026_07_25_inserted_at_topic_idx ON realtime.messages_2026_07_25 USING btree (inserted_at DESC, topic) WHERE ((extension = 'broadcast'::text) AND (private IS TRUE));


--
-- Name: messages_2026_07_26_inserted_at_topic_idx; Type: INDEX; Schema: realtime; Owner: -
--

CREATE INDEX messages_2026_07_26_inserted_at_topic_idx ON realtime.messages_2026_07_26 USING btree (inserted_at DESC, topic) WHERE ((extension = 'broadcast'::text) AND (private IS TRUE));


--
-- Name: messages_2026_07_27_inserted_at_topic_idx; Type: INDEX; Schema: realtime; Owner: -
--

CREATE INDEX messages_2026_07_27_inserted_at_topic_idx ON realtime.messages_2026_07_27 USING btree (inserted_at DESC, topic) WHERE ((extension = 'broadcast'::text) AND (private IS TRUE));


--
-- Name: messages_2026_07_28_inserted_at_topic_idx; Type: INDEX; Schema: realtime; Owner: -
--

CREATE INDEX messages_2026_07_28_inserted_at_topic_idx ON realtime.messages_2026_07_28 USING btree (inserted_at DESC, topic) WHERE ((extension = 'broadcast'::text) AND (private IS TRUE));


--
-- Name: messages_2026_07_29_inserted_at_topic_idx; Type: INDEX; Schema: realtime; Owner: -
--

CREATE INDEX messages_2026_07_29_inserted_at_topic_idx ON realtime.messages_2026_07_29 USING btree (inserted_at DESC, topic) WHERE ((extension = 'broadcast'::text) AND (private IS TRUE));


--
-- Name: subscription_subscription_id_entity_filters_action_filter_selec; Type: INDEX; Schema: realtime; Owner: -
--

CREATE UNIQUE INDEX subscription_subscription_id_entity_filters_action_filter_selec ON realtime.subscription USING btree (subscription_id, entity, filters, action_filter, COALESCE(selected_columns, '{}'::text[]));


--
-- Name: bname; Type: INDEX; Schema: storage; Owner: -
--

CREATE UNIQUE INDEX bname ON storage.buckets USING btree (name);


--
-- Name: bucketid_objname; Type: INDEX; Schema: storage; Owner: -
--

CREATE UNIQUE INDEX bucketid_objname ON storage.objects USING btree (bucket_id, name);


--
-- Name: buckets_analytics_unique_name_idx; Type: INDEX; Schema: storage; Owner: -
--

CREATE UNIQUE INDEX buckets_analytics_unique_name_idx ON storage.buckets_analytics USING btree (name) WHERE (deleted_at IS NULL);


--
-- Name: idx_multipart_uploads_list; Type: INDEX; Schema: storage; Owner: -
--

CREATE INDEX idx_multipart_uploads_list ON storage.s3_multipart_uploads USING btree (bucket_id, key, created_at);


--
-- Name: idx_objects_bucket_id_name; Type: INDEX; Schema: storage; Owner: -
--

CREATE INDEX idx_objects_bucket_id_name ON storage.objects USING btree (bucket_id, name COLLATE "C");


--
-- Name: idx_objects_bucket_id_name_lower; Type: INDEX; Schema: storage; Owner: -
--

CREATE INDEX idx_objects_bucket_id_name_lower ON storage.objects USING btree (bucket_id, lower(name) COLLATE "C");


--
-- Name: name_prefix_search; Type: INDEX; Schema: storage; Owner: -
--

CREATE INDEX name_prefix_search ON storage.objects USING btree (name text_pattern_ops);


--
-- Name: vector_indexes_name_bucket_id_idx; Type: INDEX; Schema: storage; Owner: -
--

CREATE UNIQUE INDEX vector_indexes_name_bucket_id_idx ON storage.vector_indexes USING btree (name, bucket_id);


--
-- Name: messages_2026_07_22_inserted_at_topic_idx; Type: INDEX ATTACH; Schema: realtime; Owner: -
--

ALTER INDEX realtime.messages_inserted_at_topic_index ATTACH PARTITION realtime.messages_2026_07_22_inserted_at_topic_idx;


--
-- Name: messages_2026_07_22_pkey; Type: INDEX ATTACH; Schema: realtime; Owner: -
--

ALTER INDEX realtime.messages_pkey ATTACH PARTITION realtime.messages_2026_07_22_pkey;


--
-- Name: messages_2026_07_23_inserted_at_topic_idx; Type: INDEX ATTACH; Schema: realtime; Owner: -
--

ALTER INDEX realtime.messages_inserted_at_topic_index ATTACH PARTITION realtime.messages_2026_07_23_inserted_at_topic_idx;


--
-- Name: messages_2026_07_23_pkey; Type: INDEX ATTACH; Schema: realtime; Owner: -
--

ALTER INDEX realtime.messages_pkey ATTACH PARTITION realtime.messages_2026_07_23_pkey;


--
-- Name: messages_2026_07_24_inserted_at_topic_idx; Type: INDEX ATTACH; Schema: realtime; Owner: -
--

ALTER INDEX realtime.messages_inserted_at_topic_index ATTACH PARTITION realtime.messages_2026_07_24_inserted_at_topic_idx;


--
-- Name: messages_2026_07_24_pkey; Type: INDEX ATTACH; Schema: realtime; Owner: -
--

ALTER INDEX realtime.messages_pkey ATTACH PARTITION realtime.messages_2026_07_24_pkey;


--
-- Name: messages_2026_07_25_inserted_at_topic_idx; Type: INDEX ATTACH; Schema: realtime; Owner: -
--

ALTER INDEX realtime.messages_inserted_at_topic_index ATTACH PARTITION realtime.messages_2026_07_25_inserted_at_topic_idx;


--
-- Name: messages_2026_07_25_pkey; Type: INDEX ATTACH; Schema: realtime; Owner: -
--

ALTER INDEX realtime.messages_pkey ATTACH PARTITION realtime.messages_2026_07_25_pkey;


--
-- Name: messages_2026_07_26_inserted_at_topic_idx; Type: INDEX ATTACH; Schema: realtime; Owner: -
--

ALTER INDEX realtime.messages_inserted_at_topic_index ATTACH PARTITION realtime.messages_2026_07_26_inserted_at_topic_idx;


--
-- Name: messages_2026_07_26_pkey; Type: INDEX ATTACH; Schema: realtime; Owner: -
--

ALTER INDEX realtime.messages_pkey ATTACH PARTITION realtime.messages_2026_07_26_pkey;


--
-- Name: messages_2026_07_27_inserted_at_topic_idx; Type: INDEX ATTACH; Schema: realtime; Owner: -
--

ALTER INDEX realtime.messages_inserted_at_topic_index ATTACH PARTITION realtime.messages_2026_07_27_inserted_at_topic_idx;


--
-- Name: messages_2026_07_27_pkey; Type: INDEX ATTACH; Schema: realtime; Owner: -
--

ALTER INDEX realtime.messages_pkey ATTACH PARTITION realtime.messages_2026_07_27_pkey;


--
-- Name: messages_2026_07_28_inserted_at_topic_idx; Type: INDEX ATTACH; Schema: realtime; Owner: -
--

ALTER INDEX realtime.messages_inserted_at_topic_index ATTACH PARTITION realtime.messages_2026_07_28_inserted_at_topic_idx;


--
-- Name: messages_2026_07_28_pkey; Type: INDEX ATTACH; Schema: realtime; Owner: -
--

ALTER INDEX realtime.messages_pkey ATTACH PARTITION realtime.messages_2026_07_28_pkey;


--
-- Name: messages_2026_07_29_inserted_at_topic_idx; Type: INDEX ATTACH; Schema: realtime; Owner: -
--

ALTER INDEX realtime.messages_inserted_at_topic_index ATTACH PARTITION realtime.messages_2026_07_29_inserted_at_topic_idx;


--
-- Name: messages_2026_07_29_pkey; Type: INDEX ATTACH; Schema: realtime; Owner: -
--

ALTER INDEX realtime.messages_pkey ATTACH PARTITION realtime.messages_2026_07_29_pkey;


--
-- Name: users on_auth_user_created; Type: TRIGGER; Schema: auth; Owner: -
--

CREATE TRIGGER on_auth_user_created AFTER INSERT ON auth.users FOR EACH ROW EXECUTE FUNCTION public.handle_new_auth_user();


--
-- Name: profile_photos auto_set_visible_on_primary_photo_approval; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER auto_set_visible_on_primary_photo_approval AFTER INSERT OR UPDATE OF moderation_status, is_primary, deleted_at ON public.profile_photos FOR EACH ROW EXECUTE FUNCTION public.auto_set_visible_on_primary_photo_approval();


--
-- Name: app_users create_default_notification_preferences_after_user_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER create_default_notification_preferences_after_user_insert AFTER INSERT ON public.app_users FOR EACH ROW EXECUTE FUNCTION public.create_default_notification_preferences();


--
-- Name: app_users create_default_support_conversation_after_user_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER create_default_support_conversation_after_user_insert AFTER INSERT ON public.app_users FOR EACH ROW EXECUTE FUNCTION public.create_default_support_conversation();


--
-- Name: user_blocks end_active_matches_when_blocked; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER end_active_matches_when_blocked AFTER INSERT OR UPDATE OF status ON public.user_blocks FOR EACH ROW EXECUTE FUNCTION public.end_active_matches_when_blocked();


--
-- Name: user_discovery_actions enforce_discovery_action_immutability; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER enforce_discovery_action_immutability BEFORE UPDATE ON public.user_discovery_actions FOR EACH ROW EXECUTE FUNCTION public.enforce_discovery_action_immutability();


--
-- Name: matches enforce_match_immutability; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER enforce_match_immutability BEFORE UPDATE ON public.matches FOR EACH ROW EXECUTE FUNCTION public.enforce_match_immutability();


--
-- Name: messages enforce_message_identity_immutability; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER enforce_message_identity_immutability BEFORE UPDATE ON public.messages FOR EACH ROW EXECUTE FUNCTION public.enforce_message_identity_immutability();


--
-- Name: notification_campaigns enforce_notification_campaign_lifecycle; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER enforce_notification_campaign_lifecycle BEFORE UPDATE ON public.notification_campaigns FOR EACH ROW EXECUTE FUNCTION public.enforce_notification_campaign_lifecycle();


--
-- Name: profiles enforce_profile_age_compliance; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER enforce_profile_age_compliance BEFORE INSERT OR UPDATE OF date_of_birth ON public.profiles FOR EACH ROW EXECUTE FUNCTION public.verify_profile_age_compliance();


--
-- Name: audit_log prevent_audit_log_mutation; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER prevent_audit_log_mutation BEFORE DELETE OR UPDATE ON public.audit_log FOR EACH ROW EXECUTE FUNCTION public.prevent_audit_log_mutation();


--
-- Name: ethnicities set_ethnicities_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_ethnicities_updated_at BEFORE UPDATE ON public.ethnicities FOR EACH ROW EXECUTE FUNCTION public.set_ethnicities_updated_at();


--
-- Name: languages set_languages_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_languages_updated_at BEFORE UPDATE ON public.languages FOR EACH ROW EXECUTE FUNCTION public.set_languages_updated_at();


--
-- Name: support_conversations set_support_conversations_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_support_conversations_updated_at BEFORE UPDATE ON public.support_conversations FOR EACH ROW EXECUTE FUNCTION public.set_support_updated_at();


--
-- Name: support_conversation_staff_reads set_support_staff_reads_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_support_staff_reads_updated_at BEFORE UPDATE ON public.support_conversation_staff_reads FOR EACH ROW EXECUTE FUNCTION public.set_support_updated_at();


--
-- Name: addresses set_timestamp_addresses; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_addresses BEFORE UPDATE ON public.addresses FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: app_users set_timestamp_app_users; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_app_users BEFORE UPDATE ON public.app_users FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: discovery_preferences set_timestamp_discovery_preferences; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_discovery_preferences BEFORE UPDATE ON public.discovery_preferences FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: location_places set_timestamp_location_places; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_location_places BEFORE UPDATE ON public.location_places FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: match_notification_settings set_timestamp_match_notification_settings; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_match_notification_settings BEFORE UPDATE ON public.match_notification_settings FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: matches set_timestamp_matches; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_matches BEFORE UPDATE ON public.matches FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: messages set_timestamp_messages; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_messages BEFORE UPDATE ON public.messages FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: notification_campaigns set_timestamp_notification_campaigns; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_notification_campaigns BEFORE UPDATE ON public.notification_campaigns FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: notification_deliveries set_timestamp_notification_deliveries; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_notification_deliveries BEFORE UPDATE ON public.notification_deliveries FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: notification_devices set_timestamp_notification_devices; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_notification_devices BEFORE UPDATE ON public.notification_devices FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: profile_photos set_timestamp_profile_photos; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_profile_photos BEFORE UPDATE ON public.profile_photos FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: profile_prompt_answers set_timestamp_profile_prompt_answers; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_profile_prompt_answers BEFORE UPDATE ON public.profile_prompt_answers FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: profile_prompt_translations set_timestamp_profile_prompt_translations; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_profile_prompt_translations BEFORE UPDATE ON public.profile_prompt_translations FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: profile_prompts set_timestamp_profile_prompts; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_profile_prompts BEFORE UPDATE ON public.profile_prompts FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: profiles set_timestamp_profiles; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_profiles BEFORE UPDATE ON public.profiles FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: subscription_plan_limits set_timestamp_subscription_plan_limits; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_subscription_plan_limits BEFORE UPDATE ON public.subscription_plan_limits FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: subscription_plans set_timestamp_subscription_plans; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_subscription_plans BEFORE UPDATE ON public.subscription_plans FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: transactions set_timestamp_transactions; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_transactions BEFORE UPDATE ON public.transactions FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: user_blocks set_timestamp_user_blocks; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_user_blocks BEFORE UPDATE ON public.user_blocks FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: user_daily_limits set_timestamp_user_daily_limits; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_user_daily_limits BEFORE UPDATE ON public.user_daily_limits FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: user_notification_preferences set_timestamp_user_notification_preferences; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_user_notification_preferences BEFORE UPDATE ON public.user_notification_preferences FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: user_reports set_timestamp_user_reports; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_user_reports BEFORE UPDATE ON public.user_reports FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: user_subscriptions set_timestamp_user_subscriptions; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER set_timestamp_user_subscriptions BEFORE UPDATE ON public.user_subscriptions FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: messages touch_match_after_message_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER touch_match_after_message_insert AFTER INSERT ON public.messages FOR EACH ROW EXECUTE FUNCTION public.touch_match_message_timestamps();


--
-- Name: payment_orders trg_validate_payment_order_market; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_validate_payment_order_market BEFORE INSERT OR UPDATE ON public.payment_orders FOR EACH ROW EXECUTE FUNCTION public.validate_payment_order_market();


--
-- Name: billing_customers update_billing_customers_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_billing_customers_updated_at BEFORE UPDATE ON public.billing_customers FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: consumable_products update_consumable_products_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_consumable_products_updated_at BEFORE UPDATE ON public.consumable_products FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: payment_methods update_payment_methods_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_payment_methods_updated_at BEFORE UPDATE ON public.payment_methods FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: payment_offers update_payment_offers_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_payment_offers_updated_at BEFORE UPDATE ON public.payment_offers FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: payment_orders update_payment_orders_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_payment_orders_updated_at BEFORE UPDATE ON public.payment_orders FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: payment_verification_attempts update_payment_verification_attempts_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_payment_verification_attempts_updated_at BEFORE UPDATE ON public.payment_verification_attempts FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: subscription_products update_subscription_products_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_subscription_products_updated_at BEFORE UPDATE ON public.subscription_products FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: user_discovery_actions validate_active_match_action_states; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER validate_active_match_action_states AFTER UPDATE OF status ON public.user_discovery_actions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.validate_active_match_action_states();


--
-- Name: active_boosts validate_boost_transaction_owner; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER validate_boost_transaction_owner BEFORE INSERT OR UPDATE OF user_id, transaction_id ON public.active_boosts FOR EACH ROW EXECUTE FUNCTION public.validate_boost_transaction_owner();


--
-- Name: matches validate_match_like_actions; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER validate_match_like_actions BEFORE INSERT ON public.matches FOR EACH ROW EXECUTE FUNCTION public.validate_match_like_actions();


--
-- Name: match_notification_settings validate_match_notification_settings_member; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER validate_match_notification_settings_member BEFORE INSERT OR UPDATE OF match_id, user_id ON public.match_notification_settings FOR EACH ROW EXECUTE FUNCTION public.validate_match_notification_settings_member();


--
-- Name: messages validate_message_sender_before_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER validate_message_sender_before_insert BEFORE INSERT ON public.messages FOR EACH ROW EXECUTE FUNCTION public.validate_message_sender_is_match_participant();


--
-- Name: support_messages validate_public_support_message_sender_before_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER validate_public_support_message_sender_before_insert BEFORE INSERT ON public.support_messages FOR EACH ROW EXECUTE FUNCTION public.validate_public_support_message_sender();


--
-- Name: user_subscriptions validate_user_subscription_paid_plan; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER validate_user_subscription_paid_plan BEFORE INSERT OR UPDATE OF plan_id ON public.user_subscriptions FOR EACH ROW EXECUTE FUNCTION public.validate_user_subscription_paid_plan();


--
-- Name: profile_photos validate_visible_profile_after_photo_change; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER validate_visible_profile_after_photo_change AFTER INSERT OR DELETE OR UPDATE ON public.profile_photos DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.validate_visible_profile_dependencies();


--
-- Name: discovery_preferences validate_visible_profile_after_preference_change; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER validate_visible_profile_after_preference_change AFTER INSERT OR DELETE OR UPDATE ON public.discovery_preferences DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.validate_visible_profile_dependencies();


--
-- Name: profiles validate_visible_profile_after_profile_change; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER validate_visible_profile_after_profile_change AFTER INSERT OR DELETE OR UPDATE ON public.profiles DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.validate_visible_profile_dependencies();


--
-- Name: app_users validate_visible_profile_after_user_change; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER validate_visible_profile_after_user_change AFTER INSERT OR DELETE OR UPDATE ON public.app_users DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.validate_visible_profile_dependencies();


--
-- Name: subscription tr_check_filters; Type: TRIGGER; Schema: realtime; Owner: -
--

CREATE TRIGGER tr_check_filters BEFORE INSERT OR UPDATE ON realtime.subscription FOR EACH ROW EXECUTE FUNCTION realtime.subscription_check_filters();


--
-- Name: buckets enforce_bucket_name_length_trigger; Type: TRIGGER; Schema: storage; Owner: -
--

CREATE TRIGGER enforce_bucket_name_length_trigger BEFORE INSERT OR UPDATE OF name ON storage.buckets FOR EACH ROW EXECUTE FUNCTION storage.enforce_bucket_name_length();


--
-- Name: buckets protect_buckets_delete; Type: TRIGGER; Schema: storage; Owner: -
--

CREATE TRIGGER protect_buckets_delete BEFORE DELETE ON storage.buckets FOR EACH STATEMENT EXECUTE FUNCTION storage.protect_delete();


--
-- Name: objects protect_objects_delete; Type: TRIGGER; Schema: storage; Owner: -
--

CREATE TRIGGER protect_objects_delete BEFORE DELETE ON storage.objects FOR EACH STATEMENT EXECUTE FUNCTION storage.protect_delete();


--
-- Name: objects update_objects_updated_at; Type: TRIGGER; Schema: storage; Owner: -
--

CREATE TRIGGER update_objects_updated_at BEFORE UPDATE ON storage.objects FOR EACH ROW EXECUTE FUNCTION storage.update_updated_at_column();


--
-- Name: identities identities_user_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.identities
    ADD CONSTRAINT identities_user_id_fkey FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE CASCADE;


--
-- Name: mfa_amr_claims mfa_amr_claims_session_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.mfa_amr_claims
    ADD CONSTRAINT mfa_amr_claims_session_id_fkey FOREIGN KEY (session_id) REFERENCES auth.sessions(id) ON DELETE CASCADE;


--
-- Name: mfa_challenges mfa_challenges_auth_factor_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.mfa_challenges
    ADD CONSTRAINT mfa_challenges_auth_factor_id_fkey FOREIGN KEY (factor_id) REFERENCES auth.mfa_factors(id) ON DELETE CASCADE;


--
-- Name: mfa_factors mfa_factors_user_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.mfa_factors
    ADD CONSTRAINT mfa_factors_user_id_fkey FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE CASCADE;


--
-- Name: oauth_authorizations oauth_authorizations_client_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.oauth_authorizations
    ADD CONSTRAINT oauth_authorizations_client_id_fkey FOREIGN KEY (client_id) REFERENCES auth.oauth_clients(id) ON DELETE CASCADE;


--
-- Name: oauth_authorizations oauth_authorizations_user_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.oauth_authorizations
    ADD CONSTRAINT oauth_authorizations_user_id_fkey FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE CASCADE;


--
-- Name: oauth_consents oauth_consents_client_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.oauth_consents
    ADD CONSTRAINT oauth_consents_client_id_fkey FOREIGN KEY (client_id) REFERENCES auth.oauth_clients(id) ON DELETE CASCADE;


--
-- Name: oauth_consents oauth_consents_user_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.oauth_consents
    ADD CONSTRAINT oauth_consents_user_id_fkey FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE CASCADE;


--
-- Name: one_time_tokens one_time_tokens_user_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.one_time_tokens
    ADD CONSTRAINT one_time_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE CASCADE;


--
-- Name: refresh_tokens refresh_tokens_session_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.refresh_tokens
    ADD CONSTRAINT refresh_tokens_session_id_fkey FOREIGN KEY (session_id) REFERENCES auth.sessions(id) ON DELETE CASCADE;


--
-- Name: saml_providers saml_providers_sso_provider_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.saml_providers
    ADD CONSTRAINT saml_providers_sso_provider_id_fkey FOREIGN KEY (sso_provider_id) REFERENCES auth.sso_providers(id) ON DELETE CASCADE;


--
-- Name: saml_relay_states saml_relay_states_flow_state_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.saml_relay_states
    ADD CONSTRAINT saml_relay_states_flow_state_id_fkey FOREIGN KEY (flow_state_id) REFERENCES auth.flow_state(id) ON DELETE CASCADE;


--
-- Name: saml_relay_states saml_relay_states_sso_provider_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.saml_relay_states
    ADD CONSTRAINT saml_relay_states_sso_provider_id_fkey FOREIGN KEY (sso_provider_id) REFERENCES auth.sso_providers(id) ON DELETE CASCADE;


--
-- Name: sessions sessions_oauth_client_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.sessions
    ADD CONSTRAINT sessions_oauth_client_id_fkey FOREIGN KEY (oauth_client_id) REFERENCES auth.oauth_clients(id) ON DELETE CASCADE;


--
-- Name: sessions sessions_user_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.sessions
    ADD CONSTRAINT sessions_user_id_fkey FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE CASCADE;


--
-- Name: sso_domains sso_domains_sso_provider_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.sso_domains
    ADD CONSTRAINT sso_domains_sso_provider_id_fkey FOREIGN KEY (sso_provider_id) REFERENCES auth.sso_providers(id) ON DELETE CASCADE;


--
-- Name: webauthn_challenges webauthn_challenges_user_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.webauthn_challenges
    ADD CONSTRAINT webauthn_challenges_user_id_fkey FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE CASCADE;


--
-- Name: webauthn_credentials webauthn_credentials_user_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: -
--

ALTER TABLE ONLY auth.webauthn_credentials
    ADD CONSTRAINT webauthn_credentials_user_id_fkey FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE CASCADE;


--
-- Name: active_boosts active_boosts_consumption_ledger_entry_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.active_boosts
    ADD CONSTRAINT active_boosts_consumption_ledger_entry_id_fkey FOREIGN KEY (consumption_ledger_entry_id) REFERENCES public.user_entitlement_ledger(id) ON DELETE SET NULL;


--
-- Name: active_boosts active_boosts_transaction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.active_boosts
    ADD CONSTRAINT active_boosts_transaction_id_fkey FOREIGN KEY (transaction_id) REFERENCES public.transactions(id) ON DELETE SET NULL;


--
-- Name: active_boosts active_boosts_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.active_boosts
    ADD CONSTRAINT active_boosts_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: addresses addresses_location_place_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.addresses
    ADD CONSTRAINT addresses_location_place_id_fkey FOREIGN KEY (location_place_id) REFERENCES public.location_places(id) ON DELETE SET NULL;


--
-- Name: app_users app_users_address_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_users
    ADD CONSTRAINT app_users_address_id_fkey FOREIGN KEY (address_id) REFERENCES public.addresses(id) ON DELETE SET NULL;


--
-- Name: app_users app_users_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_users
    ADD CONSTRAINT app_users_id_fkey FOREIGN KEY (id) REFERENCES auth.users(id) ON DELETE RESTRICT;


--
-- Name: audit_log audit_log_actor_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_actor_user_id_fkey FOREIGN KEY (actor_user_id) REFERENCES public.app_users(id) ON DELETE SET NULL;


--
-- Name: auth_anonymization_tasks auth_anonymization_tasks_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_anonymization_tasks
    ADD CONSTRAINT auth_anonymization_tasks_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE CASCADE;


--
-- Name: billing_customers billing_customers_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_customers
    ADD CONSTRAINT billing_customers_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: chat_attachments chat_attachments_message_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_attachments
    ADD CONSTRAINT chat_attachments_message_id_fkey FOREIGN KEY (message_id) REFERENCES public.messages(id) ON DELETE CASCADE;


--
-- Name: chat_outbox_events chat_outbox_events_match_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_outbox_events
    ADD CONSTRAINT chat_outbox_events_match_id_fkey FOREIGN KEY (match_id) REFERENCES public.matches(id) ON DELETE SET NULL;


--
-- Name: chat_outbox_events chat_outbox_events_recipient_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_outbox_events
    ADD CONSTRAINT chat_outbox_events_recipient_user_id_fkey FOREIGN KEY (recipient_user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: discovery_preferences discovery_preferences_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.discovery_preferences
    ADD CONSTRAINT discovery_preferences_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: match_notification_settings match_notification_settings_match_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.match_notification_settings
    ADD CONSTRAINT match_notification_settings_match_id_fkey FOREIGN KEY (match_id) REFERENCES public.matches(id) ON DELETE RESTRICT;


--
-- Name: match_notification_settings match_notification_settings_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.match_notification_settings
    ADD CONSTRAINT match_notification_settings_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: matches matches_created_by_action_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.matches
    ADD CONSTRAINT matches_created_by_action_id_fkey FOREIGN KEY (created_by_action_id) REFERENCES public.user_discovery_actions(id) ON DELETE RESTRICT;


--
-- Name: matches matches_ended_by_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.matches
    ADD CONSTRAINT matches_ended_by_user_id_fkey FOREIGN KEY (ended_by_user_id) REFERENCES public.app_users(id) ON DELETE SET NULL;


--
-- Name: matches matches_user_one_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.matches
    ADD CONSTRAINT matches_user_one_id_fkey FOREIGN KEY (user_one_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: matches matches_user_one_like_action_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.matches
    ADD CONSTRAINT matches_user_one_like_action_id_fkey FOREIGN KEY (user_one_like_action_id) REFERENCES public.user_discovery_actions(id) ON DELETE RESTRICT;


--
-- Name: matches matches_user_two_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.matches
    ADD CONSTRAINT matches_user_two_id_fkey FOREIGN KEY (user_two_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: matches matches_user_two_like_action_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.matches
    ADD CONSTRAINT matches_user_two_like_action_id_fkey FOREIGN KEY (user_two_like_action_id) REFERENCES public.user_discovery_actions(id) ON DELETE RESTRICT;


--
-- Name: messages messages_deleted_by_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_deleted_by_user_id_fkey FOREIGN KEY (deleted_by_user_id) REFERENCES public.app_users(id) ON DELETE SET NULL;


--
-- Name: messages messages_match_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_match_id_fkey FOREIGN KEY (match_id) REFERENCES public.matches(id) ON DELETE RESTRICT;


--
-- Name: messages messages_sender_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_sender_user_id_fkey FOREIGN KEY (sender_user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: notification_campaigns notification_campaigns_created_by_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_campaigns
    ADD CONSTRAINT notification_campaigns_created_by_user_id_fkey FOREIGN KEY (created_by_user_id) REFERENCES public.app_users(id) ON DELETE SET NULL;


--
-- Name: notification_deliveries notification_deliveries_notification_device_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_deliveries
    ADD CONSTRAINT notification_deliveries_notification_device_id_fkey FOREIGN KEY (notification_device_id) REFERENCES public.notification_devices(id) ON DELETE RESTRICT;


--
-- Name: notification_deliveries notification_deliveries_notification_outbox_event_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_deliveries
    ADD CONSTRAINT notification_deliveries_notification_outbox_event_id_fkey FOREIGN KEY (notification_outbox_event_id) REFERENCES public.notification_outbox_events(id) ON DELETE RESTRICT;


--
-- Name: notification_devices notification_devices_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_devices
    ADD CONSTRAINT notification_devices_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: notification_outbox_events notification_outbox_events_actor_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_outbox_events
    ADD CONSTRAINT notification_outbox_events_actor_user_id_fkey FOREIGN KEY (actor_user_id) REFERENCES public.app_users(id) ON DELETE SET NULL;


--
-- Name: notification_outbox_events notification_outbox_events_campaign_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_outbox_events
    ADD CONSTRAINT notification_outbox_events_campaign_id_fkey FOREIGN KEY (campaign_id) REFERENCES public.notification_campaigns(id) ON DELETE SET NULL;


--
-- Name: notification_outbox_events notification_outbox_events_discovery_action_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_outbox_events
    ADD CONSTRAINT notification_outbox_events_discovery_action_id_fkey FOREIGN KEY (discovery_action_id) REFERENCES public.user_discovery_actions(id) ON DELETE SET NULL;


--
-- Name: notification_outbox_events notification_outbox_events_match_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_outbox_events
    ADD CONSTRAINT notification_outbox_events_match_id_fkey FOREIGN KEY (match_id) REFERENCES public.matches(id) ON DELETE SET NULL;


--
-- Name: notification_outbox_events notification_outbox_events_message_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_outbox_events
    ADD CONSTRAINT notification_outbox_events_message_id_fkey FOREIGN KEY (message_id) REFERENCES public.messages(id) ON DELETE SET NULL;


--
-- Name: notification_outbox_events notification_outbox_events_recipient_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_outbox_events
    ADD CONSTRAINT notification_outbox_events_recipient_user_id_fkey FOREIGN KEY (recipient_user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: payment_events payment_events_payment_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_events
    ADD CONSTRAINT payment_events_payment_order_id_fkey FOREIGN KEY (payment_order_id) REFERENCES public.payment_orders(id) ON DELETE SET NULL;


--
-- Name: payment_events payment_events_subscription_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_events
    ADD CONSTRAINT payment_events_subscription_id_fkey FOREIGN KEY (subscription_id) REFERENCES public.user_subscriptions(id) ON DELETE SET NULL;


--
-- Name: payment_events payment_events_transaction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_events
    ADD CONSTRAINT payment_events_transaction_id_fkey FOREIGN KEY (transaction_id) REFERENCES public.transactions(id) ON DELETE SET NULL;


--
-- Name: payment_events payment_events_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_events
    ADD CONSTRAINT payment_events_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE SET NULL;


--
-- Name: payment_offers payment_offers_consumable_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_offers
    ADD CONSTRAINT payment_offers_consumable_product_id_fkey FOREIGN KEY (consumable_product_id) REFERENCES public.consumable_products(id) ON DELETE SET NULL;


--
-- Name: payment_offers payment_offers_subscription_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_offers
    ADD CONSTRAINT payment_offers_subscription_product_id_fkey FOREIGN KEY (subscription_product_id) REFERENCES public.subscription_products(id) ON DELETE SET NULL;


--
-- Name: payment_orders payment_orders_payment_method_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_orders
    ADD CONSTRAINT payment_orders_payment_method_id_fkey FOREIGN KEY (payment_method_id) REFERENCES public.payment_methods(id) ON DELETE RESTRICT;


--
-- Name: payment_orders payment_orders_payment_offer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_orders
    ADD CONSTRAINT payment_orders_payment_offer_id_fkey FOREIGN KEY (payment_offer_id) REFERENCES public.payment_offers(id) ON DELETE RESTRICT;


--
-- Name: payment_orders payment_orders_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_orders
    ADD CONSTRAINT payment_orders_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: payment_proofs payment_proofs_payment_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_proofs
    ADD CONSTRAINT payment_proofs_payment_order_id_fkey FOREIGN KEY (payment_order_id) REFERENCES public.payment_orders(id) ON DELETE RESTRICT;


--
-- Name: payment_verification_attempts payment_verification_attempts_payment_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_verification_attempts
    ADD CONSTRAINT payment_verification_attempts_payment_order_id_fkey FOREIGN KEY (payment_order_id) REFERENCES public.payment_orders(id) ON DELETE RESTRICT;


--
-- Name: payment_verification_attempts payment_verification_attempts_payment_proof_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_verification_attempts
    ADD CONSTRAINT payment_verification_attempts_payment_proof_id_fkey FOREIGN KEY (payment_proof_id) REFERENCES public.payment_proofs(id) ON DELETE SET NULL;


--
-- Name: payment_verification_attempts payment_verification_attempts_verified_by_admin_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_verification_attempts
    ADD CONSTRAINT payment_verification_attempts_verified_by_admin_id_fkey FOREIGN KEY (verified_by_admin_id) REFERENCES public.app_users(id) ON DELETE SET NULL;


--
-- Name: profile_photos profile_photos_reviewed_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profile_photos
    ADD CONSTRAINT profile_photos_reviewed_by_fkey FOREIGN KEY (reviewed_by) REFERENCES public.app_users(id) ON DELETE SET NULL;


--
-- Name: profile_photos profile_photos_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profile_photos
    ADD CONSTRAINT profile_photos_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: profile_prompt_answers profile_prompt_answers_prompt_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profile_prompt_answers
    ADD CONSTRAINT profile_prompt_answers_prompt_id_fkey FOREIGN KEY (prompt_id) REFERENCES public.profile_prompts(id) ON DELETE RESTRICT;


--
-- Name: profile_prompt_answers profile_prompt_answers_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profile_prompt_answers
    ADD CONSTRAINT profile_prompt_answers_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: profile_prompt_translations profile_prompt_translations_prompt_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profile_prompt_translations
    ADD CONSTRAINT profile_prompt_translations_prompt_id_fkey FOREIGN KEY (prompt_id) REFERENCES public.profile_prompts(id) ON DELETE CASCADE;


--
-- Name: profiles profiles_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profiles
    ADD CONSTRAINT profiles_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: promotion_campaigns promotion_campaigns_created_by_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.promotion_campaigns
    ADD CONSTRAINT promotion_campaigns_created_by_user_id_fkey FOREIGN KEY (created_by_user_id) REFERENCES public.app_users(id) ON DELETE SET NULL;


--
-- Name: promotion_campaigns promotion_campaigns_subscription_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.promotion_campaigns
    ADD CONSTRAINT promotion_campaigns_subscription_product_id_fkey FOREIGN KEY (subscription_product_id) REFERENCES public.subscription_products(id);


--
-- Name: promotion_redemptions promotion_redemptions_campaign_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.promotion_redemptions
    ADD CONSTRAINT promotion_redemptions_campaign_id_fkey FOREIGN KEY (campaign_id) REFERENCES public.promotion_campaigns(id);


--
-- Name: promotion_redemptions promotion_redemptions_payment_offer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.promotion_redemptions
    ADD CONSTRAINT promotion_redemptions_payment_offer_id_fkey FOREIGN KEY (payment_offer_id) REFERENCES public.payment_offers(id) ON DELETE SET NULL;


--
-- Name: promotion_redemptions promotion_redemptions_payment_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.promotion_redemptions
    ADD CONSTRAINT promotion_redemptions_payment_order_id_fkey FOREIGN KEY (payment_order_id) REFERENCES public.payment_orders(id) ON DELETE SET NULL;


--
-- Name: promotion_redemptions promotion_redemptions_subscription_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.promotion_redemptions
    ADD CONSTRAINT promotion_redemptions_subscription_id_fkey FOREIGN KEY (subscription_id) REFERENCES public.user_subscriptions(id) ON DELETE SET NULL;


--
-- Name: promotion_redemptions promotion_redemptions_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.promotion_redemptions
    ADD CONSTRAINT promotion_redemptions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id);


--
-- Name: subscription_plan_limits subscription_plan_limits_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subscription_plan_limits
    ADD CONSTRAINT subscription_plan_limits_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.subscription_plans(id) ON DELETE CASCADE;


--
-- Name: subscription_products subscription_products_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subscription_products
    ADD CONSTRAINT subscription_products_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.subscription_plans(id) ON DELETE RESTRICT;


--
-- Name: support_attachments support_attachments_message_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_attachments
    ADD CONSTRAINT support_attachments_message_id_fkey FOREIGN KEY (message_id) REFERENCES public.support_messages(id) ON DELETE CASCADE;


--
-- Name: support_conversation_staff_reads support_conversation_staff_reads_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_conversation_staff_reads
    ADD CONSTRAINT support_conversation_staff_reads_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.support_conversations(id) ON DELETE CASCADE;


--
-- Name: support_conversation_staff_reads support_conversation_staff_reads_staff_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_conversation_staff_reads
    ADD CONSTRAINT support_conversation_staff_reads_staff_user_id_fkey FOREIGN KEY (staff_user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: support_conversations support_conversations_assigned_staff_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_conversations
    ADD CONSTRAINT support_conversations_assigned_staff_user_id_fkey FOREIGN KEY (assigned_staff_user_id) REFERENCES public.app_users(id) ON DELETE SET NULL;


--
-- Name: support_conversations support_conversations_closed_by_app_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_conversations
    ADD CONSTRAINT support_conversations_closed_by_app_user_id_fkey FOREIGN KEY (closed_by_app_user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: support_conversations support_conversations_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_conversations
    ADD CONSTRAINT support_conversations_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: support_internal_notes support_internal_notes_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_internal_notes
    ADD CONSTRAINT support_internal_notes_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.support_conversations(id) ON DELETE CASCADE;


--
-- Name: support_internal_notes support_internal_notes_staff_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_internal_notes
    ADD CONSTRAINT support_internal_notes_staff_user_id_fkey FOREIGN KEY (staff_user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: support_messages support_messages_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_messages
    ADD CONSTRAINT support_messages_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.support_conversations(id) ON DELETE CASCADE;


--
-- Name: support_messages support_messages_sender_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_messages
    ADD CONSTRAINT support_messages_sender_user_id_fkey FOREIGN KEY (sender_user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: transactions transactions_payment_offer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transactions
    ADD CONSTRAINT transactions_payment_offer_id_fkey FOREIGN KEY (payment_offer_id) REFERENCES public.payment_offers(id) ON DELETE SET NULL;


--
-- Name: transactions transactions_payment_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transactions
    ADD CONSTRAINT transactions_payment_order_id_fkey FOREIGN KEY (payment_order_id) REFERENCES public.payment_orders(id) ON DELETE SET NULL;


--
-- Name: transactions transactions_related_transaction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transactions
    ADD CONSTRAINT transactions_related_transaction_id_fkey FOREIGN KEY (related_transaction_id) REFERENCES public.transactions(id) ON DELETE SET NULL;


--
-- Name: transactions transactions_subscription_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transactions
    ADD CONSTRAINT transactions_subscription_id_fkey FOREIGN KEY (subscription_id) REFERENCES public.user_subscriptions(id) ON DELETE SET NULL;


--
-- Name: transactions transactions_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transactions
    ADD CONSTRAINT transactions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: user_blocks user_blocks_blocked_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_blocks
    ADD CONSTRAINT user_blocks_blocked_user_id_fkey FOREIGN KEY (blocked_user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: user_blocks user_blocks_blocker_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_blocks
    ADD CONSTRAINT user_blocks_blocker_user_id_fkey FOREIGN KEY (blocker_user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: user_daily_limits user_daily_limits_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_daily_limits
    ADD CONSTRAINT user_daily_limits_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: user_discovery_actions user_discovery_actions_actor_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_discovery_actions
    ADD CONSTRAINT user_discovery_actions_actor_user_id_fkey FOREIGN KEY (actor_user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: user_discovery_actions user_discovery_actions_target_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_discovery_actions
    ADD CONSTRAINT user_discovery_actions_target_user_id_fkey FOREIGN KEY (target_user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: user_entitlement_credit_consumptions user_entitlement_credit_consum_consumption_ledger_entry_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_entitlement_credit_consumptions
    ADD CONSTRAINT user_entitlement_credit_consum_consumption_ledger_entry_id_fkey FOREIGN KEY (consumption_ledger_entry_id) REFERENCES public.user_entitlement_ledger(id) ON DELETE RESTRICT;


--
-- Name: user_entitlement_credit_consumptions user_entitlement_credit_consumptions_credit_lot_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_entitlement_credit_consumptions
    ADD CONSTRAINT user_entitlement_credit_consumptions_credit_lot_id_fkey FOREIGN KEY (credit_lot_id) REFERENCES public.user_entitlement_credit_lots(id) ON DELETE RESTRICT;


--
-- Name: user_entitlement_credit_lots user_entitlement_credit_lots_source_ledger_entry_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_entitlement_credit_lots
    ADD CONSTRAINT user_entitlement_credit_lots_source_ledger_entry_id_fkey FOREIGN KEY (source_ledger_entry_id) REFERENCES public.user_entitlement_ledger(id) ON DELETE RESTRICT;


--
-- Name: user_entitlement_credit_lots user_entitlement_credit_lots_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_entitlement_credit_lots
    ADD CONSTRAINT user_entitlement_credit_lots_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: user_entitlement_ledger user_entitlement_ledger_related_discovery_action_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_entitlement_ledger
    ADD CONSTRAINT user_entitlement_ledger_related_discovery_action_id_fkey FOREIGN KEY (related_discovery_action_id) REFERENCES public.user_discovery_actions(id) ON DELETE SET NULL;


--
-- Name: user_entitlement_ledger user_entitlement_ledger_subscription_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_entitlement_ledger
    ADD CONSTRAINT user_entitlement_ledger_subscription_id_fkey FOREIGN KEY (subscription_id) REFERENCES public.user_subscriptions(id) ON DELETE SET NULL;


--
-- Name: user_entitlement_ledger user_entitlement_ledger_transaction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_entitlement_ledger
    ADD CONSTRAINT user_entitlement_ledger_transaction_id_fkey FOREIGN KEY (transaction_id) REFERENCES public.transactions(id) ON DELETE SET NULL;


--
-- Name: user_entitlement_ledger user_entitlement_ledger_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_entitlement_ledger
    ADD CONSTRAINT user_entitlement_ledger_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: user_notification_preferences user_notification_preferences_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_notification_preferences
    ADD CONSTRAINT user_notification_preferences_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: user_quota_usage user_quota_usage_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_quota_usage
    ADD CONSTRAINT user_quota_usage_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.subscription_plans(id) ON DELETE RESTRICT;


--
-- Name: user_quota_usage user_quota_usage_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_quota_usage
    ADD CONSTRAINT user_quota_usage_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: user_reports user_reports_related_message_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_reports
    ADD CONSTRAINT user_reports_related_message_id_fkey FOREIGN KEY (related_message_id) REFERENCES public.messages(id) ON DELETE SET NULL;


--
-- Name: user_reports user_reports_reported_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_reports
    ADD CONSTRAINT user_reports_reported_user_id_fkey FOREIGN KEY (reported_user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: user_reports user_reports_reporter_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_reports
    ADD CONSTRAINT user_reports_reporter_user_id_fkey FOREIGN KEY (reporter_user_id) REFERENCES public.app_users(id) ON DELETE SET NULL;


--
-- Name: user_reports user_reports_reviewed_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_reports
    ADD CONSTRAINT user_reports_reviewed_by_fkey FOREIGN KEY (reviewed_by) REFERENCES public.app_users(id) ON DELETE SET NULL;


--
-- Name: user_subscriptions user_subscriptions_payment_offer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_subscriptions
    ADD CONSTRAINT user_subscriptions_payment_offer_id_fkey FOREIGN KEY (payment_offer_id) REFERENCES public.payment_offers(id) ON DELETE SET NULL;


--
-- Name: user_subscriptions user_subscriptions_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_subscriptions
    ADD CONSTRAINT user_subscriptions_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.subscription_plans(id) ON DELETE RESTRICT;


--
-- Name: user_subscriptions user_subscriptions_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_subscriptions
    ADD CONSTRAINT user_subscriptions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: user_verifications user_verifications_reviewed_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_verifications
    ADD CONSTRAINT user_verifications_reviewed_by_fkey FOREIGN KEY (reviewed_by) REFERENCES public.app_users(id) ON DELETE SET NULL;


--
-- Name: user_verifications user_verifications_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_verifications
    ADD CONSTRAINT user_verifications_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE RESTRICT;


--
-- Name: objects objects_bucketId_fkey; Type: FK CONSTRAINT; Schema: storage; Owner: -
--

ALTER TABLE ONLY storage.objects
    ADD CONSTRAINT "objects_bucketId_fkey" FOREIGN KEY (bucket_id) REFERENCES storage.buckets(id);


--
-- Name: s3_multipart_uploads s3_multipart_uploads_bucket_id_fkey; Type: FK CONSTRAINT; Schema: storage; Owner: -
--

ALTER TABLE ONLY storage.s3_multipart_uploads
    ADD CONSTRAINT s3_multipart_uploads_bucket_id_fkey FOREIGN KEY (bucket_id) REFERENCES storage.buckets(id);


--
-- Name: s3_multipart_uploads_parts s3_multipart_uploads_parts_bucket_id_fkey; Type: FK CONSTRAINT; Schema: storage; Owner: -
--

ALTER TABLE ONLY storage.s3_multipart_uploads_parts
    ADD CONSTRAINT s3_multipart_uploads_parts_bucket_id_fkey FOREIGN KEY (bucket_id) REFERENCES storage.buckets(id);


--
-- Name: s3_multipart_uploads_parts s3_multipart_uploads_parts_upload_id_fkey; Type: FK CONSTRAINT; Schema: storage; Owner: -
--

ALTER TABLE ONLY storage.s3_multipart_uploads_parts
    ADD CONSTRAINT s3_multipart_uploads_parts_upload_id_fkey FOREIGN KEY (upload_id) REFERENCES storage.s3_multipart_uploads(id) ON DELETE CASCADE;


--
-- Name: vector_indexes vector_indexes_bucket_id_fkey; Type: FK CONSTRAINT; Schema: storage; Owner: -
--

ALTER TABLE ONLY storage.vector_indexes
    ADD CONSTRAINT vector_indexes_bucket_id_fkey FOREIGN KEY (bucket_id) REFERENCES storage.buckets_vectors(id);


--
-- Name: audit_log_entries; Type: ROW SECURITY; Schema: auth; Owner: -
--

ALTER TABLE auth.audit_log_entries ENABLE ROW LEVEL SECURITY;

--
-- Name: flow_state; Type: ROW SECURITY; Schema: auth; Owner: -
--

ALTER TABLE auth.flow_state ENABLE ROW LEVEL SECURITY;

--
-- Name: identities; Type: ROW SECURITY; Schema: auth; Owner: -
--

ALTER TABLE auth.identities ENABLE ROW LEVEL SECURITY;

--
-- Name: instances; Type: ROW SECURITY; Schema: auth; Owner: -
--

ALTER TABLE auth.instances ENABLE ROW LEVEL SECURITY;

--
-- Name: mfa_amr_claims; Type: ROW SECURITY; Schema: auth; Owner: -
--

ALTER TABLE auth.mfa_amr_claims ENABLE ROW LEVEL SECURITY;

--
-- Name: mfa_challenges; Type: ROW SECURITY; Schema: auth; Owner: -
--

ALTER TABLE auth.mfa_challenges ENABLE ROW LEVEL SECURITY;

--
-- Name: mfa_factors; Type: ROW SECURITY; Schema: auth; Owner: -
--

ALTER TABLE auth.mfa_factors ENABLE ROW LEVEL SECURITY;

--
-- Name: one_time_tokens; Type: ROW SECURITY; Schema: auth; Owner: -
--

ALTER TABLE auth.one_time_tokens ENABLE ROW LEVEL SECURITY;

--
-- Name: refresh_tokens; Type: ROW SECURITY; Schema: auth; Owner: -
--

ALTER TABLE auth.refresh_tokens ENABLE ROW LEVEL SECURITY;

--
-- Name: saml_providers; Type: ROW SECURITY; Schema: auth; Owner: -
--

ALTER TABLE auth.saml_providers ENABLE ROW LEVEL SECURITY;

--
-- Name: saml_relay_states; Type: ROW SECURITY; Schema: auth; Owner: -
--

ALTER TABLE auth.saml_relay_states ENABLE ROW LEVEL SECURITY;

--
-- Name: schema_migrations; Type: ROW SECURITY; Schema: auth; Owner: -
--

ALTER TABLE auth.schema_migrations ENABLE ROW LEVEL SECURITY;

--
-- Name: sessions; Type: ROW SECURITY; Schema: auth; Owner: -
--

ALTER TABLE auth.sessions ENABLE ROW LEVEL SECURITY;

--
-- Name: sso_domains; Type: ROW SECURITY; Schema: auth; Owner: -
--

ALTER TABLE auth.sso_domains ENABLE ROW LEVEL SECURITY;

--
-- Name: sso_providers; Type: ROW SECURITY; Schema: auth; Owner: -
--

ALTER TABLE auth.sso_providers ENABLE ROW LEVEL SECURITY;

--
-- Name: users; Type: ROW SECURITY; Schema: auth; Owner: -
--

ALTER TABLE auth.users ENABLE ROW LEVEL SECURITY;

--
-- Name: active_boosts; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.active_boosts ENABLE ROW LEVEL SECURITY;

--
-- Name: addresses; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.addresses ENABLE ROW LEVEL SECURITY;

--
-- Name: app_users; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.app_users ENABLE ROW LEVEL SECURITY;

--
-- Name: audit_log; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.audit_log ENABLE ROW LEVEL SECURITY;

--
-- Name: auth_anonymization_tasks; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.auth_anonymization_tasks ENABLE ROW LEVEL SECURITY;

--
-- Name: billing_customers; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.billing_customers ENABLE ROW LEVEL SECURITY;

--
-- Name: chat_attachments; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.chat_attachments ENABLE ROW LEVEL SECURITY;

--
-- Name: chat_outbox_events; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.chat_outbox_events ENABLE ROW LEVEL SECURITY;

--
-- Name: consumable_products; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.consumable_products ENABLE ROW LEVEL SECURITY;

--
-- Name: discovery_preferences; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.discovery_preferences ENABLE ROW LEVEL SECURITY;

--
-- Name: ethnicities; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.ethnicities ENABLE ROW LEVEL SECURITY;

--
-- Name: image_moderation_results; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.image_moderation_results ENABLE ROW LEVEL SECURITY;

--
-- Name: languages; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.languages ENABLE ROW LEVEL SECURITY;

--
-- Name: location_places; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.location_places ENABLE ROW LEVEL SECURITY;

--
-- Name: match_notification_settings; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.match_notification_settings ENABLE ROW LEVEL SECURITY;

--
-- Name: matches; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.matches ENABLE ROW LEVEL SECURITY;

--
-- Name: messages; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;

--
-- Name: notification_campaigns; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.notification_campaigns ENABLE ROW LEVEL SECURITY;

--
-- Name: notification_deliveries; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.notification_deliveries ENABLE ROW LEVEL SECURITY;

--
-- Name: notification_devices; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.notification_devices ENABLE ROW LEVEL SECURITY;

--
-- Name: notification_outbox_events; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.notification_outbox_events ENABLE ROW LEVEL SECURITY;

--
-- Name: payment_events; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.payment_events ENABLE ROW LEVEL SECURITY;

--
-- Name: payment_methods; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.payment_methods ENABLE ROW LEVEL SECURITY;

--
-- Name: payment_offers; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.payment_offers ENABLE ROW LEVEL SECURITY;

--
-- Name: payment_orders; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.payment_orders ENABLE ROW LEVEL SECURITY;

--
-- Name: payment_proofs; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.payment_proofs ENABLE ROW LEVEL SECURITY;

--
-- Name: payment_verification_attempts; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.payment_verification_attempts ENABLE ROW LEVEL SECURITY;

--
-- Name: profile_photos; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.profile_photos ENABLE ROW LEVEL SECURITY;

--
-- Name: profile_prompt_answers; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.profile_prompt_answers ENABLE ROW LEVEL SECURITY;

--
-- Name: profile_prompt_translations; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.profile_prompt_translations ENABLE ROW LEVEL SECURITY;

--
-- Name: profile_prompts; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.profile_prompts ENABLE ROW LEVEL SECURITY;

--
-- Name: profiles; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

--
-- Name: promotion_campaigns; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.promotion_campaigns ENABLE ROW LEVEL SECURITY;

--
-- Name: promotion_redemptions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.promotion_redemptions ENABLE ROW LEVEL SECURITY;

--
-- Name: subscription_plan_limits; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.subscription_plan_limits ENABLE ROW LEVEL SECURITY;

--
-- Name: subscription_plans; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.subscription_plans ENABLE ROW LEVEL SECURITY;

--
-- Name: subscription_products; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.subscription_products ENABLE ROW LEVEL SECURITY;

--
-- Name: support_attachments support_attachment_user_select; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY support_attachment_user_select ON public.support_attachments FOR SELECT TO authenticated USING ((EXISTS ( SELECT 1
   FROM (public.support_messages m
     JOIN public.support_conversations c ON ((c.id = m.conversation_id)))
  WHERE ((m.id = support_attachments.message_id) AND (c.user_id = auth.uid())))));


--
-- Name: support_attachments; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.support_attachments ENABLE ROW LEVEL SECURITY;

--
-- Name: support_conversations support_conv_user_select; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY support_conv_user_select ON public.support_conversations FOR SELECT TO authenticated USING ((user_id = auth.uid()));


--
-- Name: support_conversation_staff_reads; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.support_conversation_staff_reads ENABLE ROW LEVEL SECURITY;

--
-- Name: support_conversations; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.support_conversations ENABLE ROW LEVEL SECURITY;

--
-- Name: support_internal_notes; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.support_internal_notes ENABLE ROW LEVEL SECURITY;

--
-- Name: support_messages; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.support_messages ENABLE ROW LEVEL SECURITY;

--
-- Name: support_messages support_msg_user_select; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY support_msg_user_select ON public.support_messages FOR SELECT TO authenticated USING ((EXISTS ( SELECT 1
   FROM public.support_conversations c
  WHERE ((c.id = support_messages.conversation_id) AND (c.user_id = auth.uid())))));


--
-- Name: transactions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.transactions ENABLE ROW LEVEL SECURITY;

--
-- Name: user_blocks; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.user_blocks ENABLE ROW LEVEL SECURITY;

--
-- Name: user_daily_limits; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.user_daily_limits ENABLE ROW LEVEL SECURITY;

--
-- Name: user_discovery_actions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.user_discovery_actions ENABLE ROW LEVEL SECURITY;

--
-- Name: user_entitlement_credit_consumptions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.user_entitlement_credit_consumptions ENABLE ROW LEVEL SECURITY;

--
-- Name: user_entitlement_credit_lots; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.user_entitlement_credit_lots ENABLE ROW LEVEL SECURITY;

--
-- Name: user_entitlement_ledger; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.user_entitlement_ledger ENABLE ROW LEVEL SECURITY;

--
-- Name: user_notification_preferences; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.user_notification_preferences ENABLE ROW LEVEL SECURITY;

--
-- Name: user_quota_usage; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.user_quota_usage ENABLE ROW LEVEL SECURITY;

--
-- Name: user_reports; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.user_reports ENABLE ROW LEVEL SECURITY;

--
-- Name: user_subscriptions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.user_subscriptions ENABLE ROW LEVEL SECURITY;

--
-- Name: user_verifications; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.user_verifications ENABLE ROW LEVEL SECURITY;

--
-- Name: messages chat realtime publish ephemeral; Type: POLICY; Schema: realtime; Owner: -
--

CREATE POLICY "chat realtime publish ephemeral" ON realtime.messages FOR INSERT TO authenticated WITH CHECK ((((extension = 'broadcast'::text) AND (public.chat_realtime_is_active_match_member(realtime.topic(), 'typing'::text) OR public.chat_realtime_is_active_match_member(realtime.topic(), 'presence'::text))) OR ((extension = 'presence'::text) AND public.chat_realtime_is_active_match_member(realtime.topic(), 'presence'::text))));


--
-- Name: messages chat realtime receive; Type: POLICY; Schema: realtime; Owner: -
--

CREATE POLICY "chat realtime receive" ON realtime.messages FOR SELECT TO authenticated USING ((((extension = 'broadcast'::text) AND (public.chat_realtime_is_active_match_member(realtime.topic(), 'events'::text) OR public.chat_realtime_is_active_match_member(realtime.topic(), 'typing'::text) OR public.chat_realtime_is_active_match_member(realtime.topic(), 'presence'::text) OR public.chat_realtime_is_own_inbox_topic(realtime.topic()))) OR ((extension = 'presence'::text) AND public.chat_realtime_is_active_match_member(realtime.topic(), 'presence'::text))));


--
-- Name: messages; Type: ROW SECURITY; Schema: realtime; Owner: -
--

ALTER TABLE realtime.messages ENABLE ROW LEVEL SECURITY;

--
-- Name: objects Authenticated users can read profile photos; Type: POLICY; Schema: storage; Owner: -
--

CREATE POLICY "Authenticated users can read profile photos" ON storage.objects FOR SELECT TO authenticated USING ((bucket_id = 'profile-photos'::text));


--
-- Name: objects Users can delete their own profile photos; Type: POLICY; Schema: storage; Owner: -
--

CREATE POLICY "Users can delete their own profile photos" ON storage.objects FOR DELETE TO authenticated USING (((bucket_id = 'profile-photos'::text) AND ((storage.foldername(name))[1] = (auth.uid())::text)));


--
-- Name: objects Users can update their own profile photos; Type: POLICY; Schema: storage; Owner: -
--

CREATE POLICY "Users can update their own profile photos" ON storage.objects FOR UPDATE TO authenticated USING (((bucket_id = 'profile-photos'::text) AND ((storage.foldername(name))[1] = (auth.uid())::text)));


--
-- Name: objects Users can upload their own profile photos; Type: POLICY; Schema: storage; Owner: -
--

CREATE POLICY "Users can upload their own profile photos" ON storage.objects FOR INSERT TO authenticated WITH CHECK (((bucket_id = 'profile-photos'::text) AND ((storage.foldername(name))[1] = (auth.uid())::text)));


--
-- Name: buckets; Type: ROW SECURITY; Schema: storage; Owner: -
--

ALTER TABLE storage.buckets ENABLE ROW LEVEL SECURITY;

--
-- Name: buckets_analytics; Type: ROW SECURITY; Schema: storage; Owner: -
--

ALTER TABLE storage.buckets_analytics ENABLE ROW LEVEL SECURITY;

--
-- Name: buckets_vectors; Type: ROW SECURITY; Schema: storage; Owner: -
--

ALTER TABLE storage.buckets_vectors ENABLE ROW LEVEL SECURITY;

--
-- Name: migrations; Type: ROW SECURITY; Schema: storage; Owner: -
--

ALTER TABLE storage.migrations ENABLE ROW LEVEL SECURITY;

--
-- Name: objects; Type: ROW SECURITY; Schema: storage; Owner: -
--

ALTER TABLE storage.objects ENABLE ROW LEVEL SECURITY;

--
-- Name: s3_multipart_uploads; Type: ROW SECURITY; Schema: storage; Owner: -
--

ALTER TABLE storage.s3_multipart_uploads ENABLE ROW LEVEL SECURITY;

--
-- Name: s3_multipart_uploads_parts; Type: ROW SECURITY; Schema: storage; Owner: -
--

ALTER TABLE storage.s3_multipart_uploads_parts ENABLE ROW LEVEL SECURITY;

--
-- Name: vector_indexes; Type: ROW SECURITY; Schema: storage; Owner: -
--

ALTER TABLE storage.vector_indexes ENABLE ROW LEVEL SECURITY;

--
-- Name: supabase_realtime; Type: PUBLICATION; Schema: -; Owner: -
--

CREATE PUBLICATION supabase_realtime WITH (publish = 'insert, update, delete, truncate');


--
-- Name: supabase_realtime_messages_publication; Type: PUBLICATION; Schema: -; Owner: -
--

CREATE PUBLICATION supabase_realtime_messages_publication WITH (publish = 'insert, update, delete, truncate');


--
-- Name: supabase_realtime_messages_publication messages; Type: PUBLICATION TABLE; Schema: realtime; Owner: -
--

ALTER PUBLICATION supabase_realtime_messages_publication ADD TABLE ONLY realtime.messages;


--
-- Name: ensure_rls; Type: EVENT TRIGGER; Schema: -; Owner: -
--

CREATE EVENT TRIGGER ensure_rls ON ddl_command_end
         WHEN TAG IN ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
   EXECUTE FUNCTION public.rls_auto_enable();


--
-- Name: issue_graphql_placeholder; Type: EVENT TRIGGER; Schema: -; Owner: -
--

CREATE EVENT TRIGGER issue_graphql_placeholder ON sql_drop
         WHEN TAG IN ('DROP EXTENSION')
   EXECUTE FUNCTION extensions.set_graphql_placeholder();


--
-- Name: issue_pg_cron_access; Type: EVENT TRIGGER; Schema: -; Owner: -
--

CREATE EVENT TRIGGER issue_pg_cron_access ON ddl_command_end
         WHEN TAG IN ('CREATE EXTENSION')
   EXECUTE FUNCTION extensions.grant_pg_cron_access();


--
-- Name: issue_pg_graphql_access; Type: EVENT TRIGGER; Schema: -; Owner: -
--

CREATE EVENT TRIGGER issue_pg_graphql_access ON ddl_command_end
         WHEN TAG IN ('CREATE EXTENSION')
   EXECUTE FUNCTION extensions.grant_pg_graphql_access();


--
-- Name: issue_pg_net_access; Type: EVENT TRIGGER; Schema: -; Owner: -
--

CREATE EVENT TRIGGER issue_pg_net_access ON ddl_command_end
         WHEN TAG IN ('CREATE EXTENSION')
   EXECUTE FUNCTION extensions.grant_pg_net_access();


--
-- Name: pgrst_ddl_watch; Type: EVENT TRIGGER; Schema: -; Owner: -
--

CREATE EVENT TRIGGER pgrst_ddl_watch ON ddl_command_end
   EXECUTE FUNCTION extensions.pgrst_ddl_watch();


--
-- Name: pgrst_drop_watch; Type: EVENT TRIGGER; Schema: -; Owner: -
--

CREATE EVENT TRIGGER pgrst_drop_watch ON sql_drop
   EXECUTE FUNCTION extensions.pgrst_drop_watch();


--
-- PostgreSQL database dump complete
--


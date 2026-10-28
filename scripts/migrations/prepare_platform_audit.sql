-- After V20, before V21. No activation or usable credential.
\set ON_ERROR_STOP on
\set ECHO none
\if :{?apply}
\else
  \set apply false
\endif
\if :apply
\else
  DO $$ BEGIN RAISE EXCEPTION 'Explicit apply=true required'; END $$;
\endif
BEGIN;
DO $$ BEGIN
    IF session_user<>'postgres' OR current_user<>session_user OR current_database()<>'ls_core_dev'
       OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
       OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999
       OR NOT EXISTS (SELECT FROM pg_trigger WHERE tgrelid='platform.administrators'::regclass AND tgname='platform_designation_lock')
       OR EXISTS (SELECT FROM pg_roles WHERE rolname='ls_core_platform_security_log_dev') THEN
        RAISE EXCEPTION 'Expected fresh local V20 installation; no adoption';
    END IF;
END $$;
CREATE ROLE ls_core_platform_security_log_dev NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 2 PASSWORD NULL;
COMMIT;

-- Installation prerequisite for V20, never an operational activation procedure.
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
       OR NOT EXISTS (SELECT FROM pg_constraint WHERE conname='platform_admin_active_identity_fk'
                      AND conrelid='platform.administrators'::regclass)
       OR EXISTS (SELECT FROM pg_roles WHERE rolname='ls_core_platform_mutator_dev') THEN
        RAISE EXCEPTION 'Expected fresh local V19 installation; no adoption or activation';
    END IF;
END $$;
CREATE ROLE ls_core_platform_mutator_dev NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 2 PASSWORD NULL;
-- Deliberately no CONNECT, password, role membership, or grant to a service.
COMMIT;

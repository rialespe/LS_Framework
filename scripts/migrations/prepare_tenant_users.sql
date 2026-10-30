-- Explicit installation after V27. Dedicated administrator channels; no global identity seeds.
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
       OR NOT EXISTS(SELECT FROM pg_class WHERE oid='security.branch_history_requests'::regclass AND relforcerowsecurity)
       OR EXISTS(SELECT FROM pg_roles WHERE rolname IN ('ls_core_tenant_users_dev','ls_core_tenant_users_log_dev')) THEN
        RAISE EXCEPTION 'Expected local V27 and fresh roles; no adoption';
    END IF;
END $$;
CREATE ROLE ls_core_tenant_users_dev NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 3;
CREATE ROLE ls_core_tenant_users_log_dev NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 2;
SET LOCAL password_encryption='scram-sha-256';
\password ls_core_tenant_users_dev
\password ls_core_tenant_users_log_dev
DO $$ BEGIN
    IF (SELECT count(*) FROM pg_authid WHERE rolname IN ('ls_core_tenant_users_dev','ls_core_tenant_users_log_dev')
        AND rolpassword LIKE 'SCRAM-SHA-256$%')<>2 THEN RAISE EXCEPTION 'SCRAM incomplete'; END IF;
END $$;
GRANT CONNECT ON DATABASE ls_core_dev TO ls_core_tenant_users_dev,ls_core_tenant_users_log_dev;
ALTER ROLE ls_core_tenant_users_dev LOGIN;
ALTER ROLE ls_core_tenant_users_log_dev LOGIN;
COMMIT;

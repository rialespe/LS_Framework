-- Exceptional installation operator only, after successful F04 owner initialization and connection close.
\set ON_ERROR_STOP on
\set ECHO none
\set VERBOSITY terse
\if :{?apply}
\else
  \set apply false
\endif
\if :apply
\else
  DO $$ BEGIN RAISE EXCEPTION 'Explicit apply=true required'; END $$;
\endif
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='10s';
SELECT pg_advisory_xact_lock(370019,1);
DO $$ BEGIN
    IF session_user<>'postgres' OR current_user<>session_user OR current_database()<>'ls_core_dev'
       OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
       OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999
       OR (SELECT count(*) FROM platform.administrators a JOIN identity.users u ON u.id=a.user_id
           WHERE a.administrator_kind='OWNER' AND a.status='ACTIVE' AND a.revoked_at IS NULL AND u.status='ACTIVE')<>1
       OR NOT EXISTS(SELECT FROM pg_roles WHERE rolname='ls_core_platform_bootstrap_dev' AND rolcanlogin)
       OR EXISTS(SELECT FROM pg_stat_activity WHERE usename='ls_core_platform_bootstrap_dev') THEN
        RAISE EXCEPTION 'Expected one protected owner and a closed unsealed installation channel';
    END IF;
END $$;
ALTER ROLE ls_core_platform_bootstrap_dev NOLOGIN;
REVOKE CONNECT ON DATABASE ls_core_dev FROM ls_core_platform_bootstrap_dev;
COMMIT;
\echo 'SEALED: owner installation LOGIN disabled. No delegation channel enabled.'

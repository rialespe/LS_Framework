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
SELECT pg_advisory_xact_lock(390021,1);
DO $$ BEGIN
    IF session_user<>'postgres' OR current_user<>session_user OR current_database()<>'ls_core_dev'
       OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
       OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999
       OR NOT EXISTS(SELECT FROM pg_trigger WHERE tgrelid='platform.administrators'::regclass AND tgname='platform_designation_audit')
       OR (SELECT count(*) FROM pg_roles r WHERE rolname IN ('ls_core_platform_mutator_dev','ls_core_platform_security_log_dev')
           AND NOT (rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
           AND rolconnlimit=2 AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
           AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o'))<>2 THEN
        RAISE EXCEPTION 'Expected local V21 installation and isolated restricted principals';
    END IF;
END $$;
DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_stat_activity WHERE usename IN ('ls_core_platform_mutator_dev','ls_core_platform_security_log_dev')) THEN
        RAISE EXCEPTION 'Close owned executor connections before deactivation';
    END IF;
END $$;
ALTER ROLE ls_core_platform_mutator_dev NOLOGIN PASSWORD NULL;
ALTER ROLE ls_core_platform_security_log_dev NOLOGIN PASSWORD NULL;
REVOKE CONNECT ON DATABASE ls_core_dev FROM ls_core_platform_mutator_dev,ls_core_platform_security_log_dev;
COMMIT;

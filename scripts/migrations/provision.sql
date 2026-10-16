-- Administrative preparation, separate from Flyway and from the unchanged LFD-23 bootstrap.
-- Invoke through manage_migrations.py provision, only after explicit owner authorization.
\set ON_ERROR_STOP on
\set ECHO none
\set VERBOSITY terse
\pset pager off
\if :{?apply}
\else
  \set apply false
\endif
\if :apply
\else
  DO $$ BEGIN RAISE EXCEPTION 'Inspection only by default; use the confirmed administrative wrapper'; END $$;
\endif
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '10s';
SELECT pg_advisory_xact_lock(250025, 17);
DO $$
DECLARE
    app pg_roles%ROWTYPE;
BEGIN
    IF current_user <> 'postgres' OR session_user <> 'postgres'
       OR current_database() <> 'ls_core_dev'
       OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
       OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999 THEN
        RAISE EXCEPTION 'Expected existing postgres administrator on local ls_core_dev, PostgreSQL 17';
    END IF;
    IF NOT EXISTS (SELECT FROM pg_database WHERE datname = current_database()
                   AND pg_get_userbyid(datdba) = 'postgres'
                   AND pg_encoding_to_char(encoding) = 'UTF8' AND NOT datistemplate) THEN
        RAISE EXCEPTION 'Unexpected database; no repair attempted';
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'ls_core_migrator_dev') THEN
        RAISE EXCEPTION 'Migrator role already exists: stop for compatibility review; no adoption or modification';
    END IF;
    IF EXISTS (SELECT FROM pg_namespace WHERE nspname IN
               ('identity', 'platform', 'tenant', 'security', 'catalog', 'integration', 'audit', 'operations')) THEN
        RAISE EXCEPTION 'An authorized schema already exists: no automatic adoption or replacement';
    END IF;
    IF EXISTS (SELECT FROM pg_database d WHERE d.datname <> current_database() AND d.datallowconn
               AND EXISTS (SELECT FROM aclexplode(coalesce(d.datacl, acldefault('d', d.datdba))) a
                            WHERE a.grantee = 0 AND a.privilege_type = 'CONNECT')) THEN
        RAISE EXCEPTION 'Other databases allow PUBLIC CONNECT: security review required; no global ACL changes attempted';
    END IF;
    SELECT * INTO app FROM pg_roles WHERE rolname = 'ls_core_app_dev';
    IF NOT FOUND OR NOT app.rolcanlogin OR app.rolsuper OR app.rolcreatedb
       OR app.rolcreaterole OR app.rolbypassrls OR app.rolreplication OR app.rolinherit
       OR app.rolconnlimit <> 3
       OR NOT has_database_privilege(app.oid, current_database(), 'CONNECT')
       OR has_database_privilege(app.oid, current_database(), 'CREATE')
       OR has_database_privilege(app.oid, current_database(), 'TEMPORARY')
       OR EXISTS (SELECT FROM pg_auth_members WHERE member = app.oid OR roleid = app.oid)
       OR EXISTS (SELECT FROM pg_shdepend WHERE refclassid = 'pg_authid'::regclass
                  AND refobjid = app.oid AND deptype = 'o') THEN
        RAISE EXCEPTION 'Application role does not match the existing limited LFD-23 contract';
    END IF;
    IF EXISTS (SELECT FROM aclexplode(coalesce(
                   (SELECT datacl FROM pg_database WHERE datname = current_database()),
                   acldefault('d', (SELECT datdba FROM pg_database WHERE datname = current_database()))))
               WHERE grantee = 0) THEN
        RAISE EXCEPTION 'PUBLIC database privileges are incompatible; no repair attempted';
    END IF;
END $$;
CREATE ROLE ls_core_migrator_dev NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 3;
COMMENT ON ROLE ls_core_migrator_dev IS 'LS Core LFD-25 migrations v1';
GRANT CONNECT ON DATABASE ls_core_dev TO ls_core_migrator_dev;
-- Administrator creates only these eight namespaces. Migrator never receives database CREATE/TEMP.
CREATE SCHEMA identity AUTHORIZATION ls_core_migrator_dev;
CREATE SCHEMA platform AUTHORIZATION ls_core_migrator_dev;
CREATE SCHEMA tenant AUTHORIZATION ls_core_migrator_dev;
CREATE SCHEMA security AUTHORIZATION ls_core_migrator_dev;
CREATE SCHEMA catalog AUTHORIZATION ls_core_migrator_dev;
CREATE SCHEMA integration AUTHORIZATION ls_core_migrator_dev;
CREATE SCHEMA audit AUTHORIZATION ls_core_migrator_dev;
CREATE SCHEMA operations AUTHORIZATION ls_core_migrator_dev;
-- Same transaction/session: the password is entered locally, never in SQL literals or arguments.
SET LOCAL password_encryption = 'scram-sha-256';
\password ls_core_migrator_dev
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_authid WHERE rolname = 'ls_core_migrator_dev'
                   AND rolpassword IS NOT NULL AND NOT rolcanlogin) THEN
        RAISE EXCEPTION 'Secure password exchange incomplete; provisioning rolls back';
    END IF;
END $$;
ALTER ROLE ls_core_migrator_dev LOGIN;
COMMIT;
\echo 'PREPARED: limited migrator and eight authorized schemas; application role unchanged.'

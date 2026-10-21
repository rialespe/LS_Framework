-- LFD-5 administrative step, never invoked by Flyway or application startup.
-- Sequence: restricted migrator applies through V9; administrator runs this script;
-- restricted migrator applies V10; configure LS_IDENTITY_DB_* and opt in to the verifier.
-- psql -X -v apply=true -f scripts/migrations/prepare_credential_verifier.sql
-- Supply the connection separately. Enter the password through \password, never SQL/argv.
-- No adoption, repair, memberships or table grants here. Failure rolls back creation.
\set ON_ERROR_STOP on
\set ECHO none
\set VERBOSITY terse
\if :{?apply}
\else
  \set apply false
\endif
\if :apply
\else
  DO $$ BEGIN RAISE EXCEPTION 'No changes by default; explicit apply=true required'; END $$;
\endif
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '10s';
SELECT pg_advisory_xact_lock(500005, 17);
DO $$
DECLARE
    relation record;
BEGIN
    IF current_user <> 'postgres' OR session_user <> 'postgres'
       OR current_database() <> 'ls_core_dev'
       OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
       OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999 THEN
        RAISE EXCEPTION 'Expected postgres administrator on local PostgreSQL 17 ls_core_dev';
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='ls_core_credential_verifier_dev') THEN
        RAISE EXCEPTION 'Credential verifier already exists; no adoption or modification';
    END IF;
    IF (SELECT count(*) FROM pg_roles WHERE rolname IN ('ls_core_app_dev','ls_core_migrator_dev')
         AND rolcanlogin AND NOT (rolinherit OR rolsuper OR rolcreatedb OR rolcreaterole
                                 OR rolreplication OR rolbypassrls) AND rolconnlimit=3) <> 2
       OR EXISTS (SELECT FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member OR r.oid=m.roleid
                   WHERE r.rolname IN ('ls_core_app_dev','ls_core_migrator_dev')) THEN
        RAISE EXCEPTION 'Runtime/migrator role contract mismatch';
    END IF;
    IF (SELECT count(*) FROM pg_class WHERE oid IN
         ('identity.users'::regclass,'identity.user_credentials'::regclass)
         AND relkind='r' AND relowner='ls_core_migrator_dev'::regrole
         AND relrowsecurity AND relforcerowsecurity) <> 2
       OR EXISTS (SELECT FROM pg_policy WHERE polrelid IN
                   ('identity.users'::regclass,'identity.user_credentials'::regclass)) THEN
        RAISE EXCEPTION 'Expected V9 credential tables with FORCE RLS and no policies';
    END IF;
    IF EXISTS (SELECT FROM pg_database d WHERE d.datallowconn
                AND EXISTS (SELECT FROM aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a
                             WHERE a.grantee=0))
       OR EXISTS (SELECT FROM pg_namespace n CROSS JOIN LATERAL
                   aclexplode(coalesce(n.nspacl,acldefault('n',n.nspowner))) a
                   WHERE a.grantee=0 AND a.privilege_type='CREATE') THEN
        RAISE EXCEPTION 'PUBLIC database/schema privileges incompatible; no repair attempted';
    END IF;
    FOR relation IN
        SELECT c.oid,c.relacl,c.relowner FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname !~ '^pg_'
          AND c.relkind IN ('r','p','v','m','f')
    LOOP
        IF EXISTS (SELECT FROM aclexplode(coalesce(relation.relacl,acldefault('r',relation.relowner)))
                    WHERE grantee=0)
           OR EXISTS (SELECT FROM pg_attribute a CROSS JOIN LATERAL aclexplode(a.attacl) acl
                       WHERE a.attrelid=relation.oid AND acl.grantee=0) THEN
            RAISE EXCEPTION 'PUBLIC relation privileges incompatible; no repair attempted';
        END IF;
    END LOOP;
    IF has_any_column_privilege('ls_core_app_dev','identity.users','SELECT,INSERT,UPDATE,REFERENCES')
       OR has_any_column_privilege('ls_core_app_dev','identity.user_credentials','SELECT,INSERT,UPDATE,REFERENCES') THEN
        RAISE EXCEPTION 'Runtime identity privileges incompatible; no repair attempted';
    END IF;
END $$;
CREATE ROLE ls_core_credential_verifier_dev NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 3;
GRANT CONNECT ON DATABASE ls_core_dev TO ls_core_credential_verifier_dev;
SET LOCAL password_encryption = 'scram-sha-256';
\password ls_core_credential_verifier_dev
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_authid WHERE rolname='ls_core_credential_verifier_dev'
                   AND NOT rolcanlogin AND rolpassword LIKE 'SCRAM-SHA-256$%') THEN
        RAISE EXCEPTION 'SCRAM password exchange incomplete; creation rolls back';
    END IF;
END $$;
ALTER ROLE ls_core_credential_verifier_dev LOGIN;
COMMIT;
\echo 'PREPARED: isolated credential LOGIN only; restricted migrator must apply V10 next.'

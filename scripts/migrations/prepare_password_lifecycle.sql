-- LFD-32 explicit administrative preparation; never run by Flyway/application startup.
-- Sequence: V11 applied; administrator runs this script; restricted migrator applies V12.
-- psql -X -v apply=true -f scripts/migrations/prepare_password_lifecycle.sql
-- Supply connection separately; password via \password, not SQL/argv. No adoption or ACL repair.
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
SELECT pg_advisory_xact_lock(320032, 17);
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
    IF EXISTS (SELECT FROM pg_roles WHERE rolname IN ('ls_core_password_issuer_dev','ls_core_password_changer_dev')) THEN
        RAISE EXCEPTION 'Password service role already exists; no adoption or modification';
    END IF;
    IF (SELECT count(*) FROM pg_class WHERE oid IN
         ('identity.users'::regclass,'identity.user_credentials'::regclass)
         AND relkind='r' AND relowner='ls_core_migrator_dev'::regrole
         AND relrowsecurity AND relforcerowsecurity) <> 2
       OR (SELECT count(*) FROM pg_policy WHERE polrelid='identity.users'::regclass) <> 2
       OR NOT EXISTS (SELECT FROM pg_policy WHERE polrelid='identity.users'::regclass
                       AND polname='credential_verifier_read' AND polcmd='r'
                       AND polroles=ARRAY['ls_core_credential_verifier_dev'::regrole::oid]) THEN
        RAISE EXCEPTION 'Expected V11 identity tables and policies';
    END IF;
    IF (SELECT count(*) FROM pg_roles WHERE rolname IN
         ('ls_core_app_dev','ls_core_migrator_dev','ls_core_credential_verifier_dev')
         AND rolcanlogin AND NOT (rolinherit OR rolsuper OR rolcreatedb OR rolcreaterole
                                 OR rolreplication OR rolbypassrls) AND rolconnlimit=3) <> 3
       OR EXISTS (SELECT FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member OR r.oid=m.roleid
                   WHERE r.rolname IN ('ls_core_app_dev','ls_core_migrator_dev','ls_core_credential_verifier_dev'))
       OR EXISTS (SELECT FROM pg_database d WHERE d.datallowconn
                   AND EXISTS (SELECT FROM aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a
                                WHERE a.grantee=0))
       OR EXISTS (SELECT FROM pg_namespace n CROSS JOIN LATERAL
                   aclexplode(coalesce(n.nspacl,acldefault('n',n.nspowner))) a
                   WHERE a.grantee=0 AND a.privilege_type='CREATE') THEN
        RAISE EXCEPTION 'Existing role/PUBLIC privileges incompatible; no repair attempted';
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
END $$;
CREATE ROLE ls_core_password_issuer_dev NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 3;
CREATE ROLE ls_core_password_changer_dev NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 3;
GRANT CONNECT ON DATABASE ls_core_dev TO ls_core_password_issuer_dev,ls_core_password_changer_dev;
SET LOCAL password_encryption = 'scram-sha-256';
\password ls_core_password_issuer_dev
\password ls_core_password_changer_dev
DO $$ BEGIN
    IF (SELECT count(*) FROM pg_authid WHERE rolname IN
        ('ls_core_password_issuer_dev','ls_core_password_changer_dev')
        AND NOT rolcanlogin AND rolpassword LIKE 'SCRAM-SHA-256$%') <> 2 THEN
        RAISE EXCEPTION 'SCRAM password exchange incomplete; creation rolls back';
    END IF;
END $$;
ALTER ROLE ls_core_password_issuer_dev LOGIN;
ALTER ROLE ls_core_password_changer_dev LOGIN;
COMMIT;
\echo 'PREPARED: isolated password LOGINs only; restricted migrator must apply V12 next.'

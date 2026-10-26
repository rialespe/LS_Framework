-- Explicit administrative provisioning after V14, before restricted-owner V15. No implicit apply/adoption.
-- psql -X -v apply=true -f scripts/migrations/prepare_branch_operation.sql
-- Supply connection separately and use the interactive SCRAM password exchange, never argv/SQL secrets.
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
SELECT pg_advisory_xact_lock(290015,17);
DO $$
DECLARE relation record;
BEGIN
    IF session_user<>'postgres' OR current_user<>session_user OR current_database()<>'ls_core_dev'
       OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
       OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999 THEN
        RAISE EXCEPTION 'Expected local postgres administrator on PostgreSQL 17 ls_core_dev';
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname IN ('ls_core_branch_operation_dev','ls_core_operation_emitter_dev')) THEN
        RAISE EXCEPTION 'Operation role already exists; no adoption or modification';
    END IF;
    IF (SELECT count(*) FROM pg_class WHERE oid IN
        ('identity.users'::regclass,'tenant.tenants'::regclass,'tenant.memberships'::regclass,
         'tenant.branches'::regclass,'tenant.branch_memberships'::regclass,'catalog.tenant_applications'::regclass,
         'identity.password_recovery_tokens'::regclass)
        AND relkind='r' AND relowner='ls_core_migrator_dev'::regrole AND relrowsecurity AND relforcerowsecurity)<>7
       OR NOT EXISTS (SELECT FROM pg_policy WHERE polrelid='identity.users'::regclass
           AND polname='authorization_metadata_read' AND polroles=ARRAY['ls_core_authorization_reader_dev'::regrole::oid]) THEN
        RAISE EXCEPTION 'Expected V14 and FORCE RLS authorization tables';
    END IF;
    IF EXISTS (SELECT FROM pg_database d CROSS JOIN LATERAL aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a
               WHERE d.datallowconn AND a.grantee=0)
       OR EXISTS (SELECT FROM pg_namespace n CROSS JOIN LATERAL aclexplode(coalesce(n.nspacl,acldefault('n',n.nspowner))) a
                  WHERE a.grantee=0 AND a.privilege_type='CREATE') THEN
        RAISE EXCEPTION 'PUBLIC database/schema privileges incompatible; no repair attempted';
    END IF;
    FOR relation IN SELECT c.oid,c.relacl,c.relowner FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname !~ '^pg_'
          AND c.relkind IN ('r','p','v','m','f')
    LOOP
        IF EXISTS (SELECT FROM aclexplode(coalesce(relation.relacl,acldefault('r',relation.relowner))) WHERE grantee=0)
           OR EXISTS (SELECT FROM pg_attribute a CROSS JOIN LATERAL aclexplode(a.attacl) acl
                      WHERE a.attrelid=relation.oid AND acl.grantee=0) THEN
            RAISE EXCEPTION 'PUBLIC relation privileges incompatible; no repair attempted';
        END IF;
    END LOOP;
END $$;
CREATE ROLE ls_core_branch_operation_dev NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 3;
CREATE ROLE ls_core_operation_emitter_dev NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 3;
GRANT CONNECT ON DATABASE ls_core_dev TO ls_core_branch_operation_dev,ls_core_operation_emitter_dev;
SET LOCAL password_encryption='scram-sha-256';
\password ls_core_branch_operation_dev
\password ls_core_operation_emitter_dev
DO $$ BEGIN
    IF (SELECT count(*) FROM pg_authid WHERE rolname IN ('ls_core_branch_operation_dev','ls_core_operation_emitter_dev')
        AND NOT rolcanlogin AND rolpassword LIKE 'SCRAM-SHA-256$%')<>2 THEN
        RAISE EXCEPTION 'SCRAM password exchange incomplete';
    END IF;
END $$;
ALTER ROLE ls_core_branch_operation_dev LOGIN;
ALTER ROLE ls_core_operation_emitter_dev LOGIN;
COMMIT;
\echo 'PREPARED: operation/emitter LOGINs only; restricted migrator must apply V15 next.'

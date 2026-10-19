-- C1 administrative preparation; never called by Flyway or application startup.
-- Default: refuse changes. Reviewed application: psql -X -v apply=true -f prepare_catalog_reader.sql
-- Connection/credentials must be supplied separately by the administrator.
-- Capability role only: NOLOGIN, no members, no password or CONNECT grant.
-- Assigning a separate catalog-service identity requires independent approval.
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
SELECT pg_advisory_xact_lock(290001, 17);
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
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='ls_core_catalog_reader_dev') THEN
        RAISE EXCEPTION 'Catalog reader already exists; no adoption or modification';
    END IF;
    IF EXISTS (SELECT FROM pg_database d
               CROSS JOIN LATERAL aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a
               WHERE d.datname=current_database() AND a.grantee=0)
       OR EXISTS (SELECT FROM pg_namespace n
                  CROSS JOIN LATERAL aclexplode(coalesce(n.nspacl,acldefault('n',n.nspowner))) a
                  WHERE a.grantee=0 AND a.privilege_type='CREATE') THEN
        RAISE EXCEPTION 'PUBLIC database or schema CREATE privileges incompatible; no repair attempted';
    END IF;
    IF (SELECT count(*) FROM pg_roles
         WHERE rolname IN ('ls_core_app_dev','ls_core_migrator_dev')
           AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolinherit)
           AND rolcanlogin AND rolconnlimit=3) <> 2
       OR EXISTS (SELECT FROM pg_auth_members m JOIN pg_roles r
                  ON r.oid=m.member OR r.oid=m.roleid
                  WHERE r.rolname IN ('ls_core_app_dev','ls_core_migrator_dev')) THEN
        RAISE EXCEPTION 'Existing runtime/migrator role contract mismatch';
    END IF;
    IF (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE c.oid IN ('catalog.applications'::regclass,'catalog.modules'::regclass,
                        'security.permissions'::regclass,'security.role_templates'::regclass,
                        'security.role_template_permissions'::regclass,'security.profile_templates'::regclass,
                        'security.profile_template_roles'::regclass)
           AND c.relkind='r' AND c.relowner='ls_core_migrator_dev'::regrole
           AND NOT c.relrowsecurity AND NOT c.relforcerowsecurity) <> 7 THEN
        RAISE EXCEPTION 'Expected seven global catalog tables owned by the restricted migrator, without RLS';
    END IF;
    -- Refuse drift instead of revoking privileges or repairing existing infrastructure.
    FOR relation IN
        SELECT c.oid, c.relacl, c.relowner FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname IN ('identity','platform','tenant','security','catalog','integration','audit','operations')
          AND c.relkind IN ('r','v','m','p','f')
    LOOP
        IF has_table_privilege('ls_core_app_dev',relation.oid,
                              'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
           OR has_any_column_privilege('ls_core_app_dev',relation.oid,'SELECT,INSERT,UPDATE,REFERENCES')
           OR EXISTS (SELECT FROM aclexplode(coalesce(relation.relacl,acldefault('r',relation.relowner)))
                       WHERE grantee=0)
           OR EXISTS (SELECT FROM pg_attribute a CROSS JOIN LATERAL aclexplode(a.attacl) acl
                       WHERE a.attrelid=relation.oid AND acl.grantee=0) THEN
            RAISE EXCEPTION 'Existing business ACLs incompatible; no repair attempted';
        END IF;
    END LOOP;
END $$;
CREATE ROLE ls_core_catalog_reader_dev NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 3;
COMMENT ON ROLE ls_core_catalog_reader_dev IS 'LFD-29 C1 global catalog columns; no login or members';
GRANT USAGE ON SCHEMA catalog, security TO ls_core_catalog_reader_dev;
GRANT SELECT (id, code, name, description, status)
    ON catalog.applications TO ls_core_catalog_reader_dev;
GRANT SELECT (id, application_id, code, name, description, status)
    ON catalog.modules TO ls_core_catalog_reader_dev;
GRANT SELECT (id, application_id, module_id, code, name, description, resource_scope, status)
    ON security.permissions TO ls_core_catalog_reader_dev;
GRANT SELECT (id, application_id, code, version, name, description, status)
    ON security.role_templates TO ls_core_catalog_reader_dev;
GRANT SELECT (application_id, role_template_id, permission_id)
    ON security.role_template_permissions TO ls_core_catalog_reader_dev;
GRANT SELECT (id, application_id, code, version, name, description, status)
    ON security.profile_templates TO ls_core_catalog_reader_dev;
GRANT SELECT (application_id, profile_template_id, role_template_id)
    ON security.profile_template_roles TO ls_core_catalog_reader_dev;
COMMIT;
\echo 'PREPARED: catalog capability role only; no login, memberships, runtime grants or default privileges.'

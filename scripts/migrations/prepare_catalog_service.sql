-- C2 administrative script, never executed by Flyway or application startup.
-- Explicit opt-in: psql -X -v apply=true -f prepare_catalog_service.sql
-- Administrator supplies the connection separately and enters the password via \password.
-- Refuse drift; never adopt roles, revoke existing ACLs or change the C1 reader.
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
    item record;
    expected text;
BEGIN
    IF current_user <> 'postgres' OR session_user <> 'postgres'
       OR current_database() <> 'ls_core_dev'
       OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
       OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999 THEN
        RAISE EXCEPTION 'Expected postgres administrator on local PostgreSQL 17 ls_core_dev';
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='ls_core_catalog_service_dev') THEN
        RAISE EXCEPTION 'Catalog service already exists; no adoption or modification';
    END IF;
    IF (SELECT count(*) FROM pg_class WHERE oid IN
         ('catalog.applications'::regclass,'catalog.modules'::regclass,'security.permissions'::regclass,
          'security.role_templates'::regclass,'security.role_template_permissions'::regclass,
          'security.profile_templates'::regclass,'security.profile_template_roles'::regclass)
         AND relkind='r' AND relowner='ls_core_migrator_dev'::regrole
         AND NOT relrowsecurity AND NOT relforcerowsecurity) <> 7 THEN
        RAISE EXCEPTION 'Expected seven unchanged global catalog tables';
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='ls_core_catalog_reader_dev'
                   AND NOT (rolcanlogin OR rolinherit OR rolsuper OR rolcreatedb OR rolcreaterole
                            OR rolreplication OR rolbypassrls) AND rolconnlimit=3)
       OR EXISTS (SELECT FROM pg_auth_members WHERE member='ls_core_catalog_reader_dev'::regrole
                   OR roleid='ls_core_catalog_reader_dev'::regrole)
       OR EXISTS (SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass
                   AND refobjid='ls_core_catalog_reader_dev'::regrole AND deptype='o') THEN
        RAISE EXCEPTION 'Expected unassigned, unprivileged C1 reader without ownership';
    END IF;
    IF EXISTS (SELECT FROM pg_database d WHERE d.datallowconn AND
                (has_database_privilege('ls_core_catalog_reader_dev',d.oid,'CONNECT,CREATE,TEMPORARY')
                 OR has_database_privilege('ls_core_app_dev',d.oid,'CREATE,TEMPORARY')
                 OR (d.datname<>current_database() AND has_database_privilege('ls_core_app_dev',d.oid,'CONNECT'))))
       OR EXISTS (SELECT FROM pg_namespace WHERE has_schema_privilege('ls_core_catalog_reader_dev',oid,'CREATE'))
       OR EXISTS (SELECT FROM pg_roles WHERE rolname='ls_core_app_dev'
                   AND (rolinherit OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
       OR EXISTS (SELECT FROM pg_auth_members WHERE member='ls_core_app_dev'::regrole
                   OR roleid='ls_core_app_dev'::regrole) THEN
        RAISE EXCEPTION 'Incompatible inherited/database/schema privileges; no repair attempted';
    END IF;
    -- Validate exact effective C1 column SELECT, including absence of PUBLIC table access.
    FOR item IN
        SELECT c.oid,n.nspname||'.'||c.relname AS name FROM pg_class c
        JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname !~ '^pg_toast'
          AND c.relkind IN ('r','p','v','m','f')
    LOOP
        IF has_table_privilege('ls_core_catalog_reader_dev',item.oid,
                              'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
           OR has_table_privilege('ls_core_app_dev',item.oid,
                                 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
           OR has_any_column_privilege('ls_core_app_dev',item.oid,'SELECT,INSERT,UPDATE,REFERENCES')
           OR has_any_column_privilege('ls_core_catalog_reader_dev',item.oid,'INSERT,UPDATE,REFERENCES') THEN
            RAISE EXCEPTION 'Incompatible table ACL: %', item.name;
        END IF;
        expected := CASE item.name
          WHEN 'catalog.applications' THEN 'id code name description status'
          WHEN 'catalog.modules' THEN 'id application_id code name description status'
          WHEN 'security.permissions' THEN 'id application_id module_id code name description resource_scope status'
          WHEN 'security.role_templates' THEN 'id application_id code version name description status'
          WHEN 'security.role_template_permissions' THEN 'application_id role_template_id permission_id'
          WHEN 'security.profile_templates' THEN 'id application_id code version name description status'
          WHEN 'security.profile_template_roles' THEN 'application_id profile_template_id role_template_id'
          ELSE '' END;
        IF EXISTS (SELECT FROM pg_attribute a WHERE a.attrelid=item.oid AND a.attnum>0 AND NOT a.attisdropped
                    AND (has_column_privilege('ls_core_catalog_reader_dev',item.oid,a.attnum,'SELECT')
                         <> (a.attname=ANY(string_to_array(expected,' ')))
                         OR has_column_privilege('ls_core_catalog_reader_dev',item.oid,a.attnum,'SELECT WITH GRANT OPTION'))) THEN
            RAISE EXCEPTION 'Incompatible C1 column ACL: %', item.name;
        END IF;
    END LOOP;
END $$;
CREATE ROLE ls_core_catalog_service_dev NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 3;
GRANT CONNECT ON DATABASE ls_core_dev TO ls_core_catalog_service_dev;
GRANT ls_core_catalog_reader_dev TO ls_core_catalog_service_dev WITH INHERIT FALSE;
GRANT ls_core_catalog_reader_dev TO ls_core_catalog_service_dev WITH SET TRUE;
GRANT ls_core_catalog_reader_dev TO ls_core_catalog_service_dev WITH ADMIN FALSE;
SET LOCAL password_encryption = 'scram-sha-256';
\password ls_core_catalog_service_dev
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_authid WHERE rolname='ls_core_catalog_service_dev'
                   AND NOT rolcanlogin AND rolpassword LIKE 'SCRAM-SHA-256$%') THEN
        RAISE EXCEPTION 'SCRAM password exchange incomplete; creation rolls back';
    END IF;
END $$;
ALTER ROLE ls_core_catalog_service_dev LOGIN;
COMMIT;
\echo 'PREPARED: isolated catalog LOGIN; explicit SET ROLE required; runtime unchanged.'

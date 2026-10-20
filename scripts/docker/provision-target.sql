\set ON_ERROR_STOP on
\set ECHO none
\set VERBOSITY verbose
BEGIN;
SET LOCAL log_statement='none';
SET LOCAL log_min_duration_statement=-1;
SET LOCAL log_min_error_statement='panic';
SET LOCAL password_encryption='scram-sha-256';
DO $$ BEGIN
  IF current_user<>'ls_core_docker' OR session_user<>current_user
     OR current_database()<>'ls_core_dev'
     OR (SELECT system_identifier::text FROM pg_control_system())<>'7698808171985612839'
     OR current_setting('server_version_num')::int NOT BETWEEN 170000 AND 179999
     OR EXISTS (SELECT FROM pg_roles WHERE rolname IN ('ls_core_migrator_dev','ls_core_app_dev'))
     OR EXISTS (SELECT FROM pg_namespace WHERE nspname IN
          ('identity','platform','tenant','security','catalog','integration','audit','operations'))
     OR EXISTS (SELECT FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
          WHERE n.nspname='public') THEN
    RAISE EXCEPTION 'Fresh Docker target contract mismatch; no adoption';
  END IF;
END $$;
REVOKE ALL ON DATABASE ls_core_dev FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE ROLE ls_core_migrator_dev NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 3;
CREATE ROLE ls_core_app_dev NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 3;
GRANT CONNECT ON DATABASE ls_core_dev TO ls_core_migrator_dev,ls_core_app_dev;
CREATE SCHEMA identity AUTHORIZATION ls_core_migrator_dev;
CREATE SCHEMA platform AUTHORIZATION ls_core_migrator_dev;
CREATE SCHEMA tenant AUTHORIZATION ls_core_migrator_dev;
CREATE SCHEMA security AUTHORIZATION ls_core_migrator_dev;
CREATE SCHEMA catalog AUTHORIZATION ls_core_migrator_dev;
CREATE SCHEMA integration AUTHORIZATION ls_core_migrator_dev;
CREATE SCHEMA audit AUTHORIZATION ls_core_migrator_dev;
CREATE SCHEMA operations AUTHORIZATION ls_core_migrator_dev;
\password ls_core_migrator_dev
\password ls_core_app_dev
DO $$ BEGIN
  IF (SELECT count(*) FROM pg_authid WHERE rolname IN ('ls_core_migrator_dev','ls_core_app_dev')
       AND rolpassword LIKE 'SCRAM-SHA-256$%' AND NOT rolcanlogin)<>2 THEN
    RAISE EXCEPTION 'Secure password setup incomplete; rolling back roles/schemas';
  END IF;
END $$;
ALTER ROLE ls_core_migrator_dev LOGIN;
ALTER ROLE ls_core_app_dev LOGIN;
COMMIT;
\echo 'Restricted roles and eight schemas committed.'

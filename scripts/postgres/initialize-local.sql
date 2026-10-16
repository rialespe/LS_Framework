-- psql only; run through Initialize-LocalPostgres.ps1. No secrets in SQL variables.
\set ON_ERROR_STOP on
\set ECHO none
\set VERBOSITY terse
\pset pager off
SET statement_timeout = '10s';
SET lock_timeout = '5s';

-- A session advisory lock serializes cooperating installers; it changes no objects.
-- Do not run other administrators' DDL concurrently with this installer.
SELECT pg_try_advisory_lock(230023, 17) AS locked \gset
\if :locked
\else
  DO $$ BEGIN RAISE EXCEPTION 'Another LS Core initializer is running'; END $$;
\endif

\ir inspect-bootstrap.sql

-- Flags describe a validated candidate, not permission to adopt it.
SELECT EXISTS (SELECT FROM pg_authid WHERE rolname = 'ls_core_app_dev' AND NOT rolcanlogin
               AND shobj_description(oid, 'pg_authid') IS NULL) AS adopt_role,
       coalesce((SELECT oid::text FROM pg_roles WHERE rolname = 'ls_core_app_dev'), '0') AS role_oid
\gset plan_

SELECT md5(coalesce(string_agg(ROW(oid, datname, datdba, encoding, datacl, datallowconn)::text,
       '|' ORDER BY oid), '')) AS fingerprint
FROM pg_database WHERE datname <> 'ls_core_dev' \gset before_

\echo 'PLAN - only ls_core_dev / ls_core_app_dev may be changed'
\echo 'Compatible unmarked partial role; explicit adoption required:' :plan_adopt_role
\if :plan_adopt_role
  \echo 'Adoption will mark the existing role and set CONNECTION LIMIT 3, preserving its identity and NOLOGIN until ready.'
  \echo 'Catalogs cannot prove absence of dormant application use. The owner must attest exclusive LS Core use.'
\endif
SELECT NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'ls_core_app_dev') AS create_role,
       NOT EXISTS (SELECT FROM pg_database WHERE datname = 'ls_core_dev') AS create_database,
       NOT EXISTS (SELECT FROM pg_authid WHERE rolname = 'ls_core_app_dev' AND rolcanlogin) AS enable_login,
       NOT EXISTS (SELECT FROM pg_authid WHERE rolname = 'ls_core_app_dev' AND rolpassword IS NOT NULL) AS set_new_password
\gset plan_

-- Existing ACL drift is repairable only for an identifiable, incomplete bootstrap.
SELECT CASE WHEN NOT EXISTS (SELECT FROM pg_database WHERE datname = 'ls_core_dev') THEN true
            ELSE EXISTS (
              SELECT FROM pg_database d, LATERAL aclexplode(coalesce(d.datacl, acldefault('d', d.datdba))) a
              WHERE d.datname = 'ls_core_dev' AND a.grantee = 0
            ) OR CASE WHEN EXISTS (SELECT FROM pg_roles WHERE rolname = 'ls_core_app_dev')
                      THEN NOT has_database_privilege('ls_core_app_dev', 'ls_core_dev', 'CONNECT')
                      ELSE true END END AS configure_acl
\gset plan_

\echo 'Create NOLOGIN role:' :plan_create_role
\echo 'Create UTF8 database owned by postgres (template0):' :plan_create_database
\echo 'Remove PUBLIC access and grant only CONNECT on target database:' :plan_configure_acl
\echo 'Set password only for new/incomplete NOLOGIN role:' :plan_set_new_password
\echo 'Enable LOGIN after permissions are ready:' :plan_enable_login

SELECT :'plan_create_role'::boolean OR :'plan_create_database'::boolean OR :'plan_configure_acl'::boolean
       OR :'plan_set_new_password'::boolean OR :'plan_enable_login'::boolean
       OR :'plan_adopt_role'::boolean AS changes \gset
\if :apply
  \if :plan_adopt_role
    \if :adopt
    \else
      DO $$ BEGIN RAISE EXCEPTION 'Adoption required: rerun with -Apply -AdoptExistingRole after reviewing inspection. No changes applied.'; END $$;
    \endif
  \endif
  \if :changes
    \if :plan_adopt_role
      \echo 'Confirm you personally created this role for LS Core and it is not used by other applications.'
      \prompt 'Type ADOPT ls_core_app_dev AND APPLY to attest this and execute the plan: ' answer
      SELECT :'answer' = 'ADOPT ls_core_app_dev AND APPLY' AS confirmed \gset
    \else
      \prompt 'Type APPLY to execute only this plan (anything else cancels): ' answer
      SELECT :'answer' = 'APPLY' AS confirmed \gset
    \endif
    \unset answer
    \if :confirmed
    \else
      DO $$ BEGIN RAISE EXCEPTION 'Cancelled before applying changes'; END $$;
    \endif
  \else
    \echo 'NO CHANGES: existing resources are valid. No confirmation or password reset needed.'
  \endif
\else
  \echo 'INSPECTION ONLY: no resources changed.'
  \quit
\endif

\if :plan_adopt_role
  BEGIN;
  -- Recheck catalogs after human review and before the first write.
  \ir inspect-bootstrap.sql
  SELECT EXISTS (SELECT FROM pg_authid WHERE rolname = 'ls_core_app_dev'
    AND oid = :'plan_role_oid'::oid AND NOT rolcanlogin
    AND shobj_description(oid, 'pg_authid') IS NULL) AS adoption_still_valid \gset
  \if :adoption_still_valid
  \else
    DO $$ BEGIN RAISE EXCEPTION 'Adoption candidate changed since inspection; cancelled without adoption'; END $$;
  \endif
  ALTER ROLE ls_core_app_dev CONNECTION LIMIT 3;
  COMMENT ON ROLE ls_core_app_dev IS 'LS Core LFD-23 bootstrap v1';
  COMMIT;
  \echo 'ADOPTED: existing role identity preserved, marked, NOLOGIN, CONNECTION LIMIT 3.'
\endif

\if :plan_create_role
  BEGIN;
  CREATE ROLE ls_core_app_dev NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 3;
  COMMENT ON ROLE ls_core_app_dev IS 'LS Core LFD-23 bootstrap v1';
  COMMIT;
\endif

-- Use psql's secure password exchange; no literal/hash is stored in this file.
SELECT NOT rolcanlogin AND rolpassword IS NULL AS password_needed
FROM pg_authid WHERE rolname = 'ls_core_app_dev' \gset
\if :password_needed
  SET password_encryption = 'scram-sha-256';
  \password ls_core_app_dev
  DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_authid WHERE rolname = 'ls_core_app_dev' AND rolpassword IS NOT NULL) THEN
      RAISE EXCEPTION 'Password was not set; role remains NOLOGIN. No automatic reset.';
    END IF;
  END $$;
\endif

-- CREATE DATABASE must be a separate, top-level command. A race fails, never overwrites.
\if :plan_create_database
  CREATE DATABASE ls_core_dev OWNER postgres ENCODING 'UTF8' TEMPLATE template0;
  COMMENT ON DATABASE ls_core_dev IS 'LS Core LFD-23 bootstrap v1';
\endif

\if :plan_configure_acl
  BEGIN;
  REVOKE ALL ON DATABASE ls_core_dev FROM PUBLIC;
  GRANT CONNECT ON DATABASE ls_core_dev TO ls_core_app_dev;
  COMMIT;
\endif

\if :plan_create_role
  ALTER ROLE ls_core_app_dev LOGIN;
\else
  \if :plan_enable_login
    ALTER ROLE ls_core_app_dev LOGIN;
  \endif
\endif

\ir inspect-bootstrap.sql
DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_authid WHERE rolname = 'ls_core_app_dev' AND rolcanlogin AND rolpassword IS NOT NULL)
     OR NOT has_database_privilege('ls_core_app_dev', 'ls_core_dev', 'CONNECT')
     OR has_database_privilege('ls_core_app_dev', 'ls_core_dev', 'CREATE')
     OR has_database_privilege('ls_core_app_dev', 'ls_core_dev', 'TEMPORARY') THEN
    RAISE EXCEPTION 'Final role/database verification failed; resources retained for inspection';
  END IF;
END $$;
SELECT :'before_fingerprint' = md5(coalesce(string_agg(
       ROW(oid, datname, datdba, encoding, datacl, datallowconn)::text, '|' ORDER BY oid), '')) AS others_unchanged
FROM pg_database WHERE datname <> 'ls_core_dev' \gset
\if :others_unchanged
  \echo 'Other database definitions/ACLs unchanged: true'
\else
  DO $$ BEGIN RAISE EXCEPTION 'Other database definitions changed concurrently; administrator review required'; END $$;
\endif
\echo 'ADMIN VERIFICATION OK. No other databases or server settings changed.'

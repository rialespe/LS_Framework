-- Read-only preflight; no role passwords, hashes, or unrelated database names are printed.
\set ON_ERROR_STOP on
\set ECHO none
\set VERBOSITY terse
\pset pager off
BEGIN READ ONLY;
DO $$
BEGIN
    IF current_database() <> 'ls_core_dev' OR current_user <> 'postgres'
       OR session_user <> 'postgres'
       OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999
       OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet THEN
        RAISE EXCEPTION 'Expected existing postgres administrator on local ls_core_dev, PostgreSQL 17';
    END IF;
    IF NOT EXISTS (SELECT FROM pg_database WHERE datname = current_database()
                   AND pg_get_userbyid(datdba) = 'postgres'
                   AND pg_encoding_to_char(encoding) = 'UTF8' AND NOT datistemplate) THEN
        RAISE EXCEPTION 'Unexpected target database owner or encoding';
    END IF;
END $$;
SELECT current_database() AS database_name, current_setting('server_version') AS version,
       inet_server_port() AS port;
SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolreplication,
       rolbypassrls, rolinherit, rolconnlimit
FROM pg_roles WHERE rolname IN ('ls_core_app_dev', 'ls_core_migrator_dev');
SELECT nspname, pg_get_userbyid(nspowner) AS owner
FROM pg_namespace
WHERE nspname IN ('identity', 'platform', 'tenant', 'security', 'catalog',
                  'integration', 'audit', 'operations') ORDER BY nspname;
SELECT EXISTS (SELECT FROM pg_roles WHERE rolname = 'ls_core_migrator_dev') AS existing_role_requires_review,
       count(*) AS existing_authorized_schemas_require_review
FROM pg_namespace WHERE nspname IN ('identity', 'platform', 'tenant', 'security', 'catalog',
                                   'integration', 'audit', 'operations');
SELECT count(*) AS other_databases_with_public_connect_require_security_review
FROM pg_database d
WHERE d.datname <> current_database() AND d.datallowconn
  AND EXISTS (SELECT FROM aclexplode(coalesce(d.datacl, acldefault('d', d.datdba))) a
              WHERE a.grantee = 0 AND a.privilege_type = 'CONNECT');
COMMIT;

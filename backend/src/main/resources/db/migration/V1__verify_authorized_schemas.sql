-- Namespace creation is an explicit administrator preparation, not database CREATE granted to runtime/migrator.
-- Fail closed on a database, role, schema owner or privilege mismatch. No baseline/adoption is attempted.
DO $$
BEGIN
    IF current_database() <> 'ls_core_dev' OR current_user <> 'ls_core_migrator_dev'
       OR session_user <> 'ls_core_migrator_dev'
       OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999 THEN
        RAISE EXCEPTION 'Expected limited LS Core migrator on PostgreSQL 17 ls_core_dev';
    END IF;
    IF (SELECT count(*) FROM pg_namespace
         WHERE nspname IN ('identity', 'platform', 'tenant', 'security', 'catalog',
                           'integration', 'audit', 'operations')
           AND pg_get_userbyid(nspowner) = current_user) <> 8 THEN
        RAISE EXCEPTION 'Eight explicitly provisioned schemas owned by the migrator are required';
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = current_user
               AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolinherit))
       OR has_database_privilege(current_user, current_database(), 'CREATE')
       OR has_database_privilege(current_user, current_database(), 'TEMPORARY')
       OR EXISTS (SELECT FROM pg_auth_members WHERE member = (SELECT oid FROM pg_roles WHERE rolname = current_user)) THEN
        RAISE EXCEPTION 'Migrator privileges exceed the authorized schema-only contract';
    END IF;
END $$;

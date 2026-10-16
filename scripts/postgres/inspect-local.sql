-- Run through psql -X -W -v ON_ERROR_STOP=1. No writes and no private catalog dump.
BEGIN READ ONLY;

SELECT current_setting('server_version') AS server_version,
       inet_server_port() AS server_port;

SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'ls_core_dev') AS database_exists,
       EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ls_core_app_dev') AS role_exists;

-- If resources exist, stop and establish ownership/purpose before reusing them.
SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole,
       rolreplication, rolbypassrls, rolconnlimit
FROM pg_roles WHERE rolname = 'ls_core_app_dev';

COMMIT;

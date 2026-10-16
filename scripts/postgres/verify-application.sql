\set ON_ERROR_STOP on
\set ECHO none
\pset pager off
BEGIN READ ONLY;
DO $$ BEGIN
  IF current_user <> 'ls_core_app_dev' OR current_database() <> 'ls_core_dev'
     OR NOT has_database_privilege(current_user, current_database(), 'CONNECT')
     OR has_database_privilege(current_user, current_database(), 'CREATE')
     OR has_database_privilege(current_user, current_database(), 'TEMPORARY') THEN
    RAISE EXCEPTION 'Application connection/database permissions do not match the bootstrap contract';
  END IF;
  IF EXISTS (SELECT FROM pg_namespace WHERE has_schema_privilege(current_user, oid, 'CREATE')) THEN
    RAISE EXCEPTION 'Application role has effective CREATE on a schema; no automatic repair attempted';
  END IF;
END $$;
SELECT current_database(), current_user, 1;
SELECT has_database_privilege(current_user, current_database(), 'CONNECT') AS can_connect,
       has_database_privilege(current_user, current_database(), 'CREATE') AS can_create,
       has_database_privilege(current_user, current_database(), 'TEMPORARY') AS can_create_temp;
-- Report only a count; PUBLIC may still permit connections elsewhere. Never modify those ACLs.
SELECT count(*) AS other_databases_with_effective_connect
FROM pg_database WHERE datallowconn AND datname <> current_database()
  AND has_database_privilege(current_user, oid, 'CONNECT');
COMMIT;

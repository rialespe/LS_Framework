\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout='5s';
SELECT pg_advisory_xact_lock(250025,17) \gset
DO $$ BEGIN
  IF current_user<>'ls_core_docker' OR session_user<>current_user
     OR current_database()<>'ls_core_docker_dev'
     OR (SELECT system_identifier::text FROM pg_control_system())<>'7698808171985612839'
     OR current_setting('server_version_num')::int NOT BETWEEN 170000 AND 179999 THEN
    RAISE EXCEPTION 'Docker cluster identity mismatch';
  END IF;
  IF EXISTS (SELECT FROM pg_database WHERE datname='ls_core_dev')
     OR EXISTS (SELECT FROM pg_roles WHERE rolname NOT LIKE 'pg_%' AND rolname<>'ls_core_docker')
     OR EXISTS (SELECT FROM pg_stat_activity WHERE pid<>pg_backend_pid()
                AND backend_type='client backend' AND usename<>'ls_core_docker')
     OR EXISTS (SELECT FROM pg_prepared_xacts) THEN
    RAISE EXCEPTION 'Unexpected objects, roles, connections or prepared transactions';
  END IF;
END $$;
CREATE TEMP TABLE before_databases ON COMMIT DROP AS
  SELECT oid, to_jsonb(d)-'datacl' AS metadata FROM pg_database d;
CREATE TEMP TABLE before_acl ON COMMIT DROP AS
  SELECT d.oid, d.datname, a.* FROM pg_database d,
    LATERAL aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a;
DO $$ BEGIN
  IF (SELECT array_agg(datname::text ORDER BY datname) FROM before_acl
       WHERE grantee=0 AND privilege_type='CONNECT' AND datname IN
       (SELECT datname FROM pg_database WHERE datallowconn))
     IS DISTINCT FROM ARRAY['ls_core_docker_dev','postgres','template1'] THEN
    RAISE EXCEPTION 'PUBLIC CONNECT differs from authorized scope';
  END IF;
END $$;
REVOKE CONNECT ON DATABASE ls_core_docker_dev FROM PUBLIC;
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
REVOKE CONNECT ON DATABASE template1 FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (
    (SELECT oid, to_jsonb(d)-'datacl' FROM pg_database d EXCEPT SELECT * FROM before_databases)
    UNION ALL (SELECT * FROM before_databases EXCEPT SELECT oid, to_jsonb(d)-'datacl' FROM pg_database d)
  ) THEN RAISE EXCEPTION 'Unexpected database metadata change; rolling back ACL transaction'; END IF;
  IF EXISTS (
    WITH actual AS (
      SELECT d.oid,d.datname,a.* FROM pg_database d,
        LATERAL aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a
    ), expected AS (
      SELECT * FROM before_acl WHERE NOT (grantee=0 AND privilege_type='CONNECT'
         AND datname IN ('ls_core_docker_dev','postgres','template1'))
    )
    (SELECT * FROM actual EXCEPT SELECT * FROM expected)
    UNION ALL (SELECT * FROM expected EXCEPT SELECT * FROM actual)
  ) THEN RAISE EXCEPTION 'Unexpected ACL change; rolling back ACL transaction'; END IF;
END $$;
COMMIT;

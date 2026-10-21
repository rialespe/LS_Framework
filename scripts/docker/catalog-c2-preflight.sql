-- Additional Docker-only checks, executed before the unchanged original C2 checks.
DO $$ BEGIN
 IF current_user<>'ls_core_docker' OR session_user<>current_user OR current_database()<>'ls_core_dev'
    OR (SELECT system_identifier::text FROM pg_control_system())<>'7698808171985612839'
    OR current_setting('server_version_num')::int NOT BETWEEN 170000 AND 179999 THEN
   RAISE EXCEPTION 'Docker C2 identity mismatch';
 END IF;
 IF (SELECT array_agg(version::int ORDER BY installed_rank) FROM operations.flyway_schema_history
       WHERE success AND installed_by='ls_core_migrator_dev') IS DISTINCT FROM ARRAY[1,2,3,4,5,6,7,8,9]
    OR (SELECT count(*) FROM operations.flyway_schema_history)<>9 THEN
   RAISE EXCEPTION 'Original successful V1-V9 required';
 END IF;
 IF (SELECT array_agg(datname::text ORDER BY datname) FROM pg_database)
      IS DISTINCT FROM ARRAY['ls_core_dev','ls_core_docker_dev','postgres','template0','template1']
    OR EXISTS (SELECT FROM pg_database WHERE pg_get_userbyid(datdba)<>'ls_core_docker') THEN
   RAISE EXCEPTION 'Unexpected database/owner; no adaptation or repair';
 END IF;
 IF (SELECT array_agg(rolname::text ORDER BY rolname) FROM pg_roles WHERE rolname !~ '^pg_')
      IS DISTINCT FROM ARRAY['ls_core_app_dev','ls_core_catalog_reader_dev','ls_core_docker','ls_core_migrator_dev'] THEN
   RAISE EXCEPTION 'Unexpected custom role or C2 already exists';
 END IF;
 IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='ls_core_app_dev' AND rolcanlogin AND rolconnlimit=3)
    OR EXISTS (SELECT FROM pg_roles WHERE rolname IN ('ls_core_app_dev','ls_core_catalog_reader_dev') AND rolconfig IS NOT NULL)
    OR EXISTS (SELECT FROM pg_namespace n,pg_roles r WHERE r.rolname IN ('ls_core_app_dev','ls_core_catalog_reader_dev') AND has_schema_privilege(r.oid,n.oid,'CREATE'))
    OR EXISTS (SELECT FROM pg_auth_members WHERE member IN
       ('ls_core_app_dev'::regrole,'ls_core_catalog_reader_dev'::regrole)
       OR roleid IN ('ls_core_app_dev'::regrole,'ls_core_catalog_reader_dev'::regrole))
    OR EXISTS (SELECT FROM pg_stat_activity WHERE usename IN
       ('ls_core_app_dev','ls_core_catalog_reader_dev','ls_core_catalog_service_dev'))
    OR EXISTS (SELECT FROM pg_prepared_xacts) THEN
   RAISE EXCEPTION 'Unexpected runtime attributes, memberships, sessions or prepared transactions';
 END IF;
 IF EXISTS (SELECT FROM pg_database d,LATERAL aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a
       WHERE a.grantee='ls_core_catalog_reader_dev'::regrole)
    OR EXISTS (SELECT FROM pg_namespace n,LATERAL aclexplode(coalesce(n.nspacl,acldefault('n',n.nspowner))) a
       WHERE a.grantee='ls_core_catalog_reader_dev'::regrole
         AND (n.nspname NOT IN ('catalog','security') OR a.privilege_type<>'USAGE' OR a.is_grantable))
    OR NOT has_schema_privilege('ls_core_catalog_reader_dev','catalog','USAGE')
    OR NOT has_schema_privilege('ls_core_catalog_reader_dev','security','USAGE') THEN
   RAISE EXCEPTION 'Unexpected C1 database/schema grants';
 END IF;
END $$;

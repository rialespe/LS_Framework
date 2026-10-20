\set ON_ERROR_STOP on
DO $$ BEGIN
  IF current_user<>'ls_core_docker' OR current_database()<>'ls_core_dev'
     OR (SELECT system_identifier::text FROM pg_control_system())<>'7698808171985612839' THEN
    RAISE EXCEPTION 'Verification identity mismatch';
  END IF;
  IF (SELECT count(*) FROM operations.flyway_schema_history)<>9
     OR (SELECT array_agg(version::int ORDER BY version::int) FROM operations.flyway_schema_history
          WHERE success AND installed_by='ls_core_migrator_dev') IS DISTINCT FROM ARRAY[1,2,3,4,5,6,7,8,9] THEN
    RAISE EXCEPTION 'Expected nine successful original migrations';
  END IF;
  IF (SELECT count(*) FROM pg_namespace WHERE nspname IN
      ('identity','platform','tenant','security','catalog','integration','audit','operations')
      AND pg_get_userbyid(nspowner)='ls_core_migrator_dev')<>8 THEN
    RAISE EXCEPTION 'Schema owner mismatch';
  END IF;
  IF (SELECT count(*) FROM pg_roles WHERE rolname IN ('ls_core_app_dev','ls_core_migrator_dev')
       AND rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole
       AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit AND rolconnlimit=3
       AND has_database_privilege(oid,'ls_core_dev','CONNECT')
       AND NOT has_database_privilege(oid,'ls_core_dev','CREATE,TEMPORARY'))<>2
     OR EXISTS (SELECT FROM pg_auth_members WHERE member IN
          (SELECT oid FROM pg_roles WHERE rolname IN ('ls_core_app_dev','ls_core_migrator_dev'))
          OR roleid IN (SELECT oid FROM pg_roles WHERE rolname IN ('ls_core_app_dev','ls_core_migrator_dev')))
     OR EXISTS (SELECT FROM pg_database d,pg_roles r WHERE d.datallowconn AND d.datname<>'ls_core_dev'
          AND r.rolname IN ('ls_core_app_dev','ls_core_migrator_dev') AND has_database_privilege(r.oid,d.oid,'CONNECT')) THEN
    RAISE EXCEPTION 'Role privileges or cross-database CONNECT mismatch';
  END IF;
END $$;
CREATE TEMP TABLE verified_relations AS
SELECT n.nspname||'.'||c.relname AS name,c.oid,c.relrowsecurity,c.relforcerowsecurity,c.relowner
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE c.relkind='r' AND n.nspname IN
  ('identity','platform','tenant','security','catalog','integration','audit','operations')
  AND c.relname<>'flyway_schema_history';
DO $$ DECLARE row record; total integer:=0; counts integer[]:='{}'; BEGIN
  IF (SELECT count(*) FROM verified_relations)<>29
     OR EXISTS (SELECT FROM verified_relations WHERE pg_get_userbyid(relowner)<>'ls_core_migrator_dev')
     OR (SELECT count(*) FROM verified_relations WHERE relrowsecurity AND relforcerowsecurity)<>22
     OR EXISTS (SELECT FROM pg_policy WHERE polrelid IN (SELECT oid FROM verified_relations)) THEN
    RAISE EXCEPTION '29 tables / 22 FORCE RLS / no policies contract mismatch';
  END IF;
  FOR row IN SELECT * FROM verified_relations ORDER BY name LOOP
    IF row.name=ANY(ARRAY['catalog.applications','catalog.modules','security.permissions','security.role_templates',
        'security.role_template_permissions','security.profile_templates','security.profile_template_roles']) THEN
      IF row.relrowsecurity OR row.relforcerowsecurity THEN RAISE EXCEPTION 'Global catalog RLS mismatch'; END IF;
      EXECUTE 'SELECT count(*) FROM '||row.name INTO total;
      counts:=array_append(counts,total);
    ELSE
      IF NOT row.relrowsecurity OR NOT row.relforcerowsecurity THEN RAISE EXCEPTION 'Missing protected-table RLS'; END IF;
      EXECUTE 'SELECT count(*) FROM '||row.name INTO total;
      IF total<>0 THEN RAISE EXCEPTION 'Unexpected domain data'; END IF;
    END IF;
  END LOOP;
  IF counts<>ARRAY[1,3,9,2,2,9,2] THEN
    RAISE EXCEPTION 'V8 seed distribution mismatch: %',counts;
  END IF;
END $$;
SELECT json_build_object('versions',(SELECT json_agg(version ORDER BY installed_rank) FROM operations.flyway_schema_history),
 'domain_tables',29,'seed_rows',28,'global_catalogs',7,'force_rls_tables',22,'policies',0,'restricted_roles',2);

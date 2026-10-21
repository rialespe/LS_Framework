DO $$ BEGIN
 IF (SELECT system_identifier::text FROM pg_control_system())<>'7698808171985612839'
    OR current_database()<>'ls_core_dev' OR current_user<>'ls_core_docker' THEN
   RAISE EXCEPTION 'Docker verification identity mismatch';
 END IF;
 IF NOT EXISTS (SELECT FROM pg_authid WHERE rolname='ls_core_catalog_service_dev' AND rolcanlogin
    AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolinherit)
    AND rolconnlimit=3 AND rolpassword LIKE 'SCRAM-SHA-256$%')
    OR (SELECT count(*) FROM pg_auth_members WHERE member='ls_core_catalog_service_dev'::regrole)<>1
    OR NOT EXISTS (SELECT FROM pg_auth_members WHERE member='ls_core_catalog_service_dev'::regrole
       AND roleid='ls_core_catalog_reader_dev'::regrole AND set_option AND NOT inherit_option AND NOT admin_option)
    OR EXISTS (SELECT FROM pg_auth_members WHERE roleid='ls_core_catalog_service_dev'::regrole)
    OR EXISTS (SELECT FROM pg_database WHERE datallowconn AND
       (has_database_privilege('ls_core_catalog_service_dev',oid,'CONNECT')<>(datname='ls_core_dev')
        OR has_database_privilege('ls_core_catalog_service_dev',oid,'CREATE')))
    OR has_database_privilege('ls_core_catalog_service_dev','ls_core_dev','TEMPORARY') THEN
   RAISE EXCEPTION 'C2 restricted identity/membership/database contract mismatch';
 END IF;
END $$;

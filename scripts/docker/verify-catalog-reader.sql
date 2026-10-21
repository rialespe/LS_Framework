BEGIN READ ONLY;
DO $$ BEGIN
 IF session_user<>'ls_core_catalog_service_dev' OR current_user<>session_user
    OR current_database()<>'ls_core_dev' THEN RAISE EXCEPTION 'Unexpected catalog service identity'; END IF;
 BEGIN
   PERFORM id FROM catalog.applications;
   RAISE EXCEPTION 'C2 read succeeded without SET ROLE';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
END $$;
SET LOCAL ROLE ls_core_catalog_reader_dev;
DO $$ BEGIN
 IF session_user<>'ls_core_catalog_service_dev' OR current_user<>'ls_core_catalog_reader_dev' THEN
   RAISE EXCEPTION 'Explicit catalog role switch missing';
 END IF;
 BEGIN
   PERFORM created_at FROM catalog.applications;
   RAISE EXCEPTION 'Unauthorized catalog column readable';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 BEGIN
   PERFORM id FROM tenant.tenants;
   RAISE EXCEPTION 'Enterprise data readable';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
END $$;
SELECT json_build_object('session_user',session_user,'current_user',current_user,
 'applications',(SELECT json_agg(x ORDER BY code,id) FROM
    (SELECT id,code,name,description,status FROM catalog.applications) x),
 'modules',(SELECT json_agg(x ORDER BY code,id) FROM
    (SELECT id,application_id,code,name,description,status FROM catalog.modules) x),
 'enterprise_and_extra_column_denied',true,'direct_c2_read_denied',true);
COMMIT;

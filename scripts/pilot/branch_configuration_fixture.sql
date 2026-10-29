-- DISPOSABLE FIXTURE ONLY. No LS Fiscal, LS POS, production UUID or approved product contract.
DO $$ DECLARE app uuid=gen_random_uuid(); mod uuid=gen_random_uuid(); BEGIN
 IF session_user<>'postgres' OR current_user<>session_user OR current_database()<>'ls_core_dev'
 OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
 OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999
 OR current_setting('port')::integer IN (5432,55432)
 OR position('ls-core-migrations-test-' IN current_setting('data_directory'))=0 THEN
 RAISE EXCEPTION 'Owned disposable PG17 fixture required'; END IF;
 INSERT INTO catalog.applications(id,code,name,status) VALUES(app,'fixture-config-lfd44','FIXTURE LFD-44 - NO PRODUCTIVO','PUBLISHED');
 INSERT INTO catalog.modules(id,application_id,code,name,status) VALUES(mod,app,'fixture-settings','Configuracion de prueba desechable','PUBLISHED');
 INSERT INTO catalog.tenant_applications(tenant_id,application_id,status) VALUES('__TENANT__',app,'ACTIVE');
 INSERT INTO catalog.tenant_modules(tenant_id,application_id,module_id,status) VALUES('__TENANT__',app,mod,'ACTIVE');
 INSERT INTO catalog.branch_configuration_definitions(application_id,module_id,version,status,tenant_administration_allowed,approval_reference)
 VALUES(app,mod,1,'DRAFT',true,'DISPOSABLE-FIXTURE-LFD-44');
 INSERT INTO catalog.branch_configuration_fields(application_id,module_id,version,field_key,label,value_type,required,max_length,minimum,maximum) VALUES
 (app,mod,1,'label','Etiqueta de prueba','TEXT',true,40,NULL,NULL),
 (app,mod,1,'capacity','Capacidad de prueba','INTEGER',true,NULL,1,10),
 (app,mod,1,'verified','Verificacion de prueba','BOOLEAN',false,NULL,NULL,NULL);
 UPDATE catalog.branch_configuration_definitions SET status='PUBLISHED' WHERE application_id=app AND module_id=mod AND version=1;
END $$;

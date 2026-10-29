-- LFD-44. Empty contract registry: no product modules, definitions, grants or configurations seeded.
DO $$ BEGIN
 IF session_user<>'ls_core_migrator_dev' OR current_user<>session_user OR current_database()<>'ls_core_dev'
 OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999
 OR (SELECT count(*) FROM pg_roles r WHERE rolname IN ('ls_core_branch_config_dev','ls_core_branch_config_log_dev')
 AND rolcanlogin AND NOT(rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
 AND rolconnlimit=CASE WHEN rolname='ls_core_branch_config_dev' THEN 3 ELSE 2 END
 AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
 AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o')
 AND NOT has_database_privilege(r.oid,current_database(),'CREATE,TEMPORARY'))<>2 THEN
 RAISE EXCEPTION 'Expected V25 and fresh configuration channels'; END IF;
END $$;
CREATE TABLE catalog.branch_configuration_definitions (
 application_id uuid NOT NULL, module_id uuid NOT NULL, version integer NOT NULL CHECK(version BETWEEN 1 AND 100000),
 status text NOT NULL CHECK(status IN ('DRAFT','PUBLISHED','RETIRED')),
 tenant_administration_allowed boolean NOT NULL DEFAULT false,
 approval_reference text NOT NULL CHECK(char_length(approval_reference) BETWEEN 1 AND 200 AND approval_reference !~ '[[:cntrl:]]'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(application_id,module_id,version),
 FOREIGN KEY(application_id,module_id) REFERENCES catalog.modules(application_id,id)
);
CREATE TABLE catalog.branch_configuration_fields (
 application_id uuid NOT NULL, module_id uuid NOT NULL, version integer NOT NULL,
 field_key text NOT NULL CHECK(field_key ~ '^[a-z][a-z0-9_]{0,39}$'),
 label text NOT NULL CHECK(char_length(label) BETWEEN 1 AND 100 AND label !~ '[[:cntrl:]]'),
 value_type text NOT NULL CHECK(value_type IN ('TEXT','INTEGER','BOOLEAN')), required boolean NOT NULL,
 max_length integer, minimum integer, maximum integer,
 PRIMARY KEY(application_id,module_id,version,field_key),
 FOREIGN KEY(application_id,module_id,version) REFERENCES catalog.branch_configuration_definitions(application_id,module_id,version),
 CHECK((value_type='TEXT' AND max_length IS NOT NULL AND max_length BETWEEN 1 AND 200 AND minimum IS NULL AND maximum IS NULL)
 OR (value_type='INTEGER' AND max_length IS NULL AND minimum IS NOT NULL AND maximum IS NOT NULL AND minimum BETWEEN -1000000 AND 1000000 AND maximum BETWEEN minimum AND 1000000)
 OR (value_type='BOOLEAN' AND max_length IS NULL AND minimum IS NULL AND maximum IS NULL))
);
CREATE TABLE platform.branch_configuration_grants (
 designation_id uuid NOT NULL REFERENCES platform.administrators(id), application_id uuid NOT NULL,module_id uuid NOT NULL,
 capability text NOT NULL CHECK(capability IN ('READ','MANAGE')),
 PRIMARY KEY(designation_id,application_id,module_id,capability),
 FOREIGN KEY(application_id,module_id) REFERENCES catalog.modules(application_id,id)
);
CREATE TABLE tenant.branch_configuration_grants (
 tenant_id uuid NOT NULL, designation_id uuid NOT NULL,application_id uuid NOT NULL,module_id uuid NOT NULL,
 capability text NOT NULL CHECK(capability IN ('READ','MANAGE')),
 PRIMARY KEY(tenant_id,designation_id,application_id,module_id,capability),
 FOREIGN KEY(tenant_id,designation_id) REFERENCES tenant.administrators(tenant_id,id),
 FOREIGN KEY(tenant_id,application_id,module_id) REFERENCES catalog.tenant_modules(tenant_id,application_id,module_id)
);
CREATE TABLE tenant.branch_configurations (
 tenant_id uuid NOT NULL,branch_id uuid NOT NULL,application_id uuid NOT NULL,module_id uuid NOT NULL,definition_version integer NOT NULL,
 values_json jsonb NOT NULL CHECK(jsonb_typeof(values_json)='object' AND octet_length(values_json::text)<=4096),
 readiness text NOT NULL CHECK(readiness IN ('DRAFT','READY')), revision bigint NOT NULL CHECK(revision>0),
 updated_at timestamptz NOT NULL,actor_user_id uuid NOT NULL REFERENCES identity.users(id),request_id uuid NOT NULL UNIQUE,
 PRIMARY KEY(tenant_id,branch_id,application_id,module_id,definition_version),
 FOREIGN KEY(tenant_id,branch_id) REFERENCES tenant.branches(tenant_id,id),
 FOREIGN KEY(tenant_id,application_id) REFERENCES catalog.tenant_applications(tenant_id,application_id),
 FOREIGN KEY(tenant_id,application_id,module_id) REFERENCES catalog.tenant_modules(tenant_id,application_id,module_id),
 FOREIGN KEY(application_id,module_id,definition_version) REFERENCES catalog.branch_configuration_definitions(application_id,module_id,version)
);
CREATE TABLE tenant.branch_configuration_operations (
 request_id uuid PRIMARY KEY,actor_user_id uuid NOT NULL REFERENCES identity.users(id),
 tenant_id uuid NOT NULL,branch_id uuid NOT NULL,application_id uuid NOT NULL,module_id uuid NOT NULL,definition_version integer NOT NULL,
 action text NOT NULL CHECK(action IN ('PREPARE','UPDATE','VALIDATE')),expected_revision bigint,
 values_json jsonb NOT NULL,previous_readiness text,readiness text NOT NULL,revision bigint NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(tenant_id,branch_id,application_id,module_id,definition_version)
 REFERENCES tenant.branch_configurations(tenant_id,branch_id,application_id,module_id,definition_version) DEFERRABLE INITIALLY DEFERRED
);
CREATE FUNCTION tenant.branch_configuration_barrier() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN PERFORM pg_advisory_xact_lock(370019,1); PERFORM pg_advisory_xact_lock(410023,1); RETURN NULL; END $$;
-- Registry/grant installers and state changes obey the same order BEFORE locking any catalog row.
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['catalog.branch_configuration_definitions','catalog.branch_configuration_fields','platform.branch_administration_grants','platform.branch_configuration_grants','tenant.branch_configuration_grants',
 'catalog.applications','catalog.modules','catalog.tenant_applications','catalog.tenant_modules'] LOOP
 EXECUTE format('CREATE TRIGGER configuration_coordination BEFORE INSERT OR UPDATE OR DELETE ON %s FOR EACH STATEMENT EXECUTE FUNCTION tenant.branch_configuration_barrier()',tab);
 END LOOP;
END $$;
CREATE FUNCTION catalog.guard_branch_configuration_contract() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE state text; count_fields integer;
BEGIN
 IF current_user<>'postgres' OR session_user<>current_user OR TG_OP='DELETE' THEN
 RAISE EXCEPTION 'Controlled contract installation required' USING ERRCODE='42501'; END IF;
 IF TG_TABLE_NAME='branch_configuration_fields' THEN
 IF TG_OP='UPDATE' AND (OLD.application_id,OLD.module_id,OLD.version,OLD.field_key) IS DISTINCT FROM (NEW.application_id,NEW.module_id,NEW.version,NEW.field_key) THEN
 RAISE EXCEPTION 'Immutable field identity' USING ERRCODE='42501'; END IF;
 SELECT status INTO state FROM catalog.branch_configuration_definitions WHERE application_id=NEW.application_id AND module_id=NEW.module_id AND version=NEW.version;
 IF state IS DISTINCT FROM 'DRAFT' THEN RAISE EXCEPTION 'Published fields immutable' USING ERRCODE='42501'; END IF;
 SELECT count(*) INTO count_fields FROM catalog.branch_configuration_fields WHERE application_id=NEW.application_id AND module_id=NEW.module_id AND version=NEW.version;
 IF TG_OP='INSERT' AND count_fields>=16 THEN RAISE EXCEPTION 'Maximum sixteen fields' USING ERRCODE='23514'; END IF;
 ELSE
 IF TG_OP='INSERT' AND NEW.status<>'DRAFT' THEN RAISE EXCEPTION 'Install schema before publication' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' THEN
 IF (OLD.application_id,OLD.module_id,OLD.version,OLD.tenant_administration_allowed,OLD.approval_reference,OLD.created_at) IS DISTINCT FROM (NEW.application_id,NEW.module_id,NEW.version,NEW.tenant_administration_allowed,NEW.approval_reference,NEW.created_at)
 OR NOT ((OLD.status='DRAFT' AND NEW.status='PUBLISHED') OR (OLD.status='PUBLISHED' AND NEW.status='RETIRED')) THEN
 RAISE EXCEPTION 'Immutable versioned contract' USING ERRCODE='42501'; END IF;
 SELECT count(*) INTO count_fields FROM catalog.branch_configuration_fields WHERE application_id=NEW.application_id AND module_id=NEW.module_id AND version=NEW.version;
 IF NEW.status='PUBLISHED' AND count_fields NOT BETWEEN 1 AND 16 THEN
 RAISE EXCEPTION 'One to sixteen explicit fields required' USING ERRCODE='23514'; END IF;
 END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER configuration_contract_guard BEFORE INSERT OR UPDATE OR DELETE ON catalog.branch_configuration_definitions FOR EACH ROW EXECUTE FUNCTION catalog.guard_branch_configuration_contract();
CREATE TRIGGER configuration_field_guard BEFORE INSERT OR UPDATE OR DELETE ON catalog.branch_configuration_fields FOR EACH ROW EXECUTE FUNCTION catalog.guard_branch_configuration_contract();
CREATE FUNCTION tenant.branch_configuration_authority(actor uuid,company uuid,app uuid,mod uuid,ver integer,mutating boolean) RETURNS boolean
 LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
 SELECT tenant.branch_administration_authority(actor,company,mutating) AND (
 EXISTS(SELECT FROM platform.administrators a WHERE a.user_id=actor AND a.status='ACTIVE' AND a.revoked_at IS NULL
 AND (a.administrator_kind='OWNER' OR (a.administrator_kind='SUPERADMIN'
 AND EXISTS(SELECT FROM platform.branch_configuration_grants g WHERE g.designation_id=a.id AND g.application_id=app AND g.module_id=mod AND g.capability='READ')
 AND (NOT mutating OR EXISTS(SELECT FROM platform.branch_configuration_grants g WHERE g.designation_id=a.id AND g.application_id=app AND g.module_id=mod AND g.capability='MANAGE')))))
 OR (EXISTS(SELECT FROM catalog.branch_configuration_definitions d WHERE d.application_id=app AND d.module_id=mod AND d.version=ver AND d.tenant_administration_allowed)
 AND EXISTS(SELECT FROM tenant.valid_administrators(company) a WHERE a.user_id=actor
 AND EXISTS(SELECT FROM tenant.branch_configuration_grants g WHERE g.tenant_id=company AND g.designation_id=a.id AND g.application_id=app AND g.module_id=mod AND g.capability='READ')
 AND (NOT mutating OR EXISTS(SELECT FROM tenant.branch_configuration_grants g WHERE g.tenant_id=company AND g.designation_id=a.id AND g.application_id=app AND g.module_id=mod AND g.capability='MANAGE')))))
$$;
CREATE FUNCTION tenant.check_branch_configuration(app uuid,mod uuid,ver integer,vals jsonb,complete boolean) RETURNS text[]
 LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE item record; field record; missing text[]='{}';
BEGIN
 IF vals IS NULL OR jsonb_typeof(vals)<>'object' OR octet_length(vals::text)>4096 THEN
 RAISE EXCEPTION 'Bounded object required' USING ERRCODE='P2602'; END IF;
 FOR item IN SELECT key,value FROM jsonb_each(vals) LOOP
 SELECT * INTO field FROM catalog.branch_configuration_fields WHERE application_id=app AND module_id=mod AND version=ver AND field_key=item.key;
 IF NOT FOUND OR item.value='null'::jsonb THEN RAISE EXCEPTION 'Unknown or null field' USING ERRCODE='P2602'; END IF;
 IF field.value_type='TEXT' THEN
 IF jsonb_typeof(item.value)<>'string' OR char_length(item.value#>>'{}')>field.max_length OR (item.value#>>'{}') ~ '[[:cntrl:]]' THEN
 RAISE EXCEPTION 'Invalid text field' USING ERRCODE='P2602'; END IF;
 ELSIF field.value_type='BOOLEAN' THEN
 IF jsonb_typeof(item.value)<>'boolean' THEN RAISE EXCEPTION 'Invalid boolean field' USING ERRCODE='P2602'; END IF;
 ELSE
 IF jsonb_typeof(item.value)<>'number' OR item.value::text !~ '^-?[0-9]{1,7}$' THEN RAISE EXCEPTION 'Invalid integer field' USING ERRCODE='P2602'; END IF;
 IF (item.value::text)::integer NOT BETWEEN field.minimum AND field.maximum THEN RAISE EXCEPTION 'Integer out of range' USING ERRCODE='P2602'; END IF;
 END IF;
 END LOOP;
 FOR field IN SELECT * FROM catalog.branch_configuration_fields WHERE application_id=app AND module_id=mod AND version=ver AND required ORDER BY field_key LOOP
 IF NOT vals ? field.field_key OR (field.value_type='TEXT' AND btrim(vals->>field.field_key)='') THEN missing=array_append(missing,field.field_key); END IF;
 END LOOP;
 IF complete AND cardinality(missing)>0 THEN RAISE EXCEPTION 'Required configuration fields missing' USING ERRCODE='P2602'; END IF;
 RETURN missing;
END $$;
CREATE FUNCTION tenant.apply_branch_configuration() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE prior_config tenant.branch_configurations%ROWTYPE; state text;
BEGIN
 IF current_user<>'ls_core_branch_config_dev' OR session_user<>current_user OR TG_OP<>'INSERT' OR current_setting('transaction_isolation')<>'read committed' THEN
 RAISE EXCEPTION 'Configuration channel unavailable' USING ERRCODE='42501'; END IF;
 PERFORM pg_advisory_xact_lock(370019,1); PERFORM pg_advisory_xact_lock(410023,1);
 SELECT status INTO state FROM tenant.tenants WHERE id=NEW.tenant_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Tenant unavailable' USING ERRCODE='P2604'; END IF;
 IF state<>'ACTIVE' THEN RAISE EXCEPTION 'Tenant must be active' USING ERRCODE='P2601'; END IF;
 PERFORM id FROM identity.users WHERE id=NEW.actor_user_id FOR SHARE;
 PERFORM user_id FROM identity.user_credentials WHERE user_id=NEW.actor_user_id FOR SHARE;
 SELECT status INTO state FROM tenant.branches WHERE tenant_id=NEW.tenant_id AND id=NEW.branch_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Branch unavailable' USING ERRCODE='P2604'; END IF;
 IF state<>'ACTIVE' THEN RAISE EXCEPTION 'Branch must be active' USING ERRCODE='P2601'; END IF;
 IF NOT tenant.branch_configuration_authority(NEW.actor_user_id,NEW.tenant_id,NEW.application_id,NEW.module_id,NEW.definition_version,true) THEN
 RAISE EXCEPTION 'Module configuration not authorized' USING ERRCODE='42501'; END IF;
 PERFORM id FROM catalog.applications WHERE id=NEW.application_id AND status='PUBLISHED' FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Application unavailable' USING ERRCODE='P2603'; END IF;
 PERFORM id FROM catalog.modules WHERE application_id=NEW.application_id AND id=NEW.module_id AND status='PUBLISHED' FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Module unavailable' USING ERRCODE='P2603'; END IF;
 PERFORM id FROM catalog.tenant_applications WHERE tenant_id=NEW.tenant_id AND application_id=NEW.application_id AND status='ACTIVE' FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Application not enabled' USING ERRCODE='P2603'; END IF;
 PERFORM id FROM catalog.tenant_modules WHERE tenant_id=NEW.tenant_id AND application_id=NEW.application_id AND module_id=NEW.module_id AND status='ACTIVE' FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Module not enabled' USING ERRCODE='P2603'; END IF;
 PERFORM version FROM catalog.branch_configuration_definitions WHERE application_id=NEW.application_id AND module_id=NEW.module_id AND version=NEW.definition_version AND status='PUBLISHED' FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Contract unavailable' USING ERRCODE='P2603'; END IF;
 PERFORM field_key FROM catalog.branch_configuration_fields WHERE application_id=NEW.application_id AND module_id=NEW.module_id AND version=NEW.definition_version ORDER BY field_key FOR SHARE;
 SELECT * INTO prior_config FROM tenant.branch_configurations WHERE tenant_id=NEW.tenant_id AND branch_id=NEW.branch_id AND application_id=NEW.application_id AND module_id=NEW.module_id AND definition_version=NEW.definition_version FOR UPDATE;
 IF NEW.action='PREPARE' THEN
 IF FOUND OR NEW.expected_revision IS NOT NULL THEN RAISE EXCEPTION 'Duplicate configuration' USING ERRCODE='P2601'; END IF;
 NEW.revision=1; NEW.previous_readiness=NULL;
 ELSE
 IF NOT FOUND THEN RAISE EXCEPTION 'Configuration unavailable' USING ERRCODE='P2604'; END IF;
 IF prior_config.revision IS DISTINCT FROM NEW.expected_revision THEN RAISE EXCEPTION 'Stale configuration revision' USING ERRCODE='P2601'; END IF;
 NEW.revision=prior_config.revision+1; NEW.previous_readiness=prior_config.readiness;
 IF NEW.action='VALIDATE' THEN NEW.values_json=prior_config.values_json; END IF;
 END IF;
 PERFORM tenant.check_branch_configuration(NEW.application_id,NEW.module_id,NEW.definition_version,NEW.values_json,NEW.action='VALIDATE');
 NEW.readiness=CASE NEW.action WHEN 'VALIDATE' THEN 'READY' ELSE 'DRAFT' END;
 NEW.created_at=clock_timestamp(); RETURN NEW;
END $$;
CREATE TRIGGER configuration_operation_guard BEFORE INSERT OR UPDATE OR DELETE ON tenant.branch_configuration_operations FOR EACH ROW EXECUTE FUNCTION tenant.apply_branch_configuration();
CREATE TRIGGER configuration_operation_no_truncate BEFORE TRUNCATE ON tenant.branch_configuration_operations FOR EACH STATEMENT EXECUTE FUNCTION tenant.apply_branch_configuration();
CREATE FUNCTION tenant.complete_branch_configuration() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.action='PREPARE' THEN
 INSERT INTO tenant.branch_configurations VALUES(NEW.tenant_id,NEW.branch_id,NEW.application_id,NEW.module_id,NEW.definition_version,NEW.values_json,NEW.readiness,NEW.revision,NEW.created_at,NEW.actor_user_id,NEW.request_id);
 ELSE
 UPDATE tenant.branch_configurations SET values_json=NEW.values_json,readiness=NEW.readiness,revision=NEW.revision,updated_at=NEW.created_at,actor_user_id=NEW.actor_user_id,request_id=NEW.request_id
 WHERE tenant_id=NEW.tenant_id AND branch_id=NEW.branch_id AND application_id=NEW.application_id AND module_id=NEW.module_id AND definition_version=NEW.definition_version;
 END IF;
 INSERT INTO audit.events(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
 VALUES(clock_timestamp(),'USER',NEW.actor_user_id,'BRANCH_CONFIG_'||NEW.action,'TENANT_BRANCH',NEW.branch_id,'SUCCESS',NEW.request_id,
 jsonb_build_object('tenant_id',NEW.tenant_id,'application_id',NEW.application_id,'module_id',NEW.module_id,'definition_version',NEW.definition_version,'revision',NEW.revision,'from',NEW.previous_readiness,'to',NEW.readiness));
 RETURN NEW;
END $$;
CREATE TRIGGER configuration_operation_complete AFTER INSERT ON tenant.branch_configuration_operations FOR EACH ROW EXECUTE FUNCTION tenant.complete_branch_configuration();
CREATE FUNCTION tenant.guard_branch_configuration_child() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF current_user<>'ls_core_branch_config_dev' OR session_user<>current_user OR TG_OP NOT IN ('INSERT','UPDATE') OR pg_trigger_depth()<>2 THEN
 RAISE EXCEPTION 'Atomic configuration operation required' USING ERRCODE='42501'; END IF; RETURN NEW;
END $$;
CREATE TRIGGER configuration_child BEFORE INSERT OR UPDATE OR DELETE ON tenant.branch_configurations FOR EACH ROW EXECUTE FUNCTION tenant.guard_branch_configuration_child();
CREATE FUNCTION audit.guard_branch_configuration_event() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP IN ('UPDATE','DELETE') THEN
 IF OLD.action_code IN ('BRANCH_CONFIG_PREPARE','BRANCH_CONFIG_UPDATE','BRANCH_CONFIG_VALIDATE') THEN RAISE EXCEPTION 'Immutable configuration evidence' USING ERRCODE='42501'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 END IF;
 IF NEW.action_code IN ('BRANCH_CONFIG_PREPARE','BRANCH_CONFIG_UPDATE','BRANCH_CONFIG_VALIDATE') OR current_user IN ('ls_core_branch_config_dev','ls_core_branch_config_log_dev') THEN
 IF TG_OP<>'INSERT' OR session_user<>current_user OR NEW.actor_kind<>'USER' OR NEW.actor_user_id IS NULL OR NEW.resource_type IS DISTINCT FROM 'TENANT_BRANCH'
 OR NEW.correlation_id IS NULL OR NEW.tenant_id IS NOT NULL OR NEW.application_id IS NOT NULL OR NEW.session_id IS NOT NULL OR NEW.actor_api_key_id IS NOT NULL THEN
 RAISE EXCEPTION 'Invalid configuration evidence' USING ERRCODE='42501'; END IF;
 IF current_user='ls_core_branch_config_dev' THEN
 IF pg_trigger_depth()<>2 OR NEW.outcome<>'SUCCESS' OR NOT EXISTS(SELECT FROM tenant.branch_configuration_operations o WHERE o.request_id=NEW.correlation_id AND o.actor_user_id=NEW.actor_user_id
 AND o.branch_id=NEW.resource_id AND NEW.action_code='BRANCH_CONFIG_'||o.action AND NEW.metadata=jsonb_build_object('tenant_id',o.tenant_id,'application_id',o.application_id,'module_id',o.module_id,'definition_version',o.definition_version,'revision',o.revision,'from',o.previous_readiness,'to',o.readiness)) THEN
 RAISE EXCEPTION 'Atomic evidence required' USING ERRCODE='42501'; END IF;
 ELSIF current_user='ls_core_branch_config_log_dev' THEN
 IF NEW.action_code NOT IN ('BRANCH_CONFIG_PREPARE','BRANCH_CONFIG_UPDATE','BRANCH_CONFIG_VALIDATE') OR NEW.outcome NOT IN ('DENIED','UNKNOWN') OR NEW.metadata<>'{}'::jsonb THEN RAISE EXCEPTION 'Rejection evidence required' USING ERRCODE='42501'; END IF;
 ELSE RAISE EXCEPTION 'Configuration evidence unavailable' USING ERRCODE='42501'; END IF;
 NEW.occurred_at=clock_timestamp(); NEW.created_at=NEW.occurred_at;
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER configuration_event BEFORE INSERT OR UPDATE OR DELETE ON audit.events FOR EACH ROW EXECUTE FUNCTION audit.guard_branch_configuration_event();

GRANT USAGE ON SCHEMA identity,platform,tenant,catalog,audit TO ls_core_branch_config_dev;
GRANT USAGE ON SCHEMA audit TO ls_core_branch_config_log_dev;
GRANT SELECT(id,username,status),UPDATE(id) ON identity.users TO ls_core_branch_config_dev;
GRANT SELECT(user_id,must_change_password,password_changed_at,password_expires_at,locked_until),UPDATE(user_id) ON identity.user_credentials TO ls_core_branch_config_dev;
GRANT SELECT(id,user_id,administrator_kind,status,revoked_at) ON platform.administrators TO ls_core_branch_config_dev;
GRANT SELECT(id,name,status),UPDATE(status) ON tenant.tenants TO ls_core_branch_config_dev;
GRANT SELECT(id,tenant_id,code,name,status),UPDATE(status) ON tenant.branches TO ls_core_branch_config_dev;
GRANT SELECT(id,tenant_id,user_id,status) ON tenant.memberships TO ls_core_branch_config_dev;
GRANT SELECT(id,tenant_id,membership_id,status,revoked_at) ON tenant.administrators TO ls_core_branch_config_dev;
GRANT SELECT ON platform.branch_administration_grants,platform.branch_configuration_grants,tenant.branch_configuration_grants,
 catalog.branch_configuration_definitions,catalog.branch_configuration_fields,tenant.branch_configurations,tenant.branch_configuration_operations TO ls_core_branch_config_dev;
GRANT SELECT(id,name,status),UPDATE(id) ON catalog.applications TO ls_core_branch_config_dev;
GRANT SELECT(id,application_id,name,status),UPDATE(id) ON catalog.modules TO ls_core_branch_config_dev;
GRANT SELECT(id,tenant_id,application_id,status),UPDATE(id) ON catalog.tenant_applications TO ls_core_branch_config_dev;
GRANT SELECT(id,tenant_id,application_id,module_id,status),UPDATE(id) ON catalog.tenant_modules TO ls_core_branch_config_dev;
GRANT UPDATE(version) ON catalog.branch_configuration_definitions TO ls_core_branch_config_dev;
GRANT UPDATE(field_key) ON catalog.branch_configuration_fields TO ls_core_branch_config_dev;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['identity.users','identity.user_credentials','platform.administrators','platform.branch_administration_grants','platform.branch_configuration_grants',
 'tenant.tenants','tenant.branches','tenant.memberships','tenant.administrators','tenant.branch_configuration_grants','catalog.tenant_applications','catalog.tenant_modules',
 'catalog.branch_configuration_definitions','catalog.branch_configuration_fields','tenant.branch_configurations','tenant.branch_configuration_operations'] LOOP
 EXECUTE format('CREATE POLICY progressive_configuration_read ON %s FOR SELECT TO ls_core_branch_config_dev USING(session_user=current_user AND current_user=''ls_core_branch_config_dev'')',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['identity.users','identity.user_credentials','tenant.tenants','tenant.branches','catalog.tenant_applications','catalog.tenant_modules','catalog.branch_configuration_definitions','catalog.branch_configuration_fields'] LOOP
 EXECUTE format('CREATE POLICY progressive_configuration_lock ON %s FOR UPDATE TO ls_core_branch_config_dev USING(session_user=current_user AND current_user=''ls_core_branch_config_dev'') WITH CHECK(false)',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['catalog.branch_configuration_definitions','catalog.branch_configuration_fields','platform.branch_configuration_grants','tenant.branch_configuration_grants','tenant.branch_configurations','tenant.branch_configuration_operations'] LOOP
 EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY',tab); EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY',tab);
 END LOOP;
END $$;
-- Global catalog tables remain global; UPDATE exists only for FOR SHARE and a trigger rejects storing writes by this role.
CREATE FUNCTION catalog.guard_configuration_catalog_lock() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN IF current_user='ls_core_branch_config_dev' THEN RAISE EXCEPTION 'Catalog lock only' USING ERRCODE='42501'; END IF; RETURN NEW; END $$;
CREATE TRIGGER configuration_catalog_lock BEFORE UPDATE ON catalog.applications FOR EACH ROW EXECUTE FUNCTION catalog.guard_configuration_catalog_lock();
CREATE TRIGGER configuration_catalog_lock BEFORE UPDATE ON catalog.modules FOR EACH ROW EXECUTE FUNCTION catalog.guard_configuration_catalog_lock();
GRANT INSERT(request_id,actor_user_id,tenant_id,branch_id,application_id,module_id,definition_version,action,expected_revision,values_json,readiness,revision) ON tenant.branch_configuration_operations TO ls_core_branch_config_dev;
CREATE POLICY configuration_command ON tenant.branch_configuration_operations FOR INSERT TO ls_core_branch_config_dev WITH CHECK(session_user=current_user AND current_user='ls_core_branch_config_dev');
GRANT INSERT ON tenant.branch_configurations TO ls_core_branch_config_dev;
GRANT UPDATE(values_json,readiness,revision,updated_at,actor_user_id,request_id) ON tenant.branch_configurations TO ls_core_branch_config_dev;
CREATE POLICY configuration_insert ON tenant.branch_configurations FOR INSERT TO ls_core_branch_config_dev WITH CHECK(session_user=current_user AND pg_trigger_depth()=1);
CREATE POLICY configuration_update ON tenant.branch_configurations FOR UPDATE TO ls_core_branch_config_dev USING(session_user=current_user) WITH CHECK(pg_trigger_depth()=1);
GRANT INSERT(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata) ON audit.events TO ls_core_branch_config_dev,ls_core_branch_config_log_dev;
CREATE POLICY configuration_success ON audit.events FOR INSERT TO ls_core_branch_config_dev WITH CHECK(session_user=current_user AND outcome='SUCCESS' AND action_code IN ('BRANCH_CONFIG_PREPARE','BRANCH_CONFIG_UPDATE','BRANCH_CONFIG_VALIDATE'));
CREATE POLICY configuration_rejection ON audit.events FOR INSERT TO ls_core_branch_config_log_dev WITH CHECK(session_user=current_user AND outcome IN ('DENIED','UNKNOWN') AND action_code IN ('BRANCH_CONFIG_PREPARE','BRANCH_CONFIG_UPDATE','BRANCH_CONFIG_VALIDATE'));
GRANT EXECUTE ON FUNCTION tenant.administrator_ready(uuid),tenant.valid_administrators(uuid),tenant.branch_administration_authority(uuid,uuid,boolean) TO ls_core_branch_config_dev;
DO $$ DECLARE f text; BEGIN
 FOREACH f IN ARRAY ARRAY['tenant.branch_configuration_barrier()','catalog.guard_branch_configuration_contract()',
 'tenant.branch_configuration_authority(uuid,uuid,uuid,uuid,integer,boolean)','tenant.check_branch_configuration(uuid,uuid,integer,jsonb,boolean)',
 'tenant.apply_branch_configuration()','tenant.complete_branch_configuration()','tenant.guard_branch_configuration_child()','audit.guard_branch_configuration_event()','catalog.guard_configuration_catalog_lock()'] LOOP
 EXECUTE 'REVOKE ALL ON FUNCTION '||f||' FROM PUBLIC'; END LOOP;
END $$;
GRANT EXECUTE ON FUNCTION tenant.branch_configuration_authority(uuid,uuid,uuid,uuid,integer,boolean),tenant.check_branch_configuration(uuid,uuid,integer,jsonb,boolean) TO ls_core_branch_config_dev;

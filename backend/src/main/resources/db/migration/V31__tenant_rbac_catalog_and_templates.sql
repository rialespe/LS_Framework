-- LFD-49. Explicit tenant RBAC catalogs; no effective branch assignments.
DO $$ BEGIN
 IF session_user<>'ls_core_migrator_dev' OR current_user<>session_user OR current_database()<>'ls_core_dev'
 OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999
 OR (SELECT count(*) FROM pg_roles r WHERE rolname IN ('ls_core_tenant_rbac_dev','ls_core_tenant_rbac_log_dev')
 AND rolcanlogin AND NOT(rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
 AND rolconnlimit=CASE WHEN rolname='ls_core_tenant_rbac_dev' THEN 3 ELSE 2 END
 AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
 AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o')
 AND NOT has_database_privilege(r.oid,current_database(),'CREATE,TEMPORARY'))<>2
 THEN RAISE EXCEPTION 'Expected V30 and fresh restricted RBAC channels'; END IF;
END $$;
DO $$ DECLARE principal record; relation record; BEGIN
 FOR principal IN SELECT oid FROM pg_roles WHERE rolname IN ('ls_core_tenant_rbac_dev','ls_core_tenant_rbac_log_dev') LOOP
 IF EXISTS(SELECT FROM pg_namespace WHERE has_schema_privilege(principal.oid,oid,'CREATE'))
 OR EXISTS(SELECT FROM pg_database WHERE datallowconn AND datname<>current_database() AND has_database_privilege(principal.oid,oid,'CONNECT'))
 OR NOT has_database_privilege(principal.oid,current_database(),'CONNECT') THEN
 RAISE EXCEPTION 'Incompatible RBAC channel privileges; no repair'; END IF;
 FOR relation IN SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname !~ '^pg_' AND c.relkind IN ('r','p','v','m','f') LOOP
 IF has_table_privilege(principal.oid,relation.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
 OR has_any_column_privilege(principal.oid,relation.oid,'SELECT,INSERT,UPDATE,REFERENCES') THEN
 RAISE EXCEPTION 'RBAC channel has preexisting relation access'; END IF;
 END LOOP;
 END LOOP;
END $$;
-- Empty platform-owned ceilings. Publication is not delegation, including for OWNER.
CREATE TABLE platform.rbac_permission_delegations (
 tenant_id uuid NOT NULL, application_id uuid NOT NULL, permission_id uuid NOT NULL,
 privilege_kind text NOT NULL CHECK(privilege_kind IN ('READ','WRITE')), expires_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,application_id,permission_id),
 FOREIGN KEY(tenant_id,application_id) REFERENCES catalog.tenant_applications(tenant_id,application_id),
 FOREIGN KEY(application_id,permission_id) REFERENCES security.permissions(application_id,id)
);
CREATE TABLE platform.rbac_administrator_grants (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL, application_id uuid NOT NULL,
 platform_designation_id uuid REFERENCES platform.administrators(id), tenant_designation_id uuid,
 capability text NOT NULL CHECK(capability IN ('READ','MANAGE')), expires_at timestamptz NOT NULL,
 FOREIGN KEY(tenant_id,application_id) REFERENCES catalog.tenant_applications(tenant_id,application_id),
 FOREIGN KEY(tenant_id,tenant_designation_id) REFERENCES tenant.administrators(tenant_id,id),
 CHECK(num_nonnulls(platform_designation_id,tenant_designation_id)=1),
 UNIQUE NULLS NOT DISTINCT(tenant_id,application_id,platform_designation_id,tenant_designation_id,capability)
);
-- Legacy rows keep revision 0 and unknown origin version; no automatic adoption or activation.
ALTER TABLE security.roles ADD COLUMN revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0), ADD COLUMN source_template_version integer CHECK(source_template_version>0);
ALTER TABLE security.profiles ADD COLUMN revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0), ADD COLUMN source_template_version integer CHECK(source_template_version>0);
CREATE TABLE security.rbac_operations (
 request_id uuid PRIMARY KEY, actor_user_id uuid NOT NULL REFERENCES identity.users(id), tenant_id uuid NOT NULL,
 application_id uuid NOT NULL, action text NOT NULL CHECK(action IN ('ROLE_CREATE','ROLE_UPDATE','ROLE_DISABLE','ROLE_TEMPLATE','PROFILE_CREATE','PROFILE_UPDATE','PROFILE_DISABLE','PROFILE_TEMPLATE')),
 resource_id uuid NOT NULL, role_id uuid, profile_id uuid, expected_revision bigint,
 template_id uuid, code text, name text, description text,
 permission_ids uuid[] NOT NULL DEFAULT '{}', role_ids uuid[] NOT NULL DEFAULT '{}',
 input jsonb NOT NULL, previous_snapshot jsonb, result_snapshot jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(tenant_id,application_id) REFERENCES catalog.tenant_applications(tenant_id,application_id),
 FOREIGN KEY(tenant_id,application_id,role_id) REFERENCES security.roles(tenant_id,application_id,id),
 FOREIGN KEY(tenant_id,application_id,profile_id) REFERENCES security.profiles(tenant_id,application_id,id),
 CHECK((action LIKE 'ROLE_%' AND role_id=resource_id AND profile_id IS NULL)
 OR (action LIKE 'PROFILE_%' AND profile_id=resource_id AND role_id IS NULL))
);
CREATE INDEX rbac_operations_history_idx ON security.rbac_operations(tenant_id,application_id,created_at DESC,request_id DESC);
CREATE FUNCTION security.rbac_owner(actor uuid) RETURNS boolean LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
 SELECT tenant.administrator_ready(actor) AND EXISTS(SELECT FROM platform.administrators WHERE user_id=actor AND administrator_kind='OWNER' AND status='ACTIVE' AND revoked_at IS NULL)
$$;
CREATE FUNCTION security.rbac_authority(actor uuid,company uuid,app uuid,mutating boolean) RETURNS boolean LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
 SELECT tenant.administrator_ready(actor) AND EXISTS(SELECT FROM tenant.tenants WHERE id=company AND status='ACTIVE')
 AND (security.rbac_owner(actor) OR EXISTS(SELECT FROM platform.rbac_administrator_grants g
 WHERE g.tenant_id=company AND g.application_id=app AND g.capability='READ' AND g.expires_at>clock_timestamp()
 AND (EXISTS(SELECT FROM platform.administrators a WHERE a.id=g.platform_designation_id AND a.user_id=actor AND a.administrator_kind='SUPERADMIN' AND a.status='ACTIVE' AND a.revoked_at IS NULL)
 OR EXISTS(SELECT FROM tenant.valid_administrators(company) a WHERE a.id=g.tenant_designation_id AND a.user_id=actor))
 AND (NOT mutating OR EXISTS(SELECT FROM platform.rbac_administrator_grants w WHERE w.tenant_id=g.tenant_id AND w.application_id=g.application_id
 AND w.platform_designation_id IS NOT DISTINCT FROM g.platform_designation_id AND w.tenant_designation_id IS NOT DISTINCT FROM g.tenant_designation_id
 AND w.capability='MANAGE' AND w.expires_at>clock_timestamp()))))
$$;
CREATE FUNCTION security.rbac_permission_allowed(company uuid,app uuid,permission uuid) RETURNS boolean LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
 SELECT EXISTS(SELECT FROM security.permissions p JOIN catalog.applications a ON a.id=p.application_id
 JOIN catalog.tenant_applications ta ON ta.application_id=a.id AND ta.tenant_id=company
 JOIN platform.rbac_permission_delegations d ON d.tenant_id=company AND d.application_id=p.application_id AND d.permission_id=p.id
 WHERE p.id=permission AND p.application_id=app AND a.status='PUBLISHED' AND p.status='PUBLISHED'
 AND p.resource_scope IN ('TENANT','BRANCH') AND d.expires_at>clock_timestamp()
 AND (ta.status='ACTIVE' OR (ta.status='READ_ONLY' AND d.privilege_kind='READ'))
 AND (p.module_id IS NULL OR EXISTS(SELECT FROM catalog.modules m JOIN catalog.tenant_modules tm ON tm.application_id=m.application_id AND tm.module_id=m.id AND tm.tenant_id=company
 WHERE m.id=p.module_id AND m.application_id=app AND m.status='PUBLISHED'
 AND (tm.status='ACTIVE' OR (tm.status='READ_ONLY' AND d.privilege_kind='READ')))))
$$;
CREATE FUNCTION security.rbac_template_allowed(company uuid,app uuid,template uuid) RETURNS boolean LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
 SELECT EXISTS(SELECT FROM security.role_templates WHERE id=template AND application_id=app AND status='PUBLISHED' AND version>0)
 AND EXISTS(SELECT FROM security.role_template_permissions WHERE role_template_id=template AND application_id=app)
 AND NOT EXISTS(SELECT FROM security.role_template_permissions WHERE role_template_id=template AND application_id=app AND NOT security.rbac_permission_allowed(company,app,permission_id))
$$;
CREATE FUNCTION security.rbac_snapshot(company uuid,app uuid,resource uuid,kind text) RETURNS jsonb LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
 SELECT CASE WHEN kind='ROLE' THEN (SELECT jsonb_build_object('id',r.id,'code',r.code,'name',r.name,'description',r.description,'status',r.status,'revision',r.revision,
 'sourceTemplateId',r.source_role_template_id,'sourceVersion',r.source_template_version,'permissionIds',COALESCE((SELECT jsonb_agg(permission_id ORDER BY permission_id) FROM security.role_permissions WHERE tenant_id=company AND application_id=app AND role_id=r.id),'[]'::jsonb))
 FROM security.roles r WHERE r.tenant_id=company AND r.application_id=app AND r.id=resource)
 ELSE (SELECT jsonb_build_object('id',r.id,'code',r.code,'name',r.name,'description',r.description,'status',r.status,'revision',r.revision,
 'sourceTemplateId',r.source_profile_template_id,'sourceVersion',r.source_template_version,'roleIds',COALESCE((SELECT jsonb_agg(role_id ORDER BY role_id) FROM security.profile_roles WHERE tenant_id=company AND application_id=app AND profile_id=r.id),'[]'::jsonb))
 FROM security.profiles r WHERE r.tenant_id=company AND r.application_id=app AND r.id=resource) END
$$;
CREATE FUNCTION security.materialize_rbac_role(company uuid,app uuid,template uuid) RETURNS uuid LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE source security.role_templates%ROWTYPE; result uuid;
BEGIN
 IF current_user<>'ls_core_tenant_rbac_dev' OR session_user<>current_user OR pg_trigger_depth()<>1 THEN RAISE EXCEPTION 'Atomic RBAC command required' USING ERRCODE='42501'; END IF;
 IF NOT security.rbac_template_allowed(company,app,template) THEN RAISE EXCEPTION 'Template unavailable' USING ERRCODE='42501'; END IF;
 SELECT * INTO STRICT source FROM security.role_templates WHERE id=template AND application_id=app;
 IF (SELECT count(*) FROM security.roles WHERE tenant_id=company AND application_id=app AND source_role_template_id=template)>1 THEN RAISE EXCEPTION 'Template materialization conflict' USING ERRCODE='P3101'; END IF;
 SELECT id INTO result FROM security.roles WHERE tenant_id=company AND application_id=app AND source_role_template_id=template;
 IF result IS NOT NULL THEN
 IF NOT EXISTS(SELECT FROM security.roles WHERE id=result AND status='ACTIVE' AND source_template_version=source.version)
 OR EXISTS((SELECT permission_id FROM security.role_template_permissions WHERE application_id=app AND role_template_id=template)
 EXCEPT (SELECT permission_id FROM security.role_permissions WHERE tenant_id=company AND application_id=app AND role_id=result))
 OR EXISTS((SELECT permission_id FROM security.role_permissions WHERE tenant_id=company AND application_id=app AND role_id=result)
 EXCEPT (SELECT permission_id FROM security.role_template_permissions WHERE application_id=app AND role_template_id=template)) THEN
 RAISE EXCEPTION 'Existing role differs from template' USING ERRCODE='P3101'; END IF;
 RETURN result;
 END IF;
 result=gen_random_uuid();
 INSERT INTO security.roles(id,tenant_id,application_id,code,name,description,status,source_role_template_id,source_template_version,revision)
 VALUES(result,company,app,source.code||'_v'||source.version,source.name,source.description,'ACTIVE',source.id,source.version,1);
 INSERT INTO security.role_permissions(tenant_id,application_id,role_id,permission_id) SELECT company,app,result,permission_id FROM security.role_template_permissions WHERE application_id=app AND role_template_id=template;
 RETURN result;
END $$;
CREATE FUNCTION security.apply_rbac_operation() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE kind text; previous jsonb; permission uuid; selected_role uuid; source security.profile_templates%ROWTYPE; source_role uuid; existing uuid; current_revision bigint; state text;
BEGIN
 IF current_user<>'ls_core_tenant_rbac_dev' OR session_user<>current_user OR TG_OP<>'INSERT' OR current_setting('transaction_isolation')<>'read committed' THEN RAISE EXCEPTION 'RBAC channel unavailable' USING ERRCODE='42501'; END IF;
 PERFORM pg_advisory_xact_lock(370019,1); PERFORM pg_advisory_xact_lock(410023,1);
 PERFORM id FROM tenant.tenants WHERE id=NEW.tenant_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Resource unavailable' USING ERRCODE='P3104'; END IF;
 PERFORM id FROM identity.users WHERE id=NEW.actor_user_id FOR SHARE;
 PERFORM user_id FROM identity.user_credentials WHERE user_id=NEW.actor_user_id FOR SHARE;
 PERFORM id FROM tenant.memberships WHERE tenant_id=NEW.tenant_id ORDER BY id FOR SHARE;
 PERFORM id FROM tenant.administrators WHERE tenant_id=NEW.tenant_id ORDER BY id FOR SHARE;
 IF NOT security.rbac_authority(NEW.actor_user_id,NEW.tenant_id,NEW.application_id,true) THEN RAISE EXCEPTION 'RBAC authority unavailable' USING ERRCODE='42501'; END IF;
 IF NOT EXISTS(SELECT FROM catalog.applications a JOIN catalog.tenant_applications t ON t.application_id=a.id WHERE a.id=NEW.application_id AND t.tenant_id=NEW.tenant_id AND a.status='PUBLISHED' AND t.status IN ('ACTIVE','READ_ONLY')) THEN RAISE EXCEPTION 'Application unavailable' USING ERRCODE='P3102'; END IF;
 -- Global registry and policy changes take the same barriers before any row locks.
 PERFORM id FROM security.roles WHERE tenant_id=NEW.tenant_id AND application_id=NEW.application_id ORDER BY id FOR UPDATE;
 PERFORM id FROM security.profiles WHERE tenant_id=NEW.tenant_id AND application_id=NEW.application_id ORDER BY id FOR UPDATE;
 kind=split_part(NEW.action,'_',1);
 IF kind NOT IN ('ROLE','PROFILE') THEN RAISE EXCEPTION 'Invalid action' USING ERRCODE='22023'; END IF;
 IF NEW.permission_ids IS NULL OR NEW.role_ids IS NULL OR cardinality(NEW.permission_ids)>200 OR cardinality(NEW.role_ids)>100
 OR array_position(NEW.permission_ids,NULL) IS NOT NULL OR array_position(NEW.role_ids,NULL) IS NOT NULL
 OR cardinality(NEW.permission_ids)<>(SELECT count(DISTINCT p) FROM unnest(NEW.permission_ids) p)
 OR cardinality(NEW.role_ids)<>(SELECT count(DISTINCT r) FROM unnest(NEW.role_ids) r)
 OR (kind='ROLE' AND cardinality(NEW.role_ids)>0) OR (kind='PROFILE' AND cardinality(NEW.permission_ids)>0) THEN RAISE EXCEPTION 'Invalid composition' USING ERRCODE='22023'; END IF;
 NEW.input=jsonb_build_object('resourceId',NEW.resource_id,'templateId',NEW.template_id,'expectedRevision',NEW.expected_revision,'code',NEW.code,'name',NEW.name,'description',NEW.description,'permissionIds',NEW.permission_ids,'roleIds',NEW.role_ids);
 IF NEW.action LIKE '%_TEMPLATE' THEN
 IF NEW.template_id IS NULL OR NEW.expected_revision IS NOT NULL OR NEW.resource_id IS NOT NULL OR cardinality(NEW.permission_ids)>0 OR cardinality(NEW.role_ids)>0 THEN RAISE EXCEPTION 'Invalid template input' USING ERRCODE='22023'; END IF;
 IF kind='ROLE' THEN
 SELECT id INTO existing FROM security.roles WHERE tenant_id=NEW.tenant_id AND application_id=NEW.application_id AND source_role_template_id=NEW.template_id;
 previous=security.rbac_snapshot(NEW.tenant_id,NEW.application_id,existing,'ROLE');
 NEW.resource_id=security.materialize_rbac_role(NEW.tenant_id,NEW.application_id,NEW.template_id);
 ELSE
 SELECT * INTO source FROM security.profile_templates WHERE id=NEW.template_id AND application_id=NEW.application_id AND status='PUBLISHED' AND version>0;
 IF NOT FOUND OR NOT EXISTS(SELECT FROM security.profile_template_roles WHERE application_id=NEW.application_id AND profile_template_id=NEW.template_id) THEN RAISE EXCEPTION 'Template unavailable' USING ERRCODE='42501'; END IF;
 NEW.role_ids='{}';
 FOR source_role IN SELECT role_template_id FROM security.profile_template_roles WHERE application_id=NEW.application_id AND profile_template_id=source.id ORDER BY role_template_id LOOP
 NEW.role_ids=array_append(NEW.role_ids,security.materialize_rbac_role(NEW.tenant_id,NEW.application_id,source_role));
 END LOOP;
 IF (SELECT count(*) FROM security.profiles WHERE tenant_id=NEW.tenant_id AND application_id=NEW.application_id AND source_profile_template_id=source.id)>1 THEN RAISE EXCEPTION 'Template materialization conflict' USING ERRCODE='P3101'; END IF;
 SELECT id INTO existing FROM security.profiles WHERE tenant_id=NEW.tenant_id AND application_id=NEW.application_id AND source_profile_template_id=source.id;
 IF existing IS NOT NULL THEN
 previous=security.rbac_snapshot(NEW.tenant_id,NEW.application_id,existing,'PROFILE');
 IF NOT EXISTS(SELECT FROM security.profiles WHERE id=existing AND status='ACTIVE' AND source_template_version=source.version)
 OR (SELECT array_agg(r ORDER BY r) FROM unnest(NEW.role_ids) r) IS DISTINCT FROM (SELECT array_agg(role_id ORDER BY role_id) FROM security.profile_roles WHERE tenant_id=NEW.tenant_id AND application_id=NEW.application_id AND profile_id=existing) THEN
 RAISE EXCEPTION 'Existing profile differs from template' USING ERRCODE='P3101'; END IF;
 NEW.resource_id=existing;
 ELSE
 NEW.resource_id=gen_random_uuid();
 INSERT INTO security.profiles(id,tenant_id,application_id,code,name,description,status,source_profile_template_id,source_template_version,revision)
 VALUES(NEW.resource_id,NEW.tenant_id,NEW.application_id,source.code||'_v'||source.version,source.name,source.description,'ACTIVE',source.id,source.version,1);
 INSERT INTO security.profile_roles(tenant_id,application_id,profile_id,role_id) SELECT NEW.tenant_id,NEW.application_id,NEW.resource_id,r FROM unnest(NEW.role_ids) r;
 END IF;
 END IF;
 ELSE
 IF NEW.template_id IS NOT NULL THEN RAISE EXCEPTION 'Invalid origin' USING ERRCODE='22023'; END IF;
 IF NEW.action LIKE '%_CREATE' THEN
 IF NEW.resource_id IS NOT NULL OR NEW.expected_revision IS NOT NULL THEN RAISE EXCEPTION 'Invalid create input' USING ERRCODE='22023'; END IF;
 NEW.resource_id=gen_random_uuid();
 ELSE
 previous=security.rbac_snapshot(NEW.tenant_id,NEW.application_id,NEW.resource_id,kind);
 IF previous IS NULL THEN RAISE EXCEPTION 'Resource unavailable' USING ERRCODE='P3104'; END IF;
 current_revision=(previous->>'revision')::bigint; state=previous->>'status';
 IF NEW.expected_revision IS DISTINCT FROM current_revision OR state<>'ACTIVE' THEN RAISE EXCEPTION 'Stale revision or invalid state' USING ERRCODE='P3101'; END IF;
 IF EXISTS(SELECT FROM security.branch_profile_assignments b WHERE b.tenant_id=NEW.tenant_id AND b.application_id=NEW.application_id AND b.status='ACTIVE' AND b.revoked_at IS NULL
 AND (kind='PROFILE' AND b.profile_id=NEW.resource_id OR kind='ROLE' AND EXISTS(SELECT FROM security.profile_roles pr WHERE pr.tenant_id=b.tenant_id AND pr.application_id=b.application_id AND pr.profile_id=b.profile_id AND pr.role_id=NEW.resource_id))) THEN RAISE EXCEPTION 'Assigned compositions require a separate workflow' USING ERRCODE='P3101'; END IF;
 END IF;
 IF NEW.action LIKE '%_DISABLE' THEN
 IF cardinality(NEW.permission_ids)>0 OR cardinality(NEW.role_ids)>0 THEN RAISE EXCEPTION 'Invalid disable composition' USING ERRCODE='22023'; END IF;
 IF kind='ROLE' THEN
 IF EXISTS(SELECT FROM security.profile_roles pr JOIN security.profiles p ON p.tenant_id=pr.tenant_id AND p.application_id=pr.application_id AND p.id=pr.profile_id WHERE pr.tenant_id=NEW.tenant_id AND pr.application_id=NEW.application_id AND pr.role_id=NEW.resource_id AND p.status='ACTIVE') THEN RAISE EXCEPTION 'Role is used by an active profile' USING ERRCODE='P3101'; END IF;
 UPDATE security.roles SET status='DISABLED',revision=revision+1,updated_at=clock_timestamp() WHERE id=NEW.resource_id AND tenant_id=NEW.tenant_id AND application_id=NEW.application_id;
 ELSE UPDATE security.profiles SET status='DISABLED',revision=revision+1,updated_at=clock_timestamp() WHERE id=NEW.resource_id AND tenant_id=NEW.tenant_id AND application_id=NEW.application_id;
 END IF;
 ELSE
 IF NEW.code IS NULL OR btrim(NEW.code)='' OR char_length(NEW.code)>80 OR NEW.code ~ '[[:cntrl:]]'
 OR NEW.name IS NULL OR btrim(NEW.name)='' OR char_length(NEW.name)>200 OR NEW.name ~ '[[:cntrl:]]'
 OR (NEW.description IS NOT NULL AND (char_length(NEW.description)>2000 OR NEW.description ~ '[[:cntrl:]]')) THEN RAISE EXCEPTION 'Invalid metadata' USING ERRCODE='22023'; END IF;
 IF kind='ROLE' THEN
 FOREACH permission IN ARRAY NEW.permission_ids LOOP
 IF NOT security.rbac_permission_allowed(NEW.tenant_id,NEW.application_id,permission) THEN RAISE EXCEPTION 'Permission is not delegable' USING ERRCODE='42501'; END IF;
 END LOOP;
 IF NEW.action='ROLE_CREATE' THEN INSERT INTO security.roles(id,tenant_id,application_id,code,name,description,status,revision) VALUES(NEW.resource_id,NEW.tenant_id,NEW.application_id,btrim(NEW.code),btrim(NEW.name),NEW.description,'ACTIVE',1);
 ELSE UPDATE security.roles SET code=btrim(NEW.code),name=btrim(NEW.name),description=NEW.description,revision=revision+1,updated_at=clock_timestamp() WHERE id=NEW.resource_id AND tenant_id=NEW.tenant_id AND application_id=NEW.application_id;
 DELETE FROM security.role_permissions WHERE tenant_id=NEW.tenant_id AND application_id=NEW.application_id AND role_id=NEW.resource_id;
 END IF;
 INSERT INTO security.role_permissions(tenant_id,application_id,role_id,permission_id) SELECT NEW.tenant_id,NEW.application_id,NEW.resource_id,p FROM unnest(NEW.permission_ids) p;
 ELSE
 FOREACH selected_role IN ARRAY NEW.role_ids LOOP
 IF NOT EXISTS(SELECT FROM security.roles WHERE tenant_id=NEW.tenant_id AND application_id=NEW.application_id AND id=selected_role AND status='ACTIVE')
 OR EXISTS(SELECT FROM security.role_permissions WHERE tenant_id=NEW.tenant_id AND application_id=NEW.application_id AND role_id=selected_role AND NOT security.rbac_permission_allowed(NEW.tenant_id,NEW.application_id,permission_id)) THEN RAISE EXCEPTION 'Role composition unavailable' USING ERRCODE='42501'; END IF;
 END LOOP;
 IF NEW.action='PROFILE_CREATE' THEN INSERT INTO security.profiles(id,tenant_id,application_id,code,name,description,status,revision) VALUES(NEW.resource_id,NEW.tenant_id,NEW.application_id,btrim(NEW.code),btrim(NEW.name),NEW.description,'ACTIVE',1);
 ELSE UPDATE security.profiles SET code=btrim(NEW.code),name=btrim(NEW.name),description=NEW.description,revision=revision+1,updated_at=clock_timestamp() WHERE id=NEW.resource_id AND tenant_id=NEW.tenant_id AND application_id=NEW.application_id;
 DELETE FROM security.profile_roles WHERE tenant_id=NEW.tenant_id AND application_id=NEW.application_id AND profile_id=NEW.resource_id;
 END IF;
 INSERT INTO security.profile_roles(tenant_id,application_id,profile_id,role_id) SELECT NEW.tenant_id,NEW.application_id,NEW.resource_id,r FROM unnest(NEW.role_ids) r;
 END IF;
 END IF;
 END IF;
 NEW.role_id=CASE WHEN kind='ROLE' THEN NEW.resource_id END; NEW.profile_id=CASE WHEN kind='PROFILE' THEN NEW.resource_id END;
 NEW.previous_snapshot=previous; NEW.result_snapshot=security.rbac_snapshot(NEW.tenant_id,NEW.application_id,NEW.resource_id,kind);
 IF NEW.action='PROFILE_TEMPLATE' THEN
 NEW.result_snapshot=NEW.result_snapshot||jsonb_build_object('templateRoles',(SELECT jsonb_agg(security.rbac_snapshot(NEW.tenant_id,NEW.application_id,r,'ROLE') ORDER BY r) FROM unnest(NEW.role_ids) r));
 END IF;
 NEW.created_at=clock_timestamp(); RETURN NEW;
END $$;
CREATE TRIGGER rbac_command BEFORE INSERT OR UPDATE OR DELETE ON security.rbac_operations FOR EACH ROW EXECUTE FUNCTION security.apply_rbac_operation();
CREATE FUNCTION security.guard_rbac_child() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF current_user='ls_core_tenant_rbac_dev' AND (session_user<>current_user OR pg_trigger_depth()<>2) THEN RAISE EXCEPTION 'Atomic RBAC command required' USING ERRCODE='42501'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION security.complete_rbac_operation() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 INSERT INTO audit.events(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
 VALUES(clock_timestamp(),'USER',NEW.actor_user_id,'TENANT_RBAC_'||NEW.action,'TENANT',NEW.tenant_id,'SUCCESS',NEW.request_id,
 jsonb_build_object('application_id',NEW.application_id,'resource_id',NEW.resource_id,'previous',NEW.previous_snapshot,'result',NEW.result_snapshot)); RETURN NEW;
END $$;
CREATE TRIGGER rbac_complete AFTER INSERT ON security.rbac_operations FOR EACH ROW EXECUTE FUNCTION security.complete_rbac_operation();
CREATE FUNCTION platform.guard_rbac_policy() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF session_user<>'postgres' OR current_user<>session_user THEN RAISE EXCEPTION 'Platform installation operator required' USING ERRCODE='42501'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
CREATE FUNCTION platform.audit_rbac_policy() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE company uuid;
BEGIN
 company=CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
 INSERT INTO audit.events(occurred_at,actor_kind,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
 VALUES(clock_timestamp(),'INSTALLATION_OPERATOR','RBAC_POLICY_'||upper(TG_TABLE_NAME)||'_'||TG_OP,'TENANT',company,'SUCCESS',gen_random_uuid(),
 jsonb_build_object('table',TG_TABLE_NAME,'from',CASE WHEN TG_OP<>'INSERT' THEN to_jsonb(OLD) END,'to',CASE WHEN TG_OP<>'DELETE' THEN to_jsonb(NEW) END));
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
CREATE FUNCTION audit.guard_rbac_event() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP IN ('UPDATE','DELETE') THEN
 IF OLD.action_code LIKE 'TENANT_RBAC_%' OR OLD.action_code LIKE 'RBAC_POLICY_%' THEN RAISE EXCEPTION 'Immutable RBAC evidence' USING ERRCODE='42501'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 END IF;
 IF NEW.action_code LIKE 'RBAC_POLICY_%' THEN
 IF TG_OP<>'INSERT' OR session_user<>'postgres' OR current_user<>session_user OR pg_trigger_depth()<>2
 OR NEW.actor_kind<>'INSTALLATION_OPERATOR' OR NEW.actor_user_id IS NOT NULL OR NEW.outcome<>'SUCCESS'
 OR NEW.resource_type<>'TENANT' OR NEW.correlation_id IS NULL THEN RAISE EXCEPTION 'Invalid policy evidence' USING ERRCODE='42501'; END IF;
 ELSIF NEW.action_code LIKE 'TENANT_RBAC_%' OR current_user IN ('ls_core_tenant_rbac_dev','ls_core_tenant_rbac_log_dev') THEN
 IF TG_OP<>'INSERT' OR session_user<>current_user OR NEW.actor_kind<>'USER' OR NEW.actor_user_id IS NULL
 OR NEW.resource_type IS DISTINCT FROM 'TENANT' OR NEW.correlation_id IS NULL OR NEW.tenant_id IS NOT NULL
 OR NEW.application_id IS NOT NULL OR NEW.session_id IS NOT NULL OR NEW.actor_api_key_id IS NOT NULL THEN RAISE EXCEPTION 'Invalid evidence' USING ERRCODE='42501'; END IF;
 IF current_user='ls_core_tenant_rbac_dev' THEN
 IF pg_trigger_depth()<>2 OR NEW.outcome<>'SUCCESS' OR NOT EXISTS(SELECT FROM security.rbac_operations o
 WHERE o.request_id=NEW.correlation_id AND o.actor_user_id=NEW.actor_user_id AND o.tenant_id=NEW.resource_id
 AND NEW.action_code='TENANT_RBAC_'||o.action AND NEW.metadata=jsonb_build_object('application_id',o.application_id,'resource_id',o.resource_id,'previous',o.previous_snapshot,'result',o.result_snapshot)) THEN RAISE EXCEPTION 'Atomic evidence required' USING ERRCODE='42501'; END IF;
 ELSIF current_user='ls_core_tenant_rbac_log_dev' THEN
 IF NEW.action_code NOT IN ('TENANT_RBAC_ROLE_CREATE','TENANT_RBAC_ROLE_UPDATE','TENANT_RBAC_ROLE_DISABLE','TENANT_RBAC_ROLE_TEMPLATE','TENANT_RBAC_PROFILE_CREATE','TENANT_RBAC_PROFILE_UPDATE','TENANT_RBAC_PROFILE_DISABLE','TENANT_RBAC_PROFILE_TEMPLATE')
 OR NEW.outcome NOT IN ('DENIED','UNKNOWN') OR NEW.metadata<>'{}'::jsonb THEN RAISE EXCEPTION 'Invalid rejection' USING ERRCODE='42501'; END IF;
 ELSE RAISE EXCEPTION 'RBAC evidence unavailable' USING ERRCODE='42501'; END IF;
 END IF;
 IF NEW.action_code LIKE 'TENANT_RBAC_%' OR NEW.action_code LIKE 'RBAC_POLICY_%' THEN NEW.occurred_at=clock_timestamp(); NEW.created_at=NEW.occurred_at; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER rbac_event BEFORE INSERT OR UPDATE OR DELETE ON audit.events FOR EACH ROW EXECUTE FUNCTION audit.guard_rbac_event();
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['platform.rbac_permission_delegations','platform.rbac_administrator_grants','security.permissions','security.role_templates','security.role_template_permissions','security.profile_templates','security.profile_template_roles'] LOOP
 EXECUTE format('CREATE TRIGGER rbac_coordination BEFORE INSERT OR UPDATE OR DELETE ON %s FOR EACH STATEMENT EXECUTE FUNCTION tenant.branch_configuration_barrier()',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['platform.rbac_permission_delegations','platform.rbac_administrator_grants'] LOOP
 EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY',tab); EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY',tab);
 EXECUTE format('CREATE TRIGGER rbac_policy BEFORE INSERT OR UPDATE OR DELETE ON %s FOR EACH ROW EXECUTE FUNCTION platform.guard_rbac_policy()',tab);
 EXECUTE format('CREATE TRIGGER rbac_policy_evidence AFTER INSERT OR UPDATE OR DELETE ON %s FOR EACH ROW EXECUTE FUNCTION platform.audit_rbac_policy()',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['security.roles','security.role_permissions','security.profiles','security.profile_roles'] LOOP
 EXECUTE format('CREATE TRIGGER rbac_child BEFORE INSERT OR UPDATE OR DELETE ON %s FOR EACH ROW EXECUTE FUNCTION security.guard_rbac_child()',tab);
 END LOOP;
END $$;
ALTER TABLE security.rbac_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE security.rbac_operations FORCE ROW LEVEL SECURITY;
GRANT USAGE ON SCHEMA security,platform,tenant,identity,catalog,audit TO ls_core_tenant_rbac_dev;
GRANT USAGE ON SCHEMA audit TO ls_core_tenant_rbac_log_dev;
GRANT SELECT(id,username,status) ON identity.users TO ls_core_tenant_rbac_dev;
GRANT SELECT(user_id,must_change_password,password_changed_at,password_expires_at,locked_until) ON identity.user_credentials TO ls_core_tenant_rbac_dev;
GRANT SELECT(id,name,status) ON tenant.tenants TO ls_core_tenant_rbac_dev;
GRANT SELECT(id,tenant_id,user_id,status) ON tenant.memberships TO ls_core_tenant_rbac_dev;
GRANT SELECT(id,tenant_id,membership_id,status,revoked_at) ON tenant.administrators TO ls_core_tenant_rbac_dev;
GRANT SELECT(id,user_id,administrator_kind,status,revoked_at) ON platform.administrators TO ls_core_tenant_rbac_dev;
GRANT SELECT(tenant_id,application_id,profile_id,status,revoked_at) ON security.branch_profile_assignments TO ls_core_tenant_rbac_dev;
GRANT SELECT ON platform.rbac_permission_delegations,platform.rbac_administrator_grants,
 catalog.applications,catalog.modules,catalog.tenant_applications,catalog.tenant_modules,
 security.permissions,security.role_templates,security.role_template_permissions,security.profile_templates,security.profile_template_roles,
 security.roles,security.role_permissions,security.profiles,security.profile_roles,security.rbac_operations TO ls_core_tenant_rbac_dev;
GRANT UPDATE(id) ON identity.users,tenant.administrators TO ls_core_tenant_rbac_dev;
GRANT UPDATE(user_id) ON identity.user_credentials TO ls_core_tenant_rbac_dev;
GRANT UPDATE(status) ON tenant.tenants TO ls_core_tenant_rbac_dev;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['identity.users','identity.user_credentials','tenant.tenants','tenant.memberships','tenant.administrators','platform.administrators',
 'platform.rbac_permission_delegations','platform.rbac_administrator_grants','catalog.tenant_applications','catalog.tenant_modules','security.branch_profile_assignments',
 'security.roles','security.role_permissions','security.profiles','security.profile_roles','security.rbac_operations'] LOOP
 EXECUTE format('CREATE POLICY tenant_rbac_read ON %s FOR SELECT TO ls_core_tenant_rbac_dev USING(session_user=current_user)',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['identity.users','identity.user_credentials','tenant.tenants','tenant.memberships','tenant.administrators'] LOOP
 EXECUTE format('CREATE POLICY tenant_rbac_lock ON %s FOR UPDATE TO ls_core_tenant_rbac_dev USING(session_user=current_user) WITH CHECK(false)',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['security.roles','security.role_permissions','security.profiles','security.profile_roles'] LOOP
 EXECUTE format('CREATE POLICY tenant_rbac_child_insert ON %s FOR INSERT TO ls_core_tenant_rbac_dev WITH CHECK(session_user=current_user AND pg_trigger_depth()=1)',tab);
 EXECUTE format('CREATE POLICY tenant_rbac_child_update ON %s FOR UPDATE TO ls_core_tenant_rbac_dev USING(session_user=current_user) WITH CHECK(pg_trigger_depth()=1)',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['security.role_permissions','security.profile_roles'] LOOP
 EXECUTE format('CREATE POLICY tenant_rbac_child_delete ON %s FOR DELETE TO ls_core_tenant_rbac_dev USING(session_user=current_user AND pg_trigger_depth()=1)',tab);
 END LOOP;
END $$;
-- FOR SHARE on memberships requires a column UPDATE privilege, with no row writes allowed.
GRANT UPDATE(id) ON tenant.memberships TO ls_core_tenant_rbac_dev;
GRANT INSERT(id,tenant_id,application_id,code,name,description,status,source_role_template_id,source_template_version,revision),UPDATE(code,name,description,status,revision,updated_at) ON security.roles TO ls_core_tenant_rbac_dev;
GRANT INSERT(id,tenant_id,application_id,code,name,description,status,source_profile_template_id,source_template_version,revision),UPDATE(code,name,description,status,revision,updated_at) ON security.profiles TO ls_core_tenant_rbac_dev;
GRANT INSERT(tenant_id,application_id,role_id,permission_id),DELETE ON security.role_permissions TO ls_core_tenant_rbac_dev;
GRANT INSERT(tenant_id,application_id,profile_id,role_id),DELETE ON security.profile_roles TO ls_core_tenant_rbac_dev;
GRANT INSERT(request_id,actor_user_id,tenant_id,application_id,action,resource_id,expected_revision,template_id,code,name,description,permission_ids,role_ids) ON security.rbac_operations TO ls_core_tenant_rbac_dev;
CREATE POLICY tenant_rbac_command ON security.rbac_operations FOR INSERT TO ls_core_tenant_rbac_dev WITH CHECK(session_user=current_user);
GRANT INSERT(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata) ON audit.events TO ls_core_tenant_rbac_dev,ls_core_tenant_rbac_log_dev;
CREATE POLICY tenant_rbac_success ON audit.events FOR INSERT TO ls_core_tenant_rbac_dev WITH CHECK(session_user=current_user AND outcome='SUCCESS' AND action_code LIKE 'TENANT_RBAC_%');
CREATE POLICY tenant_rbac_rejected ON audit.events FOR INSERT TO ls_core_tenant_rbac_log_dev WITH CHECK(session_user=current_user AND outcome IN ('DENIED','UNKNOWN') AND action_code LIKE 'TENANT_RBAC_%');
DO $$ DECLARE f text; BEGIN
 FOREACH f IN ARRAY ARRAY['security.rbac_owner(uuid)','security.rbac_authority(uuid,uuid,uuid,boolean)','security.rbac_permission_allowed(uuid,uuid,uuid)',
 'security.rbac_template_allowed(uuid,uuid,uuid)','security.rbac_snapshot(uuid,uuid,uuid,text)','security.materialize_rbac_role(uuid,uuid,uuid)',
 'security.apply_rbac_operation()','security.guard_rbac_child()','security.complete_rbac_operation()','platform.guard_rbac_policy()','platform.audit_rbac_policy()','audit.guard_rbac_event()'] LOOP
 EXECUTE 'REVOKE ALL ON FUNCTION '||f||' FROM PUBLIC';
 END LOOP;
END $$;
GRANT EXECUTE ON FUNCTION security.rbac_owner(uuid),security.rbac_authority(uuid,uuid,uuid,boolean),security.rbac_permission_allowed(uuid,uuid,uuid),
 security.rbac_template_allowed(uuid,uuid,uuid),security.rbac_snapshot(uuid,uuid,uuid,text),security.materialize_rbac_role(uuid,uuid,uuid),tenant.administrator_ready(uuid),tenant.valid_administrators(uuid) TO ls_core_tenant_rbac_dev;

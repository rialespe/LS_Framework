-- LFD-50. Explicit branch onboarding, historical profile assignments and contextual union.
DO $$ BEGIN
 IF session_user<>'ls_core_migrator_dev' OR current_user<>session_user OR current_database()<>'ls_core_dev'
 OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999
 OR (SELECT count(*) FROM pg_roles r WHERE rolname IN ('ls_core_profile_assignments_dev','ls_core_profile_assignments_log_dev','ls_core_context_permissions_dev')
 AND rolcanlogin AND NOT(rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
 AND rolconnlimit=CASE WHEN rolname='ls_core_profile_assignments_log_dev' THEN 2 ELSE 3 END
 AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
 AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o')
 AND NOT has_database_privilege(r.oid,current_database(),'CREATE,TEMPORARY'))<>3
 THEN RAISE EXCEPTION 'Expected V31 and fresh restricted RBAC channels'; END IF;
END $$;
DO $$ DECLARE principal record; relation record; BEGIN
 FOR principal IN SELECT oid FROM pg_roles WHERE rolname IN ('ls_core_profile_assignments_dev','ls_core_profile_assignments_log_dev','ls_core_context_permissions_dev') LOOP
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
CREATE TABLE platform.permission_execution_rules (
 application_id uuid NOT NULL, permission_id uuid NOT NULL,
 operation_kind text NOT NULL CHECK(operation_kind IN ('READ','WRITE')),
 context_mode text NOT NULL CHECK(context_mode IN ('SINGLE_BRANCH','RESERVED')),
 PRIMARY KEY(application_id,permission_id), FOREIGN KEY(application_id,permission_id) REFERENCES security.permissions(application_id,id)
);
-- Empty, independently installed semantic rules. No policy row is a permission grant.
ALTER TABLE platform.permission_execution_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.permission_execution_rules FORCE ROW LEVEL SECURITY;
CREATE TRIGGER execution_rule_barrier BEFORE INSERT OR UPDATE OR DELETE ON platform.permission_execution_rules FOR EACH STATEMENT EXECUTE FUNCTION tenant.branch_configuration_barrier();
CREATE TRIGGER execution_rule_operator BEFORE INSERT OR UPDATE OR DELETE ON platform.permission_execution_rules FOR EACH ROW EXECUTE FUNCTION platform.guard_rbac_policy();
ALTER TABLE security.branch_profile_assignments ADD COLUMN revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
 ADD COLUMN revoked_by_user_id uuid REFERENCES identity.users(id);
CREATE UNIQUE INDEX branch_profiles_active_uq ON security.branch_profile_assignments(tenant_id,application_id,branch_membership_id,profile_id) WHERE status='ACTIVE' AND revoked_at IS NULL;
ALTER TABLE security.branch_profile_assignments ADD CONSTRAINT branch_profiles_assignment_scope_uq UNIQUE(tenant_id,application_id,id,branch_membership_id);
CREATE TABLE security.profile_assignment_operations (
 request_id uuid PRIMARY KEY, actor_user_id uuid NOT NULL REFERENCES identity.users(id), tenant_id uuid NOT NULL,
 application_id uuid NOT NULL, membership_id uuid NOT NULL, user_id uuid NOT NULL,
 branch_id uuid NOT NULL, branch_membership_id uuid NOT NULL, assignment_id uuid,
 action text NOT NULL CHECK(action IN ('JOIN_BRANCH','ASSIGN','REVOKE')),
 expected_updated_at timestamptz, expected_revision bigint, profile_ids uuid[] NOT NULL DEFAULT '{}',
 input jsonb NOT NULL, previous_snapshot jsonb, result_snapshot jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(tenant_id,application_id) REFERENCES catalog.tenant_applications(tenant_id,application_id),
 FOREIGN KEY(tenant_id,membership_id,user_id) REFERENCES tenant.memberships(tenant_id,id,user_id),
 FOREIGN KEY(tenant_id,branch_membership_id,membership_id,branch_id) REFERENCES tenant.branch_memberships(tenant_id,id,membership_id,branch_id),
 FOREIGN KEY(tenant_id,application_id,assignment_id,branch_membership_id) REFERENCES security.branch_profile_assignments(tenant_id,application_id,id,branch_membership_id)
);
ALTER TABLE security.profile_assignment_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE security.profile_assignment_operations FORCE ROW LEVEL SECURITY;
CREATE INDEX profile_assignment_operations_history_idx ON security.profile_assignment_operations(tenant_id,application_id,membership_id,created_at DESC,request_id DESC);
CREATE FUNCTION security.profile_assignable(company uuid,app uuid,profile uuid) RETURNS boolean LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
 SELECT EXISTS(SELECT FROM security.profiles WHERE tenant_id=company AND application_id=app AND id=profile AND status='ACTIVE')
 AND NOT EXISTS(SELECT FROM security.profile_roles pr JOIN security.roles r ON r.tenant_id=pr.tenant_id AND r.application_id=pr.application_id AND r.id=pr.role_id
 WHERE pr.tenant_id=company AND pr.application_id=app AND pr.profile_id=profile AND (r.status<>'ACTIVE'
 OR EXISTS(SELECT FROM security.role_permissions rp WHERE rp.tenant_id=r.tenant_id AND rp.application_id=r.application_id AND rp.role_id=r.id
 AND (NOT security.rbac_permission_allowed(company,app,rp.permission_id) OR NOT EXISTS(SELECT FROM platform.permission_execution_rules e
 JOIN platform.rbac_permission_delegations d ON d.tenant_id=company AND d.application_id=e.application_id AND d.permission_id=e.permission_id
 WHERE e.application_id=app AND e.permission_id=rp.permission_id AND e.operation_kind=d.privilege_kind)))))
$$;
CREATE FUNCTION security.context_permission_set(subject uuid,company uuid,app uuid,branch uuid,member uuid,branch_member uuid)
 RETURNS TABLE(id uuid,code text,name text,resource_scope text,module_id uuid,module_code text,module_name text,operation_kind text)
 LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
 SELECT DISTINCT p.id,p.code,p.name,p.resource_scope,p.module_id,mo.code,mo.name,e.operation_kind
 FROM identity.users u JOIN identity.user_credentials cred ON cred.user_id=u.id
 JOIN tenant.memberships m ON m.user_id=u.id AND m.tenant_id=company AND m.id=member
 JOIN tenant.tenants t ON t.id=m.tenant_id JOIN tenant.branches b ON b.tenant_id=t.id AND b.id=branch
 JOIN tenant.branch_memberships bm ON bm.tenant_id=t.id AND bm.membership_id=m.id AND bm.branch_id=b.id AND bm.id=branch_member
 JOIN catalog.tenant_applications ta ON ta.tenant_id=t.id AND ta.application_id=app JOIN catalog.applications a ON a.id=ta.application_id
 JOIN security.branch_profile_assignments ba ON ba.tenant_id=t.id AND ba.application_id=a.id AND ba.branch_membership_id=bm.id
 JOIN security.profiles pf ON pf.tenant_id=ba.tenant_id AND pf.application_id=ba.application_id AND pf.id=ba.profile_id
 JOIN security.profile_roles pr ON pr.tenant_id=pf.tenant_id AND pr.application_id=pf.application_id AND pr.profile_id=pf.id
 JOIN security.roles r ON r.tenant_id=pr.tenant_id AND r.application_id=pr.application_id AND r.id=pr.role_id
 JOIN security.role_permissions rp ON rp.tenant_id=r.tenant_id AND rp.application_id=r.application_id AND rp.role_id=r.id
 JOIN security.permissions p ON p.application_id=rp.application_id AND p.id=rp.permission_id
 JOIN platform.permission_execution_rules e ON e.application_id=p.application_id AND e.permission_id=p.id
 LEFT JOIN catalog.modules mo ON mo.application_id=p.application_id AND mo.id=p.module_id
 WHERE u.id=subject AND u.status='ACTIVE' AND NOT cred.must_change_password AND cred.password_expires_at>clock_timestamp()
 AND (cred.locked_until IS NULL OR cred.locked_until<=clock_timestamp()) AND t.status='ACTIVE' AND m.status='ACTIVE'
 AND b.status='ACTIVE' AND bm.status='ACTIVE' AND a.status='PUBLISHED' AND ta.status IN ('ACTIVE','READ_ONLY')
 AND ba.status='ACTIVE' AND ba.revoked_at IS NULL AND pf.status='ACTIVE' AND r.status='ACTIVE'
 AND p.status='PUBLISHED' AND p.resource_scope IN ('TENANT','BRANCH') AND e.context_mode='SINGLE_BRANCH'
 AND (ta.status='ACTIVE' OR e.operation_kind='READ')
 AND (p.module_id IS NULL OR mo.status='PUBLISHED' AND EXISTS(SELECT FROM catalog.tenant_modules tm
 WHERE tm.tenant_id=t.id AND tm.application_id=a.id AND tm.module_id=p.module_id AND (tm.status='ACTIVE' OR tm.status='READ_ONLY' AND e.operation_kind='READ')))
 ORDER BY p.code,p.id
$$;
CREATE FUNCTION security.resolve_context_permissions(subject uuid,company uuid,app uuid,branch uuid,member uuid,branch_member uuid,ctx uuid,session uuid)
 RETURNS TABLE(id uuid,code text,name text,resource_scope text,module_id uuid,module_code text,module_name text,operation_kind text)
 LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF session_user<>current_user OR current_user NOT IN ('ls_core_session_manager_dev','ls_core_context_permissions_dev') THEN RAISE EXCEPTION 'Context channel unavailable' USING ERRCODE='42501'; END IF;
 IF current_user='ls_core_session_manager_dev' THEN
 -- F05 takes both session-level shared barriers BEFORE its repeatable snapshot.
 IF NOT EXISTS(SELECT FROM pg_locks WHERE pid=pg_backend_pid() AND locktype='advisory' AND classid=370019 AND objid=1 AND granted AND mode='ShareLock')
 OR NOT EXISTS(SELECT FROM pg_locks WHERE pid=pg_backend_pid() AND locktype='advisory' AND classid=410023 AND objid=1 AND granted AND mode='ShareLock') THEN
 RAISE EXCEPTION 'Context coordinator barrier required' USING ERRCODE='42501'; END IF;
 ELSE
 IF current_setting('transaction_isolation')<>'read committed' OR current_setting('transaction_read_only')<>'off' THEN RAISE EXCEPTION 'Owned context transaction required' USING ERRCODE='42501'; END IF;
 PERFORM pg_advisory_xact_lock_shared(370019,1); PERFORM pg_advisory_xact_lock_shared(410023,1);
 -- Pin F04 until COMMIT. Session/context validity is checked at admission; the lifecycle barriers
 -- serialize assignment/access revocation. Do not lock a session held by the Core coordinator.
 PERFORM u.id FROM identity.users u WHERE u.id=subject FOR SHARE;
 PERFORM c.user_id FROM identity.user_credentials c WHERE c.user_id=subject FOR SHARE;
 END IF;
 IF NOT EXISTS(SELECT FROM security.session_contexts c JOIN security.sessions s ON s.id=c.session_id AND s.user_id=c.user_id AND s.application_id=c.application_id
 JOIN identity.users u ON u.id=c.user_id JOIN identity.user_credentials cr ON cr.user_id=u.id
 JOIN tenant.memberships m ON m.tenant_id=c.tenant_id AND m.id=c.membership_id AND m.user_id=c.user_id
 JOIN tenant.tenants t ON t.id=m.tenant_id JOIN tenant.branches b ON b.tenant_id=t.id AND b.id=c.branch_id
 JOIN tenant.branch_memberships bm ON bm.tenant_id=t.id AND bm.membership_id=m.id AND bm.branch_id=b.id
 JOIN catalog.applications a ON a.id=c.application_id JOIN catalog.tenant_applications ta ON ta.tenant_id=t.id AND ta.application_id=a.id
 WHERE c.id=ctx AND s.id=session AND c.user_id=subject AND c.tenant_id=company AND c.application_id=app AND c.branch_id=branch AND c.membership_id=member AND bm.id=branch_member
 AND c.revoked_at IS NULL AND c.expires_at>clock_timestamp() AND s.revoked_at IS NULL AND s.expires_at>clock_timestamp()
 AND coalesce(s.last_seen_at,s.created_at)>clock_timestamp()-interval '15 minutes'
 AND NOT EXISTS(SELECT FROM security.session_tenant_revocations rv WHERE rv.session_id=s.id AND rv.application_id=a.id AND rv.tenant_id=t.id)
 AND u.status='ACTIVE' AND NOT cr.must_change_password AND cr.password_expires_at>clock_timestamp() AND (cr.locked_until IS NULL OR cr.locked_until<=clock_timestamp())
 AND t.status='ACTIVE' AND m.status='ACTIVE' AND b.status='ACTIVE' AND bm.status='ACTIVE' AND a.status='PUBLISHED' AND ta.status IN ('ACTIVE','READ_ONLY')) THEN
 RAISE EXCEPTION 'Context unavailable' USING ERRCODE='42501'; END IF;
 RETURN QUERY SELECT * FROM security.context_permission_set(subject,company,app,branch,member,branch_member);
END $$;
CREATE FUNCTION security.require_context_permission(subject uuid,company uuid,app uuid,branch uuid,member uuid,branch_member uuid,ctx uuid,session uuid,permission_code text,operation text)
 RETURNS void LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF current_user<>'ls_core_context_permissions_dev' OR (operation IS NULL OR operation NOT IN ('READ','WRITE')) OR NOT EXISTS(
 SELECT FROM security.resolve_context_permissions(subject,company,app,branch,member,branch_member,ctx,session) p
 WHERE p.code=permission_code AND p.operation_kind=operation) THEN RAISE EXCEPTION 'Permission unavailable' USING ERRCODE='42501'; END IF;
END $$;
CREATE FUNCTION security.apply_profile_assignment_operation() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE target uuid; member_status text; branch_status text; access_status text; access_version timestamptz; profile uuid; assigned uuid; ids uuid[]='{}'; revoked integer=0; old_assignment security.branch_profile_assignments%ROWTYPE;
BEGIN
 IF current_user<>'ls_core_profile_assignments_dev' OR session_user<>current_user OR TG_OP<>'INSERT' OR current_setting('transaction_isolation')<>'read committed' THEN RAISE EXCEPTION 'Assignment channel unavailable' USING ERRCODE='42501'; END IF;
 PERFORM pg_advisory_xact_lock(370019,1); PERFORM pg_advisory_xact_lock(410023,1);
 PERFORM id FROM tenant.tenants WHERE id=NEW.tenant_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Resource unavailable' USING ERRCODE='P3204'; END IF;
 SELECT user_id,status INTO target,member_status FROM tenant.memberships WHERE tenant_id=NEW.tenant_id AND id=NEW.membership_id;
 PERFORM id FROM identity.users WHERE id IN (NEW.actor_user_id,target) ORDER BY id FOR SHARE;
 PERFORM user_id FROM identity.user_credentials WHERE user_id IN (NEW.actor_user_id,target) ORDER BY user_id FOR SHARE;
 PERFORM id FROM tenant.memberships WHERE tenant_id=NEW.tenant_id ORDER BY id FOR SHARE;
 PERFORM id FROM tenant.administrators WHERE tenant_id=NEW.tenant_id ORDER BY id FOR SHARE;
 IF NOT security.rbac_authority(NEW.actor_user_id,NEW.tenant_id,NEW.application_id,true) THEN RAISE EXCEPTION 'Assignment authority unavailable' USING ERRCODE='42501'; END IF;
 IF target IS NULL THEN RAISE EXCEPTION 'Resource unavailable' USING ERRCODE='P3204'; END IF;
 SELECT status INTO branch_status FROM tenant.branches WHERE tenant_id=NEW.tenant_id AND id=NEW.branch_id FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Resource unavailable' USING ERRCODE='P3204'; END IF;
 IF NOT EXISTS(SELECT FROM catalog.applications a JOIN catalog.tenant_applications ta ON ta.application_id=a.id WHERE a.id=NEW.application_id AND ta.tenant_id=NEW.tenant_id AND a.status='PUBLISHED' AND ta.status IN ('ACTIVE','READ_ONLY')) THEN RAISE EXCEPTION 'Application unavailable' USING ERRCODE='P3202'; END IF;
 IF NEW.profile_ids IS NULL OR cardinality(NEW.profile_ids)>50 OR array_position(NEW.profile_ids,NULL) IS NOT NULL
 OR cardinality(NEW.profile_ids)<>(SELECT count(DISTINCT p) FROM unnest(NEW.profile_ids) p) THEN RAISE EXCEPTION 'Invalid profiles' USING ERRCODE='22023'; END IF;
 NEW.input=jsonb_build_object('membershipId',NEW.membership_id,'branchId',NEW.branch_id,'branchMembershipId',NEW.branch_membership_id,'assignmentId',NEW.assignment_id,'expectedUpdatedAt',NEW.expected_updated_at,'expectedRevision',NEW.expected_revision,'profileIds',NEW.profile_ids);
 IF NEW.action='JOIN_BRANCH' THEN
 IF NEW.branch_membership_id IS NOT NULL OR NEW.assignment_id IS NOT NULL OR NEW.expected_updated_at IS NOT NULL OR NEW.expected_revision IS NOT NULL OR cardinality(NEW.profile_ids)<>0 THEN RAISE EXCEPTION 'Invalid incorporation input' USING ERRCODE='22023'; END IF;
 ELSE
 IF NEW.expected_updated_at IS NULL THEN RAISE EXCEPTION 'Access version required' USING ERRCODE='22023'; END IF;
 END IF;
 SELECT id,status,updated_at INTO assigned,access_status,access_version FROM tenant.branch_memberships WHERE tenant_id=NEW.tenant_id AND membership_id=NEW.membership_id AND branch_id=NEW.branch_id FOR UPDATE;
 IF NEW.action='JOIN_BRANCH' THEN
 IF member_status<>'ACTIVE' OR branch_status<>'ACTIVE' OR NOT tenant.administrator_ready(target) THEN RAISE EXCEPTION 'Incorporation requirements incomplete' USING ERRCODE='P3202'; END IF;
 IF assigned IS NOT NULL AND access_status<>'ACTIVE' THEN RAISE EXCEPTION 'Use V30 access lifecycle' USING ERRCODE='P3201'; END IF;
 IF assigned IS NULL THEN
 assigned=gen_random_uuid();
 INSERT INTO tenant.branch_memberships(id,tenant_id,membership_id,branch_id,status) VALUES(assigned,NEW.tenant_id,NEW.membership_id,NEW.branch_id,'ACTIVE');
 SELECT updated_at INTO access_version FROM tenant.branch_memberships WHERE id=assigned;
 END IF;
 NEW.branch_membership_id=assigned; access_status='ACTIVE';
 ELSE
 IF assigned IS NULL OR NEW.branch_membership_id IS DISTINCT FROM assigned THEN RAISE EXCEPTION 'Resource unavailable' USING ERRCODE='P3204'; END IF;
 IF NEW.expected_updated_at IS DISTINCT FROM access_version THEN RAISE EXCEPTION 'Stale access version' USING ERRCODE='P3201'; END IF;
 PERFORM r.id FROM security.roles r WHERE r.tenant_id=NEW.tenant_id AND r.application_id=NEW.application_id ORDER BY r.id FOR SHARE;
 PERFORM p.id FROM security.profiles p WHERE p.tenant_id=NEW.tenant_id AND p.application_id=NEW.application_id ORDER BY p.id FOR SHARE;
 IF NEW.action='ASSIGN' THEN
 IF NEW.actor_user_id=target THEN RAISE EXCEPTION 'Self assignment unavailable' USING ERRCODE='42501'; END IF;
 IF NEW.assignment_id IS NOT NULL OR NEW.expected_revision IS NOT NULL OR cardinality(NEW.profile_ids)=0 THEN RAISE EXCEPTION 'Invalid assignment input' USING ERRCODE='22023'; END IF;
 IF member_status<>'ACTIVE' OR branch_status<>'ACTIVE' OR access_status<>'ACTIVE' OR NOT tenant.administrator_ready(target) THEN RAISE EXCEPTION 'Assignment requirements incomplete' USING ERRCODE='P3202'; END IF;
 FOREACH profile IN ARRAY NEW.profile_ids LOOP
 IF NOT security.profile_assignable(NEW.tenant_id,NEW.application_id,profile) THEN RAISE EXCEPTION 'Profile is not delegable' USING ERRCODE='42501'; END IF;
 assigned=gen_random_uuid();
 INSERT INTO security.branch_profile_assignments(id,tenant_id,application_id,branch_membership_id,profile_id,status,granted_by_user_id,revision)
 VALUES(assigned,NEW.tenant_id,NEW.application_id,NEW.branch_membership_id,profile,'ACTIVE',NEW.actor_user_id,1);
 ids=array_append(ids,assigned);
 END LOOP;
 ELSIF NEW.action='REVOKE' THEN
 IF cardinality(NEW.profile_ids)<>0 OR NEW.assignment_id IS NULL OR NEW.expected_revision IS NULL THEN RAISE EXCEPTION 'Invalid revocation input' USING ERRCODE='22023'; END IF;
 SELECT * INTO old_assignment FROM security.branch_profile_assignments WHERE tenant_id=NEW.tenant_id AND application_id=NEW.application_id AND branch_membership_id=NEW.branch_membership_id AND id=NEW.assignment_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Resource unavailable' USING ERRCODE='P3204'; END IF;
 IF old_assignment.status<>'ACTIVE' OR old_assignment.revoked_at IS NOT NULL OR old_assignment.revision<>NEW.expected_revision THEN RAISE EXCEPTION 'Stale assignment' USING ERRCODE='P3201'; END IF;
 NEW.previous_snapshot=to_jsonb(old_assignment);
 UPDATE security.branch_profile_assignments SET status='REVOKED',revoked_at=clock_timestamp(),revoked_by_user_id=NEW.actor_user_id,revision=revision+1,updated_at=clock_timestamp() WHERE id=NEW.assignment_id;
 ids=ARRAY[NEW.assignment_id]; NEW.profile_ids=ARRAY[old_assignment.profile_id];
 ELSE RAISE EXCEPTION 'Invalid action' USING ERRCODE='22023'; END IF;
 UPDATE tenant.branch_memberships SET updated_at=clock_timestamp() WHERE tenant_id=NEW.tenant_id AND id=NEW.branch_membership_id RETURNING updated_at INTO access_version;
 UPDATE security.session_contexts SET revoked_at=clock_timestamp(),updated_at=clock_timestamp()
 WHERE tenant_id=NEW.tenant_id AND membership_id=NEW.membership_id AND branch_id=NEW.branch_id AND application_id=NEW.application_id AND revoked_at IS NULL;
 GET DIAGNOSTICS revoked=ROW_COUNT;
 END IF;
 NEW.user_id=target;
 NEW.result_snapshot=jsonb_build_object('membershipId',NEW.membership_id,'branchMembershipId',NEW.branch_membership_id,'branchId',NEW.branch_id,'accessStatus',access_status,'updatedAt',access_version,'assignmentIds',ids,'profileIds',NEW.profile_ids,'revokedContexts',revoked,'action',NEW.action);
 NEW.created_at=clock_timestamp(); RETURN NEW;
END $$;
CREATE TRIGGER profile_assignment_command BEFORE INSERT OR UPDATE OR DELETE ON security.profile_assignment_operations FOR EACH ROW EXECUTE FUNCTION security.apply_profile_assignment_operation();
CREATE FUNCTION security.guard_profile_assignment_child() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF current_user='ls_core_profile_assignments_dev' AND (session_user<>current_user OR pg_trigger_depth()<>2 OR TG_OP='DELETE') THEN RAISE EXCEPTION 'Atomic assignment command required' USING ERRCODE='42501'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
CREATE TRIGGER profile_assignment_child BEFORE INSERT OR UPDATE OR DELETE ON security.branch_profile_assignments FOR EACH ROW EXECUTE FUNCTION security.guard_profile_assignment_child();
CREATE TRIGGER profile_branch_membership_child BEFORE INSERT OR UPDATE OR DELETE ON tenant.branch_memberships FOR EACH ROW EXECUTE FUNCTION security.guard_profile_assignment_child();
CREATE TRIGGER profile_context_child BEFORE UPDATE ON security.session_contexts FOR EACH ROW EXECUTE FUNCTION security.guard_profile_assignment_child();
CREATE FUNCTION security.complete_profile_assignment_operation() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 INSERT INTO audit.events(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
 VALUES(clock_timestamp(),'USER',NEW.actor_user_id,'TENANT_PROFILE_'||NEW.action,'TENANT',NEW.tenant_id,'SUCCESS',NEW.request_id,
 jsonb_build_object('application_id',NEW.application_id,'membership_id',NEW.membership_id,'previous',NEW.previous_snapshot,'result',NEW.result_snapshot)); RETURN NEW;
END $$;
CREATE TRIGGER profile_assignment_complete AFTER INSERT ON security.profile_assignment_operations FOR EACH ROW EXECUTE FUNCTION security.complete_profile_assignment_operation();
-- CHANNEL GRANTS
GRANT USAGE ON SCHEMA security,platform,tenant,identity,catalog,audit TO ls_core_profile_assignments_dev;
GRANT USAGE ON SCHEMA audit TO ls_core_profile_assignments_log_dev;
GRANT USAGE ON SCHEMA security,platform,tenant,identity,catalog TO ls_core_context_permissions_dev;
GRANT SELECT(id,username,display_name,status) ON identity.users TO ls_core_profile_assignments_dev,ls_core_context_permissions_dev;
GRANT SELECT(user_id,must_change_password,password_changed_at,password_expires_at,locked_until) ON identity.user_credentials
 TO ls_core_profile_assignments_dev,ls_core_context_permissions_dev,ls_core_session_manager_dev;
GRANT SELECT ON tenant.tenants,tenant.memberships,tenant.branches,tenant.branch_memberships,
 catalog.applications,catalog.modules,catalog.tenant_applications,catalog.tenant_modules,
 security.permissions,security.profiles,security.roles,security.profile_roles,security.role_permissions,
 security.branch_profile_assignments,platform.permission_execution_rules TO ls_core_profile_assignments_dev,ls_core_context_permissions_dev;
GRANT SELECT ON tenant.administrators,platform.administrators,platform.rbac_permission_delegations,
 platform.rbac_administrator_grants,security.profile_assignment_operations,security.session_contexts TO ls_core_profile_assignments_dev;
GRANT SELECT(id,user_id,application_id,expires_at,revoked_at,last_seen_at,created_at) ON security.sessions TO ls_core_context_permissions_dev;
GRANT SELECT ON security.session_contexts,security.session_tenant_revocations TO ls_core_context_permissions_dev;
GRANT USAGE ON SCHEMA platform TO ls_core_session_manager_dev;
GRANT SELECT ON platform.permission_execution_rules TO ls_core_session_manager_dev;
GRANT UPDATE(id) ON identity.users,tenant.memberships,tenant.administrators,tenant.branches,security.roles,security.profiles TO ls_core_profile_assignments_dev;
GRANT UPDATE(user_id) ON identity.user_credentials TO ls_core_profile_assignments_dev,ls_core_context_permissions_dev;
GRANT UPDATE(id) ON identity.users TO ls_core_context_permissions_dev;
GRANT UPDATE(status) ON tenant.tenants TO ls_core_profile_assignments_dev;
GRANT INSERT(id,tenant_id,membership_id,branch_id,status),UPDATE(updated_at) ON tenant.branch_memberships TO ls_core_profile_assignments_dev;
GRANT INSERT(id,tenant_id,application_id,branch_membership_id,profile_id,status,granted_by_user_id,revision),
 UPDATE(status,revoked_at,revoked_by_user_id,revision,updated_at) ON security.branch_profile_assignments TO ls_core_profile_assignments_dev;
GRANT UPDATE(revoked_at,updated_at) ON security.session_contexts TO ls_core_profile_assignments_dev;
GRANT INSERT(request_id,actor_user_id,tenant_id,application_id,membership_id,branch_id,branch_membership_id,assignment_id,action,expected_updated_at,expected_revision,profile_ids)
 ON security.profile_assignment_operations TO ls_core_profile_assignments_dev;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['identity.users','identity.user_credentials','tenant.tenants','tenant.memberships','tenant.branches','tenant.branch_memberships',
 'tenant.administrators','platform.administrators','platform.rbac_permission_delegations','platform.rbac_administrator_grants',
 'platform.permission_execution_rules','catalog.tenant_applications','catalog.tenant_modules','security.roles','security.profiles','security.profile_roles',
 'security.role_permissions','security.branch_profile_assignments','security.profile_assignment_operations','security.session_contexts'] LOOP
 EXECUTE format('CREATE POLICY profile_assignment_read ON %s FOR SELECT TO ls_core_profile_assignments_dev USING(session_user=current_user)',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['identity.users','identity.user_credentials','tenant.tenants','tenant.memberships','tenant.branches','tenant.branch_memberships',
 'catalog.tenant_applications','catalog.tenant_modules','security.roles','security.profiles','security.profile_roles','security.role_permissions',
 'security.branch_profile_assignments','platform.permission_execution_rules','security.sessions','security.session_contexts','security.session_tenant_revocations'] LOOP
 EXECUTE format('CREATE POLICY context_permissions_read ON %s FOR SELECT TO ls_core_context_permissions_dev USING(session_user=current_user)',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['identity.users','identity.user_credentials','tenant.tenants','tenant.memberships','tenant.administrators','tenant.branches','security.roles','security.profiles'] LOOP
 EXECUTE format('CREATE POLICY profile_assignment_lock ON %s FOR UPDATE TO ls_core_profile_assignments_dev USING(session_user=current_user) WITH CHECK(false)',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['identity.users','identity.user_credentials'] LOOP
 EXECUTE format('CREATE POLICY context_permissions_lock ON %s FOR UPDATE TO ls_core_context_permissions_dev USING(session_user=current_user) WITH CHECK(false)',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['tenant.branch_memberships','security.branch_profile_assignments'] LOOP
 EXECUTE format('CREATE POLICY profile_assignment_child_insert ON %s FOR INSERT TO ls_core_profile_assignments_dev WITH CHECK(session_user=current_user AND pg_trigger_depth()=1)',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['tenant.branch_memberships','security.branch_profile_assignments','security.session_contexts'] LOOP
 EXECUTE format('CREATE POLICY profile_assignment_child_update ON %s FOR UPDATE TO ls_core_profile_assignments_dev USING(session_user=current_user) WITH CHECK(pg_trigger_depth()=1)',tab);
 END LOOP;
END $$;
CREATE POLICY profile_assignment_command ON security.profile_assignment_operations FOR INSERT TO ls_core_profile_assignments_dev WITH CHECK(session_user=current_user);
CREATE POLICY context_execution_rule_read ON platform.permission_execution_rules FOR SELECT TO ls_core_session_manager_dev USING(session_user=current_user);
CREATE POLICY context_credential_read ON identity.user_credentials FOR SELECT TO ls_core_session_manager_dev USING(session_user=current_user);
GRANT INSERT(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
 ON audit.events TO ls_core_profile_assignments_dev,ls_core_profile_assignments_log_dev;
CREATE POLICY profile_assignment_success ON audit.events FOR INSERT TO ls_core_profile_assignments_dev WITH CHECK(session_user=current_user AND outcome='SUCCESS' AND action_code LIKE 'TENANT_PROFILE_%');
CREATE POLICY profile_assignment_rejection ON audit.events FOR INSERT TO ls_core_profile_assignments_log_dev WITH CHECK(session_user=current_user AND outcome IN ('DENIED','UNKNOWN') AND action_code LIKE 'TENANT_PROFILE_%');
CREATE FUNCTION audit.guard_profile_assignment_event() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP IN ('UPDATE','DELETE') THEN
 IF OLD.action_code LIKE 'TENANT_PROFILE_%' THEN RAISE EXCEPTION 'Immutable assignment evidence' USING ERRCODE='42501'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 END IF;
 IF NEW.action_code LIKE 'TENANT_PROFILE_%' OR current_user IN ('ls_core_profile_assignments_dev','ls_core_profile_assignments_log_dev') THEN
 IF TG_OP<>'INSERT' OR session_user<>current_user OR NEW.actor_kind<>'USER' OR NEW.actor_user_id IS NULL
 OR NEW.resource_type IS DISTINCT FROM 'TENANT' OR NEW.correlation_id IS NULL OR NEW.tenant_id IS NOT NULL
 OR NEW.application_id IS NOT NULL OR NEW.session_id IS NOT NULL OR NEW.actor_api_key_id IS NOT NULL THEN RAISE EXCEPTION 'Invalid evidence' USING ERRCODE='42501'; END IF;
 IF current_user='ls_core_profile_assignments_dev' THEN
 IF pg_trigger_depth()<>2 OR NEW.outcome<>'SUCCESS' OR NOT EXISTS(SELECT FROM security.profile_assignment_operations o
 WHERE o.request_id=NEW.correlation_id AND o.actor_user_id=NEW.actor_user_id AND o.tenant_id=NEW.resource_id
 AND NEW.action_code='TENANT_PROFILE_'||o.action AND NEW.metadata=jsonb_build_object('application_id',o.application_id,'membership_id',o.membership_id,'previous',o.previous_snapshot,'result',o.result_snapshot)) THEN RAISE EXCEPTION 'Atomic evidence required' USING ERRCODE='42501'; END IF;
 ELSIF current_user='ls_core_profile_assignments_log_dev' THEN
 IF NEW.action_code NOT IN ('TENANT_PROFILE_JOIN_BRANCH','TENANT_PROFILE_ASSIGN','TENANT_PROFILE_REVOKE') OR NEW.outcome NOT IN ('DENIED','UNKNOWN') OR NEW.metadata<>'{}'::jsonb THEN RAISE EXCEPTION 'Invalid rejection' USING ERRCODE='42501'; END IF;
 ELSE RAISE EXCEPTION 'Assignment evidence unavailable' USING ERRCODE='42501'; END IF;
 NEW.occurred_at=clock_timestamp(); NEW.created_at=NEW.occurred_at;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER profile_assignment_event BEFORE INSERT OR UPDATE OR DELETE ON audit.events FOR EACH ROW EXECUTE FUNCTION audit.guard_profile_assignment_event();
DO $$ DECLARE f record; BEGIN
 FOR f IN SELECT oid::regprocedure signature FROM pg_proc WHERE pronamespace IN ('security'::regnamespace,'audit'::regnamespace)
 AND proname IN ('profile_assignable','context_permission_set','resolve_context_permissions','require_context_permission',
 'apply_profile_assignment_operation','guard_profile_assignment_child','complete_profile_assignment_operation','guard_profile_assignment_event') LOOP
 EXECUTE 'REVOKE ALL ON FUNCTION '||f.signature||' FROM PUBLIC';
 END LOOP;
END $$;
GRANT EXECUTE ON FUNCTION security.rbac_owner(uuid),security.rbac_authority(uuid,uuid,uuid,boolean),security.rbac_permission_allowed(uuid,uuid,uuid),
 tenant.administrator_ready(uuid),tenant.valid_administrators(uuid),security.profile_assignable(uuid,uuid,uuid),
 security.context_permission_set(uuid,uuid,uuid,uuid,uuid,uuid) TO ls_core_profile_assignments_dev;
GRANT EXECUTE ON FUNCTION security.context_permission_set(uuid,uuid,uuid,uuid,uuid,uuid),
 security.resolve_context_permissions(uuid,uuid,uuid,uuid,uuid,uuid,uuid,uuid) TO ls_core_session_manager_dev,ls_core_context_permissions_dev;
GRANT EXECUTE ON FUNCTION security.require_context_permission(uuid,uuid,uuid,uuid,uuid,uuid,uuid,uuid,text,text) TO ls_core_context_permissions_dev;

CREATE POLICY session_context_read_only_issue ON security.session_contexts FOR INSERT TO ls_core_session_manager_dev
    WITH CHECK (session_user=current_user AND current_user='ls_core_session_manager_dev' AND revoked_at IS NULL
        AND expires_at>clock_timestamp()
        AND EXISTS (SELECT FROM security.sessions s
            JOIN identity.users u ON u.id=s.user_id
            JOIN catalog.applications a ON a.id=s.application_id
            JOIN tenant.memberships m ON m.user_id=u.id AND m.tenant_id=session_contexts.tenant_id
            JOIN tenant.tenants t ON t.id=m.tenant_id
            JOIN tenant.branches b ON b.tenant_id=t.id AND b.id=session_contexts.branch_id
            JOIN tenant.branch_memberships bm ON bm.tenant_id=t.id AND bm.membership_id=m.id AND bm.branch_id=b.id
            JOIN catalog.tenant_applications ta ON ta.tenant_id=t.id AND ta.application_id=a.id
            WHERE s.id=session_contexts.session_id AND s.user_id=session_contexts.user_id
              AND s.application_id=session_contexts.application_id AND m.id=session_contexts.membership_id
              AND s.revoked_at IS NULL AND s.expires_at>=session_contexts.expires_at
              AND u.status='ACTIVE' AND t.status='ACTIVE' AND m.status='ACTIVE' AND b.status='ACTIVE'
              AND bm.status='ACTIVE' AND a.status='PUBLISHED' AND ta.status='READ_ONLY'));

CREATE FUNCTION platform.audit_permission_execution_rule() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 INSERT INTO audit.events(occurred_at,actor_kind,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
 VALUES(clock_timestamp(),'INSTALLATION_OPERATOR','PERMISSION_EXECUTION_RULE_'||TG_OP,'PERMISSION',CASE WHEN TG_OP='DELETE' THEN OLD.permission_id ELSE NEW.permission_id END,'SUCCESS',gen_random_uuid(),
 jsonb_build_object('previous',CASE WHEN TG_OP<>'INSERT' THEN to_jsonb(OLD) END,'result',CASE WHEN TG_OP<>'DELETE' THEN to_jsonb(NEW) END));
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
CREATE TRIGGER execution_rule_evidence AFTER INSERT OR UPDATE OR DELETE ON platform.permission_execution_rules FOR EACH ROW EXECUTE FUNCTION platform.audit_permission_execution_rule();
CREATE FUNCTION audit.guard_permission_execution_event() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP IN ('UPDATE','DELETE') AND OLD.action_code LIKE 'PERMISSION_EXECUTION_RULE_%' THEN RAISE EXCEPTION 'Immutable execution rule evidence' USING ERRCODE='42501'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 IF NEW.action_code LIKE 'PERMISSION_EXECUTION_RULE_%' AND (TG_OP<>'INSERT' OR session_user<>'postgres' OR session_user<>current_user OR pg_trigger_depth()<>2 OR NEW.actor_kind<>'INSTALLATION_OPERATOR' OR NEW.outcome<>'SUCCESS') THEN RAISE EXCEPTION 'Installation evidence required' USING ERRCODE='42501'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER permission_execution_event BEFORE INSERT OR UPDATE OR DELETE ON audit.events FOR EACH ROW EXECUTE FUNCTION audit.guard_permission_execution_event();
REVOKE ALL ON FUNCTION platform.audit_permission_execution_rule(),audit.guard_permission_execution_event() FROM PUBLIC;

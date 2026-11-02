-- LFD-48. Independent membership and branch access lifecycle; consent remains immutable.
DO $$ BEGIN
 IF session_user<>'ls_core_migrator_dev' OR current_user<>session_user OR current_database()<>'ls_core_dev'
 OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999
 OR (SELECT count(*) FROM pg_roles r WHERE rolname IN ('ls_core_tenant_access_dev','ls_core_tenant_access_log_dev')
 AND rolcanlogin AND NOT(rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
 AND rolconnlimit=CASE WHEN rolname='ls_core_tenant_access_dev' THEN 3 ELSE 2 END
 AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
 AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o')
 AND NOT has_database_privilege(r.oid,current_database(),'CREATE,TEMPORARY'))<>2
 THEN RAISE EXCEPTION 'Expected V29 and fresh restricted access channels'; END IF;
END $$;
DO $$ DECLARE principal record; relation record; BEGIN
 FOR principal IN SELECT oid FROM pg_roles WHERE rolname IN ('ls_core_tenant_access_dev','ls_core_tenant_access_log_dev') LOOP
 IF EXISTS(SELECT FROM pg_namespace WHERE has_schema_privilege(principal.oid,oid,'CREATE'))
 OR EXISTS(SELECT FROM pg_database WHERE datallowconn AND datname<>current_database() AND has_database_privilege(principal.oid,oid,'CONNECT'))
 OR NOT has_database_privilege(principal.oid,current_database(),'CONNECT') THEN
 RAISE EXCEPTION 'Incompatible access channel privileges; no repair'; END IF;
 FOR relation IN SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname !~ '^pg_' AND c.relkind IN ('r','p','v','m','f') LOOP
 IF has_table_privilege(principal.oid,relation.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
 OR has_any_column_privilege(principal.oid,relation.oid,'SELECT,INSERT,UPDATE,REFERENCES') THEN
 RAISE EXCEPTION 'Access channel has preexisting relation access'; END IF;
 END LOOP;
 END LOOP;
END $$;
-- The existing status columns are unrestricted text. Only the command's two-state
-- transitions are constrained; legacy PENDING/terminal values are not rewritten.
ALTER TABLE tenant.branch_memberships ADD CONSTRAINT branch_membership_lifecycle_scope_uq UNIQUE(tenant_id,id,membership_id,branch_id);
CREATE TABLE tenant.access_operations (
 request_id uuid PRIMARY KEY, actor_user_id uuid NOT NULL REFERENCES identity.users(id),
 tenant_id uuid NOT NULL REFERENCES tenant.tenants(id), membership_id uuid NOT NULL, user_id uuid NOT NULL,
 branch_membership_id uuid, branch_id uuid,
 action text NOT NULL CHECK(action IN ('MEMBER_SUSPEND','MEMBER_REACTIVATE','BRANCH_SUSPEND','BRANCH_REACTIVATE')),
 expected_updated_at timestamptz NOT NULL, previous_status text NOT NULL CHECK(previous_status IN ('ACTIVE','SUSPENDED')),
 resulting_status text NOT NULL CHECK(resulting_status IN ('ACTIVE','SUSPENDED')),
 revoked_contexts integer NOT NULL CHECK(revoked_contexts>=0),
 resource_updated_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(tenant_id,membership_id,user_id) REFERENCES tenant.memberships(tenant_id,id,user_id),
 FOREIGN KEY(tenant_id,branch_membership_id,membership_id,branch_id) REFERENCES tenant.branch_memberships(tenant_id,id,membership_id,branch_id),
 CHECK((action LIKE 'MEMBER_%' AND branch_id IS NULL AND branch_membership_id IS NULL)
 OR (action LIKE 'BRANCH_%' AND branch_id IS NOT NULL AND branch_membership_id IS NOT NULL)),
 CHECK((action IN ('MEMBER_SUSPEND','BRANCH_SUSPEND') AND previous_status='ACTIVE' AND resulting_status='SUSPENDED')
 OR (action IN ('MEMBER_REACTIVATE','BRANCH_REACTIVATE') AND previous_status='SUSPENDED' AND resulting_status='ACTIVE'))
);
CREATE INDEX access_operations_history_idx ON tenant.access_operations(tenant_id,membership_id,created_at DESC,request_id DESC);
CREATE FUNCTION tenant.apply_access_operation() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE company_status text; member_status text; target uuid; resource_status text; resource_version timestamptz; global_branch_status text;
BEGIN
 IF session_user<>'ls_core_tenant_access_dev' OR current_user<>session_user OR TG_OP<>'INSERT'
 OR current_setting('transaction_isolation')<>'read committed' THEN RAISE EXCEPTION 'Access channel unavailable' USING ERRCODE='42501'; END IF;
 PERFORM pg_advisory_xact_lock(370019,1);
 PERFORM pg_advisory_xact_lock(410023,1);
 SELECT status INTO company_status FROM tenant.tenants WHERE id=NEW.tenant_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Resource unavailable' USING ERRCODE='P3004'; END IF;
 SELECT user_id INTO target FROM tenant.memberships WHERE tenant_id=NEW.tenant_id AND id=NEW.membership_id;
 -- Pin the complete V24 eligibility set, in the same total order, through COMMIT.
 PERFORM id FROM identity.users WHERE id IN (NEW.actor_user_id,target)
 OR id IN (SELECT user_id FROM tenant.memberships WHERE tenant_id=NEW.tenant_id) ORDER BY id FOR SHARE;
 PERFORM user_id FROM identity.user_credentials WHERE user_id IN (NEW.actor_user_id,target)
 OR user_id IN (SELECT user_id FROM tenant.memberships WHERE tenant_id=NEW.tenant_id) ORDER BY user_id FOR SHARE;
 PERFORM id FROM tenant.memberships WHERE tenant_id=NEW.tenant_id ORDER BY id FOR UPDATE;
 PERFORM id FROM tenant.administrators WHERE tenant_id=NEW.tenant_id ORDER BY id FOR SHARE;
 IF NOT tenant.user_authority(NEW.actor_user_id,NEW.tenant_id,true) THEN RAISE EXCEPTION 'Authority unavailable' USING ERRCODE='42501'; END IF;
 IF target IS NULL THEN RAISE EXCEPTION 'Resource unavailable' USING ERRCODE='P3004'; END IF;
 IF EXISTS(SELECT FROM platform.administrators WHERE user_id=target AND administrator_kind='OWNER' AND status='ACTIVE' AND revoked_at IS NULL) THEN
 RAISE EXCEPTION 'Owner is protected' USING ERRCODE='P3001'; END IF;
 SELECT status,updated_at INTO member_status,resource_version FROM tenant.memberships WHERE tenant_id=NEW.tenant_id AND id=NEW.membership_id;
 NEW.user_id=target; NEW.branch_id=NULL;
 IF NEW.action LIKE 'MEMBER_%' THEN
 IF NEW.branch_membership_id IS NOT NULL THEN RAISE EXCEPTION 'Invalid scope' USING ERRCODE='22023'; END IF;
 -- Neither suspension nor reactivation may indirectly remove/restore an administrative designation.
 -- Revoke it using V24 first; V24 itself refuses removal of the last valid administrator.
 IF EXISTS(SELECT FROM tenant.administrators WHERE tenant_id=NEW.tenant_id AND membership_id=NEW.membership_id AND revoked_at IS NULL) THEN
 RAISE EXCEPTION 'Retire administrative designation first' USING ERRCODE='P3001'; END IF;
 resource_status=member_status;
 ELSE
 SELECT bm.status,bm.updated_at,bm.branch_id,b.status INTO resource_status,resource_version,NEW.branch_id,global_branch_status
 FROM tenant.branch_memberships bm JOIN tenant.branches b ON b.tenant_id=bm.tenant_id AND b.id=bm.branch_id
 WHERE bm.tenant_id=NEW.tenant_id AND bm.membership_id=NEW.membership_id AND bm.id=NEW.branch_membership_id FOR UPDATE OF bm;
 IF NOT FOUND THEN RAISE EXCEPTION 'Resource unavailable' USING ERRCODE='P3004'; END IF;
 PERFORM id FROM tenant.branches WHERE tenant_id=NEW.tenant_id AND id=NEW.branch_id FOR SHARE;
 END IF;
 IF NEW.expected_updated_at IS DISTINCT FROM resource_version THEN RAISE EXCEPTION 'Stale access version' USING ERRCODE='P3001'; END IF;
 IF NEW.action IN ('MEMBER_SUSPEND','BRANCH_SUSPEND') THEN
 IF resource_status<>'ACTIVE' THEN RAISE EXCEPTION 'Invalid transition' USING ERRCODE='P3001'; END IF;
 NEW.resulting_status='SUSPENDED';
 ELSE
 IF resource_status<>'SUSPENDED' THEN RAISE EXCEPTION 'Invalid transition' USING ERRCODE='P3001'; END IF;
 IF company_status<>'ACTIVE' OR NOT tenant.administrator_ready(target)
 OR (NEW.action='BRANCH_REACTIVATE' AND (member_status<>'ACTIVE' OR global_branch_status<>'ACTIVE')) THEN
 RAISE EXCEPTION 'Reactivation requirements incomplete' USING ERRCODE='P3002'; END IF;
 NEW.resulting_status='ACTIVE';
 END IF;
 NEW.previous_status=resource_status; NEW.created_at=clock_timestamp(); NEW.resource_updated_at=NEW.created_at;
 IF NEW.action LIKE 'MEMBER_%' THEN
 UPDATE tenant.memberships SET status=NEW.resulting_status,updated_at=NEW.resource_updated_at WHERE tenant_id=NEW.tenant_id AND id=NEW.membership_id;
 ELSE
 UPDATE tenant.branch_memberships SET status=NEW.resulting_status,updated_at=NEW.resource_updated_at WHERE tenant_id=NEW.tenant_id AND id=NEW.branch_membership_id;
 END IF;
 NEW.revoked_contexts=0;
 IF NEW.resulting_status='SUSPENDED' THEN
 UPDATE security.session_contexts SET revoked_at=NEW.created_at,updated_at=NEW.created_at
 WHERE tenant_id=NEW.tenant_id AND membership_id=NEW.membership_id AND revoked_at IS NULL
 AND (NEW.branch_id IS NULL OR branch_id=NEW.branch_id);
 GET DIAGNOSTICS NEW.revoked_contexts=ROW_COUNT;
 END IF;
 IF company_status='ACTIVE' AND NOT EXISTS(SELECT FROM tenant.valid_administrators(NEW.tenant_id)) THEN
 RAISE EXCEPTION 'Last valid administrator is protected' USING ERRCODE='P3001'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER access_command BEFORE INSERT OR UPDATE OR DELETE ON tenant.access_operations FOR EACH ROW EXECUTE FUNCTION tenant.apply_access_operation();
CREATE CONSTRAINT TRIGGER access_administrator_continuity AFTER INSERT ON tenant.access_operations
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION tenant.guard_administrative_continuity();
CREATE FUNCTION tenant.guard_access_child() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF current_user='ls_core_tenant_access_dev' AND (session_user<>current_user OR TG_OP<>'UPDATE' OR pg_trigger_depth()<>2) THEN
 RAISE EXCEPTION 'Atomic access command required' USING ERRCODE='42501'; END IF;
 RETURN NEW;
END $$;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['tenant.memberships','tenant.branch_memberships','security.session_contexts'] LOOP
 EXECUTE format('CREATE TRIGGER access_child BEFORE UPDATE ON %s FOR EACH ROW EXECUTE FUNCTION tenant.guard_access_child()',tab);
 END LOOP;
END $$;
CREATE FUNCTION tenant.complete_access_operation() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 INSERT INTO audit.events(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
 VALUES(clock_timestamp(),'USER',NEW.actor_user_id,'TENANT_ACCESS_'||NEW.action,'TENANT',NEW.tenant_id,'SUCCESS',NEW.request_id,
 jsonb_build_object('membership_id',NEW.membership_id,'user_id',NEW.user_id,'branch_id',NEW.branch_id,
 'branch_membership_id',NEW.branch_membership_id,'from',NEW.previous_status,'to',NEW.resulting_status,'revoked_contexts',NEW.revoked_contexts));
 RETURN NEW;
END $$;
CREATE TRIGGER access_complete AFTER INSERT ON tenant.access_operations FOR EACH ROW EXECUTE FUNCTION tenant.complete_access_operation();
CREATE FUNCTION audit.guard_access_event() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP IN ('UPDATE','DELETE') THEN
 IF OLD.action_code IN ('TENANT_ACCESS_MEMBER_SUSPEND','TENANT_ACCESS_MEMBER_REACTIVATE','TENANT_ACCESS_BRANCH_SUSPEND','TENANT_ACCESS_BRANCH_REACTIVATE') THEN RAISE EXCEPTION 'Immutable evidence' USING ERRCODE='42501'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 END IF;
 IF NEW.action_code IN ('TENANT_ACCESS_MEMBER_SUSPEND','TENANT_ACCESS_MEMBER_REACTIVATE','TENANT_ACCESS_BRANCH_SUSPEND','TENANT_ACCESS_BRANCH_REACTIVATE')
 OR current_user IN ('ls_core_tenant_access_dev','ls_core_tenant_access_log_dev') THEN
 IF TG_OP<>'INSERT' OR session_user<>current_user OR NEW.actor_kind<>'USER' OR NEW.actor_user_id IS NULL
 OR NEW.resource_type IS DISTINCT FROM 'TENANT' OR NEW.correlation_id IS NULL OR NEW.tenant_id IS NOT NULL
 OR NEW.application_id IS NOT NULL OR NEW.session_id IS NOT NULL OR NEW.actor_api_key_id IS NOT NULL THEN RAISE EXCEPTION 'Invalid evidence' USING ERRCODE='42501'; END IF;
 IF current_user='ls_core_tenant_access_dev' THEN
 IF pg_trigger_depth()<>2 OR NEW.outcome<>'SUCCESS' OR NOT EXISTS(SELECT FROM tenant.access_operations o
 WHERE o.request_id=NEW.correlation_id AND o.actor_user_id=NEW.actor_user_id AND o.tenant_id=NEW.resource_id
 AND NEW.action_code='TENANT_ACCESS_'||o.action AND NEW.metadata=jsonb_build_object('membership_id',o.membership_id,'user_id',o.user_id,'branch_id',o.branch_id,
 'branch_membership_id',o.branch_membership_id,'from',o.previous_status,'to',o.resulting_status,'revoked_contexts',o.revoked_contexts)) THEN RAISE EXCEPTION 'Atomic evidence required' USING ERRCODE='42501'; END IF;
 ELSIF current_user='ls_core_tenant_access_log_dev' THEN
 IF NEW.action_code NOT IN ('TENANT_ACCESS_MEMBER_SUSPEND','TENANT_ACCESS_MEMBER_REACTIVATE','TENANT_ACCESS_BRANCH_SUSPEND','TENANT_ACCESS_BRANCH_REACTIVATE') OR NEW.outcome NOT IN ('DENIED','UNKNOWN') OR NEW.metadata<>'{}'::jsonb THEN RAISE EXCEPTION 'Invalid rejection' USING ERRCODE='42501'; END IF;
 ELSE RAISE EXCEPTION 'Evidence unavailable' USING ERRCODE='42501'; END IF;
 NEW.occurred_at=clock_timestamp(); NEW.created_at=NEW.occurred_at;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER access_event BEFORE INSERT OR UPDATE OR DELETE ON audit.events FOR EACH ROW EXECUTE FUNCTION audit.guard_access_event();
GRANT USAGE ON SCHEMA identity,tenant,platform,security,audit TO ls_core_tenant_access_dev;
GRANT USAGE ON SCHEMA audit TO ls_core_tenant_access_log_dev;
GRANT SELECT(id,username,status) ON identity.users TO ls_core_tenant_access_dev;
GRANT SELECT(user_id,must_change_password,password_changed_at,password_expires_at,locked_until) ON identity.user_credentials TO ls_core_tenant_access_dev;
GRANT SELECT(id,name,status) ON tenant.tenants TO ls_core_tenant_access_dev;
GRANT SELECT(id,tenant_id,user_id,status,updated_at) ON tenant.memberships TO ls_core_tenant_access_dev;
GRANT SELECT(id,tenant_id,membership_id,status,revoked_at) ON tenant.administrators TO ls_core_tenant_access_dev;
GRANT SELECT(id,user_id,administrator_kind,status,revoked_at) ON platform.administrators TO ls_core_tenant_access_dev;
GRANT SELECT ON platform.tenant_user_grants,tenant.access_operations TO ls_core_tenant_access_dev;
GRANT SELECT(id,tenant_id,code,name,status) ON tenant.branches TO ls_core_tenant_access_dev;
GRANT SELECT(id,tenant_id,membership_id,branch_id,status,updated_at) ON tenant.branch_memberships TO ls_core_tenant_access_dev;
GRANT SELECT(tenant_id,membership_id,branch_id,revoked_at) ON security.session_contexts TO ls_core_tenant_access_dev;
GRANT UPDATE(status) ON tenant.tenants TO ls_core_tenant_access_dev;
GRANT UPDATE(id) ON identity.users,tenant.administrators,tenant.branches TO ls_core_tenant_access_dev;
GRANT UPDATE(user_id) ON identity.user_credentials TO ls_core_tenant_access_dev;
GRANT UPDATE(status,updated_at) ON tenant.memberships,tenant.branch_memberships TO ls_core_tenant_access_dev;
GRANT UPDATE(revoked_at,updated_at) ON security.session_contexts TO ls_core_tenant_access_dev;
ALTER TABLE tenant.access_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant.access_operations FORCE ROW LEVEL SECURITY;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['identity.users','identity.user_credentials','tenant.tenants','tenant.memberships','tenant.administrators','platform.administrators','platform.tenant_user_grants','tenant.branches','tenant.branch_memberships','security.session_contexts','tenant.access_operations'] LOOP
 EXECUTE format('CREATE POLICY tenant_access_read ON %s FOR SELECT TO ls_core_tenant_access_dev USING(session_user=current_user)',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['identity.users','identity.user_credentials','tenant.tenants','tenant.administrators','tenant.branches'] LOOP
 EXECUTE format('CREATE POLICY tenant_access_lock ON %s FOR UPDATE TO ls_core_tenant_access_dev USING(session_user=current_user) WITH CHECK(false)',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['tenant.memberships','tenant.branch_memberships','security.session_contexts'] LOOP
 EXECUTE format('CREATE POLICY tenant_access_child ON %s FOR UPDATE TO ls_core_tenant_access_dev USING(session_user=current_user) WITH CHECK(pg_trigger_depth()=1)',tab);
 END LOOP;
END $$;
GRANT INSERT(request_id,actor_user_id,tenant_id,membership_id,branch_membership_id,action,expected_updated_at) ON tenant.access_operations TO ls_core_tenant_access_dev;
CREATE POLICY tenant_access_command ON tenant.access_operations FOR INSERT TO ls_core_tenant_access_dev WITH CHECK(session_user=current_user);
GRANT INSERT(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata) ON audit.events TO ls_core_tenant_access_dev,ls_core_tenant_access_log_dev;
CREATE POLICY tenant_access_success ON audit.events FOR INSERT TO ls_core_tenant_access_dev WITH CHECK(session_user=current_user AND outcome='SUCCESS' AND action_code IN ('TENANT_ACCESS_MEMBER_SUSPEND','TENANT_ACCESS_MEMBER_REACTIVATE','TENANT_ACCESS_BRANCH_SUSPEND','TENANT_ACCESS_BRANCH_REACTIVATE'));
CREATE POLICY tenant_access_rejected ON audit.events FOR INSERT TO ls_core_tenant_access_log_dev WITH CHECK(session_user=current_user AND outcome IN ('DENIED','UNKNOWN') AND action_code IN ('TENANT_ACCESS_MEMBER_SUSPEND','TENANT_ACCESS_MEMBER_REACTIVATE','TENANT_ACCESS_BRANCH_SUSPEND','TENANT_ACCESS_BRANCH_REACTIVATE'));
DO $$ DECLARE f text; BEGIN
 FOREACH f IN ARRAY ARRAY['tenant.apply_access_operation()','tenant.guard_access_child()','tenant.complete_access_operation()','audit.guard_access_event()'] LOOP
 EXECUTE 'REVOKE ALL ON FUNCTION '||f||' FROM PUBLIC';
 END LOOP;
END $$;
GRANT EXECUTE ON FUNCTION tenant.user_authority(uuid,uuid,boolean),tenant.administrator_ready(uuid),tenant.valid_administrators(uuid) TO ls_core_tenant_access_dev;

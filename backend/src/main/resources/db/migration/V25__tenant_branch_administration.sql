-- LFD-43. Restricted invoker commands; V7 owns normalized code uniqueness.
DO $$ BEGIN
 IF session_user<>'ls_core_migrator_dev' OR current_user<>session_user OR current_database()<>'ls_core_dev'
 OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999
 OR (SELECT count(*) FROM pg_roles r WHERE rolname IN ('ls_core_branch_admin_dev','ls_core_branch_admin_log_dev')
 AND rolcanlogin AND NOT(rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
 AND rolconnlimit=CASE WHEN rolname='ls_core_branch_admin_dev' THEN 3 ELSE 2 END
 AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
 AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o')
 AND NOT has_database_privilege(r.oid,current_database(),'CREATE,TEMPORARY'))<>2
 OR NOT EXISTS(SELECT FROM pg_class WHERE oid='tenant.branches'::regclass AND relrowsecurity AND relforcerowsecurity AND relowner=current_user::regrole)
 THEN RAISE EXCEPTION 'Expected V24 and fresh restricted branch channels'; END IF;
END $$;
-- Validates every existing row, failing without rewriting incompatible historical states.
ALTER TABLE tenant.branches ADD CONSTRAINT branches_administration_status_ck CHECK(status IN ('ACTIVE','SUSPENDED','CLOSED'));
CREATE TABLE platform.branch_administration_grants (
 designation_id uuid REFERENCES platform.administrators(id),
 capability text CHECK(capability IN ('READ','MANAGE')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(designation_id,capability)
);
CREATE TABLE tenant.branch_operations (
 request_id uuid PRIMARY KEY,
 actor_user_id uuid NOT NULL REFERENCES identity.users(id),
 tenant_id uuid NOT NULL REFERENCES tenant.tenants(id),
 branch_id uuid NOT NULL,
 action text NOT NULL CHECK(action IN ('CREATE','EDIT','SUSPEND','REACTIVATE','CLOSE')),
 expected_updated_at timestamptz,
 previous_code text, previous_name text, previous_status text,
 code text NOT NULL, name text NOT NULL, resulting_status text NOT NULL,
 branch_created_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(tenant_id,branch_id) REFERENCES tenant.branches(tenant_id,id) DEFERRABLE INITIALLY DEFERRED
);
CREATE FUNCTION tenant.branch_administration_authority(actor uuid,company uuid,mutating boolean) RETURNS boolean
 LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
 SELECT tenant.administrator_ready(actor) AND (EXISTS(
 SELECT FROM platform.administrators a WHERE a.user_id=actor AND a.status='ACTIVE' AND a.revoked_at IS NULL
 AND (a.administrator_kind='OWNER' OR (a.administrator_kind='SUPERADMIN'
 AND EXISTS(SELECT FROM platform.branch_administration_grants g WHERE g.designation_id=a.id AND g.capability='READ')
 AND (NOT mutating OR EXISTS(SELECT FROM platform.branch_administration_grants g WHERE g.designation_id=a.id AND g.capability='MANAGE')))))
 OR (EXISTS(SELECT FROM tenant.tenants t WHERE t.id=company AND t.status='ACTIVE')
 AND EXISTS(SELECT FROM tenant.valid_administrators(company) a WHERE a.user_id=actor)))
$$;
CREATE FUNCTION tenant.apply_branch_operation() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE company_status text; old_branch tenant.branches%ROWTYPE;
BEGIN
 IF session_user<>'ls_core_branch_admin_dev' OR current_user<>session_user OR TG_OP<>'INSERT'
 OR current_setting('transaction_isolation')<>'read committed' THEN
 RAISE EXCEPTION 'Branch channel unavailable' USING ERRCODE='42501'; END IF;
 PERFORM pg_advisory_xact_lock(370019,1);
 PERFORM pg_advisory_xact_lock(410023,1);
 SELECT status INTO company_status FROM tenant.tenants WHERE id=NEW.tenant_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Company unavailable' USING ERRCODE='P2504'; END IF;
 PERFORM id FROM identity.users WHERE id=NEW.actor_user_id FOR SHARE;
 PERFORM user_id FROM identity.user_credentials WHERE user_id=NEW.actor_user_id FOR SHARE;
 IF NOT tenant.branch_administration_authority(NEW.actor_user_id,NEW.tenant_id,true) THEN
 RAISE EXCEPTION 'Branch administration not authorized' USING ERRCODE='42501'; END IF;
 NEW.created_at=clock_timestamp();
 IF NEW.action='CREATE' THEN
 IF NEW.expected_updated_at IS NOT NULL THEN RAISE EXCEPTION 'Invalid create precondition' USING ERRCODE='P2502'; END IF;
 NEW.branch_id=gen_random_uuid(); NEW.resulting_status='ACTIVE'; NEW.branch_created_at=NEW.created_at;
 NEW.previous_code=NULL; NEW.previous_name=NULL; NEW.previous_status=NULL;
 NEW.code=btrim(NEW.code); NEW.name=btrim(NEW.name);
 ELSE
 SELECT * INTO old_branch FROM tenant.branches WHERE id=NEW.branch_id AND tenant_id=NEW.tenant_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Branch unavailable' USING ERRCODE='P2504'; END IF;
 IF old_branch.status='CLOSED' OR NEW.expected_updated_at IS DISTINCT FROM old_branch.updated_at THEN
 RAISE EXCEPTION 'Closed branch or stale version' USING ERRCODE='P2501'; END IF;
 NEW.previous_code=old_branch.code; NEW.previous_name=old_branch.name; NEW.previous_status=old_branch.status;
 NEW.branch_created_at=old_branch.created_at; NEW.resulting_status=old_branch.status;
 IF NEW.action='EDIT' THEN
 -- Preserve unchanged historical codes literally; an explicit replacement must satisfy the new format.
 IF NEW.code IS DISTINCT FROM old_branch.code THEN NEW.code=btrim(NEW.code); END IF;
 NEW.name=btrim(NEW.name);
 IF NEW.code IS DISTINCT FROM old_branch.code AND (
 EXISTS(SELECT FROM tenant.branch_memberships WHERE tenant_id=NEW.tenant_id AND branch_id=NEW.branch_id)
 OR EXISTS(SELECT FROM security.session_contexts WHERE tenant_id=NEW.tenant_id AND branch_id=NEW.branch_id)
 OR EXISTS(SELECT FROM integration.api_keys WHERE tenant_id=NEW.tenant_id AND branch_id=NEW.branch_id)) THEN
 RAISE EXCEPTION 'Referenced branch code is protected' USING ERRCODE='P2501'; END IF;
 IF NEW.code IS NOT DISTINCT FROM old_branch.code AND NEW.name IS NOT DISTINCT FROM old_branch.name THEN
 RAISE EXCEPTION 'No change requested' USING ERRCODE='P2501'; END IF;
 ELSE
 NEW.code=old_branch.code; NEW.name=old_branch.name;
 IF NEW.action='SUSPEND' AND old_branch.status='ACTIVE' THEN NEW.resulting_status='SUSPENDED';
 ELSIF NEW.action='REACTIVATE' AND old_branch.status='SUSPENDED' THEN NEW.resulting_status='ACTIVE';
 ELSIF NEW.action='CLOSE' AND old_branch.status IN ('ACTIVE','SUSPENDED') THEN NEW.resulting_status='CLOSED';
 ELSE RAISE EXCEPTION 'Invalid branch transition' USING ERRCODE='P2501'; END IF;
 END IF;
 END IF;
 IF NEW.action='CREATE' OR (NEW.action='EDIT' AND NEW.code IS DISTINCT FROM NEW.previous_code) THEN
 IF NEW.code IS NULL OR char_length(NEW.code) NOT BETWEEN 1 AND 40 OR NEW.code !~ '^[A-Za-z0-9][A-Za-z0-9 ._-]*$' THEN
 RAISE EXCEPTION 'Code requires 1-40 ASCII letters digits spaces dot underscore or hyphen' USING ERRCODE='P2502'; END IF;
 END IF;
 IF NEW.action IN ('CREATE','EDIT') AND (NEW.name IS NULL OR char_length(NEW.name) NOT BETWEEN 1 AND 200 OR NEW.name ~ '[[:cntrl:]]') THEN
 RAISE EXCEPTION 'Name requires 1-200 characters without controls' USING ERRCODE='P2502'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER branch_operation_guard BEFORE INSERT OR UPDATE OR DELETE ON tenant.branch_operations FOR EACH ROW EXECUTE FUNCTION tenant.apply_branch_operation();
CREATE TRIGGER branch_operation_no_truncate BEFORE TRUNCATE ON tenant.branch_operations FOR EACH STATEMENT EXECUTE FUNCTION tenant.apply_branch_operation();
CREATE FUNCTION tenant.complete_branch_operation() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.action='CREATE' THEN
 INSERT INTO tenant.branches(id,tenant_id,code,name,status,created_at,updated_at)
 VALUES(NEW.branch_id,NEW.tenant_id,NEW.code,NEW.name,NEW.resulting_status,NEW.branch_created_at,NEW.created_at);
 ELSE
 UPDATE tenant.branches SET code=NEW.code,name=NEW.name,status=NEW.resulting_status,updated_at=NEW.created_at
 WHERE id=NEW.branch_id AND tenant_id=NEW.tenant_id;
 END IF;
 IF NEW.resulting_status IN ('SUSPENDED','CLOSED') THEN
 UPDATE security.session_contexts SET revoked_at=clock_timestamp(),updated_at=clock_timestamp()
 WHERE tenant_id=NEW.tenant_id AND branch_id=NEW.branch_id AND revoked_at IS NULL;
 END IF;
 INSERT INTO audit.events(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
 VALUES(clock_timestamp(),'USER',NEW.actor_user_id,'BRANCH_'||NEW.action,'TENANT_BRANCH',NEW.branch_id,'SUCCESS',NEW.request_id,
 jsonb_build_object('tenant_id',NEW.tenant_id,'previous_code',NEW.previous_code,'code',NEW.code,
 'previous_name',NEW.previous_name,'name',NEW.name,'from',NEW.previous_status,'to',NEW.resulting_status));
 RETURN NEW;
END $$;
CREATE TRIGGER branch_operation_complete AFTER INSERT ON tenant.branch_operations FOR EACH ROW EXECUTE FUNCTION tenant.complete_branch_operation();
CREATE FUNCTION tenant.guard_branch_child() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF current_user='ls_core_branch_admin_dev' AND (session_user<>current_user OR TG_OP NOT IN ('INSERT','UPDATE') OR pg_trigger_depth()<>2) THEN
 RAISE EXCEPTION 'Atomic branch command required' USING ERRCODE='42501'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER branch_administration_child BEFORE INSERT OR UPDATE ON tenant.branches FOR EACH ROW EXECUTE FUNCTION tenant.guard_branch_child();
CREATE TRIGGER branch_administration_context_child BEFORE INSERT OR UPDATE ON security.session_contexts FOR EACH ROW EXECUTE FUNCTION tenant.guard_branch_child();
CREATE FUNCTION audit.guard_branch_event() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP IN ('UPDATE','DELETE') THEN
 IF OLD.action_code IN ('BRANCH_CREATE','BRANCH_EDIT','BRANCH_SUSPEND','BRANCH_REACTIVATE','BRANCH_CLOSE') THEN
 RAISE EXCEPTION 'Immutable branch evidence' USING ERRCODE='42501'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 END IF;
 IF NEW.action_code IN ('BRANCH_CREATE','BRANCH_EDIT','BRANCH_SUSPEND','BRANCH_REACTIVATE','BRANCH_CLOSE')
 OR current_user IN ('ls_core_branch_admin_dev','ls_core_branch_admin_log_dev') THEN
 IF TG_OP<>'INSERT' OR session_user<>current_user OR NEW.actor_kind<>'USER' OR NEW.actor_user_id IS NULL
 OR NEW.resource_type IS DISTINCT FROM 'TENANT_BRANCH' OR NEW.correlation_id IS NULL OR NEW.tenant_id IS NOT NULL
 OR NEW.application_id IS NOT NULL OR NEW.session_id IS NOT NULL OR NEW.actor_api_key_id IS NOT NULL THEN
 RAISE EXCEPTION 'Invalid branch evidence' USING ERRCODE='42501'; END IF;
 IF current_user='ls_core_branch_admin_dev' THEN
 IF pg_trigger_depth()<>2 OR NEW.outcome<>'SUCCESS' OR NOT EXISTS(SELECT FROM tenant.branch_operations o
 WHERE o.request_id=NEW.correlation_id AND o.actor_user_id=NEW.actor_user_id AND o.branch_id=NEW.resource_id
 AND NEW.action_code='BRANCH_'||o.action AND NEW.metadata=jsonb_build_object('tenant_id',o.tenant_id,'previous_code',o.previous_code,'code',o.code,
 'previous_name',o.previous_name,'name',o.name,'from',o.previous_status,'to',o.resulting_status)) THEN
 RAISE EXCEPTION 'Atomic branch evidence required' USING ERRCODE='42501'; END IF;
 ELSIF current_user='ls_core_branch_admin_log_dev' THEN
 IF NEW.action_code NOT IN ('BRANCH_CREATE','BRANCH_EDIT','BRANCH_SUSPEND','BRANCH_REACTIVATE','BRANCH_CLOSE')
 OR NEW.outcome NOT IN ('DENIED','UNKNOWN') OR NEW.metadata<>'{}'::jsonb THEN
 RAISE EXCEPTION 'Rejection evidence required' USING ERRCODE='42501'; END IF;
 ELSE RAISE EXCEPTION 'Evidence unavailable' USING ERRCODE='42501'; END IF;
 NEW.occurred_at=clock_timestamp(); NEW.created_at=NEW.occurred_at;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER branch_event BEFORE INSERT OR UPDATE OR DELETE ON audit.events FOR EACH ROW EXECUTE FUNCTION audit.guard_branch_event();

GRANT USAGE ON SCHEMA identity,platform,tenant,security,integration,audit TO ls_core_branch_admin_dev;
GRANT USAGE ON SCHEMA audit TO ls_core_branch_admin_log_dev;
GRANT SELECT(id,username,status) ON identity.users TO ls_core_branch_admin_dev;
GRANT SELECT(user_id,must_change_password,password_changed_at,password_expires_at,locked_until) ON identity.user_credentials TO ls_core_branch_admin_dev;
GRANT SELECT(id,user_id,administrator_kind,status,revoked_at) ON platform.administrators TO ls_core_branch_admin_dev;
GRANT SELECT ON platform.branch_administration_grants,tenant.branch_operations TO ls_core_branch_admin_dev;
GRANT SELECT(id,tenant_id,code,name,status,created_at,updated_at) ON tenant.branches TO ls_core_branch_admin_dev;
GRANT SELECT(id,name,status) ON tenant.tenants TO ls_core_branch_admin_dev;
GRANT SELECT(id,tenant_id,user_id,status) ON tenant.memberships TO ls_core_branch_admin_dev;
GRANT SELECT(id,tenant_id,membership_id,status,revoked_at) ON tenant.administrators TO ls_core_branch_admin_dev;
GRANT SELECT(tenant_id,branch_id) ON tenant.branch_memberships,integration.api_keys TO ls_core_branch_admin_dev;
GRANT SELECT(tenant_id,branch_id,revoked_at) ON security.session_contexts TO ls_core_branch_admin_dev;
GRANT UPDATE(id) ON identity.users TO ls_core_branch_admin_dev;
GRANT UPDATE(user_id) ON identity.user_credentials TO ls_core_branch_admin_dev;
GRANT UPDATE(status) ON tenant.tenants TO ls_core_branch_admin_dev;
CREATE POLICY branch_identity_lock ON identity.users FOR UPDATE TO ls_core_branch_admin_dev USING(session_user=current_user) WITH CHECK(false);
CREATE POLICY branch_credential_lock ON identity.user_credentials FOR UPDATE TO ls_core_branch_admin_dev USING(session_user=current_user) WITH CHECK(false);
CREATE POLICY branch_tenant_lock ON tenant.tenants FOR UPDATE TO ls_core_branch_admin_dev USING(session_user=current_user) WITH CHECK(false);
ALTER TABLE platform.branch_administration_grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.branch_administration_grants FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant.branch_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant.branch_operations FORCE ROW LEVEL SECURITY;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['identity.users','identity.user_credentials','platform.administrators','platform.branch_administration_grants',
 'tenant.tenants','tenant.branches','tenant.memberships','tenant.administrators','tenant.branch_memberships','security.session_contexts','integration.api_keys','tenant.branch_operations'] LOOP
 EXECUTE format('CREATE POLICY branch_administration_read ON %s FOR SELECT TO ls_core_branch_admin_dev USING(session_user=current_user AND current_user=''ls_core_branch_admin_dev'')',tab);
 END LOOP;
END $$;
GRANT INSERT(request_id,actor_user_id,tenant_id,branch_id,action,expected_updated_at,code,name,resulting_status,branch_created_at) ON tenant.branch_operations TO ls_core_branch_admin_dev;
CREATE POLICY branch_command ON tenant.branch_operations FOR INSERT TO ls_core_branch_admin_dev WITH CHECK(session_user=current_user AND current_user='ls_core_branch_admin_dev');
GRANT INSERT(id,tenant_id,code,name,status,created_at,updated_at),UPDATE(code,name,status,updated_at) ON tenant.branches TO ls_core_branch_admin_dev;
CREATE POLICY branch_insert ON tenant.branches FOR INSERT TO ls_core_branch_admin_dev WITH CHECK(session_user=current_user AND current_user='ls_core_branch_admin_dev' AND pg_trigger_depth()=1);
CREATE POLICY branch_update ON tenant.branches FOR UPDATE TO ls_core_branch_admin_dev USING(session_user=current_user AND current_user='ls_core_branch_admin_dev') WITH CHECK(pg_trigger_depth()=1);
GRANT UPDATE(revoked_at,updated_at) ON security.session_contexts TO ls_core_branch_admin_dev;
CREATE POLICY branch_context_update ON security.session_contexts FOR UPDATE TO ls_core_branch_admin_dev USING(session_user=current_user AND current_user='ls_core_branch_admin_dev') WITH CHECK(pg_trigger_depth()=1);
GRANT INSERT(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata) ON audit.events TO ls_core_branch_admin_dev,ls_core_branch_admin_log_dev;
CREATE POLICY branch_success ON audit.events FOR INSERT TO ls_core_branch_admin_dev WITH CHECK(session_user=current_user AND outcome='SUCCESS' AND action_code IN ('BRANCH_CREATE','BRANCH_EDIT','BRANCH_SUSPEND','BRANCH_REACTIVATE','BRANCH_CLOSE'));
CREATE POLICY branch_rejected ON audit.events FOR INSERT TO ls_core_branch_admin_log_dev WITH CHECK(session_user=current_user AND outcome IN ('DENIED','UNKNOWN') AND action_code IN ('BRANCH_CREATE','BRANCH_EDIT','BRANCH_SUSPEND','BRANCH_REACTIVATE','BRANCH_CLOSE'));
GRANT EXECUTE ON FUNCTION tenant.administrator_ready(uuid),tenant.valid_administrators(uuid) TO ls_core_branch_admin_dev;
DO $$ DECLARE f text; BEGIN
 FOREACH f IN ARRAY ARRAY['tenant.branch_administration_authority(uuid,uuid,boolean)','tenant.apply_branch_operation()',
 'tenant.complete_branch_operation()','tenant.guard_branch_child()','audit.guard_branch_event()'] LOOP
 EXECUTE 'REVOKE ALL ON FUNCTION '||f||' FROM PUBLIC';
 END LOOP;
END $$;
GRANT EXECUTE ON FUNCTION tenant.branch_administration_authority(uuid,uuid,boolean) TO ls_core_branch_admin_dev;
-- Minimum existing operational frontier. Coordinator and F05 hold the V23 shared barrier through COMMIT.
CREATE POLICY branch_administration_active ON tenant.branches AS RESTRICTIVE FOR SELECT TO ls_core_branch_operation_dev USING(status='ACTIVE');
CREATE OR REPLACE FUNCTION security.lifecycle_context_barrier() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 PERFORM pg_advisory_xact_lock_shared(410023,1);
 IF NOT EXISTS(SELECT FROM tenant.tenants WHERE id=NEW.tenant_id AND status='ACTIVE')
 OR NOT EXISTS(SELECT FROM tenant.branches WHERE tenant_id=NEW.tenant_id AND id=NEW.branch_id AND status='ACTIVE') THEN
 RAISE EXCEPTION 'Operational context unavailable' USING ERRCODE='42501'; END IF;
 RETURN NEW;
END $$;

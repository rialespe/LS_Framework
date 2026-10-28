-- LFD-41. Isolated lifecycle writer. No business admission, seeds or SECURITY DEFINER.
DO $$ BEGIN
 IF session_user<>'ls_core_migrator_dev' OR current_user<>session_user OR current_database()<>'ls_core_dev'
 OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999
 OR (SELECT count(*) FROM pg_roles r WHERE rolname IN ('ls_core_tenant_lifecycle_dev','ls_core_tenant_lifecycle_log_dev')
 AND rolcanlogin AND NOT(rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
 AND rolconnlimit=CASE WHEN rolname='ls_core_tenant_lifecycle_dev' THEN 3 ELSE 2 END
 AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
 AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o')
 AND NOT has_database_privilege(r.oid,current_database(),'CREATE,TEMPORARY'))<>2 THEN
 RAISE EXCEPTION 'Expected restricted V22 and fresh lifecycle channels'; END IF;
END $$;
CREATE TABLE platform.tenant_lifecycle_grants (
 designation_id uuid REFERENCES platform.administrators(id),
 capability text CHECK(capability IN ('READ','ACTIVATE','SUSPEND','REACTIVATE')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(designation_id,capability)
);
CREATE TABLE tenant.lifecycle_operations (
 request_id uuid PRIMARY KEY,
 actor_user_id uuid NOT NULL REFERENCES identity.users(id),
 tenant_id uuid NOT NULL REFERENCES tenant.tenants(id),
 action text NOT NULL CHECK(action IN ('ACTIVATE','SUSPEND','REACTIVATE')),
 previous_status text NOT NULL,
 resulting_status text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION tenant.apply_lifecycle() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE old_status text; prepared integer;
BEGIN
 IF session_user<>'ls_core_tenant_lifecycle_dev' OR current_user<>session_user OR TG_OP<>'INSERT'
 OR current_setting('transaction_isolation')<>'read committed' THEN
 RAISE EXCEPTION 'Lifecycle channel unavailable' USING ERRCODE='42501'; END IF;
 PERFORM pg_advisory_xact_lock(370019,1);
 PERFORM pg_advisory_xact_lock(410023,1);
 IF NOT EXISTS(SELECT FROM platform.administrators a JOIN identity.users u ON u.id=a.user_id
 WHERE a.user_id=NEW.actor_user_id AND a.status='ACTIVE' AND a.revoked_at IS NULL AND u.status='ACTIVE'
 AND (a.administrator_kind='OWNER' OR (a.administrator_kind='SUPERADMIN' AND EXISTS
 (SELECT FROM platform.tenant_lifecycle_grants g WHERE g.designation_id=a.id AND g.capability=NEW.action)))) THEN
 RAISE EXCEPTION 'Lifecycle not authorized' USING ERRCODE='42501'; END IF;
 SELECT status INTO old_status FROM tenant.tenants WHERE id=NEW.tenant_id FOR UPDATE;
 IF old_status IS DISTINCT FROM (CASE NEW.action WHEN 'ACTIVATE' THEN 'PENDING' WHEN 'SUSPEND' THEN 'ACTIVE' WHEN 'REACTIVATE' THEN 'SUSPENDED' END) THEN
 RAISE EXCEPTION 'Invalid transition' USING ERRCODE='P2301'; END IF;
 IF NEW.action<>'SUSPEND' THEN
 PERFORM 1 FROM tenant.initial_registrations r
 JOIN tenant.memberships m ON m.id=r.membership_id AND m.tenant_id=r.tenant_id AND m.user_id=r.administrator_user_id
 JOIN tenant.administrators a ON a.id=r.designation_id AND a.tenant_id=r.tenant_id AND a.membership_id=m.id
 JOIN identity.users u ON u.id=m.user_id
 JOIN identity.user_credentials c ON c.user_id=u.id
 WHERE r.tenant_id=NEW.tenant_id AND u.status='ACTIVE' AND a.revoked_at IS NULL
 AND m.status=CASE NEW.action WHEN 'ACTIVATE' THEN 'PENDING' ELSE 'ACTIVE' END
 AND a.status=m.status AND NOT c.must_change_password AND c.password_changed_at IS NOT NULL
 AND c.password_expires_at>clock_timestamp() AND (c.locked_until IS NULL OR c.locked_until<=clock_timestamp()) FOR SHARE OF u,c,m,a;
 GET DIAGNOSTICS prepared=ROW_COUNT;
 IF prepared<>1 OR EXISTS(SELECT FROM tenant.administrators a WHERE a.tenant_id=NEW.tenant_id AND a.revoked_at IS NULL
 AND NOT EXISTS(SELECT FROM tenant.initial_registrations r WHERE r.tenant_id=a.tenant_id AND r.designation_id=a.id)) THEN
 RAISE EXCEPTION 'Prepare initial administrator credentials and designation' USING ERRCODE='P2302'; END IF;
 END IF;
 NEW.previous_status=old_status;
 NEW.resulting_status=CASE NEW.action WHEN 'SUSPEND' THEN 'SUSPENDED' ELSE 'ACTIVE' END;
 NEW.created_at=clock_timestamp();
 RETURN NEW;
END $$;
CREATE TRIGGER lifecycle_guard BEFORE INSERT OR UPDATE OR DELETE ON tenant.lifecycle_operations FOR EACH ROW EXECUTE FUNCTION tenant.apply_lifecycle();
CREATE FUNCTION tenant.complete_lifecycle() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.action='ACTIVATE' THEN
 UPDATE tenant.memberships SET status='ACTIVE',accepted_at=clock_timestamp(),updated_at=clock_timestamp()
 WHERE id=(SELECT membership_id FROM tenant.initial_registrations WHERE tenant_id=NEW.tenant_id);
 UPDATE tenant.administrators SET status='ACTIVE',updated_at=clock_timestamp()
 WHERE id=(SELECT designation_id FROM tenant.initial_registrations WHERE tenant_id=NEW.tenant_id);
 END IF;
 UPDATE tenant.tenants SET status=NEW.resulting_status,updated_at=clock_timestamp() WHERE id=NEW.tenant_id;
 IF NEW.action='SUSPEND' THEN
 UPDATE security.session_contexts SET revoked_at=clock_timestamp(),updated_at=clock_timestamp()
 WHERE tenant_id=NEW.tenant_id AND revoked_at IS NULL;
 END IF;
 INSERT INTO audit.events(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
 VALUES(clock_timestamp(),'USER',NEW.actor_user_id,'TENANT_'||NEW.action,'TENANT',NEW.tenant_id,'SUCCESS',NEW.request_id,
 jsonb_build_object('from',NEW.previous_status,'to',NEW.resulting_status));
 RETURN NEW;
END $$;
CREATE TRIGGER lifecycle_complete AFTER INSERT ON tenant.lifecycle_operations FOR EACH ROW EXECUTE FUNCTION tenant.complete_lifecycle();
CREATE FUNCTION tenant.guard_lifecycle_child() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF current_user='ls_core_tenant_lifecycle_dev' AND (session_user<>current_user OR TG_OP<>'UPDATE' OR pg_trigger_depth()<>2) THEN
 RAISE EXCEPTION 'Atomic lifecycle operation required' USING ERRCODE='42501'; END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION audit.guard_lifecycle_event() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP IN ('UPDATE','DELETE') THEN
 IF OLD.action_code IN ('TENANT_ACTIVATE','TENANT_SUSPEND','TENANT_REACTIVATE') THEN
 RAISE EXCEPTION 'Immutable lifecycle evidence' USING ERRCODE='42501'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 END IF;
 IF NEW.action_code IN ('TENANT_ACTIVATE','TENANT_SUSPEND','TENANT_REACTIVATE')
 OR current_user IN ('ls_core_tenant_lifecycle_dev','ls_core_tenant_lifecycle_log_dev') THEN
 IF TG_OP<>'INSERT' OR session_user<>current_user OR NEW.actor_kind<>'USER' OR NEW.resource_type IS DISTINCT FROM 'TENANT'
 OR NEW.actor_user_id IS NULL OR NEW.correlation_id IS NULL OR NEW.tenant_id IS NOT NULL OR NEW.application_id IS NOT NULL
 OR NEW.session_id IS NOT NULL OR NEW.actor_api_key_id IS NOT NULL THEN
 RAISE EXCEPTION 'Invalid lifecycle evidence' USING ERRCODE='42501'; END IF;
 IF current_user='ls_core_tenant_lifecycle_dev' THEN
 IF pg_trigger_depth()<>2 OR NEW.outcome<>'SUCCESS' OR NOT EXISTS(SELECT FROM tenant.lifecycle_operations o
 WHERE o.request_id=NEW.correlation_id AND o.actor_user_id=NEW.actor_user_id AND o.tenant_id=NEW.resource_id
 AND NEW.action_code='TENANT_'||o.action AND NEW.metadata=jsonb_build_object('from',o.previous_status,'to',o.resulting_status)) THEN
 RAISE EXCEPTION 'Atomic evidence required' USING ERRCODE='42501'; END IF;
 ELSIF current_user='ls_core_tenant_lifecycle_log_dev' THEN
 IF NEW.action_code NOT IN ('TENANT_ACTIVATE','TENANT_SUSPEND','TENANT_REACTIVATE') OR NEW.outcome NOT IN ('DENIED','UNKNOWN') OR NEW.metadata<>'{}'::jsonb THEN
 RAISE EXCEPTION 'Rejection evidence required' USING ERRCODE='42501'; END IF;
 ELSE RAISE EXCEPTION 'Lifecycle evidence unavailable' USING ERRCODE='42501'; END IF;
 NEW.occurred_at=clock_timestamp(); NEW.created_at=NEW.occurred_at;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER lifecycle_event BEFORE INSERT OR UPDATE OR DELETE ON audit.events FOR EACH ROW EXECUTE FUNCTION audit.guard_lifecycle_event();
-- SQL-level issuance barrier, in addition to bounded Java context transactions.
CREATE FUNCTION security.lifecycle_context_barrier() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 PERFORM pg_advisory_xact_lock_shared(410023,1);
 IF NOT EXISTS(SELECT FROM tenant.tenants WHERE id=NEW.tenant_id AND status='ACTIVE') THEN
 RAISE EXCEPTION 'Tenant unavailable' USING ERRCODE='42501'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER lifecycle_context_barrier BEFORE INSERT ON security.session_contexts FOR EACH ROW EXECUTE FUNCTION security.lifecycle_context_barrier();
GRANT USAGE ON SCHEMA identity,platform,tenant,security,audit TO ls_core_tenant_lifecycle_dev;
GRANT USAGE ON SCHEMA audit TO ls_core_tenant_lifecycle_log_dev;
GRANT SELECT(id,username,display_name,status) ON identity.users TO ls_core_tenant_lifecycle_dev;
CREATE POLICY lifecycle_read ON identity.users FOR SELECT TO ls_core_tenant_lifecycle_dev USING(session_user=current_user AND current_user='ls_core_tenant_lifecycle_dev');
GRANT SELECT(user_id,must_change_password,password_changed_at,password_expires_at,locked_until) ON identity.user_credentials TO ls_core_tenant_lifecycle_dev;
CREATE POLICY lifecycle_read ON identity.user_credentials FOR SELECT TO ls_core_tenant_lifecycle_dev USING(session_user=current_user AND current_user='ls_core_tenant_lifecycle_dev');
GRANT SELECT(id,user_id,administrator_kind,status,revoked_at) ON platform.administrators TO ls_core_tenant_lifecycle_dev;
CREATE POLICY lifecycle_read ON platform.administrators FOR SELECT TO ls_core_tenant_lifecycle_dev USING(session_user=current_user AND current_user='ls_core_tenant_lifecycle_dev');
GRANT SELECT(designation_id,capability) ON platform.tenant_lifecycle_grants TO ls_core_tenant_lifecycle_dev;
ALTER TABLE platform.tenant_lifecycle_grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.tenant_lifecycle_grants FORCE ROW LEVEL SECURITY;
CREATE POLICY lifecycle_read ON platform.tenant_lifecycle_grants FOR SELECT TO ls_core_tenant_lifecycle_dev USING(session_user=current_user AND current_user='ls_core_tenant_lifecycle_dev');
GRANT SELECT(id,name,status,created_at) ON tenant.tenants TO ls_core_tenant_lifecycle_dev;
CREATE POLICY lifecycle_read ON tenant.tenants FOR SELECT TO ls_core_tenant_lifecycle_dev USING(session_user=current_user AND current_user='ls_core_tenant_lifecycle_dev');
GRANT SELECT(request_id,tenant_id,membership_id,designation_id,administrator_user_id) ON tenant.initial_registrations TO ls_core_tenant_lifecycle_dev;
CREATE POLICY lifecycle_read ON tenant.initial_registrations FOR SELECT TO ls_core_tenant_lifecycle_dev USING(session_user=current_user AND current_user='ls_core_tenant_lifecycle_dev');
GRANT SELECT(id,tenant_id,user_id,status) ON tenant.memberships TO ls_core_tenant_lifecycle_dev;
CREATE POLICY lifecycle_read ON tenant.memberships FOR SELECT TO ls_core_tenant_lifecycle_dev USING(session_user=current_user AND current_user='ls_core_tenant_lifecycle_dev');
GRANT SELECT(id,tenant_id,membership_id,status,revoked_at) ON tenant.administrators TO ls_core_tenant_lifecycle_dev;
CREATE POLICY lifecycle_read ON tenant.administrators FOR SELECT TO ls_core_tenant_lifecycle_dev USING(session_user=current_user AND current_user='ls_core_tenant_lifecycle_dev');
GRANT SELECT(id,tenant_id,revoked_at) ON security.session_contexts TO ls_core_tenant_lifecycle_dev;
CREATE POLICY lifecycle_read ON security.session_contexts FOR SELECT TO ls_core_tenant_lifecycle_dev USING(session_user=current_user AND current_user='ls_core_tenant_lifecycle_dev');
GRANT SELECT(request_id,actor_user_id,tenant_id,action,previous_status,resulting_status,created_at) ON tenant.lifecycle_operations TO ls_core_tenant_lifecycle_dev;
ALTER TABLE tenant.lifecycle_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant.lifecycle_operations FORCE ROW LEVEL SECURITY;
CREATE POLICY lifecycle_read ON tenant.lifecycle_operations FOR SELECT TO ls_core_tenant_lifecycle_dev USING(session_user=current_user AND current_user='ls_core_tenant_lifecycle_dev');
GRANT UPDATE(status,updated_at) ON tenant.tenants TO ls_core_tenant_lifecycle_dev;
CREATE POLICY lifecycle_update ON tenant.tenants FOR UPDATE TO ls_core_tenant_lifecycle_dev USING(session_user=current_user AND current_user='ls_core_tenant_lifecycle_dev') WITH CHECK(pg_trigger_depth()=1);
CREATE TRIGGER lifecycle_child BEFORE INSERT OR UPDATE ON tenant.tenants FOR EACH ROW EXECUTE FUNCTION tenant.guard_lifecycle_child();
GRANT UPDATE(status,accepted_at,updated_at) ON tenant.memberships TO ls_core_tenant_lifecycle_dev;
CREATE POLICY lifecycle_update ON tenant.memberships FOR UPDATE TO ls_core_tenant_lifecycle_dev USING(session_user=current_user AND current_user='ls_core_tenant_lifecycle_dev') WITH CHECK(pg_trigger_depth()=1);
CREATE TRIGGER lifecycle_child BEFORE INSERT OR UPDATE ON tenant.memberships FOR EACH ROW EXECUTE FUNCTION tenant.guard_lifecycle_child();
GRANT UPDATE(status,updated_at) ON tenant.administrators TO ls_core_tenant_lifecycle_dev;
CREATE POLICY lifecycle_update ON tenant.administrators FOR UPDATE TO ls_core_tenant_lifecycle_dev USING(session_user=current_user AND current_user='ls_core_tenant_lifecycle_dev') WITH CHECK(pg_trigger_depth()=1);
CREATE TRIGGER lifecycle_child BEFORE INSERT OR UPDATE ON tenant.administrators FOR EACH ROW EXECUTE FUNCTION tenant.guard_lifecycle_child();
GRANT UPDATE(revoked_at,updated_at) ON security.session_contexts TO ls_core_tenant_lifecycle_dev;
CREATE POLICY lifecycle_update ON security.session_contexts FOR UPDATE TO ls_core_tenant_lifecycle_dev USING(session_user=current_user AND current_user='ls_core_tenant_lifecycle_dev') WITH CHECK(pg_trigger_depth()=1);
CREATE TRIGGER lifecycle_child BEFORE INSERT OR UPDATE ON security.session_contexts FOR EACH ROW EXECUTE FUNCTION tenant.guard_lifecycle_child();
GRANT INSERT(request_id,actor_user_id,tenant_id,action,previous_status,resulting_status) ON tenant.lifecycle_operations TO ls_core_tenant_lifecycle_dev;
CREATE POLICY lifecycle_insert ON tenant.lifecycle_operations FOR INSERT TO ls_core_tenant_lifecycle_dev WITH CHECK(session_user=current_user AND current_user='ls_core_tenant_lifecycle_dev');
GRANT INSERT(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata) ON audit.events TO ls_core_tenant_lifecycle_dev,ls_core_tenant_lifecycle_log_dev;
CREATE POLICY lifecycle_success ON audit.events FOR INSERT TO ls_core_tenant_lifecycle_dev WITH CHECK(session_user=current_user AND outcome='SUCCESS' AND action_code IN ('TENANT_ACTIVATE','TENANT_SUSPEND','TENANT_REACTIVATE'));
CREATE POLICY lifecycle_rejection ON audit.events FOR INSERT TO ls_core_tenant_lifecycle_log_dev WITH CHECK(session_user=current_user AND outcome IN ('DENIED','UNKNOWN') AND action_code IN ('TENANT_ACTIVATE','TENANT_SUSPEND','TENANT_REACTIVATE'));
REVOKE ALL ON FUNCTION tenant.apply_lifecycle() FROM PUBLIC;
REVOKE ALL ON FUNCTION tenant.complete_lifecycle() FROM PUBLIC;
REVOKE ALL ON FUNCTION tenant.guard_lifecycle_child() FROM PUBLIC;
REVOKE ALL ON FUNCTION audit.guard_lifecycle_event() FROM PUBLIC;
REVOKE ALL ON FUNCTION security.lifecycle_context_barrier() FROM PUBLIC;

-- The existing bounded password-operation channel also participates in suspension draining.
GRANT SELECT(id,status) ON tenant.tenants TO ls_core_branch_operation_dev;
CREATE POLICY lifecycle_operation_tenant ON tenant.tenants FOR SELECT TO ls_core_branch_operation_dev
 USING(session_user=current_user AND current_user='ls_core_branch_operation_dev' AND status='ACTIVE');
CREATE POLICY lifecycle_operation_branch ON tenant.branches AS RESTRICTIVE FOR SELECT TO ls_core_branch_operation_dev
 USING(EXISTS(SELECT FROM tenant.tenants t WHERE t.id=branches.tenant_id AND t.status='ACTIVE'));

-- Row-lock-only ACLs: UPDATE policies never permit storing an identity/credential change.
GRANT UPDATE(id) ON identity.users TO ls_core_tenant_lifecycle_dev;
GRANT UPDATE(user_id) ON identity.user_credentials TO ls_core_tenant_lifecycle_dev;
CREATE POLICY lifecycle_identity_lock ON identity.users FOR UPDATE TO ls_core_tenant_lifecycle_dev
 USING(session_user=current_user AND current_user='ls_core_tenant_lifecycle_dev') WITH CHECK(false);
CREATE POLICY lifecycle_credential_lock ON identity.user_credentials FOR UPDATE TO ls_core_tenant_lifecycle_dev
 USING(session_user=current_user AND current_user='ls_core_tenant_lifecycle_dev') WITH CHECK(false);
DO $$ BEGIN
 IF (SELECT count(*) FROM pg_class WHERE oid IN ('tenant.tenants'::regclass,'tenant.memberships'::regclass,
 'tenant.administrators'::regclass,'identity.users'::regclass,'identity.user_credentials'::regclass,
 'audit.events'::regclass,'security.session_contexts'::regclass,'platform.administrators'::regclass)
 AND relowner=current_user::regrole AND relrowsecurity AND relforcerowsecurity)<>8 THEN
 RAISE EXCEPTION 'Expected FORCE RLS and restricted ownership'; END IF;
END $$;

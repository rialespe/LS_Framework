-- LFD-42. Commands through an isolated invoker channel; no seeds or HTTP table writes.
DO $$ BEGIN
 IF session_user<>'ls_core_migrator_dev' OR current_user<>session_user OR current_database()<>'ls_core_dev'
 OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999
 OR (SELECT count(*) FROM pg_roles r WHERE rolname IN ('ls_core_tenant_admin_dev','ls_core_tenant_admin_log_dev')
 AND rolcanlogin AND NOT(rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
 AND rolconnlimit=CASE WHEN rolname='ls_core_tenant_admin_dev' THEN 3 ELSE 2 END
 AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
 AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o')
 AND NOT has_database_privilege(r.oid,current_database(),'CREATE,TEMPORARY'))<>2 THEN
 RAISE EXCEPTION 'Expected V23 and fresh restricted administrator channels'; END IF;
END $$;
CREATE TABLE platform.tenant_administrator_grants (
 designation_id uuid REFERENCES platform.administrators(id),
 capability text CHECK(capability IN ('READ','MANAGE')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(designation_id,capability)
);
CREATE UNIQUE INDEX administrators_current_membership_uq ON tenant.administrators(tenant_id,membership_id) WHERE revoked_at IS NULL;
CREATE TABLE tenant.administrator_operations (
 request_id uuid PRIMARY KEY,
 actor_user_id uuid NOT NULL REFERENCES identity.users(id),
 tenant_id uuid NOT NULL REFERENCES tenant.tenants(id),
 action text NOT NULL CHECK(action IN ('DESIGNATE','ACTIVATE','REVOKE','REPLACE')),
 target_id uuid,
 candidate_username text,
 designation_id uuid NOT NULL,
 resulting_status text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(tenant_id,designation_id) REFERENCES tenant.administrators(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(tenant_id,target_id) REFERENCES tenant.administrators(tenant_id,id),
 CHECK((action IN ('DESIGNATE','REPLACE'))=(candidate_username IS NOT NULL)),
 CHECK((action<>'DESIGNATE')=(target_id IS NOT NULL))
);
CREATE FUNCTION tenant.administrator_ready(who uuid) RETURNS boolean LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
 SELECT EXISTS(SELECT FROM identity.users u JOIN identity.user_credentials c ON c.user_id=u.id
 WHERE u.id=who AND u.status='ACTIVE' AND NOT c.must_change_password AND c.password_changed_at IS NOT NULL
 AND c.password_expires_at>clock_timestamp() AND (c.locked_until IS NULL OR c.locked_until<=clock_timestamp()))
$$;
CREATE FUNCTION tenant.valid_administrators(company uuid) RETURNS TABLE(id uuid,user_id uuid)
 LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
 SELECT a.id,m.user_id FROM tenant.administrators a
 JOIN tenant.memberships m ON m.id=a.membership_id AND m.tenant_id=a.tenant_id
 WHERE a.tenant_id=company AND a.status='ACTIVE' AND a.revoked_at IS NULL AND m.status='ACTIVE'
 AND tenant.administrator_ready(m.user_id)
$$;
CREATE FUNCTION tenant.administrator_authority(actor uuid,company uuid,mutating boolean) RETURNS boolean
 LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
 SELECT tenant.administrator_ready(actor) AND (EXISTS(
 SELECT FROM platform.administrators a WHERE a.user_id=actor AND a.status='ACTIVE' AND a.revoked_at IS NULL
 AND (a.administrator_kind='OWNER' OR (a.administrator_kind='SUPERADMIN'
 AND EXISTS(SELECT FROM platform.tenant_administrator_grants g WHERE g.designation_id=a.id AND g.capability='READ')
 AND (NOT mutating OR EXISTS(SELECT FROM platform.tenant_administrator_grants g WHERE g.designation_id=a.id AND g.capability='MANAGE')))))
 OR (EXISTS(SELECT FROM tenant.tenants t WHERE t.id=company AND t.status='ACTIVE')
 AND EXISTS(SELECT FROM tenant.valid_administrators(company) a WHERE a.user_id=actor)))
$$;
CREATE FUNCTION tenant.apply_administrator_operation() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE company_status text; candidate uuid; member_id uuid; member_status text; old_member uuid; ready boolean;
BEGIN
 IF session_user<>'ls_core_tenant_admin_dev' OR current_user<>session_user OR TG_OP<>'INSERT'
 OR current_setting('transaction_isolation')<>'read committed' THEN
 RAISE EXCEPTION 'Administrator channel unavailable' USING ERRCODE='42501'; END IF;
 -- Same total order as F06/V23. The admission barrier drains existing bounded context operations.
 PERFORM pg_advisory_xact_lock(370019,1);
 PERFORM pg_advisory_xact_lock(410023,1);
 SELECT status INTO company_status FROM tenant.tenants WHERE id=NEW.tenant_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Company unavailable' USING ERRCODE='P2404'; END IF;
 IF NEW.candidate_username IS NOT NULL THEN
 SELECT id INTO candidate FROM identity.users WHERE username=NEW.candidate_username;
 IF candidate IS NULL THEN RAISE EXCEPTION 'Candidate unavailable' USING ERRCODE='P2404'; END IF;
 END IF;
 -- Pin the complete eligibility set against F04 changes, including the actor and the candidate.
 PERFORM u.id FROM identity.users u WHERE u.id IN (NEW.actor_user_id,candidate)
 OR u.id IN (SELECT m.user_id FROM tenant.memberships m WHERE m.tenant_id=NEW.tenant_id) ORDER BY u.id FOR SHARE;
 PERFORM c.user_id FROM identity.user_credentials c WHERE c.user_id IN (NEW.actor_user_id,candidate)
 OR c.user_id IN (SELECT m.user_id FROM tenant.memberships m WHERE m.tenant_id=NEW.tenant_id) ORDER BY c.user_id FOR SHARE;
 PERFORM m.id FROM tenant.memberships m WHERE m.tenant_id=NEW.tenant_id ORDER BY m.id FOR UPDATE;
 PERFORM a.id FROM tenant.administrators a WHERE a.tenant_id=NEW.tenant_id ORDER BY a.id FOR UPDATE;
 IF NOT tenant.administrator_authority(NEW.actor_user_id,NEW.tenant_id,true) THEN
 RAISE EXCEPTION 'Administrator not authorized' USING ERRCODE='42501'; END IF;
 IF NEW.action<>'DESIGNATE' THEN
 SELECT membership_id INTO old_member FROM tenant.administrators WHERE id=NEW.target_id AND tenant_id=NEW.tenant_id
 AND revoked_at IS NULL AND (NEW.action<>'ACTIVATE' OR status='PENDING');
 IF NOT FOUND THEN RAISE EXCEPTION 'Designation conflict' USING ERRCODE='P2401'; END IF;
 END IF;
 IF NEW.action IN ('DESIGNATE','REPLACE') THEN
 IF NOT EXISTS(SELECT FROM identity.users WHERE id=candidate AND status IN ('ACTIVE','PENDING')) THEN
 RAISE EXCEPTION 'Candidate requirements incomplete' USING ERRCODE='P2402'; END IF;
 ready=tenant.administrator_ready(candidate) AND company_status<>'PENDING';
 IF NEW.action='REPLACE' AND NOT ready THEN RAISE EXCEPTION 'Replacement must be ready' USING ERRCODE='P2402'; END IF;
 SELECT id,status INTO member_id,member_status FROM tenant.memberships WHERE tenant_id=NEW.tenant_id AND user_id=candidate;
 IF member_id IS NULL THEN
 member_id=gen_random_uuid();
 INSERT INTO tenant.memberships(id,tenant_id,user_id,status,invited_by_user_id,accepted_at)
 VALUES(member_id,NEW.tenant_id,candidate,CASE WHEN ready THEN 'ACTIVE' ELSE 'PENDING' END,NEW.actor_user_id,CASE WHEN ready THEN clock_timestamp() END);
 ELSIF member_status NOT IN ('ACTIVE','PENDING') THEN
 RAISE EXCEPTION 'Membership requires separate remediation' USING ERRCODE='P2402';
 ELSIF ready AND member_status='PENDING' THEN
 UPDATE tenant.memberships SET status='ACTIVE',accepted_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=member_id;
 END IF;
 IF EXISTS(SELECT FROM tenant.administrators WHERE tenant_id=NEW.tenant_id AND membership_id=member_id AND revoked_at IS NULL) THEN
 RAISE EXCEPTION 'Designation already exists' USING ERRCODE='P2401'; END IF;
 NEW.designation_id=gen_random_uuid(); NEW.resulting_status=CASE WHEN ready THEN 'ACTIVE' ELSE 'PENDING' END;
 INSERT INTO tenant.administrators(id,tenant_id,membership_id,status,granted_by_user_id,granted_at)
 VALUES(NEW.designation_id,NEW.tenant_id,member_id,NEW.resulting_status,NEW.actor_user_id,clock_timestamp());
 ELSIF NEW.action='ACTIVATE' THEN
 IF company_status='PENDING' OR NOT tenant.administrator_ready((SELECT user_id FROM tenant.memberships WHERE id=old_member))
 OR NOT EXISTS(SELECT FROM tenant.memberships WHERE id=old_member AND status IN ('PENDING','ACTIVE')) THEN
 RAISE EXCEPTION 'Complete F04 and tenant activation first' USING ERRCODE='P2402'; END IF;
 UPDATE tenant.memberships SET status='ACTIVE',accepted_at=COALESCE(accepted_at,clock_timestamp()),updated_at=clock_timestamp() WHERE id=old_member;
 UPDATE tenant.administrators SET status='ACTIVE',updated_at=clock_timestamp() WHERE id=NEW.target_id;
 NEW.designation_id=NEW.target_id; NEW.resulting_status='ACTIVE';
 ELSE NEW.designation_id=NEW.target_id; NEW.resulting_status='REVOKED';
 END IF;
 IF NEW.action IN ('REVOKE','REPLACE') THEN
 -- Replacement is already ACTIVE before the previous designation is revoked.
 UPDATE tenant.administrators SET status='REVOKED',revoked_at=clock_timestamp(),revoked_by_user_id=NEW.actor_user_id,updated_at=clock_timestamp() WHERE id=NEW.target_id;
 UPDATE security.session_contexts SET revoked_at=clock_timestamp(),updated_at=clock_timestamp()
 WHERE tenant_id=NEW.tenant_id AND membership_id=old_member AND revoked_at IS NULL;
 END IF;
 IF NEW.resulting_status='ACTIVE' THEN
 UPDATE security.session_contexts SET revoked_at=clock_timestamp(),updated_at=clock_timestamp()
 WHERE tenant_id=NEW.tenant_id AND membership_id=CASE WHEN NEW.action='ACTIVATE' THEN old_member ELSE member_id END AND revoked_at IS NULL;
 END IF;
 IF company_status='ACTIVE' AND NOT EXISTS(SELECT FROM tenant.valid_administrators(NEW.tenant_id)) THEN
 RAISE EXCEPTION 'Last valid administrator is protected' USING ERRCODE='P2401'; END IF;
 -- Preserve the V22 activation prerequisite for a PENDING company.
 IF company_status='PENDING' AND NEW.action IN ('REVOKE','REPLACE') AND EXISTS(
 SELECT FROM tenant.initial_registrations r WHERE r.tenant_id=NEW.tenant_id AND r.designation_id=NEW.target_id) THEN
 RAISE EXCEPTION 'Initial pending administrator is protected' USING ERRCODE='P2401'; END IF;
 NEW.created_at=clock_timestamp(); RETURN NEW;
END $$;
CREATE TRIGGER administrator_operation_guard BEFORE INSERT OR UPDATE OR DELETE ON tenant.administrator_operations
 FOR EACH ROW EXECUTE FUNCTION tenant.apply_administrator_operation();
CREATE FUNCTION tenant.complete_administrator_operation() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 INSERT INTO audit.events(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
 VALUES(clock_timestamp(),'USER',NEW.actor_user_id,'TENANT_ADMIN_'||NEW.action,'TENANT',NEW.tenant_id,'SUCCESS',NEW.request_id,
 jsonb_build_object('designation_id',NEW.designation_id,'target_id',NEW.target_id,'status',NEW.resulting_status));
 RETURN NEW;
END $$;
CREATE TRIGGER administrator_operation_complete AFTER INSERT ON tenant.administrator_operations FOR EACH ROW EXECUTE FUNCTION tenant.complete_administrator_operation();
-- Recheck at transaction completion as well as under the command locks.
CREATE FUNCTION tenant.guard_administrative_continuity() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF EXISTS(SELECT FROM tenant.tenants WHERE id=NEW.tenant_id AND status='ACTIVE')
 AND NOT EXISTS(SELECT FROM tenant.valid_administrators(NEW.tenant_id)) THEN
 RAISE EXCEPTION 'At least one valid administrator is required' USING ERRCODE='P2401'; END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER administrator_continuity AFTER INSERT ON tenant.administrator_operations
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION tenant.guard_administrative_continuity();
CREATE CONSTRAINT TRIGGER lifecycle_administrator_continuity AFTER INSERT ON tenant.lifecycle_operations
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION tenant.guard_administrative_continuity();
CREATE FUNCTION tenant.guard_administrator_child() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF current_user='ls_core_tenant_admin_dev' AND (session_user<>current_user OR TG_OP NOT IN ('INSERT','UPDATE') OR pg_trigger_depth()<>2) THEN
 RAISE EXCEPTION 'Atomic administrator command required' USING ERRCODE='42501'; END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION audit.guard_tenant_administrator_event() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP IN ('UPDATE','DELETE') THEN
 IF OLD.action_code IN ('TENANT_ADMIN_DESIGNATE','TENANT_ADMIN_ACTIVATE','TENANT_ADMIN_REVOKE','TENANT_ADMIN_REPLACE') THEN RAISE EXCEPTION 'Immutable evidence' USING ERRCODE='42501'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 END IF;
 IF NEW.action_code IN ('TENANT_ADMIN_DESIGNATE','TENANT_ADMIN_ACTIVATE','TENANT_ADMIN_REVOKE','TENANT_ADMIN_REPLACE') OR current_user IN ('ls_core_tenant_admin_dev','ls_core_tenant_admin_log_dev') THEN
 IF TG_OP<>'INSERT' OR session_user<>current_user OR NEW.actor_kind<>'USER' OR NEW.actor_user_id IS NULL
 OR NEW.resource_type IS DISTINCT FROM 'TENANT' OR NEW.correlation_id IS NULL OR NEW.tenant_id IS NOT NULL
 OR NEW.application_id IS NOT NULL OR NEW.session_id IS NOT NULL OR NEW.actor_api_key_id IS NOT NULL THEN
 RAISE EXCEPTION 'Invalid administrator evidence' USING ERRCODE='42501'; END IF;
 IF current_user='ls_core_tenant_admin_dev' THEN
 IF pg_trigger_depth()<>2 OR NEW.outcome<>'SUCCESS' OR NOT EXISTS(SELECT FROM tenant.administrator_operations o
 WHERE o.request_id=NEW.correlation_id AND o.actor_user_id=NEW.actor_user_id AND o.tenant_id=NEW.resource_id
 AND NEW.action_code='TENANT_ADMIN_'||o.action AND NEW.metadata=jsonb_build_object('designation_id',o.designation_id,'target_id',o.target_id,'status',o.resulting_status)) THEN
 RAISE EXCEPTION 'Atomic administrator evidence required' USING ERRCODE='42501'; END IF;
 ELSIF current_user='ls_core_tenant_admin_log_dev' THEN
 IF NEW.action_code NOT IN ('TENANT_ADMIN_DESIGNATE','TENANT_ADMIN_ACTIVATE','TENANT_ADMIN_REVOKE','TENANT_ADMIN_REPLACE')
 OR NEW.outcome NOT IN ('DENIED','UNKNOWN') OR NEW.metadata<>'{}'::jsonb THEN RAISE EXCEPTION 'Rejection evidence required' USING ERRCODE='42501'; END IF;
 ELSE RAISE EXCEPTION 'Evidence unavailable' USING ERRCODE='42501'; END IF;
 NEW.occurred_at=clock_timestamp(); NEW.created_at=NEW.occurred_at;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER tenant_administrator_event BEFORE INSERT OR UPDATE OR DELETE ON audit.events FOR EACH ROW EXECUTE FUNCTION audit.guard_tenant_administrator_event();

GRANT USAGE ON SCHEMA identity,platform,tenant,security,audit TO ls_core_tenant_admin_dev;
GRANT USAGE ON SCHEMA audit TO ls_core_tenant_admin_log_dev;
GRANT SELECT(id,username,display_name,status) ON identity.users TO ls_core_tenant_admin_dev;
GRANT SELECT(user_id,must_change_password,password_changed_at,password_expires_at,locked_until) ON identity.user_credentials TO ls_core_tenant_admin_dev;
GRANT SELECT(id,user_id,administrator_kind,status,revoked_at) ON platform.administrators TO ls_core_tenant_admin_dev;
GRANT SELECT ON platform.tenant_administrator_grants,tenant.administrator_operations TO ls_core_tenant_admin_dev;
GRANT SELECT(id,name,status,created_at) ON tenant.tenants TO ls_core_tenant_admin_dev;
GRANT SELECT(tenant_id,designation_id) ON tenant.initial_registrations TO ls_core_tenant_admin_dev;
GRANT SELECT(id,tenant_id,user_id,status,accepted_at) ON tenant.memberships TO ls_core_tenant_admin_dev;
GRANT SELECT(id,tenant_id,membership_id,status,granted_at,revoked_at) ON tenant.administrators TO ls_core_tenant_admin_dev;
GRANT SELECT(tenant_id,membership_id,revoked_at) ON security.session_contexts TO ls_core_tenant_admin_dev;
GRANT UPDATE(id) ON identity.users TO ls_core_tenant_admin_dev;
GRANT UPDATE(user_id) ON identity.user_credentials TO ls_core_tenant_admin_dev;
GRANT UPDATE(status) ON tenant.tenants TO ls_core_tenant_admin_dev;
CREATE POLICY administrator_identity_lock ON identity.users FOR UPDATE TO ls_core_tenant_admin_dev USING(session_user=current_user) WITH CHECK(false);
CREATE POLICY administrator_credential_lock ON identity.user_credentials FOR UPDATE TO ls_core_tenant_admin_dev USING(session_user=current_user) WITH CHECK(false);
CREATE POLICY administrator_tenant_lock ON tenant.tenants FOR UPDATE TO ls_core_tenant_admin_dev USING(session_user=current_user) WITH CHECK(false);
ALTER TABLE platform.tenant_administrator_grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.tenant_administrator_grants FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant.administrator_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant.administrator_operations FORCE ROW LEVEL SECURITY;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['identity.users','identity.user_credentials','platform.administrators','platform.tenant_administrator_grants',
 'tenant.tenants','tenant.initial_registrations','tenant.memberships','tenant.administrators','security.session_contexts','tenant.administrator_operations'] LOOP
 EXECUTE format('CREATE POLICY administrator_read ON %s FOR SELECT TO ls_core_tenant_admin_dev USING(session_user=current_user AND current_user=''ls_core_tenant_admin_dev'')',tab);
 END LOOP;
END $$;
GRANT INSERT(request_id,actor_user_id,tenant_id,action,target_id,candidate_username,designation_id,resulting_status) ON tenant.administrator_operations TO ls_core_tenant_admin_dev;
CREATE POLICY administrator_command ON tenant.administrator_operations FOR INSERT TO ls_core_tenant_admin_dev WITH CHECK(session_user=current_user AND current_user='ls_core_tenant_admin_dev');
GRANT INSERT(id,tenant_id,user_id,status,invited_by_user_id,accepted_at),UPDATE(status,accepted_at,updated_at) ON tenant.memberships TO ls_core_tenant_admin_dev;
GRANT INSERT(id,tenant_id,membership_id,status,granted_by_user_id,granted_at),UPDATE(status,revoked_at,revoked_by_user_id,updated_at) ON tenant.administrators TO ls_core_tenant_admin_dev;
GRANT UPDATE(revoked_at,updated_at) ON security.session_contexts TO ls_core_tenant_admin_dev;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['tenant.memberships','tenant.administrators','security.session_contexts'] LOOP
 EXECUTE format('CREATE POLICY administrator_update ON %s FOR UPDATE TO ls_core_tenant_admin_dev USING(session_user=current_user AND current_user=''ls_core_tenant_admin_dev'') WITH CHECK(pg_trigger_depth()=1)',tab);
 EXECUTE format('CREATE TRIGGER administrator_child BEFORE INSERT OR UPDATE ON %s FOR EACH ROW EXECUTE FUNCTION tenant.guard_administrator_child()',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['tenant.memberships','tenant.administrators'] LOOP
 EXECUTE format('CREATE POLICY administrator_insert ON %s FOR INSERT TO ls_core_tenant_admin_dev WITH CHECK(session_user=current_user AND current_user=''ls_core_tenant_admin_dev'' AND pg_trigger_depth()=1)',tab);
 END LOOP;
END $$;
GRANT INSERT(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata) ON audit.events TO ls_core_tenant_admin_dev,ls_core_tenant_admin_log_dev;
CREATE POLICY administrator_success ON audit.events FOR INSERT TO ls_core_tenant_admin_dev WITH CHECK(session_user=current_user AND outcome='SUCCESS' AND action_code IN ('TENANT_ADMIN_DESIGNATE','TENANT_ADMIN_ACTIVATE','TENANT_ADMIN_REVOKE','TENANT_ADMIN_REPLACE'));
CREATE POLICY administrator_rejected ON audit.events FOR INSERT TO ls_core_tenant_admin_log_dev WITH CHECK(session_user=current_user AND outcome IN ('DENIED','UNKNOWN') AND action_code IN ('TENANT_ADMIN_DESIGNATE','TENANT_ADMIN_ACTIVATE','TENANT_ADMIN_REVOKE','TENANT_ADMIN_REPLACE'));
DO $$ DECLARE f text; BEGIN
 FOREACH f IN ARRAY ARRAY['tenant.administrator_ready(uuid)','tenant.valid_administrators(uuid)','tenant.administrator_authority(uuid,uuid,boolean)',
 'tenant.apply_administrator_operation()','tenant.complete_administrator_operation()','tenant.guard_administrative_continuity()','tenant.guard_administrator_child()','audit.guard_tenant_administrator_event()'] LOOP
 EXECUTE 'REVOKE ALL ON FUNCTION '||f||' FROM PUBLIC';
 END LOOP;
END $$;
GRANT EXECUTE ON FUNCTION tenant.administrator_ready(uuid),tenant.valid_administrators(uuid),tenant.administrator_authority(uuid,uuid,boolean) TO ls_core_tenant_admin_dev;
GRANT EXECUTE ON FUNCTION tenant.administrator_ready(uuid),tenant.valid_administrators(uuid) TO ls_core_tenant_lifecycle_dev;

-- V23 activation retains the initial preparation; reactivation accepts authorized replacements.
CREATE OR REPLACE FUNCTION tenant.apply_lifecycle() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
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
 IF NEW.action='ACTIVATE' THEN
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
 IF prepared<>1 THEN
 RAISE EXCEPTION 'Prepare initial administrator credentials and designation' USING ERRCODE='P2302'; END IF;
 ELSIF NEW.action='REACTIVATE' THEN
 PERFORM u.id FROM identity.users u WHERE u.id IN (SELECT m.user_id FROM tenant.memberships m WHERE m.tenant_id=NEW.tenant_id) ORDER BY u.id FOR SHARE;
 PERFORM c.user_id FROM identity.user_credentials c WHERE c.user_id IN (SELECT m.user_id FROM tenant.memberships m WHERE m.tenant_id=NEW.tenant_id) ORDER BY c.user_id FOR SHARE;
 PERFORM m.id FROM tenant.memberships m WHERE m.tenant_id=NEW.tenant_id ORDER BY m.id FOR SHARE;
 PERFORM a.id FROM tenant.administrators a WHERE a.tenant_id=NEW.tenant_id ORDER BY a.id FOR SHARE;
 IF NOT EXISTS(SELECT FROM tenant.valid_administrators(NEW.tenant_id)) THEN
 RAISE EXCEPTION 'Prepare at least one valid administrator' USING ERRCODE='P2302'; END IF;
 END IF;
 NEW.previous_status=old_status;
 NEW.resulting_status=CASE NEW.action WHEN 'SUSPEND' THEN 'SUSPENDED' ELSE 'ACTIVE' END;
 NEW.created_at=clock_timestamp();
 RETURN NEW;
END $$;

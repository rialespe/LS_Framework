-- LFD-45: denied-by-default future operation barrier and separate bounded historical channel.
DO $$ BEGIN
 IF session_user<>'ls_core_migrator_dev' OR current_user<>session_user OR current_database()<>'ls_core_dev'
 OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999
 OR (SELECT count(*) FROM pg_roles r WHERE rolname IN ('ls_core_branch_guard_dev','ls_core_branch_history_dev')
 AND rolcanlogin AND rolconnlimit=3 AND NOT(rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
 AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
 AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o')
 AND NOT has_database_privilege(r.oid,current_database(),'CREATE,TEMPORARY'))<>2
 THEN RAISE EXCEPTION 'Expected V26 and fresh isolated guard/history roles'; END IF;
END $$;
-- Empty technical contracts. No HTTP grant management or implicit administrator authority (F09 pending).
CREATE TABLE security.branch_operation_grants (
 tenant_id uuid, membership_id uuid, branch_id uuid, application_id uuid, module_id uuid,
 capability text CHECK(capability IN ('HISTORY_READ','WRITE_PROBE')),
 expires_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,membership_id,branch_id,application_id,module_id,capability),
 FOREIGN KEY(tenant_id,membership_id,branch_id) REFERENCES tenant.branch_memberships(tenant_id,membership_id,branch_id),
 FOREIGN KEY(tenant_id,application_id,module_id) REFERENCES catalog.tenant_modules(tenant_id,application_id,module_id)
);
CREATE TABLE security.branch_multihistory_grants (
 tenant_id uuid, membership_id uuid, expires_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,membership_id), FOREIGN KEY(tenant_id,membership_id) REFERENCES tenant.memberships(tenant_id,id)
);
CREATE TABLE security.branch_history_requests (
 request_id uuid PRIMARY KEY, actor_user_id uuid NOT NULL REFERENCES identity.users(id), tenant_id uuid NOT NULL REFERENCES tenant.tenants(id),
 application_id uuid NOT NULL, module_id uuid NOT NULL, branch_ids uuid[] NOT NULL,
 backend_pid integer NOT NULL, transaction_id xid8 NOT NULL,
 created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 FOREIGN KEY(tenant_id,application_id,module_id) REFERENCES catalog.tenant_modules(tenant_id,application_id,module_id),
 CHECK(cardinality(branch_ids) BETWEEN 1 AND 20), CHECK(expires_at>created_at AND expires_at<=created_at+interval '10 seconds')
);
CREATE INDEX branch_history_native_idx ON security.branch_history_requests(backend_pid,transaction_id,expires_at);
CREATE INDEX branch_operations_history_scope_idx ON tenant.branch_operations(tenant_id,branch_id,created_at DESC,request_id DESC);
CREATE FUNCTION security.branch_guard_locks() RETURNS void LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF session_user<>current_user OR current_user NOT IN ('ls_core_branch_history_dev','ls_core_branch_guard_dev')
 OR current_setting('transaction_isolation')<>'read committed' OR current_setting('transaction_read_only')<>'off' THEN
 RAISE EXCEPTION 'Guard unavailable' USING ERRCODE='42501'; END IF;
 PERFORM pg_advisory_xact_lock_shared(370019,1);
 PERFORM pg_advisory_xact_lock_shared(410023,1);
END $$;
CREATE FUNCTION security.branch_guard_authority(actor uuid,company uuid,branch uuid,app uuid,mod uuid,cap text) RETURNS uuid
 LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE member_id uuid;
BEGIN
 PERFORM security.branch_guard_locks();
 PERFORM id FROM tenant.tenants WHERE id=company AND status='ACTIVE' FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Tenant state rejected' USING ERRCODE='P2701'; END IF;
 PERFORM id FROM tenant.branches WHERE tenant_id=company AND id=branch FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Branch unavailable' USING ERRCODE='42501'; END IF;
 PERFORM id FROM identity.users WHERE id=actor AND status='ACTIVE' FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Authority rejected' USING ERRCODE='42501'; END IF;
 PERFORM user_id FROM identity.user_credentials WHERE user_id=actor AND NOT must_change_password
 AND password_changed_at IS NOT NULL AND (password_expires_at IS NULL OR password_expires_at>clock_timestamp())
 AND (locked_until IS NULL OR locked_until<=clock_timestamp()) FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Authority rejected' USING ERRCODE='42501'; END IF;
 SELECT m.id INTO member_id FROM tenant.memberships m JOIN tenant.branch_memberships b
 ON b.tenant_id=m.tenant_id AND b.membership_id=m.id AND b.branch_id=branch
 WHERE m.tenant_id=company AND m.user_id=actor AND m.status='ACTIVE' AND b.status='ACTIVE' FOR SHARE OF m,b;
 IF NOT FOUND OR NOT EXISTS(SELECT FROM security.branch_operation_grants g WHERE g.tenant_id=company
 AND g.membership_id=member_id AND g.branch_id=branch AND g.application_id=app AND g.module_id=mod AND g.capability=cap
 AND g.expires_at>clock_timestamp()) THEN RAISE EXCEPTION 'Authority rejected' USING ERRCODE='42501'; END IF;
 IF NOT EXISTS(SELECT FROM catalog.applications a JOIN catalog.modules m ON m.application_id=a.id
 JOIN catalog.tenant_applications ta ON ta.application_id=a.id AND ta.tenant_id=company
 JOIN catalog.tenant_modules tm ON tm.application_id=a.id AND tm.module_id=m.id AND tm.tenant_id=company
 WHERE a.id=app AND m.id=mod AND a.status='PUBLISHED' AND m.status='PUBLISHED' AND ta.status='ACTIVE' AND tm.status='ACTIVE') THEN
 RAISE EXCEPTION 'Module unavailable' USING ERRCODE='P2703'; END IF;
 RETURN member_id;
END $$;
-- No business DML grants: this is a fixture/internal admission probe, never an HTTP business channel.
-- A future approved consumer must call in its owned transaction and retain locks through its own COMMIT.
CREATE FUNCTION security.require_active_branch_operation(actor uuid,company uuid,branch uuid,app uuid,mod uuid,ctx uuid,required_version integer DEFAULT NULL)
 RETURNS void LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE member_id uuid;
BEGIN
 IF current_user<>'ls_core_branch_guard_dev' OR session_user<>current_user THEN RAISE EXCEPTION 'Guard unavailable' USING ERRCODE='42501'; END IF;
 member_id=security.branch_guard_authority(actor,company,branch,app,mod,'WRITE_PROBE');
 IF NOT EXISTS(SELECT FROM tenant.branches WHERE tenant_id=company AND id=branch AND status='ACTIVE') THEN
 RAISE EXCEPTION 'Branch state rejected' USING ERRCODE='P2702'; END IF;
 PERFORM c.id FROM security.session_contexts c JOIN security.sessions s ON s.id=c.session_id AND s.user_id=c.user_id AND s.application_id=c.application_id
 WHERE c.id=ctx AND c.tenant_id=company AND c.branch_id=branch AND c.application_id=app AND c.user_id=actor AND c.membership_id=member_id
 AND c.revoked_at IS NULL AND c.expires_at>clock_timestamp() AND s.revoked_at IS NULL AND s.expires_at>clock_timestamp() FOR SHARE OF c,s;
 IF NOT FOUND THEN RAISE EXCEPTION 'Context rejected' USING ERRCODE='42501'; END IF;
 IF required_version IS NOT NULL AND NOT EXISTS(SELECT FROM tenant.branch_configurations c
 JOIN catalog.branch_configuration_definitions d ON d.application_id=c.application_id AND d.module_id=c.module_id AND d.version=c.definition_version
 WHERE c.tenant_id=company AND c.branch_id=branch AND c.application_id=app AND c.module_id=mod AND c.definition_version=required_version
 AND c.readiness='READY' AND d.status='PUBLISHED') THEN RAISE EXCEPTION 'Readiness rejected' USING ERRCODE='P2704'; END IF;
END $$;
CREATE FUNCTION security.admit_branch_history() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE branch uuid; member_id uuid;
BEGIN
 IF TG_OP<>'INSERT' OR current_user<>'ls_core_branch_history_dev' OR session_user<>current_user THEN
 RAISE EXCEPTION 'Immutable historical evidence' USING ERRCODE='42501'; END IF;
 PERFORM security.branch_guard_locks();
 IF NEW.branch_ids IS NULL OR cardinality(NEW.branch_ids) NOT BETWEEN 1 AND 20
 OR EXISTS(SELECT FROM unnest(NEW.branch_ids) b WHERE b IS NULL)
 OR (SELECT count(DISTINCT b) FROM unnest(NEW.branch_ids) b)<>cardinality(NEW.branch_ids) THEN
 RAISE EXCEPTION 'Invalid history selection' USING ERRCODE='42501'; END IF;
 -- This resource contract exposes administrative branch changes only, not arbitrary module data.
 IF NOT EXISTS(SELECT FROM catalog.modules m JOIN catalog.applications a ON a.id=m.application_id
 WHERE a.id=NEW.application_id AND m.id=NEW.module_id AND a.code='ls-core' AND m.code='branches') THEN
 RAISE EXCEPTION 'Historical resource unavailable' USING ERRCODE='42501'; END IF;
 FOR branch IN SELECT b FROM unnest(NEW.branch_ids) b ORDER BY b LOOP
 member_id=security.branch_guard_authority(NEW.actor_user_id,NEW.tenant_id,branch,NEW.application_id,NEW.module_id,'HISTORY_READ');
 END LOOP;
 IF cardinality(NEW.branch_ids)>1 AND NOT EXISTS(SELECT FROM security.branch_multihistory_grants g
 WHERE g.tenant_id=NEW.tenant_id AND g.membership_id=member_id AND g.expires_at>clock_timestamp()) THEN
 RAISE EXCEPTION 'Multibranch authority rejected' USING ERRCODE='42501'; END IF;
 NEW.backend_pid=pg_backend_pid(); NEW.transaction_id=pg_current_xact_id();
 NEW.created_at=clock_timestamp(); NEW.expires_at=NEW.created_at+interval '10 seconds'; RETURN NEW;
END $$;
CREATE TRIGGER history_admission BEFORE INSERT OR UPDATE OR DELETE ON security.branch_history_requests FOR EACH ROW EXECUTE FUNCTION security.admit_branch_history();
CREATE TRIGGER history_no_truncate BEFORE TRUNCATE ON security.branch_history_requests FOR EACH STATEMENT EXECUTE FUNCTION security.admit_branch_history();
CREATE FUNCTION security.audit_branch_history() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 INSERT INTO audit.events(actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
 VALUES('USER',NEW.actor_user_id,'BRANCH_HISTORY_ADMITTED','TENANT',NEW.tenant_id,'SUCCESS',NEW.request_id,
 jsonb_build_object('branch_ids',NEW.branch_ids,'application_id',NEW.application_id,'module_id',NEW.module_id)); RETURN NEW;
END $$;
CREATE TRIGGER history_evidence AFTER INSERT ON security.branch_history_requests FOR EACH ROW EXECUTE FUNCTION security.audit_branch_history();
-- Evidence is native-transaction bound, short lived, not a GUC or transferable client capability.
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['security.branch_operation_grants','security.branch_multihistory_grants','security.branch_history_requests'] LOOP
 EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY',tab); EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['security.branch_operation_grants','security.branch_multihistory_grants'] LOOP
 EXECUTE format('CREATE TRIGGER operation_grant_coordination BEFORE INSERT OR UPDATE OR DELETE ON %s FOR EACH STATEMENT EXECUTE FUNCTION tenant.branch_configuration_barrier()',tab);
 END LOOP;
END $$;
GRANT USAGE ON SCHEMA security,identity,tenant,catalog,audit TO ls_core_branch_guard_dev,ls_core_branch_history_dev;
GRANT SELECT(id,status) ON identity.users,tenant.tenants TO ls_core_branch_guard_dev,ls_core_branch_history_dev;
GRANT SELECT(user_id,must_change_password,password_changed_at,password_expires_at,locked_until) ON identity.user_credentials TO ls_core_branch_guard_dev,ls_core_branch_history_dev;
GRANT SELECT(id,tenant_id,status) ON tenant.branches TO ls_core_branch_guard_dev,ls_core_branch_history_dev;
GRANT SELECT(id,tenant_id,user_id,status) ON tenant.memberships TO ls_core_branch_guard_dev,ls_core_branch_history_dev;
GRANT SELECT(tenant_id,membership_id,branch_id,status) ON tenant.branch_memberships TO ls_core_branch_guard_dev,ls_core_branch_history_dev;
GRANT SELECT(id,code,status) ON catalog.applications TO ls_core_branch_guard_dev,ls_core_branch_history_dev;
GRANT SELECT(id,application_id,code,status) ON catalog.modules TO ls_core_branch_guard_dev,ls_core_branch_history_dev;
GRANT SELECT(tenant_id,application_id,status) ON catalog.tenant_applications TO ls_core_branch_guard_dev,ls_core_branch_history_dev;
GRANT SELECT(tenant_id,application_id,module_id,status) ON catalog.tenant_modules TO ls_core_branch_guard_dev,ls_core_branch_history_dev;
GRANT SELECT ON security.branch_operation_grants,security.branch_multihistory_grants TO ls_core_branch_guard_dev,ls_core_branch_history_dev;
GRANT UPDATE(id) ON tenant.tenants,tenant.branches,identity.users TO ls_core_branch_guard_dev,ls_core_branch_history_dev;
GRANT UPDATE(user_id) ON identity.user_credentials TO ls_core_branch_guard_dev,ls_core_branch_history_dev;
GRANT UPDATE(id) ON tenant.memberships TO ls_core_branch_guard_dev,ls_core_branch_history_dev;
GRANT UPDATE(status) ON tenant.branch_memberships TO ls_core_branch_guard_dev,ls_core_branch_history_dev;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['identity.users','identity.user_credentials','tenant.tenants','tenant.branches','tenant.memberships','tenant.branch_memberships',
 'catalog.tenant_applications','catalog.tenant_modules','security.branch_operation_grants','security.branch_multihistory_grants'] LOOP
 EXECUTE format('CREATE POLICY operation_guard_metadata ON %s FOR SELECT TO ls_core_branch_guard_dev,ls_core_branch_history_dev USING(session_user=current_user)',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['identity.users','identity.user_credentials','tenant.tenants','tenant.branches','tenant.memberships','tenant.branch_memberships'] LOOP
 EXECUTE format('CREATE POLICY operation_guard_lock ON %s FOR UPDATE TO ls_core_branch_guard_dev,ls_core_branch_history_dev USING(session_user=current_user) WITH CHECK(false)',tab);
 END LOOP;
END $$;
GRANT SELECT(id,session_id,user_id,application_id,tenant_id,membership_id,branch_id,expires_at,revoked_at) ON security.session_contexts TO ls_core_branch_guard_dev;
GRANT SELECT(id,user_id,application_id,expires_at,revoked_at) ON security.sessions TO ls_core_branch_guard_dev;
GRANT UPDATE(id) ON security.session_contexts,security.sessions TO ls_core_branch_guard_dev;
CREATE POLICY operation_guard_context ON security.session_contexts FOR SELECT TO ls_core_branch_guard_dev USING(session_user=current_user);
CREATE POLICY operation_guard_session ON security.sessions FOR SELECT TO ls_core_branch_guard_dev USING(session_user=current_user);
CREATE POLICY operation_guard_context_lock ON security.session_contexts FOR UPDATE TO ls_core_branch_guard_dev USING(session_user=current_user) WITH CHECK(false);
CREATE POLICY operation_guard_session_lock ON security.sessions FOR UPDATE TO ls_core_branch_guard_dev USING(session_user=current_user) WITH CHECK(false);
GRANT SELECT(tenant_id,branch_id,application_id,module_id,definition_version,readiness) ON tenant.branch_configurations TO ls_core_branch_guard_dev;
GRANT SELECT(application_id,module_id,version,status) ON catalog.branch_configuration_definitions TO ls_core_branch_guard_dev;
CREATE POLICY operation_guard_config ON tenant.branch_configurations FOR SELECT TO ls_core_branch_guard_dev USING(session_user=current_user);
CREATE POLICY operation_guard_contract ON catalog.branch_configuration_definitions FOR SELECT TO ls_core_branch_guard_dev USING(session_user=current_user);
GRANT INSERT(request_id,actor_user_id,tenant_id,application_id,module_id,branch_ids) ON security.branch_history_requests TO ls_core_branch_history_dev;
GRANT SELECT ON security.branch_history_requests TO ls_core_branch_history_dev;
CREATE POLICY history_command ON security.branch_history_requests FOR INSERT TO ls_core_branch_history_dev WITH CHECK(session_user=current_user);
CREATE POLICY history_native ON security.branch_history_requests FOR SELECT TO ls_core_branch_history_dev
 USING(session_user=current_user AND backend_pid=pg_backend_pid() AND transaction_id=pg_current_xact_id_if_assigned() AND expires_at>clock_timestamp()
 AND EXISTS(SELECT FROM tenant.memberships m JOIN identity.users u ON u.id=m.user_id
 JOIN identity.user_credentials c ON c.user_id=u.id WHERE m.tenant_id=branch_history_requests.tenant_id
 AND m.user_id=branch_history_requests.actor_user_id AND m.status='ACTIVE' AND u.status='ACTIVE'
 AND NOT c.must_change_password AND c.password_changed_at IS NOT NULL
 AND (c.password_expires_at IS NULL OR c.password_expires_at>clock_timestamp())
 AND (c.locked_until IS NULL OR c.locked_until<=clock_timestamp())
 AND (cardinality(branch_ids)=1 OR EXISTS(SELECT FROM security.branch_multihistory_grants mg
 WHERE mg.tenant_id=m.tenant_id AND mg.membership_id=m.id AND mg.expires_at>clock_timestamp()))
 AND NOT EXISTS(SELECT FROM unnest(branch_ids) b WHERE NOT EXISTS(SELECT FROM security.branch_operation_grants g
 WHERE g.tenant_id=m.tenant_id AND g.membership_id=m.id AND g.branch_id=b AND g.application_id=branch_history_requests.application_id
 AND g.module_id=branch_history_requests.module_id AND g.capability='HISTORY_READ' AND g.expires_at>clock_timestamp()))));
GRANT SELECT(request_id,tenant_id,branch_id,action,previous_status,resulting_status,created_at) ON tenant.branch_operations TO ls_core_branch_history_dev;
CREATE POLICY history_selected_branches ON tenant.branch_operations FOR SELECT TO ls_core_branch_history_dev
 USING(session_user=current_user AND EXISTS(SELECT FROM security.branch_history_requests r WHERE r.tenant_id=branch_operations.tenant_id AND branch_operations.branch_id=ANY(r.branch_ids)));
GRANT INSERT(actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata) ON audit.events TO ls_core_branch_guard_dev,ls_core_branch_history_dev;
CREATE POLICY operation_guard_evidence ON audit.events FOR INSERT TO ls_core_branch_guard_dev,ls_core_branch_history_dev WITH CHECK(session_user=current_user
 AND actor_kind='USER' AND actor_user_id IS NOT NULL AND outcome='SUCCESS' AND correlation_id IS NOT NULL AND tenant_id IS NULL
 AND application_id IS NULL AND session_id IS NULL AND actor_api_key_id IS NULL AND (
 (current_user='ls_core_branch_guard_dev' AND action_code='BRANCH_WRITE_PROBE_ADMITTED' AND resource_type='TENANT_BRANCH') OR
 (current_user='ls_core_branch_history_dev' AND action_code='BRANCH_HISTORY_ADMITTED' AND resource_type='TENANT' AND pg_trigger_depth()=1)));
DO $$ DECLARE f text; BEGIN
 FOREACH f IN ARRAY ARRAY['security.branch_guard_locks()','security.branch_guard_authority(uuid,uuid,uuid,uuid,uuid,text)',
 'security.require_active_branch_operation(uuid,uuid,uuid,uuid,uuid,uuid,integer)','security.admit_branch_history()','security.audit_branch_history()'] LOOP
 EXECUTE 'REVOKE ALL ON FUNCTION '||f||' FROM PUBLIC'; END LOOP;
END $$;
GRANT EXECUTE ON FUNCTION security.branch_guard_locks(),security.branch_guard_authority(uuid,uuid,uuid,uuid,uuid,text) TO ls_core_branch_guard_dev,ls_core_branch_history_dev;
GRANT EXECUTE ON FUNCTION security.require_active_branch_operation(uuid,uuid,uuid,uuid,uuid,uuid,integer) TO ls_core_branch_guard_dev;

CREATE TABLE security.branch_write_probes (
 request_id uuid PRIMARY KEY,actor_user_id uuid NOT NULL REFERENCES identity.users(id),tenant_id uuid NOT NULL,
 branch_id uuid NOT NULL,application_id uuid NOT NULL,module_id uuid NOT NULL,context_id uuid NOT NULL,
 required_version integer,created_at timestamptz NOT NULL,
 FOREIGN KEY(tenant_id,branch_id) REFERENCES tenant.branches(tenant_id,id),
 FOREIGN KEY(tenant_id,application_id,module_id) REFERENCES catalog.tenant_modules(tenant_id,application_id,module_id)
);
ALTER TABLE security.branch_write_probes ENABLE ROW LEVEL SECURITY;
ALTER TABLE security.branch_write_probes FORCE ROW LEVEL SECURITY;
CREATE FUNCTION security.admit_branch_write_probe() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP<>'INSERT' OR session_user<>current_user OR current_user<>'ls_core_branch_guard_dev' THEN
 RAISE EXCEPTION 'Immutable admission probe' USING ERRCODE='42501'; END IF;
 PERFORM security.require_active_branch_operation(NEW.actor_user_id,NEW.tenant_id,NEW.branch_id,NEW.application_id,NEW.module_id,NEW.context_id,NEW.required_version);
 NEW.created_at=clock_timestamp(); RETURN NEW;
END $$;
CREATE TRIGGER write_probe_guard BEFORE INSERT OR UPDATE OR DELETE ON security.branch_write_probes FOR EACH ROW EXECUTE FUNCTION security.admit_branch_write_probe();
CREATE TRIGGER write_probe_no_truncate BEFORE TRUNCATE ON security.branch_write_probes FOR EACH STATEMENT EXECUTE FUNCTION security.admit_branch_write_probe();
CREATE FUNCTION security.audit_branch_write_probe() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 INSERT INTO audit.events(actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
 VALUES('USER',NEW.actor_user_id,'BRANCH_WRITE_PROBE_ADMITTED','TENANT_BRANCH',NEW.branch_id,'SUCCESS',NEW.request_id,
 jsonb_build_object('tenant_id',NEW.tenant_id,'application_id',NEW.application_id,'module_id',NEW.module_id)); RETURN NEW;
END $$;
CREATE TRIGGER write_probe_evidence AFTER INSERT ON security.branch_write_probes FOR EACH ROW EXECUTE FUNCTION security.audit_branch_write_probe();
GRANT INSERT(request_id,actor_user_id,tenant_id,branch_id,application_id,module_id,context_id,required_version) ON security.branch_write_probes TO ls_core_branch_guard_dev;
CREATE POLICY write_probe_command ON security.branch_write_probes FOR INSERT TO ls_core_branch_guard_dev WITH CHECK(session_user=current_user);
-- No SELECT on the probe ledger is needed by the runtime. Successful INSERT is admission evidence only.
CREATE FUNCTION audit.guard_branch_admission_event() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP IN ('UPDATE','DELETE') AND OLD.action_code IN ('BRANCH_HISTORY_ADMITTED','BRANCH_WRITE_PROBE_ADMITTED','BRANCH_READ_PROOF_ISSUED','BRANCH_HISTORY_REJECTED') THEN
 RAISE EXCEPTION 'Immutable admission evidence' USING ERRCODE='42501'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 IF NEW.action_code IN ('BRANCH_HISTORY_ADMITTED','BRANCH_WRITE_PROBE_ADMITTED','BRANCH_READ_PROOF_ISSUED','BRANCH_HISTORY_REJECTED') THEN
 IF TG_OP<>'INSERT' OR session_user<>current_user OR pg_trigger_depth()<>2 OR (NEW.action_code='BRANCH_HISTORY_REJECTED' AND NEW.outcome<>'DENIED') OR (NEW.action_code<>'BRANCH_HISTORY_REJECTED' AND NEW.outcome<>'SUCCESS')
 OR (NEW.action_code IN ('BRANCH_HISTORY_ADMITTED','BRANCH_HISTORY_REJECTED') AND current_user<>'ls_core_branch_history_dev')
 OR (NEW.action_code='BRANCH_WRITE_PROBE_ADMITTED' AND current_user<>'ls_core_branch_guard_dev')
 OR (NEW.action_code='BRANCH_READ_PROOF_ISSUED' AND current_user<>'ls_core_operation_emitter_dev') THEN
 RAISE EXCEPTION 'Atomic admission evidence required' USING ERRCODE='42501'; END IF;
 NEW.occurred_at=clock_timestamp(); NEW.created_at=NEW.occurred_at;
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER branch_admission_event BEFORE INSERT OR UPDATE OR DELETE ON audit.events FOR EACH ROW EXECUTE FUNCTION audit.guard_branch_admission_event();
CREATE FUNCTION security.audit_branch_read_proof() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 INSERT INTO audit.events(actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
 VALUES('USER',NEW.actor_id,'BRANCH_READ_PROOF_ISSUED','TENANT_BRANCH',NEW.branch_id,'SUCCESS',gen_random_uuid(),
 jsonb_build_object('tenant_id',NEW.tenant_id,'application_id',NEW.application_id)); RETURN NEW;
END $$;
CREATE TRIGGER branch_read_proof_evidence AFTER INSERT ON security.branch_read_admissions FOR EACH ROW EXECUTE FUNCTION security.audit_branch_read_proof();
GRANT USAGE ON SCHEMA audit TO ls_core_operation_emitter_dev;
GRANT INSERT(actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata) ON audit.events TO ls_core_operation_emitter_dev;
CREATE POLICY operation_read_proof_evidence ON audit.events FOR INSERT TO ls_core_operation_emitter_dev WITH CHECK(session_user=current_user
 AND pg_trigger_depth()=1 AND action_code='BRANCH_READ_PROOF_ISSUED' AND outcome='SUCCESS');
REVOKE ALL ON FUNCTION security.admit_branch_write_probe(),security.audit_branch_write_probe(),audit.guard_branch_admission_event(),security.audit_branch_read_proof() FROM PUBLIC;

-- Identified historical rejections use an independent transaction after the rejected request rolled back.
CREATE TABLE security.branch_history_rejections (
 request_id uuid PRIMARY KEY,actor_user_id uuid NOT NULL REFERENCES identity.users(id),tenant_id uuid NOT NULL,
 reason text NOT NULL CHECK(reason IN ('TENANT_STATE','AUTHORITY','MODULE_UNAVAILABLE')),created_at timestamptz NOT NULL
);
ALTER TABLE security.branch_history_rejections ENABLE ROW LEVEL SECURITY;
ALTER TABLE security.branch_history_rejections FORCE ROW LEVEL SECURITY;
CREATE FUNCTION security.record_branch_history_rejection() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP<>'INSERT' OR session_user<>current_user OR current_user<>'ls_core_branch_history_dev' THEN
 RAISE EXCEPTION 'Immutable rejection evidence' USING ERRCODE='42501'; END IF;
 NEW.created_at=clock_timestamp();
 INSERT INTO audit.events(actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
 VALUES('USER',NEW.actor_user_id,'BRANCH_HISTORY_REJECTED','TENANT',NEW.tenant_id,'DENIED',NEW.request_id,jsonb_build_object('reason',NEW.reason));
 RETURN NEW;
END $$;
CREATE TRIGGER history_rejection BEFORE INSERT OR UPDATE OR DELETE ON security.branch_history_rejections FOR EACH ROW EXECUTE FUNCTION security.record_branch_history_rejection();
CREATE TRIGGER history_rejection_no_truncate BEFORE TRUNCATE ON security.branch_history_rejections FOR EACH STATEMENT EXECUTE FUNCTION security.record_branch_history_rejection();
GRANT INSERT(request_id,actor_user_id,tenant_id,reason) ON security.branch_history_rejections TO ls_core_branch_history_dev;
CREATE POLICY history_rejection ON security.branch_history_rejections FOR INSERT TO ls_core_branch_history_dev WITH CHECK(session_user=current_user);
CREATE POLICY history_rejection_event ON audit.events FOR INSERT TO ls_core_branch_history_dev WITH CHECK(session_user=current_user
 AND pg_trigger_depth()=1 AND actor_kind='USER' AND actor_user_id IS NOT NULL AND action_code='BRANCH_HISTORY_REJECTED'
 AND resource_type='TENANT' AND outcome='DENIED' AND correlation_id IS NOT NULL AND metadata IN
 (jsonb_build_object('reason','TENANT_STATE'),jsonb_build_object('reason','AUTHORITY'),jsonb_build_object('reason','MODULE_UNAVAILABLE')));
REVOKE ALL ON FUNCTION security.record_branch_history_rejection() FROM PUBLIC;

-- F05 native insertion follows the same order as its Java coordinator and the protected reader.
CREATE OR REPLACE FUNCTION security.lifecycle_context_barrier() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 PERFORM pg_advisory_xact_lock_shared(370019,1);
 PERFORM pg_advisory_xact_lock_shared(410023,1);
 IF NOT EXISTS(SELECT FROM tenant.tenants WHERE id=NEW.tenant_id AND status='ACTIVE')
 OR NOT EXISTS(SELECT FROM tenant.branches WHERE tenant_id=NEW.tenant_id AND id=NEW.branch_id AND status='ACTIVE') THEN
 RAISE EXCEPTION 'Operational context unavailable' USING ERRCODE='42501'; END IF;
 RETURN NEW;
END $$;

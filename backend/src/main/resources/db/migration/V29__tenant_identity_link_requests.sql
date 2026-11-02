-- LFD-47. Consent is a holder decision, never an administrative identity adoption.
DO $$ BEGIN
 IF session_user<>'ls_core_migrator_dev' OR current_user<>session_user OR current_database()<>'ls_core_dev'
 OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999
 OR (SELECT count(*) FROM pg_roles r WHERE rolname IN ('ls_core_identity_links_dev','ls_core_identity_links_log_dev')
 AND rolcanlogin AND NOT(rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
 AND rolconnlimit=CASE WHEN rolname='ls_core_identity_links_dev' THEN 3 ELSE 2 END
 AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
 AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o')
 AND NOT has_database_privilege(r.oid,current_database(),'CREATE,TEMPORARY'))<>2
 THEN RAISE EXCEPTION 'Expected V28 and fresh restricted link channels'; END IF;
END $$;
DO $$ DECLARE principal record; relation record; BEGIN
 FOR principal IN SELECT oid FROM pg_roles WHERE rolname IN ('ls_core_identity_links_dev','ls_core_identity_links_log_dev') LOOP
 IF EXISTS(SELECT FROM pg_namespace WHERE has_schema_privilege(principal.oid,oid,'CREATE'))
 OR EXISTS(SELECT FROM pg_database WHERE datallowconn AND datname<>current_database() AND has_database_privilege(principal.oid,oid,'CONNECT'))
 OR NOT has_database_privilege(principal.oid,current_database(),'CONNECT') THEN
 RAISE EXCEPTION 'Incompatible link channel privileges; no repair'; END IF;
 FOR relation IN SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname !~ '^pg_' AND c.relkind IN ('r','p','v','m','f') LOOP
 IF has_table_privilege(principal.oid,relation.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
 OR has_any_column_privilege(principal.oid,relation.oid,'SELECT,INSERT,UPDATE,REFERENCES') THEN
 RAISE EXCEPTION 'Link channel has preexisting relation access'; END IF;
 END LOOP;
 END LOOP;
END $$;
CREATE TABLE tenant.identity_link_requests (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant.tenants(id),
 user_id uuid NOT NULL REFERENCES identity.users(id),
 requested_by uuid NOT NULL REFERENCES identity.users(id),
 status text NOT NULL CHECK(status IN ('PENDING','ACCEPTED','REJECTED','EXPIRED')),
 membership_id uuid,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL,
 decided_at timestamptz,
 UNIQUE(tenant_id,id,user_id),
 FOREIGN KEY(tenant_id,membership_id,user_id) REFERENCES tenant.memberships(tenant_id,id,user_id),
 CHECK((status='ACCEPTED')=(membership_id IS NOT NULL)),
 CHECK((status='PENDING')=(decided_at IS NULL)),
 CHECK(expires_at>created_at)
);
CREATE UNIQUE INDEX identity_link_pending_uq ON tenant.identity_link_requests(tenant_id,user_id) WHERE status='PENDING';
CREATE INDEX identity_link_inbox_idx ON tenant.identity_link_requests(user_id,created_at DESC,id);
-- REQUEST receipts deliberately contain no target, lookup outcome or link identifier.
CREATE TABLE tenant.identity_link_operations (
 request_id uuid PRIMARY KEY,
 actor_user_id uuid NOT NULL REFERENCES identity.users(id),
 tenant_id uuid NOT NULL REFERENCES tenant.tenants(id),
 action text NOT NULL CHECK(action IN ('REQUEST','ACCEPT','REJECT')),
 link_id uuid, user_id uuid, membership_id uuid,
 candidate_username text CHECK(candidate_username IS NULL),
 resulting_status text NOT NULL CHECK(resulting_status IN ('SUBMITTED','ACCEPTED','REJECTED','EXPIRED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(tenant_id,link_id,user_id) REFERENCES tenant.identity_link_requests(tenant_id,id,user_id),
 FOREIGN KEY(tenant_id,membership_id,user_id) REFERENCES tenant.memberships(tenant_id,id,user_id),
 CHECK((action='REQUEST' AND resulting_status='SUBMITTED' AND link_id IS NULL AND user_id IS NULL AND membership_id IS NULL)
 OR (action IN ('ACCEPT','REJECT') AND link_id IS NOT NULL AND user_id=actor_user_id
 AND ((resulting_status='ACCEPTED' AND membership_id IS NOT NULL) OR (resulting_status IN ('REJECTED','EXPIRED') AND membership_id IS NULL))))
);
CREATE INDEX identity_link_sent_idx ON tenant.identity_link_operations(tenant_id,created_at DESC,request_id);
CREATE FUNCTION tenant.apply_identity_link_operation() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE company_status text; candidate uuid; requester uuid; linked tenant.identity_link_requests%ROWTYPE;
BEGIN
 IF session_user<>'ls_core_identity_links_dev' OR current_user<>session_user OR TG_OP<>'INSERT'
 OR current_setting('transaction_isolation')<>'read committed' THEN RAISE EXCEPTION 'Link channel unavailable' USING ERRCODE='42501'; END IF;
 PERFORM pg_advisory_xact_lock(370019,1);
 PERFORM pg_advisory_xact_lock(410023,1);
 SELECT status INTO company_status FROM tenant.tenants WHERE id=NEW.tenant_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Resource unavailable' USING ERRCODE='P2904'; END IF;
 IF NEW.action='REQUEST' THEN
 IF NEW.candidate_username IS NULL OR char_length(NEW.candidate_username) NOT BETWEEN 1 AND 200
 OR btrim(NEW.candidate_username)='' OR NEW.candidate_username ~ '[[:cntrl:]]' THEN RAISE EXCEPTION 'Invalid input' USING ERRCODE='22023'; END IF;
 SELECT id INTO candidate FROM identity.users WHERE lower(btrim(username))=lower(btrim(NEW.candidate_username));
 requester=NEW.actor_user_id;
 ELSE
 SELECT * INTO linked FROM tenant.identity_link_requests WHERE id=NEW.link_id AND tenant_id=NEW.tenant_id AND user_id=NEW.actor_user_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'Resource unavailable' USING ERRCODE='P2904'; END IF;
 candidate=linked.user_id; requester=linked.requested_by;
 END IF;
 -- F06 -> V23 -> tenant -> identities -> credentials -> memberships -> designations -> link.
 PERFORM id FROM identity.users WHERE id IN (NEW.actor_user_id,candidate,requester) ORDER BY id FOR SHARE;
 PERFORM user_id FROM identity.user_credentials WHERE user_id IN (NEW.actor_user_id,candidate,requester) ORDER BY user_id FOR SHARE;
 PERFORM id FROM tenant.memberships WHERE tenant_id=NEW.tenant_id ORDER BY id FOR UPDATE;
 PERFORM id FROM tenant.administrators WHERE tenant_id=NEW.tenant_id ORDER BY id FOR SHARE;
 IF NOT tenant.administrator_ready(NEW.actor_user_id) THEN RAISE EXCEPTION 'Authority unavailable' USING ERRCODE='42501'; END IF;
 IF company_status<>'ACTIVE' THEN RAISE EXCEPTION 'Tenant unavailable' USING ERRCODE='P2902'; END IF;
 IF EXISTS(SELECT FROM tenant.identity_link_operations WHERE request_id=NEW.request_id) THEN RAISE EXCEPTION 'Operation already submitted' USING ERRCODE='23505'; END IF;
 IF NEW.action='REQUEST' THEN
 IF NOT tenant.user_authority(requester,NEW.tenant_id,true) THEN RAISE EXCEPTION 'Authority unavailable' USING ERRCODE='42501'; END IF;
 -- Expiration never renews consent. Its evidence is emitted by the transition trigger.
 UPDATE tenant.identity_link_requests SET status='EXPIRED',decided_at=clock_timestamp()
 WHERE tenant_id=NEW.tenant_id AND user_id=candidate AND status='PENDING' AND expires_at<=clock_timestamp();
 IF candidate IS NOT NULL AND NOT EXISTS(SELECT FROM tenant.memberships WHERE tenant_id=NEW.tenant_id AND user_id=candidate)
 AND NOT EXISTS(SELECT FROM tenant.identity_link_requests WHERE tenant_id=NEW.tenant_id AND user_id=candidate AND status='PENDING') THEN
 INSERT INTO tenant.identity_link_requests(tenant_id,user_id,requested_by,status,expires_at)
 VALUES(NEW.tenant_id,candidate,requester,'PENDING',clock_timestamp()+interval '7 days');
 END IF;
 NEW.link_id=NULL; NEW.user_id=NULL; NEW.membership_id=NULL; NEW.resulting_status='SUBMITTED';
 ELSE
 SELECT * INTO linked FROM tenant.identity_link_requests WHERE id=NEW.link_id FOR UPDATE;
 IF linked.status<>'PENDING' THEN RAISE EXCEPTION 'Decision conflict' USING ERRCODE='P2901'; END IF;
 IF NOT tenant.user_authority(requester,NEW.tenant_id,true) THEN RAISE EXCEPTION 'Authority unavailable' USING ERRCODE='42501'; END IF;
 NEW.user_id=candidate; NEW.membership_id=NULL;
 IF linked.expires_at<=clock_timestamp() THEN NEW.resulting_status='EXPIRED';
 ELSIF NEW.action='REJECT' THEN NEW.resulting_status='REJECTED';
 ELSE
 IF EXISTS(SELECT FROM tenant.memberships WHERE tenant_id=NEW.tenant_id AND user_id=candidate) THEN
 RAISE EXCEPTION 'Membership requires separate administration' USING ERRCODE='P2901'; END IF;
 NEW.membership_id=gen_random_uuid(); NEW.resulting_status='ACCEPTED';
 INSERT INTO tenant.memberships(id,tenant_id,user_id,status,invited_by_user_id,accepted_at)
 VALUES(NEW.membership_id,NEW.tenant_id,candidate,'ACTIVE',requester,clock_timestamp());
 END IF;
 UPDATE tenant.identity_link_requests SET status=NEW.resulting_status,membership_id=NEW.membership_id,decided_at=clock_timestamp() WHERE id=NEW.link_id;
 END IF;
 NEW.candidate_username=NULL; NEW.created_at=clock_timestamp(); RETURN NEW;
END $$;
CREATE TRIGGER identity_link_command BEFORE INSERT OR UPDATE OR DELETE ON tenant.identity_link_operations FOR EACH ROW EXECUTE FUNCTION tenant.apply_identity_link_operation();
CREATE FUNCTION tenant.guard_identity_link_child() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF current_user='ls_core_identity_links_dev' AND (session_user<>current_user OR pg_trigger_depth()<>2) THEN
 RAISE EXCEPTION 'Atomic link command required' USING ERRCODE='42501'; END IF;
 IF TG_TABLE_NAME='identity_link_requests' THEN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Immutable consent' USING ERRCODE='42501'; END IF;
 IF TG_OP='UPDATE' AND (OLD.status<>'PENDING' OR NEW.status='PENDING'
 OR (NEW.id,NEW.tenant_id,NEW.user_id,NEW.requested_by,NEW.created_at,NEW.expires_at)
 IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.user_id,OLD.requested_by,OLD.created_at,OLD.expires_at)) THEN
 RAISE EXCEPTION 'Immutable consent' USING ERRCODE='42501'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER identity_link_child BEFORE INSERT OR UPDATE OR DELETE ON tenant.identity_link_requests FOR EACH ROW EXECUTE FUNCTION tenant.guard_identity_link_child();
CREATE TRIGGER identity_link_member BEFORE INSERT OR UPDATE ON tenant.memberships FOR EACH ROW EXECUTE FUNCTION tenant.guard_identity_link_child();
CREATE FUNCTION tenant.complete_identity_link_operation() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 INSERT INTO audit.events(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
 VALUES(clock_timestamp(),'USER',NEW.actor_user_id,'IDENTITY_LINK_'||NEW.action,'TENANT',NEW.tenant_id,'SUCCESS',NEW.request_id,
 jsonb_build_object('link_id',NEW.link_id,'membership_id',NEW.membership_id,'status',NEW.resulting_status));
 RETURN NEW;
END $$;
CREATE TRIGGER identity_link_complete AFTER INSERT ON tenant.identity_link_operations FOR EACH ROW EXECUTE FUNCTION tenant.complete_identity_link_operation();
CREATE FUNCTION audit.guard_identity_link_event() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP IN ('UPDATE','DELETE') THEN
 IF OLD.action_code IN ('IDENTITY_LINK_REQUEST','IDENTITY_LINK_ACCEPT','IDENTITY_LINK_REJECT','IDENTITY_LINK_EXPIRED') THEN RAISE EXCEPTION 'Immutable evidence' USING ERRCODE='42501'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 END IF;
 IF NEW.action_code='IDENTITY_LINK_EXPIRED' THEN
 IF TG_OP<>'INSERT' OR current_user<>'ls_core_identity_links_dev' OR session_user<>current_user
 OR pg_trigger_depth()<>3 OR NEW.actor_kind<>'SYSTEM' OR NEW.actor_user_id IS NOT NULL
 OR NEW.outcome<>'SUCCESS' OR NEW.resource_type<>'TENANT' OR NEW.metadata<>'{}'::jsonb
 OR NEW.tenant_id IS NOT NULL OR NEW.application_id IS NOT NULL OR NEW.session_id IS NOT NULL OR NEW.actor_api_key_id IS NOT NULL
 OR NOT EXISTS(SELECT FROM tenant.identity_link_requests WHERE id=NEW.correlation_id AND tenant_id=NEW.resource_id AND status='EXPIRED')
 THEN RAISE EXCEPTION 'Invalid expiration evidence' USING ERRCODE='42501'; END IF;
 NEW.occurred_at=clock_timestamp(); NEW.created_at=NEW.occurred_at; RETURN NEW;
 END IF;
 IF NEW.action_code IN ('IDENTITY_LINK_REQUEST','IDENTITY_LINK_ACCEPT','IDENTITY_LINK_REJECT') OR current_user IN ('ls_core_identity_links_dev','ls_core_identity_links_log_dev') THEN
 IF TG_OP<>'INSERT' OR session_user<>current_user OR NEW.actor_kind<>'USER' OR NEW.actor_user_id IS NULL
 OR NEW.resource_type IS DISTINCT FROM 'TENANT' OR NEW.correlation_id IS NULL OR NEW.tenant_id IS NOT NULL
 OR NEW.application_id IS NOT NULL OR NEW.session_id IS NOT NULL OR NEW.actor_api_key_id IS NOT NULL THEN RAISE EXCEPTION 'Invalid evidence' USING ERRCODE='42501'; END IF;
 IF current_user='ls_core_identity_links_dev' THEN
 IF pg_trigger_depth()<>2 OR NEW.outcome<>'SUCCESS' OR NOT EXISTS(SELECT FROM tenant.identity_link_operations o
 WHERE o.request_id=NEW.correlation_id AND o.actor_user_id=NEW.actor_user_id AND o.tenant_id=NEW.resource_id
 AND NEW.action_code='IDENTITY_LINK_'||o.action AND NEW.metadata=jsonb_build_object('link_id',o.link_id,'membership_id',o.membership_id,'status',o.resulting_status))
 THEN RAISE EXCEPTION 'Atomic evidence required' USING ERRCODE='42501'; END IF;
 ELSIF current_user='ls_core_identity_links_log_dev' THEN
 IF NEW.action_code NOT IN ('IDENTITY_LINK_REQUEST','IDENTITY_LINK_ACCEPT','IDENTITY_LINK_REJECT') OR NEW.outcome NOT IN ('DENIED','UNKNOWN') OR NEW.metadata<>'{}'::jsonb THEN RAISE EXCEPTION 'Invalid rejection' USING ERRCODE='42501'; END IF;
 ELSE RAISE EXCEPTION 'Evidence unavailable' USING ERRCODE='42501'; END IF;
 NEW.occurred_at=clock_timestamp(); NEW.created_at=NEW.occurred_at;
 END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION tenant.audit_identity_link_expiration() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.status='EXPIRED' AND OLD.status='PENDING' THEN
 INSERT INTO audit.events(occurred_at,actor_kind,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
 VALUES(clock_timestamp(),'SYSTEM','IDENTITY_LINK_EXPIRED','TENANT',NEW.tenant_id,'SUCCESS',NEW.id,'{}'::jsonb);
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER identity_link_expiration AFTER UPDATE ON tenant.identity_link_requests FOR EACH ROW EXECUTE FUNCTION tenant.audit_identity_link_expiration();
CREATE TRIGGER identity_link_event BEFORE INSERT OR UPDATE OR DELETE ON audit.events FOR EACH ROW EXECUTE FUNCTION audit.guard_identity_link_event();
GRANT USAGE ON SCHEMA identity,tenant,platform,audit TO ls_core_identity_links_dev;
GRANT USAGE ON SCHEMA audit TO ls_core_identity_links_log_dev;
GRANT SELECT(id,username,status) ON identity.users TO ls_core_identity_links_dev;
GRANT SELECT(user_id,must_change_password,password_changed_at,password_expires_at,locked_until) ON identity.user_credentials TO ls_core_identity_links_dev;
GRANT SELECT(id,name,status) ON tenant.tenants TO ls_core_identity_links_dev;
GRANT SELECT(id,tenant_id,user_id,status) ON tenant.memberships TO ls_core_identity_links_dev;
GRANT SELECT(id,tenant_id,membership_id,status,revoked_at) ON tenant.administrators TO ls_core_identity_links_dev;
GRANT SELECT(id,user_id,administrator_kind,status,revoked_at) ON platform.administrators TO ls_core_identity_links_dev;
GRANT SELECT ON platform.tenant_user_grants,tenant.identity_link_requests,tenant.identity_link_operations TO ls_core_identity_links_dev;
GRANT UPDATE(status) ON tenant.tenants TO ls_core_identity_links_dev;
GRANT UPDATE(id) ON identity.users,tenant.administrators TO ls_core_identity_links_dev;
GRANT UPDATE(user_id) ON identity.user_credentials TO ls_core_identity_links_dev;
GRANT UPDATE(id) ON tenant.memberships TO ls_core_identity_links_dev;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['identity.users','identity.user_credentials','tenant.tenants','tenant.memberships','tenant.administrators','platform.administrators','platform.tenant_user_grants','tenant.identity_link_requests','tenant.identity_link_operations'] LOOP
 EXECUTE format('CREATE POLICY identity_link_read ON %s FOR SELECT TO ls_core_identity_links_dev USING(session_user=current_user)',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['identity.users','identity.user_credentials','tenant.tenants','tenant.memberships','tenant.administrators'] LOOP
 EXECUTE format('CREATE POLICY identity_link_lock ON %s FOR UPDATE TO ls_core_identity_links_dev USING(session_user=current_user) WITH CHECK(false)',tab);
 END LOOP;
END $$;
ALTER TABLE tenant.identity_link_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant.identity_link_requests FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant.identity_link_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant.identity_link_operations FORCE ROW LEVEL SECURITY;
GRANT INSERT(request_id,actor_user_id,tenant_id,action,link_id,candidate_username,resulting_status) ON tenant.identity_link_operations TO ls_core_identity_links_dev;
CREATE POLICY identity_link_command ON tenant.identity_link_operations FOR INSERT TO ls_core_identity_links_dev WITH CHECK(session_user=current_user);
GRANT INSERT(tenant_id,user_id,requested_by,status,expires_at),UPDATE(status,membership_id,decided_at) ON tenant.identity_link_requests TO ls_core_identity_links_dev;
CREATE POLICY identity_link_request_insert ON tenant.identity_link_requests FOR INSERT TO ls_core_identity_links_dev WITH CHECK(session_user=current_user AND pg_trigger_depth()=1 AND status='PENDING');
CREATE POLICY identity_link_request_update ON tenant.identity_link_requests FOR UPDATE TO ls_core_identity_links_dev USING(session_user=current_user) WITH CHECK(pg_trigger_depth()=1);
GRANT INSERT(id,tenant_id,user_id,status,invited_by_user_id,accepted_at) ON tenant.memberships TO ls_core_identity_links_dev;
CREATE POLICY identity_link_membership ON tenant.memberships FOR INSERT TO ls_core_identity_links_dev WITH CHECK(session_user=current_user AND pg_trigger_depth()=1 AND status='ACTIVE' AND accepted_at IS NOT NULL);
GRANT INSERT(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata) ON audit.events TO ls_core_identity_links_dev,ls_core_identity_links_log_dev;
CREATE POLICY identity_link_success ON audit.events FOR INSERT TO ls_core_identity_links_dev WITH CHECK(session_user=current_user AND outcome='SUCCESS' AND action_code IN ('IDENTITY_LINK_REQUEST','IDENTITY_LINK_ACCEPT','IDENTITY_LINK_REJECT','IDENTITY_LINK_EXPIRED'));
CREATE POLICY identity_link_rejected ON audit.events FOR INSERT TO ls_core_identity_links_log_dev WITH CHECK(session_user=current_user AND outcome IN ('DENIED','UNKNOWN') AND action_code IN ('IDENTITY_LINK_REQUEST','IDENTITY_LINK_ACCEPT','IDENTITY_LINK_REJECT'));
DO $$ DECLARE f text; BEGIN
 FOREACH f IN ARRAY ARRAY['tenant.apply_identity_link_operation()','tenant.guard_identity_link_child()','tenant.complete_identity_link_operation()','tenant.audit_identity_link_expiration()','audit.guard_identity_link_event()'] LOOP
 EXECUTE 'REVOKE ALL ON FUNCTION '||f||' FROM PUBLIC';
 END LOOP;
END $$;
GRANT EXECUTE ON FUNCTION tenant.user_authority(uuid,uuid,boolean),tenant.administrator_ready(uuid),tenant.valid_administrators(uuid) TO ls_core_identity_links_dev;

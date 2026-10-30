-- LFD-46. Atomic new identity + F04 temporary credential + PENDING membership.
DO $$ BEGIN
 IF session_user<>'ls_core_migrator_dev' OR current_user<>session_user OR current_database()<>'ls_core_dev'
 OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999
 OR (SELECT count(*) FROM pg_roles r WHERE rolname IN ('ls_core_tenant_users_dev','ls_core_tenant_users_log_dev')
 AND rolcanlogin AND NOT(rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
 AND rolconnlimit=CASE WHEN rolname='ls_core_tenant_users_dev' THEN 3 ELSE 2 END
 AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
 AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o')
 AND NOT has_database_privilege(r.oid,current_database(),'CREATE,TEMPORARY'))<>2
 THEN RAISE EXCEPTION 'Expected V27 and fresh restricted user channels'; END IF;
END $$;
DO $$ DECLARE principal record; relation record; BEGIN
 FOR principal IN SELECT oid FROM pg_roles WHERE rolname IN ('ls_core_tenant_users_dev','ls_core_tenant_users_log_dev') LOOP
 IF EXISTS(SELECT FROM pg_namespace WHERE has_schema_privilege(principal.oid,oid,'CREATE'))
 OR EXISTS(SELECT FROM pg_database WHERE datallowconn AND datname<>current_database() AND has_database_privilege(principal.oid,oid,'CONNECT'))
 OR NOT has_database_privilege(principal.oid,current_database(),'CONNECT') THEN
 RAISE EXCEPTION 'Incompatible user channel privileges; no repair'; END IF;
 FOR relation IN SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname !~ '^pg_' AND c.relkind IN ('r','p','v','m','f') LOOP
 IF has_table_privilege(principal.oid,relation.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
 OR has_any_column_privilege(principal.oid,relation.oid,'SELECT,INSERT,UPDATE,REFERENCES') THEN
 RAISE EXCEPTION 'User channel has preexisting relation access; no repair'; END IF;
 END LOOP;
 END LOOP;
END $$;
CREATE TABLE platform.tenant_user_grants (
 designation_id uuid REFERENCES platform.administrators(id), capability text CHECK(capability IN ('READ','MANAGE')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(designation_id,capability)
);
CREATE TABLE tenant.user_operations (
 request_id uuid PRIMARY KEY, actor_user_id uuid NOT NULL REFERENCES identity.users(id),
 tenant_id uuid NOT NULL REFERENCES tenant.tenants(id), action text NOT NULL CHECK(action IN ('CREATE','REISSUE','ACTIVATE')),
 user_id uuid NOT NULL REFERENCES identity.users(id) DEFERRABLE INITIALLY DEFERRED,
 membership_id uuid NOT NULL, username text, display_name text, email text,
 -- Transport-only hash: cleared by BEFORE trigger; never persisted in the receipt or returned to HTTP.
 temporary_hash text CHECK(temporary_hash IS NULL), temporary_expires_at timestamptz,
 resulting_status text NOT NULL CHECK(resulting_status IN ('PENDING','ACTIVE')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(tenant_id,membership_id,user_id) REFERENCES tenant.memberships(tenant_id,id,user_id) DEFERRABLE INITIALLY DEFERRED,
 CHECK(username IS NULL AND display_name IS NULL AND email IS NULL)
);
CREATE UNIQUE INDEX user_operations_new_identity_uq ON tenant.user_operations(user_id) WHERE action='CREATE';
CREATE UNIQUE INDEX user_operations_new_membership_uq ON tenant.user_operations(membership_id) WHERE action='CREATE';
CREATE INDEX user_operations_history_idx ON tenant.user_operations(tenant_id,membership_id,created_at DESC,request_id DESC);
CREATE FUNCTION tenant.user_authority(actor uuid,company uuid,mutating boolean) RETURNS boolean
 LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
 SELECT tenant.administrator_ready(actor) AND (EXISTS(SELECT FROM platform.administrators a
 WHERE a.user_id=actor AND a.status='ACTIVE' AND a.revoked_at IS NULL
 AND (a.administrator_kind='OWNER' OR (a.administrator_kind='SUPERADMIN'
 AND EXISTS(SELECT FROM platform.tenant_user_grants g WHERE g.designation_id=a.id AND g.capability='READ')
 AND (NOT mutating OR EXISTS(SELECT FROM platform.tenant_user_grants g WHERE g.designation_id=a.id AND g.capability='MANAGE')))))
 OR (EXISTS(SELECT FROM tenant.tenants WHERE id=company AND status='ACTIVE')
 AND EXISTS(SELECT FROM tenant.valid_administrators(company) a WHERE a.user_id=actor)))
$$;
CREATE FUNCTION tenant.apply_user_operation() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE company_status text; target_status text; member_status text;
BEGIN
 IF session_user<>'ls_core_tenant_users_dev' OR current_user<>session_user OR TG_OP<>'INSERT'
 OR current_setting('transaction_isolation')<>'read committed' THEN RAISE EXCEPTION 'User channel unavailable' USING ERRCODE='42501'; END IF;
 PERFORM pg_advisory_xact_lock(370019,1);
 PERFORM pg_advisory_xact_lock(410023,1);
 SELECT status INTO company_status FROM tenant.tenants WHERE id=NEW.tenant_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Resource unavailable' USING ERRCODE='P2804'; END IF;
 -- F06/F07 order: tenant, identities, credentials, memberships, designations.
 PERFORM id FROM identity.users WHERE id IN (NEW.actor_user_id,NEW.user_id) ORDER BY id FOR SHARE;
 PERFORM user_id FROM identity.user_credentials WHERE user_id IN (NEW.actor_user_id,NEW.user_id) ORDER BY user_id FOR UPDATE;
 PERFORM id FROM tenant.memberships WHERE tenant_id=NEW.tenant_id ORDER BY id FOR UPDATE;
 PERFORM id FROM tenant.administrators WHERE tenant_id=NEW.tenant_id ORDER BY id FOR SHARE;
 IF NOT tenant.user_authority(NEW.actor_user_id,NEW.tenant_id,true) THEN RAISE EXCEPTION 'Authority unavailable' USING ERRCODE='42501'; END IF;
 IF company_status<>'ACTIVE' THEN RAISE EXCEPTION 'Activate tenant first' USING ERRCODE='P2802'; END IF;
 IF EXISTS(SELECT FROM tenant.user_operations WHERE request_id=NEW.request_id) THEN RAISE EXCEPTION 'Operation already submitted' USING ERRCODE='23505'; END IF;
 IF NEW.action='CREATE' THEN
 IF NEW.username IS NULL OR char_length(NEW.username) NOT BETWEEN 1 AND 200 OR btrim(NEW.username)='' OR NEW.username ~ '[[:cntrl:]]'
 OR NEW.display_name IS NULL OR char_length(NEW.display_name) NOT BETWEEN 1 AND 200 OR btrim(NEW.display_name)='' OR NEW.display_name ~ '[[:cntrl:]]'
 OR (NEW.email IS NOT NULL AND (char_length(NEW.email)>320 OR btrim(NEW.email)='' OR NEW.email ~ '[[:cntrl:]]'))
 THEN RAISE EXCEPTION 'Invalid input' USING ERRCODE='22023'; END IF;
 -- No global lookup, adoption, upsert or membership of a preexisting identity. V7 arbitrates uniqueness.
 NEW.user_id=gen_random_uuid(); NEW.membership_id=gen_random_uuid();
 INSERT INTO identity.users(id,username,display_name,email) VALUES(NEW.user_id,NEW.username,NEW.display_name,NEW.email);
 ELSIF NEW.action IN ('REISSUE','ACTIVATE') THEN
 SELECT u.status,m.status INTO target_status,member_status FROM tenant.memberships m JOIN identity.users u ON u.id=m.user_id
 WHERE m.tenant_id=NEW.tenant_id AND m.id=NEW.membership_id AND m.user_id=NEW.user_id
 AND EXISTS(SELECT FROM tenant.user_operations o WHERE o.action='CREATE' AND o.tenant_id=m.tenant_id AND o.membership_id=m.id AND o.user_id=m.user_id);
 IF NOT FOUND THEN RAISE EXCEPTION 'Resource unavailable' USING ERRCODE='P2804'; END IF;
 IF member_status<>'PENDING' THEN RAISE EXCEPTION 'Incorporation conflict' USING ERRCODE='P2801'; END IF;
 END IF;
 IF NEW.action IN ('CREATE','REISSUE') THEN
 IF NEW.temporary_hash IS NULL OR NEW.temporary_hash !~ '^\{pbkdf2-sha256-600000\}[0-9a-f]{96}$'
 THEN RAISE EXCEPTION 'Credential unavailable' USING ERRCODE='P2802'; END IF;
 NEW.temporary_expires_at=clock_timestamp()+interval '72 hours';
 IF NEW.action='CREATE' THEN
 INSERT INTO identity.user_credentials(user_id,password_hash,must_change_password,password_changed_at,password_expires_at,failed_attempts,locked_until)
 VALUES(NEW.user_id,NEW.temporary_hash,true,clock_timestamp(),NEW.temporary_expires_at,0,NULL);
 INSERT INTO tenant.memberships(id,tenant_id,user_id,status,invited_by_user_id)
 VALUES(NEW.membership_id,NEW.tenant_id,NEW.user_id,'PENDING',NEW.actor_user_id);
 ELSE
 IF target_status<>'PENDING' OR NOT EXISTS(SELECT FROM identity.user_credentials WHERE user_id=NEW.user_id AND must_change_password
 AND (locked_until IS NULL OR locked_until<=clock_timestamp())) THEN RAISE EXCEPTION 'Use F04 recovery after exchange' USING ERRCODE='P2802'; END IF;
 UPDATE identity.user_credentials SET password_hash=NEW.temporary_hash,password_changed_at=clock_timestamp(),
 password_expires_at=NEW.temporary_expires_at,updated_at=clock_timestamp() WHERE user_id=NEW.user_id;
 END IF;
 NEW.resulting_status='PENDING';
 ELSE
 IF NEW.temporary_hash IS NOT NULL OR NOT tenant.administrator_ready(NEW.user_id) THEN RAISE EXCEPTION 'Complete F04 first' USING ERRCODE='P2802'; END IF;
 UPDATE tenant.memberships SET status='ACTIVE',accepted_at=clock_timestamp(),updated_at=clock_timestamp() WHERE tenant_id=NEW.tenant_id AND id=NEW.membership_id;
 NEW.resulting_status='ACTIVE'; NEW.temporary_expires_at=NULL;
 END IF;
 NEW.temporary_hash=NULL; NEW.username=NULL; NEW.display_name=NULL; NEW.email=NULL;
 NEW.created_at=clock_timestamp(); RETURN NEW;
END $$;
CREATE TRIGGER user_operation_guard BEFORE INSERT OR UPDATE OR DELETE ON tenant.user_operations FOR EACH ROW EXECUTE FUNCTION tenant.apply_user_operation();
CREATE FUNCTION tenant.complete_user_operation() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 INSERT INTO audit.events(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
 VALUES(clock_timestamp(),'USER',NEW.actor_user_id,'TENANT_USER_'||NEW.action,'TENANT',NEW.tenant_id,'SUCCESS',NEW.request_id,
 jsonb_build_object('user_id',NEW.user_id,'membership_id',NEW.membership_id,'status',NEW.resulting_status));
 RETURN NEW;
END $$;
CREATE TRIGGER user_operation_complete AFTER INSERT ON tenant.user_operations FOR EACH ROW EXECUTE FUNCTION tenant.complete_user_operation();
CREATE FUNCTION tenant.guard_user_child() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF current_user='ls_core_tenant_users_dev' AND (session_user<>current_user OR pg_trigger_depth()<>2)
 THEN RAISE EXCEPTION 'Atomic user command required' USING ERRCODE='42501'; END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION audit.guard_tenant_user_event() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP IN ('UPDATE','DELETE') THEN
 IF OLD.action_code IN ('TENANT_USER_CREATE','TENANT_USER_REISSUE','TENANT_USER_ACTIVATE') THEN RAISE EXCEPTION 'Immutable evidence' USING ERRCODE='42501'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 END IF;
 IF NEW.action_code IN ('TENANT_USER_CREATE','TENANT_USER_REISSUE','TENANT_USER_ACTIVATE') OR current_user IN ('ls_core_tenant_users_dev','ls_core_tenant_users_log_dev') THEN
 IF TG_OP<>'INSERT' OR session_user<>current_user OR NEW.actor_kind<>'USER' OR NEW.actor_user_id IS NULL
 OR NEW.resource_type IS DISTINCT FROM 'TENANT' OR NEW.correlation_id IS NULL OR NEW.tenant_id IS NOT NULL
 OR NEW.application_id IS NOT NULL OR NEW.session_id IS NOT NULL OR NEW.actor_api_key_id IS NOT NULL THEN RAISE EXCEPTION 'Invalid evidence' USING ERRCODE='42501'; END IF;
 IF current_user='ls_core_tenant_users_dev' THEN
 IF pg_trigger_depth()<>2 OR NEW.outcome<>'SUCCESS' OR NOT EXISTS(SELECT FROM tenant.user_operations o
 WHERE o.request_id=NEW.correlation_id AND o.actor_user_id=NEW.actor_user_id AND o.tenant_id=NEW.resource_id
 AND NEW.action_code='TENANT_USER_'||o.action AND NEW.metadata=jsonb_build_object('user_id',o.user_id,'membership_id',o.membership_id,'status',o.resulting_status))
 THEN RAISE EXCEPTION 'Atomic evidence required' USING ERRCODE='42501'; END IF;
 ELSIF current_user='ls_core_tenant_users_log_dev' THEN
 IF NEW.action_code NOT IN ('TENANT_USER_CREATE','TENANT_USER_REISSUE','TENANT_USER_ACTIVATE') OR NEW.outcome NOT IN ('DENIED','UNKNOWN') OR NEW.metadata<>'{}'::jsonb
 THEN RAISE EXCEPTION 'Invalid rejection' USING ERRCODE='42501'; END IF;
 ELSE RAISE EXCEPTION 'Evidence unavailable' USING ERRCODE='42501'; END IF;
 NEW.occurred_at=clock_timestamp(); NEW.created_at=NEW.occurred_at;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER tenant_user_event BEFORE INSERT OR UPDATE OR DELETE ON audit.events FOR EACH ROW EXECUTE FUNCTION audit.guard_tenant_user_event();
GRANT USAGE ON SCHEMA identity,tenant,platform,audit TO ls_core_tenant_users_dev;
GRANT USAGE ON SCHEMA audit TO ls_core_tenant_users_log_dev;
GRANT SELECT(id,username,display_name,email,email_verified_at,status) ON identity.users TO ls_core_tenant_users_dev;
GRANT SELECT(user_id,must_change_password,password_changed_at,password_expires_at,locked_until) ON identity.user_credentials TO ls_core_tenant_users_dev;
GRANT SELECT(id,name,status) ON tenant.tenants TO ls_core_tenant_users_dev;
GRANT SELECT(id,tenant_id,user_id,status,invited_by_user_id,accepted_at,created_at,updated_at) ON tenant.memberships TO ls_core_tenant_users_dev;
GRANT SELECT(id,tenant_id,membership_id,status,revoked_at) ON tenant.administrators TO ls_core_tenant_users_dev;
GRANT SELECT(id,user_id,administrator_kind,status,revoked_at) ON platform.administrators TO ls_core_tenant_users_dev;
GRANT SELECT ON platform.tenant_user_grants,tenant.user_operations TO ls_core_tenant_users_dev;
GRANT UPDATE(status) ON tenant.tenants TO ls_core_tenant_users_dev;
GRANT UPDATE(id) ON identity.users TO ls_core_tenant_users_dev;
GRANT UPDATE(id) ON tenant.administrators TO ls_core_tenant_users_dev;
CREATE POLICY user_tenant_lock ON tenant.tenants FOR UPDATE TO ls_core_tenant_users_dev USING(session_user=current_user) WITH CHECK(false);
CREATE POLICY user_identity_lock ON identity.users FOR UPDATE TO ls_core_tenant_users_dev USING(session_user=current_user) WITH CHECK(false);
CREATE POLICY user_admin_lock ON tenant.administrators FOR UPDATE TO ls_core_tenant_users_dev USING(session_user=current_user) WITH CHECK(false);
ALTER TABLE platform.tenant_user_grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.tenant_user_grants FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant.user_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant.user_operations FORCE ROW LEVEL SECURITY;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['identity.users','identity.user_credentials','tenant.tenants','tenant.memberships','tenant.administrators','platform.administrators','platform.tenant_user_grants','tenant.user_operations'] LOOP
 EXECUTE format('CREATE POLICY tenant_user_read ON %s FOR SELECT TO ls_core_tenant_users_dev USING(session_user=current_user)',tab);
 END LOOP;
END $$;
GRANT INSERT(request_id,actor_user_id,tenant_id,action,user_id,membership_id,username,display_name,email,temporary_hash,resulting_status) ON tenant.user_operations TO ls_core_tenant_users_dev;
CREATE POLICY tenant_user_command ON tenant.user_operations FOR INSERT TO ls_core_tenant_users_dev WITH CHECK(session_user=current_user);
GRANT INSERT(id,username,display_name,email) ON identity.users TO ls_core_tenant_users_dev;
CREATE POLICY tenant_user_identity_insert ON identity.users FOR INSERT TO ls_core_tenant_users_dev WITH CHECK(session_user=current_user AND pg_trigger_depth()=1 AND status='PENDING' AND email_verified_at IS NULL);
GRANT INSERT(user_id,password_hash,must_change_password,password_changed_at,password_expires_at,failed_attempts,locked_until),
 UPDATE(password_hash,password_changed_at,password_expires_at,updated_at) ON identity.user_credentials TO ls_core_tenant_users_dev;
CREATE POLICY tenant_user_credential_insert ON identity.user_credentials FOR INSERT TO ls_core_tenant_users_dev WITH CHECK(session_user=current_user AND pg_trigger_depth()=1 AND must_change_password AND failed_attempts=0 AND locked_until IS NULL AND password_expires_at<=clock_timestamp()+interval '72 hours');
CREATE POLICY tenant_user_credential_update ON identity.user_credentials FOR UPDATE TO ls_core_tenant_users_dev USING(session_user=current_user) WITH CHECK(pg_trigger_depth()=1 AND must_change_password AND password_expires_at<=clock_timestamp()+interval '72 hours');
GRANT INSERT(id,tenant_id,user_id,status,invited_by_user_id),UPDATE(status,accepted_at,updated_at) ON tenant.memberships TO ls_core_tenant_users_dev;
CREATE POLICY tenant_user_member_insert ON tenant.memberships FOR INSERT TO ls_core_tenant_users_dev WITH CHECK(session_user=current_user AND pg_trigger_depth()=1 AND status='PENDING');
CREATE POLICY tenant_user_member_update ON tenant.memberships FOR UPDATE TO ls_core_tenant_users_dev USING(session_user=current_user) WITH CHECK(pg_trigger_depth()=1 AND status='ACTIVE');
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['identity.users','identity.user_credentials','tenant.memberships'] LOOP
 EXECUTE format('CREATE TRIGGER tenant_user_child BEFORE INSERT OR UPDATE ON %s FOR EACH ROW EXECUTE FUNCTION tenant.guard_user_child()',tab);
 END LOOP;
END $$;
GRANT INSERT(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata) ON audit.events TO ls_core_tenant_users_dev,ls_core_tenant_users_log_dev;
CREATE POLICY tenant_user_success ON audit.events FOR INSERT TO ls_core_tenant_users_dev WITH CHECK(session_user=current_user AND outcome='SUCCESS' AND action_code IN ('TENANT_USER_CREATE','TENANT_USER_REISSUE','TENANT_USER_ACTIVATE'));
CREATE POLICY tenant_user_rejected ON audit.events FOR INSERT TO ls_core_tenant_users_log_dev WITH CHECK(session_user=current_user AND outcome IN ('DENIED','UNKNOWN') AND action_code IN ('TENANT_USER_CREATE','TENANT_USER_REISSUE','TENANT_USER_ACTIVATE'));
DO $$ DECLARE f text; BEGIN
 FOREACH f IN ARRAY ARRAY['tenant.user_authority(uuid,uuid,boolean)','tenant.apply_user_operation()','tenant.complete_user_operation()','tenant.guard_user_child()','audit.guard_tenant_user_event()'] LOOP
 EXECUTE 'REVOKE ALL ON FUNCTION '||f||' FROM PUBLIC';
 END LOOP;
END $$;
GRANT EXECUTE ON FUNCTION tenant.user_authority(uuid,uuid,boolean),tenant.administrator_ready(uuid),tenant.valid_administrators(uuid) TO ls_core_tenant_users_dev;

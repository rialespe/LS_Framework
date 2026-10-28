-- LFD-40. Registration only, no activation or operational tenant privileges.
DO $$ BEGIN
    IF session_user<>'ls_core_migrator_dev' OR current_user<>session_user OR current_database()<>'ls_core_dev'
       OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999
       OR (SELECT count(*) FROM pg_roles r WHERE rolname IN ('ls_core_tenant_registrar_dev','ls_core_tenant_registration_log_dev')
            AND rolcanlogin AND NOT(rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
            AND rolconnlimit=CASE WHEN rolname='ls_core_tenant_registrar_dev' THEN 3 ELSE 2 END
            AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
            AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o')
            AND NOT has_database_privilege(r.oid,current_database(),'CREATE,TEMPORARY'))<>2
       OR (SELECT count(*) FROM pg_class WHERE oid IN ('tenant.tenants'::regclass,'tenant.memberships'::regclass,
            'tenant.administrators'::regclass,'audit.events'::regclass,'platform.administrators'::regclass)
            AND relowner=current_user::regrole AND relrowsecurity AND relforcerowsecurity)<>5 THEN
        RAISE EXCEPTION 'Expected restricted V21 owner, FORCE RLS and isolated registration channels';
    END IF;
END $$;
CREATE TABLE platform.tenant_registration_grants (
    designation_id uuid PRIMARY KEY REFERENCES platform.administrators(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
ALTER TABLE platform.tenant_registration_grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.tenant_registration_grants FORCE ROW LEVEL SECURITY;
-- No functional writer. The controlled installation operator grants this specific capability explicitly.
ALTER TABLE tenant.administrators ADD CONSTRAINT administrators_registration_scope_uq UNIQUE(tenant_id,id);
CREATE TABLE tenant.initial_registrations (
    request_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    membership_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    designation_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    actor_user_id uuid NOT NULL REFERENCES identity.users(id),
    administrator_user_id uuid NOT NULL REFERENCES identity.users(id),
    name text NOT NULL CHECK (name=btrim(name) AND char_length(name) BETWEEN 1 AND 200 AND name !~ '[[:cntrl:]]'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(tenant_id) REFERENCES tenant.tenants(id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY(tenant_id,designation_id) REFERENCES tenant.administrators(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY(tenant_id,membership_id,administrator_user_id) REFERENCES tenant.memberships(tenant_id,id,user_id) DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE tenant.initial_registrations ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant.initial_registrations FORCE ROW LEVEL SECURITY;

CREATE FUNCTION tenant.guard_initial_registration() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
    IF session_user<>'ls_core_tenant_registrar_dev' OR current_user<>session_user OR TG_OP<>'INSERT'
       OR current_setting('transaction_isolation')<>'read committed' THEN
        RAISE EXCEPTION 'Registration channel unavailable' USING ERRCODE='42501';
    END IF;
    PERFORM pg_advisory_xact_lock(370019,1);
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.request_id::text,400022));
    IF NOT EXISTS(SELECT FROM platform.administrators a JOIN identity.users u ON u.id=a.user_id
        WHERE a.user_id=NEW.actor_user_id AND a.status='ACTIVE' AND u.status='ACTIVE' AND a.revoked_at IS NULL
          AND (a.administrator_kind='OWNER' OR (a.administrator_kind='SUPERADMIN'
               AND EXISTS(SELECT FROM platform.tenant_registration_grants g WHERE g.designation_id=a.id))))
       OR NOT EXISTS(SELECT FROM identity.users WHERE id=NEW.administrator_user_id AND status IN ('ACTIVE','PENDING')) THEN
        RAISE EXCEPTION 'Registration not authorized' USING ERRCODE='42501';
    END IF;
    NEW.created_at=clock_timestamp();
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION tenant.guard_initial_registration() FROM PUBLIC;
CREATE TRIGGER initial_registration_guard BEFORE INSERT OR UPDATE OR DELETE ON tenant.initial_registrations
    FOR EACH ROW EXECUTE FUNCTION tenant.guard_initial_registration();

CREATE FUNCTION tenant.complete_initial_registration() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
    INSERT INTO tenant.tenants(id,name,status) VALUES(NEW.tenant_id,NEW.name,'PENDING');
    INSERT INTO tenant.memberships(id,tenant_id,user_id,status,invited_by_user_id)
        VALUES(NEW.membership_id,NEW.tenant_id,NEW.administrator_user_id,'PENDING',NEW.actor_user_id);
    INSERT INTO tenant.administrators(id,tenant_id,membership_id,status,granted_by_user_id,granted_at)
        VALUES(NEW.designation_id,NEW.tenant_id,NEW.membership_id,'PENDING',NEW.actor_user_id,clock_timestamp());
    INSERT INTO audit.events(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
        VALUES(clock_timestamp(),'USER',NEW.actor_user_id,'TENANT_REGISTER','TENANT',NEW.tenant_id,'SUCCESS',NEW.request_id,
               jsonb_build_object('administrator_user_id',NEW.administrator_user_id::text,'request_id',NEW.request_id::text));
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION tenant.complete_initial_registration() FROM PUBLIC;
CREATE TRIGGER initial_registration_complete AFTER INSERT ON tenant.initial_registrations
    FOR EACH ROW EXECUTE FUNCTION tenant.complete_initial_registration();

CREATE FUNCTION tenant.guard_registration_child() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
    IF current_user='ls_core_tenant_registrar_dev' AND (session_user<>current_user OR TG_OP<>'INSERT'
        OR pg_trigger_depth()<>2 OR NEW.status<>'PENDING') THEN
        RAISE EXCEPTION 'Only atomic PENDING initialization is permitted' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION tenant.guard_registration_child() FROM PUBLIC;
CREATE TRIGGER registration_tenant_child BEFORE INSERT OR UPDATE ON tenant.tenants FOR EACH ROW EXECUTE FUNCTION tenant.guard_registration_child();
CREATE TRIGGER registration_membership_child BEFORE INSERT OR UPDATE ON tenant.memberships FOR EACH ROW EXECUTE FUNCTION tenant.guard_registration_child();
CREATE TRIGGER registration_administrator_child BEFORE INSERT OR UPDATE ON tenant.administrators FOR EACH ROW EXECUTE FUNCTION tenant.guard_registration_child();

CREATE FUNCTION audit.guard_tenant_registration_event() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
    IF TG_OP IN ('UPDATE','DELETE') THEN
        IF OLD.action_code='TENANT_REGISTER' THEN RAISE EXCEPTION 'Registration evidence immutable' USING ERRCODE='42501'; END IF;
        IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    END IF;
    IF NEW.action_code='TENANT_REGISTER' OR current_user IN ('ls_core_tenant_registrar_dev','ls_core_tenant_registration_log_dev') THEN
        IF TG_OP<>'INSERT' OR session_user<>current_user OR NEW.action_code<>'TENANT_REGISTER'
            OR NEW.actor_kind<>'USER' OR NEW.resource_type IS DISTINCT FROM 'TENANT'
            OR NEW.tenant_id IS NOT NULL OR NEW.application_id IS NOT NULL OR NEW.session_id IS NOT NULL
            OR NEW.actor_api_key_id IS NOT NULL OR NEW.correlation_id IS NULL THEN
            RAISE EXCEPTION 'Invalid registration evidence' USING ERRCODE='42501';
        END IF;
        IF current_user='ls_core_tenant_registrar_dev' THEN
            IF pg_trigger_depth()<>2 OR NEW.outcome<>'SUCCESS' OR NEW.actor_user_id IS NULL OR NEW.resource_id IS NULL
                OR NOT EXISTS(SELECT FROM tenant.initial_registrations r WHERE r.request_id=NEW.correlation_id
                    AND r.actor_user_id=NEW.actor_user_id AND r.tenant_id=NEW.resource_id
                    AND NEW.metadata=jsonb_build_object('administrator_user_id',r.administrator_user_id::text,'request_id',r.request_id::text)) THEN
                RAISE EXCEPTION 'Atomic registration evidence required' USING ERRCODE='42501';
            END IF;
        ELSIF current_user='ls_core_tenant_registration_log_dev' THEN
            IF NEW.outcome NOT IN ('DENIED','UNKNOWN') OR NEW.metadata<>'{}'::jsonb THEN
                RAISE EXCEPTION 'Rejection channel cannot claim success' USING ERRCODE='42501';
            END IF;
        ELSE RAISE EXCEPTION 'Registration evidence unavailable' USING ERRCODE='42501';
        END IF;
        NEW.occurred_at=clock_timestamp(); NEW.created_at=NEW.occurred_at;
    END IF;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION audit.guard_tenant_registration_event() FROM PUBLIC;
CREATE TRIGGER tenant_registration_event BEFORE INSERT OR UPDATE OR DELETE ON audit.events
    FOR EACH ROW EXECUTE FUNCTION audit.guard_tenant_registration_event();

GRANT USAGE ON SCHEMA identity,platform,tenant,audit TO ls_core_tenant_registrar_dev;
GRANT USAGE ON SCHEMA audit TO ls_core_tenant_registration_log_dev;
GRANT SELECT(id,username,display_name,status) ON identity.users TO ls_core_tenant_registrar_dev;
GRANT SELECT(id,user_id,administrator_kind,status,revoked_at) ON platform.administrators TO ls_core_tenant_registrar_dev;
GRANT SELECT(designation_id) ON platform.tenant_registration_grants TO ls_core_tenant_registrar_dev;
GRANT SELECT ON tenant.initial_registrations TO ls_core_tenant_registrar_dev;
GRANT INSERT(request_id,actor_user_id,administrator_user_id,name) ON tenant.initial_registrations TO ls_core_tenant_registrar_dev;
GRANT SELECT(id,name,status,created_at) ON tenant.tenants TO ls_core_tenant_registrar_dev;
GRANT INSERT(id,name,status) ON tenant.tenants TO ls_core_tenant_registrar_dev;
GRANT INSERT(id,tenant_id,user_id,status,invited_by_user_id) ON tenant.memberships TO ls_core_tenant_registrar_dev;
GRANT INSERT(id,tenant_id,membership_id,status,granted_by_user_id,granted_at) ON tenant.administrators TO ls_core_tenant_registrar_dev;
GRANT INSERT(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
    ON audit.events TO ls_core_tenant_registrar_dev,ls_core_tenant_registration_log_dev;
CREATE POLICY registration_users ON identity.users FOR SELECT TO ls_core_tenant_registrar_dev
    USING(session_user=current_user AND current_user='ls_core_tenant_registrar_dev' AND status IN ('ACTIVE','PENDING'));
CREATE POLICY registration_authority ON platform.administrators FOR SELECT TO ls_core_tenant_registrar_dev
    USING(session_user=current_user AND current_user='ls_core_tenant_registrar_dev');
CREATE POLICY registration_grants ON platform.tenant_registration_grants FOR SELECT TO ls_core_tenant_registrar_dev
    USING(session_user=current_user AND current_user='ls_core_tenant_registrar_dev');
CREATE POLICY registration_ledger_read ON tenant.initial_registrations FOR SELECT TO ls_core_tenant_registrar_dev
    USING(session_user=current_user AND current_user='ls_core_tenant_registrar_dev');
CREATE POLICY registration_ledger_insert ON tenant.initial_registrations FOR INSERT TO ls_core_tenant_registrar_dev
    WITH CHECK(session_user=current_user AND current_user='ls_core_tenant_registrar_dev');
CREATE POLICY registration_tenant_read ON tenant.tenants FOR SELECT TO ls_core_tenant_registrar_dev
    USING(session_user=current_user AND current_user='ls_core_tenant_registrar_dev'
          AND EXISTS(SELECT FROM tenant.initial_registrations r WHERE r.tenant_id=tenant.tenants.id));
CREATE POLICY registration_tenant_insert ON tenant.tenants FOR INSERT TO ls_core_tenant_registrar_dev
    WITH CHECK(session_user=current_user AND current_user='ls_core_tenant_registrar_dev' AND status='PENDING' AND pg_trigger_depth()=1);
CREATE POLICY registration_membership_insert ON tenant.memberships FOR INSERT TO ls_core_tenant_registrar_dev
    WITH CHECK(session_user=current_user AND current_user='ls_core_tenant_registrar_dev' AND status='PENDING' AND pg_trigger_depth()=1);
CREATE POLICY registration_admin_insert ON tenant.administrators FOR INSERT TO ls_core_tenant_registrar_dev
    WITH CHECK(session_user=current_user AND current_user='ls_core_tenant_registrar_dev' AND status='PENDING' AND pg_trigger_depth()=1);
CREATE POLICY registration_success ON audit.events FOR INSERT TO ls_core_tenant_registrar_dev
    WITH CHECK(session_user=current_user AND current_user='ls_core_tenant_registrar_dev' AND action_code='TENANT_REGISTER' AND outcome='SUCCESS');
CREATE POLICY registration_rejection ON audit.events FOR INSERT TO ls_core_tenant_registration_log_dev
    WITH CHECK(session_user=current_user AND current_user='ls_core_tenant_registration_log_dev' AND action_code='TENANT_REGISTER' AND outcome IN ('DENIED','UNKNOWN'));

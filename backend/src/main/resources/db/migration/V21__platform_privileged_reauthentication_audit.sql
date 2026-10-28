-- No identities, credentials or channel activation. V19/V20 remain authoritative.
DO $$ BEGIN
    IF session_user<>'ls_core_migrator_dev' OR current_user<>session_user OR current_database()<>'ls_core_dev'
       OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999
       OR (SELECT count(*) FROM pg_roles r WHERE rolname IN ('ls_core_platform_mutator_dev','ls_core_platform_security_log_dev')
           AND NOT (rolcanlogin OR rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
           AND rolconnlimit=2 AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
           AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o')
           AND NOT has_database_privilege(r.oid,current_database(),'CONNECT,CREATE,TEMPORARY'))<>2
       OR (SELECT count(*) FROM pg_class WHERE oid IN ('audit.events'::regclass,'platform.administrators'::regclass)
           AND relowner=current_user::regrole AND relrowsecurity AND relforcerowsecurity)<>2 THEN
        RAISE EXCEPTION 'Expected restricted migrator, FORCE RLS and dormant channels';
    END IF;
END $$;

CREATE FUNCTION audit.guard_platform_event() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
    IF TG_OP='TRUNCATE' THEN
        RAISE EXCEPTION 'Audit history cannot be truncated' USING ERRCODE='42501';
    END IF;
    IF TG_OP IN ('UPDATE','DELETE') THEN
        IF OLD.action_code IN ('PLATFORM_DESIGNATE_SUPERADMIN','PLATFORM_REVOKE_SUPERADMIN','PLATFORM_INVALID_REQUEST') THEN
            RAISE EXCEPTION 'Platform evidence is immutable' USING ERRCODE='42501';
        END IF;
        IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    END IF;
    IF NEW.action_code IN ('PLATFORM_DESIGNATE_SUPERADMIN','PLATFORM_REVOKE_SUPERADMIN','PLATFORM_INVALID_REQUEST')
       OR current_user IN ('ls_core_platform_mutator_dev','ls_core_platform_security_log_dev') THEN
        IF TG_OP<>'INSERT' OR session_user<>current_user OR NEW.tenant_id IS NOT NULL
           OR NEW.application_id IS NOT NULL OR NEW.actor_api_key_id IS NOT NULL OR NEW.session_id IS NOT NULL
           OR NEW.actor_kind<>'USER' OR NEW.resource_type IS DISTINCT FROM 'PLATFORM_USER' OR NEW.correlation_id IS NULL
           OR NEW.action_code NOT IN ('PLATFORM_DESIGNATE_SUPERADMIN','PLATFORM_REVOKE_SUPERADMIN','PLATFORM_INVALID_REQUEST') THEN
            RAISE EXCEPTION 'Invalid platform evidence' USING ERRCODE='42501';
        END IF;
        IF current_user='ls_core_platform_mutator_dev' THEN
            IF pg_trigger_depth()<>2 OR NEW.outcome<>'SUCCESS' OR NEW.actor_user_id IS NULL OR NEW.resource_id IS NULL
               OR jsonb_typeof(NEW.metadata)<>'object' OR NEW.metadata IS DISTINCT FROM jsonb_build_object('designation_id',NEW.metadata->>'designation_id')
               OR (NEW.metadata->>'designation_id') IS NULL THEN
                RAISE EXCEPTION 'Success evidence requires the designation trigger' USING ERRCODE='42501';
            END IF;
        ELSIF current_user='ls_core_platform_security_log_dev' THEN
            IF NEW.outcome NOT IN ('DENIED','UNKNOWN')
               OR NEW.metadata IS DISTINCT FROM jsonb_build_object('reason',CASE WHEN NEW.outcome='UNKNOWN' THEN 'COMMIT_UNCERTAIN' ELSE 'REJECTED' END) THEN
                RAISE EXCEPTION 'Security channel cannot claim success' USING ERRCODE='42501';
            END IF;
        ELSE
            RAISE EXCEPTION 'Platform evidence channel unavailable' USING ERRCODE='42501';
        END IF;
        NEW.occurred_at=clock_timestamp();
        NEW.created_at=NEW.occurred_at;
    END IF;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION audit.guard_platform_event() FROM PUBLIC;
CREATE TRIGGER platform_event_guard BEFORE INSERT OR UPDATE OR DELETE ON audit.events
    FOR EACH ROW EXECUTE FUNCTION audit.guard_platform_event();
CREATE TRIGGER platform_event_no_truncate BEFORE TRUNCATE ON audit.events
    FOR EACH STATEMENT EXECUTE FUNCTION audit.guard_platform_event();

CREATE FUNCTION platform.audit_designation_change() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
    IF NEW.administrator_kind='SUPERADMIN' THEN
        IF current_user<>'ls_core_platform_mutator_dev' OR session_user<>current_user THEN
            RAISE EXCEPTION 'Designation audit channel unavailable' USING ERRCODE='42501';
        END IF;
        INSERT INTO audit.events(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
        VALUES (clock_timestamp(),'USER',CASE WHEN TG_OP='INSERT' THEN NEW.granted_by_user_id ELSE NEW.revoked_by_user_id END,
            CASE WHEN TG_OP='INSERT' THEN 'PLATFORM_DESIGNATE_SUPERADMIN' ELSE 'PLATFORM_REVOKE_SUPERADMIN' END,
            'PLATFORM_USER',NEW.user_id,'SUCCESS',gen_random_uuid(),jsonb_build_object('designation_id',NEW.id::text));
    END IF;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION platform.audit_designation_change() FROM PUBLIC;
CREATE TRIGGER platform_designation_audit AFTER INSERT OR UPDATE ON platform.administrators
    FOR EACH ROW EXECUTE FUNCTION platform.audit_designation_change();

GRANT USAGE ON SCHEMA audit TO ls_core_platform_mutator_dev,ls_core_platform_security_log_dev;
GRANT INSERT(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
    ON audit.events TO ls_core_platform_mutator_dev,ls_core_platform_security_log_dev;
GRANT SELECT(correlation_id,resource_id,action_code,outcome,metadata) ON audit.events TO ls_core_platform_mutator_dev;
CREATE POLICY platform_success_insert ON audit.events FOR INSERT TO ls_core_platform_mutator_dev
    WITH CHECK (session_user=current_user AND current_user='ls_core_platform_mutator_dev' AND outcome='SUCCESS'
        AND action_code IN ('PLATFORM_DESIGNATE_SUPERADMIN','PLATFORM_REVOKE_SUPERADMIN'));
CREATE POLICY platform_evidence_read ON audit.events FOR SELECT TO ls_core_platform_mutator_dev
    USING (session_user=current_user AND current_user='ls_core_platform_mutator_dev' AND outcome='SUCCESS'
        AND action_code IN ('PLATFORM_DESIGNATE_SUPERADMIN','PLATFORM_REVOKE_SUPERADMIN'));
CREATE POLICY platform_rejected_insert ON audit.events FOR INSERT TO ls_core_platform_security_log_dev
    WITH CHECK (session_user=current_user AND current_user='ls_core_platform_security_log_dev' AND outcome IN ('DENIED','UNKNOWN')
        AND action_code IN ('PLATFORM_DESIGNATE_SUPERADMIN','PLATFORM_REVOKE_SUPERADMIN','PLATFORM_INVALID_REQUEST'));
-- No audit UPDATE/DELETE/TRUNCATE, tenant access, session grants or HTTP principal activation.

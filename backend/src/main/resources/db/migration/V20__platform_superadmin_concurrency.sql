-- LFD-38: dormant future channel. LFD-39 must supply recent proof and audit before any activation.
DO $$ BEGIN
    IF session_user<>'ls_core_migrator_dev' OR current_user<>session_user
       OR current_database()<>'ls_core_dev'
       OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999
       OR NOT EXISTS (SELECT FROM pg_roles r WHERE rolname='ls_core_platform_mutator_dev'
           AND NOT (rolcanlogin OR rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
           AND rolconnlimit=2 AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
           AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o')
           AND NOT has_database_privilege(r.oid,current_database(),'CONNECT,CREATE,TEMPORARY'))
       OR NOT EXISTS (SELECT FROM pg_class WHERE oid='platform.administrators'::regclass
           AND relowner=current_user::regrole AND relrowsecurity AND relforcerowsecurity) THEN
        RAISE EXCEPTION 'Expected restricted migrator, FORCE RLS and dormant platform channel';
    END IF;
END $$;

-- BEFORE STATEMENT takes the global lock before PostgreSQL locks any target designation.
-- READ COMMITTED is mandatory: VOLATILE trigger queries after the lock get fresh snapshots.
CREATE FUNCTION platform.lock_designations() RETURNS trigger
LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
    IF session_user<>current_user OR TG_OP NOT IN ('INSERT','UPDATE')
       OR current_user NOT IN ('ls_core_platform_bootstrap_dev','ls_core_platform_mutator_dev')
       OR (current_user='ls_core_platform_bootstrap_dev' AND TG_OP<>'INSERT') THEN
        RAISE EXCEPTION 'Platform mutation channel unavailable' USING ERRCODE='42501';
    END IF;
    IF current_setting('transaction_isolation')<>'read committed' THEN
        RAISE EXCEPTION 'Platform mutations require READ COMMITTED' USING ERRCODE='25000';
    END IF;
    PERFORM pg_advisory_xact_lock(370019,1);
    IF current_user='ls_core_platform_mutator_dev' THEN
        -- Stable row lock order, including the owner, after global serialization.
        PERFORM id FROM platform.administrators ORDER BY id FOR UPDATE;
        IF (SELECT count(*) FROM platform.administrators a JOIN identity.users u ON u.id=a.user_id
            WHERE a.administrator_kind='OWNER' AND a.status='ACTIVE' AND u.status='ACTIVE')<>1 THEN
            RAISE EXCEPTION 'Active owner required' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION platform.lock_designations() FROM PUBLIC;
CREATE TRIGGER platform_designation_lock BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON platform.administrators
    FOR EACH STATEMENT EXECUTE FUNCTION platform.lock_designations();

CREATE OR REPLACE FUNCTION platform.guard_initial_designation() RETURNS trigger
LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE owner_id uuid;
BEGIN
    IF TG_OP='INSERT' AND session_user='ls_core_platform_bootstrap_dev' AND current_user=session_user
       AND NEW.administrator_kind='OWNER' AND NEW.status='ACTIVE' THEN
        RETURN NEW; -- Existing exceptional bootstrap only; all V19 constraints and policy remain.
    END IF;
    IF session_user<>'ls_core_platform_mutator_dev' OR current_user<>session_user
       OR TG_OP NOT IN ('INSERT','UPDATE') THEN
        RAISE EXCEPTION 'Global mutation unavailable pending LFD-39' USING ERRCODE='P0001';
    END IF;
    SELECT user_id INTO STRICT owner_id FROM platform.administrators
        WHERE administrator_kind='OWNER' AND status='ACTIVE';
    IF TG_OP='INSERT' THEN
        IF NEW.administrator_kind<>'SUPERADMIN' OR NEW.status<>'ACTIVE'
           OR NEW.granted_by_user_id IS DISTINCT FROM owner_id OR NEW.user_id=owner_id
           OR NEW.revoked_by_user_id IS NOT NULL OR NEW.revoked_at IS NOT NULL THEN
            RAISE EXCEPTION 'Only owner-authorized delegation is valid' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    -- Stored generated columns are computed after BEFORE triggers; their expression and FK remain authoritative.
    IF OLD.administrator_kind<>'SUPERADMIN' OR OLD.status<>'ACTIVE'
       OR NEW.status<>'REVOKED' OR NEW.revoked_by_user_id IS DISTINCT FROM owner_id
       OR (to_jsonb(NEW)-ARRAY['status','revoked_by_user_id','revoked_at','updated_at','protected_user_status'])
          IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','revoked_by_user_id','revoked_at','updated_at','protected_user_status']) THEN
        RAISE EXCEPTION 'Designation identity and history are immutable' USING ERRCODE='23514';
    END IF;
    -- Fresh read after the statement lock; sees prior revocations in this transaction too.
    IF (SELECT count(*) FROM platform.administrators WHERE administrator_kind='SUPERADMIN' AND status='ACTIVE')<=1 THEN
        RAISE EXCEPTION 'Designate a replacement before revoking the last delegate' USING ERRCODE='23514';
    END IF;
    NEW.revoked_at=clock_timestamp();
    NEW.updated_at=NEW.revoked_at;
    RETURN NEW;
END $$;

GRANT USAGE ON SCHEMA identity,platform TO ls_core_platform_mutator_dev;
GRANT SELECT(id,status) ON identity.users TO ls_core_platform_mutator_dev;
GRANT SELECT(id,user_id,administrator_kind,status,granted_by_user_id,revoked_by_user_id,revoked_at,created_at,updated_at)
    ON platform.administrators TO ls_core_platform_mutator_dev;
GRANT INSERT(user_id,administrator_kind,status,granted_by_user_id)
    ON platform.administrators TO ls_core_platform_mutator_dev;
GRANT UPDATE(status,revoked_by_user_id,revoked_at,updated_at)
    ON platform.administrators TO ls_core_platform_mutator_dev;
CREATE POLICY platform_future_user_read ON identity.users FOR SELECT TO ls_core_platform_mutator_dev
    USING (session_user=current_user AND current_user='ls_core_platform_mutator_dev' AND status='ACTIVE');
CREATE POLICY platform_future_read ON platform.administrators FOR SELECT TO ls_core_platform_mutator_dev
    USING (session_user=current_user AND current_user='ls_core_platform_mutator_dev');
CREATE POLICY platform_future_insert ON platform.administrators FOR INSERT TO ls_core_platform_mutator_dev
    WITH CHECK (session_user=current_user AND current_user='ls_core_platform_mutator_dev' AND administrator_kind='SUPERADMIN');
CREATE POLICY platform_future_revoke ON platform.administrators FOR UPDATE TO ls_core_platform_mutator_dev
    USING (session_user=current_user AND current_user='ls_core_platform_mutator_dev')
    WITH CHECK (session_user=current_user AND current_user='ls_core_platform_mutator_dev' AND administrator_kind='SUPERADMIN' AND status='REVOKED');
-- No DELETE/TRUNCATE, credential access, tenant grants, LOGIN, CONNECT or memberships.
-- V19 FK conservatively protects both active and historical identities; no history is removed.

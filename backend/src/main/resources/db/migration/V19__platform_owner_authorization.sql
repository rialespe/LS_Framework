-- LFD-37. Provision the isolated authorization LOGIN explicitly before applying V19 as the restricted migrator.
-- These are trusted internal service identities, never client-selectable roles or authentication results.
DO $$
DECLARE
    principal record;
    relation record;
BEGIN
    IF current_user <> 'ls_core_migrator_dev' OR session_user <> current_user
       OR current_database() <> 'ls_core_dev'
       OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999 THEN
        RAISE EXCEPTION 'Expected restricted migrator on PostgreSQL 17 ls_core_dev';
    END IF;
    IF (SELECT count(*) FROM pg_roles WHERE rolname IN ('ls_core_platform_bootstrap_dev','ls_core_platform_reader_dev') AND rolcanlogin
        AND NOT (rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
        AND rolconnlimit=CASE WHEN rolname='ls_core_platform_bootstrap_dev' THEN 1 ELSE 3 END) <> 2 THEN
        RAISE EXCEPTION 'Provision isolated platform channels before V19';
    END IF;
    IF (SELECT count(*) FROM pg_class WHERE oid IN
        ('identity.users'::regclass,'identity.user_credentials'::regclass,'platform.administrators'::regclass)
        AND relowner=current_user::regrole AND relrowsecurity AND relforcerowsecurity) <> 3 THEN
        RAISE EXCEPTION 'Expected restricted owner and FORCE RLS';
    END IF;
    FOR principal IN SELECT oid FROM pg_roles WHERE rolname IN ('ls_core_platform_bootstrap_dev','ls_core_platform_reader_dev')
    LOOP
        IF EXISTS (SELECT FROM pg_auth_members WHERE member=principal.oid OR roleid=principal.oid)
           OR EXISTS (SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass
                      AND refobjid=principal.oid AND deptype='o')
           OR EXISTS (SELECT FROM pg_database WHERE datallowconn AND
                      (has_database_privilege(principal.oid,oid,'CREATE,TEMPORARY') OR
                      (datname<>current_database() AND has_database_privilege(principal.oid,oid,'CONNECT'))))
           OR NOT has_database_privilege(principal.oid,current_database(),'CONNECT')
           OR EXISTS (SELECT FROM pg_namespace WHERE has_schema_privilege(principal.oid,oid,'CREATE')) THEN
            RAISE EXCEPTION 'Authorization reader privileges incompatible; no repair attempted';
        END IF;
        FOR relation IN SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname !~ '^pg_'
              AND c.relkind IN ('r','p','v','m','f')
        LOOP
            IF has_table_privilege(principal.oid,relation.oid,
                'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
               OR has_any_column_privilege(principal.oid,relation.oid,'SELECT,INSERT,UPDATE,REFERENCES') THEN
                RAISE EXCEPTION 'Authorization reader has preexisting relation access; no repair attempted';
            END IF;
        END LOOP;
    END LOOP;
END $$;

-- Validate an empty installation without exposing rows or weakening FORCE RLS.
-- Any pre-existing designation must be reviewed separately; never silently adopt it.
ALTER TABLE platform.administrators ADD CONSTRAINT platform_initial_empty_ck CHECK (false);
ALTER TABLE platform.administrators DROP CONSTRAINT platform_initial_empty_ck;
ALTER TABLE identity.users ADD CONSTRAINT users_platform_status_uq UNIQUE(id,status);
ALTER TABLE platform.administrators ADD COLUMN protected_user_status TEXT
    GENERATED ALWAYS AS ('ACTIVE'::text) STORED NOT NULL;
ALTER TABLE platform.administrators ADD CONSTRAINT platform_admin_active_identity_fk
    FOREIGN KEY(user_id,protected_user_status) REFERENCES identity.users(id,status)
    ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE platform.administrators ADD CONSTRAINT platform_admin_state_ck CHECK (
    (administrator_kind='OWNER' AND status='ACTIVE' AND granted_by_user_id IS NULL
        AND revoked_at IS NULL AND revoked_by_user_id IS NULL)
    OR (administrator_kind='SUPERADMIN' AND granted_by_user_id IS NOT NULL AND granted_by_user_id<>user_id
        AND ((status='ACTIVE' AND revoked_at IS NULL AND revoked_by_user_id IS NULL)
            OR (status='REVOKED' AND revoked_at IS NOT NULL AND revoked_by_user_id IS NOT NULL)))
);
CREATE UNIQUE INDEX platform_single_owner_uq ON platform.administrators(administrator_kind) WHERE administrator_kind='OWNER';
CREATE UNIQUE INDEX platform_active_user_uq ON platform.administrators(user_id) WHERE status='ACTIVE';

-- No definer privileges or table reads: OLD/NEW and the actual database principal only.
CREATE FUNCTION platform.guard_initial_designation() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
    IF TG_OP<>'INSERT' THEN
        RAISE EXCEPTION 'Platform designation mutation disabled pending LFD-38 and LFD-39';
    END IF;
    IF session_user<>'ls_core_platform_bootstrap_dev' OR current_user<>session_user
       OR NEW.administrator_kind<>'OWNER' OR NEW.status<>'ACTIVE' THEN
        RAISE EXCEPTION 'Only the exceptional owner installation channel is enabled';
    END IF;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION platform.guard_initial_designation() FROM PUBLIC;
CREATE TRIGGER platform_designation_guard BEFORE INSERT OR UPDATE OR DELETE ON platform.administrators
    FOR EACH ROW EXECUTE FUNCTION platform.guard_initial_designation();
CREATE TRIGGER platform_designation_no_truncate BEFORE TRUNCATE ON platform.administrators
    FOR EACH STATEMENT EXECUTE FUNCTION platform.guard_initial_designation();

GRANT USAGE ON SCHEMA identity,platform TO ls_core_platform_reader_dev,ls_core_platform_bootstrap_dev;
GRANT SELECT(id,status) ON identity.users TO ls_core_platform_reader_dev,ls_core_platform_bootstrap_dev;
GRANT SELECT(id,user_id,administrator_kind,status,granted_by_user_id,revoked_by_user_id,revoked_at,created_at)
    ON platform.administrators TO ls_core_platform_reader_dev;
GRANT SELECT(id,user_id,administrator_kind,status) ON platform.administrators TO ls_core_platform_bootstrap_dev;
GRANT SELECT(user_id,must_change_password,password_expires_at,locked_until)
    ON identity.user_credentials TO ls_core_platform_bootstrap_dev;
GRANT INSERT(user_id,administrator_kind,status) ON platform.administrators TO ls_core_platform_bootstrap_dev;
CREATE POLICY platform_condition_user_read ON identity.users FOR SELECT
    TO ls_core_platform_reader_dev,ls_core_platform_bootstrap_dev
    USING (session_user=current_user AND current_user IN ('ls_core_platform_reader_dev','ls_core_platform_bootstrap_dev') AND status='ACTIVE');
CREATE POLICY platform_condition_read ON platform.administrators FOR SELECT TO ls_core_platform_reader_dev,ls_core_platform_bootstrap_dev
    USING (session_user=current_user AND current_user IN ('ls_core_platform_reader_dev','ls_core_platform_bootstrap_dev'));
CREATE POLICY platform_bootstrap_credential_state ON identity.user_credentials FOR SELECT TO ls_core_platform_bootstrap_dev
    USING (session_user=current_user AND current_user='ls_core_platform_bootstrap_dev');
CREATE POLICY platform_owner_install ON platform.administrators FOR INSERT TO ls_core_platform_bootstrap_dev
    WITH CHECK (session_user=current_user AND current_user='ls_core_platform_bootstrap_dev'
        AND administrator_kind='OWNER' AND status='ACTIVE' AND granted_by_user_id IS NULL
        AND revoked_by_user_id IS NULL AND revoked_at IS NULL
        AND EXISTS (SELECT FROM identity.users u JOIN identity.user_credentials c ON c.user_id=u.id
            WHERE u.id=platform.administrators.user_id AND u.status='ACTIVE' AND NOT c.must_change_password
              AND c.password_expires_at>clock_timestamp() AND (c.locked_until IS NULL OR c.locked_until<=clock_timestamp())));
-- No functional UPDATE/DELETE/TRUNCATE grants, mutable HTTP route, delegation LOGIN or session authority.

-- LFD-34. Provision the isolated session LOGIN explicitly before applying V16 as the restricted migrator.
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
    IF (SELECT count(*) FROM pg_roles WHERE rolname='ls_core_session_manager_dev' AND rolcanlogin
        AND NOT (rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
        AND rolconnlimit=3) <> 1 THEN
        RAISE EXCEPTION 'Provision isolated session manager LOGIN before V16';
    END IF;
    IF (SELECT count(*) FROM pg_class WHERE oid IN
        ('identity.users'::regclass,'security.sessions'::regclass)
        AND relowner=current_user::regrole AND relrowsecurity AND relforcerowsecurity) <> 2 THEN
        RAISE EXCEPTION 'Expected restricted owner and FORCE RLS';
    END IF;
    FOR principal IN SELECT oid FROM pg_roles WHERE rolname='ls_core_session_manager_dev'
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

-- Tokens are opaque 256-bit secrets; only lowercase SHA-256 digests are stored.
ALTER TABLE security.sessions ADD CONSTRAINT sessions_sha256_digest_ck CHECK (token_hash ~ '^[0-9a-f]{64}$');
CREATE UNIQUE INDEX sessions_token_hash_uq ON security.sessions(token_hash);
GRANT USAGE ON SCHEMA identity,catalog,security TO ls_core_session_manager_dev;
GRANT SELECT(id,status) ON identity.users TO ls_core_session_manager_dev;
GRANT SELECT(id,status) ON catalog.applications TO ls_core_session_manager_dev;
GRANT SELECT(id,user_id,application_id,token_hash,expires_at,revoked_at,created_at)
    ON security.sessions TO ls_core_session_manager_dev;
GRANT INSERT(id,user_id,application_id,token_hash,expires_at) ON security.sessions TO ls_core_session_manager_dev;
GRANT UPDATE(revoked_at,updated_at) ON security.sessions TO ls_core_session_manager_dev;
CREATE POLICY session_identity_read ON identity.users FOR SELECT TO ls_core_session_manager_dev
    USING (session_user=current_user AND current_user='ls_core_session_manager_dev' AND status='ACTIVE');
CREATE POLICY session_metadata_read ON security.sessions FOR SELECT TO ls_core_session_manager_dev
    USING (session_user=current_user AND current_user='ls_core_session_manager_dev');
CREATE POLICY session_issue ON security.sessions FOR INSERT TO ls_core_session_manager_dev
    WITH CHECK (session_user=current_user AND current_user='ls_core_session_manager_dev' AND revoked_at IS NULL
        AND expires_at>clock_timestamp() AND expires_at<=clock_timestamp()+interval '24 hours'
        AND EXISTS (SELECT FROM identity.users u WHERE u.id=user_id AND u.status='ACTIVE')
        AND EXISTS (SELECT FROM catalog.applications a WHERE a.id=application_id AND a.status='PUBLISHED'));
CREATE POLICY session_revoke ON security.sessions FOR UPDATE TO ls_core_session_manager_dev
    USING (session_user=current_user AND current_user='ls_core_session_manager_dev' AND revoked_at IS NULL)
    WITH CHECK (session_user=current_user AND current_user='ls_core_session_manager_dev' AND revoked_at IS NOT NULL);
-- No enterprise grants, session-context writes, role memberships, SECURITY DEFINER or SESSION operation admission.

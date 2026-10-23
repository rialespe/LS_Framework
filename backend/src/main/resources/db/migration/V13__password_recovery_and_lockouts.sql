-- LFD-33. Provision the two isolated LOGINs explicitly before applying V13 as the restricted migrator.
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
    IF (SELECT count(*) FROM pg_roles WHERE rolname IN
        ('ls_core_password_authenticator_dev','ls_core_password_recovery_dev') AND rolcanlogin
        AND NOT (rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
        AND rolconnlimit=3) <> 2 THEN
        RAISE EXCEPTION 'Provision isolated password service LOGINs before V13';
    END IF;
    IF (SELECT count(*) FROM pg_class WHERE oid IN
        ('identity.users'::regclass,'identity.user_credentials'::regclass)
        AND relowner=current_user::regrole AND relrowsecurity AND relforcerowsecurity) <> 2 THEN
        RAISE EXCEPTION 'Expected restricted owner and FORCE RLS';
    END IF;
    FOR principal IN SELECT oid FROM pg_roles WHERE rolname IN
        ('ls_core_password_authenticator_dev','ls_core_password_recovery_dev')
    LOOP
        IF EXISTS (SELECT FROM pg_auth_members WHERE member=principal.oid OR roleid=principal.oid)
           OR EXISTS (SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass
                      AND refobjid=principal.oid AND deptype='o')
           OR EXISTS (SELECT FROM pg_database WHERE datallowconn AND
                      (has_database_privilege(principal.oid,oid,'CREATE,TEMPORARY') OR
                      (datname<>current_database() AND has_database_privilege(principal.oid,oid,'CONNECT'))))
           OR NOT has_database_privilege(principal.oid,current_database(),'CONNECT')
           OR EXISTS (SELECT FROM pg_namespace WHERE has_schema_privilege(principal.oid,oid,'CREATE')) THEN
            RAISE EXCEPTION 'Password role privileges incompatible; no repair attempted';
        END IF;
        FOR relation IN SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname !~ '^pg_'
              AND c.relkind IN ('r','p','v','m','f')
        LOOP
            IF has_table_privilege(principal.oid,relation.oid,
                'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
               OR has_any_column_privilege(principal.oid,relation.oid,'SELECT,INSERT,UPDATE,REFERENCES') THEN
                RAISE EXCEPTION 'Password role has preexisting relation access; no repair attempted';
            END IF;
        END LOOP;
    END LOOP;
END $$;

-- Retire the non-counting production lookup. The pure verifier remains only a KDF primitive.
REVOKE SELECT(id,username,status) ON identity.users FROM ls_core_credential_verifier_dev;
REVOKE SELECT(user_id,password_hash,password_expires_at,locked_until,must_change_password)
    ON identity.user_credentials FROM ls_core_credential_verifier_dev;
REVOKE USAGE ON SCHEMA identity FROM ls_core_credential_verifier_dev;
DROP POLICY credential_verifier_read ON identity.users;
DROP POLICY credential_verifier_read ON identity.user_credentials;

CREATE TABLE identity.password_recovery_tokens (
    token_digest BYTEA PRIMARY KEY CHECK (octet_length(token_digest)=32),
    user_id UUID NOT NULL REFERENCES identity.users(id),
    credential_fingerprint BYTEA NOT NULL CHECK (octet_length(credential_fingerprint)=32),
    email TEXT NOT NULL,
    email_verified_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CHECK (expires_at>created_at)
);
CREATE INDEX password_recovery_user_created_idx ON identity.password_recovery_tokens(user_id,created_at);
CREATE INDEX password_recovery_created_idx ON identity.password_recovery_tokens(created_at);
ALTER TABLE identity.password_recovery_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.password_recovery_tokens FORCE ROW LEVEL SECURITY;

GRANT USAGE ON SCHEMA identity TO ls_core_password_authenticator_dev,ls_core_password_recovery_dev;
GRANT SELECT(id,username,status) ON identity.users TO ls_core_password_authenticator_dev;
GRANT SELECT(id,username,status,email,email_verified_at) ON identity.users TO ls_core_password_recovery_dev;
-- UPDATE privilege/policy is needed for SELECT FOR UPDATE. WITH CHECK false prohibits actual identity writes.
GRANT UPDATE(updated_at) ON identity.users TO ls_core_password_authenticator_dev,ls_core_password_recovery_dev;
CREATE POLICY password_auth_users_read ON identity.users FOR SELECT TO ls_core_password_authenticator_dev
    USING (session_user=current_user);
CREATE POLICY password_auth_users_lock ON identity.users FOR UPDATE TO ls_core_password_authenticator_dev
    USING (session_user=current_user) WITH CHECK (false);
CREATE POLICY password_recovery_users_read ON identity.users FOR SELECT TO ls_core_password_recovery_dev
    USING (session_user=current_user);
CREATE POLICY password_recovery_users_lock ON identity.users FOR UPDATE TO ls_core_password_recovery_dev
    USING (session_user=current_user) WITH CHECK (false);

GRANT SELECT(user_id,password_hash,must_change_password,password_expires_at,locked_until,failed_attempts)
    ON identity.user_credentials TO ls_core_password_authenticator_dev;
GRANT UPDATE(failed_attempts,locked_until,updated_at) ON identity.user_credentials TO ls_core_password_authenticator_dev;
CREATE POLICY password_auth_read ON identity.user_credentials FOR SELECT TO ls_core_password_authenticator_dev
    USING (session_user=current_user);
CREATE POLICY password_auth_counters ON identity.user_credentials FOR UPDATE TO ls_core_password_authenticator_dev
    USING (session_user=current_user) WITH CHECK (session_user=current_user AND failed_attempts>=0);

GRANT SELECT(user_id,password_hash,must_change_password) ON identity.user_credentials TO ls_core_password_recovery_dev;
GRANT UPDATE(password_hash,must_change_password,password_changed_at,password_expires_at,failed_attempts,locked_until,updated_at)
    ON identity.user_credentials TO ls_core_password_recovery_dev;
CREATE POLICY password_recovery_credentials_read ON identity.user_credentials FOR SELECT TO ls_core_password_recovery_dev
    USING (session_user=current_user);
CREATE POLICY password_recovery_credentials_update ON identity.user_credentials FOR UPDATE TO ls_core_password_recovery_dev
    USING (session_user=current_user)
    WITH CHECK (session_user=current_user AND NOT must_change_password AND failed_attempts=0 AND locked_until IS NULL
        AND password_hash ~ '^\{pbkdf2-sha256-600000\}[0-9a-f]{96}$'
        AND password_expires_at>clock_timestamp()
        AND password_expires_at<=clock_timestamp()+interval '90 days');

GRANT SELECT ON identity.password_recovery_tokens TO ls_core_password_recovery_dev;
GRANT INSERT(token_digest,user_id,credential_fingerprint,email,email_verified_at,expires_at)
    ON identity.password_recovery_tokens TO ls_core_password_recovery_dev;
GRANT UPDATE(consumed_at) ON identity.password_recovery_tokens TO ls_core_password_recovery_dev;
GRANT DELETE ON identity.password_recovery_tokens TO ls_core_password_recovery_dev;
CREATE POLICY password_recovery_tokens_read ON identity.password_recovery_tokens FOR SELECT TO ls_core_password_recovery_dev
    USING (session_user=current_user);
CREATE POLICY password_recovery_tokens_insert ON identity.password_recovery_tokens FOR INSERT TO ls_core_password_recovery_dev
    WITH CHECK (session_user=current_user AND consumed_at IS NULL AND expires_at>clock_timestamp()
        AND expires_at<=clock_timestamp()+interval '1 hour');
CREATE POLICY password_recovery_tokens_consume ON identity.password_recovery_tokens FOR UPDATE TO ls_core_password_recovery_dev
    USING (session_user=current_user) WITH CHECK (session_user=current_user AND consumed_at IS NOT NULL);
CREATE POLICY password_recovery_tokens_prune ON identity.password_recovery_tokens FOR DELETE TO ls_core_password_recovery_dev
    USING (session_user=current_user AND expires_at<clock_timestamp()-interval '24 hours');

-- LFD-31: provision the dedicated LOGIN administratively before this restricted-owner migration.
-- PENDING is an initial, non-authenticating state; no credential or state transition is created here.
DO $$
DECLARE
    registrar oid;
    relation record;
BEGIN
    IF current_user <> 'ls_core_migrator_dev' OR session_user <> current_user
       OR current_database() <> 'ls_core_dev'
       OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999 THEN
        RAISE EXCEPTION 'Expected restricted migrator on PostgreSQL 17 ls_core_dev';
    END IF;
    SELECT oid INTO registrar FROM pg_roles WHERE rolname='ls_core_identity_registrar_dev'
        AND rolcanlogin AND NOT (rolinherit OR rolsuper OR rolcreatedb OR rolcreaterole
                                 OR rolreplication OR rolbypassrls) AND rolconnlimit=3;
    IF registrar IS NULL THEN
        RAISE EXCEPTION 'Provision the dedicated identity registrar before V11';
    END IF;
    IF EXISTS (SELECT FROM pg_auth_members WHERE member=registrar OR roleid=registrar)
       OR EXISTS (SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass
                   AND refobjid=registrar AND deptype='o')
       OR EXISTS (SELECT FROM pg_database WHERE datallowconn
                   AND (has_database_privilege(registrar,oid,'CREATE,TEMPORARY')
                        OR (datname<>current_database() AND has_database_privilege(registrar,oid,'CONNECT'))))
       OR NOT has_database_privilege(registrar,current_database(),'CONNECT')
       OR EXISTS (SELECT FROM pg_namespace WHERE has_schema_privilege(registrar,oid,'CREATE')) THEN
        RAISE EXCEPTION 'Registrar identity/privileges incompatible; no repair attempted';
    END IF;
    IF NOT EXISTS (SELECT FROM pg_class WHERE oid='identity.users'::regclass AND relkind='r'
                    AND relowner=current_user::regrole AND relrowsecurity AND relforcerowsecurity)
       OR (SELECT count(*) FROM pg_policy WHERE polrelid='identity.users'::regclass) <> 1
       OR NOT EXISTS (SELECT FROM pg_policy WHERE polrelid='identity.users'::regclass
                       AND polname='credential_verifier_read' AND polcmd='r'
                       AND polroles=ARRAY['ls_core_credential_verifier_dev'::regrole::oid])
       OR EXISTS (SELECT FROM pg_attrdef d JOIN pg_attribute a ON a.attrelid=d.adrelid AND a.attnum=d.adnum
                   WHERE d.adrelid='identity.users'::regclass AND a.attname='status') THEN
        RAISE EXCEPTION 'Expected unchanged V10 identity table/policies/status default';
    END IF;
    FOR relation IN
        SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname !~ '^pg_'
          AND c.relkind IN ('r','p','v','m','f')
    LOOP
        IF has_table_privilege(registrar,relation.oid,
                              'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
           OR has_any_column_privilege(registrar,relation.oid,'SELECT,INSERT,UPDATE,REFERENCES') THEN
            RAISE EXCEPTION 'Registrar has preexisting relation access; no repair attempted';
        END IF;
    END LOOP;
    IF has_any_column_privilege('ls_core_app_dev','identity.users','INSERT,UPDATE,REFERENCES')
       OR has_any_column_privilege('ls_core_credential_verifier_dev','identity.users','INSERT,UPDATE,REFERENCES') THEN
        RAISE EXCEPTION 'Runtime/verifier identity write privileges incompatible';
    END IF;
END $$;

ALTER TABLE identity.users ALTER COLUMN status SET DEFAULT 'PENDING';
GRANT USAGE ON SCHEMA identity TO ls_core_identity_registrar_dev;
-- Application-generated identifier avoids SELECT/RETURNING or read policies on other identities.
GRANT INSERT (id,username,display_name,email) ON identity.users TO ls_core_identity_registrar_dev;
CREATE POLICY manual_identity_registration ON identity.users FOR INSERT TO ls_core_identity_registrar_dev
    WITH CHECK (session_user = current_user AND current_user = 'ls_core_identity_registrar_dev'
                AND status = 'PENDING' AND email_verified_at IS NULL
                AND username IS NOT NULL AND btrim(username) <> '' AND btrim(display_name) <> '');

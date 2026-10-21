-- LFD-5: run only after the explicit administrative prepare_credential_verifier.sql step.
-- Flyway uses the existing restricted table owner, never CREATE ROLE or administrative powers.
-- The credential service is trusted to read these columns across identities for password verification.
-- This is not enterprise authorization. The enterprise runtime receives no grants/policies.
DO $$
DECLARE
    verifier oid;
    relation record;
BEGIN
    IF current_user <> 'ls_core_migrator_dev' OR session_user <> current_user
       OR current_database() <> 'ls_core_dev'
       OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999 THEN
        RAISE EXCEPTION 'Expected restricted migrator on PostgreSQL 17 ls_core_dev';
    END IF;
    SELECT oid INTO verifier FROM pg_roles WHERE rolname='ls_core_credential_verifier_dev'
        AND rolcanlogin AND NOT (rolinherit OR rolsuper OR rolcreatedb OR rolcreaterole
                                 OR rolreplication OR rolbypassrls) AND rolconnlimit=3;
    IF verifier IS NULL THEN
        RAISE EXCEPTION 'Provision the dedicated credential verifier before V10';
    END IF;
    IF EXISTS (SELECT FROM pg_auth_members WHERE member=verifier OR roleid=verifier)
       OR EXISTS (SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass
                   AND refobjid=verifier AND deptype='o')
       OR EXISTS (SELECT FROM pg_database WHERE datallowconn
                   AND (has_database_privilege(verifier,oid,'CREATE,TEMPORARY')
                        OR (datname<>current_database() AND has_database_privilege(verifier,oid,'CONNECT'))))
       OR NOT has_database_privilege(verifier,current_database(),'CONNECT')
       OR EXISTS (SELECT FROM pg_namespace WHERE has_schema_privilege(verifier,oid,'CREATE')) THEN
        RAISE EXCEPTION 'Credential verifier identity/privileges incompatible; no repair attempted';
    END IF;
    IF (SELECT count(*) FROM pg_class WHERE oid IN
         ('identity.users'::regclass,'identity.user_credentials'::regclass)
         AND relkind='r' AND relowner=current_user::regrole AND relrowsecurity AND relforcerowsecurity) <> 2
       OR EXISTS (SELECT FROM pg_policy WHERE polrelid IN
                   ('identity.users'::regclass,'identity.user_credentials'::regclass)) THEN
        RAISE EXCEPTION 'Expected unchanged V9 credential tables and policies';
    END IF;
    FOR relation IN
        SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname !~ '^pg_'
          AND c.relkind IN ('r','p','v','m','f')
    LOOP
        IF has_table_privilege(verifier,relation.oid,
                              'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
           OR has_any_column_privilege(verifier,relation.oid,'SELECT,INSERT,UPDATE,REFERENCES') THEN
            RAISE EXCEPTION 'Credential verifier has preexisting relation access; no repair attempted';
        END IF;
    END LOOP;
    IF has_any_column_privilege('ls_core_app_dev','identity.users','SELECT,INSERT,UPDATE,REFERENCES')
       OR has_any_column_privilege('ls_core_app_dev','identity.user_credentials','SELECT,INSERT,UPDATE,REFERENCES') THEN
        RAISE EXCEPTION 'Runtime identity privileges incompatible; no repair attempted';
    END IF;
END $$;

GRANT USAGE ON SCHEMA identity TO ls_core_credential_verifier_dev;
GRANT SELECT (id,username,status) ON identity.users TO ls_core_credential_verifier_dev;
GRANT SELECT (user_id,password_hash,password_expires_at,locked_until,must_change_password)
    ON identity.user_credentials TO ls_core_credential_verifier_dev;

-- Native authenticated LOGIN is the authority; no GUC, supplied UUID or SET ROLE grants access.
-- No policy subqueries, recursion, SECURITY DEFINER, writes or business-table grants.
CREATE POLICY credential_verifier_read ON identity.users FOR SELECT TO ls_core_credential_verifier_dev
    USING (session_user = current_user AND current_user = 'ls_core_credential_verifier_dev');
CREATE POLICY credential_verifier_read ON identity.user_credentials FOR SELECT TO ls_core_credential_verifier_dev
    USING (session_user = current_user AND current_user = 'ls_core_credential_verifier_dev');

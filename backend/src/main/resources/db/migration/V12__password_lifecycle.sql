-- LFD-32. Provision the two isolated LOGINs explicitly before applying V12 as the restricted migrator.
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
        ('ls_core_password_issuer_dev','ls_core_password_changer_dev') AND rolcanlogin
        AND NOT (rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
        AND rolconnlimit=3) <> 2 THEN
        RAISE EXCEPTION 'Provision isolated password service LOGINs before V12';
    END IF;
    IF (SELECT count(*) FROM pg_class WHERE oid IN
        ('identity.users'::regclass,'identity.user_credentials'::regclass)
        AND relowner=current_user::regrole AND relrowsecurity AND relforcerowsecurity) <> 2 THEN
        RAISE EXCEPTION 'Expected restricted owner and FORCE RLS';
    END IF;
    FOR principal IN SELECT oid FROM pg_roles WHERE rolname IN
        ('ls_core_password_issuer_dev','ls_core_password_changer_dev')
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

GRANT USAGE ON SCHEMA identity TO ls_core_password_issuer_dev,ls_core_password_changer_dev;
GRANT SELECT(id,status) ON identity.users TO ls_core_password_issuer_dev;
GRANT INSERT(user_id,password_hash,must_change_password,password_changed_at,password_expires_at,
             failed_attempts,locked_until) ON identity.user_credentials TO ls_core_password_issuer_dev;
CREATE POLICY password_issuer_users ON identity.users FOR SELECT TO ls_core_password_issuer_dev
    USING (session_user=current_user AND status='PENDING');
CREATE POLICY password_issuer_insert ON identity.user_credentials FOR INSERT TO ls_core_password_issuer_dev
    WITH CHECK (session_user=current_user AND must_change_password AND failed_attempts=0 AND locked_until IS NULL
        AND password_hash ~ '^\{pbkdf2-sha256-600000\}[0-9a-f]{96}$'
        AND password_expires_at>clock_timestamp()
        AND password_expires_at<=clock_timestamp()+interval '72 hours'
        AND EXISTS (SELECT FROM identity.users u WHERE u.id=user_id AND u.status='PENDING'));

GRANT SELECT(id,username,status) ON identity.users TO ls_core_password_changer_dev;
GRANT UPDATE(status,updated_at) ON identity.users TO ls_core_password_changer_dev;
GRANT SELECT(user_id,password_hash,must_change_password,password_expires_at,locked_until)
    ON identity.user_credentials TO ls_core_password_changer_dev;
GRANT UPDATE(password_hash,must_change_password,password_changed_at,password_expires_at,updated_at)
    ON identity.user_credentials TO ls_core_password_changer_dev;
CREATE POLICY password_changer_users_read ON identity.users FOR SELECT TO ls_core_password_changer_dev
    USING (session_user=current_user AND status IN ('PENDING','ACTIVE'));
CREATE POLICY password_changer_activate ON identity.users FOR UPDATE TO ls_core_password_changer_dev
    USING (session_user=current_user AND status IN ('PENDING','ACTIVE'))
    WITH CHECK (session_user=current_user AND status='ACTIVE' AND EXISTS
        (SELECT FROM identity.user_credentials c WHERE c.user_id=id AND NOT c.must_change_password
         AND c.password_expires_at>clock_timestamp()));
CREATE POLICY password_changer_read ON identity.user_credentials FOR SELECT TO ls_core_password_changer_dev
    USING (session_user=current_user);
CREATE POLICY password_changer_update ON identity.user_credentials FOR UPDATE TO ls_core_password_changer_dev
    USING (session_user=current_user)
    WITH CHECK (session_user=current_user AND NOT must_change_password
        AND password_hash ~ '^\{pbkdf2-sha256-600000\}[0-9a-f]{96}$'
        AND password_expires_at>clock_timestamp()
        AND password_expires_at<=clock_timestamp()+interval '90 days');

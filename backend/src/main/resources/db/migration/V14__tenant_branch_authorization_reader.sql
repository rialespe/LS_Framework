-- LFD-29. Provision the isolated authorization LOGIN explicitly before applying V14 as the restricted migrator.
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
    IF (SELECT count(*) FROM pg_roles WHERE rolname='ls_core_authorization_reader_dev' AND rolcanlogin
        AND NOT (rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
        AND rolconnlimit=3) <> 1 THEN
        RAISE EXCEPTION 'Provision isolated authorization reader LOGIN before V14';
    END IF;
    IF (SELECT count(*) FROM pg_class WHERE oid IN
        ('identity.users'::regclass,'tenant.tenants'::regclass,'tenant.memberships'::regclass,
         'tenant.branches'::regclass,'tenant.branch_memberships'::regclass,'catalog.tenant_applications'::regclass)
        AND relowner=current_user::regrole AND relrowsecurity AND relforcerowsecurity) <> 6 THEN
        RAISE EXCEPTION 'Expected restricted owner and FORCE RLS';
    END IF;
    FOR principal IN SELECT oid FROM pg_roles WHERE rolname='ls_core_authorization_reader_dev'
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

-- Bootstrap authorization metadata only. No runtime membership, business execution or GUC authority.
GRANT USAGE ON SCHEMA identity,tenant,catalog TO ls_core_authorization_reader_dev;
GRANT SELECT(id,status) ON identity.users TO ls_core_authorization_reader_dev;
GRANT SELECT(id,status) ON tenant.tenants TO ls_core_authorization_reader_dev;
GRANT SELECT(id,tenant_id,user_id,status) ON tenant.memberships TO ls_core_authorization_reader_dev;
GRANT SELECT(id,tenant_id,status) ON tenant.branches TO ls_core_authorization_reader_dev;
GRANT SELECT(id,tenant_id,membership_id,branch_id,status) ON tenant.branch_memberships TO ls_core_authorization_reader_dev;
GRANT SELECT(tenant_id,application_id,status) ON catalog.tenant_applications TO ls_core_authorization_reader_dev;
GRANT SELECT(id,status) ON catalog.applications TO ls_core_authorization_reader_dev;
CREATE POLICY authorization_metadata_read ON identity.users FOR SELECT TO ls_core_authorization_reader_dev
    USING (session_user=current_user AND current_user='ls_core_authorization_reader_dev');
CREATE POLICY authorization_metadata_read ON tenant.tenants FOR SELECT TO ls_core_authorization_reader_dev
    USING (session_user=current_user AND current_user='ls_core_authorization_reader_dev');
CREATE POLICY authorization_metadata_read ON tenant.memberships FOR SELECT TO ls_core_authorization_reader_dev
    USING (session_user=current_user AND current_user='ls_core_authorization_reader_dev');
CREATE POLICY authorization_metadata_read ON tenant.branches FOR SELECT TO ls_core_authorization_reader_dev
    USING (session_user=current_user AND current_user='ls_core_authorization_reader_dev');
CREATE POLICY authorization_metadata_read ON tenant.branch_memberships FOR SELECT TO ls_core_authorization_reader_dev
    USING (session_user=current_user AND current_user='ls_core_authorization_reader_dev');
CREATE POLICY authorization_metadata_read ON catalog.tenant_applications FOR SELECT TO ls_core_authorization_reader_dev
    USING (session_user=current_user AND current_user='ls_core_authorization_reader_dev');

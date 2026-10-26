-- LFD-29. Provision the isolated operation LOGINs explicitly before applying V15 as the restricted migrator.
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
    IF (SELECT count(*) FROM pg_roles WHERE rolname IN ('ls_core_branch_operation_dev','ls_core_operation_emitter_dev') AND rolcanlogin
        AND NOT (rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
        AND rolconnlimit=3) <> 2 THEN
        RAISE EXCEPTION 'Provision isolated operation LOGINs before V15';
    END IF;
    IF (SELECT count(*) FROM pg_class WHERE oid IN
        ('identity.users'::regclass,'tenant.tenants'::regclass,'tenant.memberships'::regclass,
         'tenant.branches'::regclass,'tenant.branch_memberships'::regclass,'catalog.tenant_applications'::regclass)
        AND relowner=current_user::regrole AND relrowsecurity AND relforcerowsecurity) <> 6 THEN
        RAISE EXCEPTION 'Expected restricted owner and FORCE RLS';
    END IF;
    FOR principal IN SELECT oid FROM pg_roles WHERE rolname IN ('ls_core_branch_operation_dev','ls_core_operation_emitter_dev')
    LOOP
        IF EXISTS (SELECT FROM pg_auth_members WHERE member=principal.oid OR roleid=principal.oid)
           OR EXISTS (SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass
                      AND refobjid=principal.oid AND deptype='o')
           OR EXISTS (SELECT FROM pg_database WHERE datallowconn AND
                      (has_database_privilege(principal.oid,oid,'CREATE,TEMPORARY') OR
                      (datname<>current_database() AND has_database_privilege(principal.oid,oid,'CONNECT'))))
           OR NOT has_database_privilege(principal.oid,current_database(),'CONNECT')
           OR EXISTS (SELECT FROM pg_namespace WHERE has_schema_privilege(principal.oid,oid,'CREATE')) THEN
            RAISE EXCEPTION 'Operation role privileges incompatible; no repair attempted';
        END IF;
        FOR relation IN SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname !~ '^pg_'
              AND c.relkind IN ('r','p','v','m','f')
        LOOP
            IF has_table_privilege(principal.oid,relation.oid,
                'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
               OR has_any_column_privilege(principal.oid,relation.oid,'SELECT,INSERT,UPDATE,REFERENCES') THEN
                RAISE EXCEPTION 'Operation role has preexisting relation access; no repair attempted';
            END IF;
        END LOOP;
    END LOOP;
END $$;

-- Native transaction-bound evidence, not a bearer token/session. The operation LOGIN cannot write it.
CREATE TABLE security.branch_read_admissions (
    database_oid OID NOT NULL,
    login_oid OID NOT NULL,
    backend_pid INTEGER NOT NULL,
    backend_start TIMESTAMPTZ NOT NULL,
    transaction_id XID8 NOT NULL,
    tenant_id UUID NOT NULL,
    application_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    branch_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()+interval '10 seconds',
    PRIMARY KEY(database_oid,login_oid,backend_pid,backend_start,transaction_id),
    CHECK (expires_at>created_at AND expires_at<=created_at+interval '15 seconds')
);
ALTER TABLE security.branch_read_admissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE security.branch_read_admissions FORCE ROW LEVEL SECURITY;
GRANT USAGE ON SCHEMA security TO ls_core_operation_emitter_dev,ls_core_branch_operation_dev;
GRANT INSERT(database_oid,login_oid,backend_pid,backend_start,transaction_id,tenant_id,application_id,branch_id,
             actor_id,membership_id,branch_membership_id) ON security.branch_read_admissions TO ls_core_operation_emitter_dev;
GRANT SELECT(database_oid,login_oid,backend_pid,backend_start,transaction_id),DELETE
    ON security.branch_read_admissions TO ls_core_operation_emitter_dev;
CREATE POLICY branch_admission_issue ON security.branch_read_admissions FOR INSERT TO ls_core_operation_emitter_dev
    WITH CHECK (session_user=current_user AND current_user='ls_core_operation_emitter_dev'
        AND database_oid=(SELECT oid FROM pg_catalog.pg_database WHERE datname=current_database())
        AND login_oid=(SELECT oid FROM pg_catalog.pg_roles WHERE rolname='ls_core_branch_operation_dev')
        AND backend_pid>0 AND transaction_id<>'0'::xid8);
CREATE POLICY branch_admission_cleanup_read ON security.branch_read_admissions FOR SELECT TO ls_core_operation_emitter_dev
    USING (session_user=current_user AND current_user='ls_core_operation_emitter_dev');
CREATE POLICY branch_admission_cleanup ON security.branch_read_admissions FOR DELETE TO ls_core_operation_emitter_dev
    USING (session_user=current_user AND current_user='ls_core_operation_emitter_dev');
GRANT SELECT(database_oid,login_oid,backend_pid,backend_start,transaction_id,tenant_id,application_id,branch_id,
             actor_id,membership_id,branch_membership_id) ON security.branch_read_admissions TO ls_core_branch_operation_dev;
CREATE POLICY branch_admission_native_read ON security.branch_read_admissions FOR SELECT TO ls_core_branch_operation_dev
    USING (session_user=current_user AND current_user='ls_core_branch_operation_dev'
        AND database_oid=(SELECT oid FROM pg_catalog.pg_database WHERE datname=current_database())
        AND login_oid=(SELECT oid FROM pg_catalog.pg_roles WHERE rolname=session_user)
        AND backend_pid=pg_catalog.pg_backend_pid()
        AND backend_start=(SELECT backend_start FROM pg_catalog.pg_stat_activity WHERE pid=pg_catalog.pg_backend_pid())
        AND transaction_id=pg_catalog.pg_current_xact_id_if_assigned()
        AND expires_at>pg_catalog.clock_timestamp()
        AND pg_catalog.current_setting('transaction_isolation')='read committed'
        AND pg_catalog.current_setting('transaction_read_only')='on');

GRANT USAGE ON SCHEMA tenant TO ls_core_branch_operation_dev;
GRANT SELECT(id,tenant_id,code,name) ON tenant.branches TO ls_core_branch_operation_dev;
CREATE POLICY admitted_single_branch_read ON tenant.branches FOR SELECT TO ls_core_branch_operation_dev
    USING (session_user=current_user AND current_user='ls_core_branch_operation_dev' AND EXISTS (
        SELECT FROM security.branch_read_admissions e
        WHERE e.tenant_id=branches.tenant_id AND e.branch_id=branches.id));
-- No grants/memberships to ls_core_app_dev, no general business access, no SECURITY DEFINER or ls.* authority.

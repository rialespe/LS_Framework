-- LFD-35: extend the V16 technical channel with bounded context metadata only.
-- No new login, business execution rights, GUC authority or session operation admission.
DO $$
BEGIN
    IF current_user<>'ls_core_migrator_dev' OR session_user<>current_user
       OR current_database()<>'ls_core_dev'
       OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999 THEN
        RAISE EXCEPTION 'Expected restricted migrator on PostgreSQL 17 ls_core_dev';
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles r WHERE rolname='ls_core_session_manager_dev'
        AND rolcanlogin AND rolconnlimit=3
        AND NOT (rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
        AND NOT EXISTS (SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
        AND NOT EXISTS (SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o')) THEN
        RAISE EXCEPTION 'Expected isolated V16 session manager';
    END IF;
    IF (SELECT count(*) FROM pg_class WHERE oid IN (
        'tenant.tenants'::regclass,
        'tenant.memberships'::regclass,
        'tenant.branches'::regclass,
        'tenant.branch_memberships'::regclass,
        'catalog.tenant_applications'::regclass,
        'catalog.tenant_modules'::regclass,
        'security.branch_profile_assignments'::regclass,
        'security.profiles'::regclass,
        'security.profile_roles'::regclass,
        'security.roles'::regclass,
        'security.role_permissions'::regclass,
        'security.session_contexts'::regclass)
        AND relowner=current_user::regrole AND relrowsecurity AND relforcerowsecurity)<>12 THEN
        RAISE EXCEPTION 'Expected restricted ownership and FORCE RLS';
    END IF;
END $$;

CREATE UNIQUE INDEX session_contexts_one_active_uq ON security.session_contexts(session_id) WHERE revoked_at IS NULL;
GRANT USAGE ON SCHEMA tenant TO ls_core_session_manager_dev;
GRANT SELECT(id,name,status) ON tenant.tenants TO ls_core_session_manager_dev;
GRANT SELECT(id,tenant_id,user_id,status) ON tenant.memberships TO ls_core_session_manager_dev;
GRANT SELECT(id,tenant_id,code,name,status) ON tenant.branches TO ls_core_session_manager_dev;
GRANT SELECT(id,tenant_id,membership_id,branch_id,status) ON tenant.branch_memberships TO ls_core_session_manager_dev;
GRANT SELECT(tenant_id,application_id,status) ON catalog.tenant_applications TO ls_core_session_manager_dev;
GRANT SELECT(tenant_id,application_id,module_id,status) ON catalog.tenant_modules TO ls_core_session_manager_dev;
GRANT SELECT(id,application_id,code,name,status) ON catalog.modules TO ls_core_session_manager_dev;
GRANT SELECT(tenant_id,application_id,branch_membership_id,profile_id,status,revoked_at) ON security.branch_profile_assignments TO ls_core_session_manager_dev;
GRANT SELECT(id,tenant_id,application_id,status) ON security.profiles TO ls_core_session_manager_dev;
GRANT SELECT(tenant_id,application_id,profile_id,role_id) ON security.profile_roles TO ls_core_session_manager_dev;
GRANT SELECT(id,tenant_id,application_id,status) ON security.roles TO ls_core_session_manager_dev;
GRANT SELECT(tenant_id,application_id,role_id,permission_id) ON security.role_permissions TO ls_core_session_manager_dev;
GRANT SELECT(id,application_id,module_id,code,name,resource_scope,status) ON security.permissions TO ls_core_session_manager_dev;
GRANT SELECT(id,session_id,user_id,application_id,tenant_id,membership_id,branch_id,expires_at,revoked_at) ON security.session_contexts TO ls_core_session_manager_dev;
CREATE POLICY session_context_metadata_read ON tenant.tenants FOR SELECT TO ls_core_session_manager_dev
    USING (session_user=current_user AND current_user='ls_core_session_manager_dev');
CREATE POLICY session_context_metadata_read ON tenant.memberships FOR SELECT TO ls_core_session_manager_dev
    USING (session_user=current_user AND current_user='ls_core_session_manager_dev');
CREATE POLICY session_context_metadata_read ON tenant.branches FOR SELECT TO ls_core_session_manager_dev
    USING (session_user=current_user AND current_user='ls_core_session_manager_dev');
CREATE POLICY session_context_metadata_read ON tenant.branch_memberships FOR SELECT TO ls_core_session_manager_dev
    USING (session_user=current_user AND current_user='ls_core_session_manager_dev');
CREATE POLICY session_context_metadata_read ON catalog.tenant_applications FOR SELECT TO ls_core_session_manager_dev
    USING (session_user=current_user AND current_user='ls_core_session_manager_dev');
CREATE POLICY session_context_metadata_read ON catalog.tenant_modules FOR SELECT TO ls_core_session_manager_dev
    USING (session_user=current_user AND current_user='ls_core_session_manager_dev');
CREATE POLICY session_context_metadata_read ON security.branch_profile_assignments FOR SELECT TO ls_core_session_manager_dev
    USING (session_user=current_user AND current_user='ls_core_session_manager_dev');
CREATE POLICY session_context_metadata_read ON security.profiles FOR SELECT TO ls_core_session_manager_dev
    USING (session_user=current_user AND current_user='ls_core_session_manager_dev');
CREATE POLICY session_context_metadata_read ON security.profile_roles FOR SELECT TO ls_core_session_manager_dev
    USING (session_user=current_user AND current_user='ls_core_session_manager_dev');
CREATE POLICY session_context_metadata_read ON security.roles FOR SELECT TO ls_core_session_manager_dev
    USING (session_user=current_user AND current_user='ls_core_session_manager_dev');
CREATE POLICY session_context_metadata_read ON security.role_permissions FOR SELECT TO ls_core_session_manager_dev
    USING (session_user=current_user AND current_user='ls_core_session_manager_dev');
CREATE POLICY session_context_metadata_read ON security.session_contexts FOR SELECT TO ls_core_session_manager_dev
    USING (session_user=current_user AND current_user='ls_core_session_manager_dev');
GRANT INSERT(id,session_id,user_id,application_id,tenant_id,membership_id,branch_id,expires_at)
    ON security.session_contexts TO ls_core_session_manager_dev;
GRANT UPDATE(revoked_at,updated_at) ON security.session_contexts TO ls_core_session_manager_dev;
CREATE POLICY session_context_issue ON security.session_contexts FOR INSERT TO ls_core_session_manager_dev
    WITH CHECK (session_user=current_user AND current_user='ls_core_session_manager_dev' AND revoked_at IS NULL
        AND expires_at>clock_timestamp()
        AND EXISTS (SELECT FROM security.sessions s
            JOIN identity.users u ON u.id=s.user_id
            JOIN catalog.applications a ON a.id=s.application_id
            JOIN tenant.memberships m ON m.user_id=u.id AND m.tenant_id=session_contexts.tenant_id
            JOIN tenant.tenants t ON t.id=m.tenant_id
            JOIN tenant.branches b ON b.tenant_id=t.id AND b.id=session_contexts.branch_id
            JOIN tenant.branch_memberships bm ON bm.tenant_id=t.id AND bm.membership_id=m.id AND bm.branch_id=b.id
            JOIN catalog.tenant_applications ta ON ta.tenant_id=t.id AND ta.application_id=a.id
            WHERE s.id=session_contexts.session_id AND s.user_id=session_contexts.user_id
              AND s.application_id=session_contexts.application_id AND m.id=session_contexts.membership_id
              AND s.revoked_at IS NULL AND s.expires_at>=session_contexts.expires_at
              AND u.status='ACTIVE' AND t.status='ACTIVE' AND m.status='ACTIVE' AND b.status='ACTIVE'
              AND bm.status='ACTIVE' AND a.status='PUBLISHED' AND ta.status='ACTIVE'));
CREATE POLICY session_context_revoke ON security.session_contexts FOR UPDATE TO ls_core_session_manager_dev
    USING (session_user=current_user AND current_user='ls_core_session_manager_dev' AND revoked_at IS NULL)
    WITH CHECK (session_user=current_user AND current_user='ls_core_session_manager_dev' AND revoked_at IS NOT NULL);
-- All earlier RLS policies and migrations remain intact. No administrative metadata writes are granted.

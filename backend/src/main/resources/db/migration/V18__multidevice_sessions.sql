-- LFD-36: V16 technical session channel only. No business execution or GUC authority.
DO $$ BEGIN
    IF session_user<>current_user OR current_user<>'ls_core_migrator_dev'
       OR current_database()<>'ls_core_dev'
       OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999 THEN
        RAISE EXCEPTION 'Expected restricted PG17 migrator';
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles r WHERE rolname='ls_core_session_manager_dev'
        AND rolcanlogin AND rolconnlimit=3
        AND NOT (rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication)
        AND NOT EXISTS (SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
        AND NOT EXISTS (SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o'))
       OR (SELECT count(*) FROM pg_class WHERE oid IN
           ('security.sessions'::regclass,'security.session_contexts'::regclass,'tenant.administrators'::regclass)
           AND relowner=current_user::regrole AND relrowsecurity AND relforcerowsecurity)<>3
       OR to_regclass('security.session_contexts_one_active_uq') IS NULL THEN
        RAISE EXCEPTION 'Expected isolated V17 session role and FORCE RLS';
    END IF;
END $$;

GRANT SELECT(last_seen_at) ON security.sessions TO ls_core_session_manager_dev;
GRANT INSERT(last_seen_at) ON security.sessions TO ls_core_session_manager_dev;
GRANT UPDATE(last_seen_at) ON security.sessions TO ls_core_session_manager_dev;
-- A revoked row cannot be targeted by either UPDATE policy; activity never changes expires_at.
CREATE POLICY session_activity ON security.sessions FOR UPDATE TO ls_core_session_manager_dev
    USING (session_user=current_user AND current_user='ls_core_session_manager_dev' AND revoked_at IS NULL)
    WITH CHECK (session_user=current_user AND current_user='ls_core_session_manager_dev' AND revoked_at IS NULL
        AND expires_at>clock_timestamp() AND last_seen_at>=created_at AND last_seen_at<=clock_timestamp());

GRANT SELECT(tenant_id,membership_id,status,revoked_at) ON tenant.administrators TO ls_core_session_manager_dev;
CREATE POLICY session_tenant_admin_read ON tenant.administrators FOR SELECT TO ls_core_session_manager_dev
    USING (session_user=current_user AND current_user='ls_core_session_manager_dev');

CREATE TABLE security.session_tenant_revocations (
    session_id UUID NOT NULL,
    user_id UUID NOT NULL,
    application_id UUID NOT NULL,
    tenant_id UUID NOT NULL REFERENCES tenant.tenants(id),
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    revoked_by_user_id UUID NOT NULL REFERENCES identity.users(id),
    PRIMARY KEY(session_id,tenant_id),
    FOREIGN KEY(session_id,user_id,application_id) REFERENCES security.sessions(id,user_id,application_id)
);
ALTER TABLE security.session_tenant_revocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE security.session_tenant_revocations FORCE ROW LEVEL SECURITY;
CREATE INDEX session_tenant_revocations_scope_idx ON security.session_tenant_revocations(tenant_id,application_id);
GRANT SELECT(session_id,user_id,application_id,tenant_id,revoked_at,revoked_by_user_id)
    ON security.session_tenant_revocations TO ls_core_session_manager_dev;
GRANT INSERT(session_id,user_id,application_id,tenant_id,revoked_by_user_id)
    ON security.session_tenant_revocations TO ls_core_session_manager_dev;
CREATE POLICY session_tenant_barrier_read ON security.session_tenant_revocations FOR SELECT TO ls_core_session_manager_dev
    USING (session_user=current_user AND current_user='ls_core_session_manager_dev');
CREATE POLICY session_tenant_barrier_issue ON security.session_tenant_revocations FOR INSERT TO ls_core_session_manager_dev
    WITH CHECK (session_user=current_user AND current_user='ls_core_session_manager_dev'
        AND EXISTS (SELECT FROM security.sessions s WHERE s.id=session_tenant_revocations.session_id
            AND s.user_id=session_tenant_revocations.user_id AND s.application_id=session_tenant_revocations.application_id)
        AND EXISTS (SELECT FROM security.session_contexts sc WHERE sc.session_id=session_tenant_revocations.session_id
            AND sc.tenant_id=session_tenant_revocations.tenant_id AND sc.application_id=session_tenant_revocations.application_id)
        AND EXISTS (SELECT FROM tenant.administrators ad
            JOIN tenant.memberships m ON m.tenant_id=ad.tenant_id AND m.id=ad.membership_id
            JOIN tenant.tenants t ON t.id=m.tenant_id
            JOIN catalog.tenant_applications ta ON ta.tenant_id=t.id
            WHERE ad.tenant_id=session_tenant_revocations.tenant_id AND ad.status='ACTIVE' AND ad.revoked_at IS NULL
              AND m.user_id=session_tenant_revocations.revoked_by_user_id AND m.status='ACTIVE' AND t.status='ACTIVE'
              AND ta.application_id=session_tenant_revocations.application_id AND ta.status='ACTIVE'));
-- A fresh context cannot undo the immutable pair-specific barrier. No UPDATE/DELETE grant exists on barriers.
CREATE POLICY session_context_tenant_barrier ON security.session_contexts AS RESTRICTIVE FOR INSERT TO ls_core_session_manager_dev
    WITH CHECK (NOT EXISTS (SELECT FROM security.session_tenant_revocations rv
        WHERE rv.session_id=session_contexts.session_id AND rv.tenant_id=session_contexts.tenant_id));

-- LFD-25: approved physical dictionary, Jira comment 10044.
-- Tenant/application composite integrity and final uniqueness are reserved for LFD-26.
-- No seeds, runtime grants, business defaults or cascading deletes. Flyway manages the transaction.

CREATE TABLE security.roles (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    application_id UUID NOT NULL,
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    status TEXT NOT NULL,
    source_role_template_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT roles_pk PRIMARY KEY (id),
    CONSTRAINT roles_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT roles_application_id_fk FOREIGN KEY (application_id) REFERENCES catalog.applications (id),
    CONSTRAINT roles_source_role_template_id_fk FOREIGN KEY (source_role_template_id) REFERENCES security.role_templates (id)
);

CREATE TABLE security.role_permissions (
    tenant_id UUID NOT NULL,
    application_id UUID NOT NULL,
    role_id UUID NOT NULL,
    permission_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT role_permissions_pk PRIMARY KEY (tenant_id, role_id, permission_id),
    CONSTRAINT role_permissions_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT role_permissions_application_id_fk FOREIGN KEY (application_id) REFERENCES catalog.applications (id),
    CONSTRAINT role_permissions_permission_id_fk FOREIGN KEY (permission_id) REFERENCES security.permissions (id)
);

CREATE TABLE security.profiles (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    application_id UUID NOT NULL,
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    status TEXT NOT NULL,
    source_profile_template_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT profiles_pk PRIMARY KEY (id),
    CONSTRAINT profiles_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT profiles_application_id_fk FOREIGN KEY (application_id) REFERENCES catalog.applications (id),
    CONSTRAINT profiles_source_profile_template_id_fk FOREIGN KEY (source_profile_template_id) REFERENCES security.profile_templates (id)
);

CREATE TABLE security.profile_roles (
    tenant_id UUID NOT NULL,
    application_id UUID NOT NULL,
    profile_id UUID NOT NULL,
    role_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT profile_roles_pk PRIMARY KEY (tenant_id, profile_id, role_id),
    CONSTRAINT profile_roles_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT profile_roles_application_id_fk FOREIGN KEY (application_id) REFERENCES catalog.applications (id)
);

CREATE TABLE security.branch_profile_assignments (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    application_id UUID NOT NULL,
    branch_membership_id UUID NOT NULL,
    profile_id UUID NOT NULL,
    status TEXT NOT NULL,
    granted_by_user_id UUID,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT branch_profile_assignments_pk PRIMARY KEY (id),
    CONSTRAINT branch_profile_assignments_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT branch_profile_assignments_application_id_fk FOREIGN KEY (application_id) REFERENCES catalog.applications (id),
    CONSTRAINT branch_profile_assignments_granted_by_user_id_fk FOREIGN KEY (granted_by_user_id) REFERENCES identity.users (id)
);

CREATE TABLE security.sessions (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    application_id UUID NOT NULL,
    token_hash TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revoked_by_user_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT sessions_pk PRIMARY KEY (id),
    CONSTRAINT sessions_user_id_fk FOREIGN KEY (user_id) REFERENCES identity.users (id),
    CONSTRAINT sessions_application_id_fk FOREIGN KEY (application_id) REFERENCES catalog.applications (id),
    CONSTRAINT sessions_revoked_by_user_id_fk FOREIGN KEY (revoked_by_user_id) REFERENCES identity.users (id)
);

CREATE TABLE security.session_contexts (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL,
    user_id UUID NOT NULL,
    application_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT session_contexts_pk PRIMARY KEY (id),
    CONSTRAINT session_contexts_user_id_fk FOREIGN KEY (user_id) REFERENCES identity.users (id),
    CONSTRAINT session_contexts_application_id_fk FOREIGN KEY (application_id) REFERENCES catalog.applications (id),
    CONSTRAINT session_contexts_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id)
);

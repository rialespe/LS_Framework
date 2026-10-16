-- LFD-25: approved physical dictionary, Jira comment 10044.
-- Tenant/application composite integrity and final uniqueness are reserved for LFD-26.
-- No seeds, runtime grants, business defaults or cascading deletes. Flyway manages the transaction.

CREATE TABLE tenant.tenants (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT tenants_pk PRIMARY KEY (id),
    CONSTRAINT tenants_status_ck CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED'))
);

CREATE TABLE tenant.branches (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT branches_pk PRIMARY KEY (id),
    CONSTRAINT branches_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id)
);

CREATE TABLE tenant.memberships (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    status TEXT NOT NULL,
    invited_by_user_id UUID,
    accepted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT memberships_pk PRIMARY KEY (id),
    CONSTRAINT memberships_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT memberships_user_id_fk FOREIGN KEY (user_id) REFERENCES identity.users (id),
    CONSTRAINT memberships_invited_by_user_id_fk FOREIGN KEY (invited_by_user_id) REFERENCES identity.users (id)
);

CREATE TABLE tenant.branch_memberships (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT branch_memberships_pk PRIMARY KEY (id),
    CONSTRAINT branch_memberships_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id)
);

CREATE TABLE tenant.administrators (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    status TEXT NOT NULL,
    granted_by_user_id UUID,
    granted_at TIMESTAMPTZ NOT NULL,
    revoked_by_user_id UUID,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT administrators_pk PRIMARY KEY (id),
    CONSTRAINT administrators_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT administrators_granted_by_user_id_fk FOREIGN KEY (granted_by_user_id) REFERENCES identity.users (id),
    CONSTRAINT administrators_revoked_by_user_id_fk FOREIGN KEY (revoked_by_user_id) REFERENCES identity.users (id)
);

CREATE TABLE catalog.tenant_applications (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    application_id UUID NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT tenant_applications_pk PRIMARY KEY (id),
    CONSTRAINT tenant_applications_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT tenant_applications_application_id_fk FOREIGN KEY (application_id) REFERENCES catalog.applications (id),
    CONSTRAINT tenant_applications_status_ck CHECK (status IN ('ACTIVE', 'READ_ONLY', 'DISABLED'))
);

CREATE TABLE catalog.tenant_modules (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    application_id UUID NOT NULL,
    module_id UUID NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT tenant_modules_pk PRIMARY KEY (id),
    CONSTRAINT tenant_modules_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT tenant_modules_application_id_fk FOREIGN KEY (application_id) REFERENCES catalog.applications (id),
    CONSTRAINT tenant_modules_module_id_fk FOREIGN KEY (module_id) REFERENCES catalog.modules (id),
    CONSTRAINT tenant_modules_status_ck CHECK (status IN ('ACTIVE', 'READ_ONLY', 'DISABLED'))
);

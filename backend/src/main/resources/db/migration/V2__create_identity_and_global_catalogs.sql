-- LFD-25: approved physical dictionary, Jira comment 10044.
-- Tenant/application composite integrity and final uniqueness are reserved for LFD-26.
-- No seeds, runtime grants, business defaults or cascading deletes. Flyway manages the transaction.

CREATE TABLE identity.users (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    username TEXT,
    email TEXT,
    email_verified_at TIMESTAMPTZ,
    display_name TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT users_pk PRIMARY KEY (id),
    CONSTRAINT users_identifier_ck CHECK (username IS NOT NULL OR email IS NOT NULL)
);

CREATE TABLE identity.user_credentials (
    user_id UUID NOT NULL,
    password_hash TEXT NOT NULL,
    must_change_password BOOLEAN NOT NULL,
    password_changed_at TIMESTAMPTZ NOT NULL,
    password_expires_at TIMESTAMPTZ NOT NULL,
    failed_attempts INTEGER NOT NULL,
    locked_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT user_credentials_pk PRIMARY KEY (user_id),
    CONSTRAINT user_credentials_user_id_fk FOREIGN KEY (user_id) REFERENCES identity.users (id)
);

CREATE TABLE platform.administrators (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    administrator_kind TEXT NOT NULL,
    status TEXT NOT NULL,
    granted_by_user_id UUID,
    revoked_by_user_id UUID,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT administrators_pk PRIMARY KEY (id),
    CONSTRAINT administrators_user_id_fk FOREIGN KEY (user_id) REFERENCES identity.users (id),
    CONSTRAINT administrators_granted_by_user_id_fk FOREIGN KEY (granted_by_user_id) REFERENCES identity.users (id),
    CONSTRAINT administrators_revoked_by_user_id_fk FOREIGN KEY (revoked_by_user_id) REFERENCES identity.users (id),
    CONSTRAINT administrators_kind_ck CHECK (administrator_kind IN ('OWNER', 'SUPERADMIN'))
);

CREATE TABLE catalog.applications (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT applications_pk PRIMARY KEY (id)
);

CREATE TABLE catalog.modules (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL,
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT modules_pk PRIMARY KEY (id),
    CONSTRAINT modules_application_id_fk FOREIGN KEY (application_id) REFERENCES catalog.applications (id)
);

CREATE TABLE security.permissions (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL,
    module_id UUID,
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    resource_scope TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT permissions_pk PRIMARY KEY (id),
    CONSTRAINT permissions_application_id_fk FOREIGN KEY (application_id) REFERENCES catalog.applications (id),
    CONSTRAINT permissions_module_id_fk FOREIGN KEY (module_id) REFERENCES catalog.modules (id)
);

CREATE TABLE security.role_templates (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL,
    code TEXT NOT NULL,
    version INTEGER NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT role_templates_pk PRIMARY KEY (id),
    CONSTRAINT role_templates_application_id_fk FOREIGN KEY (application_id) REFERENCES catalog.applications (id)
);

CREATE TABLE security.role_template_permissions (
    application_id UUID NOT NULL,
    role_template_id UUID NOT NULL,
    permission_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT role_template_permissions_pk PRIMARY KEY (role_template_id, permission_id),
    CONSTRAINT role_template_permissions_application_id_fk FOREIGN KEY (application_id) REFERENCES catalog.applications (id),
    CONSTRAINT role_template_permissions_role_template_id_fk FOREIGN KEY (role_template_id) REFERENCES security.role_templates (id),
    CONSTRAINT role_template_permissions_permission_id_fk FOREIGN KEY (permission_id) REFERENCES security.permissions (id)
);

CREATE TABLE security.profile_templates (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL,
    code TEXT NOT NULL,
    version INTEGER NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT profile_templates_pk PRIMARY KEY (id),
    CONSTRAINT profile_templates_application_id_fk FOREIGN KEY (application_id) REFERENCES catalog.applications (id)
);

CREATE TABLE security.profile_template_roles (
    application_id UUID NOT NULL,
    profile_template_id UUID NOT NULL,
    role_template_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT profile_template_roles_pk PRIMARY KEY (profile_template_id, role_template_id),
    CONSTRAINT profile_template_roles_application_id_fk FOREIGN KEY (application_id) REFERENCES catalog.applications (id),
    CONSTRAINT profile_template_roles_profile_template_id_fk FOREIGN KEY (profile_template_id) REFERENCES security.profile_templates (id),
    CONSTRAINT profile_template_roles_role_template_id_fk FOREIGN KEY (role_template_id) REFERENCES security.role_templates (id)
);

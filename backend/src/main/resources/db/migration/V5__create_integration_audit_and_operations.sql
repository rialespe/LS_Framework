-- LFD-25: approved physical dictionary, Jira comment 10044.
-- Tenant/application composite integrity and final uniqueness are reserved for LFD-26.
-- No seeds, runtime grants, business defaults or cascading deletes. Flyway manages the transaction.

CREATE TABLE integration.api_keys (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    application_id UUID NOT NULL,
    branch_id UUID,
    name TEXT NOT NULL,
    key_hash TEXT NOT NULL,
    created_by_user_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoked_by_user_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT api_keys_pk PRIMARY KEY (id),
    CONSTRAINT api_keys_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT api_keys_application_id_fk FOREIGN KEY (application_id) REFERENCES catalog.applications (id),
    CONSTRAINT api_keys_created_by_user_id_fk FOREIGN KEY (created_by_user_id) REFERENCES identity.users (id),
    CONSTRAINT api_keys_revoked_by_user_id_fk FOREIGN KEY (revoked_by_user_id) REFERENCES identity.users (id)
);

CREATE TABLE integration.api_key_permissions (
    tenant_id UUID NOT NULL,
    application_id UUID NOT NULL,
    api_key_id UUID NOT NULL,
    permission_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT api_key_permissions_pk PRIMARY KEY (tenant_id, api_key_id, permission_id),
    CONSTRAINT api_key_permissions_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT api_key_permissions_application_id_fk FOREIGN KEY (application_id) REFERENCES catalog.applications (id),
    CONSTRAINT api_key_permissions_permission_id_fk FOREIGN KEY (permission_id) REFERENCES security.permissions (id)
);

CREATE TABLE audit.events (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    occurred_at TIMESTAMPTZ NOT NULL,
    tenant_id UUID,
    application_id UUID,
    actor_kind TEXT NOT NULL,
    actor_user_id UUID,
    actor_api_key_id UUID,
    session_id UUID,
    action_code TEXT NOT NULL,
    resource_type TEXT,
    resource_id UUID,
    outcome TEXT NOT NULL,
    correlation_id UUID,
    metadata JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT events_pk PRIMARY KEY (id)
);

CREATE TABLE operations.maintenance_windows (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    reason TEXT NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ,
    status TEXT NOT NULL,
    requested_by_user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT maintenance_windows_pk PRIMARY KEY (id),
    CONSTRAINT maintenance_windows_requested_by_user_id_fk FOREIGN KEY (requested_by_user_id) REFERENCES identity.users (id)
);

CREATE TABLE operations.operation_runs (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    operation_type TEXT NOT NULL,
    status TEXT NOT NULL,
    requested_by_user_id UUID,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    result_code TEXT,
    correlation_id UUID,
    maintenance_window_id UUID,
    artifact_reference TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT operation_runs_pk PRIMARY KEY (id),
    CONSTRAINT operation_runs_requested_by_user_id_fk FOREIGN KEY (requested_by_user_id) REFERENCES identity.users (id),
    CONSTRAINT operation_runs_maintenance_window_id_fk FOREIGN KEY (maintenance_window_id) REFERENCES operations.maintenance_windows (id)
);

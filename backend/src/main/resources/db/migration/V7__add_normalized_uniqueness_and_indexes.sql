-- LFD-26. PostgreSQL 17 / administrative Flyway only; no seeds, grants or runtime behavior.
-- Flyway wraps this DDL in a transaction; incompatible existing data must fail closed.

-- Presence checks extend V2 without changing historical migrations or business lifecycle vocabularies.
ALTER TABLE identity.users ADD CONSTRAINT users_username_nonblank_ck CHECK (username IS NULL OR btrim(username) <> '');
ALTER TABLE identity.users ADD CONSTRAINT users_email_nonblank_ck CHECK (email IS NULL OR btrim(email) <> '');
ALTER TABLE identity.users ADD CONSTRAINT users_verified_email_ck CHECK (email_verified_at IS NULL OR (email IS NOT NULL AND btrim(email) <> ''));
ALTER TABLE tenant.branches ADD CONSTRAINT branches_code_nonblank_ck CHECK (btrim(code) <> '');
ALTER TABLE catalog.applications ADD CONSTRAINT applications_code_nonblank_ck CHECK (btrim(code) <> '');
ALTER TABLE catalog.modules ADD CONSTRAINT modules_code_nonblank_ck CHECK (btrim(code) <> '');
ALTER TABLE security.permissions ADD CONSTRAINT permissions_code_nonblank_ck CHECK (btrim(code) <> '');
ALTER TABLE security.role_templates ADD CONSTRAINT role_templates_code_nonblank_ck CHECK (btrim(code) <> '');
ALTER TABLE security.profile_templates ADD CONSTRAINT profile_templates_code_nonblank_ck CHECK (btrim(code) <> '');
ALTER TABLE security.roles ADD CONSTRAINT roles_code_nonblank_ck CHECK (btrim(code) <> '');
ALTER TABLE security.profiles ADD CONSTRAINT profiles_code_nonblank_ck CHECK (btrim(code) <> '');

-- Natural, non-partial uniqueness. Entitlement/access candidate keys in V6 already prevent duplicates.
ALTER TABLE tenant.memberships ADD CONSTRAINT memberships_tenant_user_uq UNIQUE (tenant_id,user_id);
ALTER TABLE catalog.tenant_modules ADD CONSTRAINT tenant_modules_scope_uq UNIQUE (tenant_id,application_id,module_id);

-- Normalization is exactly lower(btrim(value)): database collation, no provider/Unicode transformations.
CREATE UNIQUE INDEX users_username_normalized_uq ON identity.users (lower(btrim(username))) WHERE username IS NOT NULL;
CREATE UNIQUE INDEX users_verified_email_normalized_uq ON identity.users (lower(btrim(email))) WHERE email_verified_at IS NOT NULL;
CREATE UNIQUE INDEX branches_tenant_code_normalized_uq ON tenant.branches (tenant_id,lower(btrim(code)));
CREATE UNIQUE INDEX applications_code_normalized_uq ON catalog.applications (lower(btrim(code)));
CREATE UNIQUE INDEX modules_application_code_normalized_uq ON catalog.modules (application_id,lower(btrim(code)));
CREATE UNIQUE INDEX permissions_application_code_normalized_uq ON security.permissions (application_id,lower(btrim(code)));
CREATE UNIQUE INDEX role_templates_code_version_normalized_uq ON security.role_templates (application_id,lower(btrim(code)),version);
CREATE UNIQUE INDEX profile_templates_code_version_normalized_uq ON security.profile_templates (application_id,lower(btrim(code)),version);
CREATE UNIQUE INDEX roles_tenant_application_code_normalized_uq ON security.roles (tenant_id,application_id,lower(btrim(code)));
CREATE UNIQUE INDEX profiles_tenant_application_code_normalized_uq ON security.profiles (tenant_id,application_id,lower(btrim(code)));

-- Child-side FK lookups not already supported effectively by a primary/candidate-key prefix.
CREATE INDEX branch_memberships_branch_scope_idx ON tenant.branch_memberships (tenant_id,branch_id);
CREATE INDEX administrators_membership_scope_idx ON tenant.administrators (tenant_id,membership_id);
CREATE INDEX tenant_modules_module_scope_idx ON catalog.tenant_modules (application_id,module_id);
CREATE INDEX permissions_module_scope_idx ON security.permissions (application_id,module_id);
CREATE INDEX role_template_permissions_permission_idx ON security.role_template_permissions (permission_id,application_id);
CREATE INDEX profile_template_roles_role_idx ON security.profile_template_roles (role_template_id,application_id);
CREATE INDEX roles_source_template_scope_idx ON security.roles (source_role_template_id,application_id);
CREATE INDEX profiles_source_template_scope_idx ON security.profiles (source_profile_template_id,application_id);
CREATE INDEX role_permissions_permission_scope_idx ON security.role_permissions (permission_id,application_id);
CREATE INDEX profile_roles_role_scope_idx ON security.profile_roles (tenant_id,application_id,role_id);
CREATE INDEX branch_profile_assignments_access_scope_idx ON security.branch_profile_assignments (tenant_id,branch_membership_id);
CREATE INDEX branch_profile_assignments_profile_scope_idx ON security.branch_profile_assignments (tenant_id,application_id,profile_id);
CREATE INDEX session_contexts_session_scope_idx ON security.session_contexts (session_id,user_id,application_id);
CREATE INDEX session_contexts_access_scope_idx ON security.session_contexts (tenant_id,membership_id,branch_id);
CREATE INDEX session_contexts_branch_scope_idx ON security.session_contexts (tenant_id,branch_id);
CREATE INDEX session_contexts_entitlement_idx ON security.session_contexts (tenant_id,application_id);
CREATE INDEX api_keys_branch_scope_idx ON integration.api_keys (tenant_id,branch_id);
CREATE INDEX api_key_permissions_permission_scope_idx ON integration.api_key_permissions (permission_id,application_id);

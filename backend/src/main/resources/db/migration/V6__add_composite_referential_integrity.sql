-- LFD-26. PostgreSQL 17 / administrative Flyway only; no seeds, grants or runtime behavior.
-- Flyway wraps this DDL in a transaction; incompatible existing data must fail closed.

-- Non-partial candidate keys: exact referenced columns, without changing UUID primary keys.
ALTER TABLE tenant.memberships ADD CONSTRAINT memberships_tenant_id_uq UNIQUE (tenant_id,id);
ALTER TABLE tenant.memberships ADD CONSTRAINT memberships_tenant_user_id_uq UNIQUE (tenant_id,id,user_id);
ALTER TABLE tenant.branches ADD CONSTRAINT branches_tenant_id_uq UNIQUE (tenant_id,id);
ALTER TABLE tenant.branch_memberships ADD CONSTRAINT branch_memberships_tenant_id_uq UNIQUE (tenant_id,id);
ALTER TABLE tenant.branch_memberships ADD CONSTRAINT branch_memberships_scope_uq UNIQUE (tenant_id,membership_id,branch_id);
ALTER TABLE catalog.tenant_applications ADD CONSTRAINT tenant_applications_scope_uq UNIQUE (tenant_id,application_id);
ALTER TABLE catalog.modules ADD CONSTRAINT modules_application_id_uq UNIQUE (application_id,id);
ALTER TABLE security.permissions ADD CONSTRAINT permissions_application_id_uq UNIQUE (application_id,id);
ALTER TABLE security.role_templates ADD CONSTRAINT role_templates_application_id_uq UNIQUE (application_id,id);
ALTER TABLE security.profile_templates ADD CONSTRAINT profile_templates_application_id_uq UNIQUE (application_id,id);
ALTER TABLE security.roles ADD CONSTRAINT roles_scope_id_uq UNIQUE (tenant_id,application_id,id);
ALTER TABLE security.profiles ADD CONSTRAINT profiles_scope_id_uq UNIQUE (tenant_id,application_id,id);
ALTER TABLE security.sessions ADD CONSTRAINT sessions_user_application_uq UNIQUE (id,user_id,application_id);
ALTER TABLE integration.api_keys ADD CONSTRAINT api_keys_scope_id_uq UNIQUE (tenant_id,application_id,id);

-- Tenant/application/identity/access consistency. MATCH SIMPLE permits only the documented optional NULLs.
ALTER TABLE tenant.branch_memberships ADD CONSTRAINT branch_memberships_membership_scope_fk
    FOREIGN KEY (tenant_id,membership_id) REFERENCES tenant.memberships (tenant_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;

ALTER TABLE tenant.branch_memberships ADD CONSTRAINT branch_memberships_branch_scope_fk
    FOREIGN KEY (tenant_id,branch_id) REFERENCES tenant.branches (tenant_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;

ALTER TABLE tenant.administrators ADD CONSTRAINT administrators_membership_scope_fk
    FOREIGN KEY (tenant_id,membership_id) REFERENCES tenant.memberships (tenant_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;

ALTER TABLE catalog.tenant_modules ADD CONSTRAINT tenant_modules_entitlement_fk
    FOREIGN KEY (tenant_id,application_id) REFERENCES catalog.tenant_applications (tenant_id,application_id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;

ALTER TABLE catalog.tenant_modules ADD CONSTRAINT tenant_modules_module_scope_fk
    FOREIGN KEY (application_id,module_id) REFERENCES catalog.modules (application_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;
-- Superseded simple FK: stronger validated reference above preserves its guarantee. No data is removed.
ALTER TABLE catalog.tenant_modules DROP CONSTRAINT tenant_modules_module_id_fk;

ALTER TABLE security.permissions ADD CONSTRAINT permissions_module_scope_fk
    FOREIGN KEY (application_id,module_id) REFERENCES catalog.modules (application_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;
-- Superseded simple FK: stronger validated reference above preserves its guarantee. No data is removed.
ALTER TABLE security.permissions DROP CONSTRAINT permissions_module_id_fk;

ALTER TABLE security.role_template_permissions ADD CONSTRAINT role_template_permissions_template_scope_fk
    FOREIGN KEY (application_id,role_template_id) REFERENCES security.role_templates (application_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;
-- Superseded simple FK: stronger validated reference above preserves its guarantee. No data is removed.
ALTER TABLE security.role_template_permissions DROP CONSTRAINT role_template_permissions_role_template_id_fk;

ALTER TABLE security.role_template_permissions ADD CONSTRAINT role_template_permissions_permission_scope_fk
    FOREIGN KEY (application_id,permission_id) REFERENCES security.permissions (application_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;
-- Superseded simple FK: stronger validated reference above preserves its guarantee. No data is removed.
ALTER TABLE security.role_template_permissions DROP CONSTRAINT role_template_permissions_permission_id_fk;

ALTER TABLE security.profile_template_roles ADD CONSTRAINT profile_template_roles_profile_scope_fk
    FOREIGN KEY (application_id,profile_template_id) REFERENCES security.profile_templates (application_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;
-- Superseded simple FK: stronger validated reference above preserves its guarantee. No data is removed.
ALTER TABLE security.profile_template_roles DROP CONSTRAINT profile_template_roles_profile_template_id_fk;

ALTER TABLE security.profile_template_roles ADD CONSTRAINT profile_template_roles_role_scope_fk
    FOREIGN KEY (application_id,role_template_id) REFERENCES security.role_templates (application_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;
-- Superseded simple FK: stronger validated reference above preserves its guarantee. No data is removed.
ALTER TABLE security.profile_template_roles DROP CONSTRAINT profile_template_roles_role_template_id_fk;

ALTER TABLE security.roles ADD CONSTRAINT roles_entitlement_fk
    FOREIGN KEY (tenant_id,application_id) REFERENCES catalog.tenant_applications (tenant_id,application_id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;

ALTER TABLE security.roles ADD CONSTRAINT roles_source_template_scope_fk
    FOREIGN KEY (application_id,source_role_template_id) REFERENCES security.role_templates (application_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;
-- Superseded simple FK: stronger validated reference above preserves its guarantee. No data is removed.
ALTER TABLE security.roles DROP CONSTRAINT roles_source_role_template_id_fk;

ALTER TABLE security.profiles ADD CONSTRAINT profiles_entitlement_fk
    FOREIGN KEY (tenant_id,application_id) REFERENCES catalog.tenant_applications (tenant_id,application_id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;

ALTER TABLE security.profiles ADD CONSTRAINT profiles_source_template_scope_fk
    FOREIGN KEY (application_id,source_profile_template_id) REFERENCES security.profile_templates (application_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;
-- Superseded simple FK: stronger validated reference above preserves its guarantee. No data is removed.
ALTER TABLE security.profiles DROP CONSTRAINT profiles_source_profile_template_id_fk;

ALTER TABLE security.role_permissions ADD CONSTRAINT role_permissions_role_scope_fk
    FOREIGN KEY (tenant_id,application_id,role_id) REFERENCES security.roles (tenant_id,application_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;

ALTER TABLE security.role_permissions ADD CONSTRAINT role_permissions_permission_scope_fk
    FOREIGN KEY (application_id,permission_id) REFERENCES security.permissions (application_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;
-- Superseded simple FK: stronger validated reference above preserves its guarantee. No data is removed.
ALTER TABLE security.role_permissions DROP CONSTRAINT role_permissions_permission_id_fk;

ALTER TABLE security.profile_roles ADD CONSTRAINT profile_roles_profile_scope_fk
    FOREIGN KEY (tenant_id,application_id,profile_id) REFERENCES security.profiles (tenant_id,application_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;

ALTER TABLE security.profile_roles ADD CONSTRAINT profile_roles_role_scope_fk
    FOREIGN KEY (tenant_id,application_id,role_id) REFERENCES security.roles (tenant_id,application_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;

ALTER TABLE security.branch_profile_assignments ADD CONSTRAINT branch_profile_assignments_access_scope_fk
    FOREIGN KEY (tenant_id,branch_membership_id) REFERENCES tenant.branch_memberships (tenant_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;

ALTER TABLE security.branch_profile_assignments ADD CONSTRAINT branch_profile_assignments_profile_scope_fk
    FOREIGN KEY (tenant_id,application_id,profile_id) REFERENCES security.profiles (tenant_id,application_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;

ALTER TABLE security.session_contexts ADD CONSTRAINT session_contexts_session_scope_fk
    FOREIGN KEY (session_id,user_id,application_id) REFERENCES security.sessions (id,user_id,application_id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;

ALTER TABLE security.session_contexts ADD CONSTRAINT session_contexts_membership_scope_fk
    FOREIGN KEY (tenant_id,membership_id,user_id) REFERENCES tenant.memberships (tenant_id,id,user_id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;

ALTER TABLE security.session_contexts ADD CONSTRAINT session_contexts_branch_scope_fk
    FOREIGN KEY (tenant_id,branch_id) REFERENCES tenant.branches (tenant_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;

ALTER TABLE security.session_contexts ADD CONSTRAINT session_contexts_access_scope_fk
    FOREIGN KEY (tenant_id,membership_id,branch_id) REFERENCES tenant.branch_memberships (tenant_id,membership_id,branch_id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;

ALTER TABLE security.session_contexts ADD CONSTRAINT session_contexts_entitlement_fk
    FOREIGN KEY (tenant_id,application_id) REFERENCES catalog.tenant_applications (tenant_id,application_id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;

ALTER TABLE integration.api_keys ADD CONSTRAINT api_keys_entitlement_fk
    FOREIGN KEY (tenant_id,application_id) REFERENCES catalog.tenant_applications (tenant_id,application_id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;

ALTER TABLE integration.api_keys ADD CONSTRAINT api_keys_branch_scope_fk
    FOREIGN KEY (tenant_id,branch_id) REFERENCES tenant.branches (tenant_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;

ALTER TABLE integration.api_key_permissions ADD CONSTRAINT api_key_permissions_key_scope_fk
    FOREIGN KEY (tenant_id,application_id,api_key_id) REFERENCES integration.api_keys (tenant_id,application_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;

ALTER TABLE integration.api_key_permissions ADD CONSTRAINT api_key_permissions_permission_scope_fk
    FOREIGN KEY (application_id,permission_id) REFERENCES security.permissions (application_id,id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;
-- Superseded simple FK: stronger validated reference above preserves its guarantee. No data is removed.
ALTER TABLE integration.api_key_permissions DROP CONSTRAINT api_key_permissions_permission_id_fk;

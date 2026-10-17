-- LFD-27: closed manifest approved in Jira comment 10060.
-- PUBLISHED is catalog publication only, never effective tenant authorization.
-- Explicit UUIDv5 identities; ordinary INSERT fails closed on UUID/code collisions.
-- Flyway owns the PostgreSQL transaction. Historical V1-V7 remain immutable.

INSERT INTO catalog.applications (id, code, name, description, status) VALUES
    ('3bc09866-f9f0-573c-82a0-fd72ec6be41f', 'ls-core', 'LS Core', 'Framework central de administración, identidad y acceso multiempresa de LS Soluciones', 'PUBLISHED');

INSERT INTO catalog.modules (id, application_id, code, name, description, status) VALUES
    ('e2d0f4fe-ce53-50c5-b24d-e7167af48761', '3bc09866-f9f0-573c-82a0-fd72ec6be41f', 'branches', 'Sucursales', 'Consulta contextual de sucursales empresariales', 'PUBLISHED'),
    ('fedcd5b4-5675-5b97-b2c2-2264f93ad679', '3bc09866-f9f0-573c-82a0-fd72ec6be41f', 'memberships', 'Usuarios y membresías', 'Consulta de vinculaciones de identidades globales con empresas', 'PUBLISHED'),
    ('1bd27945-4109-5081-8275-65fb238dc396', '3bc09866-f9f0-573c-82a0-fd72ec6be41f', 'access', 'Roles, perfiles y accesos', 'Catálogo de roles, perfiles, asignaciones y consultas de autorización', 'PUBLISHED');

INSERT INTO security.permissions (id, application_id, module_id, code, name, description, resource_scope, status) VALUES
    ('8322b61e-868e-51a6-bb15-37cb7bb99086', '3bc09866-f9f0-573c-82a0-fd72ec6be41f', 'e2d0f4fe-ce53-50c5-b24d-e7167af48761', 'branches.read', 'Consultar sucursal', 'Consultar la sucursal autorizada', 'BRANCH', 'PUBLISHED'),
    ('77d5397f-3728-501d-bf79-97c76881dcec', '3bc09866-f9f0-573c-82a0-fd72ec6be41f', 'fedcd5b4-5675-5b97-b2c2-2264f93ad679', 'memberships.read', 'Consultar membresías', 'Consultar membresías del tenant autorizado', 'TENANT', 'PUBLISHED'),
    ('7b041a8a-55d3-54eb-be54-18cc7a97a24c', '3bc09866-f9f0-573c-82a0-fd72ec6be41f', '1bd27945-4109-5081-8275-65fb238dc396', 'roles.read', 'Consultar roles', 'Consultar roles de ese tenant', 'TENANT', 'PUBLISHED'),
    ('48520524-890b-5d11-ab98-14716a11ecab', '3bc09866-f9f0-573c-82a0-fd72ec6be41f', '1bd27945-4109-5081-8275-65fb238dc396', 'roles.manage', 'Administrar roles', 'Administrar roles dentro de los permisos delegables de ese tenant', 'TENANT', 'PUBLISHED'),
    ('9734c12b-ff44-5643-87a0-5a3ee5f84956', '3bc09866-f9f0-573c-82a0-fd72ec6be41f', '1bd27945-4109-5081-8275-65fb238dc396', 'profiles.read', 'Consultar perfiles', 'Consultar perfiles de ese tenant', 'TENANT', 'PUBLISHED'),
    ('bc05b7e7-d18c-54a1-9205-c95bd1347b09', '3bc09866-f9f0-573c-82a0-fd72ec6be41f', '1bd27945-4109-5081-8275-65fb238dc396', 'profiles.manage', 'Administrar perfiles', 'Administrar perfiles sin exceder permisos delegables', 'TENANT', 'PUBLISHED'),
    ('edbd064d-8215-5e32-8d03-82cc108b3d6d', '3bc09866-f9f0-573c-82a0-fd72ec6be41f', '1bd27945-4109-5081-8275-65fb238dc396', 'assignments.read', 'Consultar asignaciones', 'Consultar asignaciones de perfiles de la sucursal autorizada', 'BRANCH', 'PUBLISHED'),
    ('b1b35333-9ea3-53df-a14f-bcddb2f022e8', '3bc09866-f9f0-573c-82a0-fd72ec6be41f', '1bd27945-4109-5081-8275-65fb238dc396', 'assignments.manage', 'Administrar asignaciones', 'Administrar asignaciones de perfiles de la sucursal autorizada', 'BRANCH', 'PUBLISHED'),
    ('0d48a71c-a4e4-52af-a682-a5f041ec7e66', '3bc09866-f9f0-573c-82a0-fd72ec6be41f', '1bd27945-4109-5081-8275-65fb238dc396', 'consolidated.read', 'Consultar información consolidada', 'Consultar información consolidada autorizada dentro de un tenant', 'TENANT', 'PUBLISHED');

INSERT INTO security.role_templates (id, application_id, code, version, name, description, status) VALUES
    ('a801d124-aa58-5055-bcbd-af8280cf19dd', '3bc09866-f9f0-573c-82a0-fd72ec6be41f', 'branch_reader', 1, 'Lector de sucursal', 'Consulta de sucursal y asignaciones dentro de la sucursal autorizada', 'PUBLISHED'),
    ('69cdb6db-a07e-5e5f-8d25-a6cf6410b0c8', '3bc09866-f9f0-573c-82a0-fd72ec6be41f', 'access_editor', 1, 'Editor de accesos', 'Administración de accesos dentro de los permisos delegables del tenant y la sucursal autorizados', 'PUBLISHED');

INSERT INTO security.role_template_permissions (application_id, role_template_id, permission_id) VALUES
    ('3bc09866-f9f0-573c-82a0-fd72ec6be41f', 'a801d124-aa58-5055-bcbd-af8280cf19dd', '8322b61e-868e-51a6-bb15-37cb7bb99086'),
    ('3bc09866-f9f0-573c-82a0-fd72ec6be41f', 'a801d124-aa58-5055-bcbd-af8280cf19dd', 'edbd064d-8215-5e32-8d03-82cc108b3d6d'),
    ('3bc09866-f9f0-573c-82a0-fd72ec6be41f', '69cdb6db-a07e-5e5f-8d25-a6cf6410b0c8', '77d5397f-3728-501d-bf79-97c76881dcec'),
    ('3bc09866-f9f0-573c-82a0-fd72ec6be41f', '69cdb6db-a07e-5e5f-8d25-a6cf6410b0c8', '7b041a8a-55d3-54eb-be54-18cc7a97a24c'),
    ('3bc09866-f9f0-573c-82a0-fd72ec6be41f', '69cdb6db-a07e-5e5f-8d25-a6cf6410b0c8', '48520524-890b-5d11-ab98-14716a11ecab'),
    ('3bc09866-f9f0-573c-82a0-fd72ec6be41f', '69cdb6db-a07e-5e5f-8d25-a6cf6410b0c8', '9734c12b-ff44-5643-87a0-5a3ee5f84956'),
    ('3bc09866-f9f0-573c-82a0-fd72ec6be41f', '69cdb6db-a07e-5e5f-8d25-a6cf6410b0c8', 'bc05b7e7-d18c-54a1-9205-c95bd1347b09'),
    ('3bc09866-f9f0-573c-82a0-fd72ec6be41f', '69cdb6db-a07e-5e5f-8d25-a6cf6410b0c8', 'edbd064d-8215-5e32-8d03-82cc108b3d6d'),
    ('3bc09866-f9f0-573c-82a0-fd72ec6be41f', '69cdb6db-a07e-5e5f-8d25-a6cf6410b0c8', 'b1b35333-9ea3-53df-a14f-bcddb2f022e8');

INSERT INTO security.profile_templates (id, application_id, code, version, name, description, status) VALUES
    ('a2ff7d6a-ded6-5fdc-81e7-8e103dde3f77', '3bc09866-f9f0-573c-82a0-fd72ec6be41f', 'lector_sucursal', 1, 'Lector de sucursal', 'Plantilla con el rol Lector de sucursal', 'PUBLISHED'),
    ('61e78d0c-1c59-5263-870a-4bc69a9319e6', '3bc09866-f9f0-573c-82a0-fd72ec6be41f', 'editor_accesos', 1, 'Editor de accesos', 'Plantilla con el rol Editor de accesos', 'PUBLISHED');

INSERT INTO security.profile_template_roles (application_id, profile_template_id, role_template_id) VALUES
    ('3bc09866-f9f0-573c-82a0-fd72ec6be41f', 'a2ff7d6a-ded6-5fdc-81e7-8e103dde3f77', 'a801d124-aa58-5055-bcbd-af8280cf19dd'),
    ('3bc09866-f9f0-573c-82a0-fd72ec6be41f', '61e78d0c-1c59-5263-870a-4bc69a9319e6', '69cdb6db-a07e-5e5f-8d25-a6cf6410b0c8');

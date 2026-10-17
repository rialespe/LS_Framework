# Catálogo de semillas globales LFD-27

Manifiesto cerrado autorizado por el propietario en [comentario 10060](https://lssoluciones.atlassian.net/browse/LFD-27?focusedCommentId=10060). Implementado exclusivamente por [V8](../../backend/src/main/resources/db/migration/V8__seed_approved_global_catalogs.sql). Son **28 filas en siete tablas** existentes: 17 entidades con UUID fijo y 11 asociaciones con PK compuesta. Las otras 22 tablas de dominio quedan vacías. Este manifiesto registra todos los campos de negocio; created_at y updated_at, cuando existe, conservan sus defaults técnicos CURRENT_TIMESTAMP.

`PUBLISHED` significa definición publicada en catálogo; no habilita empresas, no acredita sesiones válidas ni concede autorización efectiva. No se introducen CHECK globales de estados ni scopes. `TENANT` limita el recurso al tenant autorizado, nunca a otra empresa. `BRANCH` limita el recurso a la sucursal autorizada. El permiso `consolidated.read` es independiente de consultas de sucursal y no tiene ninguna asociación inicial.

Todos los registros pertenecen a LS Core. No se incluyen LS Fiscal, LS POS ni otros módulos/permisos. `manage` exige respetar el catálogo habilitado y los permisos delegables; no autoriza conceder otros permisos ni equivale a OWNER/SUPERADMIN. Las plantillas no crean roles/perfiles empresariales, identidades, tenants, sucursales, membresías, habilitaciones, asignaciones, sesiones ni API Keys.

F10 desarrollará administración RBAC y autorización runtime, incluida validación de delegación y contexto tenant/sucursal; F11 desarrollará habilitaciones y navegación. En particular, assignments.read de branch_reader solo consulta la sucursal autorizada; la exigencia funcional corresponde a F10 y todavía no está implementada. access_editor no incluye branches.read ni consolidated.read. Ninguna plantilla representa autorización global.

Namespace UUIDv5: `7cf6014f-8524-5dd7-9a7b-1e0f6b206c19`. Claves lógicas exactas: `app:ls-core`, `module:ls-core:<code>`, `permission:ls-core:<code>`, `role-template:ls-core:<code>:v1`, `profile-template:ls-core:<code>:v1`. Los 17 valores se validaron con uuid.UUID y uuid.uuid5 antes de escribir SQL. Se insertan literales, sin gen_random_uuid() en el seed. Futuras versiones de plantillas requieren nuevos registros, versión y UUID; no se reescribe el historial de versiones anteriores.

Flyway ejecuta V8 transaccionalmente en el orden de las tablas siguientes y evita su reaplicación por historial. INSERT ordinario falla ante colisiones de UUID o códigos normalizados: no hay upsert, ON CONFLICT DO NOTHING ni sobrescritura de semillas. Una incompatibilidad requiere inspección y una decisión expresa posterior; no se repara automáticamente. No se modifican V1–V7, FK compuestas, unicidad ni privilegios. Ver [contrato independiente](../../scripts/tests/postgres_seed_contract.py), [guía](../postgresql-migrations.md) y [verificación](../verification-lfd-27.md).

Las once relaciones incluyen application_id explícito y respetan las FK compuestas de LFD-26. Las tablas siguientes son exhaustivas; las PK de relaciones son (role_template_id,permission_id) y (profile_template_id,role_template_id).

## catalog.applications (1)

| id | code | name | description | status |
| --- | --- | --- | --- | --- |
| 3bc09866-f9f0-573c-82a0-fd72ec6be41f | ls-core | LS Core | Framework central de administración, identidad y acceso multiempresa de LS Soluciones | PUBLISHED |

## catalog.modules (3)

| id | application_id | code | name | description | status |
| --- | --- | --- | --- | --- | --- |
| e2d0f4fe-ce53-50c5-b24d-e7167af48761 | 3bc09866-f9f0-573c-82a0-fd72ec6be41f | branches | Sucursales | Consulta contextual de sucursales empresariales | PUBLISHED |
| fedcd5b4-5675-5b97-b2c2-2264f93ad679 | 3bc09866-f9f0-573c-82a0-fd72ec6be41f | memberships | Usuarios y membresías | Consulta de vinculaciones de identidades globales con empresas | PUBLISHED |
| 1bd27945-4109-5081-8275-65fb238dc396 | 3bc09866-f9f0-573c-82a0-fd72ec6be41f | access | Roles, perfiles y accesos | Catálogo de roles, perfiles, asignaciones y consultas de autorización | PUBLISHED |

## security.permissions (9)

| id | application_id | module_id | code | name | description | resource_scope | status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 8322b61e-868e-51a6-bb15-37cb7bb99086 | 3bc09866-f9f0-573c-82a0-fd72ec6be41f | e2d0f4fe-ce53-50c5-b24d-e7167af48761 | branches.read | Consultar sucursal | Consultar la sucursal autorizada | BRANCH | PUBLISHED |
| 77d5397f-3728-501d-bf79-97c76881dcec | 3bc09866-f9f0-573c-82a0-fd72ec6be41f | fedcd5b4-5675-5b97-b2c2-2264f93ad679 | memberships.read | Consultar membresías | Consultar membresías del tenant autorizado | TENANT | PUBLISHED |
| 7b041a8a-55d3-54eb-be54-18cc7a97a24c | 3bc09866-f9f0-573c-82a0-fd72ec6be41f | 1bd27945-4109-5081-8275-65fb238dc396 | roles.read | Consultar roles | Consultar roles de ese tenant | TENANT | PUBLISHED |
| 48520524-890b-5d11-ab98-14716a11ecab | 3bc09866-f9f0-573c-82a0-fd72ec6be41f | 1bd27945-4109-5081-8275-65fb238dc396 | roles.manage | Administrar roles | Administrar roles dentro de los permisos delegables de ese tenant | TENANT | PUBLISHED |
| 9734c12b-ff44-5643-87a0-5a3ee5f84956 | 3bc09866-f9f0-573c-82a0-fd72ec6be41f | 1bd27945-4109-5081-8275-65fb238dc396 | profiles.read | Consultar perfiles | Consultar perfiles de ese tenant | TENANT | PUBLISHED |
| bc05b7e7-d18c-54a1-9205-c95bd1347b09 | 3bc09866-f9f0-573c-82a0-fd72ec6be41f | 1bd27945-4109-5081-8275-65fb238dc396 | profiles.manage | Administrar perfiles | Administrar perfiles sin exceder permisos delegables | TENANT | PUBLISHED |
| edbd064d-8215-5e32-8d03-82cc108b3d6d | 3bc09866-f9f0-573c-82a0-fd72ec6be41f | 1bd27945-4109-5081-8275-65fb238dc396 | assignments.read | Consultar asignaciones | Consultar asignaciones de perfiles de la sucursal autorizada | BRANCH | PUBLISHED |
| b1b35333-9ea3-53df-a14f-bcddb2f022e8 | 3bc09866-f9f0-573c-82a0-fd72ec6be41f | 1bd27945-4109-5081-8275-65fb238dc396 | assignments.manage | Administrar asignaciones | Administrar asignaciones de perfiles de la sucursal autorizada | BRANCH | PUBLISHED |
| 0d48a71c-a4e4-52af-a682-a5f041ec7e66 | 3bc09866-f9f0-573c-82a0-fd72ec6be41f | 1bd27945-4109-5081-8275-65fb238dc396 | consolidated.read | Consultar información consolidada | Consultar información consolidada autorizada dentro de un tenant | TENANT | PUBLISHED |

## security.role_templates (2)

| id | application_id | code | version | name | description | status |
| --- | --- | --- | --- | --- | --- | --- |
| a801d124-aa58-5055-bcbd-af8280cf19dd | 3bc09866-f9f0-573c-82a0-fd72ec6be41f | branch_reader | 1 | Lector de sucursal | Consulta de sucursal y asignaciones dentro de la sucursal autorizada | PUBLISHED |
| 69cdb6db-a07e-5e5f-8d25-a6cf6410b0c8 | 3bc09866-f9f0-573c-82a0-fd72ec6be41f | access_editor | 1 | Editor de accesos | Administración de accesos dentro de los permisos delegables del tenant y la sucursal autorizados | PUBLISHED |

## security.role_template_permissions (9)

| application_id | role_template_id | permission_id |
| --- | --- | --- |
| 3bc09866-f9f0-573c-82a0-fd72ec6be41f | a801d124-aa58-5055-bcbd-af8280cf19dd | 8322b61e-868e-51a6-bb15-37cb7bb99086 |
| 3bc09866-f9f0-573c-82a0-fd72ec6be41f | a801d124-aa58-5055-bcbd-af8280cf19dd | edbd064d-8215-5e32-8d03-82cc108b3d6d |
| 3bc09866-f9f0-573c-82a0-fd72ec6be41f | 69cdb6db-a07e-5e5f-8d25-a6cf6410b0c8 | 77d5397f-3728-501d-bf79-97c76881dcec |
| 3bc09866-f9f0-573c-82a0-fd72ec6be41f | 69cdb6db-a07e-5e5f-8d25-a6cf6410b0c8 | 7b041a8a-55d3-54eb-be54-18cc7a97a24c |
| 3bc09866-f9f0-573c-82a0-fd72ec6be41f | 69cdb6db-a07e-5e5f-8d25-a6cf6410b0c8 | 48520524-890b-5d11-ab98-14716a11ecab |
| 3bc09866-f9f0-573c-82a0-fd72ec6be41f | 69cdb6db-a07e-5e5f-8d25-a6cf6410b0c8 | 9734c12b-ff44-5643-87a0-5a3ee5f84956 |
| 3bc09866-f9f0-573c-82a0-fd72ec6be41f | 69cdb6db-a07e-5e5f-8d25-a6cf6410b0c8 | bc05b7e7-d18c-54a1-9205-c95bd1347b09 |
| 3bc09866-f9f0-573c-82a0-fd72ec6be41f | 69cdb6db-a07e-5e5f-8d25-a6cf6410b0c8 | edbd064d-8215-5e32-8d03-82cc108b3d6d |
| 3bc09866-f9f0-573c-82a0-fd72ec6be41f | 69cdb6db-a07e-5e5f-8d25-a6cf6410b0c8 | b1b35333-9ea3-53df-a14f-bcddb2f022e8 |

## security.profile_templates (2)

| id | application_id | code | version | name | description | status |
| --- | --- | --- | --- | --- | --- | --- |
| a2ff7d6a-ded6-5fdc-81e7-8e103dde3f77 | 3bc09866-f9f0-573c-82a0-fd72ec6be41f | lector_sucursal | 1 | Lector de sucursal | Plantilla con el rol Lector de sucursal | PUBLISHED |
| 61e78d0c-1c59-5263-870a-4bc69a9319e6 | 3bc09866-f9f0-573c-82a0-fd72ec6be41f | editor_accesos | 1 | Editor de accesos | Plantilla con el rol Editor de accesos | PUBLISHED |

## security.profile_template_roles (2)

| application_id | profile_template_id | role_template_id |
| --- | --- | --- |
| 3bc09866-f9f0-573c-82a0-fd72ec6be41f | a2ff7d6a-ded6-5fdc-81e7-8e103dde3f77 | a801d124-aa58-5055-bcbd-af8280cf19dd |
| 3bc09866-f9f0-573c-82a0-fd72ec6be41f | 61e78d0c-1c59-5263-870a-4bc69a9319e6 | 69cdb6db-a07e-5e5f-8d25-a6cf6410b0c8 |

"""Independent LFD-27 manifest oracle: literals, never parsed from migration SQL."""

import json
import uuid


NAMESPACE = uuid.UUID('7cf6014f-8524-5dd7-9a7b-1e0f6b206c19')
APP = '3bc09866-f9f0-573c-82a0-fd72ec6be41f'
MODULES = [
    ('e2d0f4fe-ce53-50c5-b24d-e7167af48761', 'branches', 'Sucursales', 'Consulta contextual de sucursales empresariales'),
    ('fedcd5b4-5675-5b97-b2c2-2264f93ad679', 'memberships', 'Usuarios y membresías', 'Consulta de vinculaciones de identidades globales con empresas'),
    ('1bd27945-4109-5081-8275-65fb238dc396', 'access', 'Roles, perfiles y accesos', 'Catálogo de roles, perfiles, asignaciones y consultas de autorización'),
]
PERMISSIONS = [
    ('8322b61e-868e-51a6-bb15-37cb7bb99086', 'branches.read', 'branches', 'BRANCH', 'Consultar sucursal', 'Consultar la sucursal autorizada'),
    ('77d5397f-3728-501d-bf79-97c76881dcec', 'memberships.read', 'memberships', 'TENANT', 'Consultar membresías', 'Consultar membresías del tenant autorizado'),
    ('7b041a8a-55d3-54eb-be54-18cc7a97a24c', 'roles.read', 'access', 'TENANT', 'Consultar roles', 'Consultar roles de ese tenant'),
    ('48520524-890b-5d11-ab98-14716a11ecab', 'roles.manage', 'access', 'TENANT', 'Administrar roles', 'Administrar roles dentro de los permisos delegables de ese tenant'),
    ('9734c12b-ff44-5643-87a0-5a3ee5f84956', 'profiles.read', 'access', 'TENANT', 'Consultar perfiles', 'Consultar perfiles de ese tenant'),
    ('bc05b7e7-d18c-54a1-9205-c95bd1347b09', 'profiles.manage', 'access', 'TENANT', 'Administrar perfiles', 'Administrar perfiles sin exceder permisos delegables'),
    ('edbd064d-8215-5e32-8d03-82cc108b3d6d', 'assignments.read', 'access', 'BRANCH', 'Consultar asignaciones', 'Consultar asignaciones de perfiles de la sucursal autorizada'),
    ('b1b35333-9ea3-53df-a14f-bcddb2f022e8', 'assignments.manage', 'access', 'BRANCH', 'Administrar asignaciones', 'Administrar asignaciones de perfiles de la sucursal autorizada'),
    ('0d48a71c-a4e4-52af-a682-a5f041ec7e66', 'consolidated.read', 'access', 'TENANT', 'Consultar información consolidada', 'Consultar información consolidada autorizada dentro de un tenant'),
]
ROLES = [
    ('a801d124-aa58-5055-bcbd-af8280cf19dd', 'branch_reader', 'Lector de sucursal', 'Consulta de sucursal y asignaciones dentro de la sucursal autorizada'),
    ('69cdb6db-a07e-5e5f-8d25-a6cf6410b0c8', 'access_editor', 'Editor de accesos', 'Administración de accesos dentro de los permisos delegables del tenant y la sucursal autorizados'),
]
PROFILES = [
    ('a2ff7d6a-ded6-5fdc-81e7-8e103dde3f77', 'lector_sucursal', 'Lector de sucursal', 'Plantilla con el rol Lector de sucursal'),
    ('61e78d0c-1c59-5263-870a-4bc69a9319e6', 'editor_accesos', 'Editor de accesos', 'Plantilla con el rol Editor de accesos'),
]
ROLE_PERMISSIONS = {
    'branch_reader': ['branches.read', 'assignments.read'],
    'access_editor': ['memberships.read', 'roles.read', 'roles.manage', 'profiles.read',
                      'profiles.manage', 'assignments.read', 'assignments.manage'],
}
PROFILE_ROLES = {'lector_sucursal': 'branch_reader', 'editor_accesos': 'access_editor'}


def manifest():
    """Return exact business columns; timestamps are verified separately and snapshotted."""
    modules = {r[1]: r[0] for r in MODULES}
    permissions = {r[1]: r[0] for r in PERMISSIONS}
    roles = {r[1]: r[0] for r in ROLES}
    profiles = {r[1]: r[0] for r in PROFILES}
    return {
        'catalog.applications': (['id', 'code', 'name', 'description', 'status'], [
            (APP, 'ls-core', 'LS Core', 'Framework central de administración, identidad y acceso multiempresa de LS Soluciones', 'PUBLISHED')]),
        'catalog.modules': (['id', 'application_id', 'code', 'name', 'description', 'status'],
                            [(i, APP, c, n, d, 'PUBLISHED') for i, c, n, d in MODULES]),
        'security.permissions': (['id', 'application_id', 'module_id', 'code', 'name', 'description', 'resource_scope', 'status'],
                                 [(i, APP, modules[m], c, n, d, s, 'PUBLISHED') for i, c, m, s, n, d in PERMISSIONS]),
        'security.role_templates': (['id', 'application_id', 'code', 'version', 'name', 'description', 'status'],
                                    [(i, APP, c, 1, n, d, 'PUBLISHED') for i, c, n, d in ROLES]),
        'security.role_template_permissions': (['application_id', 'role_template_id', 'permission_id'],
                                              [(APP, roles[r], permissions[p]) for r, ps in ROLE_PERMISSIONS.items() for p in ps]),
        'security.profile_templates': (['id', 'application_id', 'code', 'version', 'name', 'description', 'status'],
                                       [(i, APP, c, 1, n, d, 'PUBLISHED') for i, c, n, d in PROFILES]),
        'security.profile_template_roles': (['application_id', 'profile_template_id', 'role_template_id'],
                                           [(APP, profiles[p], roles[r]) for p, r in PROFILE_ROLES.items()]),
    }


def validate_ids(check):
    logical = [('app:ls-core', APP)]
    for kind, rows in [('module', MODULES), ('permission', PERMISSIONS),
                       ('role-template', ROLES), ('profile-template', PROFILES)]:
        logical += [(kind + ':ls-core:' + row[1] + (':v1' if 'template' in kind else ''), row[0]) for row in rows]
    check(len(logical) == len({value for _, value in logical}) == 17
          and all(str(uuid.UUID(value)) == value and str(uuid.uuid5(NAMESPACE, key)) == value
                  for key, value in logical), 'LFD-27: 17 distinct exact UUIDv5 and approved logical keys')


def data_snapshot(cluster, tables):
    return {table: cluster.query('SELECT row_to_json(r)::text FROM ' + table + ' r ORDER BY row_to_json(r)::text')
            for table in tables}


def test_seeds(cluster, check, tables):
    validate_ids(check)
    expected = manifest()
    check(len(expected) == 7 and sum(len(rows) for _, rows in expected.values()) == 28,
          'LFD-27: independent manifest contains exactly 28 rows in seven tables')
    for table, (columns, rows) in expected.items():
        actual = cluster.query('SELECT json_build_array(' + ','.join(columns) + ')::text FROM ' + table)
        check({tuple(json.loads(row)) for row in actual.splitlines()} == set(rows)
              and len(actual.splitlines()) == len(rows), 'LFD-27 exact seed content: ' + table)
        check(cluster.query('SELECT count(*) FROM ' + table + ' WHERE created_at IS NULL') == '0',
              'LFD-27 technical timestamps: ' + table)
    others = set(tables) - set(expected)
    check(len(others) == 22 and all(cluster.query('SELECT count(*) FROM ' + t) == '0' for t in others),
          'LFD-27: other 22 domain tables empty; no tenant authorization or identities')
    check(cluster.query("SELECT count(*) FROM security.role_template_permissions WHERE permission_id='"
                        + PERMISSIONS[-1][0] + "'") == '0', 'LFD-27: consolidated.read has no initial assignment')

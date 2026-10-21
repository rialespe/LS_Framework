"""Reviewed Docker-only C2 adaptation (LFD-29 comment 10130). No C1 creation."""
from pathlib import Path
import tempfile
import json

from provision_docker_postgres import ROOT, docker_identity, psql, Stopped

READER = 'ls_core_catalog_reader_dev'
RUNTIME = 'ls_core_app_dev'
EXCEPTION_DATABASES = ('postgres', 'ls_core_docker_dev')


def temp_exception(database, role, *, connect, create, temporary, public_temp,
                   direct_temp=False, grantable=False, sessions=0):
    """Pure form of the narrow SQL exception, used by negative regression checks."""
    return (database in EXCEPTION_DATABASES and role in (READER, RUNTIME)
            and temporary and public_temp and not connect and not create
            and not direct_temp and not grantable and sessions == 0)


def validate_inventory(state):
    expected_databases = {'ls_core_dev', 'ls_core_docker_dev', 'postgres', 'template0', 'template1'}
    if set(state['database_names']) != expected_databases or state['custom_roles'] != sorted([
            RUNTIME, READER, 'ls_core_docker', 'ls_core_migrator_dev']):
        raise Stopped('Unexpected database or custom role inventory.')
    if state['memberships'] or state['sessions'] or state['schema_create'] or state['bad_roles']:
        raise Stopped('Unexpected membership, session, schema CREATE or role attributes.')
    pairs = {(row['database'], row['role']) for row in state['privileges']}
    if pairs != {(database, role) for database in expected_databases for role in (READER, RUNTIME)} or len(state['privileges']) != 10:
        raise Stopped('Incomplete or duplicated privilege inventory.')
    for row in state['privileges']:
        if row['owner'] != 'ls_core_docker' or row['allowconn'] != (row['database'] != 'template0'):
            raise Stopped('Unexpected database owner or connection availability.')
        if not row['allowconn']:
            continue
        role, database = row['role'], row['database']
        if row['create'] or row['connect'] != (role == RUNTIME and database == 'ls_core_dev'):
            raise Stopped('Unexpected CONNECT/CREATE: ' + role + ' / ' + database)
        if row['temporary'] and not temp_exception(database, role,
                **{key: row[key] for key in ('connect', 'create', 'temporary', 'public_temp', 'direct_temp', 'grantable')},
                sessions=state['sessions']):
            raise Stopped('Unexpected TEMPORARY: ' + role + ' / ' + database)


def inventory_sql():
    return """SELECT json_build_object(
      'database_names',(SELECT json_agg(datname ORDER BY datname) FROM pg_database),
      'custom_roles',(SELECT json_agg(rolname ORDER BY rolname) FROM pg_roles WHERE rolname !~ '^pg_'),
      'memberships',(SELECT count(*) FROM pg_auth_members WHERE member IN ('ls_core_app_dev'::regrole,'ls_core_catalog_reader_dev'::regrole) OR roleid IN ('ls_core_app_dev'::regrole,'ls_core_catalog_reader_dev'::regrole)),
      'sessions',(SELECT count(*) FROM pg_stat_activity WHERE usename IN ('ls_core_app_dev','ls_core_catalog_reader_dev')),
      'schema_create',(SELECT count(*) FROM pg_namespace n,pg_roles r WHERE r.rolname IN ('ls_core_app_dev','ls_core_catalog_reader_dev') AND has_schema_privilege(r.oid,n.oid,'CREATE')),
      'bad_roles',(SELECT count(*) FROM pg_roles WHERE rolname IN ('ls_core_app_dev','ls_core_catalog_reader_dev') AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolinherit OR rolconnlimit<>3 OR rolconfig IS NOT NULL OR rolcanlogin<>(rolname='ls_core_app_dev'))),
      'privileges',(SELECT json_agg(json_build_object('database',d.datname,'role',r.rolname,
        'owner',pg_get_userbyid(d.datdba),'allowconn',d.datallowconn,
        'connect',has_database_privilege(r.oid,d.oid,'CONNECT'),
        'create',has_database_privilege(r.oid,d.oid,'CREATE'),
        'temporary',has_database_privilege(r.oid,d.oid,'TEMPORARY'),
        'public_temp',EXISTS(SELECT FROM aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a WHERE a.grantee=0 AND a.privilege_type='TEMPORARY' AND a.grantor='ls_core_docker'::regrole),
        'direct_temp',EXISTS(SELECT FROM aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a WHERE a.grantee=r.oid AND a.privilege_type='TEMPORARY'),
        'grantable',EXISTS(SELECT FROM aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a WHERE a.grantee IN (0,r.oid) AND a.privilege_type='TEMPORARY' AND a.is_grantable)) ORDER BY d.datname,r.rolname)
        FROM pg_database d CROSS JOIN pg_roles r WHERE r.rolname IN ('ls_core_app_dev','ls_core_catalog_reader_dev')));"""


def allowed_temp_sql(role):
    return f"""(d.datname IN ('postgres','ls_core_docker_dev')
          AND NOT has_database_privilege('{role}',d.oid,'CONNECT,CREATE')
          AND EXISTS (SELECT FROM aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a
            WHERE a.grantee=0 AND a.privilege_type='TEMPORARY' AND NOT a.is_grantable
              AND a.grantor='ls_core_docker'::regrole)
          AND NOT EXISTS (SELECT FROM aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a
            WHERE a.grantee='{role}'::regrole AND a.privilege_type='TEMPORARY')
          AND NOT EXISTS (SELECT FROM pg_stat_activity WHERE usename IN
            ('ls_core_catalog_reader_dev','ls_core_app_dev')))"""


def build_service_sql():
    source = (ROOT / 'scripts/migrations/prepare_catalog_service.sql').read_text(encoding='utf-8')
    old_guard = """current_user <> 'postgres' OR session_user <> 'postgres'
       OR current_database() <> 'ls_core_dev'
       OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet"""
    new_guard = """current_user <> 'ls_core_docker' OR session_user <> 'ls_core_docker'
       OR current_database() <> 'ls_core_dev'
       OR (SELECT system_identifier::text FROM pg_control_system()) <> '7698808171985612839'"""
    replacements = [(old_guard, new_guard)]
    for role, old_privileges, strict_privileges in [
        (READER, 'CONNECT,CREATE,TEMPORARY', 'CONNECT,CREATE'),
        (RUNTIME, 'CREATE,TEMPORARY', 'CREATE'),
    ]:
        old = f"has_database_privilege('{role}',d.oid,'{old_privileges}')"
        new = f"(has_database_privilege('{role}',d.oid,'{strict_privileges}') OR " \
              f"(has_database_privilege('{role}',d.oid,'TEMPORARY') AND NOT {allowed_temp_sql(role)}))"
        replacements.append((old, new))
    for old, new in replacements:
        if source.count(old) != 1:
            raise Stopped('Original C2 guard changed; review required.')
        source = source.replace(old, new, 1)
    extra = (ROOT / 'scripts/docker/catalog-c2-preflight.sql').read_text(encoding='utf-8')
    source = source.replace('BEGIN;\n', 'BEGIN;\n' + extra + '\n', 1)
    return '\\set apply true\n' + source


def prepare_c2_only(password):
    identity = docker_identity()
    source = build_service_sql()
    validate_inventory(json.loads(psql(inventory_sql(), password, database='ls_core_dev')))
    marker = 'CREATE ROLE ls_core_catalog_service_dev '
    if source.count(marker) != 1:
        raise Stopped('C2 creation boundary changed; review required.')
    # All original C1 ACL/ownership/column checks execute read-only first, then again
    # in the creation transaction. No C1 script, adoption or ACL repair is invoked.
    psql(source.split(marker)[0] + '\nROLLBACK;\n', password, database='ls_core_dev')
    fingerprint_sql = (ROOT / 'scripts/docker/catalog-c1-fingerprint.sql').read_text(encoding='utf-8')
    before = psql(fingerprint_sql, password, database='ls_core_dev')
    if docker_identity() != identity:
        raise Stopped('Docker identity changed after C2 preflight.')
    with tempfile.TemporaryDirectory(prefix='ls-catalog-c2-only-') as folder:
        script = Path(folder) / 'prepare_catalog_service.sql'
        script.write_text(source, encoding='utf-8')
        psql(None, password, database='ls_core_dev', readonly=False, script=script)
    after = psql(fingerprint_sql, password, database='ls_core_dev')
    if before != after:
        raise Stopped('C1 fingerprint changed; stop for review, no repair.')
    psql((ROOT / 'scripts/docker/verify-catalog-c2.sql').read_text(encoding='utf-8'),
         password, database='ls_core_dev')
    return {'roles': 'C2_PREPARED_C1_PRESERVED', 'original_scripts': 'UNCHANGED',
            'c1_fingerprint_unchanged': True, 'identity': identity}

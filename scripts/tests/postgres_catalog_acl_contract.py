"""C1 catalog ACL regression; standalone run uses only a new disposable PG17 cluster.

python scripts/tests/postgres_catalog_acl_contract.py
NOLOGIN reader is exercised with SET SESSION AUTHORIZATION, without creating a service identity.
"""

import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
from test_postgres_migrations import ROOT, SOURCE, check, disposable_cluster, history, prepare_fixture
from postgres_rls_contract import GLOBAL, PROTECTED, relations
from postgres_seed_contract import data_snapshot


READER = 'ls_core_catalog_reader_dev'
SCRIPT = ROOT / 'scripts/migrations/prepare_catalog_reader.sql'
# Independent public-column contract from the reviewed seven matrix rows.
COLUMNS = {
    'catalog.applications': 'id code name description status',
    'catalog.modules': 'id application_id code name description status',
    'security.permissions': 'id application_id module_id code name description resource_scope status',
    'security.role_templates': 'id application_id code version name description status',
    'security.role_template_permissions': 'application_id role_template_id permission_id',
    'security.profile_templates': 'id application_id code version name description status',
    'security.profile_template_roles': 'application_id profile_template_id role_template_id',
}


def reader_sql(cluster, sql):
    return cluster.psql(script='-', reply='\\set VERBOSITY verbose\n'
                        f'SET SESSION AUTHORIZATION {READER};\n' + sql + ';\n')


def test_catalog_acl(cluster):
    check(set(COLUMNS) == GLOBAL, 'C1 independent contract covers exactly seven global catalogs')
    before = relations(cluster)
    data = data_snapshot(cluster, sorted(before))
    previous_history = history(cluster)
    role_snapshot = cluster.query("SELECT row_to_json(r) FROM pg_roles r WHERE rolname IN "
                                  "('ls_core_app_dev','ls_core_migrator_dev') ORDER BY rolname")
    refused = cluster.psql(script=SCRIPT)
    check(refused.returncode != 0 and 'explicit apply=true' in refused.stderr
          and cluster.query(f"SELECT count(*) FROM pg_roles WHERE rolname='{READER}'") == '0',
          'administrative script defaults to no changes')
    applied = cluster.psql(script=SCRIPT, apply=True)
    check(applied.returncode == 0, 'explicit preparation succeeds only in disposable fixture')
    check(cluster.query(f"SELECT NOT (rolcanlogin OR rolinherit OR rolsuper OR rolcreatedb OR rolcreaterole "
                        f"OR rolreplication OR rolbypassrls) AND rolconnlimit=3 FROM pg_roles WHERE rolname='{READER}'")
          == 't' and cluster.query(f"SELECT count(*) FROM pg_auth_members WHERE member='{READER}'::regrole "
                                   f"OR roleid='{READER}'::regrole") == '0',
          'reader is NOLOGIN, limited, without members or elevated memberships')
    identity = reader_sql(cluster, 'SELECT current_user,session_user,current_setting(\'transaction_read_only\')')
    check(identity.returncode == 0 and f'{READER}|{READER}|off' in identity.stdout,
          'reader assertions execute as the reader in read-write mode, without superuser bypass')
    for table, public in COLUMNS.items():
        selected = ','.join(public.split())
        actual = reader_sql(cluster, f'SELECT json_build_array({selected}) FROM {table}')
        expected = cluster.query(f'SELECT json_build_array({selected}) FROM {table}')
        # SET SESSION AUTHORIZATION emits a command tag before the result rows.
        observed = [line for line in actual.stdout.splitlines() if line.startswith('[')]
        check(actual.returncode == 0 and sorted(observed) == sorted(expected.splitlines()) and len(observed) > 0,
              table + ': reader obtains exactly the seeded public data')
        schema, name = table.split('.')
        privileges = cluster.query(
            "SELECT json_build_array(a.attname,has_column_privilege('" + READER + "',c.oid,a.attnum,'SELECT'),"
            "has_column_privilege('" + READER + "',c.oid,a.attnum,'SELECT WITH GRANT OPTION')) "
            "FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid "
            "JOIN pg_namespace n ON n.oid=c.relnamespace "
            f"WHERE n.nspname='{schema}' AND c.relname='{name}' AND a.attnum>0 AND NOT a.attisdropped")
        observed_privileges = list(map(json.loads, privileges.splitlines()))
        check({column for column, allowed, _ in observed_privileges if allowed} == set(public.split())
              and not any(delegable for _, _, delegable in observed_privileges),
              table + ': exact column SELECT, no timestamps or grant option')
        for sql in [f'SELECT * FROM {table}', f'DELETE FROM {table} WHERE false',
                    f'UPDATE {table} SET {public.split()[0]}={public.split()[0]} WHERE false',
                    f'INSERT INTO {table} DEFAULT VALUES', f'TRUNCATE {table}']:
            denied = reader_sql(cluster, 'BEGIN; ' + sql + '; ROLLBACK')
            check(denied.returncode != 0 and '42501' in denied.stderr,
                  table + ': ACL rejects ' + sql.split()[0] + ' (42501)')
        attempted = reader_sql(cluster, f'GRANT SELECT ({public.split()[0]}) ON {table} TO ls_core_app_dev')
        check((attempted.returncode != 0 and '42501' in attempted.stderr)
              or (attempted.returncode == 0 and 'no privileges were granted' in attempted.stderr),
              table + ': reader cannot delegate SELECT (runtime ACL checked below)')
    for table in sorted(PROTECTED | GLOBAL | {'operations.flyway_schema_history'}):
        schema, name = table.split('.')
        check(cluster.query("SELECT NOT has_table_privilege('ls_core_app_dev',c.oid,"
                            "'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN') "
                            "AND NOT has_any_column_privilege('ls_core_app_dev',c.oid,"
                            "'SELECT,INSERT,UPDATE,REFERENCES') FROM pg_class c "
                            "JOIN pg_namespace n ON n.oid=c.relnamespace "
                            f"WHERE n.nspname='{schema}' AND c.relname='{name}'") == 't',
              table + ': runtime retains zero table/column business privileges')
        denied = cluster.psql(script='-', user='ls_core_app_dev',
                              reply='\\set VERBOSITY verbose\nSELECT * FROM ' + table + ';\n')
        check(denied.returncode != 0 and '42501' in denied.stderr,
              table + ': runtime SELECT rejected by ACL')
        if table not in GLOBAL:
            denied = reader_sql(cluster, f'SELECT * FROM {table}')
            check(denied.returncode != 0 and '42501' in denied.stderr,
                  table + ': reader SELECT rejected by ACL, not hidden by RLS')
    for target in ['postgres', 'ls_core_migrator_dev', 'ls_core_app_dev',
                   'pg_read_all_data', 'pg_write_all_data', 'pg_signal_backend']:
        denied = reader_sql(cluster, 'SET ROLE ' + target)
        check(denied.returncode != 0 and '42501' in denied.stderr, 'reader cannot SET ROLE ' + target)
    denied = cluster.psql(script='-', user='ls_core_app_dev',
                          reply=f'\\set VERBOSITY verbose\nSET ROLE {READER};\n')
    check(denied.returncode != 0 and '42501' in denied.stderr, 'runtime cannot acquire catalog reader')
    check(cluster.query(f"SELECT nspname FROM pg_namespace WHERE has_schema_privilege('{READER}',oid,'USAGE') "
                        "AND nspname NOT IN ('pg_catalog','information_schema') ORDER BY nspname")
          == 'catalog\npublic\nsecurity', 'reader schema access: catalog/security and existing PUBLIC namespace usage')
    check(cluster.query(f"SELECT count(*) FROM pg_namespace WHERE has_schema_privilege('{READER}',oid,'CREATE')") == '0'
          and cluster.query(f"SELECT NOT has_database_privilege('{READER}',current_database(),'CONNECT,CREATE,TEMPORARY')")
          == 't', 'reader has no schema CREATE or database CONNECT/CREATE/TEMP')
    check(cluster.query("SELECT row_to_json(r) FROM pg_roles r WHERE rolname IN "
                        "('ls_core_app_dev','ls_core_migrator_dev') ORDER BY rolname") == role_snapshot,
          'runtime and migrator attributes unchanged')
    check(relations(cluster) == before and history(cluster) == previous_history
          and data_snapshot(cluster, sorted(before)) == data, 'table ACLs, RLS, history and all business data unchanged')
    repeat = cluster.psql(script=SCRIPT, apply=True)
    check(repeat.returncode != 0 and 'already exists' in repeat.stderr,
          'repeat preparation refuses adoption instead of altering existing role')


def main():
    with disposable_cluster() as cluster:
        probe = cluster.psql(sql="SELECT json_build_object('data',current_setting('data_directory'),"
                                 "'port',inet_server_port(),'host',inet_server_addr(),"
                                 "'version',current_setting('server_version_num'))", database='postgres')
        check(probe.returncode == 0, 'temporary server identity query succeeds')
        identity = json.loads(probe.stdout.strip())
        check(Path(identity['data']).resolve() == cluster.data.resolve()
              and identity['port'] == cluster.port and identity['host'] == '127.0.0.1'
              and 170000 <= int(identity['version']) < 180000, 'own temporary PG17 identity verified')
        prepare_fixture(cluster)
        for database in ['postgres', 'template1']:
            check(cluster.psql(sql=f'REVOKE CONNECT, TEMPORARY ON DATABASE {database} FROM PUBLIC').returncode == 0,
                  'fixture-only database ACL preparation: ' + database)
        password = cluster.secrets['ls_core_migrator_dev']
        check(cluster.psql(script=ROOT / 'scripts/migrations/provision.sql',
                           reply=password + '\n' + password + '\n', apply=True).returncode == 0,
              'restricted migration role provisioned only in fixture')
        check(cluster.flyway('migrate', location=SOURCE, target='9').returncode == 0, 'real Flyway V1-V9 baseline')
        test_catalog_acl(cluster)
    print('Focused C1 checks passed; disposable cluster stopped and removed.', flush=True)


if __name__ == '__main__':
    main()

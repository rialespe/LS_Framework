"""C2: authenticated catalog service on a newly initialized PG17 cluster only.

Run: python scripts/tests/postgres_catalog_service_contract.py
"""
import json
from pathlib import Path
import secrets
import sys

sys.dont_write_bytecode = True
from test_postgres_migrations import ROOT, check, disposable_cluster, history, prepare_fixture
from postgres_catalog_acl_contract import COLUMNS, READER, SCRIPT as C1_SCRIPT
from postgres_rls_contract import PROTECTED, relations

SERVICE = 'ls_core_catalog_service_dev'
SCRIPT = ROOT / 'scripts/migrations/prepare_catalog_service.sql'


def service_sql(cluster, sql, reader=False, user=SERVICE):
    prefix = f'SET ROLE {READER};\n' if reader else ''
    return cluster.psql(script='-', user=user, reply='\\set VERBOSITY verbose\n' + prefix + sql + ';\n')


def test_catalog_service(cluster):
    before = relations(cluster), history(cluster)
    roles = cluster.query("SELECT row_to_json(r) FROM pg_roles r WHERE rolname IN "
                          f"('ls_core_app_dev','ls_core_migrator_dev','{READER}') ORDER BY rolname")
    check(cluster.psql(script=SCRIPT).returncode != 0, 'C2 defaults to no changes')
    # Inherited PUBLIC column access must be refused and left untouched, not silently repaired.
    cluster.query('GRANT SELECT (created_at) ON catalog.applications TO PUBLIC')
    refused = cluster.psql(script=SCRIPT, apply=True)
    check(refused.returncode != 0 and 'Incompatible table ACL' in refused.stderr
          and cluster.query(f"SELECT count(*) FROM pg_roles WHERE rolname='{SERVICE}'") == '0'
          and cluster.query(f"SELECT has_column_privilege('{READER}','catalog.applications','created_at','SELECT')") == 't',
          'incompatible inherited ACL refused atomically, without repair')
    cluster.query('REVOKE SELECT (created_at) ON catalog.applications FROM PUBLIC')
    cluster.secrets[SERVICE] = secrets.token_urlsafe(32)
    password = cluster.secrets[SERVICE]
    applied = cluster.psql(script=SCRIPT, reply=password + '\n' + password + '\n', apply=True)
    check(applied.returncode == 0, 'C2 creates service and exchanges ephemeral SCRAM password')
    check(cluster.query(f"SELECT rolcanlogin AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolinherit "
                        f"OR rolreplication OR rolbypassrls) AND rolconnlimit=3 AND rolpassword LIKE 'SCRAM-SHA-256$%' "
                        f"FROM pg_authid WHERE rolname='{SERVICE}'") == 't', 'restricted LOGIN with SCRAM verifier')
    check(cluster.query(f"SELECT count(*)=1 AND bool_and(roleid='{READER}'::regrole AND NOT inherit_option "
                        f"AND set_option AND NOT admin_option) FROM pg_auth_members WHERE member='{SERVICE}'::regrole")
          == 't', 'exactly one membership: INHERIT FALSE, SET TRUE, ADMIN FALSE')
    identity = service_sql(cluster, "SELECT session_user,current_user,current_setting('transaction_read_only')")
    check(identity.returncode == 0 and f'{SERVICE}|{SERVICE}|off' in identity.stdout,
          'real TCP authentication as service, read-write transaction')
    elevated = service_sql(cluster, 'SELECT session_user,current_user', reader=True)
    check(elevated.returncode == 0 and f'{SERVICE}|{READER}' in elevated.stdout,
          'SET ROLE changes current_user while preserving authenticated service identity')
    cluster.secrets[SERVICE] = secrets.token_urlsafe(32)
    wrong = service_sql(cluster, 'SELECT 1')
    cluster.secrets[SERVICE] = password
    check(wrong.returncode != 0 and 'password authentication failed' in wrong.stderr,
          'wrong password rejected: host authentication does not trust service')
    for table, columns in COLUMNS.items():
        selected = ','.join(columns.split())
        denied = service_sql(cluster, f'SELECT {selected} FROM {table}')
        check(denied.returncode != 0 and '42501' in denied.stderr, table + ': no access before SET ROLE')
        allowed = service_sql(cluster, f'SELECT json_build_array({selected}) FROM {table}', reader=True)
        expected = cluster.query(f'SELECT json_build_array({selected}) FROM {table}').splitlines()
        check(allowed.returncode == 0 and sorted(x for x in allowed.stdout.splitlines() if x.startswith('['))
              == sorted(expected) and len(expected) > 0, table + ': authenticated reader obtains public catalog')
        for sql in [f'SELECT * FROM {table}', f'INSERT INTO {table} DEFAULT VALUES',
                    f'UPDATE {table} SET {columns.split()[0]}={columns.split()[0]} WHERE false',
                    f'DELETE FROM {table} WHERE false', f'TRUNCATE {table}']:
            denied = service_sql(cluster, 'BEGIN; ' + sql + '; ROLLBACK', reader=True)
            check(denied.returncode != 0 and '42501' in denied.stderr, table + ': denies ' + sql.split()[0])
    for table in sorted(PROTECTED | {'operations.flyway_schema_history'}):
        denied = service_sql(cluster, f'SELECT * FROM {table}', reader=True)
        check(denied.returncode != 0 and '42501' in denied.stderr, table + ': protected by ACL after SET ROLE')
    for reader in [False, True]:
        for sql in ['CREATE SCHEMA c2_forbidden', 'CREATE TEMP TABLE c2_forbidden(id integer)',
                    'CREATE TABLE catalog.c2_forbidden(id integer)', 'CREATE ROLE c2_forbidden',
                    'CREATE DATABASE c2_forbidden', 'ALTER TABLE catalog.applications ADD COLUMN c2_forbidden text',
                    f'GRANT {READER} TO ls_core_app_dev',
                    *('SET ROLE ' + r for r in ['postgres','ls_core_migrator_dev','ls_core_app_dev',
                                              'pg_read_all_data','pg_write_all_data','pg_signal_backend'])]:
            denied = service_sql(cluster, sql, reader=reader)
            check(denied.returncode != 0 and '42501' in denied.stderr,
                  f'ACL denies DDL/escalation (reader={reader}): ' + sql)
    reset = service_sql(cluster, f'SELECT id FROM catalog.applications; RESET ROLE; SELECT id FROM catalog.applications',
                        reader=True)
    check(reset.returncode != 0 and '42501' in reset.stderr, 'RESET ROLE removes catalog access')
    for database in ['postgres', 'template1']:
        denied = cluster.psql(sql='SELECT 1', user=SERVICE, database=database)
        check(denied.returncode != 0 and 'permission denied for database' in denied.stderr,
              'authenticated service cannot connect to ' + database)
    for table in sorted(PROTECTED | set(COLUMNS) | {'operations.flyway_schema_history'}):
        schema, name = table.split('.')
        check(cluster.query("SELECT NOT has_table_privilege('" + SERVICE + "',c.oid,"
                            "'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN') "
                            f"AND NOT has_any_column_privilege('{SERVICE}',c.oid,'SELECT,INSERT,UPDATE,REFERENCES') "
                            "AND NOT has_any_column_privilege('ls_core_app_dev',c.oid,'SELECT,INSERT,UPDATE,REFERENCES') "
                            "AND NOT has_table_privilege('ls_core_app_dev',c.oid,"
                            "'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN') "
                            "FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                            f"WHERE n.nspname='{schema}' AND c.relname='{name}'") == 't',
              table + ': no direct service privileges and runtime still isolated')
    for role in [SERVICE, READER]:
        denied = service_sql(cluster, 'SET ROLE ' + role, user='ls_core_app_dev')
        check(denied.returncode != 0 and '42501' in denied.stderr, 'runtime cannot acquire ' + role)
    repeat = cluster.psql(script=SCRIPT, apply=True)
    check(repeat.returncode != 0 and 'already exists' in repeat.stderr, 'existing service refused without adoption')
    check((relations(cluster), history(cluster)) == before
          and cluster.query("SELECT row_to_json(r) FROM pg_roles r WHERE rolname IN "
                            f"('ls_core_app_dev','ls_core_migrator_dev','{READER}') ORDER BY rolname") == roles,
          'C1 attributes, runtime, migrator, table ACLs, RLS and migration history unchanged')


def main():
    with disposable_cluster() as cluster:
        probe = cluster.psql(sql="SELECT json_build_object('data',current_setting('data_directory'),"
                                 "'port',inet_server_port(),'host',inet_server_addr())", database='postgres')
        check(probe.returncode == 0, 'temporary server identity query succeeds')
        identity = json.loads(probe.stdout.strip())
        check(Path(identity['data']).resolve() == cluster.data.resolve() and identity['port'] == cluster.port
              and identity['host'] == '127.0.0.1', 'own temporary PG17 endpoint verified')
        prepare_fixture(cluster)
        for database in ['postgres','template1']:
            check(cluster.psql(sql=f'REVOKE CONNECT, TEMPORARY ON DATABASE {database} FROM PUBLIC').returncode == 0,
                  'fixture-only ACL preparation: ' + database)
        password = cluster.secrets['ls_core_migrator_dev']
        check(cluster.psql(script=ROOT / 'scripts/migrations/provision.sql',
                           reply=password + '\n' + password + '\n', apply=True).returncode == 0, 'fixture-only migrator')
        check(cluster.flyway('migrate', target='9').returncode == 0, 'real V1-V9 baseline')
        check(cluster.psql(script=C1_SCRIPT, apply=True).returncode == 0, 'unchanged C1 fixture preparation')
        test_catalog_service(cluster)
    print('Focused C2 checks passed; disposable cluster stopped and removed.', flush=True)


if __name__ == '__main__':
    main()

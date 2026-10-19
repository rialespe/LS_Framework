"""Focused V8 -> V9 regression. Standalone execution creates only a new PG17 cluster.

Run: python scripts/tests/postgres_rls_contract.py
Also runs after the historical V8 checks in test_postgres_migrations.py (CI).
"""

import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
from test_postgres_migrations import (
    ROOT, SOURCE, SCHEMAS, check, disposable_cluster, history, prepare_fixture,
)
from postgres_seed_contract import data_snapshot


# Independent reviewed contract; never derive expectations from the migration under test.
PROTECTED = set('''identity.users identity.user_credentials platform.administrators
tenant.tenants tenant.branches tenant.memberships tenant.branch_memberships tenant.administrators
catalog.tenant_applications catalog.tenant_modules security.roles security.role_permissions
security.profiles security.profile_roles security.branch_profile_assignments security.sessions
security.session_contexts integration.api_keys integration.api_key_permissions audit.events
operations.maintenance_windows operations.operation_runs'''.split())
GLOBAL = set('''catalog.applications catalog.modules security.permissions security.role_templates
security.role_template_permissions security.profile_templates security.profile_template_roles'''.split())


def relations(cluster):
    schemas = ','.join("'" + schema + "'" for schema in sorted(SCHEMAS))
    rows = cluster.query(
        "SELECT json_build_object('name',n.nspname||'.'||c.relname,'enabled',c.relrowsecurity,"
        "'forced',c.relforcerowsecurity,'owner',c.relowner,'acl',c.relacl) "
        "FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
        f"WHERE c.relkind='r' AND n.nspname IN ({schemas}) "
        "AND c.relname<>'flyway_schema_history' ORDER BY n.nspname,c.relname")
    return {row['name']: row for row in map(json.loads, rows.splitlines())}


def test_rls(cluster):
    check(len(PROTECTED) == 22 and len(GLOBAL) == 7 and not PROTECTED & GLOBAL,
          'V9 independent contract: 22 protected and seven global tables')
    before = relations(cluster)
    check(set(before) == PROTECTED | GLOBAL, 'V8 has exactly the 29 contracted tables')
    check(all(not row['enabled'] and not row['forced'] for row in before.values()),
          'V8 baseline has no RLS flags')
    data = data_snapshot(cluster, sorted(before))
    roles = cluster.query("SELECT row_to_json(r) FROM pg_roles r WHERE rolname IN "
                          "('ls_core_app_dev','ls_core_migrator_dev') ORDER BY rolname")
    old_history = history(cluster).splitlines()
    # Existing synthetic row proves FORCE/default-deny, independently of the runtime ACL denial.
    cluster.query("INSERT INTO tenant.tenants(name,status) VALUES ('V9 isolated fixture','PENDING')")
    check(cluster.query('SELECT count(*) FROM tenant.tenants', 'ls_core_migrator_dev') == '1',
          'table owner can see fixture before V9')
    check(cluster.flyway('migrate', location=SOURCE, target='9').returncode == 0,
          'real Flyway applies V9 as the restricted migrator')
    after = relations(cluster)
    check(set(after) == set(before), 'V9 creates or removes no business tables')
    for table in sorted(PROTECTED):
        check(after[table] == dict(before[table], enabled=True, forced=True),
              table + ': ENABLE/FORCE only; owner and ACL unchanged')
    for table in sorted(GLOBAL):
        check(after[table] == before[table], table + ': global configuration unchanged')
    check(cluster.query("SELECT count(*) FROM pg_policy p JOIN pg_class c ON c.oid=p.polrelid "
                        "JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ("
                        + ','.join("'" + s + "'" for s in sorted(SCHEMAS)) + ')') == '0',
          'no policies, including no PERMISSIVE policies')
    for table in sorted(before):
        schema, name = table.split('.')
        # OIDs avoid requiring schema USAGE just to inspect the runtime's effective privileges.
        check(cluster.query("SELECT NOT (has_table_privilege(current_user,c.oid,"
                            "'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN') "
                            "OR has_any_column_privilege(current_user,c.oid,"
                            "'SELECT,INSERT,UPDATE,REFERENCES')) FROM pg_class c "
                            "JOIN pg_namespace n ON n.oid=c.relnamespace "
                            f"WHERE n.nspname='{schema}' AND c.relname='{name}'", 'ls_core_app_dev') == 't',
              table + ': runtime has no table or column business privileges')
    check(cluster.query("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles "
                        "WHERE rolname=current_user", 'ls_core_app_dev') == 't'
          and cluster.query("SELECT count(*) FROM pg_auth_members WHERE member="
                            "(SELECT oid FROM pg_roles WHERE rolname='ls_core_app_dev')") == '0',
          'runtime has no superuser/BYPASSRLS or role memberships')
    denied = cluster.psql(script='-',
                          reply='\\set VERBOSITY verbose\nSELECT * FROM tenant.tenants;\n',
                          user='ls_core_app_dev')
    check(denied.returncode != 0 and '42501' in denied.stderr,
          'runtime SELECT denied by ACL (SQLSTATE 42501), not evidence of RLS filtering')
    check(cluster.query('SELECT count(*) FROM tenant.tenants', 'ls_core_migrator_dev') == '0',
          'FORCE/default-deny hides existing row from its owner despite owner SELECT privilege')
    rejected = cluster.psql(sql="SET row_security=off; SELECT * FROM tenant.tenants;",
                            user='ls_core_migrator_dev')
    check(rejected.returncode != 0 and 'row-level security' in rejected.stderr,
          'owner cannot bypass FORCE with row_security=off')
    cluster.query("DELETE FROM tenant.tenants WHERE name='V9 isolated fixture'")
    check(data_snapshot(cluster, sorted(before)) == data, 'all business data and seven catalog seeds unchanged')
    check(cluster.query("SELECT row_to_json(r) FROM pg_roles r WHERE rolname IN "
                        "('ls_core_app_dev','ls_core_migrator_dev') ORDER BY rolname") == roles,
          'V9 leaves role attributes unchanged')
    new_history = history(cluster).splitlines()
    check(new_history[:-1] == old_history and json.loads(new_history[-1])['version'] == '9',
          'V1-V8 history/checksums unchanged; only V9 appended')
    check(cluster.flyway('validate', location=SOURCE, target='9').returncode == 0,
          'Flyway validates V1-V9 checksums')


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
        print('Disposable endpoint:', cluster.data, cluster.port, flush=True)
        prepare_fixture(cluster)
        for database in ['postgres', 'template1']:
            check(cluster.psql(sql=f'REVOKE CONNECT, TEMPORARY ON DATABASE {database} FROM PUBLIC').returncode == 0,
                  'fixture-only database ACL preparation: ' + database)
        password = cluster.secrets['ls_core_migrator_dev']
        check(cluster.psql(script=ROOT / 'scripts/migrations/provision.sql',
                           reply=password + '\n' + password + '\n', apply=True).returncode == 0,
              'provision restricted roles only inside new fixture')
        check(cluster.flyway('migrate', target='8').returncode == 0, 'real Flyway V1-V8 baseline')
        test_rls(cluster)
    print('Focused V9 checks passed; own disposable cluster stopped and removed.', flush=True)


if __name__ == '__main__':
    main()

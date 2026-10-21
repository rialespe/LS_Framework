"""Focused V8 -> V9 regression. Standalone execution creates only a new PG17 cluster.

Run: python scripts/tests/postgres_rls_contract.py
Also runs after the historical V8 checks in test_postgres_migrations.py (CI).
"""

import json
from pathlib import Path
import sys
from uuid import uuid4

sys.dont_write_bytecode = True
from test_postgres_migrations import (
    ROOT, SOURCE, SCHEMAS, check, disposable_cluster, history, prepare_fixture,
)
from postgres_seed_contract import APP, data_snapshot


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


def write_fixture(cluster):
    """Two synthetic tenants and one branch each, created by their owner before V9."""
    ids = {'tenant.tenants': [], 'tenant.branches': []}
    for label in ['A', 'B']:
        tenant, branch = str(uuid4()), str(uuid4())
        ids['tenant.tenants'].append(tenant)
        ids['tenant.branches'].append(branch)
        cluster.query(
            f"INSERT INTO tenant.tenants(id,name,status) VALUES ('{tenant}','V9 fixture {label}','PENDING'); "
            "INSERT INTO tenant.branches(id,tenant_id,code,name,status) "
            f"VALUES ('{branch}','{tenant}','D1-{label}','V9 branch {label}','ACTIVE')",
            'ls_core_migrator_dev')
    for table in ids:
        check(cluster.query(f'SELECT count(*) FROM {table}', 'ls_core_migrator_dev') == '2',
              table + ': owner sees both A/B fixture rows before V9')
    return ids


def test_owner_dml(cluster, ids):
    """Committed attempts: admin snapshots prove filtering, not ACL denial or rollback.

    This is V9 default-deny coverage only; it does not establish G2/G3 or close F03.
    No tenant selector, policy, GRANT or new role is involved.
    """
    tables = sorted(ids)
    physical = data_snapshot(cluster, tables)
    for table in tables:
        check(len(physical[table].splitlines()) == 2, table + ': administrator sees both physical A/B rows')
        check(cluster.query(
            "SELECT c.relowner=(SELECT oid FROM pg_roles WHERE rolname=current_user) "
            "AND NOT r.rolsuper AND NOT r.rolbypassrls AND row_security_active(c.oid) "
            "AND has_schema_privilege(current_user,'tenant','USAGE') "
            "AND has_table_privilege(current_user,c.oid,'SELECT') "
            "AND has_table_privilege(current_user,c.oid,'INSERT') "
            "AND has_table_privilege(current_user,c.oid,'UPDATE') "
            "AND has_table_privilege(current_user,c.oid,'DELETE') "
            "FROM pg_class c JOIN pg_roles r ON r.rolname=current_user "
            f"WHERE c.oid='{table}'::regclass", 'ls_core_migrator_dev') == 't',
              table + ': restricted owner has all four DML privileges and active FORCE RLS')
        check(cluster.query(f'SELECT count(*) FROM {table}', 'ls_core_migrator_dev') == '0',
              table + ': FORCE/default-deny hides both A/B rows from owner')
        if table == 'tenant.tenants':
            inserts = ["INSERT INTO tenant.tenants(id,name,status) "
                       f"VALUES ('{uuid4()}','V9 denied insert','PENDING')"]
        else:
            inserts = ["INSERT INTO tenant.branches(id,tenant_id,code,name,status) "
                       f"VALUES ('{uuid4()}','{tenant}','D1-denied','V9 denied branch','ACTIVE')"
                       for tenant in ids['tenant.tenants']]
        predicate = 'id IN (' + ','.join("'" + value + "'" for value in ids[table]) + ')'
        statements = {
            'SELECT': f'SELECT * FROM {table} WHERE {predicate}',
            'INSERT': inserts[0],
            'UPDATE': f"UPDATE {table} SET name='V9 unauthorized change' WHERE {predicate}",
            'DELETE': f'DELETE FROM {table} WHERE {predicate}',
        }
        for insert in inserts:
            rejected = cluster.psql(script='-', reply='\\set VERBOSITY verbose\n' + insert + ';\n',
                                    user='ls_core_migrator_dev')
            check(rejected.returncode != 0 and '42501' in rejected.stderr
                  and 'row-level security' in rejected.stderr,
                  table + ': owner INSERT denied by RLS (SQLSTATE 42501), not ACL')
            check(data_snapshot(cluster, tables) == physical,
                  table + ': administrator confirms rejected INSERT left no physical changes')
        for operation in ['UPDATE', 'DELETE']:
            changed = cluster.query('WITH changed AS (' + statements[operation]
                                    + ' RETURNING id) SELECT count(*) FROM changed', 'ls_core_migrator_dev')
            check(changed == '0', table + ': owner ' + operation + ' affects zero protected A/B rows')
            check(data_snapshot(cluster, tables) == physical,
                  table + ': administrator confirms ' + operation + ' left both A/B rows unchanged')
        for operation, statement in statements.items():
            denied = cluster.psql(script='-', reply='\\set VERBOSITY verbose\n' + statement + ';\n',
                                  user='ls_core_app_dev')
            check(denied.returncode != 0 and '42501' in denied.stderr
                  and 'permission denied' in denied.stderr and 'row-level security' not in denied.stderr,
                  table + ': runtime ' + operation + ' denied by ACL (SQLSTATE 42501), not RLS')
        rejected = cluster.psql(sql=f'SET row_security=off; SELECT * FROM {table};',
                                user='ls_core_migrator_dev')
        check(rejected.returncode != 0 and 'row-level security' in rejected.stderr,
              table + ': owner cannot bypass FORCE with row_security=off')
    check(data_snapshot(cluster, tables) == physical, 'all DML attempts leave physical A/B fixture unchanged')


def test_deny_matrix(cluster, ids):
    """D2: direct SQL under ACL denial and FORCE RLS, with attacker-controlled GUCs.

    D1 supplies nonempty UPDATE/DELETE evidence for tenants/branches. Other protected
    tables stay empty: zero affected rows there is not cross-tenant authorization proof.
    DEFAULT VALUES probes the INSERT RLS gate, not business constraint validity.
    """
    physical = data_snapshot(cluster, sorted(PROTECTED | GLOBAL))
    columns = {}
    for table in sorted(PROTECTED):
        columns[table] = cluster.query(
            "SELECT quote_ident(attname) FROM pg_attribute "
            f"WHERE attrelid='{table}'::regclass AND attnum>0 AND NOT attisdropped "
            "ORDER BY attnum LIMIT 1")
        check(cluster.query(
            "SELECT c.relowner=(SELECT oid FROM pg_roles WHERE rolname=current_user) "
            "AND row_security_active(c.oid) "
            "AND has_schema_privilege(current_user,c.relnamespace,'USAGE') "
            "AND has_table_privilege(current_user,c.oid,'SELECT') "
            "AND has_table_privilege(current_user,c.oid,'INSERT') "
            "AND has_table_privilege(current_user,c.oid,'UPDATE') "
            "AND has_table_privilege(current_user,c.oid,'DELETE') "
            f"FROM pg_class c WHERE c.oid='{table}'::regclass", 'ls_core_migrator_dev') == 't',
              table + ': D2 owner has DML ACLs and active FORCE RLS')
    check(cluster.query(
        "SELECT count(*) FROM pg_roles WHERE rolname IN "
        "('ls_core_app_dev','ls_core_migrator_dev') AND "
        "(rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication)") == '0'
        and cluster.query(
            "SELECT count(*) FROM pg_auth_members WHERE member IN "
            "(SELECT oid FROM pg_roles WHERE rolname IN "
            "('ls_core_app_dev','ls_core_migrator_dev'))") == '0',
        'D2 runtime and owner have no bypass attributes or inherited role paths')
    scenarios = [
        ('unset', {}),
        ('forged A', {'ls.tenant_id': ids['tenant.tenants'][0],
                      'ls.branch_id': ids['tenant.branches'][0]}),
        ('forged B', {'ls.tenant_id': ids['tenant.tenants'][1],
                      'ls.branch_id': ids['tenant.branches'][1]}),
        ('forged A/B mismatch', {'ls.tenant_id': ids['tenant.tenants'][0],
                                 'ls.branch_id': ids['tenant.branches'][1]}),
    ]
    for label, selectors in scenarios:
        settings = dict(selectors)
        if settings:
            settings.update({'ls.user_id': str(uuid4()), 'ls.session_id': str(uuid4()),
                             'ls.is_admin': 'true'})
        prefix = ''.join(f"SET {key}='{value}'; " for key, value in settings.items())
        # Verify GUCs in the same physical session as each operation; none is trusted.
        expected = ' AND '.join(
            f"current_setting('{key}',true) IS NOT DISTINCT FROM "
            + ("'" + settings[key] + "'" if key in settings else 'NULL')
            for key in ['ls.tenant_id', 'ls.branch_id', 'ls.user_id', 'ls.session_id', 'ls.is_admin'])
        prefix += f'SELECT 1 / (({expected})::integer); '
        for table in sorted(PROTECTED):
            if not settings and table in ids:
                continue  # Existing D1 already covers these baseline operations.
            column = columns[table]
            statements = {
                'SELECT': f'SELECT count(*) FROM {table}',
                'INSERT': f'INSERT INTO {table} DEFAULT VALUES',
                'UPDATE': f'WITH changed AS (UPDATE {table} SET {column}={column} '
                          'RETURNING 1) SELECT count(*) FROM changed',
                'DELETE': f'WITH changed AS (DELETE FROM {table} RETURNING 1) '
                          'SELECT count(*) FROM changed',
            }
            for operation, statement in statements.items():
                for role in ['ls_core_app_dev', 'ls_core_migrator_dev']:
                    result = cluster.psql(script='-', reply='\\set VERBOSITY verbose\n'
                                          + prefix + statement + ';\n', user=role)
                    context = f'{label}: {table} {role} {operation}'
                    if role == 'ls_core_app_dev':
                        check(result.returncode != 0 and '42501' in result.stderr
                              and 'permission denied' in result.stderr
                              and 'row-level security' not in result.stderr,
                              context + ': ACL denial (42501), not RLS')
                    elif operation == 'INSERT':
                        check(result.returncode != 0 and '42501' in result.stderr
                              and 'row-level security' in result.stderr
                              and 'permission denied' not in result.stderr,
                              context + ': RLS denial (42501), not ACL/constraints')
                    else:
                        check(result.returncode == 0 and result.stdout.splitlines()[-1] == '0',
                              context + ': FORCE RLS exposes/changes zero rows')
        check(data_snapshot(cluster, sorted(PROTECTED | GLOBAL)) == physical,
              label + ': administrator confirms all 29 physical tables unchanged')


def test_real_context_denied(cluster, ids):
    """D3.1: forge LFD-28's nine real keys, never authenticated identities.

    One complete SESSION pass covers all protected tables; variations use D1's
    nonempty A/B tables only. LOCAL settings and probes share an explicit transaction.
    """
    keys = ('ls.tenant_id', 'ls.application_id', 'ls.branch_id', 'ls.origin',
            'ls.actor_id', 'ls.credential_id', 'ls.session_context_id',
            'ls.membership_id', 'ls.branch_membership_id')
    session = dict(zip(keys, (ids['tenant.tenants'][0], APP, ids['tenant.branches'][0],
                             'SESSION', *(str(uuid4()) for _ in range(5)))))
    key_id = str(uuid4())
    scenarios = [
        ('SESSION complete', session, sorted(PROTECTED)),
        ('API_KEY branch empty', dict(session, **{
            'ls.origin': 'API_KEY', 'ls.actor_id': key_id, 'ls.credential_id': key_id,
            'ls.branch_id': '', 'ls.session_context_id': '', 'ls.membership_id': '',
            'ls.branch_membership_id': ''}), sorted(ids)),
        ('SESSION A/B contradiction', dict(session, **{
            'ls.branch_id': ids['tenant.branches'][1]}), sorted(ids)),
        ('SESSION membership absent', {k: v for k, v in session.items()
                                       if k != 'ls.membership_id'}, sorted(ids)),
        ('SESSION origin changed only', dict(session, **{'ls.origin': 'API_KEY'}), sorted(ids)),
    ]
    physical = data_snapshot(cluster, sorted(PROTECTED | GLOBAL))
    definitions = relations(cluster)
    columns = {table: cluster.query(
        "SELECT quote_ident(attname) FROM pg_attribute "
        f"WHERE attrelid='{table}'::regclass AND attnum>0 AND NOT attisdropped "
        "ORDER BY attnum LIMIT 1") for table in sorted(PROTECTED)}
    for label, settings, tables in scenarios:
        setup = ''.join(f"SELECT set_config('{k}','{v}',true);\n" for k, v in settings.items())
        expected = ' AND '.join(
            f"current_setting('{k}',true) IS NOT DISTINCT FROM "
            + ("'" + settings[k] + "'" if k in settings else 'NULL') for k in keys)
        for table in tables:
            column = columns[table]
            statements = {
                'SELECT': f"SELECT 'D3_ROWS:' || count(*) FROM {table}",
                'INSERT': f'INSERT INTO {table} DEFAULT VALUES',
                'UPDATE': f'WITH changed AS (UPDATE {table} SET {column}={column} '
                          "RETURNING 1) SELECT 'D3_ROWS:' || count(*) FROM changed",
                'DELETE': f'WITH changed AS (DELETE FROM {table} RETURNING 1) '
                          "SELECT 'D3_ROWS:' || count(*) FROM changed",
            }
            for operation, statement in statements.items():
                for role in ['ls_core_app_dev', 'ls_core_migrator_dev']:
                    # Only the probe may fail. Capture its SQLSTATE before ending the
                    # transaction; rollback errors must not masquerade as RLS denial.
                    script = ('\\set VERBOSITY verbose\nBEGIN;\n' + setup
                              + f"SELECT 'D3_CONTEXT_OK' WHERE {expected};\n"
                              + '\\set ON_ERROR_STOP off\n' + statement + ';\n'
                              + '\\set d3_state :SQLSTATE\n\\set ON_ERROR_STOP on\n'
                              + '\\if :ERROR\nROLLBACK;\n\\else\nCOMMIT;\n\\endif\n'
                              + '\\echo D3_SQLSTATE :d3_state\n')
                    result = cluster.psql(script='-', reply=script, user=role)
                    lines = result.stdout.splitlines()
                    context = f'D3.1 {label}: {table} {role} {operation}'
                    denied = role == 'ls_core_app_dev' or operation == 'INSERT'
                    check(result.returncode == 0 and lines.count('BEGIN') == 1
                          and lines.count('D3_CONTEXT_OK') == 1
                          and lines.count('ROLLBACK' if denied else 'COMMIT') == 1
                          and lines.count('D3_SQLSTATE ' + ('42501' if denied else '00000')) == 1,
                          context + ': nine keys verified in probe transaction; clean completion')
                    if role == 'ls_core_app_dev':
                        check('42501' in result.stderr and 'permission denied' in result.stderr
                              and 'row-level security' not in result.stderr and '25P02' not in result.stderr,
                              context + ': ACL denial, not RLS/constraint/aborted transaction')
                    elif operation == 'INSERT':
                        check('42501' in result.stderr and 'row-level security' in result.stderr
                              and 'permission denied' not in result.stderr and '25P02' not in result.stderr,
                              context + ': RLS denial, not ACL/constraint/aborted transaction')
                    else:
                        check(not result.stderr and lines.count('D3_ROWS:0') == 1,
                              context + ': FORCE RLS exposes/changes zero rows')
        check(data_snapshot(cluster, sorted(PROTECTED | GLOBAL)) == physical,
              'D3.1 ' + label + ': administrator confirms all 29 physical tables unchanged')
    check(relations(cluster) == definitions,
          'D3.1 all 29 table owners, ACLs and ENABLE/FORCE flags unchanged')


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
    ids = write_fixture(cluster)
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
    test_owner_dml(cluster, ids)
    test_deny_matrix(cluster, ids)
    test_real_context_denied(cluster, ids)
    # Administrator deletes only this fixture, child first; never broad DELETE/TRUNCATE.
    for table in ['tenant.branches', 'tenant.tenants']:
        predicate = 'id IN (' + ','.join("'" + value + "'" for value in ids[table]) + ')'
        check(cluster.query(f'WITH removed AS (DELETE FROM {table} WHERE {predicate} RETURNING id) '
                            'SELECT count(*) FROM removed') == '2',
              table + ': administrator cleans exactly its two fixture rows')
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

"""F04/F07/F08 regressions on V28, with fresh database copies inside one owned PG17.

No caller-supplied connection is accepted. Database reset is restricted to the
cluster created by this process; the immutable template has no functional CONNECT.
"""
import os
import secrets
import sys

sys.dont_write_bytecode = True
from test_postgres_migrations import BACKEND, ROOT, check, disposable_cluster, environment
from postgres_application_login_contract import run_login_integration

sys.path.insert(0, str(ROOT / 'scripts/pilot'))
from start_connected_pilot import prepare_database


def run_f04(cluster):
    env = {k: v for k, v in environment().items() if not k.upper().startswith('LS_RECOVERY_TEST_')}
    env.update(LS_RECOVERY_TEST_ROOT=str(cluster.root), LS_RECOVERY_TEST_PORT=str(cluster.port))
    for role, password in cluster.secrets.items():
        env['LS_RECOVERY_TEST_' + role.upper()] = password
    command = [str(BACKEND / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(BACKEND / 'mvnw')]
    result = cluster.run(command + ['-B', '-ntp', '-f', str(BACKEND / 'pom.xml'), '-Dtest=PasswordRecoveryPostgresTest', 'verify'], env=env, timeout=180)
    if result.returncode:
        for line in result.stdout.splitlines():
            if 'PasswordRecoveryPostgresTest.java:' in line or 'Tests run:' in line or '[checkstyle]' in line:
                print(line.encode('ascii', errors='backslashreplace').decode('ascii'))
    check(result.returncode == 0 and 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' in result.stdout,
          'F04 recovery/lockouts regression on V28; one test, no skips')


def main():
    with disposable_cluster() as cluster:
        root = cluster.root.resolve()
        check(cluster.port not in (5432, 55432) and root.name.startswith('ls-core-migrations-test-')
              and (root / 'data/PG_VERSION').read_text().strip() == '17', 'owned V28 regression cluster only')
        prepare_database(cluster)
        reply = ''
        for role in ('ls_core_platform_mutator_dev', 'ls_core_platform_security_log_dev'):
            cluster.secrets[role] = secrets.token_urlsafe(32)
            reply += (cluster.secrets[role] + '\n') * 2
        check(cluster.psql(script=ROOT / 'scripts/migrations/activate_platform_executor.sql', apply=True, reply=reply).returncode == 0,
              'owned F06 channels for regression fixtures')
        check(cluster.psql(database='postgres', sql='CREATE DATABASE lfd46_pristine TEMPLATE ls_core_dev').returncode == 0, 'owned pristine V28 copy')
        check(cluster.psql(database='postgres', sql='REVOKE ALL ON DATABASE lfd46_pristine FROM PUBLIC').returncode == 0, 'no functional access to pristine copy')
        tests = ['F04', 'TenantRegistrationPostgresTest', 'TenantLifecyclePostgresTest', 'TenantAdministratorsPostgresTest',
                 'BranchAdministrationPostgresTest', 'BranchConfigurationPostgresTest', 'InactiveBranchPostgresTest', 'TenantUsersPostgresTest']
        for index, test in enumerate(tests):
            if index:
                check(cluster.psql(database='postgres', sql='DROP DATABASE ls_core_dev WITH (FORCE)').returncode == 0, 'reset owned regression database')
                check(cluster.psql(database='postgres', sql='CREATE DATABASE ls_core_dev TEMPLATE lfd46_pristine').returncode == 0, 'fresh V28 regression database')
                check(cluster.psql(database='postgres', sql='REVOKE ALL ON DATABASE ls_core_dev FROM PUBLIC').returncode == 0, 'restored database boundary')
                roles = ','.join(role for role in cluster.secrets if role == 'postgres' or role.startswith('ls_core_'))
                check(cluster.psql(database='postgres', sql='GRANT CONNECT ON DATABASE ls_core_dev TO ' + roles).returncode == 0, 'owned channel CONNECT')
            if test == 'F04':
                run_f04(cluster)
            else:
                run_login_integration(cluster, test)
        check(cluster.psql(script=ROOT / 'scripts/migrations/deactivate_platform_executor.sql', apply=True).returncode == 0, 'owned F06 channels retired')
        check(cluster.psql(script=ROOT / 'scripts/migrations/seal_platform_bootstrap.sql', apply=True).returncode == 0, 'owned bootstrap sealed')
    print('V28 regressions PASS: F04 and LFD-40 through LFD-45; own PG17 removed.', flush=True)


if __name__ == '__main__':
    main()

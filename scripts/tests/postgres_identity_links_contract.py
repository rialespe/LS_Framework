"""LFD-47: owned PG17 V29, focused consent integration and LFD-46 regression."""
import secrets
import sys
sys.dont_write_bytecode = True
from test_postgres_migrations import ROOT, check, disposable_cluster
from postgres_application_login_contract import run_login_integration
sys.path.insert(0, str(ROOT / 'scripts/pilot'))
from start_connected_pilot import prepare_database


def main():
    with disposable_cluster() as cluster:
        check(cluster.port not in (5432, 55432), 'owned ephemeral PG17 only')
        prepare_database(cluster, version=29)
        if '--migrate-only' in sys.argv:
            return
        reply = ''
        for role in ('ls_core_platform_mutator_dev', 'ls_core_platform_security_log_dev'):
            cluster.secrets[role] = secrets.token_urlsafe(32)
            reply += (cluster.secrets[role] + '\n') * 2
        check(cluster.psql(script=ROOT / 'scripts/migrations/activate_platform_executor.sql', apply=True, reply=reply).returncode == 0, 'owned F06 fixture channels')
        check(cluster.psql(database='postgres', sql='CREATE DATABASE lfd47_pristine TEMPLATE ls_core_dev').returncode == 0, 'owned V29 regression copy')
        check(cluster.psql(database='postgres', sql='REVOKE ALL ON DATABASE lfd47_pristine FROM PUBLIC').returncode == 0, 'no runtime CONNECT to pristine')
        for index, test in enumerate(('IdentityLinksPostgresTest', 'TenantUsersPostgresTest')):
            if index:
                check(cluster.psql(database='postgres', sql='DROP DATABASE ls_core_dev WITH (FORCE)').returncode == 0, 'reset owned regression database')
                check(cluster.psql(database='postgres', sql='CREATE DATABASE ls_core_dev TEMPLATE lfd47_pristine').returncode == 0, 'fresh V29 regression database')
                check(cluster.psql(database='postgres', sql='REVOKE ALL ON DATABASE ls_core_dev FROM PUBLIC').returncode == 0, 'restored ACL')
                roles = ','.join(role for role in cluster.secrets if role == 'postgres' or role.startswith('ls_core_'))
                check(cluster.psql(database='postgres', sql='GRANT CONNECT ON DATABASE ls_core_dev TO ' + roles).returncode == 0, 'owned channel CONNECT')
            run_login_integration(cluster, test)
    print('LFD-47 consent and LFD-46 regression PASS on V29; owned PG17 stopped/removed.', flush=True)


if __name__ == '__main__':
    main()

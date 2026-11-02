"""LFD-49: owned PG17 V31, focused consent integration and LFD-46/47 and F05/F07/F08 regressions."""
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
        prepare_database(cluster, version=31)
        if '--migrate-only' in sys.argv:
            return
        reply = ''
        for role in ('ls_core_platform_mutator_dev', 'ls_core_platform_security_log_dev'):
            cluster.secrets[role] = secrets.token_urlsafe(32)
            reply += (cluster.secrets[role] + '\n') * 2
        check(cluster.psql(script=ROOT / 'scripts/migrations/activate_platform_executor.sql', apply=True, reply=reply).returncode == 0, 'owned F06 fixture channels')
        check(cluster.psql(database='postgres', sql='CREATE DATABASE lfd49_pristine TEMPLATE ls_core_dev').returncode == 0, 'owned V31 regression copy')
        check(cluster.psql(database='postgres', sql='REVOKE ALL ON DATABASE lfd49_pristine FROM PUBLIC').returncode == 0, 'no runtime CONNECT to pristine')
        for index, test in enumerate(('TenantRbacPostgresTest', 'TenantAdministratorsPostgresTest', 'TenantUsersPostgresTest', 'IdentityLinksPostgresTest', 'TenantAccessPostgresTest', 'CATALOG')):
            if index:
                check(cluster.psql(database='postgres', sql='DROP DATABASE ls_core_dev WITH (FORCE)').returncode == 0, 'reset owned regression database')
                check(cluster.psql(database='postgres', sql='CREATE DATABASE ls_core_dev TEMPLATE lfd49_pristine').returncode == 0, 'fresh V31 regression database')
                check(cluster.psql(database='postgres', sql='REVOKE ALL ON DATABASE ls_core_dev FROM PUBLIC').returncode == 0, 'restored ACL')
                roles = ','.join(role for role in cluster.secrets if role == 'postgres' or role.startswith('ls_core_'))
                check(cluster.psql(database='postgres', sql='GRANT CONNECT ON DATABASE ls_core_dev TO ' + roles).returncode == 0, 'owned channel CONNECT')
            if test == 'CATALOG':
                from postgres_catalog_backend_contract import test_catalog_backend
                from postgres_catalog_acl_contract import SCRIPT as c1
                from postgres_catalog_service_contract import SCRIPT as c2, SERVICE
                check(cluster.psql(script=c1, apply=True).returncode == 0, 'catalog C1 installation on V31')
                cluster.secrets[SERVICE] = secrets.token_urlsafe(32)
                check(cluster.psql(script=c2, apply=True, reply=(cluster.secrets[SERVICE] + '\n') * 2).returncode == 0, 'restricted catalog C2 on V31')
                test_catalog_backend(cluster)
            else:
                run_login_integration(cluster, test)
    print('LFD-49 RBAC and LFD-46/47 and F05/F07/F08 regressions PASS on V31; owned PG17 stopped/removed.', flush=True)


if __name__ == '__main__':
    main()

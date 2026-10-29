"""LFD-40: one real HTTPS + PG17 registration integration, no historic test methods."""
import secrets
import sys
sys.dont_write_bytecode = True
from test_postgres_migrations import ROOT, SOURCE, check
from postgres_application_login_contract import main, run_login_integration
from postgres_platform_privileged_contract import prepare_platform_privileged


def test_tenant_registration(cluster, test_class='TenantRegistrationPostgresTest'):
    prepare_platform_privileged(cluster)
    reply = ''
    for role in ('ls_core_tenant_registrar_dev', 'ls_core_tenant_registration_log_dev'):
        cluster.secrets[role] = secrets.token_urlsafe(32)
        reply += (cluster.secrets[role] + '\n') * 2
    check(cluster.psql(script=ROOT / 'scripts/migrations/prepare_tenant_registration.sql', apply=True, reply=reply).returncode == 0, 'isolated registration channels')
    migrated = cluster.flyway('migrate', location=SOURCE, target='22')
    if migrated.returncode:
        print('V22 migration failed; controlled Flyway diagnostics:')
        for line in migrated.stdout.splitlines():
            if '[ERROR]' in line and any(s in line for s in ('ERROR:', 'Location', 'Line', 'SQL State')):
                print(line.encode('ascii', errors='backslashreplace').decode('ascii'))
    check(migrated.returncode == 0, 'restricted V22 migration')
    check(cluster.flyway('validate', location=SOURCE, target='22').returncode == 0, 'intact history through V22')
    if test_class in ('TenantRegistrationPostgresTest', 'TenantLifecyclePostgresTest', 'TenantAdministratorsPostgresTest', 'BranchAdministrationPostgresTest'):
        from postgres_tenant_lifecycle_contract import prepare_lifecycle
        prepare_lifecycle(cluster)
        from postgres_tenant_administrators_contract import prepare_administrators
        prepare_administrators(cluster)
        from postgres_branch_administration_contract import prepare_branches
        prepare_branches(cluster)
    reply = ''
    for role in ('ls_core_platform_mutator_dev', 'ls_core_platform_security_log_dev'):
        cluster.secrets[role] = secrets.token_urlsafe(32)
        reply += (cluster.secrets[role] + '\n') * 2
    check(cluster.psql(script=ROOT / 'scripts/migrations/activate_platform_executor.sql', apply=True, reply=reply).returncode == 0, 'owned F06 fixture channel')
    try:
        run_login_integration(cluster, test_class)
    finally:
        check(cluster.psql(script=ROOT / 'scripts/migrations/deactivate_platform_executor.sql', apply=True).returncode == 0, 'exceptional F06 fixture channel retired')
    check(cluster.psql(script=ROOT / 'scripts/migrations/seal_platform_bootstrap.sql', apply=True).returncode == 0, 'bootstrap sealed')


if __name__ == '__main__':
    main('TenantRegistrationPostgresTest')

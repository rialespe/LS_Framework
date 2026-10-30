"""LFD-46: one focused HTTPS/PG17 administrator integration on an owned ephemeral cluster."""
import secrets
import sys
sys.dont_write_bytecode = True
from test_postgres_migrations import ROOT, SOURCE, check
from postgres_application_login_contract import main


def prepare_users(cluster):
    reply = ''
    for role in ('ls_core_tenant_users_dev', 'ls_core_tenant_users_log_dev'):
        cluster.secrets[role] = secrets.token_urlsafe(32)
        reply += (cluster.secrets[role] + '\n') * 2
    check(cluster.psql(script=ROOT / 'scripts/migrations/prepare_tenant_users.sql', apply=True, reply=reply).returncode == 0, 'isolated administrator channels')
    migrated = cluster.flyway('migrate', location=SOURCE, target='28')
    if migrated.returncode:
        for line in migrated.stdout.splitlines():
            if '[ERROR]' in line and any(s in line for s in ('ERROR:', 'Location', 'Line', 'SQL State')):
                print(line.encode('ascii', errors='backslashreplace').decode('ascii'))
    check(migrated.returncode == 0, 'restricted V28 migration')
    check(cluster.flyway('validate', location=SOURCE, target='28').returncode == 0, 'intact V1-V28 history')


if __name__ == '__main__':
    main('TenantUsersPostgresTest')

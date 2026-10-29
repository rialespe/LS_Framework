"""LFD-43: one focused HTTPS/PG17 branch administration integration on an owned ephemeral cluster."""
import secrets
import sys
sys.dont_write_bytecode = True
from test_postgres_migrations import ROOT, SOURCE, check
from postgres_application_login_contract import main


def prepare_branches(cluster):
    reply = ''
    for role in ('ls_core_branch_admin_dev', 'ls_core_branch_admin_log_dev'):
        cluster.secrets[role] = secrets.token_urlsafe(32)
        reply += (cluster.secrets[role] + '\n') * 2
    check(cluster.psql(script=ROOT / 'scripts/migrations/prepare_branch_administration.sql', apply=True, reply=reply).returncode == 0, 'isolated branch administration channels')
    migrated = cluster.flyway('migrate', location=SOURCE, target='25')
    if migrated.returncode:
        for line in migrated.stdout.splitlines():
            if '[ERROR]' in line and any(s in line for s in ('ERROR:', 'Location', 'Line', 'SQL State')):
                print(line.encode('ascii', errors='backslashreplace').decode('ascii'))
    check(migrated.returncode == 0, 'restricted V25 migration')
    check(cluster.flyway('validate', location=SOURCE, target='25').returncode == 0, 'intact V1-V25 history')


if __name__ == '__main__':
    main('BranchAdministrationPostgresTest')

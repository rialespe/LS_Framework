"""LFD-44: one focused HTTPS/PG17 progressive configuration integration on an owned ephemeral cluster."""
import secrets
import sys
sys.dont_write_bytecode = True
from test_postgres_migrations import ROOT, SOURCE, check
from postgres_application_login_contract import main


def prepare_configuration(cluster):
    reply = ''
    for role in ('ls_core_branch_config_dev', 'ls_core_branch_config_log_dev'):
        cluster.secrets[role] = secrets.token_urlsafe(32)
        reply += (cluster.secrets[role] + '\n') * 2
    check(cluster.psql(script=ROOT / 'scripts/migrations/prepare_branch_configuration.sql', apply=True, reply=reply).returncode == 0, 'isolated branch configuration channels')
    migrated = cluster.flyway('migrate', location=SOURCE, target='26')
    if migrated.returncode:
        for line in migrated.stdout.splitlines():
            if '[ERROR]' in line and any(s in line for s in ('ERROR:', 'Location', 'Line', 'SQL State')):
                print(line.encode('ascii', errors='backslashreplace').decode('ascii'))
    check(migrated.returncode == 0, 'restricted V26 migration')
    check(cluster.flyway('validate', location=SOURCE, target='26').returncode == 0, 'intact V1-V26 history')


if __name__ == '__main__':
    main('BranchConfigurationPostgresTest')

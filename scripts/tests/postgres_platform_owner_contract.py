"""One real F04/PG17 platform installation scenario, no historical integration methods."""
import os
import secrets
import sys

sys.dont_write_bytecode = True
from test_postgres_migrations import BACKEND, ROOT, SOURCE, check, environment
from postgres_application_login_contract import main


def test_platform_owner(cluster):
    check(cluster.port not in (5432, 55432), 'platform uses owned ephemeral PG17 only')
    check(cluster.flyway('migrate', location=SOURCE, target='18').returncode == 0, 'intact V1-V18 prerequisites')
    reply = ''
    for role in ('ls_core_platform_bootstrap_dev', 'ls_core_platform_reader_dev'):
        cluster.secrets[role] = secrets.token_urlsafe(32)
        reply += (cluster.secrets[role] + '\n') * 2
    check(cluster.psql(script=ROOT / 'scripts/migrations/prepare_platform_channels.sql', apply=True, reply=reply).returncode == 0,
          'explicit isolated platform channels; no designation')
    result = cluster.flyway('migrate', location=SOURCE, target='19')
    if result.returncode:
        print((result.stdout + result.stderr)[-4000:])
    check(result.returncode == 0, 'restricted migrator applies V19')
    check(cluster.flyway('validate', location=SOURCE, target='19').returncode == 0, 'Flyway validates V1-V19')
    env = {key: value for key, value in environment().items() if not key.upper().startswith(('LS_LOGIN_', 'LS_PLATFORM_', 'LS_AUTHENTICATION_'))}
    env.update(LS_LOGIN_TEST_ROOT=str(cluster.root), LS_LOGIN_TEST_PORT=str(cluster.port))
    for role, password in cluster.secrets.items():
        env['LS_LOGIN_TEST_' + role.upper()] = password
    command = [str(BACKEND / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(BACKEND / 'mvnw')]
    command += ['-B', '-ntp', '-f', str(BACKEND / 'pom.xml'), '-Dtest=PlatformOwnerPostgresTest', 'verify']
    result = cluster.run(command, env=env, timeout=180)
    if result.returncode:
        print('Platform integration failed; credentials, identities and JDBC diagnostics withheld.')
        for line in result.stdout.splitlines():
            if 'PlatformOwnerPostgresTest.java:' in line or 'Tests run:' in line or '[checkstyle]' in line:
                print(line)
    check(result.returncode == 0 and 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' in result.stdout,
          'ONE platform F04/PG17 integration passed; Checkstyle')
    check(cluster.psql(script=ROOT / 'scripts/migrations/seal_platform_bootstrap.sql', apply=True).returncode == 0,
          'installation channel sealed after acknowledged owner commit and physical close')
    check(cluster.query("SELECT NOT rolcanlogin FROM pg_roles WHERE rolname='ls_core_platform_bootstrap_dev'") == 't',
          'bootstrap LOGIN remains disabled')
    check(cluster.psql(sql='SELECT 1', user='ls_core_platform_bootstrap_dev').returncode != 0, 'sealed channel cannot reconnect')


if __name__ == '__main__':
    main('PlatformOwnerPostgresTest')

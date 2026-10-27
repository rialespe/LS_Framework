"""One controlled two-connection PG17 concurrency scenario. No historical test methods."""
import os
import secrets
import sys

sys.dont_write_bytecode = True
from test_postgres_migrations import BACKEND, ROOT, SOURCE, check, environment
from postgres_application_login_contract import main
from postgres_platform_owner_contract import prepare_platform_owner


def test_platform_concurrency(cluster):
    prepare_platform_owner(cluster)
    check(cluster.psql(script=ROOT / 'scripts/migrations/prepare_platform_mutator.sql', apply=True).returncode == 0,
          'fresh dormant future channel; no password or LOGIN')
    check(cluster.flyway('migrate', location=SOURCE, target='20').returncode == 0, 'restricted V20 migration')
    check(cluster.flyway('validate', location=SOURCE, target='20').returncode == 0, 'unchanged V1-V19; V20 validates')
    role = 'ls_core_platform_mutator_dev'
    check(cluster.query("SELECT NOT rolcanlogin AND rolpassword IS NULL FROM pg_authid WHERE rolname='" + role + "'") == 't',
          'future role dormant without credential before disposable-only fixture')
    cluster.secrets[role] = secrets.token_urlsafe(32)
    # Only this owned temporary cluster may issue a transient credential. Never a provision/activation script.
    check(cluster.psql(sql='\\password ' + role, reply=(cluster.secrets[role] + '\n') * 2).returncode == 0,
          'owned disposable fixture SCRAM exchange')
    check(cluster.psql(sql='ALTER ROLE ' + role + ' LOGIN; GRANT CONNECT ON DATABASE ls_core_dev TO ' + role).returncode == 0,
          'exceptional test-only channel enabled inside owned disposable PG17')
    try:
        env = {k: v for k, v in environment().items() if not k.upper().startswith(('LS_LOGIN_', 'LS_PLATFORM_', 'LS_AUTHENTICATION_'))}
        env.update(LS_LOGIN_TEST_ROOT=str(cluster.root), LS_LOGIN_TEST_PORT=str(cluster.port))
        for principal, password in cluster.secrets.items():
            env['LS_LOGIN_TEST_' + principal.upper()] = password
        command = [str(BACKEND / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(BACKEND / 'mvnw')]
        result = cluster.run(command + ['-B', '-ntp', '-f', str(BACKEND / 'pom.xml'),
                            '-Dtest=PlatformConcurrencyPostgresTest', 'verify'], env=env, timeout=180)
        if result.returncode:
            print('Concurrency integration failed; identity, credential and raw SQL diagnostics withheld.')
            for line in result.stdout.splitlines():
                if 'PlatformConcurrencyPostgresTest.java:' in line or 'Tests run:' in line or '[checkstyle]' in line:
                    print(line)
        check(result.returncode == 0 and 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' in result.stdout,
              'ONE real two-JDBC concurrency method passed; Checkstyle')
    finally:
        check(cluster.psql(sql='ALTER ROLE ' + role + ' NOLOGIN PASSWORD NULL; REVOKE CONNECT ON DATABASE ls_core_dev FROM ' + role).returncode == 0,
              'exceptional fixture authority removed even on failure')
        check(cluster.query("SELECT NOT rolcanlogin AND rolpassword IS NULL AND NOT EXISTS(SELECT FROM pg_stat_activity WHERE usename=r.rolname) "
                            "FROM pg_authid r WHERE rolname='" + role + "'") == 't', 'no exceptional connection or credential remains')
    check(cluster.psql(script=ROOT / 'scripts/migrations/seal_platform_bootstrap.sql', apply=True).returncode == 0,
          'owner bootstrap sealed; no repeat installation')


if __name__ == '__main__':
    main('PlatformConcurrencyPostgresTest')

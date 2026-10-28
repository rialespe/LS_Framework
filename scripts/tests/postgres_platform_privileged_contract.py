"""One real F04, V20 concurrency and V21 audit integration on an owned PG17 cluster."""
import os
import secrets
import sys

sys.dont_write_bytecode = True
from test_postgres_migrations import BACKEND, ROOT, SOURCE, check, environment
from postgres_application_login_contract import main
from postgres_platform_owner_contract import prepare_platform_owner


def test_platform_privileged(cluster):
    prepare_platform_owner(cluster)
    for script, version in [('prepare_platform_mutator.sql', '20'), ('prepare_platform_audit.sql', '21')]:
        check(cluster.psql(script=ROOT / 'scripts/migrations' / script, apply=True).returncode == 0, 'dormant channels before V' + version)
        check(cluster.flyway('migrate', location=SOURCE, target=version).returncode == 0, 'restricted migrator applies V' + version)
    check(cluster.flyway('validate', location=SOURCE, target='21').returncode == 0, 'V1-V21 validation without historical edits')
    roles = ('ls_core_platform_mutator_dev', 'ls_core_platform_security_log_dev')
    reply = ''
    for role in roles:
        cluster.secrets[role] = secrets.token_urlsafe(32)
        reply += (cluster.secrets[role] + '\n') * 2
    check(cluster.psql(script=ROOT / 'scripts/migrations/activate_platform_executor.sql', apply=True, reply=reply).returncode == 0,
          'explicit controlled activation; no HTTP role change')
    try:
        env = {k: v for k, v in environment().items() if not k.upper().startswith(('LS_LOGIN_', 'LS_PLATFORM_', 'LS_AUTHENTICATION_'))}
        env.update(LS_LOGIN_TEST_ROOT=str(cluster.root), LS_LOGIN_TEST_PORT=str(cluster.port))
        for role, password in cluster.secrets.items():
            env['LS_LOGIN_TEST_' + role.upper()] = password
        command = [str(BACKEND / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(BACKEND / 'mvnw')]
        result = cluster.run(command + ['-B', '-ntp', '-f', str(BACKEND / 'pom.xml'), '-Dtest=PlatformPrivilegedPostgresTest', 'verify'], env=env, timeout=180)
        if result.returncode:
            print('Privileged integration failed; raw identity, credential and SQL diagnostics withheld.')
            for diagnostic in (cluster.root / 'server.log').read_text(encoding='utf-8', errors='replace').splitlines():
                if 'ERROR:' in diagnostic:
                    primary = diagnostic.split('ERROR:', 1)[1].strip()
                    if primary.startswith(('permission denied', 'new row violates', 'function ', 'column ', 'operator does not exist', 'Invalid platform evidence', 'Success evidence', 'Designation audit', 'Platform evidence')):
                        print('Controlled PostgreSQL diagnosis: ' + primary.encode('ascii', errors='backslashreplace').decode('ascii'))
            for line in result.stdout.splitlines():
                if 'PlatformPrivilegedPostgresTest.java:' in line or 'Tests run:' in line or '[checkstyle]' in line:
                    print(line)
        check(result.returncode == 0 and 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' in result.stdout,
              'ONE real F04/platform audit and concurrency method passed; Checkstyle')
    finally:
        check(cluster.psql(script=ROOT / 'scripts/migrations/deactivate_platform_executor.sql', apply=True).returncode == 0,
              'controlled channels deactivated after all connections closed')
        check(cluster.query("SELECT count(*) FROM pg_authid r WHERE rolname IN ('ls_core_platform_mutator_dev','ls_core_platform_security_log_dev') "
                            "AND NOT rolcanlogin AND rolpassword IS NULL AND NOT has_database_privilege(oid,current_database(),'CONNECT')") == '2',
              'no executor LOGIN, credential or CONNECT remains')
    check(cluster.psql(script=ROOT / 'scripts/migrations/seal_platform_bootstrap.sql', apply=True).returncode == 0, 'bootstrap sealed')


if __name__ == '__main__':
    main('PlatformPrivilegedPostgresTest')

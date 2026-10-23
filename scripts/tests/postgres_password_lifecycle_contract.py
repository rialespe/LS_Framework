"""LFD-32: one real lifecycle integration on the project's disposable PostgreSQL 17 only."""
import os
import secrets
import sys

sys.dont_write_bytecode = True
from test_postgres_migrations import BACKEND, ROOT, SOURCE, check, disposable_cluster, environment, prepare_fixture

ISSUER = 'ls_core_password_issuer_dev'
CHANGER = 'ls_core_password_changer_dev'
SCRIPT = ROOT / 'scripts/migrations/prepare_password_lifecycle.sql'


def test_password_lifecycle(cluster):
    check(cluster.port not in (5432, 55432), 'lifecycle uses only the owned ephemeral PG17 endpoint')
    check(cluster.psql(script=SCRIPT).returncode != 0, 'password provisioning requires explicit apply')
    reply = ''
    for role in [ISSUER, CHANGER]:
        cluster.secrets[role] = secrets.token_urlsafe(32)
        reply += (cluster.secrets[role] + '\n') * 2
    check(cluster.psql(script=SCRIPT, apply=True, reply=reply).returncode == 0,
          'administrative provisioning creates separate restricted SCRAM LOGINs')
    check(cluster.flyway('migrate', location=SOURCE, target='12').returncode == 0,
          'restricted migrator applies V12, preserving V1-V11 and FORCE RLS')
    check(cluster.flyway('validate', location=SOURCE, target='12').returncode == 0, 'Flyway validates V1-V12')
    env = {k: v for k, v in environment().items() if not k.upper().startswith('LS_LIFECYCLE_TEST_')}
    env.update(LS_LIFECYCLE_TEST_ROOT=str(cluster.root), LS_LIFECYCLE_TEST_PORT=str(cluster.port))
    for role, password in cluster.secrets.items():
        env['LS_LIFECYCLE_TEST_' + role.upper()] = password
    command = [str(BACKEND / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(BACKEND / 'mvnw')]
    command += ['-B', '-ntp', '-f', str(BACKEND / 'pom.xml'), '-Dtest=PasswordLifecyclePostgresTest', 'verify']
    result = cluster.run(command, env=env, timeout=180)
    if result.returncode:
        print('Lifecycle integration failed; secret-bearing JDBC diagnostics withheld.')
        # Only safe test location/status, never JDBC parameters or assertion values.
        for line in result.stdout.splitlines():
            if 'PasswordLifecyclePostgresTest.java:' in line or 'Tests run:' in line or '[checkstyle]' in line:
                print(line)
    check(result.returncode == 0 and 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' in result.stdout,
          'single lifecycle integration: issuance, 72h expiry, exchange, normal verifier, replay denial, '
          'negative states/secrets, 90d renewal, role denials and concurrent single consumption; Checkstyle')


def main():
    with disposable_cluster() as cluster:
        check(cluster.port not in (5432, 55432), 'refuse shared endpoints before fixture SQL')
        prepare_fixture(cluster)
        for database in ['postgres', 'template1']:
            check(cluster.psql(sql=f'REVOKE CONNECT, TEMPORARY ON DATABASE {database} FROM PUBLIC').returncode == 0,
                  'fixture-only database ACL')
        password = cluster.secrets['ls_core_migrator_dev']
        check(cluster.psql(script=ROOT / 'scripts/migrations/provision.sql', apply=True,
                           reply=(password + '\n') * 2).returncode == 0, 'fixture restricted migrator')
        check(cluster.flyway('migrate', location=SOURCE, target='9').returncode == 0, 'unchanged V1-V9 baseline')
        for role, script, version in [
                ('ls_core_credential_verifier_dev', 'prepare_credential_verifier.sql', '10'),
                ('ls_core_identity_registrar_dev', 'prepare_identity_registrar.sql', '11')]:
            password = secrets.token_urlsafe(32)
            cluster.secrets[role] = password
            check(cluster.psql(script=ROOT / 'scripts/migrations' / script, apply=True,
                               reply=(password + '\n') * 2).returncode == 0, 'fixture isolated ' + role)
            check(cluster.flyway('migrate', location=SOURCE, target=version).returncode == 0,
                  'unchanged V' + version + ' baseline')
        test_password_lifecycle(cluster)
    print('LFD-32 PASS: disposable PG17 stopped and removed; shared endpoints not contacted.', flush=True)


if __name__ == '__main__':
    main()

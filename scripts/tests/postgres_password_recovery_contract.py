"""LFD-33: a single focused real integration, never an existing/shared PostgreSQL endpoint."""
import os
import secrets
import sys

sys.dont_write_bytecode = True
from test_postgres_migrations import BACKEND, ROOT, SOURCE, check, disposable_cluster, environment, prepare_fixture

ROLES = ['ls_core_password_authenticator_dev', 'ls_core_password_recovery_dev']
SCRIPT = ROOT / 'scripts/migrations/prepare_password_recovery.sql'


def test_password_recovery(cluster):
    check(cluster.port not in (5432, 55432), 'recovery only uses the owned ephemeral PG17 endpoint')
    check(cluster.psql(script=SCRIPT).returncode != 0, 'V13 provisioning requires explicit apply')
    reply = ''
    for role in ROLES:
        cluster.secrets[role] = secrets.token_urlsafe(32)
        reply += (cluster.secrets[role] + '\n') * 2
    check(cluster.psql(script=SCRIPT, apply=True, reply=reply).returncode == 0,
          'separate restricted SCRAM authentication/recovery LOGINs')
    check(cluster.flyway('migrate', location=SOURCE, target='13').returncode == 0,
          'restricted migrator applies V13 without altering V1-V12')
    check(cluster.flyway('validate', location=SOURCE, target='13').returncode == 0, 'Flyway validates V1-V13')
    env = {k: v for k, v in environment().items() if not k.upper().startswith('LS_RECOVERY_TEST_')}
    env.update(LS_RECOVERY_TEST_ROOT=str(cluster.root), LS_RECOVERY_TEST_PORT=str(cluster.port))
    for role, password in cluster.secrets.items():
        env['LS_RECOVERY_TEST_' + role.upper()] = password
    command = [str(BACKEND / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(BACKEND / 'mvnw')]
    command += ['-B', '-ntp', '-f', str(BACKEND / 'pom.xml'), '-Dtest=PasswordRecoveryPostgresTest', 'verify']
    result = cluster.run(command, env=env, timeout=180)
    if result.returncode:
        print('Recovery integration failed; secret-bearing JDBC diagnostics withheld.')
        for line in result.stdout.splitlines():
            if 'PasswordRecoveryPostgresTest.java:' in line or 'Tests run:' in line or '[checkstyle]' in line:
                print(line)
    check(result.returncode == 0 and 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' in result.stdout,
          'one integration: concurrent counters, progressive locks/expiry/reset, forbidden states, '
          'verified mail, digest-only single-use tokens, quotas, revocation, concurrent recovery and role isolation')


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
        reply = ''
        for role in ['ls_core_password_issuer_dev', 'ls_core_password_changer_dev']:
            cluster.secrets[role] = secrets.token_urlsafe(32)
            reply += (cluster.secrets[role] + '\n') * 2
        check(cluster.psql(script=ROOT / 'scripts/migrations/prepare_password_lifecycle.sql',
                           apply=True, reply=reply).returncode == 0, 'fixture V12 service roles')
        check(cluster.flyway('migrate', location=SOURCE, target='12').returncode == 0, 'unchanged V12 baseline')
        test_password_recovery(cluster)
    print('LFD-33 PASS: own disposable PG17 stopped/removed; no shared endpoint contacted.', flush=True)


if __name__ == '__main__':
    main()

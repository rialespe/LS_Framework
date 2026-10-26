"""LFD-29: one production authorization integration, only on the project-owned disposable PG17."""
import os
import secrets
import sys

sys.dont_write_bytecode = True
from test_postgres_migrations import BACKEND, ROOT, SOURCE, check, disposable_cluster, environment, prepare_fixture

ROLE = 'ls_core_authorization_reader_dev'
SCRIPT = ROOT / 'scripts/migrations/prepare_authorization_reader.sql'


def test_tenant_authorization(cluster):
    check(cluster.port not in (5432, 55432), 'authorization uses only the owned ephemeral PG17 endpoint')
    check(cluster.psql(script=SCRIPT).returncode != 0, 'authorization provisioning requires explicit apply')
    password = secrets.token_urlsafe(32)
    cluster.secrets[ROLE] = password
    check(cluster.psql(script=SCRIPT, apply=True, reply=(password + '\n') * 2).returncode == 0,
          'explicit isolated SCRAM authorization reader provisioning')
    check(cluster.flyway('migrate', location=SOURCE, target='14').returncode == 0,
          'restricted migrator applies V14 with column-scoped authorization metadata reads only')
    check(cluster.flyway('validate', location=SOURCE, target='14').returncode == 0, 'Flyway validates V1-V14')
    env = {k: v for k, v in environment().items() if not k.upper().startswith('LS_AUTHORIZATION_TEST_')}
    env.update(LS_AUTHORIZATION_TEST_ROOT=str(cluster.root), LS_AUTHORIZATION_TEST_PORT=str(cluster.port))
    for role, password in cluster.secrets.items():
        env['LS_AUTHORIZATION_TEST_' + role.upper()] = password
    command = [str(BACKEND / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(BACKEND / 'mvnw')]
    command += ['-B', '-ntp', '-f', str(BACKEND / 'pom.xml'), '-Dtest=TenantAuthorizationPostgresTest', 'verify']
    result = cluster.run(command, env=env, timeout=180)
    if result.returncode:
        print('Authorization integration failed; JDBC/identity/secret diagnostics withheld.')
        for line in result.stdout.splitlines():
            if 'TenantAuthorizationPostgresTest.java:' in line or 'Tests run:' in line or '[checkstyle]' in line:
                print(line)
    check(result.returncode == 0 and 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' in result.stdout,
          'one real F04/authorization integration: positive membership, foreign/mutated selectors, suspensions, '
          'entitlement, role/GUC denials and fresh revocation decision; Checkstyle')


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
        for roles, script, version in [
                (['ls_core_credential_verifier_dev'], 'prepare_credential_verifier.sql', '10'),
                (['ls_core_identity_registrar_dev'], 'prepare_identity_registrar.sql', '11'),
                (['ls_core_password_issuer_dev', 'ls_core_password_changer_dev'], 'prepare_password_lifecycle.sql', '12'),
                (['ls_core_password_authenticator_dev', 'ls_core_password_recovery_dev'], 'prepare_password_recovery.sql', '13')]:
            reply = ''
            for role in roles:
                cluster.secrets[role] = secrets.token_urlsafe(32)
                reply += (cluster.secrets[role] + '\n') * 2
            check(cluster.psql(script=ROOT / 'scripts/migrations' / script, apply=True, reply=reply).returncode == 0,
                  'fixture isolated roles before V' + version)
            check(cluster.flyway('migrate', location=SOURCE, target=version).returncode == 0,
                  'unchanged V' + version + ' baseline')
        test_tenant_authorization(cluster)
    print('LFD-29 PASS: own disposable PG17 stopped and removed; shared endpoints not contacted.', flush=True)


if __name__ == '__main__':
    main()

"""One real branch-operation integration; only the existing owned disposable PostgreSQL 17 harness."""
import os
import secrets
import sys

sys.dont_write_bytecode = True
from test_postgres_migrations import BACKEND, ROOT, SOURCE, check, disposable_cluster, environment, prepare_fixture


def test_branch_operation(cluster, test_class='BranchOperationPostgresTest'):
    check(cluster.port not in (5432, 55432), 'operation uses only the owned ephemeral PG17 endpoint')
    reply = ''
    for role in ['ls_core_branch_operation_dev', 'ls_core_operation_emitter_dev']:
        cluster.secrets[role] = secrets.token_urlsafe(32)
        reply += (cluster.secrets[role] + '\n') * 2
    check(cluster.psql(script=ROOT / 'scripts/migrations/prepare_branch_operation.sql', apply=True, reply=reply).returncode == 0,
          'explicit isolated operation/emitter provisioning')
    migrated = cluster.flyway('migrate', location=SOURCE, target='15')
    if migrated.returncode:
        # Cluster.run already checks captured output against every ephemeral password.
        print((migrated.stdout + migrated.stderr)[-5000:])
    check(migrated.returncode == 0, 'restricted migrator applies V15')
    check(cluster.flyway('validate', location=SOURCE, target='15').returncode == 0, 'Flyway validates intact V1-V15')
    run_branch_integration(cluster, test_class)


def run_branch_integration(cluster, test_class):
    env = {k: v for k, v in environment().items() if not k.upper().startswith('LS_BRANCH_READ_TEST_')}
    env.update(LS_BRANCH_READ_TEST_ROOT=str(cluster.root), LS_BRANCH_READ_TEST_PORT=str(cluster.port))
    for role, password in cluster.secrets.items():
        env['LS_BRANCH_READ_TEST_' + role.upper()] = password
    command = [str(BACKEND / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(BACKEND / 'mvnw')]
    command += ['-B', '-ntp', '-f', str(BACKEND / 'pom.xml'), '-Dtest=' + test_class, 'verify']
    result = cluster.run(command, env=env, timeout=180)
    if result.returncode:
        print(test_class + ' failed; raw JDBC/identity/secret diagnostics withheld.')
        for line in result.stdout.splitlines():
            if test_class + '.java:' in line or 'Tests run:' in line or '[checkstyle]' in line:
                print(line)
    check(result.returncode == 0 and 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' in result.stdout,
          test_class + ': exactly one real integration passed; Checkstyle')


def main(test_class='BranchOperationPostgresTest'):
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
                (['ls_core_password_authenticator_dev', 'ls_core_password_recovery_dev'], 'prepare_password_recovery.sql', '13'),
                (['ls_core_authorization_reader_dev'], 'prepare_authorization_reader.sql', '14')]:
            reply = ''
            for role in roles:
                cluster.secrets[role] = secrets.token_urlsafe(32)
                reply += (cluster.secrets[role] + '\n') * 2
            check(cluster.psql(script=ROOT / 'scripts/migrations' / script, apply=True, reply=reply).returncode == 0,
                  'fixture isolated roles before V' + version)
            check(cluster.flyway('migrate', location=SOURCE, target=version).returncode == 0,
                  'unchanged V' + version + ' baseline')
        test_branch_operation(cluster, test_class)
    print('Branch operation PASS: owned PG17 stopped/removed. No universal concurrent-revocation claim.', flush=True)


if __name__ == '__main__':
    if sys.argv[1:] not in ([], ['--pool-isolation']):
        raise SystemExit('Only --pool-isolation is supported')
    main('TenantIsolationPoolPostgresTest' if sys.argv[1:] else 'BranchOperationPostgresTest')

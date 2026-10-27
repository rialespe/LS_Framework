"""LFD-34: one HTTPS + disposable PostgreSQL 17 login/logout integration, without re-running F03 tests."""
import os
from pathlib import Path
import secrets
import sys

sys.dont_write_bytecode = True
from test_postgres_migrations import BACKEND, ROOT, SOURCE, check, disposable_cluster, environment, prepare_fixture


def test_application_login(cluster, test_class='ApplicationLoginPostgresTest'):
    check(cluster.port not in (5432, 55432), 'login uses only owned ephemeral PG17')
    role = 'ls_core_session_manager_dev'
    cluster.secrets[role] = secrets.token_urlsafe(32)
    check(cluster.psql(script=ROOT / 'scripts/migrations/prepare_session_manager.sql', apply=True,
                       reply=(cluster.secrets[role] + '\n') * 2).returncode == 0, 'restricted session LOGIN provisioning')
    migrated = cluster.flyway('migrate', location=SOURCE, target='16')
    if migrated.returncode:
        print((migrated.stdout + migrated.stderr)[-5000:])
    check(migrated.returncode == 0, 'restricted migrator applies V16')
    check(cluster.flyway('validate', location=SOURCE, target='16').returncode == 0, 'Flyway validates V1-V16')
    if test_class != 'ApplicationLoginPostgresTest':
        from postgres_session_context_contract import test_session_contexts
        test_session_contexts(cluster)
    else:
        run_login_integration(cluster, test_class)


def run_login_integration(cluster, test_class):
    if 'login_tls' not in cluster.secrets:
        cluster.secrets['login_tls'] = secrets.token_urlsafe(32)
    env = {k: v for k, v in environment().items() if not k.upper().startswith(('LS_LOGIN_', 'LS_AUTHENTICATION_'))}
    env['LS_LOGIN_TLS_STORE_PASSWORD'] = cluster.secrets['login_tls']
    keytool = Path(env['JAVA_HOME']) / 'bin' / ('keytool.exe' if os.name == 'nt' else 'keytool')
    if not (cluster.root / 'login-test.p12').exists():
        result = cluster.run([keytool, '-genkeypair', '-alias', 'localhost', '-keyalg', 'RSA', '-keysize', '2048',
                              '-dname', 'CN=localhost', '-ext', 'SAN=IP:127.0.0.1,DNS:localhost', '-validity', '1',
                              '-storetype', 'PKCS12', '-keystore', cluster.root / 'login-test.p12',
                              '-storepass:env', 'LS_LOGIN_TLS_STORE_PASSWORD', '-keypass:env', 'LS_LOGIN_TLS_STORE_PASSWORD',
                              '-noprompt'], env=env)
        check(result.returncode == 0, 'owned test TLS certificate; no password in process arguments')
    env.update(LS_LOGIN_TEST_ROOT=str(cluster.root), LS_LOGIN_TEST_PORT=str(cluster.port),
               LS_LOGIN_TEST_TLS_PASSWORD=cluster.secrets['login_tls'])
    for principal, password in cluster.secrets.items():
        env['LS_LOGIN_TEST_' + principal.upper()] = password
    command = [str(BACKEND / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(BACKEND / 'mvnw')]
    command += ['-B', '-ntp', '-f', str(BACKEND / 'pom.xml'), '-Dtest=' + test_class, 'verify']
    result = cluster.run(command, env=env, timeout=180)
    if result.returncode:
        print('HTTPS login integration failed; raw credential/cookie diagnostics withheld.')
        for line in result.stdout.splitlines():
            if test_class + '.java:' in line or 'Tests run:' in line or '[checkstyle]' in line:
                print(line)
    check(result.returncode == 0 and 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' in result.stdout,
          test_class + ': ONE HTTPS/PG17 integration passed; Checkstyle')


def main(test_class='ApplicationLoginPostgresTest'):
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
                (['ls_core_authorization_reader_dev'], 'prepare_authorization_reader.sql', '14'),
                (['ls_core_branch_operation_dev', 'ls_core_operation_emitter_dev'], 'prepare_branch_operation.sql', '15')]:
            reply = ''
            for role in roles:
                cluster.secrets[role] = secrets.token_urlsafe(32)
                reply += (cluster.secrets[role] + '\n') * 2
            check(cluster.psql(script=ROOT / 'scripts/migrations' / script, apply=True, reply=reply).returncode == 0,
                  'fixture isolated roles before V' + version)
            check(cluster.flyway('migrate', location=SOURCE, target=version).returncode == 0,
                  'unchanged V' + version + ' baseline')
        test_application_login(cluster, test_class)
    print(test_class + ' PASS: own PG17 stopped/removed; no shared server or F03 test was used.', flush=True)


if __name__ == '__main__':
    main()

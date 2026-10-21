"""LFD-5: one production verifier integration scenario on harness-owned PostgreSQL 17.

Run directly for focused validation, or once at the end of the standard migration CI harness.
No shared database, historical matrix, session/token flow or business authorization is used.
"""
import json
import os
from pathlib import Path
import secrets
import sys

sys.dont_write_bytecode = True
from test_postgres_migrations import BACKEND, ROOT, SOURCE, check, disposable_cluster, environment, prepare_fixture

ROLE = 'ls_core_credential_verifier_dev'
SCRIPT = ROOT / 'scripts/migrations/prepare_credential_verifier.sql'


def test_credential_verifier(cluster):
    check(cluster.port not in (5432, 55432), 'credential integration refuses shared ports')
    before = cluster.query("SELECT row_to_json(r)::text FROM pg_authid r WHERE rolname='ls_core_app_dev'")
    check(cluster.psql(script=SCRIPT).returncode != 0, 'credential provisioning refuses implicit application')
    check(cluster.query("SELECT count(*) FROM pg_roles WHERE rolname='" + ROLE + "'") == '0',
          'default refusal creates no role')
    cluster.secrets[ROLE] = secrets.token_urlsafe(32)
    password = cluster.secrets[ROLE]
    check(cluster.psql(script=SCRIPT, apply=True, reply=password + '\n' + password + '\n').returncode == 0,
          'explicit administrative credential LOGIN provisioning with SCRAM')
    check(cluster.psql(script=SCRIPT, apply=True).returncode != 0,
          'existing verifier cannot be silently adopted or replaced')
    check(cluster.query("SELECT rolcanlogin AND rolpassword LIKE 'SCRAM-SHA-256$%' FROM pg_authid "
                        "WHERE rolname='" + ROLE + "'") == 't', 'dedicated SCRAM LOGIN, not a temporary bypass')
    check(cluster.flyway('migrate', location=SOURCE, target='10').returncode == 0,
          'restricted migrator applies V10 without role creation/admin privileges')
    check(cluster.flyway('validate', location=SOURCE, target='10').returncode == 0,
          'Flyway validates unchanged history and V10')
    check(cluster.query("SELECT count(*) FROM pg_policy WHERE polrelid IN "
                        "('identity.users'::regclass,'identity.user_credentials'::regclass) "
                        "AND polcmd='r' AND polroles=ARRAY['" + ROLE + "'::regrole::oid]") == '2',
          'only dedicated verifier SELECT policies on credential tables')
    check(cluster.query("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                        "WHERE n.nspname IN ('identity','platform','tenant','security','catalog','integration',"
                        "'audit','operations') AND c.relkind='r' AND ("
                        "has_table_privilege('" + ROLE + "',c.oid,"
                        "'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN') OR "
                        "has_any_column_privilege('" + ROLE + "',c.oid,'INSERT,UPDATE,REFERENCES') OR "
                        "(c.oid NOT IN ('identity.users'::regclass,'identity.user_credentials'::regclass) AND "
                        "has_any_column_privilege('" + ROLE + "',c.oid,'SELECT')))") == '0',
          'no verifier table-wide grants, writes or enterprise data access')
    env = {k: v for k, v in environment().items() if not k.upper().startswith('LS_CREDENTIAL_')}
    env.update(LS_CREDENTIAL_TEST_ROOT=str(cluster.root), LS_CREDENTIAL_TEST_PORT=str(cluster.port),
               LS_CREDENTIAL_TEST_PASSWORD=password,
               LS_CREDENTIAL_TEST_ADMIN_PASSWORD=cluster.secrets['postgres'],
               LS_CREDENTIAL_TEST_APP_PASSWORD=cluster.secrets['ls_core_app_dev'])
    command = [str(BACKEND / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(BACKEND / 'mvnw')]
    command += ['-B', '-ntp', '-f', str(BACKEND / 'pom.xml'),
                '-Dtest=PasswordCredentialPostgresTest', 'verify']
    result = cluster.run(command, env=env, timeout=180)
    if result.returncode:
        diagnostics = result.stdout[-10000:] + result.stderr[-2000:]
        print(diagnostics.encode('ascii', errors='backslashreplace').decode('ascii'))
    check(result.returncode == 0 and 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' in result.stdout,
          'one real PBKDF2/JDBC scenario: positive match, negative states, exact ACL, failures and Checkstyle')
    check(cluster.query("SELECT count(id) FROM identity.users", 'ls_core_migrator_dev') == '0'
          and cluster.query("SELECT count(user_id) FROM identity.user_credentials", 'ls_core_migrator_dev') == '0',
          'FORCE RLS still denies even the table owner')
    check(cluster.query("SELECT count(*) FROM identity.users WHERE username LIKE 'f04-%'") == '5'
          and cluster.query("SELECT count(*) FROM identity.user_credentials") == '5',
          'positive/negative fixture rows really exist; denial is not empty-data success')
    check(cluster.query("SELECT row_to_json(r)::text FROM pg_authid r WHERE rolname='ls_core_app_dev'") == before,
          'enterprise identity/password/attributes preserved')
    check(cluster.query("SELECT NOT has_any_column_privilege('ls_core_app_dev',"
                        "'identity.user_credentials','SELECT')") == 't', 'runtime still cannot read hashes')
    check(cluster.query("SELECT count(*) FROM pg_stat_activity WHERE usename='" + ROLE + "'") == '0',
          'all verifier physical connections closed')


def main():
    with disposable_cluster() as cluster:
        check(cluster.port not in (5432, 55432), 'refuse shared endpoints before fixture SQL')
        probe = cluster.psql(sql="SELECT json_build_object('data',current_setting('data_directory'),"
                                 "'port',inet_server_port(),'host',inet_server_addr())", database='postgres')
        check(probe.returncode == 0, 'disposable PostgreSQL endpoint probe')
        identity = json.loads(probe.stdout.strip())
        check(Path(identity['data']).resolve() == cluster.data.resolve() and identity['port'] == cluster.port
              and identity['host'] == '127.0.0.1', 'own temporary PG17 endpoint before fixture SQL')
        prepare_fixture(cluster)
        for database in ['postgres', 'template1']:
            check(cluster.psql(sql=f'REVOKE CONNECT, TEMPORARY ON DATABASE {database} FROM PUBLIC').returncode == 0,
                  'fixture-only database ACL')
        password = cluster.secrets['ls_core_migrator_dev']
        check(cluster.psql(script=ROOT / 'scripts/migrations/provision.sql', apply=True,
                           reply=password + '\n' + password + '\n').returncode == 0, 'fixture restricted migrator')
        check(cluster.flyway('migrate', target='9').returncode == 0, 'unchanged V1-V9 baseline')
        test_credential_verifier(cluster)
    print('LFD-5 PASS: real credential integration; own cluster stopped and removed.', flush=True)


if __name__ == '__main__':
    main()

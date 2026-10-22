"""LFD-31: one real registration scenario, called once by the existing disposable PG17 CI harness."""
import os
import secrets
import sys

sys.dont_write_bytecode = True
from test_postgres_migrations import BACKEND, ROOT, SOURCE, check, environment

ROLE = 'ls_core_identity_registrar_dev'
SCRIPT = ROOT / 'scripts/migrations/prepare_identity_registrar.sql'


def test_identity_registration(cluster):
    check(cluster.port not in (5432, 55432), 'registration uses only the owned ephemeral PG17 endpoint')
    check(cluster.psql(script=SCRIPT).returncode != 0, 'registration provisioning defaults to no changes')
    check(cluster.psql(script=SCRIPT, apply=True, user='ls_core_app_dev').returncode != 0,
          'runtime cannot provision the registrar')
    check(cluster.query("SELECT count(*) FROM pg_roles WHERE rolname='" + ROLE + "'") == '0',
          'refused provisioning creates no role')
    cluster.secrets[ROLE] = secrets.token_urlsafe(32)
    password = cluster.secrets[ROLE]
    check(cluster.psql(script=SCRIPT, apply=True, reply=password + '\n' + password + '\n').returncode == 0,
          'explicit administrative registrar provisioning with ephemeral SCRAM password')
    before = cluster.query("SELECT row_to_json(r)::text FROM pg_authid r WHERE rolname='" + ROLE + "'")
    check(cluster.psql(script=SCRIPT, apply=True).returncode != 0
          and cluster.query("SELECT row_to_json(r)::text FROM pg_authid r WHERE rolname='" + ROLE + "'") == before,
          'existing registrar refused without adoption or password changes')
    check(cluster.flyway('migrate', location=SOURCE, target='11').returncode == 0,
          'restricted migrator applies V11 without administrative privileges')
    check(cluster.flyway('validate', location=SOURCE, target='11').returncode == 0,
          'Flyway validates V11 and intact V1-V10')
    check(cluster.query("SELECT rolcanlogin AND NOT (rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole "
                        "OR rolinherit OR rolreplication) AND rolconnlimit=3 AND rolpassword LIKE 'SCRAM-SHA-256$%' "
                        "AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid) "
                        "AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass "
                        "AND refobjid=r.oid AND deptype='o') FROM pg_authid r WHERE rolname='" + ROLE + "'") == 't',
          'registrar is an isolated restricted LOGIN without ownership or memberships')
    check(cluster.query("SELECT bool_and(NOT has_table_privilege('" + ROLE + "',c.oid,"
                        "'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN') AND "
                        "NOT has_any_column_privilege('" + ROLE + "',c.oid,'SELECT,UPDATE,REFERENCES')) "
                        "FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE "
                        "n.nspname IN ('identity','platform','tenant','security','catalog','integration',"
                        "'audit','operations') AND c.relkind='r'") == 't', 'registrar cannot read/write other identities or data')
    check(cluster.query("SELECT bool_and(has_column_privilege('" + ROLE + "',c.oid,a.attnum,'INSERT') = "
                        "(c.oid='identity.users'::regclass AND a.attname IN ('id','username','display_name','email'))) "
                        "FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                        "JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped "
                        "WHERE n.nspname IN ('identity','platform','tenant','security','catalog','integration',"
                        "'audit','operations') AND c.relkind='r'") == 't', 'exactly four INSERT columns, no state/verification grants')
    # The preceding standard verifier scenario deliberately revoked this fixture grant.
    # Restore exactly the approved V10 ACL on this disposable database, so a missing-credential
    # result below cannot be a false positive caused by missing hash privileges. No V10 reexecution.
    cluster.query('GRANT SELECT(password_hash) ON identity.user_credentials TO ls_core_credential_verifier_dev',
                  'ls_core_migrator_dev')
    env = {k: v for k, v in environment().items() if not k.upper().startswith('LS_REGISTRATION_')}
    env.update(LS_REGISTRATION_TEST_ROOT=str(cluster.root), LS_REGISTRATION_TEST_PORT=str(cluster.port),
               LS_REGISTRATION_TEST_PASSWORD=password,
               LS_REGISTRATION_TEST_ADMIN_PASSWORD=cluster.secrets['postgres'],
               LS_REGISTRATION_TEST_VERIFIER_PASSWORD=cluster.secrets['ls_core_credential_verifier_dev'],
               LS_REGISTRATION_TEST_APP_PASSWORD=cluster.secrets['ls_core_app_dev'])
    command = [str(BACKEND / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(BACKEND / 'mvnw')]
    command += ['-B', '-ntp', '-f', str(BACKEND / 'pom.xml'),
                '-Dtest=ManualIdentityRegistrationPostgresTest', 'verify']
    result = cluster.run(command, env=env, timeout=180)
    if result.returncode:
        # Do not echo JDBC diagnostics or fixture identity values, even on unexpected errors.
        print('Registration Java validation failed; raw diagnostics withheld.')
    check(result.returncode == 0 and 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' in result.stdout,
          'one registration integration: persistence, V7 conflict, unverified email, invalid input, '
          'role denials, non-authenticating account, existing identities unchanged; Checkstyle passed')
    check(cluster.query("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class "
                        "WHERE oid='identity.users'::regclass") == 't', 'FORCE RLS remains enabled')
    check(cluster.query("SELECT count(*) FROM pg_policy WHERE polrelid='identity.users'::regclass "
                        "AND polname='manual_identity_registration' AND polcmd='a' AND polqual IS NULL "
                        "AND polwithcheck IS NOT NULL AND polroles=ARRAY['" + ROLE + "'::regrole::oid]") == '1',
          'dedicated INSERT-only RLS policy; no new read/business policy')

"""C3/C4: real Java catalog reader against an isolated PostgreSQL 17/C1/C2 fixture.

Run: python scripts/tests/postgres_catalog_backend_contract.py
"""
import json
import os
from pathlib import Path
import secrets
import sys

sys.dont_write_bytecode = True
from test_postgres_migrations import BACKEND, ROOT, check, disposable_cluster, environment, prepare_fixture
from postgres_catalog_acl_contract import SCRIPT as C1_SCRIPT
from postgres_catalog_service_contract import SERVICE, SCRIPT as C2_SCRIPT


def test_catalog_backend(cluster):
    env = environment()
    # No inherited catalog configuration or test target may redirect the Java process.
    env = {k: v for k, v in env.items() if not k.upper().startswith('LS_CATALOG_')}
    env.update(LS_CATALOG_TEST_ROOT=str(cluster.root), LS_CATALOG_TEST_PORT=str(cluster.port),
               LS_CATALOG_TEST_PASSWORD=cluster.secrets[SERVICE])
    command = [str(BACKEND / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(BACKEND / 'mvnw')]
    command += ['-B', '-ntp', '-f', str(BACKEND / 'pom.xml'),
                '-Dtest=GlobalCatalogConfigurationTest,GlobalCatalogPostgresTest', 'verify']
    # Deliberately insert out of order; extra application/module prove pagination and UUID scope.
    fixture = """
    INSERT INTO catalog.applications(id,code,name,status) VALUES
      ('c4000000-0000-4000-8000-000000000002','c4-z','C4 fixture Z','PUBLISHED'),
      ('c4000000-0000-4000-8000-000000000001','c4-a','C4 fixture A','PUBLISHED');
    INSERT INTO catalog.modules(id,application_id,code,name,status) VALUES
      ('c4000000-0000-4000-8000-000000000003','c4000000-0000-4000-8000-000000000001',
       'c4-foreign','C4 scoped fixture','PUBLISHED');
    """
    cluster.query(fixture)
    try:
        result = cluster.run(command, env=env, timeout=180)
    finally:
        cluster.query("DELETE FROM catalog.modules WHERE id='c4000000-0000-4000-8000-000000000003';"
                      "DELETE FROM catalog.applications WHERE id IN "
                      "('c4000000-0000-4000-8000-000000000001','c4000000-0000-4000-8000-000000000002')")
    if result.returncode:
        # Cluster.run has already checked that no ephemeral secret occurs in these diagnostics.
        print(result.stdout[-10000:], result.stderr[-2000:])
    check(result.returncode == 0 and 'Tests run: 7, Failures: 0, Errors: 0, Skipped: 0' in result.stdout,
          'C3/C4: seven Java configuration/JDBC tests and Checkstyle passed against disposable PG17')
    check(cluster.query("SELECT count(*) FROM pg_stat_activity WHERE usename='" + SERVICE + "'") == '0',
          'no catalog service physical sessions remain after Java tests')
    check(cluster.query("SELECT NOT has_any_column_privilege('ls_core_app_dev',"
                        "'catalog.applications','SELECT') AND NOT has_any_column_privilege("
                        "'ls_core_app_dev','catalog.modules','SELECT')") == 't',
          'enterprise runtime catalog restrictions unchanged')


def main():
    with disposable_cluster() as cluster:
        probe = cluster.psql(sql="SELECT json_build_object('data',current_setting('data_directory'),"
                                 "'port',inet_server_port(),'host',inet_server_addr())", database='postgres')
        check(probe.returncode == 0, 'temporary server identity probe')
        identity = json.loads(probe.stdout.strip())
        check(Path(identity['data']).resolve() == cluster.data.resolve() and identity['port'] == cluster.port
              and identity['host'] == '127.0.0.1', 'own disposable PG17 endpoint verified before fixture SQL')
        prepare_fixture(cluster)
        for database in ['postgres', 'template1']:
            check(cluster.psql(sql=f'REVOKE CONNECT, TEMPORARY ON DATABASE {database} FROM PUBLIC').returncode == 0,
                  'fixture-only database ACL')
        password = cluster.secrets['ls_core_migrator_dev']
        check(cluster.psql(script=ROOT / 'scripts/migrations/provision.sql', apply=True,
                           reply=password + '\n' + password + '\n').returncode == 0, 'fixture migrator')
        check(cluster.flyway('migrate', target='9').returncode == 0, 'unchanged V1-V9 fixture')
        check(cluster.psql(script=C1_SCRIPT, apply=True).returncode == 0, 'unchanged C1')
        cluster.secrets[SERVICE] = secrets.token_urlsafe(32)
        password = cluster.secrets[SERVICE]
        check(cluster.psql(script=C2_SCRIPT, apply=True, reply=password + '\n' + password + '\n').returncode == 0,
              'unchanged C2 with ephemeral SCRAM credentials')
        test_catalog_backend(cluster)
    print('C3/C4 passed; own cluster stopped and removed. Shared PostgreSQL was not contacted.', flush=True)


if __name__ == '__main__':
    main()

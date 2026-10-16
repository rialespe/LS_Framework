"""Flyway/permissions regression on a newly initialized, disposable PostgreSQL 17 cluster only.

Run from any directory: python scripts/tests/test_postgres_migrations.py
Uses standard-library Python, JDK 21/Maven Wrapper, and existing PG17 binaries (LS_PG_BIN).
There is deliberately no option to supply a server URL, credentials, or an existing data directory.
"""

from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / 'backend'
SOURCE = BACKEND / 'src/main/resources/db/migration'
SCHEMAS = {'identity', 'platform', 'tenant', 'security', 'catalog', 'integration', 'audit', 'operations'}
NO_WINDOW = getattr(subprocess, 'CREATE_NO_WINDOW', 0)


def check(condition, label):
    if not condition:
        raise AssertionError(label)
    print('OK:', label, flush=True)


def pg_binaries():
    configured = os.environ.get('LS_PG_BIN')
    if configured:
        directory = Path(configured)
    elif os.name == 'nt':
        directory = Path(r'C:\Program Files\PostgreSQL\17\bin')
    else:
        found = shutil.which('initdb')
        if not found:
            raise RuntimeError('Set LS_PG_BIN to the existing PostgreSQL 17 bin directory')
        directory = Path(found).parent
    return directory.resolve()


def environment():
    return {key: value for key, value in os.environ.items()
            if not key.upper().startswith(('PG', 'FLYWAY', 'LS_DB_', 'LS_MIGRATION_', 'SPRING_', 'SERVER_',
                                           'MAVEN_ARGS', 'MAVEN_OPTS', 'JAVA_TOOL_OPTIONS',
                                           'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'))}


class Cluster:
    def __init__(self, root):
        self.root = root
        self.data = root / 'data'
        self.bin = pg_binaries()
        self.secrets = {name: secrets.token_urlsafe(32) for name in
                        ['postgres', 'ls_core_app_dev', 'ls_core_migrator_dev']}
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            self.port = listener.getsockname()[1]
        if self.port == 5432:
            raise RuntimeError('Refusing shared-server port')

    def executable(self, name):
        return str(self.bin / (name + ('.exe' if os.name == 'nt' else '')))

    def run(self, args, input_text=None, env=None, capture=True, timeout=90):
        result = subprocess.run(
            [str(arg) for arg in args], input=input_text,
            stdin=None if input_text is not None else subprocess.DEVNULL,
            stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
            stderr=subprocess.PIPE if capture else subprocess.DEVNULL,
            env=env or environment(), text=True, encoding='utf-8', errors='replace',
            creationflags=NO_WINDOW, timeout=timeout, check=False,
        )
        if capture and any(secret in result.stdout + result.stderr for secret in self.secrets.values()):
            raise AssertionError('Ephemeral password appeared in captured diagnostics; output withheld')
        return result

    def psql(self, sql=None, script=None, user='postgres', database='ls_core_dev', reply=None, apply=False):
        env = environment()
        env.update(PGPASSWORD=self.secrets[user], PGCONNECT_TIMEOUT='5', PGCLIENTENCODING='UTF8')
        if os.name == 'nt':
            env['OSTYPE'] = 'msys'  # Secure password exchange stdin fallback in this isolated test only.
        args = [self.executable('psql'), '-X', '-w', '-h', '127.0.0.1', '-p', str(self.port),
                '-U', user, '-d', database, '-v', 'ON_ERROR_STOP=1',
                '-v', 'apply=' + ('true' if apply else 'false'), '-At']
        args += ['-f', script] if script else ['-c', sql]
        return self.run(args, input_text=reply, env=env)

    def query(self, sql, user='postgres'):
        result = self.psql(sql=sql, user=user)
        if result.returncode:
            raise AssertionError('Isolated SQL assertion failed; diagnostics withheld')
        return result.stdout.strip()

    def flyway(self, goal, location=SOURCE):
        env = environment()
        env.update(FLYWAY_URL=f'jdbc:postgresql://127.0.0.1:{self.port}/ls_core_dev?connectTimeout=5',
                   FLYWAY_USER='ls_core_migrator_dev', FLYWAY_PASSWORD=self.secrets['ls_core_migrator_dev'])
        command = [str(BACKEND / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(BACKEND / 'mvnw')]
        command += ['-B', '-ntp', '-f', str(BACKEND / 'pom.xml'), '-Pdatabase-migrations',
                    '-Dflyway.locations=filesystem:' + str(location), 'flyway:' + goal]
        return self.run(command, env=env, timeout=180)

    def start(self):
        version = self.run([self.executable('initdb'), '--version'])
        check(version.returncode == 0 and ' 17.' in version.stdout, 'PostgreSQL 17 binaries')
        init_env = environment()
        if os.name == 'nt':
            init_env['OSTYPE'] = 'msys'
        init = self.run([self.executable('initdb'), '-D', self.data, '-U', 'postgres',
                         '--auth-local=trust', '--auth-host=scram-sha-256', '--pwprompt',
                         '--encoding=UTF8', '--locale=C', '--no-instructions'],
                        input_text=self.secrets['postgres'] + '\n' + self.secrets['postgres'] + '\n', env=init_env)
        check(init.returncode == 0, 'fresh disposable initdb with SCRAM host authentication')
        if os.name != 'nt':
            configured = self.run([self.executable('postgres'), '-D', self.data, '-C', 'unix_socket_directories'])
            check(configured.returncode == 0, 'inspect Unix socket setting of newly initialized cluster')
            socket_directory = configured.stdout.strip()
            if socket_directory.startswith('/') and ',' not in socket_directory and not os.access(socket_directory, os.W_OK):
                # Reproduce the original runner failure without changing system directory permissions.
                # The process/data directory/port are all ours; the unwritable path prevents socket creation.
                diagnostic = self.root / 'default-socket.log'
                original = self.run([self.executable('pg_ctl'), '-D', self.data, '-l', diagnostic,
                                     '-o', f'-h 127.0.0.1 -p {self.port}', '-w', 'start'], capture=False)
                startup_log = diagnostic.read_text(encoding='utf-8', errors='replace')
                check(original.returncode != 0 and 'could not create lock file' in startup_log
                      and ('Permission denied' in startup_log or 'No such file or directory' in startup_log),
                      'original startup failure reproduced: system Unix socket lock file cannot be created')
                check(not any(secret in startup_log for secret in self.secrets.values()),
                      'socket failure diagnostic contains no ephemeral password')
                for line in startup_log.splitlines():
                    if 'FATAL' in line and 'could not create lock file' in line:
                        print('Confirmed disposable startup cause:', line[:500], flush=True)
            else:
                print('INFO: default Unix socket directory is usable; no system-path failure probe needed', flush=True)
            # Debian/Ubuntu binaries default to a system-owned Unix socket directory.
            # This fixture uses only its private TCP endpoint; change only its new PGDATA.
            with (self.data / 'postgresql.conf').open('a', encoding='utf-8') as config:
                config.write("\nunix_socket_directories = ''\n")
        start = self.run([self.executable('pg_ctl'), '-D', self.data, '-l', self.root / 'server.log',
                          '-o', f'-h 127.0.0.1 -p {self.port}', '-w', 'start'], capture=False)
        if start.returncode:
            log = (self.root / 'server.log').read_text(encoding='utf-8', errors='replace')
            if not any(secret in log for secret in self.secrets.values()):
                for line in log.splitlines():
                    if any(term in line for term in ['FATAL', 'PANIC', 'ERROR']):
                        print('Disposable PostgreSQL startup:', line[:500], flush=True)
        check(start.returncode == 0, 'isolated cluster started on non-5432 ephemeral port')

    def stop(self):
        if (self.data / 'postmaster.pid').exists():
            stopped = self.run([self.executable('pg_ctl'), '-D', self.data, '-m', 'fast', '-w', 'stop'],
                               capture=False)
            if stopped.returncode:
                raise RuntimeError('Isolated cluster could not stop; retaining temporary files')


@contextmanager
def disposable_cluster():
    root = Path(tempfile.mkdtemp(prefix='ls-core-migrations-test-')).resolve()
    if root.parent != Path(tempfile.gettempdir()).resolve() or not root.name.startswith('ls-core-migrations-test-'):
        raise RuntimeError('Unexpected cleanup path')
    cluster = Cluster(root)
    try:
        cluster.start()
        yield cluster
    finally:
        cluster.stop()
        if root.parent != Path(tempfile.gettempdir()).resolve() or not root.name.startswith('ls-core-migrations-test-'):
            raise RuntimeError('Unsafe cleanup target; retained')
        shutil.rmtree(root)


def prepare_fixture(cluster):
    check(cluster.psql(sql='CREATE DATABASE ls_core_dev OWNER postgres TEMPLATE template0',
                       database='postgres').returncode == 0, 'fresh isolated ls_core_dev fixture')
    result = cluster.psql(sql="CREATE ROLE ls_core_app_dev NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB "
                          "NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 3; "
                          "REVOKE ALL ON DATABASE ls_core_dev FROM PUBLIC; "
                          "GRANT CONNECT ON DATABASE ls_core_dev TO ls_core_app_dev; "
                          "CREATE SCHEMA fixture_unrelated AUTHORIZATION postgres; "
                          "CREATE TABLE fixture_unrelated.sentinel (id integer PRIMARY KEY); "
                          "INSERT INTO fixture_unrelated.sentinel VALUES (25)")
    check(result.returncode == 0, 'limited application role and unrelated sentinel fixture')
    result = cluster.psql(sql='\\password ls_core_app_dev',
                          reply=cluster.secrets['ls_core_app_dev'] + '\n' + cluster.secrets['ls_core_app_dev'] + '\n')
    check(result.returncode == 0, 'application password exchanged without SQL literal')
    cluster.query('ALTER ROLE ls_core_app_dev LOGIN')


def history(cluster):
    return cluster.query('SELECT row_to_json(h)::text FROM operations.flyway_schema_history h ORDER BY installed_rank')


def test_backend_without_migration(cluster):
    jar = BACKEND / 'target/ls-core-0.0.1-SNAPSHOT.jar'
    check(jar.is_file(), 'current backend JAR available (run clean verify first)')
    with zipfile.ZipFile(jar) as archive:
        check(not any('flyway' in name.lower() and name.endswith('.jar') for name in archive.namelist()),
              'Flyway is absent from packaged application dependencies')
    env = environment()
    env.update(SPRING_PROFILES_ACTIVE='postgres', SERVER_ADDRESS='127.0.0.1', SERVER_PORT='0',
               LS_DB_HOST='127.0.0.1', LS_DB_PORT=str(cluster.port), LS_DB_NAME='ls_core_dev',
               LS_DB_USERNAME='ls_core_app_dev', LS_DB_PASSWORD=cluster.secrets['ls_core_app_dev'])
    java_home = Path(os.environ['JAVA_HOME'])
    java = java_home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    log = cluster.root / 'backend.log'
    with log.open('w', encoding='utf-8') as output:
        process = subprocess.Popen([str(java), '-jar', str(jar)], cwd=BACKEND, env=env,
                                   stdin=subprocess.DEVNULL, stdout=output, stderr=output, creationflags=NO_WINDOW)
        try:
            deadline = time.monotonic() + 45
            port = None
            while time.monotonic() < deadline and process.poll() is None:
                match = re.search(r'Tomcat started on port (\d+)', log.read_text(encoding='utf-8', errors='replace'))
                if match:
                    port = int(match.group(1))
                    break
                time.sleep(0.1)
            check(port is not None and port != 5432, 'postgres profile starts on an ephemeral HTTP port')
            with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health', timeout=15) as response:
                check(response.status == 200 and json.load(response)['status'] == 'UP',
                      'existing application-role JDBC health is HTTP 200 / UP before Flyway')
            check(cluster.query("SELECT to_regclass('operations.flyway_schema_history') IS NULL") == 't',
                  'postgres application startup creates no Flyway history or migrations')
        finally:
            if process.poll() is None:
                process.terminate()
            process.wait(timeout=15)
    check(not any(secret in log.read_text(encoding='utf-8', errors='replace')
                  for secret in cluster.secrets.values()), 'no ephemeral credentials in application log')


def test_migrations(cluster):
    prepare_fixture(cluster)
    before = cluster.query("SELECT row_to_json(r)::text FROM pg_authid r WHERE rolname='ls_core_app_dev'")
    inspector = ROOT / 'scripts/migrations/inspect.sql'
    provision = ROOT / 'scripts/migrations/provision.sql'
    check(cluster.psql(script=inspector).returncode == 0, 'read-only inspection succeeds')
    # Default PostgreSQL PUBLIC CONNECT on this new cluster must fail closed, not be silently inherited.
    check(cluster.psql(script=provision).returncode != 0, 'raw provision script defaults to no changes')
    refused = cluster.psql(script=provision, apply=True)
    check(refused.returncode != 0 and 'PUBLIC CONNECT' in refused.stderr,
          'provisioning refuses inherited access to other databases without changing their ACLs')
    check(cluster.query("SELECT count(*) FROM pg_roles WHERE rolname='ls_core_migrator_dev'") == '0',
          'failed provisioning left no migrator role')
    # Fixture-only ACL preparation on the newly created cluster, never on an existing/shared installation.
    for database in ['postgres', 'template1']:
        check(cluster.psql(sql=f'REVOKE CONNECT, TEMPORARY ON DATABASE {database} FROM PUBLIC').returncode == 0,
              'disposable fixture PUBLIC privileges prepared: ' + database)
    password = cluster.secrets['ls_core_migrator_dev']
    applied = cluster.psql(script=provision, reply=password + '\n' + password + '\n', apply=True)
    check(applied.returncode == 0, 'explicit provisioning on disposable cluster')
    migrator_before = cluster.query("SELECT row_to_json(r)::text FROM pg_authid r WHERE rolname='ls_core_migrator_dev'")
    check(cluster.psql(script=provision, apply=True).returncode != 0,
          'existing migrator refused without adoption or modification')
    check(cluster.query("SELECT row_to_json(r)::text FROM pg_authid r WHERE rolname='ls_core_migrator_dev'")
          == migrator_before, 'existing migrator identity, attributes and password unchanged after refusal')
    check(cluster.query("SELECT row_to_json(r)::text FROM pg_authid r WHERE rolname='ls_core_app_dev'") == before,
          'application attributes, identity and password preserved')
    actual = set(cluster.query("SELECT nspname FROM pg_namespace WHERE pg_get_userbyid(nspowner)="
                               "'ls_core_migrator_dev'").splitlines())
    check(actual == SCHEMAS, 'exactly eight authorized schemas owned by migrator')
    test_backend_without_migration(cluster)
    migrated = cluster.flyway('migrate')
    if migrated.returncode:
        # Print only known, non-sensitive diagnostic categories, never complete Maven/connection output.
        categories = ['Unable to connect', 'No database found', 'No migrations found', 'permission denied',
                      'non-empty schema', 'Unsupported Database', 'Too many connections', 'No plugin found']
        print('Flyway failure categories:', [term for term in categories if term.lower() in migrated.stdout.lower()])
        raise AssertionError('Initial isolated Flyway migration failed; diagnostics withheld')
    check('11.14.1' in migrated.stdout, 'Flyway 11.14.1 resolved and executed with Java 21')
    versions = [path.name.split('__')[0][1:] for path in sorted(SOURCE.glob('V*__*.sql'))]
    check(cluster.query('SELECT version FROM operations.flyway_schema_history ORDER BY installed_rank').splitlines()
          == versions, 'migration versions recorded in order')
    check(cluster.query('SELECT bool_and(success AND checksum IS NOT NULL) FROM operations.flyway_schema_history') == 't',
          'history has successful migrations and checksums')
    check(cluster.psql(script=inspector).returncode == 0,
          'read-only inspection reports applied versions/checksums after migration')
    original_history = history(cluster)
    check(cluster.flyway('migrate').returncode == 0 and history(cluster) == original_history,
          'second migrate does not reapply or change history')
    check(cluster.flyway('validate').returncode == 0, 'Flyway validates original versions and checksums')
    altered = cluster.root / 'altered-migrations'
    shutil.copytree(SOURCE, altered)
    first = sorted(altered.glob('V*__*.sql'))[0]
    first.write_text(first.read_text(encoding='utf-8') + '\nSELECT 25;\n', encoding='utf-8')
    invalid = cluster.flyway('validate', altered)
    check(invalid.returncode != 0 and 'checksum' in invalid.stdout.lower(),
          'changed applied migration rejected by checksum validation')
    check(history(cluster) == original_history, 'checksum mismatch changes no history')
    # A future migration that fails after DDL must roll back both the DDL and its history row.
    rollback = cluster.root / 'rollback-migrations'
    shutil.copytree(SOURCE, rollback)
    (rollback / 'V99__rollback_probe.sql').write_text(
        'CREATE TABLE operations.rollback_probe (id uuid PRIMARY KEY);\nSELECT 1/0;\n', encoding='utf-8')
    check(cluster.flyway('migrate', rollback).returncode != 0, 'invalid future migration fails')
    check(cluster.query("SELECT to_regclass('operations.rollback_probe') IS NULL") == 't'
          and history(cluster) == original_history, 'transaction rollback preserves schema/history on failure')
    for role in ['ls_core_app_dev', 'ls_core_migrator_dev']:
        check(cluster.query("SELECT NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls OR rolreplication "
                            "OR rolinherit) AND rolconnlimit=3 FROM pg_roles WHERE rolname='" + role + "'") == 't',
              role + ': no administrative attributes, memberships or unlimited connections')
        check(cluster.query("SELECT NOT has_database_privilege(current_user,current_database(),'CREATE') "
                            "AND NOT has_database_privilege(current_user,current_database(),'TEMPORARY')", role) == 't',
              role + ': no database CREATE/TEMP')
        check(cluster.query("SELECT count(*) FROM pg_database WHERE datallowconn AND datname<>current_database() "
                            "AND has_database_privilege(current_user,oid,'CONNECT')", role) == '0',
              role + ': no CONNECT to other databases in isolated fixture')
    for sql in ['CREATE SCHEMA forbidden_probe', 'CREATE TABLE public.forbidden_probe (id integer)',
                'CREATE TABLE tenant.forbidden_probe (id integer)',
                'SELECT * FROM operations.flyway_schema_history',
                'ALTER TABLE operations.flyway_schema_history ADD COLUMN forbidden integer',
                'CREATE ROLE forbidden_probe', 'CREATE DATABASE forbidden_probe']:
        check(cluster.psql(sql=sql, user='ls_core_app_dev').returncode != 0, 'application forbidden operation rejected')
    check(cluster.psql(sql='CREATE SCHEMA forbidden_probe', user='ls_core_migrator_dev').returncode != 0,
          'migrator cannot create unauthorized schema')
    check(set(cluster.query("SELECT nspname FROM pg_namespace WHERE "
                            "has_schema_privilege(current_user,oid,'CREATE')", 'ls_core_migrator_dev').splitlines())
          == SCHEMAS, 'migrator CREATE privilege is restricted to the eight authorized schemas')
    for sql in ['CREATE TABLE public.forbidden_probe (id integer)', 'CREATE ROLE forbidden_probe',
                'CREATE DATABASE forbidden_probe']:
        check(cluster.psql(sql=sql, user='ls_core_migrator_dev').returncode != 0,
              'migrator forbidden public/global operation rejected')
    check(cluster.psql(sql='ALTER TABLE fixture_unrelated.sentinel ADD COLUMN forbidden integer',
                       user='ls_core_migrator_dev').returncode != 0, 'migrator cannot alter unrelated objects')
    check(cluster.query('SELECT id FROM fixture_unrelated.sentinel') == '25', 'unrelated sentinel preserved')
    return original_history


def main():
    with disposable_cluster() as cluster:
        test_migrations(cluster)
        for path in [cluster.root / 'server.log', *SOURCE.glob('*.sql')]:
            check(not any(secret in path.read_text(encoding='utf-8', errors='replace')
                          for secret in cluster.secrets.values()), 'no plaintext ephemeral password in ' + path.name)
    print('All isolated migration checks passed. Shared PostgreSQL was not contacted.', flush=True)


if __name__ == '__main__':
    main()

"""Flyway/permissions regression on a newly initialized, disposable PostgreSQL 17 cluster only.

Run from any directory: python scripts/tests/test_postgres_migrations.py
Uses standard-library Python, JDK 21/Maven Wrapper, and existing PG17 binaries (LS_PG_BIN).
There is deliberately no option to supply a server URL, credentials, or an existing data directory.
"""

from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import zipfile


sys.dont_write_bytecode = True
from postgres_integrity_contract import COMPOSITES, entries, integrity_snapshot, test_integrity_structure, test_integrity_values
from postgres_seed_contract import manifest, data_snapshot, test_seeds


ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / 'backend'
SOURCE = BACKEND / 'src/main/resources/db/migration'
SCHEMAS = {'identity', 'platform', 'tenant', 'security', 'catalog', 'integration', 'audit', 'operations'}
MODEL = """identity.users|E|username:text? email:text? email_verified_at:timestamptz? display_name:text status:text
identity.user_credentials|M:user_id|user_id:uuid password_hash:text must_change_password:boolean password_changed_at:timestamptz password_expires_at:timestamptz failed_attempts:integer locked_until:timestamptz?
platform.administrators|E|user_id:uuid administrator_kind:text status:text granted_by_user_id:uuid? revoked_by_user_id:uuid? revoked_at:timestamptz?
catalog.applications|E|code:text name:text description:text? status:text
catalog.modules|E|application_id:uuid code:text name:text description:text? status:text
security.permissions|E|application_id:uuid module_id:uuid? code:text name:text description:text? resource_scope:text status:text
security.role_templates|E|application_id:uuid code:text version:integer name:text description:text? status:text
security.role_template_permissions|C:role_template_id,permission_id|application_id:uuid role_template_id:uuid permission_id:uuid
security.profile_templates|E|application_id:uuid code:text version:integer name:text description:text? status:text
security.profile_template_roles|C:profile_template_id,role_template_id|application_id:uuid profile_template_id:uuid role_template_id:uuid
tenant.tenants|E|name:text status:text
tenant.branches|E|tenant_id:uuid code:text name:text status:text
tenant.memberships|E|tenant_id:uuid user_id:uuid status:text invited_by_user_id:uuid? accepted_at:timestamptz?
tenant.branch_memberships|E|tenant_id:uuid membership_id:uuid branch_id:uuid status:text
tenant.administrators|E|tenant_id:uuid membership_id:uuid status:text granted_by_user_id:uuid? granted_at:timestamptz revoked_by_user_id:uuid? revoked_at:timestamptz?
catalog.tenant_applications|E|tenant_id:uuid application_id:uuid status:text
catalog.tenant_modules|E|tenant_id:uuid application_id:uuid module_id:uuid status:text
security.roles|E|tenant_id:uuid application_id:uuid code:text name:text description:text? status:text source_role_template_id:uuid?
security.role_permissions|C:tenant_id,role_id,permission_id|tenant_id:uuid application_id:uuid role_id:uuid permission_id:uuid
security.profiles|E|tenant_id:uuid application_id:uuid code:text name:text description:text? status:text source_profile_template_id:uuid?
security.profile_roles|C:tenant_id,profile_id,role_id|tenant_id:uuid application_id:uuid profile_id:uuid role_id:uuid
security.branch_profile_assignments|E|tenant_id:uuid application_id:uuid branch_membership_id:uuid profile_id:uuid status:text granted_by_user_id:uuid? revoked_at:timestamptz?
security.sessions|E|user_id:uuid application_id:uuid token_hash:text expires_at:timestamptz last_seen_at:timestamptz? revoked_at:timestamptz? revoked_by_user_id:uuid?
security.session_contexts|E|session_id:uuid user_id:uuid application_id:uuid tenant_id:uuid membership_id:uuid branch_id:uuid expires_at:timestamptz? revoked_at:timestamptz?
integration.api_keys|E|tenant_id:uuid application_id:uuid branch_id:uuid? name:text key_hash:text created_by_user_id:uuid expires_at:timestamptz revoked_at:timestamptz? revoked_by_user_id:uuid?
integration.api_key_permissions|C:tenant_id,api_key_id,permission_id|tenant_id:uuid application_id:uuid api_key_id:uuid permission_id:uuid
audit.events|A|occurred_at:timestamptz tenant_id:uuid? application_id:uuid? actor_kind:text actor_user_id:uuid? actor_api_key_id:uuid? session_id:uuid? action_code:text resource_type:text? resource_id:uuid? outcome:text correlation_id:uuid? metadata:jsonb
operations.maintenance_windows|E|name:text reason:text starts_at:timestamptz ends_at:timestamptz? status:text requested_by_user_id:uuid
operations.operation_runs|E|operation_type:text status:text requested_by_user_id:uuid? started_at:timestamptz? finished_at:timestamptz? result_code:text? correlation_id:uuid? maintenance_window_id:uuid? artifact_reference:text?"""


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
        if self.port in (5432, 55432):
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
        if script:
            args += ['-f', script]
        elif reply is None and len(sql) > 4000:
            # Large rollback-only SQL fixtures exceed Windows CreateProcess argument limits.
            # Feed SQL directly over stdin, without files or command-line disclosure.
            args += ['-f', '-']
            reply = sql
        else:
            args += ['-c', sql]
        return self.run(args, input_text=reply, env=env)

    def query(self, sql, user='postgres'):
        result = self.psql(sql=sql, user=user)
        if result.returncode:
            raise AssertionError('Isolated SQL assertion failed; diagnostics withheld')
        return result.stdout.strip()

    def flyway(self, goal, location=None, target=None):
        location = location or getattr(self, 'migration_source', SOURCE)
        env = environment()
        env.update(FLYWAY_URL=f'jdbc:postgresql://127.0.0.1:{self.port}/ls_core_dev?connectTimeout=5',
                   FLYWAY_USER='ls_core_migrator_dev', FLYWAY_PASSWORD=self.secrets['ls_core_migrator_dev'])
        command = [str(BACKEND / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(BACKEND / 'mvnw')]
        command += ['-B', '-ntp', '-f', str(BACKEND / 'pom.xml'), '-Pdatabase-migrations',
                    '-Dflyway.locations=filesystem:' + str(location), 'flyway:' + goal]
        if target is not None:
            command.append('-Dflyway.target=' + str(target))
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


def test_context_transactions(cluster):
    """Real JDBC/pool behavior; identity evidence remains explicitly synthetic Java fixtures."""
    env = environment()
    env.update(LS_CONTEXT_TEST_ROOT=str(cluster.root), LS_CONTEXT_TEST_PORT=str(cluster.port),
               LS_CONTEXT_TEST_PASSWORD=cluster.secrets['ls_core_app_dev'])
    command = [str(BACKEND / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(BACKEND / 'mvnw')]
    command += ['-B', '-ntp', '-f', str(BACKEND / 'pom.xml'),
                '-Dtest=ContextTransactionPostgresTest', 'test']
    result = cluster.run(command, env=env, timeout=180)
    check(result.returncode == 0 and 'Tests run: 5, Failures: 0, Errors: 0, Skipped: 0' in result.stdout,
          'LFD-28: five real JDBC tests on disposable PG17, synthetic identities, limited runtime, no grants')


def history(cluster):
    return cluster.query('SELECT row_to_json(h)::text FROM operations.flyway_schema_history h ORDER BY installed_rank')


def wait_for_health(url, is_running, timeout=30):
    """A listening Tomcat port is not a promise that asynchronous JDBC health is UP yet."""
    deadline = time.monotonic() + timeout
    last = 'not ready'
    while is_running() and time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=min(5, max(0.01, deadline - time.monotonic()))) as response:
                if response.status == 200 and json.load(response).get('status') == 'UP':
                    return
                last = 'HTTP 200 without UP'
        except urllib.error.HTTPError as failure:
            failure.close()
            if failure.code != 503:
                raise
            last = 'HTTP 503'
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            last = 'connection not ready'
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(0.2, remaining))
    raise AssertionError('JDBC startup did not reach HTTP 200 / UP within the bounded wait: ' + last)


def test_health_readiness_wait():
    """Real loopback HTTP responses prove retries never turn permanent DOWN into a pass."""
    @contextmanager
    def endpoint(statuses):
        requests = []

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                code = statuses[min(len(requests), len(statuses) - 1)]
                requests.append(code)
                body = json.dumps({'status': 'UP' if code == 200 else 'DOWN'}).encode()
                self.send_response(code)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = HTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.05}, daemon=True)
        thread.start()
        try:
            yield f'http://127.0.0.1:{server.server_port}/actuator/health', requests
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    with endpoint([503, 503, 200]) as (url, requests):
        wait_for_health(url, lambda: True, timeout=3)
        check(requests == [503, 503, 200], 'readiness wait tolerates transient 503 only until HTTP 200 / UP')
    with endpoint([503]) as (url, requests):
        try:
            wait_for_health(url, lambda: True, timeout=0.3)
        except AssertionError as failure:
            check('HTTP 503' in str(failure), 'readiness wait fails on permanent DOWN at its deadline')
        else:
            raise AssertionError('Permanent HTTP 503 was accepted')
    with endpoint([404]) as (url, requests):
        try:
            wait_for_health(url, lambda: True, timeout=3)
        except urllib.error.HTTPError as failure:
            check(failure.code == 404 and len(requests) == 1, 'readiness wait does not hide unexpected HTTP errors')
        else:
            raise AssertionError('HTTP 404 was accepted')


def test_backend_without_migration(cluster, expected_history=None):
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
            wait_for_health(f'http://127.0.0.1:{port}/actuator/health', lambda: process.poll() is None)
            check(True, 'existing application-role JDBC health is HTTP 200 / UP')
            if expected_history is None:
                check(cluster.query("SELECT to_regclass('operations.flyway_schema_history') IS NULL") == 't',
                      'postgres application startup creates no Flyway history or migrations')
            else:
                check(history(cluster) == expected_history, 'runtime startup leaves V1-V8 Flyway history unchanged')
        finally:
            if process.poll() is None:
                process.terminate()
            process.wait(timeout=15)
    check(not any(secret in log.read_text(encoding='utf-8', errors='replace')
                  for secret in cluster.secrets.values()), 'no ephemeral credentials in application log')


def model_contract():
    """Independent literal dictionary from approval 10044, never parsed from SQL under test."""
    tables = {}
    for line in MODEL.splitlines():
        table, pattern, fields = line.split('|')
        kind = pattern[0]
        columns = {}
        if kind in 'EA':
            columns['id'] = ('uuid', False, 'gen_random_uuid()')
        for field in fields.split():
            name, data_type = field.split(':')
            columns[name] = (data_type.rstrip('?'), data_type.endswith('?'), None)
        columns['created_at'] = ('timestamptz', False, 'CURRENT_TIMESTAMP')
        if kind in 'EM':
            columns['updated_at'] = ('timestamptz', False, 'CURRENT_TIMESTAMP')
        primary = ['id'] if kind in 'EA' else pattern.split(':')[1].split(',')
        tables[table] = (columns, primary)
    return tables


def schema_snapshot(cluster):
    return cluster.query("SELECT row_to_json(c)::text FROM (SELECT n.nspname, c.relname, a.attname, "
                         "a.atttypid, a.attnotnull, pg_get_expr(d.adbin,d.adrelid) AS default_value "
                         "FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                         "JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped "
                         "LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum "
                         "WHERE n.nspname IN ('identity','platform','tenant','security','catalog',"
                         "'integration','audit','operations') AND c.relkind='r' "
                         "ORDER BY n.nspname,c.relname,a.attnum) c") + integrity_snapshot(cluster)


def test_model_structure(cluster):
    expected = model_contract()
    schema_list = ','.join("'" + name + "'" for name in sorted(SCHEMAS))
    actual = set(cluster.query("SELECT n.nspname||'.'||c.relname FROM pg_class c JOIN pg_namespace n "
                               "ON n.oid=c.relnamespace WHERE c.relkind IN ('r','p') AND n.nspname IN ("
                               + schema_list + ")").splitlines())
    check(len(expected) == 29 and actual == set(expected) | {'operations.flyway_schema_history'},
          'exactly 29 approved domain tables plus separate Flyway history; no unexpected tables')
    type_names = {'timestamptz': 'timestamp with time zone'}
    for table, (columns, primary) in expected.items():
        schema, name = table.split('.')
        data = json.loads(cluster.query(
            "SELECT json_agg(row_to_json(c)) FROM (SELECT column_name,data_type,is_nullable,column_default "
            "FROM information_schema.columns WHERE table_schema='" + schema + "' AND table_name='" + name
            + "' ORDER BY ordinal_position) c"))
        check([c['column_name'] for c in data] == list(columns), table + ': exact ordered columns')
        for c in data:
            typ, nullable, default = columns[c['column_name']]
            if (c['data_type'], c['is_nullable'], c['column_default']) != (
                    type_names.get(typ, typ), 'YES' if nullable else 'NO', default):
                raise AssertionError(table + '.' + c['column_name'] + ': type/nullability/default mismatch')
        check(True, table + ': all types, nullability and technical defaults match dictionary')
        keys = cluster.query("SELECT a.attname FROM pg_constraint p CROSS JOIN LATERAL "
                             "unnest(p.conkey) WITH ORDINALITY k(attnum,position) JOIN pg_attribute a "
                             "ON a.attrelid=p.conrelid AND a.attnum=k.attnum WHERE p.contype='p' "
                             "AND p.conrelid='" + table + "'::regclass ORDER BY k.position").splitlines()
        check(keys == primary, table + ': approved primary key')
        check(cluster.query('SELECT count(*) FROM ' + table) == str(len(manifest().get(table, ([], []))[1])),
              table + ': approved seed count or empty domain table')
    global_targets = {
        'user_id': 'identity.users', 'granted_by_user_id': 'identity.users',
        'revoked_by_user_id': 'identity.users', 'invited_by_user_id': 'identity.users',
        'created_by_user_id': 'identity.users', 'requested_by_user_id': 'identity.users',
        'application_id': 'catalog.applications', 'module_id': 'catalog.modules',
        'role_template_id': 'security.role_templates', 'permission_id': 'security.permissions',
        'profile_template_id': 'security.profile_templates', 'source_role_template_id': 'security.role_templates',
        'source_profile_template_id': 'security.profile_templates', 'tenant_id': 'tenant.tenants',
        'maintenance_window_id': 'operations.maintenance_windows',
    }
    expected_references = {table + '|' + table.split('.')[1] + '_' + column + '_fk|' + column + '|' + target + '|id|a|a'
                           for table, (columns, _) in expected.items() if table != 'audit.events'
                           for column, target in global_targets.items() if column in columns}
    for table, name, columns, parent, keys, old in entries(COMPOSITES):
        if old:
            old_column = old[len(table.split('.')[1]) + 1:-3]
            expected_references.remove(table + '|' + old + '|' + old_column + '|' + parent + '|id|a|a')
        expected_references.add(table + '|' + name + '|' + columns + '|' + parent + '|' + keys + '|a|a')
    references = cluster.query(
        "SELECT ns.nspname||'.'||t.relname||'|'||f.conname||'|'||array_to_string(ARRAY(SELECT a.attname FROM "
        "unnest(f.conkey) WITH ORDINALITY k(num,pos) JOIN pg_attribute a ON a.attrelid=t.oid "
        "AND a.attnum=k.num ORDER BY k.pos),',')||'|'||nd.nspname||'.'||d.relname||'|'||"
        "array_to_string(ARRAY(SELECT a.attname FROM unnest(f.confkey) WITH ORDINALITY k(num,pos) "
        "JOIN pg_attribute a ON a.attrelid=d.oid AND a.attnum=k.num ORDER BY k.pos),',')||'|'||"
        "f.confdeltype::text||'|'||f.confupdtype::text FROM pg_constraint f "
        "JOIN pg_class t ON t.oid=f.conrelid JOIN pg_namespace ns ON ns.oid=t.relnamespace "
        "JOIN pg_class d ON d.oid=f.confrelid JOIN pg_namespace nd ON nd.oid=d.relnamespace "
        "WHERE f.contype='f' AND ns.nspname IN (" + schema_list + ")")
    check(set(references.splitlines()) == expected_references and len(references.splitlines()) == len(expected_references),
          'exact FK names and global/root plus 29 composite column orders/targets with NO ACTION')
    check(cluster.query("SELECT count(*) FROM pg_trigger g JOIN pg_class c ON c.oid=g.tgrelid "
                        "JOIN pg_namespace n ON n.oid=c.relnamespace WHERE NOT g.tgisinternal "
                        "AND n.nspname IN (" + schema_list + ")") == '0',
          'no generic updated_at or functional business triggers')
    check(cluster.query("SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid "
                        "JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname IN (" + schema_list
                        + ") AND t.relname<>'flyway_schema_history' AND c.contype='c'") == '16',
          '16 CHECKs: original 5 plus 11 presence/coherence rules; other lifecycles not frozen')
    check(cluster.query("SELECT count(*) FROM pg_constraint WHERE contype='f' "
                        "AND (conrelid='audit.events'::regclass OR confrelid='audit.events'::regclass) "
                        "AND (confdeltype='c' OR confupdtype='c')") == '0',
          'audit evidence has no cascading deletes or updates')
    check(cluster.query("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                        "WHERE n.nspname IN (" + schema_list + ") AND c.relkind='r' AND "
                        "(pg_get_userbyid(c.relowner)<>'ls_core_migrator_dev' OR c.relrowsecurity)") == '0',
          'tables/history belong to migrator; no unsupported claim of RLS isolation')
    check(cluster.query("SELECT count(*) FROM pg_namespace n CROSS JOIN LATERAL "
                        "aclexplode(coalesce(n.nspacl,acldefault('n',n.nspowner))) a "
                        "WHERE n.nspname IN (" + schema_list + ") AND a.grantee=0") == '0',
          'PUBLIC has no privileges on the eight schemas')
    check(cluster.query("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                        "CROSS JOIN LATERAL aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a "
                        "WHERE n.nspname IN (" + schema_list + ") AND c.relkind='r' AND a.grantee=0") == '0',
          'PUBLIC has no privileges on new tables/history')
    check(cluster.query("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                        "WHERE n.nspname IN (" + schema_list + ") AND c.relkind='r' AND "
                        "has_table_privilege('ls_core_app_dev',c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')") == '0',
          'runtime receives no domain or history table privileges')
    test_integrity_structure(cluster, check, expected)


def test_model_values(cluster):
    # Synthetic rows live only in rolled-back transactions, never seeds or persisted secrets.
    fixtures = ("INSERT INTO identity.users(id,email,display_name,status) VALUES "
                "('00000000-0000-0000-0000-000000000001','fixture.invalid','Fixture','UNDEFINED');"
                "INSERT INTO tenant.tenants(id,name,status) VALUES "
                "('00000000-0000-0000-0000-000000000002','Fixture','PENDING');"
                "INSERT INTO catalog.applications(id,code,name,status) VALUES "
                "('00000000-0000-0000-0000-000000000003','fixture','Fixture','UNDEFINED');")
    uid = "'00000000-0000-0000-0000-000000000001'"
    tenant = "'00000000-0000-0000-0000-000000000002'"
    app = "'00000000-0000-0000-0000-000000000003'"
    module = "'00000000-0000-0000-0000-000000000004'"
    fixtures += ("INSERT INTO catalog.modules(id,application_id,code,name,status) VALUES ("
                 + module + "," + app + ",'fixture','Fixture','UNDEFINED');")
    cases = [
        ("INSERT INTO tenant.tenants(name,status) VALUES ('Fixture','INVALID')", 'tenant status'),
        ("INSERT INTO platform.administrators(user_id,administrator_kind,status) VALUES ("
         + uid + ",'INVALID','UNDEFINED')", 'global administrator kind'),
        ("INSERT INTO catalog.tenant_applications(tenant_id,application_id,status) VALUES ("
         + tenant + "," + app + ",'INVALID')", 'application entitlement status'),
        ("INSERT INTO catalog.tenant_modules(tenant_id,application_id,module_id,status) VALUES ("
         + tenant + "," + app + "," + module + ",'INVALID')", 'module entitlement status'),
        ("INSERT INTO identity.users(display_name,status) VALUES ('Fixture','UNDEFINED')", 'identifier presence'),
        ("INSERT INTO integration.api_keys(tenant_id,application_id,name,key_hash,created_by_user_id) VALUES ("
         + tenant + "," + app + ",'Fixture','non-authenticating-test-verifier'," + uid + ")", 'mandatory API key expiration'),
        ("INSERT INTO identity.user_credentials(user_id,password_hash,must_change_password,password_changed_at,"
         "failed_attempts) VALUES (" + uid + ",'non-authenticating-test-verifier',false,CURRENT_TIMESTAMP,0)",
         'mandatory credential expiration'),
    ]
    for sql, label in cases:
        result = cluster.psql(sql='BEGIN;' + fixtures + sql + ';ROLLBACK;', user='ls_core_migrator_dev')
        check(result.returncode != 0 and ('violates check constraint' in result.stderr
              if 'expiration' not in label else 'violates not-null constraint' in result.stderr),
              'model rejects invalid ' + label)
    positive = ("INSERT INTO tenant.tenants(name,status) SELECT 'Fixture',v FROM "
                "(VALUES ('PENDING'),('ACTIVE'),('SUSPENDED')) s(v);"
                "INSERT INTO catalog.tenant_applications(tenant_id,application_id,status) SELECT "
                + tenant + "," + app + ",'ACTIVE';"
                "UPDATE catalog.tenant_applications SET status='READ_ONLY';"
                "UPDATE catalog.tenant_applications SET status='DISABLED';"
                "INSERT INTO catalog.tenant_modules(tenant_id,application_id,module_id,status) SELECT "
                + tenant + "," + app + "," + module + ",'ACTIVE';"
                "UPDATE catalog.tenant_modules SET status='READ_ONLY';"
                "UPDATE catalog.tenant_modules SET status='DISABLED';"
                "INSERT INTO platform.administrators(user_id,administrator_kind,status) SELECT "
                + uid + ",v,'UNDEFINED' FROM (VALUES ('OWNER'),('SUPERADMIN')) s(v);"
                "INSERT INTO integration.api_keys(tenant_id,application_id,name,key_hash,created_by_user_id,expires_at) "
                "VALUES (" + tenant + "," + app + ",'Fixture','non-authenticating-test-verifier'," + uid
                + ",CURRENT_TIMESTAMP+INTERVAL '1 hour');"
                "INSERT INTO identity.user_credentials(user_id,password_hash,must_change_password,password_changed_at,"
                "password_expires_at,failed_attempts) VALUES (" + uid
                + ",'non-authenticating-test-verifier',false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '1 day',0);"
                "DO $$ BEGIN IF EXISTS (SELECT FROM identity.users WHERE id=" + uid
                + " AND (created_at IS NULL OR updated_at IS NULL)) THEN RAISE EXCEPTION 'timestamp default'; END IF;"
                "IF EXISTS (SELECT FROM tenant.tenants WHERE id IS NULL OR created_at IS NULL OR updated_at IS NULL) "
                "THEN RAISE EXCEPTION 'UUID/timestamp default'; END IF; END $$;")
    check(cluster.psql(sql='BEGIN;' + fixtures + positive + 'ROLLBACK;', user='ls_core_migrator_dev').returncode == 0,
          'native UUID/timestamp defaults, all approved values and verifier-only fixtures work without persisting rows')
    test_seeds(cluster, check, model_contract())


def test_seed_collisions(cluster):
    # Exercise the real Flyway V8 transaction against V7, in our new cluster only.
    check(cluster.flyway('migrate', target='7').returncode == 0, 'LFD-27 collision fixture migrated to V7')
    old_history = history(cluster)
    old_schema = schema_snapshot(cluster)
    fixture_app = "'00000000-0000-0000-0000-000000000027'"
    app = "'3bc09866-f9f0-573c-82a0-fd72ec6be41f'"
    profile = "'61e78d0c-1c59-5263-870a-4bc69a9319e6'"
    cases = [
        ('incompatible application UUID',
         "INSERT INTO catalog.applications(id,code,name,status) VALUES (" + app + ",'collision','Fixture','UNDEFINED')",
         'DELETE FROM catalog.applications WHERE id=' + app),
        ('normalized application code collision',
         "INSERT INTO catalog.applications(id,code,name,status) VALUES (" + fixture_app + ",' LS-CORE ','Fixture','UNDEFINED')",
         'DELETE FROM catalog.applications WHERE id=' + fixture_app),
        ('late profile UUID collision rolls back earlier V8 inserts',
         "INSERT INTO catalog.applications(id,code,name,status) VALUES (" + fixture_app + ",'fixture','Fixture','UNDEFINED');"
         "INSERT INTO security.profile_templates(id,application_id,code,version,name,status) VALUES ("
         + profile + ',' + fixture_app + ",'fixture',1,'Fixture','UNDEFINED')",
         'DELETE FROM security.profile_templates WHERE id=' + profile + ';DELETE FROM catalog.applications WHERE id=' + fixture_app),
    ]
    for label, setup, cleanup in cases:
        cluster.query('BEGIN;' + setup + ';COMMIT;', 'ls_core_migrator_dev')
        before = data_snapshot(cluster, model_contract())
        failed = cluster.flyway('migrate')
        check(failed.returncode != 0 and ('23505' in failed.stdout or 'duplicate key' in failed.stdout),
              'LFD-27 rejects ' + label)
        check(data_snapshot(cluster, model_contract()) == before and history(cluster) == old_history
              and schema_snapshot(cluster) == old_schema, 'LFD-27 collision preserves data/schema/V7 history')
        # Remove only the deliberately inserted fixture, not incompatible real data.
        cluster.query('BEGIN;' + cleanup + ';COMMIT;', 'ls_core_migrator_dev')
    check(cluster.flyway('validate', target='7').returncode == 0,
          'LFD-27: V7 remains valid after collision probes, without repair/baseline')


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
    test_seed_collisions(cluster)
    migrated = cluster.flyway('migrate')
    if migrated.returncode:
        # Print only known, non-sensitive diagnostic categories, never complete Maven/connection output.
        categories = ['Unable to connect', 'No database found', 'No migrations found', 'permission denied',
                      'non-empty schema', 'Unsupported Database', 'Too many connections', 'No plugin found']
        print('Flyway failure categories:', [term for term in categories if term.lower() in migrated.stdout.lower()])
        raise AssertionError('Initial isolated Flyway migration failed; diagnostics withheld')
    check('11.14.1' in migrated.stdout, 'Flyway 11.14.1 resolved and executed with Java 21')
    versions = ['1', '2', '3', '4', '5', '6', '7', '8']
    check(cluster.query('SELECT version FROM operations.flyway_schema_history ORDER BY installed_rank').splitlines()
          == versions, 'migration versions recorded in order')
    check(cluster.query('SELECT bool_and(success AND checksum IS NOT NULL) FROM operations.flyway_schema_history') == 't',
          'history has successful migrations and checksums')
    check(cluster.psql(script=inspector).returncode == 0,
          'read-only inspection reports applied versions/checksums after migration')
    old_checksums = {'1': -1378463590, '2': -895708369, '3': -849841087, '4': -1697819596,
                     '5': 1729249324, '6': -1148166384, '7': -1527763311}
    observed = {row['version']: row['checksum'] for row in map(json.loads, history(cluster).splitlines())}
    check(all(observed[v] == checksum for v, checksum in old_checksums.items()),
          'historical V1-V7 checksums are unchanged')
    test_context_transactions(cluster)
    test_model_structure(cluster)
    test_seeds(cluster, check, model_contract())
    original_data = data_snapshot(cluster, model_contract())
    test_model_values(cluster)
    test_integrity_values(cluster, check)
    check(data_snapshot(cluster, model_contract()) == original_data,
          'LFD-27: all seeds including timestamps preserved after LFD-25/LFD-26 rollback fixtures')
    original_schema = schema_snapshot(cluster)
    original_history = history(cluster)
    for entry in original_history.splitlines():
        row = json.loads(entry)
        print(f"Applied migration V{row['version']}: checksum={row['checksum']}, success={row['success']}", flush=True)
    check(cluster.flyway('migrate').returncode == 0 and history(cluster) == original_history,
          'second migrate does not reapply or change history')
    check(schema_snapshot(cluster) == original_schema, 'second migrate leaves physical schema unchanged')
    check(data_snapshot(cluster, model_contract()) == original_data, 'second migrate leaves all seed data unchanged')
    check(cluster.flyway('validate').returncode == 0, 'Flyway validates original versions and checksums')
    altered = cluster.root / 'altered-migrations'
    shutil.copytree(cluster.migration_source, altered)
    first = altered / 'V8__seed_approved_global_catalogs.sql'
    first.write_text(first.read_text(encoding='utf-8') + '\nSELECT 25;\n', encoding='utf-8')
    invalid = cluster.flyway('validate', altered)
    check(invalid.returncode != 0 and 'checksum' in invalid.stdout.lower(),
          'changed applied migration rejected by checksum validation')
    check(history(cluster) == original_history, 'checksum mismatch changes no history')
    # A future migration that fails after DDL must roll back both the DDL and its history row.
    rollback = cluster.root / 'rollback-migrations'
    shutil.copytree(cluster.migration_source, rollback)
    (rollback / 'V99__rollback_probe.sql').write_text(
        'CREATE TABLE operations.rollback_probe (id uuid PRIMARY KEY);\nSELECT 1/0;\n', encoding='utf-8')
    check(cluster.flyway('migrate', rollback).returncode != 0, 'invalid future migration fails')
    check(cluster.query("SELECT to_regclass('operations.rollback_probe') IS NULL") == 't'
          and history(cluster) == original_history, 'transaction rollback preserves schema/history on failure')
    check(cluster.flyway('validate').returncode == 0 and cluster.flyway('migrate').returncode == 0
          and history(cluster) == original_history and schema_snapshot(cluster) == original_schema,
          'recovery after failed DDL succeeds without repair, deletion or history changes')
    check(data_snapshot(cluster, model_contract()) == original_data, 'failed migration and recovery preserve exact seeds')
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
    test_backend_without_migration(cluster, original_history)
    check(data_snapshot(cluster, model_contract()) == original_data, 'runtime after V8 preserves exact seed snapshot')
    return original_history


def main():
    test_health_readiness_wait()
    with disposable_cluster() as cluster:
        # Historical DML/integrity regressions require the V8 baseline, before default-deny RLS.
        cluster.migration_source = cluster.root / 'v8-migrations'
        cluster.migration_source.mkdir()
        for migration in SOURCE.glob('V[1-8]__*.sql'):
            shutil.copy2(migration, cluster.migration_source)
        test_migrations(cluster)
        from postgres_rls_contract import test_rls
        test_rls(cluster)
        from postgres_catalog_acl_contract import test_catalog_acl
        test_catalog_acl(cluster)
        from postgres_catalog_service_contract import test_catalog_service
        test_catalog_service(cluster)
        from postgres_catalog_backend_contract import test_catalog_backend
        test_catalog_backend(cluster)
        from postgres_credential_verifier_contract import test_credential_verifier
        test_credential_verifier(cluster)
        from postgres_identity_registration_contract import test_identity_registration
        test_identity_registration(cluster)
        from postgres_password_lifecycle_contract import test_password_lifecycle
        test_password_lifecycle(cluster)
        for path in [cluster.root / 'server.log', *SOURCE.glob('*.sql')]:
            check(not any(secret in path.read_text(encoding='utf-8', errors='replace')
                          for secret in cluster.secrets.values()), 'no plaintext ephemeral password in ' + path.name)
    print('All isolated migration checks passed. Shared PostgreSQL was not contacted.', flush=True)


if __name__ == '__main__':
    main()

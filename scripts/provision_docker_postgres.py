"""Explicit Docker PG17 preflight/apply. No Windows-server destinations or repairs."""

import argparse
import getpass
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import hashlib

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_SYSTEM = '7698808171985612839'
SCHEMAS = ('identity', 'platform', 'tenant', 'security', 'catalog', 'integration', 'audit', 'operations')
ACL_DATABASES = ['ls_core_docker_dev', 'postgres', 'template1']


CONTAINER = 'ls-core-pg17'
VOLUME = 'ls-core-pg17-data'
ADMIN = 'ls_core_docker'
BOOTSTRAP = 'ls_core_docker_dev'
HOST = '127.0.0.1'
PORT = '55432'


class Stopped(RuntimeError):
    pass


def sanitized_environment():
    return {key: value for key, value in os.environ.items()
            if not key.upper().startswith(('PG', 'FLYWAY', 'LS_DB_', 'LS_MIGRATION_',
                                           'MAVEN_ARGS', 'MAVEN_OPTS', 'JAVA_TOOL_OPTIONS',
                                           'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'))}


def docker(*arguments):
    result = subprocess.run(['docker', *arguments], capture_output=True, text=True,
                            encoding='utf-8', errors='replace', timeout=20, check=False)
    if result.returncode:
        raise Stopped('Docker inspection failed; no SQL attempted.')
    return result.stdout.strip()


def docker_identity():
    # Never dump Config.Env: the bootstrap image may carry credentials there.
    values = docker('inspect', CONTAINER, '--format',
        '{{json .Id}}\n{{json .Config.Image}}\n{{json .Name}}\n{{json .State.Running}}\n'
        '{{json .Mounts}}\n{{json .HostConfig.PortBindings}}\n{{json .NetworkSettings.Ports}}'
    ).splitlines()
    identifier, image, name, running, mounts, bindings, ports = map(json.loads, values)
    expected_ports = {'5432/tcp': [{'HostIp': HOST, 'HostPort': PORT}]}
    if (name != '/' + CONTAINER or image != 'postgres:17' or not running
            or bindings != expected_ports or ports != expected_ports):
        raise Stopped('Container image, state or localhost-only port binding mismatch.')
    if (len(mounts) != 1 or mounts[0]['Type'] != 'volume' or mounts[0]['Name'] != VOLUME
            or mounts[0]['Destination'] != '/var/lib/postgresql/data' or not mounts[0]['RW']):
        raise Stopped('Container data volume mismatch.')
    if docker('volume', 'inspect', VOLUME, '--format', '{{.Driver}}') != 'local':
        raise Stopped('Unexpected volume driver.')
    version = docker('exec', CONTAINER, 'postgres', '--version')
    control = docker('exec', CONTAINER, 'pg_controldata', '/var/lib/postgresql/data')
    match = re.search(r'Database system identifier:\s+(\d+)', control)
    if not re.search(r'PostgreSQL\) 17\.', version) or not match or match[1] != EXPECTED_SYSTEM:
        raise Stopped('PostgreSQL 17 / physical system identifier not confirmed.')
    return {'container_id': identifier, 'image': image, 'version': version,
            'volume': VOLUME, 'host': HOST, 'port': PORT, 'system_identifier': match[1]}


def psql(sql, password, user=ADMIN, database=BOOTSTRAP, readonly=True, script=None):
    executable = Path(r'C:\Program Files\PostgreSQL\17\bin\psql.exe')
    if not executable.is_file():
        raise Stopped('Existing PostgreSQL 17 client required; no installation attempted.')
    environment = sanitized_environment()
    environment.update(PGPASSWORD=password, PGCONNECT_TIMEOUT='5', PGCLIENTENCODING='UTF8',
                       PGOPTIONS='-c default_transaction_read_only=' + ('on' if readonly else 'off') + ' -c statement_timeout=10000 -c lock_timeout=5000',
                       PGAPPNAME='ls-core-docker-preflight', LC_ALL='C')
    command = [str(executable), '-X', '-w', '-q', '-h', HOST, '-p', PORT,
                             '-U', user, '-d', database, '-v', 'ON_ERROR_STOP=1',
                             '-v', 'VERBOSITY=verbose', '-At', '-f', '-']
    if script:
        command[-1] = str(script)
    result = subprocess.run(command, input=None if script else sql, env=environment,
                            capture_output=not bool(script), text=True,
                            encoding='utf-8', errors='replace', timeout=None if script else 25, check=False)
    environment.pop('PGPASSWORD', None)
    if result.returncode:
        if script:
            raise Stopped('Interactive provisioning failed. Inspect local SQLSTATE; partial database retained, no retry.')
        # Query contains no credentials or password verifiers. Redact defensively.
        diagnostic = result.stderr.replace(password, '[REDACTED]') if password else result.stderr
        codes = re.findall(r'(?:ERROR|FATAL):\s+([0-9A-Z]{5}):', diagnostic)
        category = 'SQL_VALIDATION'
        if any(code.startswith('28') for code in codes) or 'password authentication failed' in diagnostic or 'no password supplied' in diagnostic:
            category = 'AUTHENTICATION'
        elif '42501' in codes or 'permission denied' in diagnostic:
            category = 'PERMISSION'
        elif any(code.startswith('08') for code in codes) or any(text in diagnostic.lower() for text in ('connection refused', 'could not connect', 'timeout expired', 'no route', 'server closed')):
            category = 'CONNECTION'
        raise Stopped(json.dumps({'category': category, 'sqlstate': codes or 'not exposed by libpq',
                                 'diagnostic': diagnostic.strip()}, ensure_ascii=True))
    return result.stdout.strip() if result.stdout else ''


def psql_query(sql, password):
    return json.loads(psql(sql, password))


def inspect(password, identity):
    data = psql_query("""SELECT json_build_object(
        'database',current_database(),'user',current_user,'session_user',session_user,
        'version_num',current_setting('server_version_num')::int,
        'system_identifier',(SELECT system_identifier::text FROM pg_control_system()),
        'superuser',(SELECT rolsuper FROM pg_roles WHERE rolname=current_user),
        'bootstrap_owner',(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()),
        'target_exists',EXISTS(SELECT FROM pg_database WHERE datname='ls_core_dev'),
        'existing_roles',(SELECT coalesce(json_agg(rolname),'[]'::json) FROM pg_roles WHERE rolname IN ('ls_core_migrator_dev','ls_core_app_dev')),
        'other_custom_roles',(SELECT coalesce(json_agg(rolname),'[]'::json) FROM pg_roles WHERE rolname NOT LIKE 'pg_%' AND rolname<>'ls_core_docker'),
        'other_connections',(SELECT coalesce(json_agg(json_build_object('user',usename,'database',datname)),'[]'::json) FROM pg_stat_activity WHERE pid<>pg_backend_pid() AND backend_type='client backend'),
        'prepared_transactions',(SELECT count(*) FROM pg_prepared_xacts),
        'public_connect_databases',(SELECT coalesce(json_agg(datname ORDER BY datname),'[]'::json) FROM pg_database d
          WHERE datname<>'ls_core_dev' AND datallowconn AND EXISTS(
            SELECT FROM aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a
            WHERE a.grantee=0 AND a.privilege_type='CONNECT'))
    );""", password)
    if (data['system_identifier'] != identity['system_identifier'] or data['database'] != BOOTSTRAP
            or data['user'] != ADMIN or data['session_user'] != ADMIN or not data['superuser']
            or data['bootstrap_owner'] != ADMIN or not 170000 <= data['version_num'] < 180000):
        raise Stopped('SQL identity / physical Docker cluster mismatch.')
    blockers = []
    if data['target_exists']:
        blockers.append('Target database already exists; no adoption or overwrite allowed.')
    if data['existing_roles']:
        blockers.append('Target roles already exist; compatibility review required, no adoption.')
    if data['public_connect_databases']:
        blockers.append('PUBLIC CONNECT requires the explicitly authorized Docker ACL phase.')
    return data, blockers


def assert_ready(data, identity):
    if docker_identity() != identity:
        raise Stopped('Docker identity changed since inspection.')
    if data['target_exists'] or data['existing_roles'] or data['other_custom_roles']:
        raise Stopped('Existing target database or custom roles: no adoption allowed.')
    if data['prepared_transactions'] or any(c['user'] != ADMIN for c in data['other_connections']):
        raise Stopped('Unexpected connections/prepared transactions: ACL impact review required.')
    if data['public_connect_databases'] != sorted(ACL_DATABASES):
        raise Stopped('PUBLIC CONNECT set differs from the three authorized databases; no repair.')


def migration_hashes():
    files = sorted((ROOT / 'backend/src/main/resources/db/migration').glob('V*.sql'))
    if len(files) != 9 or {p.name.split('__')[0] for p in files} != {f'V{i}' for i in range(1, 10)}:
        raise Stopped('Expected exactly original V1-V9 migrations.')
    for path in files:
        original = subprocess.run(['git', 'show', 'ef6953d3ce2c702e24b36294bf55c50e0b744ce6:' + path.relative_to(ROOT).as_posix()],
                                  cwd=ROOT, capture_output=True, check=False)
        if original.returncode or original.stdout != path.read_bytes().replace(b'\r\n', b'\n'):
            raise Stopped('Migration differs from authorized Git base: ' + path.name)
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in files}


def external(command, environment, label, timeout=240):
    result = subprocess.run(command, cwd=ROOT / 'backend', env=environment,
                            capture_output=True, text=True, encoding='utf-8',
                            errors='replace', timeout=timeout, check=False)
    if result.returncode:
        # Maven/driver output is never persisted; it may contain connection diagnostics.
        raise Stopped(label + ' failed; diagnostics withheld, no retry/repair attempted.')
    return result.stdout


def apply(password, identity, report, save):
    initial = migration_hashes()
    environment = sanitized_environment()
    java = Path(environment.get('JAVA_HOME', '')) / 'bin/java.exe'
    if not java.is_file():
        raise Stopped('Set JAVA_HOME to JDK 21 before applying changes.')
    version = subprocess.run([str(java), '-version'], capture_output=True, text=True, check=False)
    if version.returncode or not re.search(r'version "21\.', version.stdout + version.stderr):
        raise Stopped('JDK 21 is required before applying changes.')
    data, _ = inspect(password, identity)
    assert_ready(data, identity)
    report.update(status='APPLYING_AUTHORIZED_ACL', mutations=True)
    save()
    psql((ROOT / 'scripts/docker/revoke-public-connect.sql').read_text(encoding='utf-8'), password, readonly=False)
    report['acl_revocations'] = 'COMMITTED_AND_VERIFIED'
    report['blockers'] = []
    save()
    # CREATE DATABASE cannot run in a transaction. Fail on collisions; never adopt.
    if docker_identity() != identity:
        raise Stopped('Docker identity changed before database creation.')
    psql('CREATE DATABASE ls_core_dev OWNER ls_core_docker TEMPLATE template0 ENCODING \'UTF8\';', password, readonly=False)
    report['database_created'] = 'ls_core_dev'
    save()
    print('Set new migrator and application passwords in the psql prompts (twice each).', flush=True)
    psql(None, password, database='ls_core_dev', readonly=False, script=ROOT / 'scripts/docker/provision-target.sql')
    report['roles_and_schemas'] = 'COMMITTED'
    save()
    environment = sanitized_environment()
    wrapper = str(ROOT / 'backend/mvnw.cmd')
    environment.update(FLYWAY_URL='jdbc:postgresql://127.0.0.1:55432/ls_core_dev',
                       FLYWAY_USER='ls_core_migrator_dev')
    report['status'] = 'WAITING_FOR_MIGRATOR_PASSWORD'
    save()
    environment['FLYWAY_PASSWORD'] = getpass.getpass('Migrator password just configured (hidden): ')
    try:
        if docker_identity() != identity or migration_hashes() != initial:
            raise Stopped('Docker identity or migration bytes changed before Flyway.')
        external([wrapper, '-B', '-ntp', '-Pdatabase-migrations', '-Dflyway.target=9', 'flyway:migrate'], environment, 'Flyway migrate')
        external([wrapper, '-B', '-ntp', '-Pdatabase-migrations', 'flyway:validate'], environment, 'Flyway validate')
    finally:
        environment.pop('FLYWAY_PASSWORD', None)
    report['flyway'] = 'MIGRATED_AND_VALIDATED'
    save()
    verified = psql((ROOT / 'scripts/docker/verify-target.sql').read_text(encoding='utf-8'), password,
                    database='ls_core_dev', readonly=False)
    report['verification'] = json.loads(verified)
    if migration_hashes() != initial:
        raise Stopped('Migration file hash mismatch after Flyway.')
    report['migration_sha256'] = initial
    save()
    environment = sanitized_environment()
    with tempfile.TemporaryDirectory(prefix='ls-core-docker-jdbc-') as folder:
        classpath_file = Path(folder) / 'classpath.txt'
        external([wrapper, '-B', '-ntp', '-DskipTests', 'compile', 'dependency:build-classpath',
                  '-Dmdep.outputFile=' + str(classpath_file)], environment, 'JDBC verification build')
        classpath = str(ROOT / 'backend/target/classes') + os.pathsep + classpath_file.read_text(encoding='utf-8').strip()
        report['status'] = 'WAITING_FOR_APPLICATION_PASSWORD'
        save()
        environment['LS_DB_PASSWORD'] = getpass.getpass('Application password just configured (hidden): ')
        try:
            psql((ROOT / 'scripts/docker/verify-runtime.sql').read_text(encoding='utf-8'), environment['LS_DB_PASSWORD'],
                 user='ls_core_app_dev', database='ls_core_dev')
            output = external([str(java), '--class-path', classpath, str(ROOT / 'scripts/docker/VerifyDockerJdbc.java')],
                              environment, 'Backend JDBC SELECT 1')
            if 'DOCKER_BACKEND_JDBC_SELECT_1_OK' not in output:
                raise Stopped('Backend JDBC confirmation missing.')
        finally:
            environment.pop('LS_DB_PASSWORD', None)
    report.update(jdbc='BACKEND_POOL_SELECT_1_OK_NO_HTTP', status='COMPLETE')
    save()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, help='Optional sanitized JSON report outside the repository')
    parser.add_argument('--apply', action='store_true', help='Apply LFD-25 authorized ACL/provisioning/migrations after preflight')
    args = parser.parse_args()
    if not sys.stdin.isatty():
        raise Stopped('Use a local interactive terminal; never pipe passwords or send them through chat.')
    report = {'status': 'STARTING', 'mutations': False}

    def save():
        if args.report:
            args.report.write_text(json.dumps(report, indent=2), encoding='utf-8')

    try:
        report['docker'] = docker_identity()
        report['status'] = 'WAITING_FOR_LOCAL_PASSWORD'
        save()
        password = getpass.getpass('Docker ls_core_docker password (hidden, local only): ')
        try:
            data, blockers = inspect(password, report['docker'])
            report.update(sql=data, blockers=blockers, status='BLOCKED' if blockers else 'PREFLIGHT_PASSED')
            save()
            if args.apply:
                apply(password, report['docker'], report, save)
        finally:
            password = None
    except (Stopped, subprocess.TimeoutExpired, OSError, ValueError) as failure:
        report.update(status='ERROR', error=str(failure))
    except KeyboardInterrupt:
        report.update(status='CANCELLED', error='Local input cancelled; no changes made.')
    finally:
        save()
    print(json.dumps(report, indent=2))
    return 0 if report['status'] in ('PREFLIGHT_PASSED', 'COMPLETE') else 1


if __name__ == '__main__':
    raise SystemExit(main())

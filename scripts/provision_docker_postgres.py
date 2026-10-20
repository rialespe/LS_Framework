"""Fail-closed Docker PG17 preflight. No Windows-server destinations or repairs."""

import argparse
import getpass
import json
import os
from pathlib import Path
import re
import subprocess
import sys


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
    if not re.search(r'PostgreSQL\) 17\.', version) or not match:
        raise Stopped('PostgreSQL 17 / physical system identifier not confirmed.')
    return {'container_id': identifier, 'image': image, 'version': version,
            'volume': VOLUME, 'host': HOST, 'port': PORT, 'system_identifier': match[1]}


def psql_query(sql, password):
    executable = Path(r'C:\Program Files\PostgreSQL\17\bin\psql.exe')
    if not executable.is_file():
        raise Stopped('Existing PostgreSQL 17 client required; no installation attempted.')
    environment = sanitized_environment()
    environment.update(PGPASSWORD=password, PGCONNECT_TIMEOUT='5', PGCLIENTENCODING='UTF8',
                       PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=10000',
                       PGAPPNAME='ls-core-docker-preflight', LC_ALL='C')
    result = subprocess.run([str(executable), '-X', '-w', '-h', HOST, '-p', PORT,
                             '-U', ADMIN, '-d', BOOTSTRAP, '-v', 'ON_ERROR_STOP=1',
                             '-v', 'VERBOSITY=verbose', '-At', '-f', '-'],
                            input=sql, env=environment, capture_output=True, text=True,
                            encoding='utf-8', errors='replace', timeout=25, check=False)
    environment.pop('PGPASSWORD', None)
    if result.returncode:
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
    return json.loads(result.stdout)


def inspect(password, identity):
    data = psql_query("""SELECT json_build_object(
        'database',current_database(),'user',current_user,'session_user',session_user,
        'version_num',current_setting('server_version_num')::int,
        'system_identifier',(SELECT system_identifier::text FROM pg_control_system()),
        'superuser',(SELECT rolsuper FROM pg_roles WHERE rolname=current_user),
        'bootstrap_owner',(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()),
        'target_exists',EXISTS(SELECT FROM pg_database WHERE datname='ls_core_dev'),
        'existing_roles',(SELECT coalesce(json_agg(rolname),'[]'::json) FROM pg_roles WHERE rolname IN ('ls_core_migrator_dev','ls_core_app_dev')),
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
        blockers.append('Other databases allow PUBLIC CONNECT; restricted roles would inherit it. No external ACL changes authorized.')
    return data, blockers


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, help='Optional sanitized JSON report outside the repository')
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
        finally:
            password = None
        report.update(sql=data, blockers=blockers, status='BLOCKED' if blockers else 'PREFLIGHT_PASSED')
    except (Stopped, subprocess.TimeoutExpired, OSError, ValueError) as failure:
        report.update(status='ERROR', error=str(failure))
    except KeyboardInterrupt:
        report.update(status='CANCELLED', error='Local input cancelled; no changes made.')
    finally:
        save()
    print(json.dumps(report, indent=2))
    return 0 if report['status'] == 'PREFLIGHT_PASSED' else 1


if __name__ == '__main__':
    raise SystemExit(main())

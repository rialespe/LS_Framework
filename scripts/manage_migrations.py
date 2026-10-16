"""Explicit local administrative entry point. Defaults to inspection, never app startup."""

import argparse
import getpass
import os
from pathlib import Path
import shutil
import subprocess


ROOT = Path(__file__).resolve().parents[1]
MIGRATOR = 'ls_core_migrator_dev'


def sanitized_environment():
    # No inherited connection strings, Maven overrides, or credentials/config for other databases.
    return {key: value for key, value in os.environ.items()
            if not key.upper().startswith(('PG', 'FLYWAY', 'LS_DB_', 'LS_MIGRATION_',
                                           'MAVEN_ARGS', 'MAVEN_OPTS', 'JAVA_TOOL_OPTIONS',
                                           'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'))}


def psql_path():
    existing = Path(r'C:\Program Files\PostgreSQL\17\bin\psql.exe')
    found = str(existing) if os.name == 'nt' and existing.is_file() else shutil.which('psql')
    if not found:
        raise RuntimeError('Existing PostgreSQL 17 psql required; nothing will be installed')
    return found


def run_psql(script, password, apply=False):
    env = sanitized_environment()
    env['PGCONNECT_TIMEOUT'] = '5'
    if password is not None:
        env['PGPASSWORD'] = password
    command = [psql_path(), '-X', '-w', '-h', '127.0.0.1', '-p', '5432',
               '-U', 'postgres', '-d', 'ls_core_dev', '-v', 'ON_ERROR_STOP=1',
               '-v', 'apply=' + ('true' if apply else 'false')]
    command += ['-f', str(script)]
    # Keep diagnostics local. SQL never contains a secret or a password verifier.
    result = subprocess.run(command, env=env, check=False)
    if result.returncode:
        raise RuntimeError('Administrative operation stopped; inspect state, no automatic repair')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['inspect', 'provision', 'info', 'validate', 'migrate'],
                        nargs='?', default='inspect')
    args = parser.parse_args()
    if not os.isatty(0):
        raise RuntimeError('Use a local interactive terminal; credentials/confirmations must not be piped')
    if args.operation in ('inspect', 'provision'):
        password = getpass.getpass('Existing local administrator password (never send through chat): ')
        run_psql(ROOT / 'scripts/migrations/inspect.sql', password)
        if args.operation == 'inspect':
            return
        print('Only after separate owner authorization: new migrator and eight schemas in local ls_core_dev.')
        if input('Type PREPARE LFD-25 ls_core_dev to apply: ') != 'PREPARE LFD-25 ls_core_dev':
            raise RuntimeError('Cancelled without applying provisioning')
        run_psql(ROOT / 'scripts/migrations/provision.sql', password, apply=True)
        return
    if args.operation == 'migrate':
        if input('After owner authorization, type MIGRATE LFD-25 ls_core_dev: ') != 'MIGRATE LFD-25 ls_core_dev':
            raise RuntimeError('Cancelled without running migrations')
    env = sanitized_environment()
    env['FLYWAY_URL'] = 'jdbc:postgresql://127.0.0.1:5432/ls_core_dev?connectTimeout=5'
    env['FLYWAY_USER'] = MIGRATOR
    env['FLYWAY_PASSWORD'] = getpass.getpass('Local migrator password (external, process only): ')
    command = [str(ROOT / 'backend/mvnw.cmd')] if os.name == 'nt' else ['sh', str(ROOT / 'backend/mvnw')]
    command += ['-B', '-ntp', '-Pdatabase-migrations', 'flyway:' + args.operation]
    # Captured Maven diagnostics stay out of the terminal; report only a bounded success/failure result.
    result = subprocess.run(command, cwd=ROOT / 'backend', env=env, capture_output=True,
                            text=True, encoding='utf-8', errors='replace', check=False)
    if result.returncode:
        raise RuntimeError('Flyway failed; no clean/repair/baseline attempted. Inspect history and versioned SQL.')
    print('Flyway ' + args.operation + ': OK. Verify operations.flyway_schema_history locally.')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, KeyboardInterrupt) as failure:
        raise SystemExit(str(failure)) from None

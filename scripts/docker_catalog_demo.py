"""Explicit Docker-only C1/C2 provisioning and local catalog API launcher."""
import argparse
import getpass
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
from provision_docker_postgres import ROOT, EXPECTED_SYSTEM, docker_identity, psql, sanitized_environment, Stopped


from docker_catalog_c2 import prepare_c2_only


def serve(password, report, save):
    import socket
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 8080))
    identity = docker_identity()
    report['c2_reader_verification'] = json.loads(psql(
        (ROOT / 'scripts/docker/verify-catalog-reader.sql').read_text(encoding='utf-8'),
        password, user='ls_core_catalog_service_dev', database='ls_core_dev'))
    report['status'] = 'C2_READER_VERIFIED'
    save()
    env = sanitized_environment()
    env.update(LS_CATALOG_ENABLED='true', LS_CATALOG_API_ENABLED='true',
               LS_CATALOG_DB_HOST='127.0.0.1', LS_CATALOG_DB_PORT='55432',
               LS_CATALOG_DB_NAME='ls_core_dev', LS_CATALOG_DB_PASSWORD=password)
    java = Path(env.get('JAVA_HOME', '')) / 'bin/java.exe'
    jar = ROOT / 'backend/target/ls-core-0.0.1-SNAPSHOT.jar'
    if not java.is_file() or not jar.is_file():
        raise Stopped('JDK 21 and current built backend jar required.')
    from catalog_http_checks import start, stop, negative_checks, verify_http, react_e2e
    report['negative_http'] = negative_checks(java, jar, env)
    save()
    process = start(java, jar, env)
    try:
        if docker_identity() != identity:
            raise Stopped('Docker identity changed before E2E.')
        report.update(verify_http(report['c2_reader_verification']))
        report['react_e2e'] = react_e2e()
        report.update(status='E2E_OK_API_RUNNING', identity=identity, api_pid=process.pid)
        save()
        print('SQL, HTTP and live React DOM E2E passed. API on 127.0.0.1:8080; Ctrl+C stops it.', flush=True)
        process.wait()
    finally:
        stop(process)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['prepare-c2-only', 'serve'])
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    if not sys.stdin.isatty():
        raise Stopped('Use a visible local terminal; never pipe credentials.')
    report = {'status': 'WAITING_FOR_LOCAL_PASSWORD', 'operation': args.operation}
    def save():
        args.report.write_text(json.dumps(report, indent=2), encoding='utf-8')
    save()
    try:
        docker_identity()
        password = getpass.getpass('Docker administrator password: ' if args.operation == 'prepare-c2-only' else 'Catalog SERVICE password (C2): ')
        try:
            if args.operation == 'prepare-c2-only':
                report.update(prepare_c2_only(password), status='PREPARED')
            else:
                serve(password, report, save)
        finally:
            password = None
    except (Exception, KeyboardInterrupt) as failure:
        report.update(status='STOPPED', error=str(failure))
    finally:
        save()
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()

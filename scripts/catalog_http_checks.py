"""Local HTTP checks against the real built application; no SQL writes or mock server."""
import json
import os
import secrets
import subprocess
import threading
import time
import urllib.error
import urllib.request

from provision_docker_postgres import ROOT, Stopped


def request(path, *, proxy=False, origin=False):
    url = ('http://127.0.0.1:5173' if proxy else 'http://127.0.0.1:8080') + path
    headers = {'Origin': 'https://external.example'} if origin else {}
    try:
        response = urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=15)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        body = response.read().decode('utf-8')
        return response.code, json.loads(body) if body else None, response.headers


def start(java, jar, environment):
    process = subprocess.Popen([str(java), '-jar', str(jar), '--spring.profiles.active=catalog-docker',
        '--server.address=127.0.0.1', '--server.port=8080'], env=environment,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    def drain():
        for _ in iter(process.stdout.readline, b''):
            pass
    threading.Thread(target=drain, daemon=True).start()
    try:
        for _ in range(60):
            if process.poll() is not None:
                raise Stopped('API startup failed; driver diagnostics withheld.')
            try:
                if request('/actuator/health')[0] == 200:
                    return process
            except OSError:
                time.sleep(1)
        raise Stopped('API startup timed out.')
    except BaseException:
        stop(process)
        raise


def stop(process):
    if process.poll() is None:
        process.terminate()
        process.wait(timeout=15)


def negative_checks(java, jar, environment):
    disabled = dict(environment)
    disabled.pop('LS_CATALOG_API_ENABLED', None)
    disabled.pop('LS_CATALOG_ENABLED', None)
    process = start(java, jar, disabled)
    try:
        if request('/api/global-catalog/applications')[0] != 404:
            raise Stopped('Catalog API not disabled by default.')
    finally:
        stop(process)
    unavailable = dict(environment)
    # An invalid process-only authentication input. Never alters the stored C2 password.
    unavailable['LS_CATALOG_DB_PASSWORD'] = secrets.token_urlsafe(48)
    process = start(java, jar, unavailable)
    try:
        code, body, _ = request('/api/global-catalog/applications')
        if code != 503 or body != {'error': 'Global catalog unavailable'}:
            raise Stopped('Unavailable catalog did not return controlled 503.')
    finally:
        stop(process)
    return {'default_disabled_http': 404, 'real_auth_failure_http': 503}


def verify_http(sql):
    path = '/api/global-catalog/applications'
    code, applications, headers = request(path + '?limit=100&offset=0', proxy=True, origin=True)
    if code != 200 or applications != sql['applications'] or headers.get('Access-Control-Allow-Origin'):
        raise Stopped('API/SQL application mismatch or open CORS.')
    if not applications:
        raise Stopped('Expected V8 application.')
    modules_path = path + '/' + applications[0]['id'] + '/modules'
    code, modules, _ = request(modules_path + '?limit=100&offset=0', proxy=True)
    expected = [row for row in sql['modules'] if row['application_id'] == applications[0]['id']]
    expected = [{('applicationId' if key == 'application_id' else key): value for key, value in row.items()} for row in expected]
    if code != 200 or modules != expected:
        raise Stopped('API/SQL module mismatch.')
    for endpoint, rows in [(path, applications), (modules_path, modules)]:
        for offset in (0, 1):
            if request(endpoint + f'?limit=1&offset={offset}')[1] != rows[offset:offset + 1]:
                raise Stopped('Real API pagination mismatch.')
        if request(endpoint + '?limit=100&offset=10000')[1] != []:
            raise Stopped('Real API empty page mismatch.')
    unknown = path + '/00000000-0000-0000-0000-000000000000/modules'
    if request(unknown)[1] != []:
        raise Stopped('Module application filter mismatch.')
    for query in ('limit=0', 'limit=101', 'offset=-1', 'offset=10001', 'limit=bad'):
        code, body, _ = request(path + '?' + query)
        if code != 400 or body != {'error': 'Invalid catalog request'}:
            raise Stopped('Invalid pagination not controlled.')
    if request(path + '/invalid/modules')[0] != 400 or request('/api/global-catalog/users')[0] != 404:
        raise Stopped('Invalid ID / enterprise endpoint isolation failed.')
    return {'applications': applications, 'modules': modules, 'http': '200_SQL_MATCH',
            'pagination': 'PASS', 'empty_and_filter': 'PASS', 'invalid_http': 400, 'cors_open': False}


def react_e2e():
    environment = {key: value for key, value in os.environ.items() if not key.startswith(('LS_CATALOG_DB_', 'LS_DB_', 'PG'))}
    environment.update(LS_CATALOG_E2E='true', VITE_CATALOG_ENABLED='true')
    result = subprocess.run(['npm.cmd', 'run', 'test', '--', 'src/app/ConnectedCatalog.real.test.tsx'],
        cwd=ROOT / 'frontend', env=environment, capture_output=True, text=True,
        encoding='utf-8', errors='replace', timeout=90, check=False)
    if result.returncode:
        raise Stopped('Live React DOM E2E failed; no fixture fallback or retry.')
    return 'PASS_REAL_HTTP_DOM_NO_FIXTURES'

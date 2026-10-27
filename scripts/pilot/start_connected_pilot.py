"""Windows loopback pilot: owned PG17, V1-V18, real F04 account, trusted TLS and Vite.

No existing server URL, database password or data directory can be supplied.
Interactive by default. --smoke-only checks the new HTTPS proxy path and then removes everything.
"""
import argparse
import getpass
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/tests'))
from test_postgres_migrations import BACKEND, SOURCE, NO_WINDOW, check, disposable_cluster, environment, prepare_fixture

FRONTEND = ROOT / 'frontend'
URL = 'https://127.0.0.1:5174'


def prepare_database(cluster):
    prepare_fixture(cluster)
    for database in ('postgres', 'template1'):
        check(cluster.psql(sql=f'REVOKE CONNECT,TEMPORARY ON DATABASE {database} FROM PUBLIC').returncode == 0,
              'owned fixture database ACL')
    password = cluster.secrets['ls_core_migrator_dev']
    check(cluster.psql(script=ROOT / 'scripts/migrations/provision.sql', apply=True,
                       reply=(password + '\n') * 2).returncode == 0, 'owned restricted migrator')
    check(cluster.flyway('migrate', location=SOURCE, target='9').returncode == 0, 'owned V1-V9 baseline')
    for roles, script, target in [
            (['ls_core_credential_verifier_dev'], 'prepare_credential_verifier.sql', '10'),
            (['ls_core_identity_registrar_dev'], 'prepare_identity_registrar.sql', '11'),
            (['ls_core_password_issuer_dev', 'ls_core_password_changer_dev'], 'prepare_password_lifecycle.sql', '12'),
            (['ls_core_password_authenticator_dev', 'ls_core_password_recovery_dev'], 'prepare_password_recovery.sql', '13'),
            (['ls_core_authorization_reader_dev'], 'prepare_authorization_reader.sql', '14'),
            (['ls_core_branch_operation_dev', 'ls_core_operation_emitter_dev'], 'prepare_branch_operation.sql', '15'),
            (['ls_core_session_manager_dev'], 'prepare_session_manager.sql', '18')]:
        reply = ''
        for role in roles:
            cluster.secrets[role] = secrets.token_urlsafe(32)
            reply += (cluster.secrets[role] + '\n') * 2
        check(cluster.psql(script=ROOT / 'scripts/migrations' / script, apply=True, reply=reply).returncode == 0,
              'owned role provisioning before V' + target)
        check(cluster.flyway('migrate', location=SOURCE, target=target).returncode == 0, 'owned migration through V' + target)
    check(cluster.flyway('validate', location=SOURCE, target='18').returncode == 0, 'Flyway validates V1-V18')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--smoke-only', action='store_true')
    options = parser.parse_args()
    if os.name != 'nt':
        raise RuntimeError('This local trust-store launcher supports Windows only; see the pilot guide for manual setup.')
    java_home = Path(os.environ.get('JAVA_HOME', ''))
    java = java_home / 'bin/java.exe'
    if not java.is_file() or not (FRONTEND / 'node_modules/vite/bin/vite.js').is_file():
        raise RuntimeError('Set JAVA_HOME to JDK 21 and run npm ci in frontend first.')
    # Reserve/check the public frontend port before any initialization; bind only loopback.
    with socket.socket() as port_check:
        port_check.bind(('127.0.0.1', 5174))
    password = secrets.token_urlsafe(32) + 'A7!' if options.smoke_only else getpass.getpass('Nueva contraseña para la cuenta desechable (letra, número, símbolo; 8..1024): ')
    if not options.smoke_only and password != getpass.getpass('Confirmar contraseña: '):
        raise RuntimeError('Passwords do not match')
    if not 8 <= len(password) <= 1024 or not any(c.isalpha() for c in password) or not any(c.isdigit() for c in password) or not any(not c.isalnum() and not c.isspace() for c in password):
        raise RuntimeError('Password must have 8..1024 characters, a letter, a digit and a symbol')
    with disposable_cluster() as cluster:
        print('Temporary pilot directory: ' + str(cluster.root), flush=True)
        cluster.secrets['pilot_account'] = password
        cluster.secrets['pilot_tls'] = secrets.token_urlsafe(32)
        children = []
        logs = []
        tls_script = ROOT / 'scripts/pilot/New-PilotCertificate.ps1'
        cert_command = ['powershell.exe', '-NoProfile', '-File', tls_script, '-Directory', cluster.root]
        env = {key: value for key, value in environment().items()
               if not key.upper().startswith(('LS_', 'VITE_', 'NODE_', 'SPRING_', 'SERVER_'))}
        if os.environ.get('NODE_TLS_REJECT_UNAUTHORIZED') == '0':
            raise RuntimeError('Remove NODE_TLS_REJECT_UNAUTHORIZED=0 before starting the pilot')
        env['LS_PILOT_TLS_PASSWORD'] = cluster.secrets['pilot_tls']
        username = 'pilot-' + secrets.token_hex(4)
        try:
            check(cluster.run(cert_command, env=env).returncode == 0, 'temporary localhost certificate and current-user CA trust')
            prepare_database(cluster)
            command = [BACKEND / 'mvnw.cmd', '-B', '-ntp', '-f', BACKEND / 'pom.xml', '-DskipTests', 'package',
                       'dependency:build-classpath', '-Dmdep.outputFile=' + str(cluster.root / 'classpath.txt')]
            check(cluster.run(command, timeout=180).returncode == 0, 'package backend and resolve tooling classpath without running tests')
            env.update(LS_PILOT_ROOT=str(cluster.root), LS_PILOT_PORT=str(cluster.port), LS_PILOT_USERNAME=username)
            for role, secret in cluster.secrets.items():
                env['LS_PILOT_' + role.upper()] = secret
            classpath = str(BACKEND / 'target/classes') + os.pathsep + (cluster.root / 'classpath.txt').read_text(encoding='utf-8').strip()
            check(cluster.run([java, '--class-path', classpath, ROOT / 'scripts/pilot/PilotAccount.java',
                               BACKEND / 'src/test/resources/session-context-fixture.sql'], input_text=password, env=env).returncode == 0,
                  'real F04 account and existing disposable V17 fixture')
            # Provisioning credentials are not inherited by either application process.
            env = {key: value for key, value in env.items() if not key.startswith('LS_PILOT_')}
            with socket.socket() as listener:
                listener.bind(('127.0.0.1', 0))
                backend_port = listener.getsockname()[1]
            env.update(LS_IDENTITY_CREDENTIALS_ENABLED='true', LS_LOGIN_ENABLED='true', LS_LOGIN_CONTEXTS_ENABLED='true', LS_LOGIN_MULTIDEVICE_ENABLED='true',
                       LS_LOGIN_FISCAL_APPLICATION_ID='35000000-0000-0000-0000-000000000001',
                       LS_LOGIN_POS_APPLICATION_ID='35000000-0000-0000-0000-000000000002',
                       LS_LOGIN_SESSION_SECONDS='3600', LS_ACCESS_TENANT_AUTHORIZATION_ENABLED='false',
                       LS_ACCESS_BRANCH_OPERATION_ENABLED='false', LS_IDENTITY_RECOVERY_ENABLED='false',
                       SERVER_ADDRESS='127.0.0.1', SERVER_PORT=str(backend_port), SERVER_SSL_ENABLED='true',
                       SERVER_SSL_KEY_STORE=(cluster.root / 'pilot.p12').as_uri(), SERVER_SSL_KEY_STORE_TYPE='PKCS12',
                       SERVER_SSL_KEY_STORE_PASSWORD=cluster.secrets['pilot_tls'])
            for prefix, role in [('LS_AUTHENTICATION_DB_', 'ls_core_password_authenticator_dev'),
                                 ('LS_LOGIN_DB_', 'ls_core_session_manager_dev')]:
                env.update({prefix + 'HOST': '127.0.0.1', prefix + 'PORT': str(cluster.port), prefix + 'NAME': 'ls_core_dev',
                            prefix + 'PASSWORD': cluster.secrets[role]})
            backend_env = dict(env)
            frontend_env = {key: value for key, value in env.items() if not key.startswith(('LS_', 'SERVER_'))}
            frontend_env.update(VITE_CONNECTED_PILOT='true', VITE_CATALOG_ENABLED='false',
                                LS_PILOT_TLS_PFX=str(cluster.root / 'pilot.p12'), LS_PILOT_TLS_CA=str(cluster.root / 'pilot-ca.pem'),
                                LS_PILOT_TLS_PASSWORD=cluster.secrets['pilot_tls'], LS_PILOT_BACKEND_URL=f'https://127.0.0.1:{backend_port}')
            for args, cwd, process_env, name in [
                    ([java, '-jar', BACKEND / 'target/ls-core-0.0.1-SNAPSHOT.jar'], ROOT, backend_env, 'backend'),
                    (['node.exe', FRONTEND / 'node_modules/vite/bin/vite.js'], FRONTEND, frontend_env, 'frontend')]:
                log = (cluster.root / (name + '.log')).open('w', encoding='utf-8')
                logs.append(log)
                children.append(subprocess.Popen([str(arg) for arg in args], cwd=cwd, env=process_env, stdin=subprocess.DEVNULL,
                                                 stdout=log, stderr=subprocess.STDOUT, creationflags=NO_WINDOW))
            ssl_context = ssl.create_default_context(cafile=str(cluster.root / 'pilot-ca.pem'))
            opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ssl_context),
                                                urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
            deadline = time.monotonic() + 60
            while True:
                if any(child.poll() is not None for child in children):
                    raise RuntimeError('A local process stopped before readiness; raw diagnostics withheld')
                try:
                    with opener.open(URL + '/api/auth/fiscal/csrf', timeout=2) as response:
                        csrf = json.load(response)['csrfToken']
                    break
                except (OSError, urllib.error.URLError):
                    if time.monotonic() > deadline:
                        raise RuntimeError('HTTPS pilot did not become ready') from None
                    time.sleep(0.25)
            with opener.open(URL, timeout=5) as response:
                check('id="root"' in response.read().decode('utf-8'), 'Vite serves the real React entry over trusted HTTPS')
            with opener.open(URL + '/src/app/ConnectedFiscal.tsx', timeout=5) as response:
                check(response.status == 200, 'connected React module transforms successfully')
            print('PILOT READY: ' + URL + ' | Usuario: ' + username, flush=True)
            print('Cuenta, empresas, sucursales y permisos existen solo en este PG17 desechable. Ctrl+C detiene y elimina el entorno.', flush=True)
            if options.smoke_only:
                # New proxy/provisioning smoke only; never invokes historical JUnit integration methods.
                request = urllib.request.Request(URL + '/api/auth/fiscal/login', method='POST',
                    data=json.dumps({'username': username, 'password': password}).encode(),
                    headers={'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf})
                check(opener.open(request, timeout=10).status == 204, 'real login through certificate-validating HTTPS Vite proxy')
                with opener.open(URL + '/api/auth/fiscal/csrf', timeout=5) as response:
                    csrf = json.load(response)['csrfToken']
                with opener.open(URL + '/api/auth/fiscal/tenants', timeout=5) as response:
                    tenant = json.load(response)[0]['id']
                with opener.open(URL + '/api/auth/fiscal/tenants/' + tenant + '/branches', timeout=5) as response:
                    branch = json.load(response)[0]['id']
                request = urllib.request.Request(URL + '/api/auth/fiscal/context', method='POST',
                    data=json.dumps({'tenantId': tenant, 'branchId': branch}).encode(),
                    headers={'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf})
                check(opener.open(request, timeout=10).status == 200, 'real context selected through same-origin HTTPS proxy')
                for endpoint in ('permissions', 'navigation'):
                    with opener.open(URL + '/api/auth/fiscal/' + endpoint, timeout=5) as response:
                        check(isinstance(json.load(response), list), 'real ' + endpoint + ' response through proxy')
                request = urllib.request.Request(URL + '/api/auth/fiscal/logout', method='POST', headers={'X-CSRF-TOKEN': csrf})
                check(opener.open(request, timeout=10).status == 204, 'real logout through proxy')
            else:
                while all(child.poll() is None for child in children):
                    time.sleep(0.5)
                raise RuntimeError('A pilot process stopped; restarting requires a fresh disposable environment')
        except KeyboardInterrupt:
            print('Stopping disposable pilot.', flush=True)
        finally:
            for child in reversed(children):
                if child.poll() is None:
                    child.terminate()
                    try:
                        child.wait(timeout=8)
                    except subprocess.TimeoutExpired:
                        child.kill()
                        child.wait(timeout=5)
            for log in logs:
                log.close()
            check(cluster.run(cert_command + ['-RemoveTrust'], env=environment()).returncode == 0, 'owned CA trust removed')
    print('Pilot stopped; owned PG17, certificate files and data removed.', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, AssertionError) as failure:
        print(str(failure), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print('Pilot unavailable. Check prerequisites and the guide; credential diagnostics withheld.', file=sys.stderr)
        sys.exit(1)

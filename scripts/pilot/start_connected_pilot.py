"""Windows loopback pilot: owned PG17, V1-V26, real F04 account, trusted TLS and Vite.

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
            (['ls_core_session_manager_dev'], 'prepare_session_manager.sql', '18'),
            (['ls_core_platform_bootstrap_dev', 'ls_core_platform_reader_dev'], 'prepare_platform_channels.sql', '19'),
            ([], 'prepare_platform_mutator.sql', '20'),
            ([], 'prepare_platform_audit.sql', '21'),
            (['ls_core_tenant_registrar_dev', 'ls_core_tenant_registration_log_dev'], 'prepare_tenant_registration.sql', '22'),
            (['ls_core_tenant_lifecycle_dev', 'ls_core_tenant_lifecycle_log_dev'], 'prepare_tenant_lifecycle.sql', '23'),
            (['ls_core_tenant_admin_dev', 'ls_core_tenant_admin_log_dev'], 'prepare_tenant_administrators.sql', '24'),
            (['ls_core_branch_admin_dev', 'ls_core_branch_admin_log_dev'], 'prepare_branch_administration.sql', '25'),
            (['ls_core_branch_config_dev', 'ls_core_branch_config_log_dev'], 'prepare_branch_configuration.sql', '26')]:
        reply = ''
        for role in roles:
            cluster.secrets[role] = secrets.token_urlsafe(32)
            reply += (cluster.secrets[role] + '\n') * 2
        check(cluster.psql(script=ROOT / 'scripts/migrations' / script, apply=True, reply=reply).returncode == 0,
              'owned role provisioning before V' + target)
        check(cluster.flyway('migrate', location=SOURCE, target=target).returncode == 0, 'owned migration through V' + target)
    check(cluster.flyway('validate', location=SOURCE, target='26').returncode == 0, 'Flyway validates V1-V26')


def core_smoke(opener, username, initial_username, second_username, password, cluster):
    import uuid
    def get(action):
        with opener.open(URL + '/api/auth/core/' + action, timeout=10) as response:
            return json.load(response)
    def post(action, body):
        csrf = get('csrf')['csrfToken']
        request = urllib.request.Request(URL + '/api/auth/core/' + action, method='POST', data=json.dumps(body).encode(),
                                         headers={'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf})
        return opener.open(request, timeout=15)
    with opener.open(URL + '/core', timeout=5) as response:
        check('id="root"' in response.read().decode(), 'Core React entry over same-origin HTTPS')
    with opener.open(URL + '/src/app/ConnectedCore.tsx', timeout=5) as response:
        check(response.status == 200, 'Core React form transforms through Vite')
    with post('login', {'username': username, 'password': password}) as response:
        check(response.status == 204, 'real independent Core HTTPS login')
    check(get('authority')['canRegister'], 'real global registration authority')
    with post('initial-administrator', {'username': initial_username}) as response:
        initial = json.load(response)['id']
    key = str(uuid.uuid4())
    with post('registrations', {'requestId': key, 'name': 'Empresa del recorrido local', 'administratorId': initial, 'password': password}) as response:
        result = json.load(response)
        check(result['outcome'] == 'CONFIRMED' and result['tenant']['status'] == 'PENDING', 'real atomic tenant registration PENDING through HTTPS proxy')
    check(get('registrations/' + key)['tenant']['id'] == result['tenant']['id'], 'persisted tenant reconciled from PostgreSQL')
    tenant_id = result['tenant']['id']
    check(get('tenant-authority')['canActivate'], 'explicit lifecycle authority')
    for action, state in [('activate', 'ACTIVE'), ('suspend', 'SUSPENDED'), ('reactivate', 'ACTIVE')]:
        operation = str(uuid.uuid4())
        with post('tenants/' + tenant_id + '/' + action, {'requestId': operation, 'password': password}) as response:
            check(json.load(response)['outcome'] == 'CONFIRMED', 'real HTTPS lifecycle ' + action)
        check(get('tenants/' + tenant_id)['tenant']['status'] == state, 'persisted lifecycle state ' + state)
        check(get('tenant-operations/' + operation)['outcome'] == 'CONFIRMED', 'persisted lifecycle evidence reconciliation')

    admin_path = 'tenants/' + tenant_id + '/administrators'
    initial_designation = next(a['id'] for a in get(admin_path)['administrators'] if a['username'] == initial_username)
    with post(admin_path + '/candidate', {'username': second_username}) as response:
        check(json.load(response)['ready'], 'second administrator has real definitive F04 credentials')
    for action, candidate in [('/designate', second_username), ('/' + initial_designation + '/revoke', None)]:
        operation = str(uuid.uuid4())
        with post(admin_path + action, {'requestId': operation, 'username': candidate, 'password': password}) as response:
            check(json.load(response)['outcome'] == 'CONFIRMED', 'real administrator command ' + action.split('/')[-1])
        check(get('administrator-operations/' + operation)['outcome'] == 'CONFIRMED', 'administrator receipt persisted')
    listing = get(admin_path)
    check(listing['validCount'] == 1 and len(listing['history']) == 2, 'one valid administrator and persisted history')
    last = next(a['id'] for a in listing['administrators'] if a['valid'])
    try:
        post(admin_path + '/' + last + '/revoke', {'requestId': str(uuid.uuid4()), 'password': password})
        raise RuntimeError('Last valid administrator must be protected')
    except urllib.error.HTTPError as rejected:
        check(rejected.code == 409, 'last administrator rejected by PostgreSQL through HTTPS')
        rejected.close()
    check(cluster.query("SELECT count(*) FROM tenant.valid_administrators('" + tenant_id + "')") == '1', 'PG17 confirms one valid administrator after rejected revocation')
    branch_path = 'branch-administration/tenants/' + tenant_id + '/branches'
    branches = []
    for code in ('CASA MATRIZ', 'SUCURSAL 02'):
        operation = str(uuid.uuid4())
        with post(branch_path, {'requestId': operation, 'code': code, 'name': code, 'password': password}) as response:
            result = json.load(response)
            check(response.status == 201 and result['outcome'] == 'CONFIRMED', 'real branch creation ' + code)
            branches.append(result['change']['branch']['id'])
        check(get('branch-administration/operations/' + operation)['outcome'] == 'CONFIRMED', 'branch creation receipt persisted')
    for action, state in [('suspend', 'SUSPENDED'), ('reactivate', 'ACTIVE'), ('close', 'CLOSED')]:
        detail = get(branch_path + '/' + branches[1])
        operation = str(uuid.uuid4())
        with post(branch_path + '/' + branches[1] + '/' + action, {'requestId': operation, 'expectedUpdatedAt': detail['branch']['updatedAt'], 'password': password}) as response:
            check(json.load(response)['outcome'] == 'CONFIRMED', 'real branch transition ' + action)
        check(get(branch_path + '/' + branches[1])['branch']['status'] == state, 'persisted branch state ' + state)
    detail = get(branch_path + '/' + branches[1])
    check(len(detail['history']) == 4, 'persisted creation and transition history')
    check(get(branch_path + '/' + branches[0])['branch']['status'] == 'ACTIVE', 'matrix preserved while second branch closes')
    for selected in branches:
        check(cluster.query("SELECT tenant_id FROM tenant.branches WHERE id='" + selected + "'") == tenant_id, 'PG17 confirms exact tenant ownership for branch')
    modules_path = branch_path + '/' + branches[0] + '/module-configurations'
    check(get(modules_path) == [], 'real empty specialized configuration registry before fixture')
    install_configuration_fixture(cluster, tenant_id)
    module = get(modules_path)[0]
    check(module['readiness'] == 'ABSENT' and 'FIXTURE' in module['applicationName'], 'explicit disposable fixture distinguished from product catalog')
    scope = module['scope']
    config_path = modules_path + '/' + scope['applicationId'] + '/' + scope['moduleId'] + '/1'
    def configuration(action, revision, values):
        operation = str(uuid.uuid4())
        with post(config_path + '/' + action, {'requestId': operation, 'expectedRevision': revision, 'values': values, 'password': password}) as response:
            check(json.load(response)['outcome'] == 'CONFIRMED', 'real fixture configuration ' + action)
        check(get('branch-administration/configuration-operations/' + operation)['outcome'] == 'CONFIRMED', 'configuration receipt persisted')
    configuration('prepare', None, {})
    try:
        post(config_path + '/validate', {'requestId': str(uuid.uuid4()), 'expectedRevision': 1, 'password': password})
        raise RuntimeError('Incomplete fixture must not become READY')
    except urllib.error.HTTPError as rejected:
        check(rejected.code == 422, 'incomplete draft rejected for READY')
        rejected.close()
    configuration('update', 1, {'label': 'Disposable example', 'capacity': 2, 'verified': False})
    configuration('validate', 2, {})
    check(get(config_path)['configuration']['readiness'] == 'READY', 'persisted READY for fixture contract only')
    check(cluster.query("SELECT count(*) FROM tenant.branch_configurations WHERE tenant_id='" + tenant_id + "' AND branch_id='" + branches[0] + "'") == '1', 'PG17 confirms configuration scope')
    check(cluster.query("SELECT count(*) FROM tenant.branch_configurations WHERE tenant_id<>'" + tenant_id + "'") == '0', 'no configuration was assigned to other tenants')
    with post('logout', {}) as response:
        check(response.status == 204, 'Core logout confirmed')


def install_configuration_fixture(cluster, tenant_id):
    import uuid
    selected = str(uuid.UUID(tenant_id))
    check(cluster.port not in (5432, 55432), 'configuration fixture uses owned ephemeral endpoint')
    check(cluster.query("SELECT count(*) FROM tenant.initial_registrations r JOIN tenant.tenants t ON t.id=r.tenant_id JOIN platform.administrators a ON a.user_id=r.actor_user_id WHERE t.id='" + selected + "' AND t.status='ACTIVE' AND a.administrator_kind='OWNER' AND a.status='ACTIVE' AND a.revoked_at IS NULL") == '1', 'fixture targets an explicit active company registered by the disposable owner')
    check(cluster.query("SELECT count(*) FROM catalog.applications WHERE code='fixture-config-lfd44'") == '0', 'fixture installation is explicit and never overwrites a contract')
    result = cluster.psql(sql=(ROOT / 'scripts/pilot/branch_configuration_fixture.sql').read_text(encoding='utf-8').replace('__TENANT__', selected))
    check(result.returncode == 0, 'DISPOSABLE FIXTURE ONLY: typed configuration schema and entitlements installed for selected tenant')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--smoke-only', action='store_true')
    parser.add_argument('--core', action='store_true', help='Explicit disposable LS Core owner and registration pilot, independent of Fiscal')
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
        core_username = 'core-' + secrets.token_hex(4)
        initial_username = 'initial-' + secrets.token_hex(4)
        try:
            check(cluster.run(cert_command + (['-SkipUserTrust'] if options.smoke_only else []), env=env).returncode == 0, 'temporary localhost certificate with owned CA validation')
            prepare_database(cluster)
            command = [BACKEND / 'mvnw.cmd', '-B', '-ntp', '-f', BACKEND / 'pom.xml', '-DskipTests', 'package',
                       'dependency:build-classpath', '-Dmdep.outputFile=' + str(cluster.root / 'classpath.txt')]
            check(cluster.run(command, timeout=180).returncode == 0, 'package backend and resolve tooling classpath without running tests')
            env.update(LS_PILOT_ROOT=str(cluster.root), LS_PILOT_PORT=str(cluster.port), LS_PILOT_USERNAME=username,
                       LS_PILOT_CORE_USERNAME=core_username, LS_PILOT_INITIAL_USERNAME=initial_username)
            for role, secret in cluster.secrets.items():
                env['LS_PILOT_' + role.upper()] = secret
            classpath = str(BACKEND / 'target/classes') + os.pathsep + (cluster.root / 'classpath.txt').read_text(encoding='utf-8').strip()
            check(cluster.run([java, '--class-path', classpath, ROOT / 'scripts/pilot/PilotAccount.java',
                               BACKEND / 'src/test/resources/session-context-fixture.sql', *(['--core'] if options.core else [])], input_text=password, env=env).returncode == 0,
                  'real F04 account and existing disposable V17 fixture')
            if options.core:
                check(cluster.psql(script=ROOT / 'scripts/migrations/seal_platform_bootstrap.sql', apply=True).returncode == 0, 'explicit disposable Core owner installed and bootstrap sealed')
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
            if options.core:
                env.update(LS_LOGIN_CORE_ENABLED='true', LS_CORE_REGISTRATION_ENABLED='true', LS_CORE_LIFECYCLE_ENABLED='true', LS_CORE_ADMINISTRATORS_ENABLED='true', LS_CORE_BRANCHES_ENABLED='true', LS_CORE_BRANCHCONFIGURATION_ENABLED='true',
                           LS_LOGIN_CORE_APPLICATION_ID=cluster.query("SELECT id FROM catalog.applications WHERE code='ls-core' AND status='PUBLISHED'"))
                for prefix, role in [('LS_TENANT_REGISTRATION_DB_', 'ls_core_tenant_registrar_dev'),
                                     ('LS_TENANT_REGISTRATION_LOG_DB_', 'ls_core_tenant_registration_log_dev'),
                                     ('LS_TENANT_LIFECYCLE_DB_', 'ls_core_tenant_lifecycle_dev'),
                                     ('LS_TENANT_LIFECYCLE_LOG_DB_', 'ls_core_tenant_lifecycle_log_dev'),
                                     ('LS_TENANT_ADMIN_DB_', 'ls_core_tenant_admin_dev'),
                                     ('LS_TENANT_ADMIN_LOG_DB_', 'ls_core_tenant_admin_log_dev'),
                                     ('LS_BRANCH_ADMIN_DB_', 'ls_core_branch_admin_dev'),
                                     ('LS_BRANCH_ADMIN_LOG_DB_', 'ls_core_branch_admin_log_dev'),
                                     ('LS_BRANCH_CONFIG_DB_', 'ls_core_branch_config_dev'),
                                     ('LS_BRANCH_CONFIG_LOG_DB_', 'ls_core_branch_config_log_dev')]:
                    env.update({prefix + 'HOST': '127.0.0.1', prefix + 'PORT': str(cluster.port), prefix + 'NAME': 'ls_core_dev', prefix + 'PASSWORD': cluster.secrets[role]})
            backend_env = dict(env)
            frontend_env = {key: value for key, value in env.items() if not key.startswith(('LS_', 'SERVER_'))}
            frontend_env.update(VITE_CORE_REGISTRATION='true' if options.core else 'false', VITE_CONNECTED_PILOT='true', VITE_CATALOG_ENABLED='false',
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
            if options.core:
                print('LS CORE: ' + URL + '/core | OWNER: ' + core_username + ' | Administrador inicial disponible: ' + initial_username + ' | Segundo candidato: ' + username, flush=True)
            if options.smoke_only and options.core:
                core_smoke(opener, core_username, initial_username, username, password, cluster)
            elif options.smoke_only:
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
            elif options.core:
                print('Catalogo real sin contratos especializados. Para un ejemplo NO PRODUCTIVO, crea y activa una empresa en la UI.', flush=True)
                print('Comando: fixture-config <UUID_EMPRESA> | salir. El fixture se instala solo en esa empresa del cluster desechable.', flush=True)
                while all(child.poll() is None for child in children):
                    command = input('Piloto Core> ').strip()
                    if command == 'salir':
                        break
                    if command.startswith('fixture-config '):
                        try:
                            install_configuration_fixture(cluster, command.removeprefix('fixture-config ').strip())
                        except (ValueError, AssertionError):
                            print('Fixture no instalado. Usa una empresa activa creada por el OWNER de este piloto y no repitas una instalacion existente.', flush=True)
                    else:
                        print('Comandos disponibles: fixture-config <UUID_EMPRESA> | salir', flush=True)
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

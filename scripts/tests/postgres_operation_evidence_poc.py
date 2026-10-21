"""D3.5/D3.6: one operation-evidence scenario, executed once by the PG17 CI job.

Only standard-library Python and psql; disposable_cluster owns creation/cleanup.
No external endpoint, production migration, authenticator or test suite is invoked.
"""

from contextlib import ExitStack
import hmac
import json
from pathlib import Path
import queue
import secrets
import socket
import subprocess
import sys
import threading
from uuid import uuid4

sys.dont_write_bytecode = True
from test_postgres_migrations import check, disposable_cluster, environment, NO_WINDOW


KEYS = ('ls.tenant_id', 'ls.application_id', 'ls.branch_id', 'ls.origin', 'ls.actor_id',
        'ls.credential_id', 'ls.session_context_id', 'ls.membership_id', 'ls.branch_membership_id')
BINDING = """SELECT json_build_object(
    'db',pg_catalog.current_database(), 'login',session_user::text,
    'instance',pg_catalog.pg_postmaster_start_time()::text, 'pid',pg_catalog.pg_backend_pid(),
    'backend_start',(SELECT a.backend_start::text FROM pg_catalog.pg_stat_activity a
                     WHERE a.pid=pg_catalog.pg_backend_pid()),
    'xid',pg_catalog.pg_current_xact_id()::text);"""


def literal(value):
    """Quote fixture values only; no application SQL builder or client input."""
    return "'" + str(value).replace("'", "''") + "'"


class PsqlSession:
    """A real, persistent physical connection, exclusively owned by the test coordinator.

    Statement errors stop psql by default. Only the deliberately denied INSERT
    temporarily disables ON_ERROR_STOP and captures its SQLSTATE before recovery.
    """

    def __init__(self, cluster, role):
        self.cluster = cluster
        env = environment()
        env.update(PGPASSWORD=cluster.secrets[role], PGCONNECT_TIMEOUT='5', PGCLIENTENCODING='UTF8',
                   PGOPTIONS='-c statement_timeout=10000 -c lock_timeout=3000')
        self.process = subprocess.Popen(
            [cluster.executable('psql'), '-X', '-w', '-qAt', '-h', '127.0.0.1',
             '-p', str(cluster.port), '-U', role, '-d', 'ls_core_dev',
             '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=sqlstate', '-P', 'pager=off'],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding='utf-8', errors='replace', env=env, creationflags=NO_WINDOW)
        self.output = queue.Queue()
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        try:
            for line in self.process.stdout:
                self.output.put(line.rstrip('\r\n'))
        finally:
            self.output.put(None)

    def execute(self, sql):
        marker = 'D35_END_' + uuid4().hex  # Framing only; not authorization evidence.
        self.process.stdin.write(sql + '\n\\echo ' + marker + '\n')
        self.process.stdin.flush()
        output = []
        while True:
            try:
                line = self.output.get(timeout=20)
            except queue.Empty:
                raise AssertionError('psql response timed out; not an authorization denial') from None
            if line is None:
                raise AssertionError('psql exited before completion; preparation/SQL failure')
            if any(secret in line for secret in self.cluster.secrets.values()):
                raise AssertionError('Ephemeral credential in diagnostics; output withheld')
            if line == marker:
                return output
            if line:
                output.append(line)

    def value(self, sql):
        output = self.execute(sql)
        if len(output) != 1:
            raise AssertionError('Expected one JSON result; unexpected output is not a denial')
        return json.loads(output[0])

    def close(self):
        if self.process.poll() is None:
            try:
                self.process.stdin.write('\\q\n')
                self.process.stdin.flush()
            except (BrokenPipeError, OSError):
                pass
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)
            raise AssertionError('psql did not close normally') from None
        finally:
            self.reader.join(timeout=5)
            self.process.stdin.close()
            self.process.stdout.close()
        if self.process.returncode:
            raise AssertionError('psql failed; an aborted transaction is not a successful rejection')


def synthetic_claims():
    return {key: 'SESSION' if key == 'ls.origin' else str(uuid4()) for key in KEYS}


def insert_evidence(binding, claims, grant_id):
    values = [grant_id, binding['db'], binding['login'], binding['instance'],
              binding['pid'], binding['backend_start'], binding['xid'], json.dumps(claims)]
    return ('INSERT INTO d35.evidence(grant_id,db,login,instance_start,pid,backend_start,xid,claims) '
            'VALUES (' + ','.join(map(literal, values)) + ');')


class Coordinator:
    """Trusted fixture authority, outside the SQL attacker's privileges.

    Holds emitter credentials and physical sessions; opaque handles index its own
    connections. admit() never accepts a requester-supplied PID/XID/native tuple.
    Fixture bearer verification establishes synthetic scope only, not real F04/F05 auth.
    SQL is released to the hostile phase only after emission has committed.
    """

    def __init__(self, cluster, stack, claims_a, claims_b):
        self.cluster, self.stack = cluster, stack
        self.emitter = self.open('d35_issuer')  # Separate physical LOGIN, autocommit.
        self.credentials = [(secrets.token_urlsafe(32), claims_a), (secrets.token_urlsafe(32), claims_b)]
        self.owned = {}

    def open(self, role):
        session = PsqlSession(self.cluster, role)
        self.stack.callback(session.close)
        return session

    def begin(self):
        handle = object()  # Local custody handle, not a PostgreSQL or HTTP credential.
        session = self.open('d35_runtime')
        session.execute('BEGIN ISOLATION LEVEL READ COMMITTED READ WRITE;')
        self.owned[handle] = session
        return handle

    def admit(self, handle, credential):
        claims = next((scope for token, scope in self.credentials
                       if hmac.compare_digest(token, credential)), None)
        if claims is None or handle not in self.owned:
            raise AssertionError('No authenticated synthetic admission')
        session = self.owned[handle]
        binding = session.value(BINDING)  # Native query on the exclusively owned connection.
        if binding['login'] != 'd35_runtime' or not binding['backend_start']:
            raise AssertionError('Unexpected physical connection')
        grant_id = str(uuid4())  # Deliberately disclosed to attacker below; never a credential.
        check(self.emitter.execute(insert_evidence(binding, claims, grant_id)) == [],
              'trusted issuer committed evidence with immutable claims using its separate LOGIN')
        return session, binding, grant_id


def prepare(cluster, a, b):
    check(cluster.port not in (5432, 55432), 'D3.5 refuses shared Windows/Docker ports')
    result = cluster.psql(sql="SELECT json_build_object('data',current_setting('data_directory'),"
                          "'port',inet_server_port(),'version',current_setting('server_version_num')::int)",
                          database='postgres')
    check(result.returncode == 0, 'cluster identity query succeeds before fixture preparation')
    identity = json.loads(result.stdout)
    check(Path(identity['data']).resolve() == cluster.data.resolve() and identity['port'] == cluster.port
          and 170000 <= identity['version'] < 180000, 'owned PGDATA, loopback port and PG17 verified')
    check(cluster.psql(sql='CREATE DATABASE ls_core_dev TEMPLATE template0', database='postgres').returncode == 0,
          'fresh fixture database, no migrations or production provisioning')
    cluster.query("""
        CREATE ROLE d35_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
        CREATE ROLE d35_runtime NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
        CREATE ROLE d35_issuer NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
        REVOKE ALL ON DATABASE ls_core_dev FROM PUBLIC;
        GRANT CONNECT ON DATABASE ls_core_dev TO d35_runtime,d35_issuer;
        REVOKE CREATE ON SCHEMA public FROM PUBLIC;
        CREATE SCHEMA d35 AUTHORIZATION d35_owner;
        SET ROLE d35_owner;
        CREATE TABLE d35.tenants(id uuid PRIMARY KEY, label text NOT NULL);
        CREATE TABLE d35.data(tenant_id uuid PRIMARY KEY REFERENCES d35.tenants(id),
                              application_id uuid NOT NULL, branch_id uuid NOT NULL, payload text NOT NULL);
        CREATE TABLE d35.evidence(
            grant_id uuid UNIQUE NOT NULL, db text NOT NULL, login text NOT NULL,
            instance_start timestamptz NOT NULL, pid integer NOT NULL, backend_start timestamptz NOT NULL,
            xid xid8 NOT NULL, claims jsonb NOT NULL,
            resource text NOT NULL DEFAULT 'd35.data', command text NOT NULL DEFAULT 'SELECT',
            expires_at timestamptz NOT NULL DEFAULT (statement_timestamp()+interval '5 minutes'),
            revoked_at timestamptz,
            PRIMARY KEY(db,login,instance_start,pid,backend_start,xid));
        ALTER TABLE d35.evidence ENABLE ROW LEVEL SECURITY;
        ALTER TABLE d35.evidence FORCE ROW LEVEL SECURITY;
        CREATE POLICY own_operation ON d35.evidence FOR SELECT TO d35_runtime USING(
            db=pg_catalog.current_database() AND login=session_user::text
            AND instance_start=pg_catalog.pg_postmaster_start_time()
            AND pid=pg_catalog.pg_backend_pid()
            AND backend_start=(SELECT a.backend_start FROM pg_catalog.pg_stat_activity a
                               WHERE a.pid=pg_catalog.pg_backend_pid())
            AND xid=pg_catalog.pg_current_xact_id()
            AND pg_catalog.current_setting('transaction_isolation')='read committed'
            AND expires_at>pg_catalog.statement_timestamp() AND revoked_at IS NULL);
        CREATE POLICY trusted_emission ON d35.evidence FOR INSERT TO d35_issuer WITH CHECK(true);
        CREATE POLICY issuer_lookup ON d35.evidence FOR SELECT TO d35_issuer USING(true);
        CREATE POLICY issuer_revocation ON d35.evidence FOR UPDATE TO d35_issuer
            USING(revoked_at IS NULL) WITH CHECK(revoked_at IS NOT NULL);
        ALTER TABLE d35.data ENABLE ROW LEVEL SECURITY;
        ALTER TABLE d35.data FORCE ROW LEVEL SECURITY;
        CREATE POLICY authorized_read ON d35.data FOR SELECT TO d35_runtime USING(EXISTS(
            SELECT FROM d35.evidence e WHERE e.resource='d35.data' AND e.command='SELECT'
            AND e.claims->>'ls.tenant_id'=data.tenant_id::text
            AND e.claims->>'ls.application_id'=data.application_id::text
            AND e.claims->>'ls.branch_id'=data.branch_id::text
            AND NOT EXISTS(SELECT FROM pg_catalog.jsonb_each_text(e.claims) c
                           WHERE pg_catalog.current_setting(c.key,true) IS DISTINCT FROM c.value)));
        GRANT USAGE ON SCHEMA d35 TO d35_runtime,d35_issuer;
        GRANT SELECT ON d35.data,d35.evidence TO d35_runtime;
        GRANT INSERT ON d35.evidence TO d35_issuer;
        GRANT SELECT(grant_id,revoked_at), UPDATE(revoked_at) ON d35.evidence TO d35_issuer;
        RESET ROLE;
    """)
    for role in ('d35_runtime', 'd35_issuer'):
        password = secrets.token_urlsafe(32)
        cluster.secrets[role] = password
        result = cluster.psql(sql='\\password ' + role, reply=password + '\n' + password + '\n')
        check(result.returncode == 0, 'ephemeral SCRAM password set without SQL/CLI disclosure for ' + role)
        check(cluster.query("SELECT NOT rolcanlogin AND rolpassword LIKE 'SCRAM-SHA-256$%' "
                            "FROM pg_authid WHERE rolname=" + literal(role)) == 't', 'SCRAM verifier confirmed')
        cluster.query('ALTER ROLE ' + role + ' LOGIN')
    for scope, label in ((a, 'A'), (b, 'B')):
        cluster.query('INSERT INTO d35.tenants VALUES (' + literal(scope['ls.tenant_id']) + ',' + literal(label) + ');'
                      'INSERT INTO d35.data VALUES (' + ','.join(map(literal, [scope['ls.tenant_id'],
                      scope['ls.application_id'], scope['ls.branch_id'], label + '-only-marker'])) + ');')
    check(cluster.query('SELECT count(*) FROM d35.tenants') == '2'
          and json.loads(cluster.query('SELECT json_agg(payload ORDER BY payload) FROM d35.data'))
          == ['A-only-marker', 'B-only-marker'], 'admin verifies two tenants and two nonempty physical rows')


def set_selectors(session, claims):
    sql = ''.join('SELECT pg_catalog.set_config(' + literal(key) + ',' + literal(claims[key]) + ',true);'
                  for key in KEYS)
    # set_config returns nine values; inspect each before the separate same-connection check.
    check(session.execute(sql) == [claims[key] for key in KEYS], 'all nine ls.* assigned on attacking connection')
    comparisons = ' AND '.join('current_setting(' + literal(k) + ')=' + literal(claims[k]) for k in KEYS)
    check(session.value('SELECT to_json((' + comparisons + '));') is True,
          'all nine ls.* verified in the executing transaction')


def visible(session, where=''):
    return session.value("SELECT coalesce(json_agg(payload ORDER BY payload),'[]'::json) FROM d35.data " + where + ';')


def scenario(cluster):
    """One scenario: D3.5 controls, then committed revocation inside B's original transaction."""
    a, b = synthetic_claims(), synthetic_claims()
    prepare(cluster, a, b)
    with ExitStack() as stack:
        coordinator = Coordinator(cluster, stack, a, b)
        ca, binding_a, grant_a = coordinator.admit(coordinator.begin(), coordinator.credentials[0][0])
        cb, binding_b, grant_b = coordinator.admit(coordinator.begin(), coordinator.credentials[1][0])
        check(binding_a['login'] == binding_b['login'] == 'd35_runtime', 'both operations use the same runtime LOGIN')
        check(ca.value("""SELECT to_json(
            has_table_privilege(session_user,'d35.data','SELECT')
            AND has_table_privilege(session_user,'d35.evidence','SELECT')
            AND has_schema_privilege(session_user,'d35','USAGE')
            AND row_security_active('d35.data') AND row_security_active('d35.evidence')
            AND NOT has_table_privilege(session_user,'d35.evidence','INSERT,UPDATE,DELETE,TRUNCATE')
            AND NOT has_any_column_privilege(session_user,'d35.evidence','UPDATE')
            AND NOT has_schema_privilege(session_user,'d35','CREATE')
            AND NOT has_schema_privilege(session_user,'public','CREATE')
            AND NOT has_database_privilege(session_user,current_database(),'CREATE,TEMPORARY')
            AND NOT (r.rolsuper OR r.rolbypassrls OR r.rolcreaterole OR r.rolcreatedb OR r.rolreplication)
            AND NOT EXISTS(SELECT FROM pg_auth_members m WHERE m.member=r.oid)
            AND NOT EXISTS(SELECT FROM pg_class c WHERE c.relnamespace='d35'::regnamespace AND c.relowner=r.oid))
            FROM pg_roles r WHERE r.rolname=session_user;""") is True,
              'runtime has real SELECT and active RLS, without ownership, writes, memberships or bypass')
        set_selectors(ca, a)
        set_selectors(cb, b)
        check(visible(ca) == ['A-only-marker'], 'positive A reads exactly its physical row')
        check(visible(cb) == ['B-only-marker'], 'foreign B evidence genuinely authorizes its own operation')
        set_selectors(ca, b)
        check(visible(ca) == [] and visible(ca, 'WHERE tenant_id=' + literal(b['ls.tenant_id'])) == [],
              'forging all nine ls.* cannot expose B')
        check(ca.value('SELECT count(*)::int FROM d35.evidence WHERE grant_id=' + literal(grant_b) + ';') == 0,
              'known live B grant UUID cannot select foreign evidence')
        output = ca.execute('SAVEPOINT hostile;\n\\set ON_ERROR_STOP off\n'
                            + insert_evidence(binding_a, b, str(uuid4()))
                            + '\n\\set denied_state :SQLSTATE\n\\set ON_ERROR_STOP on\n'
                            'ROLLBACK TO SAVEPOINT hostile; RELEASE SAVEPOINT hostile;\n'
                            "SELECT json_build_object('state', :'denied_state', 'healthy', true);")
        check(len(output) == 2 and '42501' in output[0]
              and json.loads(output[1]) == {'state': '42501', 'healthy': True},
              'fabricating B evidence for own binding fails with 42501; savepoint recovery succeeds')
        set_selectors(ca, a)
        check(visible(ca) == ['A-only-marker'] and visible(cb) == ['B-only-marker'],
              'both legitimate reads still succeed after the attack; no aborted-transaction false positive')
        ca.execute('COMMIT; BEGIN ISOLATION LEVEL READ COMMITTED READ WRITE;')
        next_binding = ca.value(BINDING)
        check(all(next_binding[k] == binding_a[k] for k in ('db', 'login', 'instance', 'pid', 'backend_start'))
              and next_binding['xid'] != binding_a['xid'], 'same physical backend, new native xid8')
        set_selectors(ca, a)
        stored = json.loads(cluster.query("SELECT json_build_object('stored',count(*)::int,"
                            "'unexpired',bool_and(expires_at>statement_timestamp())) FROM d35.evidence WHERE grant_id="
                            + literal(grant_a)))
        check(stored == {'stored': 1, 'unexpired': True}, 'old A evidence remains stored and unexpired')
        check(ca.value('SELECT count(*)::int FROM d35.evidence WHERE grant_id=' + literal(grant_a) + ';') == 0
              and visible(ca) == [], 'new transaction cannot reuse old evidence, even with identical selectors')
        check(ca.value('SELECT to_json(1);') == 1, 'replay query completed normally, not in aborted transaction')
        ca.execute('COMMIT;')

        # B is still in its original transaction: no COMMIT/ROLLBACK/reconnect or new admission.
        check(cb.value(BINDING) == binding_b
              and cb.value("SELECT to_json(current_setting('transaction_isolation'));") == 'read committed'
              and visible(cb) == ['B-only-marker'], 'B is authorized in its original READ COMMITTED transaction')
        issuer = coordinator.emitter.value(
            "SELECT json_build_object('login',session_user::text,'pid',pg_backend_pid());")
        check(issuer['login'] == 'd35_issuer' and issuer['pid'] != binding_b['pid'],
              'revocation uses the existing independent trusted issuer connection')
        revoked = coordinator.emitter.execute(
            'BEGIN; WITH changed AS (UPDATE d35.evidence SET revoked_at=statement_timestamp() '
            'WHERE grant_id=' + literal(grant_b) + ' AND revoked_at IS NULL RETURNING grant_id) '
            'SELECT count(*)::int FROM changed; COMMIT;')
        check(revoked == ['1'], 'issuer updated exactly one B evidence row and COMMIT completed')
        # This is B's first SQL statement after the independent COMMIT; selectors are unchanged.
        check(visible(cb) == [], 'next B read in original open transaction returns zero rows after revocation')
        check(cb.value(BINDING) == binding_b
              and cb.value("SELECT to_json(current_setting('transaction_isolation'));") == 'read committed',
              'B retains the same connection, backend, xid8 and READ COMMITTED after denial')
        stored_b = json.loads(cluster.query(
            "SELECT json_build_object('stored',count(*)::int,'revoked',bool_and(revoked_at IS NOT NULL),"
            "'unexpired',bool_and(expires_at>statement_timestamp()),'same_claims',bool_and(claims="
            + literal(json.dumps(b)) + "::jsonb),'business_rows',(SELECT count(*)::int FROM d35.data "
            'WHERE tenant_id=' + literal(b['ls.tenant_id']) + " AND payload='B-only-marker')) "
            'FROM d35.evidence WHERE grant_id=' + literal(grant_b)))
        check(stored_b == {'stored': 1, 'revoked': True, 'unexpired': True,
                           'same_claims': True, 'business_rows': 1},
              'admin confirms B data and original evidence persist; evidence is revoked, not expired or deleted')
        set_selectors(cb, b)
        check(visible(cb) == [] and cb.value(
            'SELECT count(*)::int FROM d35.evidence WHERE grant_id=' + literal(grant_b) + ';') == 0,
              'restoring all nine B GUCs and selecting its grant cannot restore revoked authorization')
        check(cb.value(BINDING) == binding_b and cb.value('SELECT to_json(1);') == 1,
              'B transaction is still original and healthy after GUC restoration; no hidden SQL error')
        cb.execute('ROLLBACK;')


def main():
    with disposable_cluster() as cluster:
        root, data, port = cluster.root, cluster.data, cluster.port
        scenario(cluster)
    # Context exit performs the existing stop and guarded removal, without changing its implementation.
    check(not root.exists() and not data.exists(), 'disposable cluster directory and PGDATA removed')
    with socket.socket() as probe:
        probe.settimeout(1)
        check(probe.connect_ex(('127.0.0.1', port)) != 0, 'temporary listener closed')
    print('PASS D3.6: one expanded scenario; D3.5 controls preserved; B revoked between statements '
          'in its original READ COMMITTED transaction; cleanup verified.')


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        # No raw SQL/credentials or server diagnostics; failed preparation must fail CI.
        reason = str(failure) if isinstance(failure, AssertionError) else type(failure).__name__
        print('FAIL D3.6:', reason, flush=True)
        raise SystemExit(1) from None

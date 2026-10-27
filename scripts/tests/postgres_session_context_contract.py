"""LFD-35: one HTTPS context selector integration using the existing owned PG17 harness."""
import sys

sys.dont_write_bytecode = True
from test_postgres_migrations import SOURCE, check
from postgres_application_login_contract import main, run_login_integration


def test_session_contexts(cluster):
    check(cluster.port not in (5432, 55432), 'context selector uses owned ephemeral PG17 only')
    result = cluster.flyway('migrate', location=SOURCE, target='17')
    if result.returncode:
        print((result.stdout + result.stderr)[-5000:])
    check(result.returncode == 0, 'restricted migrator applies V17')
    check(cluster.flyway('validate', location=SOURCE, target='17').returncode == 0, 'Flyway validates intact V1-V17')
    run_login_integration(cluster, 'SessionContextPostgresTest')


if __name__ == '__main__':
    main('SessionContextPostgresTest')

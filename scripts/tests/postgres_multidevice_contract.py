"""One HTTPS/PG17 multidevice integration, using the existing disposable V16 harness."""
import sys

sys.dont_write_bytecode = True
from test_postgres_migrations import SOURCE, check
from postgres_application_login_contract import main, run_login_integration


def test_multidevice(cluster):
    check(cluster.port not in (5432, 55432), 'multidevice uses owned ephemeral PG17 only')
    check(cluster.flyway('migrate', location=SOURCE, target='18').returncode == 0, 'restricted migrator applies V17-V18')
    check(cluster.flyway('validate', location=SOURCE, target='18').returncode == 0, 'Flyway validates V1-V18')
    run_login_integration(cluster, 'MultidevicePostgresTest')


if __name__ == '__main__':
    main('MultidevicePostgresTest')

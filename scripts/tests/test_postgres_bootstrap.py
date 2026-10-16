"""Exercise bootstrap SQL against disposable clusters, never the shared server.

Windows: python scripts/tests/test_postgres_bootstrap.py
Uses only Python's standard library and the existing PostgreSQL 17 binaries.
"""

import os
from contextlib import contextmanager
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile


BIN = Path(r"C:\Program Files\PostgreSQL\17\bin")
SOURCE = Path(__file__).resolve().parents[1] / "postgres"
ENV = {key: value for key, value in os.environ.items() if not key.upper().startswith("PG")}
ENV["PGCONNECT_TIMEOUT"] = "3"
# psql's Windows/MSYS fallback reads the test pipe, not CONIN$.
# Only this disposable-cluster harness uses it; production prompts remain interactive.
ENV["OSTYPE"] = "msys"
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def command(args, input_text=None, capture=True):
    return subprocess.run(
        [str(arg) for arg in args], input=input_text,
        stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
        stderr=subprocess.PIPE if capture else subprocess.DEVNULL,
        **({} if input_text is not None else {"stdin": subprocess.DEVNULL}),
        text=True, encoding="utf-8", errors="replace", env=ENV,
        timeout=30, creationflags=NO_WINDOW,
    )


@contextmanager
def isolated_directory():
    root = Path(tempfile.mkdtemp(prefix="ls-core-bootstrap-test-")).resolve()
    if root.parent != Path(tempfile.gettempdir()).resolve() or not root.name.startswith("ls-core-bootstrap-test-"):
        raise RuntimeError("Unexpected temporary cleanup path")
    try:
        yield root
    finally:
        data = root / "data"
        if (data / "postmaster.pid").exists():
            stopped = command([BIN / "pg_ctl.exe", "-D", data, "-m", "fast", "-w", "stop"], capture=False)
            if stopped.returncode != 0:
                raise RuntimeError("Isolated cluster still running; retaining its temporary directory")
        shutil.rmtree(root)


def check(condition, label):
    if not condition:
        raise AssertionError(label)
    print("OK:", label, flush=True)


def scenario(manual):
    with isolated_directory() as directory:
        root = Path(directory).resolve()
        # Validate the absolute cleanup target before TemporaryDirectory can remove it.
        if root.parent != Path(tempfile.gettempdir()).resolve() or not root.name.startswith("ls-core-bootstrap-test-"):
            raise RuntimeError("Unexpected temporary directory; refusing filesystem operations")
        data = root / "data"
        sql_dir = root / "sql"
        sql_dir.mkdir()
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        if port == 5432:
            raise RuntimeError("Refusing the shared PostgreSQL port")
        for source in SOURCE.glob("*.sql"):
            text = source.read_text(encoding="utf-8")
            # The production SQL insists on 5432. Only test copies target this isolated port.
            text = text.replace("inet_server_port() <> 5432", f"inet_server_port() <> {port}")
            (sql_dir / source.name).write_text(text, encoding="utf-8")

        init = command([BIN / "initdb.exe", "-D", data, "-U", "postgres", "-A", "trust",
                        "--encoding=UTF8", "--locale=C", "--no-instructions"])
        check(init.returncode == 0, "isolated initdb")
        started = False
        password = secrets.token_urlsafe(32)
        try:
            start = command([BIN / "pg_ctl.exe", "-D", data, "-l", root / "server.log",
                             "-o", f"-h 127.0.0.1 -p {port}", "-w", "start"], capture=False)
            started = start.returncode == 0
            check(started, "isolated server on non-5432 port")

            def psql(sql=None, file=None, apply=False, adopt=False, reply=None, app=False):
                args = [BIN / "psql.exe", "-X", "-w", "-h", "127.0.0.1", "-p", str(port),
                        "-U", "ls_core_app_dev" if app else "postgres", "-d", "ls_core_dev" if app else "postgres",
                        "-v", "ON_ERROR_STOP=1", "-v", f"apply={'true' if apply else 'false'}",
                        "-v", f"adopt={'true' if adopt else 'false'}", "-At"]
                args += ["-f", sql_dir / file] if file else ["-c", sql]
                result = command(args, reply)
                if password in result.stdout + result.stderr:
                    raise AssertionError("Plaintext test password appeared in captured output")
                return result

            def snapshot():
                # Compare password and all role/database catalog fields in memory; never print/save them.
                result = psql(sql="SELECT row_to_json(r)::text FROM pg_authid r ORDER BY oid; "
                              "SELECT row_to_json(d)::text FROM pg_database d ORDER BY oid; "
                              "SELECT row_to_json(c)::text FROM pg_shdescription c ORDER BY objoid,classoid;")
                if result.returncode != 0:
                    raise AssertionError("Catalog snapshot failed; cannot verify preservation")
                return result.stdout

            if manual:
                result = psql(sql="CREATE ROLE ls_core_app_dev NOLOGIN NOSUPERUSER NOCREATEDB "
                              "NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;")
                check(result.returncode == 0, "manual partial role fixture")
                identity = psql(sql="SELECT oid FROM pg_roles WHERE rolname='ls_core_app_dev'").stdout
                for mutation in (
                    "ALTER ROLE ls_core_app_dev CREATEDB;",
                    "COMMENT ON ROLE ls_core_app_dev IS 'another application';",
                    "CREATE ROLE fixture_parent; GRANT fixture_parent TO ls_core_app_dev;",
                    "GRANT CONNECT ON DATABASE postgres TO ls_core_app_dev;",
                    "CREATE SCHEMA fixture_owned AUTHORIZATION ls_core_app_dev;",
                ):
                    # A failed psql session rolls back its fixture transaction, no DROP needed.
                    fixture = sql_dir / "negative.sql"
                    fixture.write_text("BEGIN;\n" + mutation + "\n\\ir inspect-bootstrap.sql\nROLLBACK;\n", encoding="utf-8")
                    before = snapshot()
                    result = psql(file="negative.sql")
                    check(result.returncode != 0 and "ERROR" in result.stderr, "incompatible fixture rejected")
                    check(snapshot() == before, "negative fixture fully rolled back")

            before = snapshot()
            result = psql(file="initialize-local.sql")
            check(result.returncode == 0 and "INSPECTION ONLY" in result.stdout, "inspection succeeds")
            check(snapshot() == before, "inspection changes no role/database/comment")
            if manual:
                check("explicit adoption required: t" in result.stdout, "manual role shown as adoption candidate")
                result = psql(file="initialize-local.sql", apply=True)
                check(result.returncode != 0 and "-AdoptExistingRole" in result.stderr, "apply without adoption refused")
                check(snapshot() == before, "refused adoption changes nothing")
                result = psql(file="initialize-local.sql", apply=True, adopt=True, reply="APPLY\n")
                check(result.returncode != 0, "generic APPLY cannot authorize adoption")
                check(snapshot() == before, "incorrect adoption confirmation changes nothing")
            confirmation = "ADOPT ls_core_app_dev AND APPLY" if manual else "APPLY"
            result = psql(file="initialize-local.sql", apply=True, adopt=manual,
                          reply=f"{confirmation}\n{password}\n{password}\n")
            if result.returncode != 0:
                # Do not dump captured diagnostics, which could include a password verifier.
                raise AssertionError("isolated apply failed; inspect the SQL, no captured credentials printed")
            check("ADMIN VERIFICATION OK" in result.stdout, "bootstrap completed")
            if manual:
                check(psql(sql="SELECT oid FROM pg_roles WHERE rolname='ls_core_app_dev'").stdout == identity,
                      "adoption preserves role OID")
            check(psql(file="verify-application.sql", app=True).returncode == 0, "application identity/permissions verified")
            before = snapshot()
            result = psql(file="initialize-local.sql", apply=True)
            check(result.returncode == 0 and "NO CHANGES" in result.stdout, "second apply is idempotent")
            check(snapshot() == before, "second apply preserves password, catalogs and comments")
            check(all(password not in path.read_text(encoding="utf-8", errors="replace")
                      for path in [root / "server.log", *sql_dir.glob("*.sql")]),
                  "no plaintext test password in SQL files or server log")
        finally:
            if started:
                stopped = command([BIN / "pg_ctl.exe", "-D", data, "-m", "fast", "-w", "stop"], capture=False)
                if stopped.returncode != 0:
                    raise RuntimeError("Temporary server did not stop; do not remove its data directory")


if __name__ == "__main__":
    scenario(manual=False)
    scenario(manual=True)
    print("All isolated bootstrap scenarios passed. Shared server was not used.")

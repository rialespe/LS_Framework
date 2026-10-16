-- Shared pre/postflight. Catalog reads only; errors stop the caller.
DO $$
DECLARE
  app pg_authid%ROWTYPE;
  db pg_database%ROWTYPE;
  marked boolean;
  adoptable boolean;
BEGIN
  IF current_user <> 'postgres' OR session_user <> 'postgres'
     OR NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
    RAISE EXCEPTION 'Connect as the existing postgres administrator; no privilege escalation is attempted';
  END IF;
  IF current_setting('server_version_num')::int NOT BETWEEN 170000 AND 179999
     OR inet_server_addr() <> '127.0.0.1'::inet OR inet_server_port() <> 5432 THEN
    RAISE EXCEPTION 'Expected a local PostgreSQL 17 server at 127.0.0.1:5432';
  END IF;

  SELECT * INTO app FROM pg_authid WHERE rolname = 'ls_core_app_dev';
  IF FOUND THEN
    marked := coalesce(shobj_description(app.oid, 'pg_authid') = 'LS Core LFD-23 bootstrap v1', false);
    -- Candidate only: inspection never grants adoption permission or writes a marker.
    adoptable := NOT app.rolcanlogin AND shobj_description(app.oid, 'pg_authid') IS NULL;
    IF NOT marked AND NOT adoptable AND NOT EXISTS (SELECT FROM pg_database WHERE datname = 'ls_core_dev') THEN
      RAISE EXCEPTION 'Unmarked existing role is not a compatible NOLOGIN partial installation';
    END IF;
    IF app.rolsuper OR app.rolcreatedb OR app.rolcreaterole OR app.rolreplication
       OR app.rolbypassrls OR app.rolinherit
       OR (app.rolconnlimit <> 3 AND NOT (adoptable AND app.rolconnlimit = -1))
       OR (app.rolvaliduntil IS NOT NULL AND app.rolvaliduntil <> 'infinity'::timestamptz)
       OR (NOT app.rolcanlogin AND NOT marked AND NOT adoptable)
       OR (app.rolcanlogin AND app.rolpassword IS NULL) THEN
      RAISE EXCEPTION 'Existing application role is incompatible; it will not be modified';
    END IF;
    IF adoptable AND (
      EXISTS (SELECT FROM pg_stat_activity WHERE usesysid = app.oid)
      OR EXISTS (SELECT FROM pg_prepared_xacts WHERE owner = app.rolname)
      OR EXISTS (SELECT FROM pg_shseclabel WHERE classoid = 'pg_authid'::regclass AND objoid = app.oid)
    ) THEN
      RAISE EXCEPTION 'Unmarked role has sessions, prepared transactions or security labels; adoption refused';
    END IF;
    IF EXISTS (SELECT FROM pg_auth_members WHERE member = app.oid OR roleid = app.oid OR grantor = app.oid)
       OR EXISTS (SELECT FROM pg_db_role_setting WHERE setrole = app.oid)
       OR EXISTS (SELECT FROM pg_shdepend WHERE refclassid = 'pg_authid'::regclass
                  AND refobjid = app.oid AND deptype = 'o') THEN
      RAISE EXCEPTION 'Application role has memberships, settings or owned objects; administrator review required';
    END IF;
    IF EXISTS (
      SELECT FROM pg_shdepend s WHERE s.refclassid = 'pg_authid'::regclass
      AND s.refobjid = app.oid
      AND NOT (s.deptype = 'a' AND s.classid = 'pg_database'::regclass AND s.objid =
        coalesce((SELECT oid FROM pg_database WHERE datname = 'ls_core_dev'), 0))
    ) THEN
      RAISE EXCEPTION 'Application role has shared dependencies outside the target database ACL; review required';
    END IF;
  END IF;

  SELECT * INTO db FROM pg_database WHERE datname = 'ls_core_dev';
  IF FOUND THEN
    marked := coalesce(shobj_description(db.oid, 'pg_database') = 'LS Core LFD-23 bootstrap v1', false);
    IF pg_get_userbyid(db.datdba) <> 'postgres' OR pg_encoding_to_char(db.encoding) <> 'UTF8'
       OR NOT db.datallowconn OR db.datistemplate OR db.datconnlimit = 0 THEN
      RAISE EXCEPTION 'Existing database owner/encoding/state is incompatible; no repair attempted';
    END IF;
    IF EXISTS (
      SELECT FROM aclexplode(coalesce(db.datacl, acldefault('d', db.datdba))) a
      WHERE a.grantee <> db.datdba AND
        (a.grantor <> db.datdba OR a.is_grantable
         OR (a.grantee = 0 AND a.privilege_type NOT IN ('CONNECT', 'TEMPORARY'))
         OR (a.grantee <> 0 AND (a.grantee <> coalesce(app.oid, 0) OR a.privilege_type <> 'CONNECT')))
    ) THEN
      RAISE EXCEPTION 'Existing database ACL has unexpected privileges; no repair attempted';
    END IF;
    IF NOT marked AND (
      app.oid IS NULL OR NOT has_database_privilege(app.oid, db.oid, 'CONNECT')
      OR EXISTS (SELECT FROM aclexplode(coalesce(db.datacl, acldefault('d', db.datdba))) a WHERE a.grantee = 0)
    ) THEN
      RAISE EXCEPTION 'Unmarked existing database is incomplete; verify its purpose manually before changing it';
    END IF;
  END IF;
END $$;

SELECT current_setting('server_version') AS server_version, inet_server_addr() AS server_address,
       inet_server_port() AS server_port;
SELECT EXISTS (SELECT FROM pg_database WHERE datname = 'ls_core_dev') AS database_exists,
       EXISTS (SELECT FROM pg_roles WHERE rolname = 'ls_core_app_dev') AS role_exists;
SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolreplication,
       rolbypassrls, rolinherit, rolconnlimit
FROM pg_roles WHERE rolname = 'ls_core_app_dev';
SELECT datname, pg_get_userbyid(datdba) AS owner, pg_encoding_to_char(encoding) AS encoding
FROM pg_database WHERE datname = 'ls_core_dev';

-- Platform installation only. Required vars: tenant_id, application_id, designation_id,
-- kind (PLATFORM/TENANT), capability (READ/MANAGE), expires_at. Separate READ and MANAGE grants.
\set ON_ERROR_STOP on
\set ECHO none
\if :{?apply}
\else
 \set apply false
\endif
\if :apply
\else
 DO $$ BEGIN RAISE EXCEPTION 'Explicit apply=true required'; END $$;
\endif
BEGIN;
SELECT pg_advisory_xact_lock(370019,1);
SELECT pg_advisory_xact_lock(410023,1);
DO $$ BEGIN
 IF session_user<>'postgres' OR current_user<>session_user OR current_database()<>'ls_core_dev'
 OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
 OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999 THEN
 RAISE EXCEPTION 'Controlled platform PG17 installation operator required'; END IF;
END $$;
INSERT INTO platform.rbac_administrator_grants(tenant_id,application_id,platform_designation_id,tenant_designation_id,capability,expires_at)
 SELECT t.id,ta.application_id,CASE WHEN :'kind'='PLATFORM' THEN :'designation_id'::uuid END,
 CASE WHEN :'kind'='TENANT' THEN :'designation_id'::uuid END,:'capability',:'expires_at'::timestamptz
 FROM tenant.tenants t JOIN catalog.tenant_applications ta ON ta.tenant_id=t.id
 WHERE t.id=:'tenant_id'::uuid AND ta.application_id=:'application_id'::uuid AND t.status='ACTIVE'
 AND :'expires_at'::timestamptz>clock_timestamp() AND (
 (:'kind'='PLATFORM' AND EXISTS(SELECT FROM platform.administrators a WHERE a.id=:'designation_id'::uuid AND a.administrator_kind='SUPERADMIN' AND a.status='ACTIVE' AND a.revoked_at IS NULL))
 OR (:'kind'='TENANT' AND EXISTS(SELECT FROM tenant.valid_administrators(t.id) a WHERE a.id=:'designation_id'::uuid)))
 RETURNING id \gset
COMMIT;

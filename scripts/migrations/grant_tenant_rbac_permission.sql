-- Platform installation only. Required vars: tenant_id, application_id, permission_id,
-- privilege_kind (READ/WRITE), expires_at. Never a tenant HTTP capability.
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
INSERT INTO platform.rbac_permission_delegations(tenant_id,application_id,permission_id,privilege_kind,expires_at)
 SELECT t.id,a.id,p.id,:'privilege_kind',:'expires_at'::timestamptz
 FROM tenant.tenants t JOIN catalog.tenant_applications ta ON ta.tenant_id=t.id
 JOIN catalog.applications a ON a.id=ta.application_id JOIN security.permissions p ON p.application_id=a.id
 WHERE t.id=:'tenant_id'::uuid AND a.id=:'application_id'::uuid AND p.id=:'permission_id'::uuid
 AND t.status='ACTIVE' AND a.status='PUBLISHED' AND p.status='PUBLISHED' AND p.resource_scope IN ('TENANT','BRANCH')
 AND :'expires_at'::timestamptz>clock_timestamp()
 RETURNING permission_id \gset
-- gset requires exactly one row; duplicates/conflicts fail without adoption. Trigger audits atomically.
COMMIT;

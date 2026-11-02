-- Explicit platform semantic classification. Not a delegation or assignment.
-- Required vars: application_id, permission_id, operation_kind READ/WRITE, context_mode SINGLE_BRANCH/RESERVED.
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
SELECT pg_advisory_xact_lock(370019,1); SELECT pg_advisory_xact_lock(410023,1);
DO $$ BEGIN
 IF session_user<>'postgres' OR current_user<>session_user OR current_database()<>'ls_core_dev'
 OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
 OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999 THEN
 RAISE EXCEPTION 'Controlled PG17 installation operator required'; END IF;
END $$;
INSERT INTO platform.permission_execution_rules(application_id,permission_id,operation_kind,context_mode)
 SELECT a.id,p.id,:'operation_kind',:'context_mode' FROM catalog.applications a
 JOIN security.permissions p ON p.application_id=a.id WHERE a.id=:'application_id'::uuid AND p.id=:'permission_id'::uuid
 AND a.status='PUBLISHED' AND p.status='PUBLISHED' AND p.resource_scope IN ('TENANT','BRANCH')
 RETURNING permission_id \gset
-- Exactly one row required. A duplicate fails; no silent adoption.
COMMIT;

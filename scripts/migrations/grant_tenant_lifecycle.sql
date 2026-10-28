-- Controlled installation capability, not a public administration operation.
-- The authorized installation operator must approve the specific existing SUPERADMIN designation.
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
\if :{?designation_id}
\else
  DO $$ BEGIN RAISE EXCEPTION 'Existing designation selector required'; END $$;
\endif
\if :{?capability}
\else
 DO $$ BEGIN RAISE EXCEPTION 'Specific READ/ACTIVATE/SUSPEND/REACTIVATE capability required'; END $$;
\endif
BEGIN;
SELECT pg_advisory_xact_lock(370019,1);
DO $$ BEGIN
    IF session_user<>'postgres' OR current_user<>session_user OR current_database()<>'ls_core_dev'
       OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
       OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999 THEN
        RAISE EXCEPTION 'Expected controlled local PG17 installation operator';
    END IF;
END $$;
INSERT INTO platform.tenant_lifecycle_grants(designation_id,capability)
    SELECT a.id,:'capability' FROM platform.administrators a JOIN identity.users u ON u.id=a.user_id
    WHERE a.id=:'designation_id'::uuid AND a.administrator_kind='SUPERADMIN' AND a.status='ACTIVE'
      AND a.revoked_at IS NULL AND u.status='ACTIVE';
-- Do not silently confirm a missing/inactive selector.
SELECT count(*)=1 AS granted FROM platform.tenant_lifecycle_grants WHERE designation_id=:'designation_id'::uuid AND capability=:'capability' \gset
\if :granted
\else
  DO $$ BEGIN RAISE EXCEPTION 'No active delegated designation selected'; END $$;
\endif
INSERT INTO audit.events(occurred_at,actor_kind,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
    VALUES(clock_timestamp(),'INSTALLATION_OPERATOR','TENANT_LIFECYCLE_CAPABILITY_INSTALLED','PLATFORM_DESIGNATION',
           :'designation_id'::uuid,'SUCCESS',gen_random_uuid(),jsonb_build_object('capability',:'capability'));
COMMIT;

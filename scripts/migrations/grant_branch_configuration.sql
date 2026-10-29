-- Explicit controlled installation capability; never an HTTP endpoint or automatic module entitlement.
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
 DO $$ BEGIN RAISE EXCEPTION 'Existing designation required'; END $$;
\endif
\if :{?application_id}
\else
 DO $$ BEGIN RAISE EXCEPTION 'Existing application required'; END $$;
\endif
\if :{?module_id}
\else
 DO $$ BEGIN RAISE EXCEPTION 'Existing module required'; END $$;
\endif
\if :{?capability}
\else
 DO $$ BEGIN RAISE EXCEPTION 'Explicit READ or MANAGE required'; END $$;
\endif
BEGIN;
SELECT pg_advisory_xact_lock(370019,1);
SELECT pg_advisory_xact_lock(410023,1);
DO $$ BEGIN
 IF session_user<>'postgres' OR current_user<>session_user OR current_database()<>'ls_core_dev'
 OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
 OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999 THEN
 RAISE EXCEPTION 'Controlled local PG17 installation required'; END IF;
END $$;
SELECT EXISTS(SELECT FROM catalog.branch_configuration_definitions d JOIN catalog.modules m ON m.id=d.module_id AND m.application_id=d.application_id
 JOIN catalog.applications a ON a.id=m.application_id WHERE a.id=:'application_id'::uuid AND m.id=:'module_id'::uuid AND d.status='PUBLISHED' AND m.status='PUBLISHED' AND a.status='PUBLISHED') AS applicable \gset
\if :applicable
\else
 DO $$ BEGIN RAISE EXCEPTION 'No published approved configuration contract'; END $$;
\endif
\if :{?tenant_id}
 INSERT INTO tenant.branch_configuration_grants(tenant_id,designation_id,application_id,module_id,capability)
 SELECT a.tenant_id,a.id,:'application_id'::uuid,:'module_id'::uuid,:'capability'
 FROM tenant.valid_administrators(:'tenant_id'::uuid) v JOIN tenant.administrators a ON a.id=v.id AND a.tenant_id=:'tenant_id'::uuid
 WHERE a.id=:'designation_id'::uuid;
 SELECT EXISTS(SELECT FROM tenant.branch_configuration_grants WHERE tenant_id=:'tenant_id'::uuid AND designation_id=:'designation_id'::uuid AND application_id=:'application_id'::uuid AND module_id=:'module_id'::uuid AND capability=:'capability') AS granted \gset
\else
 INSERT INTO platform.branch_configuration_grants(designation_id,application_id,module_id,capability)
 SELECT a.id,:'application_id'::uuid,:'module_id'::uuid,:'capability' FROM platform.administrators a JOIN identity.users u ON u.id=a.user_id
 WHERE a.id=:'designation_id'::uuid AND a.administrator_kind='SUPERADMIN' AND a.status='ACTIVE' AND a.revoked_at IS NULL AND u.status='ACTIVE';
 SELECT EXISTS(SELECT FROM platform.branch_configuration_grants WHERE designation_id=:'designation_id'::uuid AND application_id=:'application_id'::uuid AND module_id=:'module_id'::uuid AND capability=:'capability') AS granted \gset
\endif
\if :granted
\else
 DO $$ BEGIN RAISE EXCEPTION 'No current designation selected'; END $$;
\endif
INSERT INTO audit.events(occurred_at,actor_kind,action_code,resource_type,resource_id,outcome,correlation_id,metadata)
 VALUES(clock_timestamp(),'INSTALLATION_OPERATOR','BRANCH_CONFIGURATION_CAPABILITY_INSTALLED','MODULE',:'module_id'::uuid,'SUCCESS',gen_random_uuid(),
 jsonb_build_object('designation_id',:'designation_id'::uuid,'application_id',:'application_id'::uuid,'capability',:'capability'));
COMMIT;

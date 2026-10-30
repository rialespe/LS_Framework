-- EXPLICIT DISPOSABLE FIXTURE LFD-45 ONLY. Never run on a shared database.
DO $$ DECLARE company uuid='__TENANT__'; actor uuid; member_id uuid; app uuid; mod uuid;
BEGIN
 IF current_user<>'postgres' OR session_user<>current_user OR inet_server_addr()<>'127.0.0.1'::inet
 OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999 THEN RAISE EXCEPTION 'Owned fixture required'; END IF;
 SELECT r.actor_user_id INTO STRICT actor FROM tenant.initial_registrations r JOIN tenant.tenants t ON t.id=r.tenant_id
 JOIN platform.administrators a ON a.user_id=r.actor_user_id WHERE r.tenant_id=company AND t.status='ACTIVE'
 AND a.administrator_kind='OWNER' AND a.status='ACTIVE' AND a.revoked_at IS NULL;
 IF EXISTS(SELECT FROM security.branch_operation_grants WHERE tenant_id=company) THEN RAISE EXCEPTION 'Fixture already installed'; END IF;
 SELECT id INTO STRICT app FROM catalog.applications WHERE code='ls-core' AND status='PUBLISHED';
 SELECT id INTO STRICT mod FROM catalog.modules WHERE application_id=app AND code='branches' AND status='PUBLISHED';
 SELECT id INTO member_id FROM tenant.memberships WHERE tenant_id=company AND user_id=actor AND status='ACTIVE';
 IF member_id IS NULL THEN
 INSERT INTO tenant.memberships(tenant_id,user_id,status) VALUES(company,actor,'ACTIVE') RETURNING id INTO member_id;
 END IF;
 INSERT INTO tenant.branch_memberships(tenant_id,membership_id,branch_id,status)
 SELECT company,member_id,b.id,'ACTIVE' FROM tenant.branches b WHERE b.tenant_id=company
 AND NOT EXISTS(SELECT FROM tenant.branch_memberships bm WHERE bm.tenant_id=company AND bm.membership_id=member_id AND bm.branch_id=b.id);
 INSERT INTO catalog.tenant_applications(tenant_id,application_id,status) SELECT company,app,'ACTIVE'
 WHERE NOT EXISTS(SELECT FROM catalog.tenant_applications WHERE tenant_id=company AND application_id=app);
 INSERT INTO catalog.tenant_modules(tenant_id,application_id,module_id,status) SELECT company,app,mod,'ACTIVE'
 WHERE NOT EXISTS(SELECT FROM catalog.tenant_modules WHERE tenant_id=company AND application_id=app AND module_id=mod);
 INSERT INTO security.branch_operation_grants(tenant_id,membership_id,branch_id,application_id,module_id,capability,expires_at)
 SELECT company,member_id,b.id,app,mod,'HISTORY_READ',clock_timestamp()+interval '1 hour' FROM tenant.branches b WHERE b.tenant_id=company;
 INSERT INTO security.branch_multihistory_grants VALUES(company,member_id,clock_timestamp()+interval '1 hour');
END $$;

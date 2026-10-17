"""LFD-26 literal PostgreSQL contracts and rolled-back synthetic cases.

No connection settings: only the existing disposable harness supplies a cluster.
Expectations are explicit and are never derived from the SQL being tested.
"""

CANDIDATES = """tenant.memberships|memberships_tenant_id_uq|tenant_id,id
tenant.memberships|memberships_tenant_user_id_uq|tenant_id,id,user_id
tenant.branches|branches_tenant_id_uq|tenant_id,id
tenant.branch_memberships|branch_memberships_tenant_id_uq|tenant_id,id
tenant.branch_memberships|branch_memberships_scope_uq|tenant_id,membership_id,branch_id
catalog.tenant_applications|tenant_applications_scope_uq|tenant_id,application_id
catalog.modules|modules_application_id_uq|application_id,id
security.permissions|permissions_application_id_uq|application_id,id
security.role_templates|role_templates_application_id_uq|application_id,id
security.profile_templates|profile_templates_application_id_uq|application_id,id
security.roles|roles_scope_id_uq|tenant_id,application_id,id
security.profiles|profiles_scope_id_uq|tenant_id,application_id,id
security.sessions|sessions_user_application_uq|id,user_id,application_id
integration.api_keys|api_keys_scope_id_uq|tenant_id,application_id,id"""

COMPOSITES = """tenant.branch_memberships|branch_memberships_membership_scope_fk|tenant_id,membership_id|tenant.memberships|tenant_id,id|
tenant.branch_memberships|branch_memberships_branch_scope_fk|tenant_id,branch_id|tenant.branches|tenant_id,id|
tenant.administrators|administrators_membership_scope_fk|tenant_id,membership_id|tenant.memberships|tenant_id,id|
catalog.tenant_modules|tenant_modules_entitlement_fk|tenant_id,application_id|catalog.tenant_applications|tenant_id,application_id|
catalog.tenant_modules|tenant_modules_module_scope_fk|application_id,module_id|catalog.modules|application_id,id|tenant_modules_module_id_fk
security.permissions|permissions_module_scope_fk|application_id,module_id|catalog.modules|application_id,id|permissions_module_id_fk
security.role_template_permissions|role_template_permissions_template_scope_fk|application_id,role_template_id|security.role_templates|application_id,id|role_template_permissions_role_template_id_fk
security.role_template_permissions|role_template_permissions_permission_scope_fk|application_id,permission_id|security.permissions|application_id,id|role_template_permissions_permission_id_fk
security.profile_template_roles|profile_template_roles_profile_scope_fk|application_id,profile_template_id|security.profile_templates|application_id,id|profile_template_roles_profile_template_id_fk
security.profile_template_roles|profile_template_roles_role_scope_fk|application_id,role_template_id|security.role_templates|application_id,id|profile_template_roles_role_template_id_fk
security.roles|roles_entitlement_fk|tenant_id,application_id|catalog.tenant_applications|tenant_id,application_id|
security.roles|roles_source_template_scope_fk|application_id,source_role_template_id|security.role_templates|application_id,id|roles_source_role_template_id_fk
security.profiles|profiles_entitlement_fk|tenant_id,application_id|catalog.tenant_applications|tenant_id,application_id|
security.profiles|profiles_source_template_scope_fk|application_id,source_profile_template_id|security.profile_templates|application_id,id|profiles_source_profile_template_id_fk
security.role_permissions|role_permissions_role_scope_fk|tenant_id,application_id,role_id|security.roles|tenant_id,application_id,id|
security.role_permissions|role_permissions_permission_scope_fk|application_id,permission_id|security.permissions|application_id,id|role_permissions_permission_id_fk
security.profile_roles|profile_roles_profile_scope_fk|tenant_id,application_id,profile_id|security.profiles|tenant_id,application_id,id|
security.profile_roles|profile_roles_role_scope_fk|tenant_id,application_id,role_id|security.roles|tenant_id,application_id,id|
security.branch_profile_assignments|branch_profile_assignments_access_scope_fk|tenant_id,branch_membership_id|tenant.branch_memberships|tenant_id,id|
security.branch_profile_assignments|branch_profile_assignments_profile_scope_fk|tenant_id,application_id,profile_id|security.profiles|tenant_id,application_id,id|
security.session_contexts|session_contexts_session_scope_fk|session_id,user_id,application_id|security.sessions|id,user_id,application_id|
security.session_contexts|session_contexts_membership_scope_fk|tenant_id,membership_id,user_id|tenant.memberships|tenant_id,id,user_id|
security.session_contexts|session_contexts_branch_scope_fk|tenant_id,branch_id|tenant.branches|tenant_id,id|
security.session_contexts|session_contexts_access_scope_fk|tenant_id,membership_id,branch_id|tenant.branch_memberships|tenant_id,membership_id,branch_id|
security.session_contexts|session_contexts_entitlement_fk|tenant_id,application_id|catalog.tenant_applications|tenant_id,application_id|
integration.api_keys|api_keys_entitlement_fk|tenant_id,application_id|catalog.tenant_applications|tenant_id,application_id|
integration.api_keys|api_keys_branch_scope_fk|tenant_id,branch_id|tenant.branches|tenant_id,id|
integration.api_key_permissions|api_key_permissions_key_scope_fk|tenant_id,application_id,api_key_id|integration.api_keys|tenant_id,application_id,id|
integration.api_key_permissions|api_key_permissions_permission_scope_fk|application_id,permission_id|security.permissions|application_id,id|api_key_permissions_permission_id_fk"""

BUSINESS_UNIQUES = """tenant.memberships|memberships_tenant_user_uq|tenant_id,user_id
catalog.tenant_modules|tenant_modules_scope_uq|tenant_id,application_id,module_id"""

NORMALIZED_INDEXES = """identity.users|users_username_normalized_uq|lower(btrim(username))|username IS NOT NULL
identity.users|users_verified_email_normalized_uq|lower(btrim(email))|email_verified_at IS NOT NULL
tenant.branches|branches_tenant_code_normalized_uq|tenant_id,lower(btrim(code))|
catalog.applications|applications_code_normalized_uq|lower(btrim(code))|
catalog.modules|modules_application_code_normalized_uq|application_id,lower(btrim(code))|
security.permissions|permissions_application_code_normalized_uq|application_id,lower(btrim(code))|
security.role_templates|role_templates_code_version_normalized_uq|application_id,lower(btrim(code)),version|
security.profile_templates|profile_templates_code_version_normalized_uq|application_id,lower(btrim(code)),version|
security.roles|roles_tenant_application_code_normalized_uq|tenant_id,application_id,lower(btrim(code))|
security.profiles|profiles_tenant_application_code_normalized_uq|tenant_id,application_id,lower(btrim(code))|"""

SUPPORT_INDEXES = """tenant.branch_memberships|branch_memberships_branch_scope_idx|tenant_id,branch_id
tenant.administrators|administrators_membership_scope_idx|tenant_id,membership_id
catalog.tenant_modules|tenant_modules_module_scope_idx|application_id,module_id
security.permissions|permissions_module_scope_idx|application_id,module_id
security.role_template_permissions|role_template_permissions_permission_idx|permission_id,application_id
security.profile_template_roles|profile_template_roles_role_idx|role_template_id,application_id
security.roles|roles_source_template_scope_idx|source_role_template_id,application_id
security.profiles|profiles_source_template_scope_idx|source_profile_template_id,application_id
security.role_permissions|role_permissions_permission_scope_idx|permission_id,application_id
security.profile_roles|profile_roles_role_scope_idx|tenant_id,application_id,role_id
security.branch_profile_assignments|branch_profile_assignments_access_scope_idx|tenant_id,branch_membership_id
security.branch_profile_assignments|branch_profile_assignments_profile_scope_idx|tenant_id,application_id,profile_id
security.session_contexts|session_contexts_session_scope_idx|session_id,user_id,application_id
security.session_contexts|session_contexts_access_scope_idx|tenant_id,membership_id,branch_id
security.session_contexts|session_contexts_branch_scope_idx|tenant_id,branch_id
security.session_contexts|session_contexts_entitlement_idx|tenant_id,application_id
integration.api_keys|api_keys_branch_scope_idx|tenant_id,branch_id
integration.api_key_permissions|api_key_permissions_permission_scope_idx|permission_id,application_id"""

NEW_CHECKS = """identity.users|users_username_nonblank_ck|username IS NULL OR btrim(username) <> ''
identity.users|users_email_nonblank_ck|email IS NULL OR btrim(email) <> ''
identity.users|users_verified_email_ck|email_verified_at IS NULL OR (email IS NOT NULL AND btrim(email) <> '')
tenant.branches|branches_code_nonblank_ck|btrim(code) <> ''
catalog.applications|applications_code_nonblank_ck|btrim(code) <> ''
catalog.modules|modules_code_nonblank_ck|btrim(code) <> ''
security.permissions|permissions_code_nonblank_ck|btrim(code) <> ''
security.role_templates|role_templates_code_nonblank_ck|btrim(code) <> ''
security.profile_templates|profile_templates_code_nonblank_ck|btrim(code) <> ''
security.roles|roles_code_nonblank_ck|btrim(code) <> ''
security.profiles|profiles_code_nonblank_ck|btrim(code) <> ''"""


def entries(spec):
    return [line.split('|') for line in spec.splitlines()]


def compact(expression):
    import re
    return re.sub(r"[\s()\"]", '', expression.replace('::text', '')).lower()


def integrity_snapshot(cluster):
    """Include constraints and index definitions, not only columns, in repeat/rollback checks."""
    return cluster.query(
        "SELECT json_build_object('constraints',(SELECT json_agg(x ORDER BY x.table_name,x.name) FROM "
        "(SELECT n.nspname||'.'||t.relname AS table_name,c.conname AS name,pg_get_constraintdef(c.oid) AS definition "
        "FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid "
        "JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname IN "
        "('identity','platform','tenant','security','catalog','integration','audit','operations')) x),"
        "'indexes',(SELECT json_agg(x ORDER BY x.table_name,x.definition) FROM "
        "(SELECT n.nspname||'.'||t.relname AS table_name,pg_get_indexdef(i.indexrelid) AS definition "
        "FROM pg_index i JOIN pg_class t ON t.oid=i.indrelid JOIN pg_namespace n ON n.oid=t.relnamespace "
        "WHERE n.nspname IN ('identity','platform','tenant','security','catalog','integration','audit','operations')) x))")


def test_integrity_structure(cluster, check, model):
    import json
    expected_unique = {(table, name): columns.split(',')
                       for table, name, columns in entries(CANDIDATES) + entries(BUSINESS_UNIQUES)}
    schema_list = "'identity','platform','tenant','security','catalog','integration','audit','operations'"
    uniques = json.loads(cluster.query(
        "SELECT json_agg(row_to_json(x)) FROM (SELECT n.nspname||'.'||t.relname AS table_name,c.conname,"
        "ARRAY(SELECT a.attname FROM unnest(c.conkey) WITH ORDINALITY k(num,pos) JOIN pg_attribute a "
        "ON a.attrelid=t.oid AND a.attnum=k.num ORDER BY k.pos) AS columns,"
        "c.convalidated,c.condeferrable FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid "
        "JOIN pg_namespace n ON n.oid=t.relnamespace WHERE c.contype='u' AND n.nspname IN ("
        + schema_list + ") AND t.relname<>'flyway_schema_history') x"))
    actual_unique = {(row['table_name'], row['conname']): row['columns'] for row in uniques}
    check(actual_unique == expected_unique and all(r['convalidated'] and not r['condeferrable'] for r in uniques),
          'LFD-26: exact 14 candidate and 2 natural UNIQUE constraints, ordered columns and immediate validation')
    checks = json.loads(cluster.query(
        "SELECT json_agg(row_to_json(x)) FROM (SELECT n.nspname||'.'||t.relname AS table_name,c.conname,"
        "pg_get_constraintdef(c.oid) AS definition,c.convalidated FROM pg_constraint c "
        "JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace "
        "WHERE c.contype='c' AND n.nspname IN (" + schema_list + ")) x"))
    for table, name, expression in entries(NEW_CHECKS):
        row = next((r for r in checks if (r['table_name'], r['conname']) == (table, name)), None)
        check(row is not None and row['convalidated'] and compact(row['definition']) == compact('CHECK (' + expression + ')'),
              'LFD-26: exact validated presence/coherence CHECK ' + name)
    expected_indexes = {}
    for table, (_, columns) in model.items():
        expected_indexes[(table, table.split('.')[1] + '_pk')] = (True, True, [compact(c) for c in columns], '')
    for table, name, columns in entries(CANDIDATES) + entries(BUSINESS_UNIQUES):
        expected_indexes[(table, name)] = (True, False, [compact(c) for c in columns.split(',')], '')
    for table, name, expressions, predicate in entries(NORMALIZED_INDEXES):
        expected_indexes[(table, name)] = (True, False, [compact(c) for c in expressions.split(',')], compact(predicate))
    for table, name, columns in entries(SUPPORT_INDEXES):
        expected_indexes[(table, name)] = (False, False, [compact(c) for c in columns.split(',')], '')
    indexes = json.loads(cluster.query(
        "SELECT json_agg(row_to_json(x)) FROM (SELECT n.nspname||'.'||t.relname AS table_name,ci.relname AS name,"
        "i.indisunique,i.indisprimary,i.indisvalid,i.indisready,am.amname,"
        "ARRAY(SELECT pg_get_indexdef(i.indexrelid,k,true) FROM generate_series(1,i.indnkeyatts) k) AS expressions,"
        "coalesce(pg_get_expr(i.indpred,i.indrelid),'') AS predicate FROM pg_index i "
        "JOIN pg_class t ON t.oid=i.indrelid JOIN pg_namespace n ON n.oid=t.relnamespace "
        "JOIN pg_class ci ON ci.oid=i.indexrelid JOIN pg_am am ON am.oid=ci.relam "
        "WHERE n.nspname IN (" + schema_list + ") AND t.relname<>'flyway_schema_history') x"))
    actual_indexes = {(r['table_name'], r['name']): (r['indisunique'], r['indisprimary'],
                       [compact(c) for c in r['expressions']], compact(r['predicate'])) for r in indexes}
    check(actual_indexes == expected_indexes and all(r['indisvalid'] and r['indisready'] and r['amname']=='btree' for r in indexes),
          'LFD-26: exact 73 valid B-tree indexes: 29 PK + 16 UNIQUE + 10 normalized + 18 support')
    check(len({(r['table_name'], tuple(r['expressions']), r['predicate'], r['indisunique']) for r in indexes}) == len(indexes),
          'LFD-26: no identical duplicate indexes')
    check(all(len(r['name'].encode('utf-8')) <= 63 for r in indexes), 'LFD-26: all index names fit 63 bytes')
    check(cluster.query("SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid "
                        "JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname IN (" + schema_list
                        + ") AND c.contype='f' AND (NOT c.convalidated OR c.condeferrable OR "
                        "c.confmatchtype<>'s' OR c.confdeltype<>'a' OR c.confupdtype<>'a')") == '0',
          'LFD-26: all FK validated, immediate MATCH SIMPLE and NO ACTION; no cascades')


class SQL(str):
    """Trusted constant expressions in synthetic tests, never external input."""


NOW = SQL('CURRENT_TIMESTAMP')
LATER = SQL("CURRENT_TIMESTAMP + INTERVAL '1 hour'")


def uid(number):
    return SQL("'00000000-0000-0000-0000-" + str(number).zfill(12) + "'")


def literal(value):
    if isinstance(value, SQL):
        return value
    if value is None:
        return 'NULL'
    if isinstance(value, bool):
        return 'true' if value else 'false'
    if isinstance(value, int):
        return str(value)
    return "'" + value.replace("'", "''") + "'"


def insert(table, values):
    return ('INSERT INTO ' + table + ' (' + ','.join(values) + ') VALUES ('
            + ','.join(literal(v) for v in values.values()) + ')')


def fixtures():
    """Two tenants, two applications and three branches, all inside one rolled-back transaction."""
    rows = {}

    def add(alias, table, **values):
        rows[alias] = (table, values)

    add('u', 'identity.users', id=uid(1), username='fixture-u', email='u@fixture.invalid',
        display_name='U', status='TEST')
    add('v', 'identity.users', id=uid(2), username='fixture-v', email='v@fixture.invalid',
        display_name='V', status='TEST')
    add('a', 'tenant.tenants', id=uid(10), name='Tenant A', status='ACTIVE')
    add('b', 'tenant.tenants', id=uid(11), name='Tenant B', status='ACTIVE')
    add('x', 'catalog.applications', id=uid(20), code='app-x', name='App X', status='TEST')
    add('y', 'catalog.applications', id=uid(21), code='app-y', name='App Y', status='TEST')
    add('mx', 'catalog.modules', id=uid(30), application_id=uid(20), code='shared',
        name='Module X', status='TEST')
    add('my', 'catalog.modules', id=uid(31), application_id=uid(21), code='shared',
        name='Module Y', status='TEST')
    add('px', 'security.permissions', id=uid(40), application_id=uid(20), module_id=uid(30),
        code='shared', name='Permission X', resource_scope='UNDEFINED', status='TEST')
    add('py', 'security.permissions', id=uid(41), application_id=uid(21), module_id=uid(31),
        code='shared', name='Permission Y', resource_scope='UNDEFINED', status='TEST')
    add('rtx', 'security.role_templates', id=uid(50), application_id=uid(20), code='shared',
        version=1, name='Role template X', status='TEST')
    add('rty', 'security.role_templates', id=uid(51), application_id=uid(21), code='shared',
        version=1, name='Role template Y', status='TEST')
    add('rtpx', 'security.role_template_permissions', application_id=uid(20),
        role_template_id=uid(50), permission_id=uid(40))
    add('ptx', 'security.profile_templates', id=uid(60), application_id=uid(20), code='shared',
        version=1, name='Profile template X', status='TEST')
    add('pty', 'security.profile_templates', id=uid(61), application_id=uid(21), code='shared',
        version=1, name='Profile template Y', status='TEST')
    add('ptrx', 'security.profile_template_roles', application_id=uid(20),
        profile_template_id=uid(60), role_template_id=uid(50))
    add('a1', 'tenant.branches', id=uid(70), tenant_id=uid(10), code='MAIN', name='A1', status='TEST')
    add('a2', 'tenant.branches', id=uid(71), tenant_id=uid(10), code='SECOND', name='A2', status='TEST')
    add('b1', 'tenant.branches', id=uid(72), tenant_id=uid(11), code='MAIN', name='B1', status='TEST')
    add('au', 'tenant.memberships', id=uid(80), tenant_id=uid(10), user_id=uid(1), status='TEST')
    add('av', 'tenant.memberships', id=uid(81), tenant_id=uid(10), user_id=uid(2), status='TEST')
    add('bu', 'tenant.memberships', id=uid(82), tenant_id=uid(11), user_id=uid(1), status='TEST')
    add('aua1', 'tenant.branch_memberships', id=uid(90), tenant_id=uid(10),
        membership_id=uid(80), branch_id=uid(70), status='TEST')
    add('bub1', 'tenant.branch_memberships', id=uid(91), tenant_id=uid(11),
        membership_id=uid(82), branch_id=uid(72), status='TEST')
    add('admin', 'tenant.administrators', id=uid(100), tenant_id=uid(10),
        membership_id=uid(80), status='TEST', granted_at=NOW)
    add('ax', 'catalog.tenant_applications', id=uid(110), tenant_id=uid(10),
        application_id=uid(20), status='ACTIVE')
    add('ay', 'catalog.tenant_applications', id=uid(111), tenant_id=uid(10),
        application_id=uid(21), status='ACTIVE')
    add('bx', 'catalog.tenant_applications', id=uid(112), tenant_id=uid(11),
        application_id=uid(20), status='ACTIVE')
    add('amx', 'catalog.tenant_modules', id=uid(120), tenant_id=uid(10), application_id=uid(20),
        module_id=uid(30), status='ACTIVE')
    add('rax', 'security.roles', id=uid(130), tenant_id=uid(10), application_id=uid(20),
        code='shared', name='Role AX', status='TEST', source_role_template_id=uid(50))
    add('rbx', 'security.roles', id=uid(131), tenant_id=uid(11), application_id=uid(20),
        code='shared', name='Role BX', status='TEST', source_role_template_id=uid(50))
    add('ray', 'security.roles', id=uid(132), tenant_id=uid(10), application_id=uid(21),
        code='shared', name='Role AY', status='TEST', source_role_template_id=uid(51))
    add('rpx', 'security.role_permissions', tenant_id=uid(10), application_id=uid(20),
        role_id=uid(130), permission_id=uid(40))
    add('pax', 'security.profiles', id=uid(140), tenant_id=uid(10), application_id=uid(20),
        code='shared', name='Profile AX', status='TEST', source_profile_template_id=uid(60))
    add('pbx', 'security.profiles', id=uid(141), tenant_id=uid(11), application_id=uid(20),
        code='shared', name='Profile BX', status='TEST', source_profile_template_id=uid(60))
    add('pay', 'security.profiles', id=uid(142), tenant_id=uid(10), application_id=uid(21),
        code='shared', name='Profile AY', status='TEST', source_profile_template_id=uid(61))
    add('prx', 'security.profile_roles', tenant_id=uid(10), application_id=uid(20),
        profile_id=uid(140), role_id=uid(130))
    add('assign', 'security.branch_profile_assignments', id=uid(150), tenant_id=uid(10),
        application_id=uid(20), branch_membership_id=uid(90), profile_id=uid(140), status='TEST')
    add('sux', 'security.sessions', id=uid(160), user_id=uid(1), application_id=uid(20),
        token_hash='non-authenticating-test-verifier', expires_at=LATER)
    add('svx', 'security.sessions', id=uid(161), user_id=uid(2), application_id=uid(20),
        token_hash='non-authenticating-test-verifier', expires_at=LATER)
    add('suy', 'security.sessions', id=uid(162), user_id=uid(1), application_id=uid(21),
        token_hash='non-authenticating-test-verifier', expires_at=LATER)
    add('context', 'security.session_contexts', id=uid(170), session_id=uid(160), user_id=uid(1),
        application_id=uid(20), tenant_id=uid(10), membership_id=uid(80), branch_id=uid(70))
    add('keyax', 'integration.api_keys', id=uid(180), tenant_id=uid(10), application_id=uid(20),
        branch_id=uid(70), name='Key AX', key_hash='non-authenticating-test-verifier',
        created_by_user_id=uid(1), expires_at=LATER)
    add('keybx', 'integration.api_keys', id=uid(181), tenant_id=uid(11), application_id=uid(20),
        branch_id=uid(72), name='Key BX', key_hash='non-authenticating-test-verifier',
        created_by_user_id=uid(1), expires_at=LATER)
    add('keypx', 'integration.api_key_permissions', tenant_id=uid(10), application_id=uid(20),
        api_key_id=uid(180), permission_id=uid(40))
    return rows


def test_integrity_values(cluster, check):
    rows = fixtures()

    def clone(alias, **changes):
        table, base = rows[alias]
        values = {k: v for k, v in base.items() if k != 'id'}
        if 'code' in values:
            values['code'] += '-probe'
        values.update(changes)
        return insert(table, values)

    cases = []

    def negative(label, sql, state='23503'):
        cases.append((label, sql, state))

    # Every composite relationship is exercised by the valid fixture above and a scoped violation below.
    negative('branch membership / foreign membership', clone('aua1', membership_id=uid(82)))
    negative('branch membership / foreign branch', clone('aua1', branch_id=uid(72)))
    negative('tenant administrator / foreign membership', clone('admin', membership_id=uid(82)))
    negative('module entitlement / missing tenant application', clone('amx', tenant_id=uid(11),
                                                                      application_id=uid(21), module_id=uid(31)))
    negative('module entitlement / wrong application module', clone('amx', application_id=uid(21)))
    negative('permission / wrong application module', clone('px', module_id=uid(31)))
    negative('role template / wrong application template', clone('rtpx', role_template_id=uid(51)))
    negative('role template / wrong application permission', clone('rtpx', permission_id=uid(41)))
    negative('profile template / wrong application profile', clone('ptrx', profile_template_id=uid(61)))
    negative('profile template / wrong application role', clone('ptrx', role_template_id=uid(51)))
    negative('role / missing entitlement', clone('ray', tenant_id=uid(11)))
    negative('role / wrong application source', clone('rax', source_role_template_id=uid(51)))
    negative('profile / missing entitlement', clone('pay', tenant_id=uid(11)))
    negative('profile / wrong application source', clone('pax', source_profile_template_id=uid(61)))
    negative('role permission / foreign tenant role', clone('rpx', role_id=uid(131)))
    negative('role permission / same tenant wrong application role', clone('rpx', role_id=uid(132)))
    negative('role permission / wrong application permission', clone('rpx', permission_id=uid(41)))
    negative('profile role / foreign tenant profile', clone('prx', profile_id=uid(141)))
    negative('profile role / foreign tenant role', clone('prx', role_id=uid(131)))
    negative('profile role / same tenant wrong application profile', clone('prx', profile_id=uid(142)))
    negative('profile role / same tenant wrong application role', clone('prx', role_id=uid(132)))
    negative('branch assignment / foreign tenant access', clone('assign', branch_membership_id=uid(91)))
    negative('branch assignment / foreign tenant profile', clone('assign', profile_id=uid(141)))
    negative('branch assignment / same tenant wrong application profile', clone('assign', profile_id=uid(142)))
    negative('context / session of another user', clone('context', session_id=uid(161)))
    negative('context / session of another application', clone('context', session_id=uid(162)))
    negative('context / foreign tenant membership', clone('context', membership_id=uid(82)))
    negative('context / membership of another user', clone('context', membership_id=uid(81)))
    negative('context / foreign branch', clone('context', branch_id=uid(72)))
    negative('context / branch without membership access', clone('context', branch_id=uid(71)))
    negative('context / missing application entitlement', clone('context', session_id=uid(162),
             application_id=uid(21), tenant_id=uid(11), membership_id=uid(82), branch_id=uid(72)))
    negative('API key / missing application entitlement', clone('keyax', tenant_id=uid(11),
                                                               application_id=uid(21), branch_id=None))
    negative('API key / foreign tenant branch', clone('keyax', branch_id=uid(72)))
    negative('API permission / foreign tenant key', clone('keypx', api_key_id=uid(181)))
    negative('API permission / wrong application permission', clone('keypx', permission_id=uid(41)))
    negative('API permission / same tenant wrong application key',
             clone('keypx', application_id=uid(21), permission_id=uid(41)))
    # Optional NULL bypass is legitimate; non-null unknown UUID must not bypass the composite FK.
    for alias, field in [('px', 'module_id'), ('rax', 'source_role_template_id'),
                         ('pax', 'source_profile_template_id'), ('keyax', 'branch_id')]:
        negative('optional non-null invalid reference / ' + field, clone(alias, **{field: uid(999)}))
    # UPDATE and referenced-parent mutation are covered in addition to INSERT violations.
    negative('update / session identity', 'UPDATE security.session_contexts SET user_id=' + uid(2))
    negative('update / foreign branch', 'UPDATE integration.api_keys SET branch_id=' + uid(72)
             + ' WHERE id=' + uid(180))
    negative('parent update / membership user', 'UPDATE tenant.memberships SET user_id=' + uid(200)
             + ' WHERE id=' + uid(80))
    negative('NO ACTION / delete referenced branch', 'DELETE FROM tenant.branches WHERE id=' + uid(70))
    negative('NO ACTION / delete referenced permission', 'DELETE FROM security.permissions WHERE id=' + uid(40))
    # Uniqueness errors are distinguished from FK and CHECK errors using SQLSTATE.
    negative('username case and trim', clone('u', username=' FIXTURE-U ', email=None), '23505')
    for alias in ['a1', 'x', 'mx', 'px', 'rtx', 'ptx', 'rax', 'pax']:
        negative('normalized scoped code / ' + rows[alias][0],
                 clone(alias, code=' ' + rows[alias][1]['code'].upper() + ' '), '23505')
        negative('blank code / ' + rows[alias][0], clone(alias, code='   '), '23514')
    for alias in ['au', 'ax', 'amx', 'aua1', 'rtpx', 'ptrx', 'rpx', 'prx', 'keypx']:
        table, base = rows[alias]
        negative('duplicate natural association / ' + table,
                 insert(table, {k: v for k, v in base.items() if k != 'id'}), '23505')
    for username, email in [(None, None), ('', None), ('   ', None), (None, ''), (None, '   ')]:
        negative('missing or blank identity ' + str((username, email)),
                 clone('u', username=username, email=email), '23514')
    negative('verification without email', clone('u', username='new-user', email=None,
                                                email_verified_at=NOW), '23514')
    negative('verification with blank email', clone('u', username='new-user', email='  ',
                                                   email_verified_at=NOW), '23514')
    negative('verified email case and trim', clone('u', username=None, email=' VERIFIED@FIXTURE.INVALID ',
                                                  email_verified_at=NOW), '23505')
    negative('verification of second unverified duplicate',
             "UPDATE identity.users SET email_verified_at=CURRENT_TIMESTAMP WHERE id=" + uid(202), '23505')
    # The same username/normalized code cannot be made conflicting by an UPDATE either.
    negative('update normalized username', "UPDATE identity.users SET username=' FIXTURE-U ' WHERE id=" + uid(2),
             '23505')
    positives = [
        ('username only', clone('u', username='new-user', email=None)),
        ('email only', clone('u', username=None, email='new@fixture.invalid')),
        ('both identity fields', clone('u', username='new-user', email='new@fixture.invalid')),
        ('unverified duplicate email', clone('u', username=None, email='verified@fixture.invalid')),
        ('another tenant same branch code', clone('a1', tenant_id=uid(11), code='SECOND')),
        ('same user another tenant', clone('av', tenant_id=uid(11))),
        ('same module enabled in another tenant', clone('amx', tenant_id=uid(11))),
        ('module code another application', clone('mx', application_id=uid(21), code='new-code')),
        ('role template another version', clone('rtx', code='shared', version=2)),
        ('profile template another version', clone('ptx', code='shared', version=2)),
        ('multiple profiles in same branch', clone('assign', profile_id=uid(143))),
        ('valid profile of another application in same branch',
         clone('assign', application_id=uid(21), profile_id=uid(142))),
        ('session for another valid application', clone('context', session_id=uid(162), application_id=uid(21))),
    ]
    for alias, field in [('px', 'module_id'), ('rax', 'source_role_template_id'),
                         ('pax', 'source_profile_template_id'), ('keyax', 'branch_id')]:
        positives.append(('optional NULL / ' + field, clone(alias, **{field: None})))
    # Extra rows establish verified/unverified email behavior and multiple legitimate profiles.
    extra = [
        insert('identity.users', dict(id=uid(200), username=None, email='verified@fixture.invalid',
                                     email_verified_at=NOW, display_name='Verified', status='TEST')),
        insert('identity.users', dict(id=uid(201), username=None, email='duplicate@fixture.invalid',
                                     email_verified_at=NOW, display_name='Verified duplicate', status='TEST')),
        insert('identity.users', dict(id=uid(202), username=None, email=' DUPLICATE@FIXTURE.INVALID ',
                                     display_name='Unverified duplicate', status='TEST')),
        insert('identity.users', dict(id=uid(203), username=None, email='duplicate@fixture.invalid',
                                     display_name='Second unverified duplicate', status='TEST')),
        insert('security.profiles', dict(id=uid(143), tenant_id=uid(10), application_id=uid(20),
                                       code='second-profile', name='Second', status='TEST')),
    ]
    statements = ['BEGIN']
    statements += [insert(table, values) for table, values in rows.values()]
    statements += extra
    for label, sql in positives:
        statements += ['SAVEPOINT valid_case', sql, 'ROLLBACK TO SAVEPOINT valid_case', 'RELEASE SAVEPOINT valid_case']
    for label, sql, state in cases:
        # An unexpected success raises P0001, never swallowed by the expected SQLSTATE handler.
        statements.append("DO $case$ BEGIN BEGIN " + sql + "; RAISE EXCEPTION "
                          + literal('Unexpected success: ' + label)
                          + "; EXCEPTION WHEN SQLSTATE '" + state + "' THEN NULL; END; END $case$")
    # Eligibility only: a tiny fixture is not a production performance benchmark.
    statements += [
        'SET LOCAL enable_seqscan=off',
        'EXPLAIN (COSTS OFF) SELECT 1 FROM tenant.branch_memberships WHERE tenant_id=' + uid(10)
        + ' AND membership_id=' + uid(80) + ' AND branch_id=' + uid(70),
        'EXPLAIN (COSTS OFF) SELECT 1 FROM security.session_contexts WHERE tenant_id=' + uid(10)
        + ' AND membership_id=' + uid(80) + ' AND branch_id=' + uid(70),
    ]
    # Runtime authorization is intentionally not encoded by FK: state changes preserve valid relationships.
    statements += [
        "UPDATE tenant.tenants SET status='SUSPENDED' WHERE id=" + uid(10),
        "UPDATE catalog.tenant_applications SET status='DISABLED' WHERE id=" + uid(110),
        "UPDATE security.sessions SET revoked_at=CURRENT_TIMESTAMP WHERE id=" + uid(160),
        'ROLLBACK',
    ]
    result = cluster.psql(sql=';\n'.join(statements) + ';', user='ls_core_migrator_dev')
    if result.returncode:
        # Static test labels and SQLSTATE only; never print connection diagnostics, row contents or verifiers.
        print('LFD-26 contract failure categories:', [term for term in
              ['23503', '23505', '23514', 'Unexpected success', 'duplicate key', 'violates foreign key',
               'violates check', 'syntax error', 'value too long'] if term in result.stderr], flush=True)
        print('Static failing case labels:', [label for label, _, _ in cases if label in result.stderr], flush=True)
        raise AssertionError('Synthetic integrity batch failed; diagnostics withheld')
    plans = [line.strip() for line in result.stdout.splitlines() if 'Index' in line and (' using ' in line or ' on ' in line)]
    check(len(plans) == 2, 'LFD-26: EXPLAIN proves lookup indexes are eligible on synthetic data')
    for plan in plans:
        print('Synthetic index eligibility (seqscan disabled in test transaction):', plan, flush=True)
    check(True, 'LFD-26: valid fixture exercises all 29 composite FK across two tenants/two applications/three branches')
    for label, _ in positives:
        check(True, 'LFD-26 positive: ' + label)
    for label, _, state in cases:
        check(True, 'LFD-26 rejects SQLSTATE ' + state + ': ' + label)
    check(True, f'LFD-26: {len(positives)} additional positive and {len(cases)} negative cases, always rolled back')
    check(True, 'LFD-26: suspension/disabled/revoked states do not pretend to implement runtime authorization')

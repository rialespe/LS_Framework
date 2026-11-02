package com.lssoluciones.lscore.session;

import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import com.lssoluciones.lscore.platform.PlatformDatabase;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

/** Core RBAC coordinator. Platform-owned ceilings are independent of tenant roles. */
public final class JdbcTenantRbac {
    public static final String ROLE = "ls_core_tenant_rbac_dev";
    public static final String LOG_ROLE = "ls_core_tenant_rbac_log_dev";
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();
    private static final String RECEIPTS = "SELECT request_id,tenant_id,application_id,action,resource_id,previous_snapshot,result_snapshot,created_at,actor_user_id FROM security.rbac_operations";
    private final JdbcApplicationSessions sessions;
    private final JdbcPasswordAuthentication authentication;
    private final DataSource source;
    private final DataSource log;

    public JdbcTenantRbac(JdbcApplicationSessions sessions, JdbcPasswordAuthentication authentication,
            DataSource source, DataSource log) {
        this.sessions = sessions;
        this.authentication = authentication;
        this.source = source;
        this.log = log;
    }

    public enum Action {
        ROLE_CREATE, ROLE_UPDATE, ROLE_DISABLE, ROLE_TEMPLATE,
        PROFILE_CREATE, PROFILE_UPDATE, PROFILE_DISABLE, PROFILE_TEMPLATE
    }

    public record Input(UUID resourceId, UUID templateId, Long expectedRevision, String code, String name,
            String description, List<UUID> permissionIds, List<UUID> roleIds) {
        public Input {
            permissionIds = permissionIds == null ? List.of() : List.copyOf(permissionIds);
            roleIds = roleIds == null ? List.of() : List.copyOf(roleIds);
        }
    }

    public record Receipt(UUID requestId, UUID tenantId, UUID applicationId, String action, UUID resourceId,
            tools.jackson.databind.JsonNode previous, tools.jackson.databind.JsonNode result, Instant createdAt, UUID actorId) {}

    public record Result(String outcome, UUID correlation, Receipt receipt) {}

    public record Application(UUID id, String code, String name, String status) {}

    public record Module(UUID id, String code, String name, String status) {}

    public record Permission(UUID id, UUID moduleId, String code, String name, String scope, String status,
            String privilegeKind, boolean delegable) {}

    public record Template(UUID id, String code, String name, int version, List<UUID> items, boolean delegable) {}

    public record Workspace(boolean canManage, List<Module> modules, List<Permission> permissions,
            List<Template> roleTemplates, List<Template> profileTemplates, List<tools.jackson.databind.JsonNode> roles,
            List<tools.jackson.databind.JsonNode> profiles, List<Receipt> history) {}

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection, UUID actor) throws SQLException;
    }

    private <T> T read(String token, Work<T> work) {
        return sessions.withSession(LoginApplication.CORE, token, (ignored, session) -> {
            try (var connection = source.getConnection()) {
                PlatformDatabase.begin(connection, ROLE, false);
                lock(connection, true);
                T value = work.run(connection, session.userId());
                connection.commit();
                return value;
            } catch (SQLException failure) {
                throw new JdbcTenantUsers.Unavailable();
            }
        });
    }

    public List<JdbcTenantUsers.Company> companies(String token) {
        return read(token, (connection, actor) -> {
            var values = new ArrayList<JdbcTenantUsers.Company>();
            try (var query = connection.prepareStatement("SELECT id,name,status FROM tenant.tenants t WHERE t.status='ACTIVE' AND"
                    + " (security.rbac_authority(?,t.id,NULL,false) OR EXISTS(SELECT FROM catalog.tenant_applications a WHERE a.tenant_id=t.id AND security.rbac_authority(?,t.id,a.application_id,false))) ORDER BY name,id LIMIT 1001")) {
                query.setObject(1, actor);
                query.setObject(2, actor);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        values.add(new JdbcTenantUsers.Company(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3)));
                    }
                }
            }
            if (values.isEmpty()) {
                try (var query = connection.prepareStatement("SELECT security.rbac_owner(?)")) {
                    query.setObject(1, actor);
                    try (var rows = query.executeQuery()) {
                        require(rows.next() && rows.getBoolean(1));
                    }
                }
            }
            return bounded(values);
        });
    }

    public List<Application> applications(String token, UUID tenant) {
        return read(token, (connection, actor) -> {
            try (var query = connection.prepareStatement("SELECT security.rbac_authority(?,?,NULL,false) OR EXISTS(SELECT FROM catalog.tenant_applications a WHERE a.tenant_id=? AND security.rbac_authority(?,?,a.application_id,false))")) {
                query.setObject(1, actor);
                query.setObject(2, tenant);
                query.setObject(3, tenant);
                query.setObject(4, actor);
                query.setObject(5, tenant);
                try (var rows = query.executeQuery()) {
                    require(rows.next() && rows.getBoolean(1));
                }
            }
            var values = new ArrayList<Application>();
            try (var query = connection.prepareStatement("SELECT a.id,a.code,a.name,t.status FROM catalog.tenant_applications t JOIN catalog.applications a ON a.id=t.application_id WHERE t.tenant_id=? AND a.status='PUBLISHED' AND security.rbac_authority(?,?,a.id,false) ORDER BY a.name,a.id LIMIT 1001")) {
                query.setObject(1, tenant);
                query.setObject(2, actor);
                query.setObject(3, tenant);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        values.add(new Application(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3), rows.getString(4)));
                    }
                }
            }
            return bounded(values);
        });
    }

    public Workspace workspace(String token, UUID tenant, UUID app) {
        return read(token, (connection, actor) -> {
            boolean manage = authorize(connection, actor, tenant, app);
            var modules = new ArrayList<Module>();
            try (var query = connection.prepareStatement("SELECT m.id,m.code,m.name,coalesce(t.status,'NOT_ENABLED') FROM catalog.modules m LEFT JOIN catalog.tenant_modules t ON t.application_id=m.application_id AND t.module_id=m.id AND t.tenant_id=? WHERE m.application_id=? AND m.status='PUBLISHED' ORDER BY m.code,m.id LIMIT 1001")) {
                query.setObject(1, tenant);
                query.setObject(2, app);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        modules.add(new Module(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3), rows.getString(4)));
                    }
                }
            }
            var permissions = new ArrayList<Permission>();
            try (var query = connection.prepareStatement("SELECT p.id,p.module_id,p.code,p.name,p.resource_scope,p.status,d.privilege_kind,security.rbac_permission_allowed(?,p.application_id,p.id) FROM security.permissions p LEFT JOIN platform.rbac_permission_delegations d ON d.tenant_id=? AND d.application_id=p.application_id AND d.permission_id=p.id AND d.expires_at>clock_timestamp() WHERE p.application_id=? AND p.status='PUBLISHED' AND p.resource_scope IN ('TENANT','BRANCH') ORDER BY p.code,p.id LIMIT 1001")) {
                query.setObject(1, tenant);
                query.setObject(2, tenant);
                query.setObject(3, app);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        permissions.add(new Permission(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class), rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7), rows.getBoolean(8)));
                    }
                }
            }
            return new Workspace(manage, bounded(modules), bounded(permissions), templates(connection, tenant, app, true),
                    templates(connection, tenant, app, false), resources(connection, tenant, app, "ROLE"), resources(connection, tenant, app, "PROFILE"), history(connection, tenant, app, null));
        });
    }

    public tools.jackson.databind.JsonNode detail(String token, UUID tenant, UUID app, String kind, UUID resource) {
        return read(token, (connection, actor) -> {
            authorize(connection, actor, tenant, app);
            try (var query = connection.prepareStatement("SELECT security.rbac_snapshot(?,?,?,?)")) {
                query.setObject(1, tenant);
                query.setObject(2, app);
                query.setObject(3, resource);
                query.setString(4, kind);
                try (var rows = query.executeQuery()) {
                    if (!rows.next() || rows.getString(1) == null) {
                        throw new JdbcTenantUsers.Missing();
                    }
                    return JSON.readTree(rows.getString(1));
                }
            }
        });
    }

    public List<Receipt> history(String token, UUID tenant, UUID app, UUID resource) {
        return read(token, (connection, actor) -> {
            authorize(connection, actor, tenant, app);
            return history(connection, tenant, app, resource);
        });
    }

    private static List<Receipt> history(Connection connection, UUID tenant, UUID app, UUID resource) throws SQLException {
        var values = new ArrayList<Receipt>();
        try (var query = connection.prepareStatement(RECEIPTS + " WHERE tenant_id=? AND application_id=?"
                + (resource == null ? "" : " AND (resource_id=? OR EXISTS(SELECT FROM jsonb_array_elements(result_snapshot->'templateRoles') r WHERE r->>'id'=?))")
                + " ORDER BY created_at DESC,request_id DESC LIMIT 50")) {
            query.setObject(1, tenant);
            query.setObject(2, app);
            if (resource != null) {
                query.setObject(3, resource);
                query.setString(4, resource.toString());
            }
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    values.add(receipt(rows));
                }
            }
        }
        return List.copyOf(values);
    }

    private static List<tools.jackson.databind.JsonNode> resources(Connection connection, UUID tenant, UUID app, String kind) throws SQLException {
        var values = new ArrayList<tools.jackson.databind.JsonNode>();
        // kind is selected by server code only, never SQL supplied by HTTP.
        String table = "ROLE".equals(kind) ? "security.roles" : "security.profiles";
        try (var query = connection.prepareStatement("SELECT security.rbac_snapshot(tenant_id,application_id,id,?) FROM " + table + " WHERE tenant_id=? AND application_id=? ORDER BY code,id LIMIT 1001")) {
            query.setString(1, kind);
            query.setObject(2, tenant);
            query.setObject(3, app);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    values.add(JSON.readTree(rows.getString(1)));
                }
            }
        }
        return bounded(values);
    }

    private static List<Template> templates(Connection connection, UUID tenant, UUID app, boolean role) throws SQLException {
        String queryText = role ? "SELECT t.id,t.code,t.name,t.version,ARRAY(SELECT permission_id FROM security.role_template_permissions WHERE application_id=t.application_id AND role_template_id=t.id ORDER BY permission_id),security.rbac_template_allowed(?,t.application_id,t.id) FROM security.role_templates t"
                : "SELECT t.id,t.code,t.name,t.version,ARRAY(SELECT role_template_id FROM security.profile_template_roles WHERE application_id=t.application_id AND profile_template_id=t.id ORDER BY role_template_id),EXISTS(SELECT FROM security.profile_template_roles WHERE application_id=t.application_id AND profile_template_id=t.id) AND NOT EXISTS(SELECT FROM security.profile_template_roles WHERE application_id=t.application_id AND profile_template_id=t.id AND NOT security.rbac_template_allowed(?,t.application_id,role_template_id)) FROM security.profile_templates t";
        var values = new ArrayList<Template>();
        try (var query = connection.prepareStatement(queryText + " WHERE t.application_id=? AND t.status='PUBLISHED' ORDER BY t.code,t.version,t.id LIMIT 1001")) {
            query.setObject(1, tenant);
            query.setObject(2, app);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    values.add(new Template(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3), rows.getInt(4), List.of((UUID[]) rows.getArray(5).getArray()), rows.getBoolean(6)));
                }
            }
        }
        return bounded(values);
    }

    private static boolean authorize(Connection connection, UUID actor, UUID tenant, UUID app) throws SQLException {
        try (var query = connection.prepareStatement("SELECT security.rbac_authority(?,?,?,false),security.rbac_authority(?,?,?,true)")) {
            query.setObject(1, actor);
            query.setObject(2, tenant);
            query.setObject(3, app);
            query.setObject(4, actor);
            query.setObject(5, tenant);
            query.setObject(6, app);
            try (var rows = query.executeQuery()) {
                require(rows.next() && rows.getBoolean(1));
                return rows.getBoolean(2);
            }
        }
    }

    private static <T> List<T> bounded(List<T> values) {
        if (values.size() > 1000) {
            throw new JdbcTenantUsers.Unavailable();
        }
        return List.copyOf(values);
    }
    public Result find(String token, UUID request) {
        return read(token, (connection, actor) -> {
            Receipt value = find(connection, actor, request);
            return value == null ? null : new Result("CONFIRMED", request, value);
        });
    }

    private static Receipt find(Connection connection, UUID actor, UUID request) throws SQLException {
        try (var query = connection.prepareStatement(RECEIPTS + " WHERE request_id=? AND actor_user_id=?")) {
            query.setObject(1, request);
            query.setObject(2, actor);
            try (var rows = query.executeQuery()) {
                return rows.next() ? receipt(rows) : null;
            }
        }
    }

    public Result mutate(String token, UUID tenant, UUID application, UUID request, Action action,
            Input input, char[] password) {
        if (request == null || action == null || tenant == null || application == null || input == null
                || input.permissionIds().size() > 200 || input.roleIds().size() > 100) {
            throw new JdbcTenantUsers.Invalid();
        }
        Result[] decided = new Result[1];
        try {
            return sessions.withSession(LoginApplication.CORE, token, (ignored, session) -> {
                decided[0] = execute(session.userId(), tenant, application, request, action, input, password);
                return decided[0];
            });
        } catch (JdbcApplicationSessions.Rejected failure) {
            if (decided[0] != null) {
                return decided[0];
            }
            throw failure;
        }
    }

    private Result execute(UUID actor, UUID tenant, UUID application, UUID request, Action action, Input input, char[] password) {
        boolean committing = false;
        String outcome = "UNAVAILABLE";
        UUID company = tenant;
        try (var connection = source.getConnection()) {
            PlatformDatabase.begin(connection, ROLE, false);
            try {
                java.sql.Timestamp started;
                String actorName;
                try (var query = connection.prepareStatement("SELECT username,clock_timestamp()"
                        + " FROM identity.users WHERE id=? AND status='ACTIVE'")) {
                    query.setObject(1, actor);
                    try (var rows = query.executeQuery()) {
                        require(rows.next());
                        actorName = rows.getString(1);
                        started = rows.getTimestamp(2);
                    }
                }
                require(actor.equals(authentication.verify(actorName, password)));
                lock(connection, false);
                Receipt prior = find(connection, actor, request);
                if (prior != null) {
                    boolean same;
                    try (var query = connection.prepareStatement("SELECT tenant_id=? AND application_id=? AND action=? AND input=?::jsonb FROM security.rbac_operations WHERE request_id=? AND actor_user_id=?")) {
                        query.setObject(1, tenant);
                        query.setObject(2, application);
                        query.setString(3, action.name());
                        query.setString(4, inputJson(input));
                        query.setObject(5, request);
                        query.setObject(6, actor);
                        try (var rows = query.executeQuery()) {
                            same = rows.next() && rows.getBoolean(1);
                        }
                    }
                    connection.rollback();
                    return new Result(same ? "CONFIRMED" : "CONFLICT", request, same ? prior : null);
                }
                try (var query = connection.prepareStatement("SELECT id FROM tenant.tenants WHERE id=? FOR UPDATE")) {
                    query.setObject(1, company);
                    try (var rows = query.executeQuery()) {
                        if (!rows.next()) {
                            throw new JdbcTenantUsers.Missing();
                        }
                    }
                }
                // Pin the actor's proof until COMMIT. The command locks the remaining eligibility set.
                for (String query : new String[] {
                        "SELECT id FROM identity.users WHERE id=? FOR SHARE",
                        "SELECT user_id FROM identity.user_credentials WHERE user_id=? FOR SHARE"}) {
                    try (var pin = connection.prepareStatement(query)) {
                        pin.setObject(1, actor);
                        try (var rows = pin.executeQuery()) {
                            require(rows.next());
                        }
                    }
                }
                try (var query = connection.prepareStatement("SELECT tenant.administrator_ready(user_id)"
                        + " AND clock_timestamp()<=?::timestamptz+interval '10 seconds' AND password_changed_at<=?"
                        + " FROM identity.user_credentials WHERE user_id=?")) {
                    query.setTimestamp(1, started);
                    query.setTimestamp(2, started);
                    query.setObject(3, actor);
                    try (var rows = query.executeQuery()) {
                        require(rows.next() && rows.getBoolean(1));
                    }
                }
                Receipt value;
                try (var query = connection.prepareStatement("INSERT INTO security.rbac_operations"
                        + "(request_id,actor_user_id,tenant_id,application_id,action,resource_id,expected_revision,template_id,code,name,description,permission_ids,role_ids)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING request_id,tenant_id,application_id,action,resource_id,previous_snapshot,result_snapshot,created_at,actor_user_id")) {
                    bind(query, connection, actor, tenant, application, request, action, input);
                    try (var rows = query.executeQuery()) {
                        require(rows.next());
                        value = receipt(rows);
                    }
                }
                committing = true;
                connection.commit();
                return new Result("CONFIRMED", request, value);
            } catch (SQLException failure) {
                connection.rollback();
                outcome = switch (failure.getSQLState() == null ? "" : failure.getSQLState()) {
                    case "23505", "P3101", "40001", "40P01", "55P03" -> "CONFLICT";
                    case "P3102" -> "INCOMPLETE_REQUIREMENTS";
                    case "P3104" -> "NOT_FOUND";
                    case "22023", "23514" -> "INVALID";
                    case "42501" -> "DENIED";
                    default -> "UNAVAILABLE";
                };
            } catch (JdbcTenantUsers.Missing failure) {
                connection.rollback();
                outcome = "NOT_FOUND";
            } catch (RuntimeException failure) {
                connection.rollback();
                outcome = "DENIED";
            }
        } catch (SQLException | RuntimeException failure) {
            // Neutral response: never log input or credential-bearing statements.
        }
        boolean recorded = company == null || rejection(actor, company, request, action, committing);
        return new Result(committing ? "UNKNOWN" : recorded ? outcome : "UNAVAILABLE", request, null);
    }

    private boolean rejection(UUID actor, UUID tenant, UUID request, Action action, boolean uncertain) {
        try (var connection = log.getConnection()) {
            PlatformDatabase.begin(connection, LOG_ROLE, false);
            try (var query = connection.prepareStatement("INSERT INTO audit.events"
                    + "(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)"
                    + " VALUES(clock_timestamp(),'USER',?,?,'TENANT',?,?,?,'{}'::jsonb)")) {
                query.setObject(1, actor);
                query.setString(2, "TENANT_RBAC_" + action.name());
                query.setObject(3, tenant);
                query.setString(4, uncertain ? "UNKNOWN" : "DENIED");
                query.setObject(5, request);
                query.executeUpdate();
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException failure) {
            return false;
        }
    }

    private static Receipt receipt(java.sql.ResultSet rows) throws SQLException {
        return new Receipt(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class), rows.getObject(3, UUID.class),
                rows.getString(4), rows.getObject(5, UUID.class), rows.getString(6) == null ? null : JSON.readTree(rows.getString(6)),
                JSON.readTree(rows.getString(7)), rows.getTimestamp(8).toInstant(), rows.getObject(9, UUID.class));
    }

    static void bind(java.sql.PreparedStatement query, Connection connection, UUID actor, UUID tenant,
            UUID app, UUID request, Action action, Input input) throws SQLException {
        query.setObject(1, request);
        query.setObject(2, actor);
        query.setObject(3, tenant);
        query.setObject(4, app);
        query.setString(5, action.name());
        query.setObject(6, input.resourceId());
        query.setObject(7, input.expectedRevision());
        query.setObject(8, input.templateId());
        query.setString(9, input.code());
        query.setString(10, input.name());
        query.setString(11, input.description());
        query.setArray(12, connection.createArrayOf("uuid", input.permissionIds().toArray(UUID[]::new)));
        query.setArray(13, connection.createArrayOf("uuid", input.roleIds().toArray(UUID[]::new)));
    }

    private static String inputJson(Input input) {
        var value = new java.util.LinkedHashMap<String, Object>();
        value.put("resourceId", input.resourceId());
        value.put("templateId", input.templateId());
        value.put("expectedRevision", input.expectedRevision());
        value.put("code", input.code());
        value.put("name", input.name());
        value.put("description", input.description());
        value.put("permissionIds", input.permissionIds());
        value.put("roleIds", input.roleIds());
        return JSON.writeValueAsString(value);
    }

    private static void lock(Connection connection, boolean read) throws SQLException {
        JdbcTenantUsers.lock(connection, read);
    }

    private static void require(boolean value) {
        if (!value) {
            throw new JdbcTenantUsers.Denied();
        }
    }
}

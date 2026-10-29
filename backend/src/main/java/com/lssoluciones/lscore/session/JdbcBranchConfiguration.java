package com.lssoluciones.lscore.session;

import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import com.lssoluciones.lscore.platform.PlatformDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Core cookie authenticates identity only. Every selected tenant receives independent authorization. */
public final class JdbcBranchConfiguration {
    public static final String ROLE = "ls_core_branch_config_dev";
    public static final String LOG_ROLE = "ls_core_branch_config_log_dev";
    private final JdbcApplicationSessions sessions;
    private final JdbcPasswordAuthentication authentication;
    private final DataSource source;
    private final DataSource log;
    public JdbcBranchConfiguration(JdbcApplicationSessions sessions, JdbcPasswordAuthentication authentication, DataSource source, DataSource log) {
        this.sessions = sessions;
        this.authentication = authentication;
        this.source = source;
        this.log = log;
    }
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public enum Action { PREPARE, UPDATE, VALIDATE }
    public record Scope(UUID tenantId, UUID branchId, UUID applicationId, UUID moduleId, int version) { }
    public record Field(String key, String label, String type, boolean required, Integer maxLength, Integer minimum, Integer maximum) { }
    public record Module(Scope scope, String applicationName, String moduleName, String contractStatus,
            boolean applicationPublished, boolean modulePublished, boolean applicationEnabled, boolean moduleEnabled,
            boolean available, boolean contextActive, boolean canRead, boolean canManage, String readiness, Long revision, List<Field> fields) { }
    public record Configuration(Scope scope, JsonNode values, String readiness, long revision, Instant updatedAt) { }
    public record Change(UUID requestId, String action, String previousReadiness, Configuration configuration) { }
    public record Detail(Module module, Configuration configuration, List<String> missingFields, List<Change> history) { }
    public record Result(String outcome, UUID correlation, String reason, Change change, List<String> missingFields) { }

    @FunctionalInterface
    private interface Work<T> { T run(Connection connection, UUID actor) throws SQLException; }
    private <T> T read(String token, Work<T> work) {
        return sessions.withSession(LoginApplication.CORE, token, (ignored, session) -> {
            try (var connection = source.getConnection()) {
                PlatformDatabase.begin(connection, ROLE, false);
                lock(connection, true);
                pinActor(connection, session.userId());
                T value = work.run(connection, session.userId());
                connection.commit();
                return value;
            } catch (SQLException failure) { throw new Unavailable(); }
        });
    }
    public List<Module> modules(String token, UUID tenant, UUID branch) {
        return read(token, (connection, actor) -> {
            authorizeBranch(connection, actor, tenant, branch);
            var scopes = new ArrayList<Scope>();
            try (var statement = connection.prepareStatement("SELECT d.application_id,d.module_id,d.version FROM catalog.branch_configuration_definitions d WHERE d.status='PUBLISHED' OR (EXISTS(SELECT FROM tenant.branch_configurations c WHERE c.tenant_id=? AND c.branch_id=? AND c.application_id=d.application_id AND c.module_id=d.module_id AND c.definition_version=d.version) AND tenant.branch_configuration_authority(?,?,d.application_id,d.module_id,d.version,false)) ORDER BY d.application_id,d.module_id,d.version LIMIT 101")) {
                statement.setObject(1, tenant);
                statement.setObject(2, branch);
                statement.setObject(3, actor);
                statement.setObject(4, tenant);
                try (var rows = statement.executeQuery()) { while (rows.next()) { scopes.add(new Scope(tenant, branch, rows.getObject(1, UUID.class), rows.getObject(2, UUID.class), rows.getInt(3))); } }
            }
            if (scopes.size() > 100) { throw new Unavailable(); }
            var values = new ArrayList<Module>();
            for (var scope : scopes) { values.add(module(connection, actor, scope)); }
            return List.copyOf(values);
        });
    }
    public Detail detail(String token, Scope scope) {
        return read(token, (connection, actor) -> {
            authorizeBranch(connection, actor, scope.tenantId(), scope.branchId());
            var definition = module(connection, actor, scope);
            require(definition.canRead());
            var value = configuration(connection, scope);
            var history = new ArrayList<Change>();
            try (var statement = connection.prepareStatement(changeSql() + " WHERE tenant_id=? AND branch_id=? AND application_id=? AND module_id=? AND definition_version=? ORDER BY created_at DESC LIMIT 50")) {
                bindScope(statement, 1, scope);
                try (var rows = statement.executeQuery()) { while (rows.next()) { history.add(change(rows)); } }
            }
            return new Detail(definition, value, validate(definition.fields(), value == null ? JSON.createObjectNode() : value.values(), false), List.copyOf(history));
        });
    }
    public Result find(String token, UUID request) {
        return read(token, (connection, actor) -> {
            try (var statement = connection.prepareStatement(changeSql() + " WHERE request_id=? AND actor_user_id=?")) {
                statement.setObject(1, request);
                statement.setObject(2, actor);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) { return null; }
                    var value = change(rows);
                    // Original actor may reconcile this receipt even after losing administrative authority.
                    // It grants no tenant listing, history or new mutation.
                    return new Result("CONFIRMED", request, null, value, List.of());
                }
            }
        });
    }
    public Result mutate(String token, Scope scope, UUID request, Action action, Long expectedRevision, JsonNode values, char[] password) {
        char[] secret = password == null ? null : password.clone();
        Result[] decided = new Result[1];
        try {
            return sessions.withSession(LoginApplication.CORE, token, (ignored, session) -> {
                decided[0] = execute(session.userId(), scope, request, action, expectedRevision, values, secret);
                return decided[0];
            });
        } catch (JdbcApplicationSessions.Rejected failure) {
            if (decided[0] != null) { return decided[0]; }
            throw failure;
        } finally { if (secret != null) { Arrays.fill(secret, '\0'); } }
    }
    private Result execute(UUID actor, Scope scope, UUID request, Action action, Long expectedRevision, JsonNode values, char[] secret) {
        UUID correlation = request == null ? UUID.randomUUID() : request;
        boolean committing = false;
        boolean confirmed = false;
        String outcome = "UNAVAILABLE";
        Change value = null;
        List<String> missing = List.of();
        try (var connection = source.getConnection()) {
            PlatformDatabase.begin(connection, ROLE, false);
            try {
                String actorName;
                java.sql.Timestamp started;
                try (var statement = connection.prepareStatement("SELECT username,clock_timestamp() FROM identity.users WHERE id=? AND status='ACTIVE'")) {
                    statement.setObject(1, actor);
                    try (var rows = statement.executeQuery()) {
                        require(rows.next());
                        actorName = rows.getString(1);
                        started = rows.getTimestamp(2);
                    }
                }
                require(actor.equals(authentication.verify(actorName, secret)));
                require(request != null && scope != null && action != null);

                lock(connection, false);
                authorizeBranch(connection, actor, scope.tenantId(), scope.branchId());
                var definition = module(connection, actor, scope);
                require(definition.canManage());
                if (!definition.available()) { throw new ModuleUnavailable(); }
                JsonNode actual = values;
                if (action == Action.VALIDATE) {
                    var current = configuration(connection, scope);
                    if (current == null) { throw new Missing(); }
                    actual = current.values();
                }
                validate(definition.fields(), actual, action == Action.VALIDATE);
                try (var statement = connection.prepareStatement("SELECT clock_timestamp()<=?::timestamptz+interval '10 seconds'")) {
                    statement.setTimestamp(1, started);
                    try (var rows = statement.executeQuery()) { require(rows.next() && rows.getBoolean(1)); }
                }
                try (var statement = connection.prepareStatement("INSERT INTO tenant.branch_configuration_operations(request_id,actor_user_id,tenant_id,branch_id,application_id,module_id,definition_version,action,expected_revision,values_json,readiness,revision) VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,'DRAFT',1) RETURNING " + changeColumns())) {
                    statement.setObject(1, request);
                    statement.setObject(2, actor);
                    bindScope(statement, 3, scope);
                    statement.setString(8, action.name());
                    statement.setObject(9, expectedRevision);
                    statement.setString(10, actual == null ? null : actual.toString());
                    try (var rows = statement.executeQuery()) {
                        require(rows.next());
                        value = change(rows);
                    }
                }
                committing = true;
                connection.commit();
                confirmed = true;
            } catch (SQLException failure) {
                connection.rollback();
                outcome = switch (failure.getSQLState() == null ? "" : failure.getSQLState()) {
                    case "P2601", "23505", "23514", "40001", "40P01", "55P03" -> "CONFLICT";
                    case "P2602" -> "INCOMPLETE_REQUIREMENTS";
                    case "P2603" -> "MODULE_UNAVAILABLE";
                    case "P2604" -> "NOT_FOUND";
                    case "42501" -> "DENIED";
                    default -> "UNAVAILABLE";
                };
            } catch (Incomplete failure) {
                connection.rollback();
                outcome = "INCOMPLETE_REQUIREMENTS";
                missing = failure.fields;
            } catch (ModuleUnavailable failure) {
                connection.rollback();
                outcome = "MODULE_UNAVAILABLE";
            } catch (Missing failure) {
                connection.rollback();
                outcome = "NOT_FOUND";
            } catch (RuntimeException failure) {
                connection.rollback();
                outcome = "DENIED";
            }
        } catch (SQLException | RuntimeException failure) { /* Public results never expose database or identity diagnostics. */ }
        if (confirmed) { return new Result("CONFIRMED", correlation, null, value, List.of()); }
        boolean recorded = rejection(actor, scope == null ? null : scope.branchId(), correlation, action, committing);
        return new Result(committing ? "UNKNOWN" : recorded ? outcome : "UNAVAILABLE", correlation,
                committing ? "RECONCILE" : "CONFLICT".equals(outcome) ? "REVALIDATE_CONFIGURATION_REVISION_STATE" : outcome, null, missing);
    }
    private boolean rejection(UUID actor, UUID branch, UUID request, Action action, boolean uncertain) {
        if (action == null) { return false; }
        try (var connection = log.getConnection()) {
            PlatformDatabase.begin(connection, LOG_ROLE, false);
            try (var statement = connection.prepareStatement("INSERT INTO audit.events(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata) VALUES(clock_timestamp(),'USER',?,?,'TENANT_BRANCH',?,?,?,'{}'::jsonb)")) {
                statement.setObject(1, actor);
                statement.setString(2, "BRANCH_CONFIG_" + action.name());
                statement.setObject(3, branch);
                statement.setString(4, uncertain ? "UNKNOWN" : "DENIED");
                statement.setObject(5, request);
                statement.executeUpdate();
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException failure) { return false; }
    }
    private static String changeColumns() { return "request_id,tenant_id,branch_id,application_id,module_id,definition_version,action,values_json,previous_readiness,readiness,revision,created_at"; }
    private static String changeSql() { return "SELECT " + changeColumns() + " FROM tenant.branch_configuration_operations"; }
    private static Change change(java.sql.ResultSet rows) throws SQLException {
        var scope = new Scope(rows.getObject(2, UUID.class), rows.getObject(3, UUID.class), rows.getObject(4, UUID.class), rows.getObject(5, UUID.class), rows.getInt(6));
        return new Change(rows.getObject(1, UUID.class), rows.getString(7), rows.getString(9), new Configuration(scope, JSON.readTree(rows.getString(8)), rows.getString(10), rows.getLong(11), rows.getTimestamp(12).toInstant()));
    }
    private static Configuration configuration(Connection connection, Scope scope) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT values_json,readiness,revision,updated_at FROM tenant.branch_configurations WHERE tenant_id=? AND branch_id=? AND application_id=? AND module_id=? AND definition_version=?")) {
            bindScope(statement, 1, scope);
            try (var rows = statement.executeQuery()) { return rows.next() ? new Configuration(scope, JSON.readTree(rows.getString(1)), rows.getString(2), rows.getLong(3), rows.getTimestamp(4).toInstant()) : null; }
        }
    }
    private static List<Field> fields(Connection connection, Scope scope) throws SQLException {
        var fields = new ArrayList<Field>();
        try (var statement = connection.prepareStatement("SELECT field_key,label,value_type,required,max_length,minimum,maximum FROM catalog.branch_configuration_fields WHERE application_id=? AND module_id=? AND version=? ORDER BY field_key")) {
            statement.setObject(1, scope.applicationId());
            statement.setObject(2, scope.moduleId());
            statement.setInt(3, scope.version());
            try (var rows = statement.executeQuery()) { while (rows.next()) { fields.add(new Field(rows.getString(1), rows.getString(2), rows.getString(3), rows.getBoolean(4), rows.getObject(5, Integer.class), rows.getObject(6, Integer.class), rows.getObject(7, Integer.class))); } }
        }
        return List.copyOf(fields);
    }
    private static Module module(Connection connection, UUID actor, Scope scope) throws SQLException {
        boolean contextActive;
        try (var statement = connection.prepareStatement("SELECT t.status='ACTIVE' AND b.status='ACTIVE' FROM tenant.tenants t JOIN tenant.branches b ON b.tenant_id=t.id WHERE t.id=? AND b.id=?")) {
            statement.setObject(1, scope.tenantId());
            statement.setObject(2, scope.branchId());
            try (var rows = statement.executeQuery()) { contextActive = rows.next() && rows.getBoolean(1); }
        }
        try (var statement = connection.prepareStatement("SELECT a.name,m.name,d.status,a.status='PUBLISHED',m.status='PUBLISHED',COALESCE(ta.status='ACTIVE',false),COALESCE(tm.status='ACTIVE',false) FROM catalog.branch_configuration_definitions d JOIN catalog.modules m ON m.application_id=d.application_id AND m.id=d.module_id JOIN catalog.applications a ON a.id=d.application_id LEFT JOIN catalog.tenant_applications ta ON ta.tenant_id=? AND ta.application_id=a.id LEFT JOIN catalog.tenant_modules tm ON tm.tenant_id=ta.tenant_id AND tm.application_id=a.id AND tm.module_id=m.id WHERE d.application_id=? AND d.module_id=? AND d.version=?")) {
            statement.setObject(1, scope.tenantId());
            statement.setObject(2, scope.applicationId());
            statement.setObject(3, scope.moduleId());
            statement.setInt(4, scope.version());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) { throw new Missing(); }
                boolean canRead = authorized(connection, actor, scope, false);
                boolean canManage = authorized(connection, actor, scope, true);
                var value = canRead ? configuration(connection, scope) : null;
                boolean available = "PUBLISHED".equals(rows.getString(3)) && rows.getBoolean(4) && rows.getBoolean(5) && rows.getBoolean(6) && rows.getBoolean(7);
                return new Module(scope, rows.getString(1), rows.getString(2), rows.getString(3), rows.getBoolean(4), rows.getBoolean(5), rows.getBoolean(6), rows.getBoolean(7), available, contextActive, canRead, canManage,
                        !canRead ? "RESTRICTED" : value == null ? "ABSENT" : value.readiness(), value == null ? null : value.revision(), canRead ? fields(connection, scope) : List.of());
            }
        }
    }
    private static List<String> validate(List<Field> fields, JsonNode values, boolean complete) {
        if (values == null || !values.isObject() || values.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096 || values.size() > 16) { throw new Incomplete(List.of()); }
        var missing = new ArrayList<String>();
        for (var entry : values.properties()) {
            var field = fields.stream().filter(f -> f.key().equals(entry.getKey())).findFirst().orElseThrow(() -> new Incomplete(List.of()));
            var node = entry.getValue();
            boolean valid = switch (field.type()) {
                case "TEXT" -> node.isString() && node.asString().codePointCount(0, node.asString().length()) <= field.maxLength() && node.asString().codePoints().noneMatch(Character::isISOControl);
                case "INTEGER" -> node.isIntegralNumber() && node.canConvertToInt() && node.intValue() >= field.minimum() && node.intValue() <= field.maximum();
                case "BOOLEAN" -> node.isBoolean();
                default -> false;
            };
            if (!valid) { throw new Incomplete(List.of(field.key())); }
        }
        for (var field : fields) { if (field.required() && (!values.has(field.key()) || field.type().equals("TEXT") && values.get(field.key()).asString().trim().isEmpty())) { missing.add(field.key()); } }
        if (complete && !missing.isEmpty()) { throw new Incomplete(List.copyOf(missing)); }
        return List.copyOf(missing);
    }
    private static void bindScope(java.sql.PreparedStatement statement, int start, Scope scope) throws SQLException {
        statement.setObject(start, scope.tenantId());
        statement.setObject(start + 1, scope.branchId());
        statement.setObject(start + 2, scope.applicationId());
        statement.setObject(start + 3, scope.moduleId());
        statement.setInt(start + 4, scope.version());
    }
    private static void authorizeBranch(Connection connection, UUID actor, UUID tenant, UUID branch) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT tenant.branch_administration_authority(?,?,false)")) {
            statement.setObject(1, actor);
            statement.setObject(2, tenant);
            try (var rows = statement.executeQuery()) { require(rows.next() && rows.getBoolean(1)); }
        }
        try (var statement = connection.prepareStatement("SELECT id FROM tenant.branches WHERE tenant_id=? AND id=?")) {
            statement.setObject(1, tenant);
            statement.setObject(2, branch);
            try (var rows = statement.executeQuery()) { if (!rows.next()) { throw new Missing(); } }
        }
    }
    private static boolean authorized(Connection connection, UUID actor, Scope scope, boolean mutating) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT tenant.branch_configuration_authority(?,?,?,?,?,?)")) {
            statement.setObject(1, actor);
            statement.setObject(2, scope.tenantId());
            statement.setObject(3, scope.applicationId());
            statement.setObject(4, scope.moduleId());
            statement.setInt(5, scope.version());
            statement.setBoolean(6, mutating);
            try (var rows = statement.executeQuery()) { return rows.next() && rows.getBoolean(1); }
        }
    }
    private static void lock(Connection connection, boolean reading) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(reading ? "SELECT pg_advisory_xact_lock_shared(370019,1)" : "SELECT pg_advisory_xact_lock(370019,1)");
            statement.execute(reading ? "SELECT pg_advisory_xact_lock_shared(410023,1)" : "SELECT pg_advisory_xact_lock(410023,1)");
        }
    }
    private static void pinActor(Connection connection, UUID actor) throws SQLException {
        for (String query : new String[] {"SELECT id FROM identity.users WHERE id=? FOR SHARE", "SELECT user_id FROM identity.user_credentials WHERE user_id=? FOR SHARE"}) {
            try (var statement = connection.prepareStatement(query)) {
                statement.setObject(1, actor);
                try (var rows = statement.executeQuery()) { require(rows.next()); }
            }
        }
    }
    private static void require(boolean value) { if (!value) { throw new Denied(); } }
    private static final class Incomplete extends RuntimeException {
        private final List<String> fields;
        Incomplete(List<String> fields) {
            super("Configuration requirements incomplete");
            this.fields = fields;
        }
    }
    private static final class ModuleUnavailable extends RuntimeException { }
    public static final class Denied extends RuntimeException { public Denied() { super("Operation unavailable"); } }
    public static final class Missing extends RuntimeException { public Missing() { super("Resource unavailable"); } }
    public static final class Unavailable extends RuntimeException { public Unavailable() { super("Service unavailable"); } }
}

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

/** Core cookie authenticates identity only. Every selected tenant receives independent authorization. */
public final class JdbcBranchAdministration {
    public static final String ROLE = "ls_core_branch_admin_dev";
    public static final String LOG_ROLE = "ls_core_branch_admin_log_dev";
    private final JdbcApplicationSessions sessions;
    private final JdbcPasswordAuthentication authentication;
    private final DataSource source;
    private final DataSource log;
    public JdbcBranchAdministration(JdbcApplicationSessions sessions, JdbcPasswordAuthentication authentication, DataSource source, DataSource log) {
        this.sessions = sessions;
        this.authentication = authentication;
        this.source = source;
        this.log = log;
    }
    public enum Action { CREATE, EDIT, SUSPEND, REACTIVATE, CLOSE }
    public record Company(UUID id, String name, String status) { }
    public record Branch(UUID id, UUID tenantId, String code, String name, String status, Instant createdAt, Instant updatedAt) { }
    public record Change(UUID requestId, UUID tenantId, String action, Branch branch, String previousCode, String previousName, String previousStatus, Instant createdAt) { }
    public record Page(List<Branch> branches, boolean hasMore, boolean canManage) { }
    public record Detail(Branch branch, boolean canManage, boolean codeEditable, List<Change> history) { }
    public record Result(String outcome, UUID correlation, String reason, Change change) { }

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
    public List<Company> companies(String token) {
        return read(token, (connection, actor) -> {
            var values = new ArrayList<Company>();
            try (var statement = connection.prepareStatement("SELECT id,name,status FROM tenant.tenants t WHERE tenant.branch_administration_authority(?,t.id,false) ORDER BY name,id LIMIT 1001")) {
                statement.setObject(1, actor);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) { values.add(new Company(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3))); }
                }
            }
            require(!values.isEmpty() || authorized(connection, actor, null, false));
            // Bounded selector, never a silent partial list.
            if (values.size() > 1000) { throw new Unavailable(); }
            return List.copyOf(values);
        });
    }
    public Page list(String token, UUID tenant, int page) {
        require(page >= 0 && page <= 100000);
        return read(token, (connection, actor) -> {
            authorize(connection, actor, tenant, false);
            var values = new ArrayList<Branch>();
            try (var statement = connection.prepareStatement("SELECT id,tenant_id,code,name,status,created_at,updated_at FROM tenant.branches WHERE tenant_id=? ORDER BY created_at,id LIMIT 51 OFFSET ?")) {
                statement.setObject(1, tenant);
                statement.setInt(2, page * 50);
                try (var rows = statement.executeQuery()) { while (rows.next()) { values.add(branch(rows)); } }
            }
            return new Page(List.copyOf(values.subList(0, Math.min(50, values.size()))), values.size() > 50, authorized(connection, actor, tenant, true));
        });
    }
    public Detail detail(String token, UUID tenant, UUID id) {
        return read(token, (connection, actor) -> {
            authorize(connection, actor, tenant, false);
            Branch value;
            try (var statement = connection.prepareStatement("SELECT id,tenant_id,code,name,status,created_at,updated_at FROM tenant.branches WHERE tenant_id=? AND id=?")) {
                statement.setObject(1, tenant);
                statement.setObject(2, id);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) { return null; }
                    value = branch(rows);
                }
            }
            var history = new ArrayList<Change>();
            try (var statement = connection.prepareStatement(changeSql() + " WHERE tenant_id=? AND branch_id=? ORDER BY created_at DESC LIMIT 50")) {
                statement.setObject(1, tenant);
                statement.setObject(2, id);
                try (var rows = statement.executeQuery()) { while (rows.next()) { history.add(change(rows)); } }
            }
            boolean editable;
            try (var statement = connection.prepareStatement("SELECT NOT EXISTS(SELECT FROM tenant.branch_memberships WHERE tenant_id=? AND branch_id=?) AND NOT EXISTS(SELECT FROM security.session_contexts WHERE tenant_id=? AND branch_id=?) AND NOT EXISTS(SELECT FROM integration.api_keys WHERE tenant_id=? AND branch_id=?)")) {
                for (int index = 1; index <= 6; index += 2) {
                    statement.setObject(index, tenant);
                    statement.setObject(index + 1, id);
                }
                try (var rows = statement.executeQuery()) {
                    require(rows.next());
                    editable = rows.getBoolean(1);
                }
            }
            return new Detail(value, authorized(connection, actor, tenant, true), editable && !"CLOSED".equals(value.status()), List.copyOf(history));
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
                    return new Result("CONFIRMED", request, null, value);
                }
            }
        });
    }
    public Result mutate(String token, UUID tenant, UUID request, Action action, UUID branch, String code, String name, Instant expectedUpdatedAt, char[] password) {
        char[] secret = password == null ? null : password.clone();
        Result[] decided = new Result[1];
        try {
            return sessions.withSession(LoginApplication.CORE, token, (ignored, session) -> {
                decided[0] = execute(session.userId(), tenant, request, action, branch, code, name, expectedUpdatedAt, secret);
                return decided[0];
            });
        } catch (JdbcApplicationSessions.Rejected failure) {
            if (decided[0] != null) { return decided[0]; }
            throw failure;
        } finally { if (secret != null) { Arrays.fill(secret, '\0'); } }
    }
    private Result execute(UUID actor, UUID tenant, UUID request, Action action, UUID branch, String code, String name, Instant expectedUpdatedAt, char[] secret) {
        UUID correlation = request == null ? UUID.randomUUID() : request;
        boolean committing = false;
        boolean confirmed = false;
        String outcome = "UNAVAILABLE";
        Change value = null;
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
                require(request != null && tenant != null && action != null);

                lock(connection, false);
                authorize(connection, actor, tenant, true);
                try (var statement = connection.prepareStatement("SELECT clock_timestamp()<=?::timestamptz+interval '10 seconds'")) {
                    statement.setTimestamp(1, started);
                    try (var rows = statement.executeQuery()) { require(rows.next() && rows.getBoolean(1)); }
                }
                try (var statement = connection.prepareStatement("INSERT INTO tenant.branch_operations(request_id,actor_user_id,tenant_id,branch_id,action,expected_updated_at,code,name,resulting_status,branch_created_at) VALUES(?,?,?,?,?,?,?,?,'',clock_timestamp()) RETURNING " + changeColumns())) {
                    statement.setObject(1, request);
                    statement.setObject(2, actor);
                    statement.setObject(3, tenant);
                    statement.setObject(4, branch == null ? UUID.randomUUID() : branch);
                    statement.setString(5, action.name());
                    statement.setTimestamp(6, expectedUpdatedAt == null ? null : java.sql.Timestamp.from(expectedUpdatedAt));
                    statement.setString(7, code);
                    statement.setString(8, name);
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
                    case "P2501", "23505", "23514", "40001", "40P01", "55P03" -> "CONFLICT";
                    case "P2502" -> "INCOMPLETE_REQUIREMENTS";
                    case "P2504" -> "NOT_FOUND";
                    case "42501" -> "DENIED";
                    default -> "UNAVAILABLE";
                };
            } catch (Missing failure) {
                connection.rollback();
                outcome = "NOT_FOUND";
            } catch (RuntimeException failure) {
                connection.rollback();
                outcome = "DENIED";
            }
        } catch (SQLException | RuntimeException failure) { /* Public results never expose database or identity diagnostics. */ }
        if (confirmed) { return new Result("CONFIRMED", correlation, null, value); }
        boolean recorded = rejection(actor, branch, correlation, action, committing);
        return new Result(committing ? "UNKNOWN" : recorded ? outcome : "UNAVAILABLE", correlation,
                committing ? "RECONCILE" : "CONFLICT".equals(outcome) ? "REVALIDATE_BRANCH_VERSION_STATE_CODE" : outcome, null);
    }
    private boolean rejection(UUID actor, UUID branch, UUID request, Action action, boolean uncertain) {
        if (action == null) { return false; }
        try (var connection = log.getConnection()) {
            PlatformDatabase.begin(connection, LOG_ROLE, false);
            try (var statement = connection.prepareStatement("INSERT INTO audit.events(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata) VALUES(clock_timestamp(),'USER',?,?,'TENANT_BRANCH',?,?,?,'{}'::jsonb)")) {
                statement.setObject(1, actor);
                statement.setString(2, "BRANCH_" + action.name());
                statement.setObject(3, branch);
                statement.setString(4, uncertain ? "UNKNOWN" : "DENIED");
                statement.setObject(5, request);
                statement.executeUpdate();
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException failure) { return false; }
    }
    private static String changeColumns() { return "request_id,tenant_id,action,branch_id,code,name,resulting_status,branch_created_at,created_at,previous_code,previous_name,previous_status"; }
    private static String changeSql() { return "SELECT " + changeColumns() + " FROM tenant.branch_operations"; }
    private static Change change(java.sql.ResultSet rows) throws SQLException {
        var stamp = rows.getTimestamp(9).toInstant();
        var value = new Branch(rows.getObject(4, UUID.class), rows.getObject(2, UUID.class), rows.getString(5), rows.getString(6), rows.getString(7), rows.getTimestamp(8).toInstant(), stamp);
        return new Change(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class), rows.getString(3), value, rows.getString(10), rows.getString(11), rows.getString(12), stamp);
    }
    private static Branch branch(java.sql.ResultSet rows) throws SQLException {
        return new Branch(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class), rows.getString(3), rows.getString(4), rows.getString(5), rows.getTimestamp(6).toInstant(), rows.getTimestamp(7).toInstant());
    }
    private static void authorize(Connection connection, UUID actor, UUID tenant, boolean mutating) throws SQLException {
        require(authorized(connection, actor, tenant, mutating));
        try (var statement = connection.prepareStatement("SELECT id FROM tenant.tenants WHERE id=?")) {
            statement.setObject(1, tenant);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) { throw new Missing(); }
            }
        }
    }
    private static boolean authorized(Connection connection, UUID actor, UUID tenant, boolean mutating) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT tenant.branch_administration_authority(?,?,?)")) {
            statement.setObject(1, actor);
            statement.setObject(2, tenant);
            statement.setBoolean(3, mutating);
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
    public static final class Denied extends RuntimeException { public Denied() { super("Operation unavailable"); } }
    public static final class Missing extends RuntimeException { public Missing() { super("Resource unavailable"); } }
    public static final class Unavailable extends RuntimeException { public Unavailable() { super("Service unavailable"); } }
}

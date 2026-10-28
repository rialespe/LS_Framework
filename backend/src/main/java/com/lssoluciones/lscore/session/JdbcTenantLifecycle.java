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

/** Trusted Core session composition; lifecycle writer and rejection evidence have separate credentials. */
public final class JdbcTenantLifecycle {
    public static final String ROLE = "ls_core_tenant_lifecycle_dev";
    public static final String LOG_ROLE = "ls_core_tenant_lifecycle_log_dev";
    private final JdbcApplicationSessions sessions;
    private final JdbcPasswordAuthentication authentication;
    private final DataSource source;
    private final DataSource log;

    public JdbcTenantLifecycle(JdbcApplicationSessions sessions, JdbcPasswordAuthentication authentication, DataSource source, DataSource log) {
        this.sessions = sessions;
        this.authentication = authentication;
        this.source = source;
        this.log = log;
    }

    public enum Action { ACTIVATE, SUSPEND, REACTIVATE }
    public record Authority(String kind, boolean canRead, boolean canActivate, boolean canSuspend, boolean canReactivate) { }
    public record Tenant(UUID id, String name, String status, UUID administratorId, String administratorName,
            String administratorStatus, Instant createdAt) { }
    public record Change(UUID requestId, String action, String previousStatus, String resultingStatus, Instant createdAt) { }
    public record Detail(Tenant tenant, List<Change> history) { }
    public record Result(String outcome, UUID correlation, String reason, Change change) { }

    public Authority authority(String token) {
        return sessions.withSession(LoginApplication.CORE, token, (ignored, session) -> {
            try (var connection = source.getConnection()) {
                PlatformDatabase.begin(connection, ROLE, false);
                var result = authority(connection, session.userId());
                connection.commit();
                return result;
            } catch (SQLException failure) {
                throw new Unavailable();
            }
        });
    }

    public record Page(List<Tenant> tenants, boolean hasMore) { }

    public Page list(String token, int page) {
        require(page >= 0 && page <= 100000);
        return sessions.withSession(LoginApplication.CORE, token, (ignored, session) -> {
            try (var connection = source.getConnection()) {
                PlatformDatabase.begin(connection, ROLE, false);
                require(authority(connection, session.userId()).canRead());
                var result = new ArrayList<Tenant>();
                try (var statement = connection.prepareStatement(tenantSql() + " ORDER BY t.created_at DESC,t.id LIMIT 51 OFFSET ?")) {
                    statement.setInt(1, page * 50);
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) { result.add(tenant(rows)); }
                    }
                }
                connection.commit();
                return new Page(List.copyOf(result.subList(0, Math.min(50, result.size()))), result.size() > 50);
            } catch (SQLException failure) {
                throw new Unavailable();
            }
        });
    }

    public Detail detail(String token, UUID tenant) {
        return sessions.withSession(LoginApplication.CORE, token, (ignored, session) -> {
            try (var connection = source.getConnection()) {
                PlatformDatabase.begin(connection, ROLE, false);
                lock(connection);
                require(authority(connection, session.userId()).canRead());
                Tenant value = null;
                try (var statement = connection.prepareStatement(tenantSql() + " WHERE t.id=?")) {
                    statement.setObject(1, tenant);
                    try (var rows = statement.executeQuery()) { if (rows.next()) { value = tenant(rows); } }
                }
                var history = new ArrayList<Change>();
                try (var statement = connection.prepareStatement("SELECT request_id,action,previous_status,resulting_status,created_at FROM tenant.lifecycle_operations WHERE tenant_id=? ORDER BY created_at DESC LIMIT 50")) {
                    statement.setObject(1, tenant);
                    try (var rows = statement.executeQuery()) { while (rows.next()) { history.add(change(rows)); } }
                }
                connection.commit();
                return value == null ? null : new Detail(value, List.copyOf(history));
            } catch (SQLException failure) {
                throw new Unavailable();
            }
        });
    }

    public Result find(String token, UUID request) {
        return sessions.withSession(LoginApplication.CORE, token, (ignored, session) -> {
            try (var connection = source.getConnection()) {
                PlatformDatabase.begin(connection, ROLE, false);
                lock(connection);
                require(authority(connection, session.userId()).canRead());
                Result result = null;
                try (var statement = connection.prepareStatement("SELECT request_id,action,previous_status,resulting_status,created_at FROM tenant.lifecycle_operations WHERE request_id=? AND actor_user_id=?")) {
                    statement.setObject(1, request);
                    statement.setObject(2, session.userId());
                    try (var rows = statement.executeQuery()) {
                        if (rows.next()) { result = new Result("CONFIRMED", request, null, change(rows)); }
                    }
                }
                connection.commit();
                return result;
            } catch (SQLException failure) {
                throw new Unavailable();
            }
        });
    }

    public Result transition(String token, UUID tenant, UUID request, Action action, char[] password) {
        char[] secret = password == null ? null : password.clone();
        Result[] decided = new Result[1];
        try {
            return sessions.withSession(LoginApplication.CORE, token, (ignored, session) -> {
                decided[0] = execute(session.userId(), tenant, request, action, secret);
                return decided[0];
            });
        } catch (JdbcApplicationSessions.Rejected failure) {
            if (decided[0] != null) { return decided[0]; }
            throw failure;
        } finally {
            if (secret != null) { Arrays.fill(secret, '\0'); }
        }
    }

    private Result execute(UUID actor, UUID tenant, UUID request, Action action, char[] secret) {
        UUID correlation = request == null ? UUID.randomUUID() : request;
        boolean committing = false;
        boolean confirmed = false;
        String outcome = "UNAVAILABLE";
        String reason = "SERVICE_UNAVAILABLE";
        Change result = null;
        try (var connection = source.getConnection()) {
            PlatformDatabase.begin(connection, ROLE, false);
            try {
                String username;
                java.sql.Timestamp started;
                try (var statement = connection.prepareStatement("SELECT username,clock_timestamp() FROM identity.users WHERE id=? AND status='ACTIVE'")) {
                    statement.setObject(1, actor);
                    try (var rows = statement.executeQuery()) {
                        require(rows.next());
                        username = rows.getString(1);
                        started = rows.getTimestamp(2);
                    }
                }
                require(actor.equals(authentication.verify(username, secret)));
                require(request != null && tenant != null && action != null);
                lock(connection);
                var authority = authority(connection, actor);
                require(authority.canRead() && switch (action) {
                    case ACTIVATE -> authority.canActivate();
                    case SUSPEND -> authority.canSuspend();
                    case REACTIVATE -> authority.canReactivate();
                });
                try (var statement = connection.prepareStatement("SELECT clock_timestamp()<=?::timestamptz+interval '10 seconds'")) {
                    statement.setTimestamp(1, started);
                    try (var rows = statement.executeQuery()) { require(rows.next() && rows.getBoolean(1)); }
                }
                // A duplicate command is rejected, never replayed. Reconciliation is a separate authorized read.
                try (var statement = connection.prepareStatement("INSERT INTO tenant.lifecycle_operations(request_id,actor_user_id,tenant_id,action,previous_status,resulting_status) VALUES(?,?,?,?,'','') RETURNING request_id,action,previous_status,resulting_status,created_at")) {
                    statement.setObject(1, request);
                    statement.setObject(2, actor);
                    statement.setObject(3, tenant);
                    statement.setString(4, action.name());
                    try (var rows = statement.executeQuery()) {
                        require(rows.next());
                        result = change(rows);
                    }
                }
                committing = true;
                connection.commit();
                confirmed = true;
            } catch (SQLException failure) {
                connection.rollback();
                outcome = switch (failure.getSQLState() == null ? "" : failure.getSQLState()) {
                    case "P2301", "23505" -> "INVALID_TRANSITION";
                    case "P2302" -> "INCOMPLETE_REQUIREMENTS";
                    case "42501" -> "DENIED";
                    default -> "UNAVAILABLE";
                };
                reason = "INCOMPLETE_REQUIREMENTS".equals(outcome) ? "PREPARE_INITIAL_ADMINISTRATOR" : outcome;
            } catch (RuntimeException failure) {
                connection.rollback();
                outcome = "DENIED";
                reason = "AUTHORITY_OR_CONFIRMATION_REQUIRED";
            }
        } catch (SQLException | RuntimeException failure) {
            // No SQL, identity input or credential diagnostics cross this boundary.
        }
        if (confirmed) { return new Result("CONFIRMED", correlation, null, result); }
        boolean recorded = rejection(actor, tenant, correlation, action, committing);
        return new Result(committing ? "UNKNOWN" : recorded ? outcome : "UNAVAILABLE", correlation, committing ? "RECONCILE" : reason, null);
    }

    private boolean rejection(UUID actor, UUID tenant, UUID request, Action action, boolean uncertain) {
        if (action == null) { return false; }
        try (var connection = log.getConnection()) {
            PlatformDatabase.begin(connection, LOG_ROLE, false);
            try (var statement = connection.prepareStatement("INSERT INTO audit.events(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata) VALUES(clock_timestamp(),'USER',?,?,'TENANT',?,?,?,'{}'::jsonb)")) {
                statement.setObject(1, actor);
                statement.setString(2, "TENANT_" + action.name());
                statement.setObject(3, tenant);
                statement.setString(4, uncertain ? "UNKNOWN" : "DENIED");
                statement.setObject(5, request);
                statement.executeUpdate();
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException failure) { return false; }
    }

    private static Authority authority(Connection connection, UUID actor) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT a.administrator_kind,"
                + "(a.administrator_kind='OWNER' OR EXISTS(SELECT FROM platform.tenant_lifecycle_grants g WHERE g.designation_id=a.id AND g.capability='READ')),"
                + "(a.administrator_kind='OWNER' OR EXISTS(SELECT FROM platform.tenant_lifecycle_grants g WHERE g.designation_id=a.id AND g.capability='ACTIVATE')),"
                + "(a.administrator_kind='OWNER' OR EXISTS(SELECT FROM platform.tenant_lifecycle_grants g WHERE g.designation_id=a.id AND g.capability='SUSPEND')),"
                + "(a.administrator_kind='OWNER' OR EXISTS(SELECT FROM platform.tenant_lifecycle_grants g WHERE g.designation_id=a.id AND g.capability='REACTIVATE')) "
                + "FROM platform.administrators a JOIN identity.users u ON u.id=a.user_id WHERE a.user_id=? AND a.status='ACTIVE' AND a.revoked_at IS NULL AND u.status='ACTIVE'")) {
            statement.setObject(1, actor);
            try (var rows = statement.executeQuery()) {
                require(rows.next());
                require(rows.getBoolean(2));
                return new Authority(rows.getString(1), rows.getBoolean(2), rows.getBoolean(3), rows.getBoolean(4), rows.getBoolean(5));
            }
        }
    }

    private static String tenantSql() {
        return "SELECT t.id,t.name,t.status,r.administrator_user_id,u.display_name,u.status,t.created_at FROM tenant.tenants t "
                + "LEFT JOIN tenant.initial_registrations r ON r.tenant_id=t.id LEFT JOIN identity.users u ON u.id=r.administrator_user_id";
    }
    private static Tenant tenant(java.sql.ResultSet rows) throws SQLException {
        return new Tenant(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3), rows.getObject(4, UUID.class),
                rows.getString(5), rows.getString(6), rows.getTimestamp(7).toInstant());
    }
    private static Change change(java.sql.ResultSet rows) throws SQLException {
        return new Change(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3), rows.getString(4), rows.getTimestamp(5).toInstant());
    }
    private static void lock(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SELECT pg_advisory_xact_lock(370019,1)");
            statement.execute("SELECT pg_advisory_xact_lock(410023,1)");
        }
    }
    private static void require(boolean value) { if (!value) { throw new Denied(); } }
    public static final class Unavailable extends RuntimeException {
        public Unavailable() { super("Lifecycle unavailable"); }
    }
    public static final class Denied extends RuntimeException {
        public Denied() { super("Lifecycle unavailable"); }
    }
}

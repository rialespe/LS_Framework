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
public final class JdbcTenantAdministrators {
    public static final String ROLE = "ls_core_tenant_admin_dev";
    public static final String LOG_ROLE = "ls_core_tenant_admin_log_dev";
    private final JdbcApplicationSessions sessions;
    private final JdbcPasswordAuthentication authentication;
    private final DataSource source;
    private final DataSource log;
    private static final String DESIGNATIONS = "SELECT a.id,m.user_id,u.username,u.display_name,u.status,m.status,a.status,a.revoked_at,a.granted_at,"
            + "EXISTS(SELECT FROM tenant.valid_administrators(a.tenant_id) v WHERE v.id=a.id) "
            + "FROM tenant.administrators a JOIN tenant.memberships m ON m.id=a.membership_id AND m.tenant_id=a.tenant_id JOIN identity.users u ON u.id=m.user_id ";
    public JdbcTenantAdministrators(JdbcApplicationSessions sessions, JdbcPasswordAuthentication authentication, DataSource source, DataSource log) {
        this.sessions = sessions;
        this.authentication = authentication;
        this.source = source;
        this.log = log;
    }
    public enum Action { DESIGNATE, ACTIVATE, REVOKE, REPLACE }
    public record Company(UUID id, String name, String status) { }
    public record Designation(UUID id, UUID userId, String username, String displayName, String identityStatus,
            String membershipStatus, String status, Instant revokedAt, Instant grantedAt, boolean valid) { }
    public record Candidate(String username, String displayName, String identityStatus, boolean ready) { }
    public record Change(UUID requestId, UUID tenantId, String action, UUID targetId, UUID designationId, String resultingStatus, Instant createdAt) { }
    public record Listing(List<Designation> administrators, boolean canManage, int validCount, List<Change> history) { }
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
            try (var statement = connection.prepareStatement("SELECT id,name,status FROM tenant.tenants t WHERE tenant.administrator_authority(?,t.id,false) ORDER BY name,id LIMIT 1001")) {
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
    public Listing list(String token, UUID tenant) {
        return read(token, (connection, actor) -> {
            authorize(connection, actor, tenant, false);
            var values = new ArrayList<Designation>();
            try (var statement = connection.prepareStatement(DESIGNATIONS + "WHERE a.tenant_id=? ORDER BY a.granted_at,a.id")) {
                statement.setObject(1, tenant);
                try (var rows = statement.executeQuery()) { while (rows.next()) { values.add(designation(rows)); } }
            }
            var history = new ArrayList<Change>();
            try (var statement = connection.prepareStatement(changeSql() + " WHERE tenant_id=? ORDER BY created_at DESC LIMIT 50")) {
                statement.setObject(1, tenant);
                try (var rows = statement.executeQuery()) { while (rows.next()) { history.add(change(rows)); } }
            }
            return new Listing(List.copyOf(values), authorized(connection, actor, tenant, true), (int) values.stream().filter(Designation::valid).count(), List.copyOf(history));
        });
    }
    public Designation detail(String token, UUID tenant, UUID id) {
        return read(token, (connection, actor) -> {
            authorize(connection, actor, tenant, false);
            try (var statement = connection.prepareStatement(DESIGNATIONS + "WHERE a.tenant_id=? AND a.id=?")) {
                statement.setObject(1, tenant);
                statement.setObject(2, id);
                try (var rows = statement.executeQuery()) { return rows.next() ? designation(rows) : null; }
            }
        });
    }
    public Candidate candidate(String token, UUID tenant, String username) {
        return read(token, (connection, actor) -> {
            authorize(connection, actor, tenant, true);
            require(username != null && !username.isBlank() && username.length() <= 1024);
            try (var statement = connection.prepareStatement("SELECT username,display_name,status,tenant.administrator_ready(id) FROM identity.users WHERE username=? AND status IN ('ACTIVE','PENDING')")) {
                statement.setString(1, username);
                try (var rows = statement.executeQuery()) { return rows.next() ? new Candidate(rows.getString(1), rows.getString(2), rows.getString(3), rows.getBoolean(4)) : null; }
            }
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
                    // Reconciliation after a self-revocation: original actor may read only this receipt.
                    // It grants no tenant listing, candidate search, history or new mutation.
                    return new Result("CONFIRMED", request, null, value);
                }
            }
        });
    }
    public Result mutate(String token, UUID tenant, UUID request, Action action, UUID target, String username, char[] password) {
        char[] secret = password == null ? null : password.clone();
        Result[] decided = new Result[1];
        try {
            return sessions.withSession(LoginApplication.CORE, token, (ignored, session) -> {
                decided[0] = execute(session.userId(), tenant, request, action, target, username, secret);
                return decided[0];
            });
        } catch (JdbcApplicationSessions.Rejected failure) {
            if (decided[0] != null) { return decided[0]; }
            throw failure;
        } finally { if (secret != null) { Arrays.fill(secret, '\0'); } }
    }
    private Result execute(UUID actor, UUID tenant, UUID request, Action action, UUID target, String username, char[] secret) {
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
                require(username == null || !username.isBlank() && username.length() <= 1024);
                lock(connection, false);
                authorize(connection, actor, tenant, true);
                try (var statement = connection.prepareStatement("SELECT clock_timestamp()<=?::timestamptz+interval '10 seconds'")) {
                    statement.setTimestamp(1, started);
                    try (var rows = statement.executeQuery()) { require(rows.next() && rows.getBoolean(1)); }
                }
                try (var statement = connection.prepareStatement("INSERT INTO tenant.administrator_operations(request_id,actor_user_id,tenant_id,action,target_id,candidate_username,designation_id,resulting_status) VALUES(?,?,?,?,?,?,?,'') RETURNING request_id,tenant_id,action,target_id,designation_id,resulting_status,created_at")) {
                    statement.setObject(1, request);
                    statement.setObject(2, actor);
                    statement.setObject(3, tenant);
                    statement.setString(4, action.name());
                    statement.setObject(5, target);
                    statement.setString(6, username);
                    statement.setObject(7, UUID.randomUUID());
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
                    case "P2401", "23505", "23514", "40001", "40P01", "55P03" -> "CONFLICT";
                    case "P2402" -> "INCOMPLETE_REQUIREMENTS";
                    case "P2404" -> "NOT_FOUND";
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
        boolean recorded = rejection(actor, tenant, correlation, action, committing);
        return new Result(committing ? "UNKNOWN" : recorded ? outcome : "UNAVAILABLE", correlation,
                committing ? "RECONCILE" : "CONFLICT".equals(outcome) ? "REVALIDATE_ADMINISTRATORS_LAST_VALID_PROTECTED" : outcome, null);
    }
    private boolean rejection(UUID actor, UUID tenant, UUID request, Action action, boolean uncertain) {
        if (action == null) { return false; }
        try (var connection = log.getConnection()) {
            PlatformDatabase.begin(connection, LOG_ROLE, false);
            try (var statement = connection.prepareStatement("INSERT INTO audit.events(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata) VALUES(clock_timestamp(),'USER',?,?,'TENANT',?,?,?,'{}'::jsonb)")) {
                statement.setObject(1, actor);
                statement.setString(2, "TENANT_ADMIN_" + action.name());
                statement.setObject(3, tenant);
                statement.setString(4, uncertain ? "UNKNOWN" : "DENIED");
                statement.setObject(5, request);
                statement.executeUpdate();
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException failure) { return false; }
    }
    private static String changeSql() { return "SELECT request_id,tenant_id,action,target_id,designation_id,resulting_status,created_at FROM tenant.administrator_operations"; }
    private static Change change(java.sql.ResultSet rows) throws SQLException {
        return new Change(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class), rows.getString(3), rows.getObject(4, UUID.class), rows.getObject(5, UUID.class), rows.getString(6), rows.getTimestamp(7).toInstant());
    }
    private static Designation designation(java.sql.ResultSet rows) throws SQLException {
        var revoked = rows.getTimestamp(8);
        return new Designation(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class), rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7), revoked == null ? null : revoked.toInstant(), rows.getTimestamp(9).toInstant(), rows.getBoolean(10));
    }
    private static void authorize(Connection connection, UUID actor, UUID tenant, boolean mutating) throws SQLException {
        require(authorized(connection, actor, tenant, mutating));
        try (var statement = connection.prepareStatement("SELECT id FROM tenant.tenants WHERE id=?")) {
            statement.setObject(1, tenant);
            try (var rows = statement.executeQuery()) { if (!rows.next()) { throw new Missing(); } }
        }
    }
    private static boolean authorized(Connection connection, UUID actor, UUID tenant, boolean mutating) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT tenant.administrator_authority(?,?,?)")) {
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

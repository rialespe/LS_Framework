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

/** Trusted Core channel. SQL lookup material never leaves a neutral request receipt. */
public final class JdbcIdentityLinks {
    public static final String ROLE = "ls_core_identity_links_dev";
    public static final String LOG_ROLE = "ls_core_identity_links_log_dev";
    private static final String RECEIPTS = "SELECT request_id,tenant_id,action,link_id,"
            + "resulting_status,created_at FROM tenant.identity_link_operations";
    private final JdbcApplicationSessions sessions;
    private final JdbcPasswordAuthentication authentication;
    private final DataSource source;
    private final DataSource log;

    public JdbcIdentityLinks(JdbcApplicationSessions sessions, JdbcPasswordAuthentication authentication,
            DataSource source, DataSource log) {
        this.sessions = sessions;
        this.authentication = authentication;
        this.source = source;
        this.log = log;
    }

    public enum Action {
        REQUEST, ACCEPT, REJECT
    }

    public record Receipt(UUID requestId, UUID tenantId, String action, UUID linkId,
            String status, Instant createdAt) {}

    public record Result(String outcome, UUID correlation, Receipt receipt) {}

    public record Link(UUID id, UUID tenantId, String company, String status,
            Instant expiresAt, Instant createdAt, Instant decidedAt) {}

    public record Inbox(List<Link> requests, boolean hasMore) {}

    public record Sent(List<Receipt> requests, boolean hasMore) {}

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

    public Inbox received(String token, int page) {
        page(page);
        return read(token, (connection, actor) -> {
            var values = new ArrayList<Link>();
            try (var query = connection.prepareStatement("SELECT r.id,r.tenant_id,t.name,"
                    + "CASE WHEN r.status='PENDING' AND r.expires_at<=clock_timestamp() THEN 'EXPIRED'"
                    + " ELSE r.status END,r.expires_at,r.created_at,r.decided_at"
                    + " FROM tenant.identity_link_requests r JOIN tenant.tenants t ON t.id=r.tenant_id"
                    + " WHERE r.user_id=? ORDER BY r.created_at DESC,r.id DESC LIMIT 51 OFFSET ?")) {
                query.setObject(1, actor);
                query.setInt(2, page * 50);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        values.add(new Link(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class),
                                rows.getString(3), rows.getString(4), rows.getTimestamp(5).toInstant(),
                                rows.getTimestamp(6).toInstant(), rows.getTimestamp(7) == null
                                        ? null : rows.getTimestamp(7).toInstant()));
                    }
                }
            }
            return new Inbox(List.copyOf(values.subList(0, Math.min(50, values.size()))), values.size() > 50);
        });
    }

    public Sent sent(String token, UUID tenant, int page) {
        page(page);
        return read(token, (connection, actor) -> {
            authority(connection, actor, tenant, false);
            var values = new ArrayList<Receipt>();
            try (var query = connection.prepareStatement(RECEIPTS
                    + " WHERE tenant_id=? AND action='REQUEST' ORDER BY created_at DESC,request_id DESC LIMIT 51 OFFSET ?")) {
                query.setObject(1, tenant);
                query.setInt(2, page * 50);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        values.add(receipt(rows));
                    }
                }
            }
            return new Sent(List.copyOf(values.subList(0, Math.min(50, values.size()))), values.size() > 50);
        });
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

    public Result mutate(String token, UUID tenant, UUID request, Action action, UUID link,
            String username, char[] password) {
        if (request == null || action == null || action == Action.REQUEST && (tenant == null
                || username == null || username.isBlank() || username.length() > 200
                || username.codePoints().anyMatch(Character::isISOControl))
                || action != Action.REQUEST && link == null) {
            throw new JdbcTenantUsers.Invalid();
        }
        Result[] decided = new Result[1];
        try {
            return sessions.withSession(LoginApplication.CORE, token, (ignored, session) -> {
                decided[0] = execute(session.userId(), tenant, request, action, link, username, password);
                return decided[0];
            });
        } catch (JdbcApplicationSessions.Rejected failure) {
            if (decided[0] != null) {
                return decided[0];
            }
            throw failure;
        }
    }

    private Result execute(UUID actor, UUID tenant, UUID request, Action action, UUID link,
            String username, char[] password) {
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
                // The holder's tenant is derived from their own inbox row, never from HTTP.
                if (action != Action.REQUEST) {
                    try (var query = connection.prepareStatement("SELECT tenant_id FROM"
                            + " tenant.identity_link_requests WHERE id=? AND user_id=?")) {
                        query.setObject(1, link);
                        query.setObject(2, actor);
                        try (var rows = query.executeQuery()) {
                            if (!rows.next()) {
                                throw new JdbcTenantUsers.Missing();
                            }
                            company = rows.getObject(1, UUID.class);
                        }
                    }
                }
                Receipt prior = find(connection, actor, request);
                if (prior != null) {
                    connection.rollback();
                    return new Result(prior.tenantId().equals(company) && prior.action().equals(action.name())
                            && java.util.Objects.equals(prior.linkId(), link) ? "CONFIRMED" : "CONFLICT", request, prior);
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
                try (var query = connection.prepareStatement("INSERT INTO tenant.identity_link_operations"
                        + "(request_id,actor_user_id,tenant_id,action,link_id,candidate_username,resulting_status)"
                        + " VALUES(?,?,?,?,?,?,'SUBMITTED') RETURNING request_id,tenant_id,action,link_id,resulting_status,created_at")) {
                    query.setObject(1, request);
                    query.setObject(2, actor);
                    query.setObject(3, company);
                    query.setString(4, action.name());
                    query.setObject(5, link);
                    query.setString(6, username);
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
                    case "23505", "P2901", "40001", "40P01", "55P03" -> "CONFLICT";
                    case "P2902" -> "INCOMPLETE_REQUIREMENTS";
                    case "P2904" -> "NOT_FOUND";
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
                query.setString(2, "IDENTITY_LINK_" + action.name());
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
        return new Receipt(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class), rows.getString(3),
                rows.getObject(4, UUID.class), rows.getString(5), rows.getTimestamp(6).toInstant());
    }

    private static void page(int page) {
        if (page < 0 || page > 100000) {
            throw new JdbcTenantUsers.Invalid();
        }
    }

    private static void authority(Connection connection, UUID actor, UUID company, boolean mutating) throws SQLException {
        try (var query = connection.prepareStatement("SELECT tenant.user_authority(?,?,?)")) {
            query.setObject(1, actor);
            query.setObject(2, company);
            query.setBoolean(3, mutating);
            try (var rows = query.executeQuery()) {
                require(rows.next() && rows.getBoolean(1));
            }
        }
    }

    private static void lock(Connection connection, boolean read) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(read ? "SELECT pg_advisory_xact_lock_shared(370019,1)" : "SELECT pg_advisory_xact_lock(370019,1)");
            statement.execute(read ? "SELECT pg_advisory_xact_lock_shared(410023,1)" : "SELECT pg_advisory_xact_lock(410023,1)");
        }
    }

    private static void require(boolean value) {
        if (!value) {
            throw new JdbcTenantUsers.Denied();
        }
    }
}

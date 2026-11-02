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

/** F09 lifecycle coordinator; session-derived actor and selective context revocation. */
public final class JdbcTenantAccess {
    public static final String ROLE = "ls_core_tenant_access_dev";
    public static final String LOG_ROLE = "ls_core_tenant_access_log_dev";
    private static final String RECEIPTS = "SELECT request_id,tenant_id,membership_id,branch_membership_id,branch_id,"
            + "action,previous_status,resulting_status,revoked_contexts,resource_updated_at,created_at,actor_user_id,expected_updated_at"
            + " FROM tenant.access_operations";
    private final JdbcApplicationSessions sessions;
    private final JdbcPasswordAuthentication authentication;
    private final DataSource source;
    private final DataSource log;

    public JdbcTenantAccess(JdbcApplicationSessions sessions, JdbcPasswordAuthentication authentication,
            DataSource source, DataSource log) {
        this.sessions = sessions;
        this.authentication = authentication;
        this.source = source;
        this.log = log;
    }

    public enum Action {
        MEMBER_SUSPEND, MEMBER_REACTIVATE, BRANCH_SUSPEND, BRANCH_REACTIVATE
    }

    public record Receipt(UUID requestId, UUID tenantId, UUID membershipId, UUID branchMembershipId,
            UUID branchId, String action, String previousStatus, String resultingStatus, int revokedContexts,
            Instant updatedAt, Instant createdAt, UUID actorId, Instant expectedUpdatedAt) {}

    public record Result(String outcome, UUID correlation, Receipt receipt) {}

    public record Branch(UUID id, UUID branchId, String name, String branchStatus, String accessStatus,
            Instant updatedAt, long revokedContexts) {}

    public record Snapshot(UUID membershipId, String status, Instant updatedAt, String tenantStatus,
            boolean f04Ready, boolean administrativeDesignation, boolean globalOwner, boolean canManage,
            long revokedContexts, List<Branch> branches, List<Receipt> history) {}

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

    public Snapshot access(String token, UUID tenant, UUID membership) {
        return read(token, (connection, actor) -> {
            require(JdbcTenantUsers.authorized(connection, actor, tenant, false));
            String status;
            Instant updated;
            String tenantStatus;
            boolean ready;
            boolean designation;
            boolean owner;
            try (var query = connection.prepareStatement("SELECT m.status,m.updated_at,t.status,tenant.administrator_ready(m.user_id),"
                    + "EXISTS(SELECT FROM tenant.administrators a WHERE a.tenant_id=m.tenant_id AND a.membership_id=m.id AND a.revoked_at IS NULL),"
                    + "EXISTS(SELECT FROM platform.administrators a WHERE a.user_id=m.user_id AND a.administrator_kind='OWNER' AND a.status='ACTIVE' AND a.revoked_at IS NULL)"
                    + " FROM tenant.memberships m JOIN tenant.tenants t ON t.id=m.tenant_id WHERE m.tenant_id=? AND m.id=?")) {
                query.setObject(1, tenant);
                query.setObject(2, membership);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new JdbcTenantUsers.Missing();
                    }
                    status = rows.getString(1);
                    updated = rows.getTimestamp(2).toInstant();
                    tenantStatus = rows.getString(3);
                    ready = rows.getBoolean(4);
                    designation = rows.getBoolean(5);
                    owner = rows.getBoolean(6);
                }
            }
            var branches = new ArrayList<Branch>();
            try (var query = connection.prepareStatement("SELECT bm.id,b.id,b.name,b.status,bm.status,bm.updated_at,"
                    + "(SELECT count(*) FROM security.session_contexts c WHERE c.tenant_id=bm.tenant_id AND c.membership_id=bm.membership_id AND c.branch_id=bm.branch_id AND c.revoked_at IS NOT NULL)"
                    + " FROM tenant.branch_memberships bm JOIN tenant.branches b ON b.tenant_id=bm.tenant_id AND b.id=bm.branch_id"
                    + " WHERE bm.tenant_id=? AND bm.membership_id=? ORDER BY b.name,b.id LIMIT 1001")) {
                query.setObject(1, tenant);
                query.setObject(2, membership);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        branches.add(new Branch(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class), rows.getString(3),
                                rows.getString(4), rows.getString(5), rows.getTimestamp(6).toInstant(), rows.getLong(7)));
                    }
                }
            }
            if (branches.size() > 1000) {
                throw new JdbcTenantUsers.Unavailable();
            }
            var history = new ArrayList<Receipt>();
            try (var query = connection.prepareStatement(RECEIPTS + " WHERE tenant_id=? AND membership_id=? ORDER BY created_at DESC,request_id DESC LIMIT 50")) {
                query.setObject(1, tenant);
                query.setObject(2, membership);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        history.add(receipt(rows));
                    }
                }
            }
            long revoked;
            try (var query = connection.prepareStatement("SELECT count(*) FROM security.session_contexts WHERE tenant_id=? AND membership_id=? AND revoked_at IS NOT NULL")) {
                query.setObject(1, tenant);
                query.setObject(2, membership);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    revoked = rows.getLong(1);
                }
            }
            return new Snapshot(membership, status, updated, tenantStatus, ready, designation, owner,
                    JdbcTenantUsers.authorized(connection, actor, tenant, true), revoked, List.copyOf(branches), List.copyOf(history));
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

    public Result mutate(String token, UUID tenant, UUID request, Action action, UUID membership,
            UUID branch, Instant expected, char[] password) {
        if (request == null || action == null || tenant == null || membership == null || expected == null
                || action.name().startsWith("BRANCH_") != (branch != null)) {
            throw new JdbcTenantUsers.Invalid();
        }
        Result[] decided = new Result[1];
        try {
            return sessions.withSession(LoginApplication.CORE, token, (ignored, session) -> {
                decided[0] = execute(session.userId(), tenant, request, action, membership, branch, expected, password);
                return decided[0];
            });
        } catch (JdbcApplicationSessions.Rejected failure) {
            if (decided[0] != null) {
                return decided[0];
            }
            throw failure;
        }
    }

    private Result execute(UUID actor, UUID tenant, UUID request, Action action, UUID membership,
            UUID branch, Instant expected, char[] password) {
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
                    connection.rollback();
                    return new Result(prior.tenantId().equals(company) && prior.action().equals(action.name())
                            && prior.membershipId().equals(membership)
                            && java.util.Objects.equals(prior.branchMembershipId(), branch)
                            && prior.expectedUpdatedAt().equals(expected) ? "CONFIRMED" : "CONFLICT", request, prior);
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
                try (var query = connection.prepareStatement("INSERT INTO tenant.access_operations"
                        + "(request_id,actor_user_id,tenant_id,membership_id,branch_membership_id,action,expected_updated_at)"
                        + " VALUES(?,?,?,?,?,?,?) RETURNING request_id,tenant_id,membership_id,branch_membership_id,branch_id,"
                        + "action,previous_status,resulting_status,revoked_contexts,resource_updated_at,created_at,actor_user_id,expected_updated_at")) {
                    query.setObject(1, request);
                    query.setObject(2, actor);
                    query.setObject(3, company);
                    query.setObject(4, membership);
                    query.setObject(5, branch);
                    query.setString(6, action.name());
                    query.setTimestamp(7, java.sql.Timestamp.from(expected));
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
                    case "23505", "P3001", "40001", "40P01", "55P03" -> "CONFLICT";
                    case "P3002" -> "INCOMPLETE_REQUIREMENTS";
                    case "P3004" -> "NOT_FOUND";
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
                query.setString(2, "TENANT_ACCESS_" + action.name());
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
                rows.getObject(4, UUID.class), rows.getObject(5, UUID.class), rows.getString(6), rows.getString(7),
                rows.getString(8), rows.getInt(9), rows.getTimestamp(10).toInstant(), rows.getTimestamp(11).toInstant(),
                rows.getObject(12, UUID.class), rows.getTimestamp(13).toInstant());
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

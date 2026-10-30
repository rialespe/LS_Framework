package com.lssoluciones.lscore.session;

import com.lssoluciones.lscore.identity.application.ManualIdentityInput;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.TemporaryCredential;
import com.lssoluciones.lscore.platform.PlatformDatabase;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

/** Trusted command channel. No global candidate search or existing identity adoption. */
public final class JdbcTenantUsers {
    public static final String ROLE = "ls_core_tenant_users_dev";
    public static final String LOG_ROLE = "ls_core_tenant_users_log_dev";
    private final JdbcApplicationSessions sessions;
    private final JdbcPasswordAuthentication authentication;
    private final DataSource source;
    private final DataSource log;
    private static final String USERS =
            "SELECT m.id,m.user_id,u.username,u.display_name,u.email,u.email_verified_at IS NOT"
                + " NULL,u.status,m.status,m.created_at,m.invited_by_user_id,"
                + "c.must_change_password,c.password_expires_at,tenant.administrator_ready(u.id),EXISTS(SELECT"
                + " FROM tenant.user_operations o WHERE o.action='CREATE' AND"
                + " o.tenant_id=m.tenant_id AND o.membership_id=m.id) FROM tenant.memberships m"
                + " JOIN identity.users u ON u.id=m.user_id LEFT JOIN identity.user_credentials c"
                + " ON c.user_id=u.id ";
    private static final String CHANGES =
            "SELECT"
                + " request_id,tenant_id,action,user_id,membership_id,resulting_status,temporary_expires_at,created_at,actor_user_id"
                + " FROM tenant.user_operations";

    public JdbcTenantUsers(
            JdbcApplicationSessions sessions,
            JdbcPasswordAuthentication authentication,
            DataSource source,
            DataSource log) {
        this.sessions = sessions;
        this.authentication = authentication;
        this.source = source;
        this.log = log;
    }

    public enum Action {
        CREATE,
        REISSUE,
        ACTIVATE
    }

    public record Company(UUID id, String name, String status) {}

    public record Member(
            UUID id,
            UUID userId,
            String username,
            String displayName,
            String email,
            boolean emailVerified,
            String identityStatus,
            String membershipStatus,
            Instant createdAt,
            UUID createdBy,
            boolean mustChangePassword,
            Instant passwordExpiresAt,
            boolean f04Ready,
            boolean managedIncorporation) {}

    public record Change(
            UUID requestId,
            UUID tenantId,
            String action,
            UUID userId,
            UUID membershipId,
            String resultingStatus,
            Instant temporaryExpiresAt,
            Instant createdAt,
            UUID actorId) {}

    public record Page(List<Member> users, boolean hasMore, boolean canManage) {}

    public record Detail(Member user, boolean canManage, List<Change> history) {}

    public record Result(String outcome, UUID correlation, Change change, char[] temporaryPassword)
            implements AutoCloseable {
        @Override
        public String toString() {
            return "TenantUserResult[REDACTED]";
        }

        @Override
        public void close() {
            if (temporaryPassword != null) {
                Arrays.fill(temporaryPassword, '\0');
            }
        }
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection, UUID actor) throws SQLException;
    }

    private <T> T read(String token, Work<T> work) {
        return sessions.withSession(
                LoginApplication.CORE,
                token,
                (ignored, session) -> {
                    try (var connection = source.getConnection()) {
                        PlatformDatabase.begin(connection, ROLE, false);
                        lock(connection, true);
                        pin(connection, session.userId());
                        T result = work.run(connection, session.userId());
                        connection.commit();
                        return result;
                    } catch (SQLException failure) {
                        throw new Unavailable();
                    }
                });
    }

    public List<Company> companies(String token) {
        return read(
                token,
                (connection, actor) -> {
                    var values = new ArrayList<Company>();
                    try (var statement =
                            connection.prepareStatement(
                                    "SELECT id,name,status FROM tenant.tenants t WHERE"
                                        + " tenant.user_authority(?,t.id,false) ORDER BY name,id"
                                        + " LIMIT 1001")) {
                        statement.setObject(1, actor);
                        try (var rows = statement.executeQuery()) {
                            while (rows.next()) {
                                values.add(
                                        new Company(
                                                rows.getObject(1, UUID.class),
                                                rows.getString(2),
                                                rows.getString(3)));
                            }
                        }
                    }
                    require(!values.isEmpty() || authorized(connection, actor, null, false));
                    if (values.size() > 1000) {
                        throw new Unavailable();
                    }
                    return List.copyOf(values);
                });
    }

    public Page list(String token, UUID tenant, int page) {
        if (page < 0 || page > 100000) {
            throw new Invalid();
        }
        return read(
                token,
                (connection, actor) -> {
                    authorize(connection, actor, tenant, false);
                    var values = new ArrayList<Member>();
                    try (var statement =
                            connection.prepareStatement(
                                    USERS
                                            + "WHERE m.tenant_id=? ORDER BY m.created_at,m.id LIMIT"
                                            + " 51 OFFSET ?")) {
                        statement.setObject(1, tenant);
                        statement.setInt(2, page * 50);
                        try (var rows = statement.executeQuery()) {
                            while (rows.next()) {
                                values.add(member(rows));
                            }
                        }
                    }
                    boolean more = values.size() > 50;
                    return new Page(
                            List.copyOf(more ? values.subList(0, 50) : values),
                            more,
                            authorized(connection, actor, tenant, true));
                });
    }

    public Detail detail(String token, UUID tenant, UUID id) {
        return read(
                token,
                (connection, actor) -> {
                    authorize(connection, actor, tenant, false);
                    Member value;
                    try (var statement =
                            connection.prepareStatement(USERS + "WHERE m.tenant_id=? AND m.id=?")) {
                        statement.setObject(1, tenant);
                        statement.setObject(2, id);
                        try (var rows = statement.executeQuery()) {
                            if (!rows.next()) {
                                return null;
                            }
                            value = member(rows);
                        }
                    }
                    var history = new ArrayList<Change>();
                    try (var statement =
                            connection.prepareStatement(
                                    CHANGES
                                            + " WHERE tenant_id=? AND membership_id=? ORDER BY"
                                            + " created_at DESC,request_id DESC LIMIT 50")) {
                        statement.setObject(1, tenant);
                        statement.setObject(2, id);
                        try (var rows = statement.executeQuery()) {
                            while (rows.next()) {
                                history.add(change(rows));
                            }
                        }
                    }
                    return new Detail(
                            value,
                            authorized(connection, actor, tenant, true),
                            List.copyOf(history));
                });
    }

    public Result find(String token, UUID request) {
        return read(
                token,
                (connection, actor) -> {
                    try (var statement =
                            connection.prepareStatement(
                                    CHANGES + " WHERE request_id=? AND actor_user_id=?")) {
                        statement.setObject(1, request);
                        statement.setObject(2, actor);
                        try (var rows = statement.executeQuery()) {
                            return rows.next()
                                    ? new Result("CONFIRMED", request, change(rows), null)
                                    : null;
                        }
                    }
                });
    }

    public Result mutate(
            String token,
            UUID tenant,
            UUID request,
            Action action,
            UUID membership,
            ManualIdentityInput input,
            char[] password) {
        Result[] decided = new Result[1];
        try {
            return sessions.withSession(
                    LoginApplication.CORE,
                    token,
                    (ignored, session) -> {
                        decided[0] =
                                execute(
                                        session.userId(),
                                        tenant,
                                        request,
                                        action,
                                        membership,
                                        input,
                                        password);
                        return decided[0];
                    });
        } catch (JdbcApplicationSessions.Rejected failure) {
            if (decided[0] != null) {
                return decided[0];
            }
            throw failure;
        }
    }

    private Result execute(
            UUID actor,
            UUID tenant,
            UUID request,
            Action action,
            UUID membership,
            ManualIdentityInput input,
            char[] password) {
        if (request == null
                || tenant == null
                || action == null
                || action == Action.CREATE && input == null) {
            throw new Invalid();
        }
        boolean committing = false;
        String outcome = "UNAVAILABLE";
        Result delivery = null;
        try (var material = action == Action.ACTIVATE ? null : new TemporaryCredential();
                var connection = source.getConnection()) {
            PlatformDatabase.begin(connection, ROLE, false);
            try {
                String username;
                java.sql.Timestamp started;
                try (var statement =
                        connection.prepareStatement(
                                "SELECT username,clock_timestamp() FROM identity.users WHERE id=?"
                                        + " AND status='ACTIVE'")) {
                    statement.setObject(1, actor);
                    try (var rows = statement.executeQuery()) {
                        require(rows.next());
                        username = rows.getString(1);
                        started = rows.getTimestamp(2);
                    }
                }
                require(actor.equals(authentication.verify(username, password)));
                lock(connection, false);
                require(authorized(connection, actor, tenant, true));
                try (var statement =
                        connection.prepareStatement(
                                "SELECT id FROM tenant.tenants WHERE id=? FOR UPDATE")) {
                    statement.setObject(1, tenant);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            throw new Missing();
                        }
                    }
                }
                UUID user = UUID.randomUUID();
                if (action != Action.CREATE) {
                    try (var statement =
                            connection.prepareStatement(
                                    "SELECT user_id FROM tenant.memberships WHERE tenant_id=? AND"
                                            + " id=?")) {
                        statement.setObject(1, tenant);
                        statement.setObject(2, membership);
                        try (var rows = statement.executeQuery()) {
                            if (!rows.next()) {
                                throw new Missing();
                            }
                            user = rows.getObject(1, UUID.class);
                        }
                    }
                }
                // Pins F04 eligibility through COMMIT; a concurrent credential change invalidates
                // this proof.
                pin(connection, actor, user);
                authorize(connection, actor, tenant, true);
                try (var statement =
                        connection.prepareStatement(
                                "SELECT clock_timestamp()<=?::timestamptz+interval '10 seconds' AND"
                                    + " password_changed_at<=? FROM identity.user_credentials WHERE"
                                    + " user_id=?")) {
                    statement.setTimestamp(1, started);
                    statement.setTimestamp(2, started);
                    statement.setObject(3, actor);
                    try (var rows = statement.executeQuery()) {
                        require(rows.next() && rows.getBoolean(1));
                    }
                }
                Change value;
                try (var statement =
                        connection.prepareStatement(
                                "INSERT INTO"
                                    + " tenant.user_operations(request_id,actor_user_id,tenant_id,action,user_id,membership_id,username,display_name,email,temporary_hash,resulting_status)"
                                    + " VALUES(?,?,?,?,?,?,?,?,?,?,'PENDING') RETURNING"
                                    + " request_id,tenant_id,action,user_id,membership_id,resulting_status,temporary_expires_at,created_at,actor_user_id")) {
                    statement.setObject(1, request);
                    statement.setObject(2, actor);
                    statement.setObject(3, tenant);
                    statement.setString(4, action.name());
                    statement.setObject(5, user);
                    statement.setObject(6, membership == null ? UUID.randomUUID() : membership);
                    statement.setString(7, input == null ? null : input.username());
                    statement.setString(8, input == null ? null : input.displayName());
                    statement.setString(9, input == null ? null : input.email());
                    statement.setString(10, material == null ? null : material.hash());
                    try (var rows = statement.executeQuery()) {
                        require(rows.next());
                        value = change(rows);
                    }
                }
                committing = true;
                connection.commit();
                delivery =
                        new Result(
                                "CONFIRMED",
                                request,
                                value,
                                material == null ? null : material.take());
                return delivery;
            } catch (SQLException failure) {
                connection.rollback();
                outcome =
                        switch (failure.getSQLState() == null ? "" : failure.getSQLState()) {
                            case "23505", "P2801", "40001", "40P01", "55P03" -> "CONFLICT";
                            case "P2802" -> "INCOMPLETE_REQUIREMENTS";
                            case "P2804" -> "NOT_FOUND";
                            case "22023", "23514" -> "INVALID";
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
        } catch (SQLException | RuntimeException failure) {
            if (delivery != null) {
                delivery.close();
            }
            /* Neutral failure; never log credential-bearing statements. */
        }
        boolean recorded = rejection(actor, tenant, request, action, committing);
        return new Result(
                committing ? "UNKNOWN" : recorded ? outcome : "UNAVAILABLE", request, null, null);
    }

    private boolean rejection(
            UUID actor, UUID tenant, UUID request, Action action, boolean uncertain) {
        try (var connection = log.getConnection()) {
            PlatformDatabase.begin(connection, LOG_ROLE, false);
            try (var statement =
                    connection.prepareStatement(
                            "INSERT INTO"
                                + " audit.events(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)"
                                + " VALUES(clock_timestamp(),'USER',?,?,'TENANT',?,?,?,'{}'::jsonb)")) {
                statement.setObject(1, actor);
                statement.setString(2, "TENANT_USER_" + action.name());
                statement.setObject(3, tenant);
                statement.setString(4, uncertain ? "UNKNOWN" : "DENIED");
                statement.setObject(5, request);
                statement.executeUpdate();
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException failure) {
            return false;
        }
    }

    private static Member member(java.sql.ResultSet rows) throws SQLException {
        return new Member(
                rows.getObject(1, UUID.class),
                rows.getObject(2, UUID.class),
                rows.getString(3),
                rows.getString(4),
                rows.getString(5),
                rows.getBoolean(6),
                rows.getString(7),
                rows.getString(8),
                rows.getTimestamp(9).toInstant(),
                rows.getObject(10, UUID.class),
                rows.getBoolean(11),
                instant(rows, 12),
                rows.getBoolean(13),
                rows.getBoolean(14));
    }

    private static Change change(java.sql.ResultSet rows) throws SQLException {
        return new Change(
                rows.getObject(1, UUID.class),
                rows.getObject(2, UUID.class),
                rows.getString(3),
                rows.getObject(4, UUID.class),
                rows.getObject(5, UUID.class),
                rows.getString(6),
                instant(rows, 7),
                rows.getTimestamp(8).toInstant(),
                rows.getObject(9, UUID.class));
    }

    private static Instant instant(java.sql.ResultSet rows, int column) throws SQLException {
        var value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static boolean authorized(
            Connection connection, UUID actor, UUID tenant, boolean mutating) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT tenant.user_authority(?,?,?)")) {
            statement.setObject(1, actor);
            statement.setObject(2, tenant);
            statement.setBoolean(3, mutating);
            try (var rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private static void authorize(Connection connection, UUID actor, UUID tenant, boolean mutating)
            throws SQLException {
        require(authorized(connection, actor, tenant, mutating));
        try (var statement =
                connection.prepareStatement("SELECT id FROM tenant.tenants WHERE id=?")) {
            statement.setObject(1, tenant);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new Missing();
                }
            }
        }
    }

    private static void lock(Connection connection, boolean reading) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(
                    reading
                            ? "SELECT pg_advisory_xact_lock_shared(370019,1)"
                            : "SELECT pg_advisory_xact_lock(370019,1)");
            statement.execute(
                    reading
                            ? "SELECT pg_advisory_xact_lock_shared(410023,1)"
                            : "SELECT pg_advisory_xact_lock(410023,1)");
        }
    }

    private static void pin(Connection connection, UUID actor) throws SQLException {
        pin(connection, actor, actor);
    }

    private static void pin(Connection connection, UUID actor, UUID target) throws SQLException {
        for (String query :
                new String[] {
                    "SELECT id FROM identity.users WHERE id IN (?,?) ORDER BY id FOR SHARE",
                    "SELECT user_id FROM identity.user_credentials WHERE user_id IN (?,?) ORDER BY"
                            + " user_id FOR SHARE"
                }) {
            try (var statement = connection.prepareStatement(query)) {
                statement.setObject(1, actor);
                statement.setObject(2, target);
                try (var rows = statement.executeQuery()) {
                    require(rows.next());
                }
            }
        }
    }

    private static void require(boolean value) {
        if (!value) {
            throw new Denied();
        }
    }

    public static final class Denied extends RuntimeException {
        public Denied() {
            super("Operation unavailable");
        }
    }

    public static final class Missing extends RuntimeException {
        public Missing() {
            super("Resource unavailable");
        }
    }

    public static final class Unavailable extends RuntimeException {
        public Unavailable() {
            super("Service unavailable");
        }
    }

    public static final class Invalid extends RuntimeException {
        public Invalid() {
            super("Invalid request");
        }
    }
}

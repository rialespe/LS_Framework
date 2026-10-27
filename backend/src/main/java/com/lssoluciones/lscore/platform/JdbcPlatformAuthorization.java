package com.lssoluciones.lscore.platform;

import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Read-only global condition, separate from tenant/session authorization and business SQL admission. */
public final class JdbcPlatformAuthorization {
    public static final String ROLE = "ls_core_platform_reader_dev";
    private final DataSource source;
    private final JdbcPasswordAuthentication authentication;

    public JdbcPlatformAuthorization(DataSource source, JdbcPasswordAuthentication authentication) {
        this.source = Objects.requireNonNull(source);
        this.authentication = Objects.requireNonNull(authentication);
    }

    public enum Condition { NONE, OWNER, SUPERADMIN }
    public enum Action { DESIGNATE_SUPERADMIN, REVOKE_SUPERADMIN }
    public record Designation(UUID id, UUID userId, Condition kind, String status, Instant createdAt) { }
    /** Declarative proposal only, never an identity, authorization capability or mutation credential. */
    public record Proposal(UUID targetUserId, Action action) { }

    public Condition evaluate(String username, char[] password) {
        try {
            return read(username, password, false, (connection, user) -> condition(connection, user));
        } catch (Unavailable failure) {
            return Condition.NONE;
        }
    }

    public List<Designation> delegates(String username, char[] password) {
        return read(username, password, true, (connection, owner) -> {
            var result = new ArrayList<Designation>();
            try (var statement = connection.prepareStatement("SELECT id,user_id,administrator_kind,status,created_at "
                    + "FROM platform.administrators WHERE administrator_kind='SUPERADMIN' ORDER BY created_at,id LIMIT 100")) {
                statement.setQueryTimeout(5);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(new Designation(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class),
                                Condition.valueOf(rows.getString(3)), rows.getString(4), rows.getTimestamp(5).toInstant()));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    public Condition designation(String username, char[] password, UUID targetUserId) {
        return read(username, password, true, (connection, owner) -> condition(connection, targetUserId));
    }

    public Proposal prepare(String username, char[] password, UUID targetUserId, Action action) {
        return read(username, password, true, (connection, owner) -> {
            if (targetUserId == null || owner.equals(targetUserId) || action == null) {
                throw new Unavailable();
            }
            try (var statement = connection.prepareStatement("SELECT EXISTS(SELECT FROM identity.users WHERE id=? AND status='ACTIVE')")) {
                statement.setObject(1, targetUserId);
                statement.setQueryTimeout(5);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !rows.getBoolean(1)) {
                        throw new Unavailable();
                    }
                }
            }
            var current = condition(connection, targetUserId);
            if (action == Action.DESIGNATE_SUPERADMIN && current != Condition.NONE
                    || action == Action.REVOKE_SUPERADMIN && current != Condition.SUPERADMIN) {
                throw new Unavailable();
            }
            return new Proposal(targetUserId, action);
        });
    }

    public void execute(Proposal proposal) {
        // Intentionally no flag, writer role, SQL mutation, HTTP mapping or alternative CLI.
        throw new MutationDisabled();
    }

    private static Condition condition(Connection connection, UUID user) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT a.administrator_kind FROM platform.administrators a "
                + "JOIN identity.users u ON u.id=a.user_id WHERE u.id=? AND u.status='ACTIVE' "
                + "AND a.status='ACTIVE' AND a.revoked_at IS NULL")) {
            statement.setObject(1, user);
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Condition.NONE;
                }
                var result = Condition.valueOf(rows.getString(1));
                if (rows.next()) {
                    throw new SQLException();
                }
                return result;
            }
        }
    }

    private <T> T read(String username, char[] password, boolean ownerRequired, Query<T> query) {
        T result;
        try {
            var user = authentication.verify(username, password);
            try (var connection = source.getConnection()) {
                PlatformDatabase.begin(connection, ROLE, true);
                try {
                    if (ownerRequired && condition(connection, user) != Condition.OWNER) {
                        throw new Unavailable();
                    }
                    result = query.run(connection, user);
                    connection.commit();
                } catch (SQLException | RuntimeException failure) {
                    connection.rollback();
                    throw failure;
                }
            }
        } catch (SQLException | RuntimeException failure) {
            throw new Unavailable();
        }
        return result;
    }

    @FunctionalInterface
    private interface Query<T> {
        T run(Connection connection, UUID verifiedUser) throws SQLException;
    }

    public static final class Unavailable extends RuntimeException {
        private Unavailable() {
            super("Platform authorization unavailable");
        }
    }

    public static final class MutationDisabled extends RuntimeException {
        private MutationDisabled() {
            super("Global mutations disabled pending LFD-39");
        }
    }
}

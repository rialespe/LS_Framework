package com.lssoluciones.lscore.platform;

import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Controlled internal channel only. No Spring bean, HTTP adapter, session capability or retry. */
public final class JdbcPlatformExecutor {
    public static final String ROLE = PlatformMutationCoordination.ROLE;
    public static final String SECURITY_LOG_ROLE = "ls_core_platform_security_log_dev";
    private final DataSource source;
    private final DataSource securityLog;
    private final JdbcPasswordAuthentication authentication;

    public JdbcPlatformExecutor(DataSource source, DataSource securityLog, JdbcPasswordAuthentication authentication) {
        this.source = Objects.requireNonNull(source);
        this.securityLog = Objects.requireNonNull(securityLog);
        this.authentication = Objects.requireNonNull(authentication);
    }

    public enum Outcome { CONFIRMED, DENIED, UNAVAILABLE, UNKNOWN }
    public record Result(Outcome outcome, UUID correlation, boolean evidenceRecorded) { }

    /** Password is supplied anew by the operator for this call; only our private copy is retained and cleared. */
    public Result execute(String username, char[] password, UUID target, JdbcPlatformAuthorization.Action action) {
        char[] secret = password == null ? null : password.clone();
        UUID actor = null;
        UUID correlation = UUID.randomUUID();
        boolean committing = false;
        boolean confirmed = false;
        try (var connection = source.getConnection()) {
            PlatformDatabase.begin(connection, ROLE, false);
            try {
                Timestamp started;
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT clock_timestamp()")) {
                    rows.next();
                    started = rows.getTimestamp(1);
                }
                var decision = PlatformMutationCoordination.inspect(connection, authentication, username, secret,
                        new JdbcPlatformAuthorization.Proposal(target, action));
                actor = decision.verifiedOwner();
                if (decision.decision() != PlatformMutationCoordination.Decision.READY || !recent(connection, started)) {
                    connection.rollback();
                } else {
                    UUID designation = mutate(connection, actor, target, action);
                    correlation = evidence(connection, designation, action);
                    committing = true;
                    connection.commit();
                    confirmed = true;
                }
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException | RuntimeException failure) {
            // Never expose SQL/identity/credential diagnostics or retry a possibly acknowledged COMMIT.
        } finally {
            if (secret != null) {
                Arrays.fill(secret, '\0');
            }
        }
        if (confirmed) {
            return new Result(Outcome.CONFIRMED, correlation, true);
        }
        Outcome outcome = committing ? Outcome.UNKNOWN : Outcome.DENIED;
        boolean recorded = recordRejection(actor, target, action, correlation, outcome);
        return new Result(recorded || committing ? outcome : Outcome.UNAVAILABLE, correlation, recorded);
    }

    private static boolean recent(Connection connection, Timestamp started) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT clock_timestamp()<=?::timestamptz + interval '10 seconds'")) {
            statement.setTimestamp(1, started);
            try (var rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private static UUID mutate(Connection connection, UUID actor, UUID target, JdbcPlatformAuthorization.Action action) throws SQLException {
        String sql = action == JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN
                ? "INSERT INTO platform.administrators(user_id,administrator_kind,status,granted_by_user_id) VALUES (?,'SUPERADMIN','ACTIVE',?) RETURNING id"
                : "UPDATE platform.administrators SET status='REVOKED',revoked_by_user_id=? WHERE user_id=? AND administrator_kind='SUPERADMIN' AND status='ACTIVE' RETURNING id";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, action == JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN ? target : actor);
            statement.setObject(2, action == JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN ? actor : target);
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException();
                }
                UUID id = rows.getObject(1, UUID.class);
                if (rows.next()) {
                    throw new SQLException();
                }
                return id;
            }
        }
    }

    private static UUID evidence(Connection connection, UUID designation, JdbcPlatformAuthorization.Action action) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT correlation_id FROM audit.events WHERE outcome='SUCCESS' AND action_code=? "
                + "AND metadata=jsonb_build_object('designation_id',?::text)")) {
            statement.setString(1, code(action));
            statement.setString(2, designation.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException();
                }
                UUID correlation = rows.getObject(1, UUID.class);
                if (rows.next()) {
                    throw new SQLException();
                }
                return correlation;
            }
        }
    }

    private boolean recordRejection(UUID actor, UUID target, JdbcPlatformAuthorization.Action action, UUID correlation, Outcome outcome) {
        try (var connection = securityLog.getConnection()) {
            PlatformDatabase.begin(connection, SECURITY_LOG_ROLE, false);
            try (var statement = connection.prepareStatement("INSERT INTO audit.events(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata) "
                    + "VALUES (clock_timestamp(),'USER',?,?,'PLATFORM_USER',?,?,?,jsonb_build_object('reason',?::text))")) {
                statement.setObject(1, actor);
                statement.setString(2, code(action));
                statement.setObject(3, target);
                statement.setString(4, outcome == Outcome.UNKNOWN ? "UNKNOWN" : "DENIED");
                statement.setObject(5, correlation);
                statement.setString(6, outcome == Outcome.UNKNOWN ? "COMMIT_UNCERTAIN" : "REJECTED");
                statement.executeUpdate();
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                return false;
            }
        } catch (SQLException | RuntimeException failure) {
            return false;
        }
    }

    private static String code(JdbcPlatformAuthorization.Action action) {
        return action == null ? "PLATFORM_INVALID_REQUEST" : "PLATFORM_" + action.name();
    }
}

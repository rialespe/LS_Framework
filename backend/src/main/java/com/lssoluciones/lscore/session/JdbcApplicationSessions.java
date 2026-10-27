package com.lssoluciones.lscore.session;

import com.lssoluciones.lscore.identity.application.PasswordAuthentication;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** JDBC session persistence and bounded context transactions. No business SQL or session admission. */
public final class JdbcApplicationSessions {
    public static final String ROLE = "ls_core_session_manager_dev";
    private final DataSource source;
    private final PasswordAuthentication authentication;
    private final Map<LoginApplication, UUID> applications;
    private final Duration validity;
    private final Map<LoginApplication, Duration> idle;
    private final SecureRandom random = new SecureRandom();

    public JdbcApplicationSessions(DataSource source, PasswordAuthentication authentication,
            Map<LoginApplication, UUID> applications, Duration validity) {
        this(source, authentication, applications, validity, null);
    }

    public JdbcApplicationSessions(DataSource source, PasswordAuthentication authentication,
            Map<LoginApplication, UUID> applications, Duration validity, Map<LoginApplication, Duration> idle) {
        this.source = Objects.requireNonNull(source);
        if (!(authentication instanceof JdbcPasswordAuthentication) || validity == null
                || validity.compareTo(Duration.ofMinutes(1)) < 0 || validity.compareTo(Duration.ofHours(24)) > 0
                || applications.size() != 2 || applications.get(LoginApplication.FISCAL) == null
                || applications.get(LoginApplication.POS) == null
                || applications.get(LoginApplication.FISCAL).equals(applications.get(LoginApplication.POS))) {
            throw new IllegalArgumentException("Invalid application session configuration");
        }
        this.authentication = authentication;
        this.applications = Map.copyOf(applications);
        this.validity = validity;
        if (idle != null && (idle.size() != 2 || java.util.Arrays.stream(LoginApplication.values()).anyMatch(app ->
                idle.get(app) == null || idle.get(app).compareTo(Duration.ofSeconds(60)) < 0
                        || idle.get(app).compareTo(Duration.ofHours(24)) > 0))) {
            throw new IllegalArgumentException("Invalid idle timeout configuration");
        }
        this.idle = idle == null ? null : Map.copyOf(idle);
    }

    public long validitySeconds() {
        return validity.toSeconds();
    }

    /** Secret is consumed by Set-Cookie only; never returned in an HTTP body, URL or exception. */
    String login(LoginApplication application, String username, char[] password) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        try {
            UUID user = authentication.verify(username, password); // Mandatory transactional F04, including lockouts.
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            try (var connection = source.getConnection()) {
                begin(connection);
                try {
                    try (var statement = connection.prepareStatement("INSERT INTO security.sessions "
                            + "(id,user_id,application_id,token_hash,expires_at" + (multidevice() ? ",last_seen_at) " : ") ")
                            + "SELECT ?,u.id,a.id,?,clock_timestamp()+(? * interval '1 second') "
                            + (multidevice() ? ",clock_timestamp() " : "")
                            + "FROM identity.users u CROSS JOIN catalog.applications a "
                            + "WHERE u.id=? AND u.status='ACTIVE' AND a.id=? AND a.status='PUBLISHED'")) {
                        statement.setObject(1, UUID.randomUUID());
                        statement.setString(2, digest(token));
                        statement.setLong(3, validity.toSeconds());
                        statement.setObject(4, user);
                        statement.setObject(5, applications.get(application));
                        statement.setQueryTimeout(5);
                        if (statement.executeUpdate() != 1) {
                            throw new SQLException();
                        }
                    }
                    connection.commit();
                } catch (SQLException | RuntimeException failure) {
                    connection.rollback();
                    throw failure;
                }
            }
            return token; // Only after acknowledged commit and physical close. Never retry uncertainty.
        } catch (SQLException | RuntimeException failure) {
            throw new Rejected();
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    boolean multidevice() {
        return idle != null;
    }

    long idleSeconds(LoginApplication application) {
        return idle.get(application).toSeconds();
    }

    void requireSession(LoginApplication application, String token) {
        withSession(application, token, (connection, session) -> null);
    }

    void logout(LoginApplication application, String token) {
        withSession(application, token, (connection, session) -> {
            try (var statement = connection.prepareStatement("UPDATE security.sessions SET revoked_at=clock_timestamp(),"
                    + "updated_at=clock_timestamp() WHERE id=? AND revoked_at IS NULL")) {
                statement.setObject(1, session.id());
                statement.setQueryTimeout(5);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException();
                }
            }
            return null;
        });
    }

    // Package-only transaction composition. Subject data never escapes as a reusable credential.
    record Session(UUID id, UUID userId, UUID applicationId, java.time.Instant expiresAt) { }

    @FunctionalInterface
    interface SessionWork<T> {
        T run(Connection connection, Session session) throws SQLException;
    }

    <T> T withSession(LoginApplication application, String token, SessionWork<T> work) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new Rejected();
        }
        T result;
        try (var connection = source.getConnection()) {
            if (multidevice()) {
                // Serialize this cookie before taking the repeatable snapshot: parallel HTTP reads
                // now write activity. The owned physical PGSimple connection releases this lock on close.
                try (var lock = connection.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?,360018))")) {
                    lock.setString(1, digest(token));
                    lock.setQueryTimeout(5);
                    lock.execute();
                }
            }
            begin(connection);
            try {
                Session session;
                // Repeatable snapshot plus session row lock serializes replacement and logout.
                // Serialization conflicts fail closed; no retry after an uncertain commit.
                try (var statement = connection.prepareStatement("SELECT s.id,s.user_id,s.application_id,s.expires_at "
                        + "FROM security.sessions s WHERE s.token_hash=? AND s.application_id=? AND s.revoked_at IS NULL "
                        + "AND s.expires_at>clock_timestamp() "
                        + (multidevice() ? "AND coalesce(s.last_seen_at,s.created_at)>clock_timestamp()-(? * interval '1 second') " : "")
                        + "AND EXISTS(SELECT FROM identity.users u WHERE u.id=s.user_id AND u.status='ACTIVE') "
                        + "AND EXISTS(SELECT FROM catalog.applications a WHERE a.id=s.application_id AND a.status='PUBLISHED') "
                        + "FOR UPDATE OF s")) {
                    statement.setString(1, digest(token));
                    statement.setObject(2, applications.get(application));
                    if (multidevice()) {
                        statement.setLong(3, idleSeconds(application));
                    }
                    statement.setQueryTimeout(5);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            throw new Rejected();
                        }
                        session = new Session(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class),
                                rows.getObject(3, UUID.class), rows.getTimestamp(4).toInstant());
                        if (rows.next()) {
                            throw new Rejected();
                        }
                    }
                }
                if (multidevice()) {
                    try (var touch = connection.prepareStatement("UPDATE security.sessions SET last_seen_at=clock_timestamp() "
                            + "WHERE id=? AND revoked_at IS NULL AND expires_at>clock_timestamp() "
                            + "AND coalesce(last_seen_at,created_at)>clock_timestamp()-(? * interval '1 second')")) {
                        touch.setObject(1, session.id());
                        touch.setLong(2, idleSeconds(application));
                        touch.setQueryTimeout(5);
                        if (touch.executeUpdate() != 1) {
                            throw new Rejected();
                        }
                    }
                }
                result = work.run(connection, session);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (JdbcSessionContexts.Denied failure) {
            throw failure;
        } catch (SQLException | RuntimeException failure) {
            throw new Rejected();
        }
        return result;
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new Rejected();
        }
    }

    private static void begin(Connection connection) throws SQLException {
        connection.setReadOnly(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery("SELECT session_user,current_user,rolcanlogin AND rolconnlimit=3 "
                    + "AND NOT (rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication) "
                    + "AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid) "
                    + "AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o') "
                    + "FROM pg_roles r WHERE rolname=session_user AND current_database()='ls_core_dev'")) {
                if (!rows.next() || !ROLE.equals(rows.getString(1)) || !ROLE.equals(rows.getString(2)) || !rows.getBoolean(3)) {
                    throw new SQLException();
                }
            }
        }
    }

    public static final class Rejected extends RuntimeException {
        Rejected() {
            super("Authentication unavailable");
        }
    }
}

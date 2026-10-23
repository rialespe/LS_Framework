package com.lssoluciones.lscore.identity.infrastructure.jdbc;

import com.lssoluciones.lscore.identity.application.PasswordAuthentication;
import com.lssoluciones.lscore.identity.application.PasswordCredentialSource.Credential;
import com.lssoluciones.lscore.identity.application.PasswordCredentialVerifier;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Serializes attempts with recovery/V12 on the same identity-then-credential row lock order. */
public final class JdbcPasswordAuthentication implements PasswordAuthentication {
    public static final String ROLE = "ls_core_password_authenticator_dev";
    private final DataSource source;
    private final Limits limits;
    private final PasswordCredentialVerifier verifier = new PasswordCredentialVerifier(
            ignored -> Optional.empty(), Clock.systemUTC());

    /** After 5 failures: 30s, then 60s, 120s, ... capped at 30min. Locked attempts do not extend the lock. */
    public record Limits(int threshold, Duration initial, Duration maximum) {
        public Limits {
            if (threshold < 1 || threshold > 100 || initial == null || maximum == null
                    || initial.compareTo(Duration.ofSeconds(1)) < 0 || maximum.compareTo(initial) < 0
                    || maximum.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("Invalid authentication limits");
            }
        }

        public static Limits defaults() {
            return new Limits(5, Duration.ofSeconds(30), Duration.ofMinutes(30));
        }

        long seconds(int failures) {
            long delay = initial.toSeconds();
            for (int i = threshold; i < failures && delay < maximum.toSeconds(); i++) {
                delay = Math.min(maximum.toSeconds(), delay * 2);
            }
            return delay;
        }
    }

    public JdbcPasswordAuthentication(DataSource source, Limits limits) {
        this.source = Objects.requireNonNull(source);
        this.limits = Objects.requireNonNull(limits);
    }

    @Override
    public UUID verify(String username, char[] password) {
        if (username == null || username.isBlank() || username.length() > 1024 || password == null
                || password.length == 0 || password.length > 1024) {
            throw new Rejected();
        }
        char[] copy = password.clone();
        UUID matched;
        try (var connection = source.getConnection()) {
            JdbcPasswordLifecycle.begin(connection, ROLE);
            try {
                matched = attempt(connection, username, copy);
                connection.commit(); // Failed attempts must commit too; reject only after commit/close.
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException | RuntimeException failure) {
            throw new Rejected();
        } finally {
            Arrays.fill(copy, '\0');
        }
        if (matched == null) {
            throw new Rejected();
        }
        return matched;
    }

    private UUID attempt(Connection connection, String username, char[] password) throws SQLException {
        UUID id = null;
        String state = null;
        try (var statement = connection.prepareStatement("SELECT id,status FROM identity.users "
                + "WHERE lower(btrim(username))=lower(btrim(?)) FOR UPDATE")) {
            statement.setString(1, username);
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    id = rows.getObject(1, UUID.class);
                    state = rows.getString(2);
                }
            }
        }
        Credential credential = null;
        int failures = 0;
        if (id != null) {
            try (var statement = connection.prepareStatement("SELECT password_hash,must_change_password,"
                    + "password_expires_at,locked_until,failed_attempts FROM identity.user_credentials "
                    + "WHERE user_id=? FOR UPDATE")) {
                statement.setObject(1, id);
                statement.setQueryTimeout(5);
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        var locked = rows.getTimestamp(4);
                        credential = new Credential(id, state, rows.getString(1), rows.getTimestamp(3).toInstant(),
                                locked == null ? null : locked.toInstant(), rows.getBoolean(2));
                        failures = Math.max(0, rows.getInt(5));
                    }
                }
            }
        }
        boolean matched;
        try {
            // Use the existing KDF/state primitive; PostgreSQL alone decides expiry and lock time below.
            Credential proof = credential == null ? null : new Credential(credential.userId(), credential.status(),
                    credential.passwordHash(), Instant.MAX, null, credential.mustChangePassword());
            verifier.verifySnapshot(proof, password);
            matched = true;
        } catch (PasswordCredentialVerifier.CredentialRejectedException rejected) {
            matched = false;
        }
        Instant now = JdbcPasswordLifecycle.databaseTime(connection);
        if (credential == null || !"ACTIVE".equals(state) || !Boolean.FALSE.equals(credential.mustChangePassword())
                || !credential.passwordExpiresAt().isAfter(now)
                || credential.lockedUntil() != null && credential.lockedUntil().isAfter(now)) {
            return null;
        }
        int next = matched ? 0 : (int) Math.min(Integer.MAX_VALUE, (long) failures + 1);
        try (var statement = connection.prepareStatement("UPDATE identity.user_credentials SET failed_attempts=?,"
                + "locked_until=CASE WHEN ? THEN clock_timestamp()+(? * interval '1 second') ELSE NULL END,"
                + "updated_at=clock_timestamp() WHERE user_id=?")) {
            statement.setInt(1, next);
            statement.setBoolean(2, !matched && next >= limits.threshold());
            statement.setLong(3, limits.seconds(next));
            statement.setObject(4, id);
            statement.setQueryTimeout(5);
            if (statement.executeUpdate() != 1) {
                throw new SQLException();
            }
        }
        return matched ? id : null;
    }
}

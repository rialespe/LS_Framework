package com.lssoluciones.lscore.identity.infrastructure.jdbc;

import com.lssoluciones.lscore.identity.application.PasswordCredentialVerifier;
import java.nio.CharBuffer;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

/**
 * Internal, trusted JDBC boundary. Dedicated LOGIN possession authorizes administration, never a caller UUID/GUC.
 * The changer must remain private to this service: its DB credential grants credential maintenance, not business access.
 * No automatic retries: loss of a commit acknowledgement can leave an uncertain result.
 */
public final class JdbcPasswordLifecycle {
    public static final String ISSUER_ROLE = "ls_core_password_issuer_dev";
    public static final String CHANGER_ROLE = "ls_core_password_changer_dev";
    private static final String PREFIX = PasswordCredentialVerifier.HASH_PREFIX;
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private final DataSource issuer;
    private final DataSource changer;
    private final Duration temporaryValidity;
    private final SecureRandom random = new SecureRandom();
    private final Pbkdf2PasswordEncoder encoder = PasswordCredentialVerifier.newEncoder();

    public JdbcPasswordLifecycle(DataSource issuer, DataSource changer, Duration temporaryValidity) {
        this.issuer = Objects.requireNonNull(issuer);
        this.changer = Objects.requireNonNull(changer);
        if (temporaryValidity == null || temporaryValidity.isNegative() || temporaryValidity.isZero()
                || temporaryValidity.compareTo(Duration.ofHours(72)) > 0) {
            throw new IllegalArgumentException("Invalid temporary credential validity");
        }
        this.temporaryValidity = temporaryValidity;
    }

    /** Returns a closeable, one-read secret only after commit. Never use in a public response or logging. */
    public TemporaryPassword issue(UUID userId) {
        char[] secret = new char[32]; // 32 independent uniform symbols: 192 bits of entropy.
        for (int i = 0; i < secret.length; i++) {
            secret[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        try {
            persistTemporary(userId, secret);
            return new TemporaryPassword(secret);
        } catch (SQLException | RuntimeException failure) {
            throw new PasswordChangeRejectedException();
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private void persistTemporary(UUID userId, char[] secret) throws SQLException {
        try (var connection = issuer.getConnection()) {
            begin(connection, ISSUER_ROLE);
            try {
                String hash = PREFIX + encoder.encode(CharBuffer.wrap(secret));
                try (var statement = connection.prepareStatement("INSERT INTO identity.user_credentials "
                        + "(user_id,password_hash,must_change_password,password_changed_at,password_expires_at,"
                        + "failed_attempts,locked_until) SELECT id,?,true,clock_timestamp(),"
                        + "clock_timestamp() + (? * interval '1 millisecond'),0,NULL FROM identity.users "
                        + "WHERE id=? AND status='PENDING'")) {
                    statement.setString(1, hash);
                    statement.setLong(2, temporaryValidity.toMillis());
                    statement.setObject(3, userId);
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
    }

    /** Caller owns and must erase its input arrays. No identity/session/authorization token is returned. */
    public void exchange(String username, char[] temporaryPassword, char[] newPassword) {
        change(username, temporaryPassword, newPassword, true);
    }

    /** Expired definitive credentials may prove this restricted operation, never ordinary authentication. */
    public void renew(String username, char[] currentPassword, char[] newPassword) {
        change(username, currentPassword, newPassword, false);
    }

    private void change(String username, char[] previous, char[] replacement, boolean temporary) {
        if (username == null || username.isBlank() || username.length() > 1024 || previous == null
                || previous.length == 0 || previous.length > 1024 || !validPassword(replacement)) {
            throw new PasswordChangeRejectedException();
        }
        char[] oldCopy = previous.clone();
        char[] newCopy = replacement.clone();
        try (var connection = changer.getConnection()) {
            begin(connection, CHANGER_ROLE);
            try {
                UUID id;
                String status;
                // Lock the identity first, also serializing concurrent suspension/activation.
                try (var statement = connection.prepareStatement("SELECT id,status FROM identity.users "
                        + "WHERE lower(btrim(username))=lower(btrim(?)) FOR UPDATE")) {
                    statement.setString(1, username);
                    statement.setQueryTimeout(5);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            throw new SQLException();
                        }
                        id = rows.getObject(1, UUID.class);
                        status = rows.getString(2);
                    }
                }
                try (var statement = connection.prepareStatement("SELECT password_hash,must_change_password,"
                        + "password_expires_at,locked_until FROM identity.user_credentials WHERE user_id=? FOR UPDATE")) {
                    statement.setObject(1, id);
                    statement.setQueryTimeout(5);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            throw new SQLException();
                        }
                        String hash = rows.getString(1);
                        if (!(temporary ? "PENDING" : "ACTIVE").equals(status)
                                || rows.getBoolean(2) != temporary || !supported(hash)
                                || !encoder.matches(CharBuffer.wrap(oldCopy), hash.substring(PREFIX.length()))
                                || Arrays.equals(oldCopy, newCopy)) {
                            throw new SQLException();
                        }
                        // Database time is sampled AFTER the potentially slow KDF and lock wait.
                        Instant now = databaseTime(connection);
                        var locked = rows.getTimestamp(4);
                        if ((temporary && !rows.getTimestamp(3).toInstant().isAfter(now))
                                || locked != null && locked.toInstant().isAfter(now)) {
                            throw new SQLException();
                        }
                    }
                }
                String hash = PREFIX + encoder.encode(CharBuffer.wrap(newCopy));
                try (var statement = connection.prepareStatement("UPDATE identity.user_credentials SET password_hash=?,"
                        + "must_change_password=false,password_changed_at=clock_timestamp(),"
                        + "password_expires_at=clock_timestamp()+interval '90 days',updated_at=clock_timestamp() "
                        + "WHERE user_id=? AND (NOT ? OR password_expires_at>clock_timestamp())")) {
                    statement.setString(1, hash);
                    statement.setObject(2, id);
                    statement.setBoolean(3, temporary);
                    statement.setQueryTimeout(5);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException();
                    }
                }
                if (temporary) {
                    try (var statement = connection.prepareStatement("UPDATE identity.users SET status='ACTIVE',"
                            + "updated_at=clock_timestamp() WHERE id=? AND status='PENDING'")) {
                        statement.setObject(1, id);
                        statement.setQueryTimeout(5);
                        if (statement.executeUpdate() != 1) {
                            throw new SQLException();
                        }
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException | RuntimeException failure) {
            throw new PasswordChangeRejectedException();
        } finally {
            Arrays.fill(oldCopy, '\0');
            Arrays.fill(newCopy, '\0');
        }
    }

    static Instant databaseTime(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery("SELECT clock_timestamp()")) {
                rows.next();
                return rows.getTimestamp(1).toInstant();
            }
        }
    }

    private static boolean supported(String hash) {
        return hash != null && hash.matches("\\{pbkdf2-sha256-600000\\}[0-9a-f]{96}");
    }

    static boolean validPassword(char[] value) {
        if (value == null || value.length < 8 || value.length > 1024) {
            return false;
        }
        boolean letter = false;
        boolean number = false;
        boolean symbol = false;
        for (char c : value) {
            letter |= Character.isLetter(c);
            number |= Character.isDigit(c);
            symbol |= !Character.isLetterOrDigit(c) && !Character.isWhitespace(c) && !Character.isISOControl(c);
        }
        return letter && number && symbol;
    }

    static void begin(Connection connection, String role) throws SQLException {
        connection.setReadOnly(false);
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.execute("SET LOCAL lock_timeout='5s'");
            try (var rows = statement.executeQuery("SELECT session_user,current_user,"
                    + "rolcanlogin AND NOT (rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole "
                    + "OR rolreplication) AND rolconnlimit=3 "
                    + "AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid) "
                    + "AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass "
                    + "AND refobjid=r.oid AND deptype='o') FROM pg_roles r WHERE rolname=session_user")) {
                if (!rows.next() || !role.equals(rows.getString(1)) || !role.equals(rows.getString(2))
                        || !rows.getBoolean(3)) {
                    throw new SQLException();
                }
            }
        }
    }

    public static final class TemporaryPassword implements AutoCloseable {
        private char[] secret;

        private TemporaryPassword(char[] secret) {
            this.secret = secret.clone();
        }

        public synchronized char[] take() {
            if (secret == null) {
                throw new PasswordChangeRejectedException();
            }
            char[] result = secret.clone();
            close();
            return result;
        }

        @Override
        public synchronized void close() {
            if (secret != null) {
                Arrays.fill(secret, '\0');
                secret = null;
            }
        }

        @Override
        public String toString() {
            return "TemporaryPassword[REDACTED]";
        }
    }

    public static final class PasswordChangeRejectedException extends RuntimeException {
        private PasswordChangeRejectedException() {
            super("Password operation rejected");
        }
    }
}

package com.lssoluciones.lscore.identity.infrastructure.jdbc;

import com.lssoluciones.lscore.identity.application.PasswordCredentialVerifier;
import com.lssoluciones.lscore.identity.application.TrustedRecoveryMail;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

/**
 * Verified-mail recovery for ACTIVE definitive accounts only; never activates an identity or grants enterprise access.
 * No administrative recovery entry point: operator/tenant authority is still a F03/F05 dependency.
 * No delivery provider means request() is disabled and remains neutral. No automatic transaction/delivery retries.
 */
public final class JdbcPasswordRecovery {
    public static final String ROLE = "ls_core_password_recovery_dev";
    public static final String NEUTRAL = "If eligible, recovery instructions will be sent.";
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private final DataSource source;
    private final TrustedRecoveryMail mail;
    private final Limits limits;
    private final SecureRandom random = new SecureRandom();
    private final Pbkdf2PasswordEncoder encoder = PasswordCredentialVerifier.newEncoder();

    public record Limits(Duration tokenValidity, Duration cooldown, int perAccountHour, int globalHour) {
        public Limits {
            if (tokenValidity == null || cooldown == null || tokenValidity.compareTo(Duration.ofSeconds(1)) < 0
                    || tokenValidity.compareTo(Duration.ofHours(1)) > 0 || cooldown.compareTo(Duration.ofSeconds(1)) < 0
                    || cooldown.compareTo(Duration.ofHours(1)) > 0 || perAccountHour < 1 || perAccountHour > 10
                    || globalHour < 1 || globalHour > 10000) {
                throw new IllegalArgumentException("Invalid recovery limits");
            }
        }

        public static Limits defaults() {
            return new Limits(Duration.ofMinutes(15), Duration.ofMinutes(1), 3, 100);
        }
    }

    public JdbcPasswordRecovery(DataSource source, TrustedRecoveryMail mail, Limits limits) {
        this.source = Objects.requireNonNull(source);
        this.mail = mail;
        this.limits = Objects.requireNonNull(limits);
    }

    /** Same response for absent/unverified/ineligible/rate-limited accounts, unavailable provider or database. */
    public String request(String username) {
        if (mail == null || username == null || username.isBlank() || username.length() > 1024) {
            return NEUTRAL;
        }
        char[] token = new char[43]; // 258 random bits; only SHA-256 digest enters JDBC.
        for (int i = 0; i < token.length; i++) {
            token[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        try {
            String recipient = issue(username, digest(token));
            if (recipient != null) {
                try (var secret = new TrustedRecoveryMail.Secret(token)) {
                    mail.send(recipient, secret); // Only AFTER acknowledged commit and connection close.
                }
            }
        } catch (SQLException | RuntimeException unavailable) {
            // Delivery/commit uncertainty is intentionally not retried or exposed.
        } finally {
            Arrays.fill(token, '\0');
        }
        return NEUTRAL;
    }

    private String issue(String username, byte[] digest) throws SQLException {
        String recipient;
        try (var connection = source.getConnection()) {
            JdbcPasswordLifecycle.begin(connection, ROLE);
            try {
                Account account = lockAccount(connection, username, null);
                recipient = account == null ? null : account.email();
                if (account != null) {
                    // Serialize all issuance quotas, across processes/replicas, after the identity lock.
                    try (var statement = connection.createStatement()) {
                        statement.setQueryTimeout(5);
                        statement.execute("SELECT pg_advisory_xact_lock(330033,17)");
                    }
                    if (allowed(connection, account.id())) {
                        try (var statement = connection.prepareStatement("INSERT INTO identity.password_recovery_tokens "
                                + "(token_digest,user_id,credential_fingerprint,email,email_verified_at,expires_at) "
                                + "VALUES (?,?,?,?,?,clock_timestamp()+(? * interval '1 second'))")) {
                            statement.setBytes(1, digest);
                            statement.setObject(2, account.id());
                            statement.setBytes(3, fingerprint(account.hash()));
                            statement.setString(4, account.email());
                            statement.setTimestamp(5, account.verifiedAt());
                            statement.setLong(6, limits.tokenValidity().toSeconds());
                            statement.setQueryTimeout(5);
                            statement.executeUpdate();
                        }
                    } else {
                        recipient = null;
                    }
                    try (var statement = connection.prepareStatement("DELETE FROM identity.password_recovery_tokens "
                            + "WHERE user_id=? AND expires_at<clock_timestamp()-interval '24 hours'")) {
                        statement.setObject(1, account.id());
                        statement.setQueryTimeout(5);
                        statement.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
        return recipient;
    }

    private boolean allowed(Connection connection, UUID id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT "
                + "count(*) FILTER (WHERE user_id=?) < ? AND count(*) < ? AND "
                + "count(*) FILTER (WHERE user_id=? AND created_at>clock_timestamp()-(? * interval '1 second'))=0 "
                + "FROM identity.password_recovery_tokens WHERE created_at>clock_timestamp()-interval '1 hour'")) {
            statement.setObject(1, id);
            statement.setInt(2, limits.perAccountHour());
            statement.setInt(3, limits.globalHour());
            statement.setObject(4, id);
            statement.setLong(5, limits.cooldown().toSeconds());
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    /** A recovery token proves only credential replacement; it is never a session or business identity. */
    public void recover(char[] token, char[] newPassword) {
        if (mail == null || token == null || token.length != 43 || !JdbcPasswordLifecycle.validPassword(newPassword)) {
            throw new Rejected();
        }
        for (char c : token) {
            if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_')) {
                throw new Rejected();
            }
        }
        char[] copy = newPassword.clone();
        char[] tokenCopy = token.clone();
        try (var connection = source.getConnection()) {
            JdbcPasswordLifecycle.begin(connection, ROLE);
            try {
                consume(connection, digest(tokenCopy), copy);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException | RuntimeException failure) {
            throw new Rejected();
        } finally {
            Arrays.fill(copy, '\0');
            Arrays.fill(tokenCopy, '\0');
        }
    }

    private void consume(Connection connection, byte[] digest, char[] password) throws SQLException {
        UUID id;
        try (var statement = connection.prepareStatement("SELECT user_id FROM identity.password_recovery_tokens "
                + "WHERE token_digest=?")) {
            statement.setBytes(1, digest);
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException();
                }
                id = rows.getObject(1, UUID.class);
            }
        }
        Account account = lockAccount(connection, null, id);
        if (account == null) {
            throw new SQLException();
        }
        try (var statement = connection.prepareStatement("SELECT credential_fingerprint,email,email_verified_at,"
                + "consumed_at,expires_at>clock_timestamp() FROM identity.password_recovery_tokens "
                + "WHERE token_digest=? AND user_id=? FOR UPDATE")) {
            statement.setBytes(1, digest);
            statement.setObject(2, id);
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getTimestamp(4) != null || !rows.getBoolean(5)
                        || !MessageDigest.isEqual(fingerprint(account.hash()), rows.getBytes(1))
                        || !account.email().equals(rows.getString(2)) || !account.verifiedAt().equals(rows.getTimestamp(3))) {
                    throw new SQLException();
                }
            }
        }
        String hash = PasswordCredentialVerifier.HASH_PREFIX + encoder.encode(CharBuffer.wrap(password));
        try (var statement = connection.prepareStatement("UPDATE identity.password_recovery_tokens "
                + "SET consumed_at=clock_timestamp() WHERE token_digest=? AND consumed_at IS NULL "
                + "AND expires_at>clock_timestamp()")) {
            statement.setBytes(1, digest);
            statement.setQueryTimeout(5);
            if (statement.executeUpdate() != 1) {
                throw new SQLException();
            }
        }
        try (var statement = connection.prepareStatement("UPDATE identity.user_credentials SET password_hash=?,"
                + "must_change_password=false,password_changed_at=clock_timestamp(),"
                + "password_expires_at=clock_timestamp()+interval '90 days',failed_attempts=0,locked_until=NULL,"
                + "updated_at=clock_timestamp() WHERE user_id=?")) {
            statement.setString(1, hash);
            statement.setObject(2, id);
            statement.setQueryTimeout(5);
            if (statement.executeUpdate() != 1) {
                throw new SQLException();
            }
        }
        try (var statement = connection.prepareStatement("UPDATE identity.password_recovery_tokens "
                + "SET consumed_at=clock_timestamp() WHERE user_id=? AND consumed_at IS NULL")) {
            statement.setObject(1, id);
            statement.setQueryTimeout(5);
            statement.executeUpdate();
        }
    }

    private static Account lockAccount(Connection connection, String username, UUID id) throws SQLException {
        UUID found;
        String email;
        java.sql.Timestamp verified;
        String state;
        String lookup = id == null ? "lower(btrim(username))=lower(btrim(?))" : "id=?";
        try (var statement = connection.prepareStatement("SELECT id,email,email_verified_at,status FROM identity.users "
                + "WHERE " + lookup + " FOR UPDATE")) {
            if (id == null) {
                statement.setString(1, username);
            } else {
                statement.setObject(1, id);
            }
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                found = rows.getObject(1, UUID.class);
                email = rows.getString(2);
                verified = rows.getTimestamp(3);
                state = rows.getString(4);
            }
        }
        if (!"ACTIVE".equals(state) || email == null || email.isBlank() || verified == null) {
            return null;
        }
        try (var statement = connection.prepareStatement("SELECT password_hash,must_change_password "
                + "FROM identity.user_credentials WHERE user_id=? FOR UPDATE")) {
            statement.setObject(1, found);
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getBoolean(2)) {
                    return null;
                }
                return new Account(found, email, verified, rows.getString(1));
            }
        }
    }

    private record Account(UUID id, String email, java.sql.Timestamp verifiedAt, String hash) {
        @Override
        public String toString() {
            return "RecoveryAccount[REDACTED]";
        }
    }

    private static byte[] fingerprint(String hash) {
        return sha256(hash.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] digest(char[] secret) {
        byte[] bytes = new byte[secret.length];
        for (int i = 0; i < secret.length; i++) {
            bytes[i] = (byte) secret[i];
        }
        try {
            return sha256(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Recovery unavailable");
        }
    }

    public static final class Rejected extends RuntimeException {
        private Rejected() {
            super("Recovery rejected");
        }
    }
}

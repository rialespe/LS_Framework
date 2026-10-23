package com.lssoluciones.lscore.identity.infrastructure.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lssoluciones.lscore.identity.application.ManualIdentityInput;
import com.lssoluciones.lscore.identity.application.PasswordAuthentication;
import com.lssoluciones.lscore.identity.application.PasswordCredentialVerifier;
import com.lssoluciones.lscore.identity.application.TrustedRecoveryMail;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.mock.env.MockEnvironment;

/** One integration of real authentication, quotas, mail recovery, concurrent attempts and SQL role isolation. */
@EnabledIfEnvironmentVariable(named = "LS_RECOVERY_TEST_ROOT", matches = ".+")
class PasswordRecoveryPostgresTest {
    private PGSimpleDataSource source(String user, String password) throws Exception {
        Path root = Path.of(System.getenv("LS_RECOVERY_TEST_ROOT")).toRealPath();
        assertEquals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath(), root.getParent());
        assertTrue(root.getFileName().toString().startsWith("ls-core-migrations-test-"));
        assertEquals("17", Files.readString(root.resolve("data/PG_VERSION")).trim());
        var pid = Files.readAllLines(root.resolve("data/postmaster.pid"));
        int port = Integer.parseInt(System.getenv("LS_RECOVERY_TEST_PORT"));
        assertNotEquals(5432, port);
        assertNotEquals(55432, port);
        assertEquals(Integer.toString(port), pid.get(3));
        assertEquals(root.resolve("data").toRealPath(), Path.of(pid.get(1)).toRealPath());
        var source = new PGSimpleDataSource();
        source.setServerNames(new String[] {"127.0.0.1"});
        source.setPortNumbers(new int[] {port});
        source.setDatabaseName("ls_core_dev");
        source.setUser(user);
        source.setPassword(password);
        source.setConnectTimeout(2);
        source.setSocketTimeout(5);
        return source;
    }

    private PGSimpleDataSource role(String role) throws Exception {
        return source(role, System.getenv("LS_RECOVERY_TEST_" + role.toUpperCase(java.util.Locale.ROOT)));
    }

    @Test
    void lockoutsAndRecoveryAreTransactionalAndRestricted() throws Exception {
        var authSource = role(JdbcPasswordAuthentication.ROLE);
        var recoverySource = role(JdbcPasswordRecovery.ROLE);
        var settings = new MockEnvironment().withProperty("LS_AUTHENTICATION_DB_HOST", "127.0.0.1")
                .withProperty("LS_AUTHENTICATION_DB_PORT", Integer.toString(authSource.getPortNumbers()[0]))
                .withProperty("LS_AUTHENTICATION_DB_NAME", "ls_core_dev")
                .withProperty("LS_AUTHENTICATION_DB_PASSWORD", System.getenv("LS_RECOVERY_TEST_" + JdbcPasswordAuthentication.ROLE.toUpperCase(java.util.Locale.ROOT)));
        var auth = new PasswordCredentialConfiguration().passwordAuthentication(settings);
        var registration = new JdbcManualIdentityRegistration(role(JdbcManualIdentityRegistration.DATABASE_ROLE));
        var lifecycle = new JdbcPasswordLifecycle(role(JdbcPasswordLifecycle.ISSUER_ROLE),
                role(JdbcPasswordLifecycle.CHANGER_ROLE), Duration.ofHours(72));
        List<char[]> delivered = new ArrayList<>();
        TrustedRecoveryMail controlledMail = (recipient, secret) -> {
            assertTrue(recipient.endsWith("@example.test"));
            assertEquals("RecoverySecret[REDACTED]", secret.toString());
            delivered.add(secret.take());
            assertThrows(IllegalStateException.class, secret::take);
        };
        var recovery = new JdbcPasswordRecovery(recoverySource, controlledMail, JdbcPasswordRecovery.Limits.defaults());
        char[] password = "Definitiva-330!".toCharArray();
        char[] replacement = "Recuperada-331!".toCharArray();
        char[] wrong = "Incorrecta-332!".toCharArray();
        try (var admin = role("postgres").getConnection(); var sql = admin.createStatement()) {
            UUID id = active(registration, lifecycle, "f33-main", password);
            UUID unverified = active(registration, lifecycle, "f33-unverified", password);
            UUID raceId = active(registration, lifecycle, "f33-race", password);
            sql.execute("UPDATE identity.users SET email_verified_at=clock_timestamp() WHERE id IN ('" + id + "','" + raceId + "')");
            assertEquals(id, auth.verify(" F33-MAIN ", password));
            rejected(() -> auth.verify("f33-main", wrong));
            assertEquals("1", text(admin, "SELECT failed_attempts FROM identity.user_credentials WHERE user_id='" + id + "'"));
            assertEquals(2, concurrent(() -> {
                rejected(() -> auth.verify("f33-main", wrong));
                return true;
            }));
            assertEquals("3", text(admin, "SELECT failed_attempts FROM identity.user_credentials WHERE user_id='" + id + "'"));
            rejected(() -> auth.verify("f33-main", wrong));
            rejected(() -> auth.verify("f33-main", wrong));
            assertTrue(flag(admin, "SELECT failed_attempts=5 AND extract(epoch FROM locked_until-clock_timestamp()) "
                    + "BETWEEN 20 AND 30 FROM identity.user_credentials WHERE user_id='" + id + "'"));
            String lock = text(admin, "SELECT row_to_json(c)::text FROM identity.user_credentials c WHERE user_id='" + id + "'");
            rejected(() -> auth.verify("f33-main", password));
            rejected(() -> auth.verify("f33-main", wrong));
            assertTrue(lock.equals(text(admin, "SELECT row_to_json(c)::text FROM identity.user_credentials c WHERE user_id='" + id + "'")));
            expireLock(admin, id);
            rejected(() -> auth.verify("f33-main", wrong));
            assertTrue(flag(admin, "SELECT failed_attempts=6 AND extract(epoch FROM locked_until-clock_timestamp()) "
                    + "BETWEEN 50 AND 60 FROM identity.user_credentials WHERE user_id='" + id + "'"));
            expireLock(admin, id);
            assertEquals(id, auth.verify("f33-main", password));
            assertTrue(flag(admin, "SELECT failed_attempts=0 AND locked_until IS NULL FROM identity.user_credentials WHERE user_id='" + id + "'"));
            for (String state : new String[] {"SUSPENDED", "BLOCKED", "UNKNOWN", "PENDING"}) {
                sql.execute("UPDATE identity.users SET status='" + state + "' WHERE id='" + id + "'");
                rejected(() -> auth.verify("f33-main", password));
                neutral(recovery, "f33-main");
            }
            sql.execute("UPDATE identity.users SET status='ACTIVE' WHERE id='" + id + "'");
            rejected(() -> auth.verify("f33-absent", wrong));
            neutral(recovery, "f33-absent");
            neutral(recovery, "f33-unverified");
            assertEquals(0, delivered.size());
            var disabled = new JdbcPasswordRecovery(recoverySource, null, JdbcPasswordRecovery.Limits.defaults());
            neutral(disabled, "f33-main");
            assertEquals("0", text(admin, "SELECT count(*) FROM identity.password_recovery_tokens"));
            neutral(recovery, "f33-main");
            assertEquals(1, delivered.size());
            char[] expired = delivered.get(0);
            neutral(recovery, "f33-main");
            assertEquals(1, delivered.size()); // Cooldown applied without revealing why.
            assertTrue(flag(admin, "SELECT octet_length(token_digest)=32 AND octet_length(credential_fingerprint)=32 "
                    + "AND abs(extract(epoch FROM expires_at-created_at)-900)<1 FROM identity.password_recovery_tokens"));
            assertTrue(!text(admin, "SELECT row_to_json(t)::text FROM identity.password_recovery_tokens t").contains(new String(expired)));
            sql.execute("UPDATE identity.password_recovery_tokens SET created_at=clock_timestamp()-interval '30 minutes',"
                    + "expires_at=clock_timestamp()-interval '1 second'");
            recoveryRejected(() -> recovery.recover(expired, replacement));
            neutral(recovery, "f33-main");
            assertEquals(2, delivered.size());
            char[] valid = delivered.get(1);
            recoveryRejected(() -> disabled.recover(valid, replacement));
            ageRequests(admin, id);
            neutral(recovery, "f33-main");
            assertEquals(3, delivered.size());
            char[] other = delivered.get(2);
            ageRequests(admin, id);
            neutral(recovery, "f33-main");
            assertEquals(3, delivered.size()); // Three-per-hour account quota includes consumed/expired requests.
            for (String weak : new String[] {"Short1!", "NoNumber!", "12345678!", "NoSymbol12"}) {
                recoveryRejected(() -> recovery.recover(valid, weak.toCharArray()));
            }
            sql.execute("UPDATE identity.user_credentials SET failed_attempts=6,locked_until=clock_timestamp()+interval '1 minute' "
                    + "WHERE user_id='" + id + "'");
            recovery.recover(valid, replacement);
            recoveryRejected(() -> recovery.recover(valid, password));
            recoveryRejected(() -> recovery.recover(other, password));
            assertEquals("0", text(admin, "SELECT count(*) FROM identity.password_recovery_tokens WHERE user_id='" + id + "' AND consumed_at IS NULL"));
            assertTrue(flag(admin, "SELECT failed_attempts=0 AND locked_until IS NULL AND NOT must_change_password "
                    + "AND abs(extract(epoch FROM password_expires_at-password_changed_at)-7776000)<1 "
                    + "FROM identity.user_credentials WHERE user_id='" + id + "'"));
            assertEquals(id, auth.verify("f33-main", replacement));
            rejected(() -> auth.verify("f33-main", password));
            assertEquals(id, auth.verify("f33-main", replacement));

            // Global quota is independent of the target account's own quota.
            var globalLimited = new JdbcPasswordRecovery(recoverySource, controlledMail,
                    new JdbcPasswordRecovery.Limits(Duration.ofMinutes(15), Duration.ofMinutes(1), 3, 1));
            neutral(globalLimited, "f33-race");
            assertEquals(3, delivered.size());
            neutral(recovery, "f33-race");
            assertEquals(4, delivered.size());
            char[] staleEmail = delivered.get(3);
            sql.execute("UPDATE identity.users SET status='SUSPENDED' WHERE id='" + raceId + "'");
            recoveryRejected(() -> recovery.recover(staleEmail, replacement));
            sql.execute("UPDATE identity.users SET status='ACTIVE',email_verified_at=clock_timestamp() WHERE id='" + raceId + "'");
            recoveryRejected(() -> recovery.recover(staleEmail, replacement));
            ageRequests(admin, raceId);
            neutral(recovery, "f33-race");
            assertEquals(5, delivered.size());
            char[] staleCredential = delivered.get(4);
            lifecycle.renew("f33-race", password, replacement);
            recoveryRejected(() -> recovery.recover(staleCredential, password));
            ageRequests(admin, raceId);
            neutral(recovery, "f33-race");
            assertEquals(6, delivered.size());
            char[] raceToken = delivered.get(5);
            assertEquals(1, concurrent(() -> {
                try {
                    recovery.recover(raceToken, password);
                    return true;
                } catch (JdbcPasswordRecovery.Rejected denied) {
                    return false;
                }
            }));
            assertEquals(raceId, auth.verify("f33-race", password));
            recoveryRejected(() -> recovery.recover(raceToken, replacement));
            // An unverified identity remains ineligible throughout.
            sql.execute("UPDATE identity.users SET email_verified_at=NULL WHERE id='" + unverified + "'");
            neutral(recovery, "f33-unverified");
            assertEquals(6, delivered.size());

            var oldVerifier = new PasswordCredentialVerifier(new JdbcPasswordCredentialSource(
                    role(JdbcPasswordCredentialSource.DATABASE_ROLE)), Clock.systemUTC());
            assertThrows(PasswordCredentialVerifier.CredentialRejectedException.class,
                    () -> oldVerifier.verify("f33-main", replacement));
            for (String principal : new String[] {"ls_core_app_dev", "ls_core_credential_verifier_dev",
                    "ls_core_identity_registrar_dev", "ls_core_password_issuer_dev", "ls_core_password_changer_dev",
                    "ls_core_migrator_dev", "postgres"}) {
                var deniedAuth = new JdbcPasswordAuthentication(role(principal), JdbcPasswordAuthentication.Limits.defaults());
                rejected(() -> deniedAuth.verify("f33-main", replacement));
                var deniedRecovery = new JdbcPasswordRecovery(role(principal), controlledMail, JdbcPasswordRecovery.Limits.defaults());
                neutral(deniedRecovery, "f33-main");
                recoveryRejected(() -> deniedRecovery.recover(raceToken, replacement));
                if (!principal.equals("postgres") && !principal.equals("ls_core_migrator_dev")) {
                    try (var connection = role(principal).getConnection()) {
                        denied(connection, "SELECT token_digest FROM identity.password_recovery_tokens");
                        denied(connection, "UPDATE identity.user_credentials SET failed_attempts=0");
                        denied(connection, "SET ROLE " + JdbcPasswordRecovery.ROLE);
                    }
                }
            }
            assertEquals(6, delivered.size());
            try (var connection = authSource.getConnection()) {
                denied(connection, "SELECT token_digest FROM identity.password_recovery_tokens");
                denied(connection, "UPDATE identity.user_credentials SET password_hash='forbidden'");
                denied(connection, "UPDATE identity.users SET updated_at=clock_timestamp() WHERE id='" + id + "'");
            }
            try (var connection = recoverySource.getConnection()) {
                denied(connection, "UPDATE identity.users SET status='ACTIVE'");
                denied(connection, "UPDATE identity.users SET email_verified_at=clock_timestamp()");
                denied(connection, "SELECT id FROM tenant.memberships");
            }
            assertTrue(flag(admin, "SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class WHERE oid IN "
                    + "('identity.users'::regclass,'identity.user_credentials'::regclass,'identity.password_recovery_tokens'::regclass)"));
            try (var connection = role("ls_core_migrator_dev").getConnection()) {
                assertEquals("0", text(connection, "SELECT count(*) FROM identity.password_recovery_tokens"));
            }
            assertEquals("0", text(admin, "SELECT count(*) FROM pg_stat_activity WHERE usename IN "
                    + "('ls_core_password_authenticator_dev','ls_core_password_recovery_dev')"));
            String log = Files.readString(Path.of(System.getenv("LS_RECOVERY_TEST_ROOT")).resolve("server.log"));
            for (char[] secret : delivered) {
                assertTrue(!log.contains(new String(secret)));
            }
            assertTrue(!log.contains(new String(password)) && !log.contains(new String(replacement)));
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(replacement, '\0');
            Arrays.fill(wrong, '\0');
            delivered.forEach(value -> Arrays.fill(value, '\0'));
        }
    }

    private static UUID active(JdbcManualIdentityRegistration registration, JdbcPasswordLifecycle lifecycle,
            String username, char[] password) {
        UUID id = registration.register(new ManualIdentityInput(username, "Recovery fixture", username + "@example.test"));
        try (var temporary = lifecycle.issue(id)) {
            char[] secret = temporary.take();
            try {
                lifecycle.exchange(username, secret, password);
            } finally {
                Arrays.fill(secret, '\0');
            }
        }
        return id;
    }

    private static int concurrent(Callable<Boolean> operation) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt = () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return operation.call();
            };
            var first = workers.submit(attempt);
            var second = workers.submit(attempt);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
        }
    }

    private static void expireLock(Connection connection, UUID id) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("UPDATE identity.user_credentials SET locked_until=clock_timestamp()-interval '1 second' WHERE user_id='" + id + "'");
        }
    }

    private static void ageRequests(Connection connection, UUID id) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("UPDATE identity.password_recovery_tokens SET created_at=clock_timestamp()-interval '2 minutes' WHERE user_id='" + id + "'");
        }
    }

    private static String text(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static boolean flag(Connection connection, String sql) throws SQLException {
        return "t".equals(text(connection, sql));
    }

    private static void neutral(JdbcPasswordRecovery recovery, String username) {
        assertEquals(JdbcPasswordRecovery.NEUTRAL, recovery.request(username));
    }

    private static void denied(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            assertEquals("42501", assertThrows(SQLException.class, () -> statement.execute(sql)).getSQLState());
        }
    }

    private static void rejected(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(PasswordAuthentication.Rejected.class, action);
        assertEquals("Invalid credentials", failure.getMessage());
        assertNull(failure.getCause());
    }

    private static void recoveryRejected(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(JdbcPasswordRecovery.Rejected.class, action);
        assertEquals("Recovery rejected", failure.getMessage());
        assertNull(failure.getCause());
    }
}

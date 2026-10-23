package com.lssoluciones.lscore.identity.infrastructure.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lssoluciones.lscore.identity.application.ManualIdentityInput;
import com.lssoluciones.lscore.identity.application.PasswordCredentialVerifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

/** One real lifecycle integration, invoked once by the owned ephemeral PG17 harness. */
@EnabledIfEnvironmentVariable(named = "LS_LIFECYCLE_TEST_ROOT", matches = ".+")
class PasswordLifecyclePostgresTest {
    private PGSimpleDataSource source(String user, String password) throws Exception {
        Path root = Path.of(System.getenv("LS_LIFECYCLE_TEST_ROOT")).toRealPath();
        assertEquals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath(), root.getParent());
        assertTrue(root.getFileName().toString().startsWith("ls-core-migrations-test-"));
        assertEquals("17", Files.readString(root.resolve("data/PG_VERSION")).trim());
        var pid = Files.readAllLines(root.resolve("data/postmaster.pid"));
        int port = Integer.parseInt(System.getenv("LS_LIFECYCLE_TEST_PORT"));
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
        return source(role, System.getenv("LS_LIFECYCLE_TEST_" + role.toUpperCase(java.util.Locale.ROOT)));
    }

    @Test
    void issueExchangeRenewAndConcurrentConsumption() throws Exception {
        var issuer = role(JdbcPasswordLifecycle.ISSUER_ROLE);
        var changer = role(JdbcPasswordLifecycle.CHANGER_ROLE);
        var adminSource = role("postgres");
        var verifierSource = role(JdbcPasswordCredentialSource.DATABASE_ROLE);
        var registrar = new JdbcManualIdentityRegistration(role(JdbcManualIdentityRegistration.DATABASE_ROLE));
        var service = new JdbcPasswordLifecycle(issuer, changer, Duration.ofHours(72));
        var verifier = new PasswordCredentialVerifier(new JdbcPasswordCredentialSource(verifierSource), Clock.systemUTC());
        char[] definitive = "Definitiva-902!".toCharArray();
        char[] renewed = "Renovada-903!".toCharArray();
        char[] wrong = "Incorrecta-904!".toCharArray();
        char[] temporary = null;
        char[] concurrentSecret = null;
        try (var admin = adminSource.getConnection(); var sql = admin.createStatement()) {
            UUID id = register(registrar, "f32-lifecycle");
            try (var once = service.issue(id)) {
                assertEquals("TemporaryPassword[REDACTED]", once.toString());
                temporary = once.take();
                assertEquals(32, temporary.length);
                rejected(() -> once.take());
            }
            char[] initial = temporary;
            assertTrue(text(admin, "SELECT password_hash FROM identity.user_credentials WHERE user_id='" + id + "'")
                    .matches("\\{pbkdf2-sha256-600000\\}[0-9a-f]{96}"));
            assertTrue(!text(admin, "SELECT row_to_json(c)::text FROM identity.user_credentials c WHERE user_id='" + id + "'")
                    .contains(new String(initial)));
            assertTrue(flag(admin, "SELECT must_change_password AND failed_attempts=0 AND locked_until IS NULL "
                    + "AND abs(extract(epoch FROM password_expires_at-password_changed_at)-259200)<1 "
                    + "FROM identity.user_credentials WHERE user_id='" + id + "'"));
            rejected(() -> service.issue(id));
            assertThrows(PasswordCredentialVerifier.CredentialRejectedException.class,
                    () -> verifier.verify("f32-lifecycle", initial));
            rejected(() -> service.exchange("f32-lifecycle", wrong, definitive));
            for (String weak : new String[] {"Ab1!", "abcdefgh!", "12345678!", "Abcdefg12", "Abcdef12 "}) {
                rejected(() -> service.exchange("f32-lifecycle", initial, weak.toCharArray()));
            }
            service.exchange(" F32-LIFECYCLE ", initial, definitive);
            assertEquals("ACTIVE", text(admin, "SELECT status FROM identity.users WHERE id='" + id + "'"));
            assertTrue(flag(admin, "SELECT NOT must_change_password AND "
                    + "abs(extract(epoch FROM password_expires_at-password_changed_at)-7776000)<1 "
                    + "FROM identity.user_credentials WHERE user_id='" + id + "'"));
            assertEquals(id, verifier.verify("f32-lifecycle", definitive));
            rejected(() -> service.exchange("f32-lifecycle", initial, renewed));
            rejected(() -> service.renew("f32-lifecycle", initial, renewed));
            rejected(() -> service.renew("f32-lifecycle", wrong, renewed));
            rejected(() -> service.renew("f32-lifecycle", definitive, definitive));
            rejected(() -> service.issue(id));
            String oldHash = text(admin, "SELECT password_hash FROM identity.user_credentials WHERE user_id='" + id + "'");
            sql.execute("UPDATE identity.user_credentials SET password_expires_at=clock_timestamp()-interval '1 second' "
                    + "WHERE user_id='" + id + "'");
            assertThrows(PasswordCredentialVerifier.CredentialRejectedException.class,
                    () -> verifier.verify("f32-lifecycle", definitive));
            service.renew("f32-lifecycle", definitive, renewed);
            assertEquals(id, verifier.verify("f32-lifecycle", renewed));
            assertTrue(!oldHash.equals(text(admin,
                    "SELECT password_hash FROM identity.user_credentials WHERE user_id='" + id + "'")));
            assertTrue(flag(admin, "SELECT NOT must_change_password AND "
                    + "abs(extract(epoch FROM password_expires_at-password_changed_at)-7776000)<1 "
                    + "FROM identity.user_credentials WHERE user_id='" + id + "'"));
            assertThrows(PasswordCredentialVerifier.CredentialRejectedException.class,
                    () -> verifier.verify("f32-lifecycle", definitive));
            sql.execute("UPDATE identity.users SET status='SUSPENDED' WHERE id='" + id + "'");
            rejected(() -> service.renew("f32-lifecycle", renewed, definitive));
            assertThrows(PasswordCredentialVerifier.CredentialRejectedException.class,
                    () -> verifier.verify("f32-lifecycle", renewed));

            UUID negativeId = register(registrar, "f32-negative");
            try (var result = service.issue(negativeId)) {
                char[] secret = result.take();
                try {
                    String snapshot = text(admin, "SELECT row_to_json(c)::text FROM identity.user_credentials c "
                            + "WHERE user_id='" + negativeId + "'");
                    for (String state : new String[] {"SUSPENDED", "BLOCKED", "UNKNOWN", "ACTIVE"}) {
                        sql.execute("UPDATE identity.users SET status='" + state + "' WHERE id='" + negativeId + "'");
                        rejected(() -> service.exchange("f32-negative", secret, definitive));
                        rejected(() -> service.renew("f32-negative", secret, definitive));
                    }
                    sql.execute("UPDATE identity.users SET status='PENDING' WHERE id='" + negativeId + "'");
                    sql.execute("UPDATE identity.user_credentials SET locked_until=clock_timestamp()+interval '1 hour' "
                            + "WHERE user_id='" + negativeId + "'");
                    rejected(() -> service.exchange("f32-negative", secret, definitive));
                    sql.execute("UPDATE identity.user_credentials SET locked_until=NULL WHERE user_id='" + negativeId + "'");
                    assertTrue(snapshot.equals(text(admin, "SELECT row_to_json(c)::text FROM identity.user_credentials c "
                            + "WHERE user_id='" + negativeId + "'")));
                    sql.execute("UPDATE identity.user_credentials SET password_expires_at=clock_timestamp()-interval '1 second' "
                            + "WHERE user_id='" + negativeId + "'");
                    rejected(() -> service.exchange("f32-negative", secret, definitive));
                    assertEquals("PENDING", text(admin, "SELECT status FROM identity.users WHERE id='" + negativeId + "'"));
                } finally {
                    Arrays.fill(secret, '\0');
                }
            }

            UUID raceId = register(registrar, "f32-race");
            try (var result = service.issue(raceId)) {
                concurrentSecret = result.take();
            }
            char[] raceSecret = concurrentSecret;
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                java.util.concurrent.Callable<Boolean> attempt = () -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        service.exchange("f32-race", raceSecret, definitive);
                        return true;
                    } catch (JdbcPasswordLifecycle.PasswordChangeRejectedException denied) {
                        return false;
                    }
                };
                var first = workers.submit(attempt);
                var second = workers.submit(attempt);
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                assertEquals(1, (first.get(15, TimeUnit.SECONDS) ? 1 : 0) + (second.get(15, TimeUnit.SECONDS) ? 1 : 0));
            }
            assertEquals(raceId, verifier.verify("f32-race", definitive));
            rejected(() -> service.exchange("f32-race", raceSecret, renewed));

            UUID deniedId = register(registrar, "f32-denied");
            for (String role : new String[] {"ls_core_app_dev", "ls_core_credential_verifier_dev",
                    "ls_core_identity_registrar_dev", "ls_core_migrator_dev", "postgres"}) {
                var unauthorized = new JdbcPasswordLifecycle(role(role), role(role), Duration.ofHours(72));
                rejected(() -> unauthorized.issue(deniedId));
                rejected(() -> unauthorized.renew("f32-race", definitive, renewed));
                if (!role.equals("postgres") && !role.equals("ls_core_migrator_dev")) {
                    try (var connection = role(role).getConnection()) {
                        denied(connection, "UPDATE identity.user_credentials SET password_hash='forbidden'");
                        denied(connection, "INSERT INTO identity.user_credentials(user_id,password_hash,must_change_password,"
                                + "password_changed_at,password_expires_at,failed_attempts) VALUES ('" + deniedId
                                + "','forbidden',true,now(),now(),0)");
                        denied(connection, "SET ROLE " + JdbcPasswordLifecycle.CHANGER_ROLE);
                    }
                }
            }
            try (var connection = issuer.getConnection()) {
                denied(connection, "SELECT password_hash FROM identity.user_credentials");
                denied(connection, "UPDATE identity.user_credentials SET must_change_password=false");
                denied(connection, "UPDATE identity.users SET status='ACTIVE'");
            }
            try (var connection = changer.getConnection()) {
                denied(connection, "INSERT INTO identity.user_credentials(user_id) VALUES ('" + deniedId + "')");
                denied(connection, "UPDATE identity.user_credentials SET locked_until=NULL");
                denied(connection, "SELECT id FROM tenant.memberships");
            }
            assertTrue(flag(admin, "SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class WHERE oid IN "
                    + "('identity.users'::regclass,'identity.user_credentials'::regclass)"));
            assertEquals("0", text(admin, "SELECT count(*) FROM pg_stat_activity WHERE usename IN "
                    + "('ls_core_password_issuer_dev','ls_core_password_changer_dev')"));
            // Secrets are not persisted in PostgreSQL statement/error logs, even for refused operations.
            String log = Files.readString(Path.of(System.getenv("LS_LIFECYCLE_TEST_ROOT")).resolve("server.log"));
            assertTrue(!log.contains(new String(initial)) && !log.contains(new String(raceSecret))
                    && !log.contains(new String(definitive)) && !log.contains(new String(renewed)));
        } finally {
            Arrays.fill(definitive, '\0');
            Arrays.fill(renewed, '\0');
            Arrays.fill(wrong, '\0');
            if (temporary != null) {
                Arrays.fill(temporary, '\0');
            }
            if (concurrentSecret != null) {
                Arrays.fill(concurrentSecret, '\0');
            }
        }
    }

    private static UUID register(JdbcManualIdentityRegistration registrar, String username) {
        return registrar.register(new ManualIdentityInput(username, "Lifecycle fixture", null));
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

    private static void denied(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            assertEquals("42501", assertThrows(SQLException.class, () -> statement.execute(sql)).getSQLState());
        }
    }

    private static void rejected(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(JdbcPasswordLifecycle.PasswordChangeRejectedException.class, action);
        assertEquals("Password operation rejected", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }
}

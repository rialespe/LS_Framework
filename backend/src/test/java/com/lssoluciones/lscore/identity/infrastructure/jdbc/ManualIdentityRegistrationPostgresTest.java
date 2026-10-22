package com.lssoluciones.lscore.identity.infrastructure.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lssoluciones.lscore.identity.application.ManualIdentityInput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

/** A single persistent registration scenario on the existing harness-owned PostgreSQL 17. */
@EnabledIfEnvironmentVariable(named = "LS_REGISTRATION_TEST_ROOT", matches = ".+")
class ManualIdentityRegistrationPostgresTest {
    private static final String ROLE = JdbcManualIdentityRegistration.DATABASE_ROLE;

    private PGSimpleDataSource source(String user, String password) throws Exception {
        Path root = Path.of(System.getenv("LS_REGISTRATION_TEST_ROOT")).toRealPath();
        assertEquals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath(), root.getParent());
        assertTrue(root.getFileName().toString().startsWith("ls-core-migrations-test-"));
        assertEquals("17", Files.readString(root.resolve("data/PG_VERSION")).trim());
        var pid = Files.readAllLines(root.resolve("data/postmaster.pid"));
        int port = Integer.parseInt(System.getenv("LS_REGISTRATION_TEST_PORT"));
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

    @Test
    void registrationPersistsPendingUnverifiedIdentitiesAndPreservesExistingUsers() throws Exception {
        var registrar = source(ROLE, System.getenv("LS_REGISTRATION_TEST_PASSWORD"));
        var adminSource = source("postgres", System.getenv("LS_REGISTRATION_TEST_ADMIN_PASSWORD"));
        var verifierSource = source(JdbcPasswordCredentialSource.DATABASE_ROLE,
                System.getenv("LS_REGISTRATION_TEST_VERIFIER_PASSWORD"));
        var runtime = source("ls_core_app_dev", System.getenv("LS_REGISTRATION_TEST_APP_PASSWORD"));
        var service = ManualIdentityRegistrationCommand.service(Map.of(
                "LS_REGISTRATION_DB_HOST", "127.0.0.1",
                "LS_REGISTRATION_DB_PORT", Integer.toString(registrar.getPortNumbers()[0]),
                "LS_REGISTRATION_DB_NAME", "ls_core_dev",
                "LS_REGISTRATION_DB_PASSWORD", System.getenv("LS_REGISTRATION_TEST_PASSWORD")));
        var first = new ManualIdentityInput(" F31-Manual ", "Manual Fixture", null);
        var second = new ManualIdentityInput("f31-email", "Email Fixture", "Unverified@Example.test");
        try (var admin = adminSource.getConnection(); var statement = admin.createStatement()) {
            String oldUsers = snapshot(admin, "SELECT coalesce(jsonb_agg(to_jsonb(u) ORDER BY id),'[]')::text "
                    + "FROM identity.users u");
            String oldCredentials = snapshot(admin, "SELECT coalesce(jsonb_agg(to_jsonb(c) ORDER BY user_id),'[]')::text "
                    + "FROM identity.user_credentials c");
            UUID firstId = service.register(first);
            UUID secondId = service.register(second);
            // Read through an independent administrative connection: register returned only after COMMIT/close.
            for (UUID id : new UUID[] {firstId, secondId}) {
                var input = id.equals(firstId) ? first : second;
                try (var read = admin.prepareStatement("SELECT username,display_name,email,email_verified_at,status "
                        + "FROM identity.users WHERE id=?")) {
                    read.setObject(1, id);
                    try (var rows = read.executeQuery()) {
                        assertTrue(rows.next());
                        assertTrue(input.username().equals(rows.getString(1)));
                        assertTrue(input.displayName().equals(rows.getString(2)));
                        assertTrue(java.util.Objects.equals(input.email(), rows.getString(3)));
                        assertNull(rows.getObject(4));
                        assertEquals("PENDING", rows.getString(5));
                        assertTrue(!rows.next());
                    }
                }
            }
            rejected(service, new ManualIdentityInput("f31-manual", "Duplicate", null));
            rejected(service, null);
            for (String blank : new String[] {null, "", " ", "\t", "\u2003"}) {
                assertThrows(IllegalArgumentException.class, () -> new ManualIdentityInput(blank, "Name", null));
                assertThrows(IllegalArgumentException.class, () -> new ManualIdentityInput("user", blank, null));
            }
            assertThrows(IllegalArgumentException.class, () -> new ManualIdentityInput("user", "Name", " "));

            try (var connection = registrar.getConnection()) {
                // Exact V7 SQLSTATE, then a healthy subsequent call: no precheck, upsert or aborted transaction.
                denied(connection, "INSERT INTO identity.users(username,display_name) "
                        + "VALUES ('  F31-MANUAL  ','Duplicate')", "23505");
                for (String sql : new String[] {"SELECT id FROM identity.users",
                        "SELECT password_hash FROM identity.user_credentials",
                        "INSERT INTO identity.users(username,display_name,status) VALUES ('x','x','ACTIVE')",
                        "INSERT INTO identity.users(username,display_name,email_verified_at) "
                            + "VALUES ('x','x',CURRENT_TIMESTAMP)",
                        "UPDATE identity.users SET status='ACTIVE'", "DELETE FROM identity.users",
                        "SET ROLE postgres", "SET ROLE ls_core_migrator_dev", "SET ROLE ls_core_credential_verifier_dev"}) {
                    denied(connection, sql, "42501");
                }
                // Fixture-only bad default proves RLS rejects ACTIVE even with a valid INSERT column ACL.
                statement.execute("ALTER TABLE identity.users ALTER COLUMN status SET DEFAULT 'ACTIVE'");
                try {
                    denied(connection, "INSERT INTO identity.users(username,display_name) "
                            + "VALUES ('f31-forbidden','Fixture')", "42501");
                    rejected(service, new ManualIdentityInput("f31-forbidden", "Fixture", null));
                } finally {
                    statement.execute("ALTER TABLE identity.users ALTER COLUMN status SET DEFAULT 'PENDING'");
                }
            }
            for (var source : new PGSimpleDataSource[] {runtime, verifierSource, adminSource}) {
                rejected(new JdbcManualIdentityRegistration(source), new ManualIdentityInput("f31-denied", "Fixture", null));
            }
            for (var source : new PGSimpleDataSource[] {runtime, verifierSource}) {
                try (var connection = source.getConnection()) {
                    denied(connection, "INSERT INTO identity.users(username,display_name) VALUES ('f31-denied','Fixture')",
                            "42501");
                    denied(connection, "SET ROLE " + ROLE, "42501");
                }
            }
            // Healthy approved JDBC reader; do not rerun PBKDF2/V10 integration or rely on a denied hash read.
            var credentials = new JdbcPasswordCredentialSource(verifierSource);
            assertTrue(credentials.findByUsername("f04-active").isPresent());
            assertTrue(credentials.findByUsername(first.username()).isEmpty());
            assertTrue(credentials.findByUsername(second.username()).isEmpty());
            try (var rows = statement.executeQuery("SELECT count(*) FROM identity.users WHERE username LIKE 'f31-%' "
                    + "OR username=' F31-Manual '")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
            assertTrue(oldUsers.equals(snapshot(admin, "SELECT coalesce(jsonb_agg(to_jsonb(u) ORDER BY id),'[]')::text "
                    + "FROM identity.users u WHERE id NOT IN ('" + firstId + "','" + secondId + "')")));
            assertTrue(oldCredentials.equals(snapshot(admin,
                    "SELECT coalesce(jsonb_agg(to_jsonb(c) ORDER BY user_id),'[]')::text FROM identity.user_credentials c")));
            try (var rows = statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE usename='" + ROLE + "'")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    private static String snapshot(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static void denied(Connection connection, String sql, String state) throws Exception {
        try (var statement = connection.createStatement()) {
            assertEquals(state, assertThrows(SQLException.class, () -> statement.execute(sql)).getSQLState());
        }
    }

    private static void rejected(JdbcManualIdentityRegistration service, ManualIdentityInput input) {
        var failure = assertThrows(JdbcManualIdentityRegistration.RegistrationRejectedException.class,
                () -> service.register(input));
        assertEquals("Identity registration unavailable", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }
}

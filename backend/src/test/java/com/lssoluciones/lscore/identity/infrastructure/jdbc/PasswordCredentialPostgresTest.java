package com.lssoluciones.lscore.identity.infrastructure.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lssoluciones.lscore.identity.application.PasswordCredentialVerifier;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm;

/** One real JDBC/SCRAM scenario, exclusively on the harness-owned disposable PostgreSQL 17. */
@EnabledIfEnvironmentVariable(named = "LS_CREDENTIAL_TEST_ROOT", matches = ".+")
class PasswordCredentialPostgresTest {
    private static final String ROLE = JdbcPasswordCredentialSource.DATABASE_ROLE;
    private static final UUID ACTIVE = UUID.fromString("f0400000-0000-4000-8000-000000000001");

    private PGSimpleDataSource source(String user, String password) throws Exception {
        Path root = Path.of(System.getenv("LS_CREDENTIAL_TEST_ROOT")).toRealPath();
        assertEquals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath(), root.getParent());
        assertTrue(root.getFileName().toString().startsWith("ls-core-migrations-test-"));
        assertEquals("17", Files.readString(root.resolve("data/PG_VERSION")).trim());
        var pid = Files.readAllLines(root.resolve("data/postmaster.pid"));
        int port = Integer.parseInt(System.getenv("LS_CREDENTIAL_TEST_PORT"));
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
    void realCredentialsRespectStatesColumnPrivilegesRlsAndFailClosed() throws Exception {
        var login = source(ROLE, System.getenv("LS_CREDENTIAL_TEST_PASSWORD"));
        var adminSource = source("postgres", System.getenv("LS_CREDENTIAL_TEST_ADMIN_PASSWORD"));
        var enterprise = source("ls_core_app_dev", System.getenv("LS_CREDENTIAL_TEST_APP_PASSWORD"));
        String password = UUID.randomUUID().toString();
        var encoder = new Pbkdf2PasswordEncoder("", 16, 600000, SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        String hash = PasswordCredentialVerifier.HASH_PREFIX + encoder.encode(password);
        try (var admin = adminSource.getConnection()) {
            // Administrative writes are synthetic fixture setup only; the production verifier is read-only.
            for (int index = 1; index <= 5; index++) {
                UUID id = UUID.fromString("f0400000-0000-4000-8000-00000000000" + index);
                String username = new String[] {"f04-active", "f04-suspended", "f04-expired",
                        "f04-locked", "f04-change"}[index - 1];
                try (var statement = admin.prepareStatement(
                        "INSERT INTO identity.users(id,username,display_name,status) VALUES (?,?,?,?)")) {
                    statement.setObject(1, id);
                    statement.setString(2, username);
                    statement.setString(3, "Synthetic credential fixture");
                    statement.setString(4, index == 2 ? "SUSPENDED" : "ACTIVE");
                    statement.executeUpdate();
                }
                try (var statement = admin.prepareStatement("INSERT INTO identity.user_credentials "
                        + "(user_id,password_hash,must_change_password,password_changed_at,password_expires_at,"
                        + "failed_attempts,locked_until) VALUES (?,?,?,CURRENT_TIMESTAMP,"
                        + "CURRENT_TIMESTAMP + (? * INTERVAL '1 day'),0,"
                        + "CASE WHEN ? THEN CURRENT_TIMESTAMP + INTERVAL '1 day' ELSE NULL END)")) {
                    statement.setObject(1, id);
                    statement.setString(2, hash);
                    statement.setBoolean(3, index == 5);
                    statement.setInt(4, index == 3 ? -1 : 1);
                    statement.setBoolean(5, index == 4);
                    statement.executeUpdate();
                }
            }

            // Use the existing production configuration as well as the real JDBC source.
            var settings = new MockEnvironment().withProperty("LS_IDENTITY_DB_HOST", "127.0.0.1")
                    .withProperty("LS_IDENTITY_DB_PORT", Integer.toString(login.getPortNumbers()[0]))
                    .withProperty("LS_IDENTITY_DB_NAME", "ls_core_dev")
                    .withProperty("LS_IDENTITY_DB_PASSWORD", System.getenv("LS_CREDENTIAL_TEST_PASSWORD"));
            var verifier = new PasswordCredentialConfiguration().passwordCredentialVerifier(settings);
            assertEquals(ACTIVE, verifier.verify("  F04-ACTIVE  ", password.toCharArray()));
            rejected(verifier, "f04-active", "incorrect");
            for (String username : new String[] {"missing", "f04-suspended", "f04-expired",
                    "f04-locked", "f04-change", "f04-active' OR true --"}) {
                rejected(verifier, username, password);
            }

            try (var connection = login.getConnection(); var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT session_user,current_user,r.rolcanlogin AND "
                        + "NOT (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolbypassrls OR r.rolreplication "
                        + "OR r.rolinherit) AND r.rolconnlimit=3 AND NOT EXISTS "
                        + "(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid) AND NOT EXISTS "
                        + "(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass "
                        + "AND refobjid=r.oid AND deptype='o') FROM pg_roles r WHERE rolname=current_user")) {
                    assertTrue(rows.next());
                    assertEquals(ROLE, rows.getString(1));
                    assertEquals(ROLE, rows.getString(2));
                    assertTrue(rows.getBoolean(3));
                }
                try (var rows = statement.executeQuery("SELECT bool_and(relrowsecurity AND relforcerowsecurity) "
                        + "FROM pg_class WHERE oid IN ('identity.users'::regclass,"
                        + "'identity.user_credentials'::regclass)")) {
                    assertTrue(rows.next());
                    assertTrue(rows.getBoolean(1));
                }
                try (var rows = statement.executeQuery("SELECT bool_and(has_column_privilege(current_user,"
                        + "c.oid,a.attnum,'SELECT') = CASE WHEN c.oid='identity.users'::regclass THEN "
                        + "a.attname IN ('id','username','status') ELSE a.attname IN "
                        + "('user_id','password_hash','password_expires_at','locked_until','must_change_password') "
                        + "END) FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid "
                        + "WHERE c.oid IN ('identity.users'::regclass,'identity.user_credentials'::regclass) "
                        + "AND a.attnum>0 AND NOT a.attisdropped")) {
                    assertTrue(rows.next());
                    assertTrue(rows.getBoolean(1));
                }
                for (String sql : new String[] {"SELECT email FROM identity.users",
                        "SELECT failed_attempts FROM identity.user_credentials", "SELECT id FROM tenant.tenants",
                        "UPDATE identity.users SET status='ACTIVE'", "DELETE FROM identity.user_credentials",
                        "SET ROLE ls_core_migrator_dev", "SET ROLE postgres"}) {
                    denied(connection, sql);
                }
            }
            try (var connection = enterprise.getConnection()) {
                denied(connection, "SELECT password_hash FROM identity.user_credentials");
                denied(connection, "SET ROLE ls_core_credential_verifier_dev");
            }
            // Real server permission denial, not a mocked SQLException. The healthy connection succeeded above.
            try (var statement = admin.createStatement()) {
                statement.execute("REVOKE SELECT(password_hash) ON identity.user_credentials FROM " + ROLE);
            }
            var deniedSource = new JdbcPasswordCredentialSource(login);
            var unavailable = assertThrows(JdbcPasswordCredentialSource.CredentialSourceUnavailableException.class,
                    () -> deniedSource.findByUsername("f04-active"));
            assertEquals("Credential source unavailable", unavailable.getMessage());
            assertNull(unavailable.getCause());
            rejected(verifier, "f04-active", password);

            // Reserve a loopback TCP endpoint without listening: no shared server or port guessing.
            try (var unused = SocketChannel.open()) {
                unused.bind(new InetSocketAddress("127.0.0.1", 0));
                int port = ((InetSocketAddress) unused.getLocalAddress()).getPort();
                assertNotEquals(5432, port);
                assertNotEquals(55432, port);
                login.setPortNumbers(new int[] {port});
                var offline = new PasswordCredentialVerifier(new JdbcPasswordCredentialSource(login), Clock.systemUTC());
                rejected(offline, "f04-active", password);
            }
            assertThrows(JdbcPasswordCredentialSource.CredentialSourceUnavailableException.class,
                    () -> new JdbcPasswordCredentialSource(adminSource).findByUsername("f04-active"));
        }
    }

    private static void denied(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            assertEquals("42501", assertThrows(SQLException.class, () -> statement.execute(sql)).getSQLState());
        }
    }

    private static void rejected(PasswordCredentialVerifier verifier, String username, String password) {
        var failure = assertThrows(PasswordCredentialVerifier.CredentialRejectedException.class,
                () -> verifier.verify(username, password.toCharArray()));
        assertEquals("Invalid credentials", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }
}

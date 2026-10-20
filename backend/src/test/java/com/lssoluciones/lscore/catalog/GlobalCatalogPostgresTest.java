package com.lssoluciones.lscore.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

/** Real SCRAM authentication, only against the harness-owned disposable PG17 instance. */
@EnabledIfEnvironmentVariable(named = "LS_CATALOG_TEST_ROOT", matches = ".+")
class GlobalCatalogPostgresTest {
    private InspectingSource source() throws Exception {
        Path root = Path.of(System.getenv("LS_CATALOG_TEST_ROOT")).toRealPath();
        assertEquals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath(), root.getParent());
        assertTrue(root.getFileName().toString().startsWith("ls-core-migrations-test-"));
        assertEquals("17", Files.readString(root.resolve("data/PG_VERSION")).trim());
        var pid = Files.readAllLines(root.resolve("data/postmaster.pid"));
        int port = Integer.parseInt(System.getenv("LS_CATALOG_TEST_PORT"));
        assertNotEquals(5432, port);
        assertEquals(Integer.toString(port), pid.get(3));
        assertEquals(root.resolve("data").toRealPath(), Path.of(pid.get(1)).toRealPath());
        var source = new InspectingSource();
        source.setServerNames(new String[] {"127.0.0.1"});
        source.setPortNumbers(new int[] {port});
        source.setDatabaseName("ls_core_dev");
        source.setUser("ls_core_catalog_service_dev");
        source.setPassword(System.getenv("LS_CATALOG_TEST_PASSWORD"));
        source.setConnectTimeout(3);
        source.setSocketTimeout(10);
        return source;
    }

    @Test
    void fixedQueriesMaterializePublicColumnsAndCloseCleanly() throws Exception {
        var source = source();
        var reader = new GlobalCatalogReader(source);
        var applications = reader.applications();
        assertEquals(1, applications.size());
        assertEquals("ls-core", applications.getFirst().code());
        assertTrue(source.last.isClosed());
        var modules = reader.modules(applications.getFirst().id());
        assertEquals(3, modules.size());
        assertTrue(modules.stream().allMatch(module -> module.applicationId().equals(applications.getFirst().id())));
        assertTrue(reader.modules(UUID.randomUUID()).isEmpty());
        assertEquals(3, source.cleanCloses);
        assertTrue(source.last.isClosed());
    }

    @Test
    void sqlFailureRollsBackRoleAndClosesWithoutLeakingDiagnostics() throws Exception {
        var source = source();
        source.failQuery = true;
        var reader = new GlobalCatalogReader(source);
        var failure = assertThrows(GlobalCatalogReader.CatalogUnavailableException.class, reader::applications);
        assertEquals("Global catalog unavailable", failure.getMessage());
        assertEquals(null, failure.getCause());
        assertEquals(1, source.cleanCloses);
        assertTrue(source.last.isClosed());
        source.failQuery = false;
        assertEquals(1, reader.applications().size());
        assertEquals(2, source.cleanCloses);
    }

    @Test
    void realServiceCannotReadWithoutLocalRoleOrEscalateBeyondCatalog() throws Exception {
        var source = source();
        try (var connection = source.getConnection()) {
            denied(connection, "SELECT id FROM catalog.applications");
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL ROLE ls_core_catalog_reader_dev");
            }
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT session_user,current_user")) {
                rows.next();
                assertEquals("ls_core_catalog_service_dev", rows.getString(1));
                assertEquals("ls_core_catalog_reader_dev", rows.getString(2));
            }
            connection.commit();
            connection.setAutoCommit(true);
            denied(connection, "SELECT id FROM catalog.applications");
            for (String sql : new String[] {"SELECT created_at FROM catalog.applications",
                    "SELECT * FROM identity.user_credentials", "SELECT * FROM tenant.tenants",
                    "SELECT * FROM audit.events", "SELECT * FROM operations.flyway_schema_history",
                    "UPDATE catalog.applications SET name=name WHERE false", "CREATE TABLE catalog.forbidden(id int)",
                    "SET ROLE ls_core_migrator_dev", "SET ROLE ls_core_app_dev", "SET ROLE postgres"}) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL ROLE ls_core_catalog_reader_dev");
                }
                denied(connection, sql);
                connection.rollback();
                connection.setAutoCommit(true);
            }
            denied(connection, "SELECT id FROM catalog.applications");
        }
    }

    private static void denied(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            assertEquals("42501", assertThrows(SQLException.class, () -> statement.execute(sql)).getSQLState());
        }
    }

    /** Observe the actual connection before physical close, including after a failed query. */
    private static final class InspectingSource extends PGSimpleDataSource {
        private Connection last;
        private int cleanCloses;
        private boolean failQuery;

        @Override
        public Connection getConnection() throws SQLException {
            last = super.getConnection();
            Connection physical = last;
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("prepareStatement")) {
                            try (var statement = physical.createStatement();
                                    var rows = statement.executeQuery(
                                            "SELECT current_user, current_setting('transaction_read_only')")) {
                                rows.next();
                                assertEquals("ls_core_catalog_reader_dev", rows.getString(1));
                                assertEquals("on", rows.getString(2));
                            }
                            if (failQuery) {
                                // Genuine server ACL failure inside the production read-only transaction.
                                return physical.prepareStatement("SELECT password_hash FROM identity.user_credentials");
                            }
                        }
                        if (method.getName().equals("close")) {
                            try {
                                try (var statement = physical.createStatement();
                                        var rows = statement.executeQuery("SELECT current_user, session_user")) {
                                    rows.next();
                                    assertEquals("ls_core_catalog_service_dev", rows.getString(1));
                                    assertEquals(rows.getString(1), rows.getString(2));
                                    cleanCloses++;
                                }
                            } finally {
                                physical.close();
                            }
                            return null;
                        }
                        try {
                            return method.invoke(physical, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }
}

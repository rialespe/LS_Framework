package com.lssoluciones.lscore.operations.infrastructure.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lssoluciones.lscore.access.application.context.ContextFixture;
import com.lssoluciones.lscore.access.application.context.ContextRejectedException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

/** Runs only from the existing disposable-cluster Python harness, with its limited runtime role. */
@EnabledIfEnvironmentVariable(named = "LS_CONTEXT_TEST_ROOT", matches = ".+")
class ContextTransactionPostgresTest {
    private HikariDataSource pool() throws Exception {
        Path root = Path.of(System.getenv("LS_CONTEXT_TEST_ROOT")).toRealPath();
        assertEquals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath(), root.getParent());
        assertTrue(root.getFileName().toString().startsWith("ls-core-migrations-test-"));
        assertEquals("17", Files.readString(root.resolve("data/PG_VERSION")).trim());
        var pid = Files.readAllLines(root.resolve("data/postmaster.pid"));
        int port = Integer.parseInt(System.getenv("LS_CONTEXT_TEST_PORT"));
        assertNotEquals(5432, port);
        assertEquals(Integer.toString(port), pid.get(3));
        assertEquals(root.resolve("data").toRealPath(), Path.of(pid.get(1)).toRealPath());
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setServerNames(new String[] {"127.0.0.1"});
        postgres.setPortNumbers(new int[] {port});
        postgres.setDatabaseName("ls_core_dev");
        postgres.setUser("ls_core_app_dev");
        postgres.setPassword(System.getenv("LS_CONTEXT_TEST_PASSWORD"));
        postgres.setConnectTimeout(3);
        postgres.setSocketTimeout(5);
        HikariConfig config = new HikariConfig();
        config.setDataSource(postgres);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setReadOnly(true);
        config.setConnectionTimeout(5000);
        return new HikariDataSource(config);
    }

    private String pid(HikariDataSource pool) throws Exception {
        try (var connection = pool.getConnection(); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT pg_backend_pid()")) {
            result.next();
            return result.getString(1);
        }
    }

    private void clean(HikariDataSource pool) throws Exception {
        try (var connection = pool.getConnection(); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT current_setting('ls.tenant_id', true), "
                        + "current_setting('ls.branch_id', true), current_setting('ls.origin', true)")) {
            assertTrue(connection.getAutoCommit());
            assertTrue(connection.isReadOnly());
            result.next();
            for (int index = 1; index <= 3; index++) {
                assertTrue(result.getString(index) == null || result.getString(index).isEmpty());
            }
        }
        assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
    }

    @Test
    void consecutiveTenantsUseSamePhysicalConnectionWithoutResidualContext() throws Exception {
        try (var pool = pool()) {
            var executor = new ContextTransaction(pool);
            String initial = pid(pool);
            AtomicReference<ContextTransaction.Queries> escaped = new AtomicReference<>();
            for (int index = 0; index < 3; index++) {
                var fixture = new ContextFixture();
                executor.execute(fixture.authorized(), queries -> {
                    escaped.set(queries);
                    var rows = queries.query("SELECT current_setting('ls.tenant_id'), "
                            + "current_setting('ls.application_id'), current_setting('ls.branch_id'), "
                            + "current_setting('ls.actor_id'), current_setting('ls.origin'), "
                            + "pg_backend_pid(), current_setting('transaction_read_only')", List.of());
                    assertEquals(List.of(fixture.tenant.toString(), fixture.app.toString(),
                            fixture.branchId.toString(), fixture.user.toString(), "SESSION", initial, "on"), rows.getFirst());
                    assertEquals("bound", queries.query("SELECT ?::text", List.of("bound")).getFirst().getFirst());
                });
                clean(pool);
                assertEquals(initial, pid(pool));
                assertThrows(ContextRejectedException.class, () -> escaped.get().query("SELECT 1", List.of()));
            }
        }
    }

    @Test
    void rollbackErrorsSwallowedErrorsTenantMutationAndNestedWorkAreRejected() throws Exception {
        try (var pool = pool()) {
            var executor = new ContextTransaction(pool);
            var fixture = new ContextFixture();
            assertThrows(ContextRejectedException.class, () -> executor.execute(null, q -> { }));
            assertThrows(ContextRejectedException.class, () -> executor.execute(fixture.authorized(), q -> {
                throw new IllegalStateException("private callback diagnostic");
            }));
            clean(pool);
            assertThrows(ContextRejectedException.class, () -> executor.execute(fixture.authorized(), q -> {
                q.query("SELECT 1 / 0", List.of());
            }));
            clean(pool);
            assertThrows(ContextRejectedException.class, () -> executor.execute(fixture.authorized(), q -> {
                assertThrows(ContextRejectedException.class, () -> q.query("SELECT 1 / 0", List.of()));
            }));
            clean(pool);
            assertThrows(ContextRejectedException.class, () -> executor.execute(fixture.authorized(), q -> {
                q.query("SELECT set_config('ls.tenant_id', ?, true)", List.of(java.util.UUID.randomUUID().toString()));
            }));
            clean(pool);
            assertThrows(ContextRejectedException.class, () -> executor.execute(fixture.authorized(), q -> {
                executor.execute(new ContextFixture().authorized(), nested -> { });
            }));
            clean(pool);
            executor.execute(fixture.authorized(), q -> assertEquals("1", q.query("SELECT 1", List.of()).getFirst().getFirst()));
            clean(pool);
        }
    }

    @Test
    void sourceUnavailableBeforeSetupAndDuringWorkFailsClosed() throws Exception {
        try (var pool = pool()) {
            var executor = new ContextTransaction(pool);
            var fixture = new ContextFixture();
            var context = fixture.authorized();
            fixture.unavailable = true;
            assertThrows(ContextRejectedException.class, () -> executor.execute(context, q -> {
                throw new AssertionError("must not run");
            }));
            assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
            fixture.unavailable = false;
            assertThrows(ContextRejectedException.class, () -> executor.execute(fixture.authorized(), q -> {
                q.query("SELECT 1", List.of());
                fixture.unavailable = true;
            }));
            clean(pool);
        }
    }

    @Test
    void apiKeyContextAndCrossThreadUseStayBounded() throws Exception {
        try (var pool = pool()) {
            var executor = new ContextTransaction(pool);
            var fixture = new ContextFixture();
            var context = fixture.resolver().apiKey(fixture.identity, fixture.scope);
            executor.execute(context, q -> {
                assertEquals(List.of("API_KEY", fixture.credential.toString(), ""),
                        q.query("SELECT current_setting('ls.origin'), current_setting('ls.actor_id'), "
                                + "current_setting('ls.membership_id')", List.of()).getFirst());
            });
            clean(pool);
            assertThrows(ContextRejectedException.class, () -> executor.execute(fixture.authorized(), q -> {
                Thread other = new Thread(() -> assertThrows(ContextRejectedException.class,
                        () -> q.query("SELECT 1", List.of())));
                other.start();
                other.join();
            }));
            clean(pool);
            assertThrows(ContextRejectedException.class, () -> executor.execute(fixture.authorized(), q -> {
                q.query("COMMIT", List.of());
            }));
            clean(pool);
        }
    }

    @Test
    void contaminatedConnectionIsRejectedAndPhysicallyEvicted() throws Exception {
        try (var pool = pool()) {
            String contaminated = pid(pool);
            // Adversarial fixture only: production infrastructure never sets session-level values.
            try (Connection connection = pool.getConnection(); var statement = connection.createStatement()) {
                statement.execute("SELECT set_config('ls.tenant_id', 'synthetic-residual', false)");
            }
            var fixture = new ContextFixture();
            assertThrows(ContextRejectedException.class,
                    () -> new ContextTransaction(pool).execute(fixture.authorized(), q -> {
                        throw new AssertionError("contaminated connection must not reach work");
                    }));
            assertNotEquals(contaminated, pid(pool));
            clean(pool);
        }
    }
}

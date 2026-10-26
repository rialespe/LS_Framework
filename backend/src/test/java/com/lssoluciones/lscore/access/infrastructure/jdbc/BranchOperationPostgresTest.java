package com.lssoluciones.lscore.access.infrastructure.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lssoluciones.lscore.access.application.context.ContextRejectedException;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Scope;
import com.lssoluciones.lscore.identity.application.ManualIdentityInput;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcManualIdentityRegistration;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordLifecycle;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.PasswordCredentialConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** One real operation flow. Hostile SQL is executed by PostgreSQL, never replaced with mocked results. */
@EnabledIfEnvironmentVariable(named = "LS_BRANCH_READ_TEST_ROOT", matches = ".+")
class BranchOperationPostgresTest {
    private PGSimpleDataSource source(String user, String password) throws Exception {
        Path root = Path.of(System.getenv("LS_BRANCH_READ_TEST_ROOT")).toRealPath();
        assertEquals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath(), root.getParent());
        assertTrue(root.getFileName().toString().startsWith("ls-core-migrations-test-"));
        assertEquals("17", Files.readString(root.resolve("data/PG_VERSION")).trim());
        var pid = Files.readAllLines(root.resolve("data/postmaster.pid"));
        int port = Integer.parseInt(System.getenv("LS_BRANCH_READ_TEST_PORT"));
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
        return source(role, System.getenv("LS_BRANCH_READ_TEST_" + role.toUpperCase(java.util.Locale.ROOT)));
    }

    @Test
    void oneBoundReadRejectsForeignSelectorsGucsReplayAndNextOperationAfterRevocation() throws Exception {
        char[] password = "Operacion-150!".toCharArray();
        var reader = role(ProtectedBranchRead.READER);
        try (var admin = role("postgres").getConnection(); var sql = admin.createStatement();
                var context = new AnnotationConfigApplicationContext()) {
            var properties = new HashMap<String, Object>();
            properties.put("ls.identity.credentials.enabled", "true");
            properties.put("ls.access.tenant-authorization.enabled", "true");
            properties.put("ls.access.branch-operation.enabled", "true");
            for (var channel : java.util.Map.of("LS_AUTHORIZATION_DB_", JdbcTenantAuthorization.ROLE,
                    "LS_AUTHENTICATION_DB_", "ls_core_password_authenticator_dev",
                    "LS_BRANCH_OPERATION_DB_", ProtectedBranchRead.READER, "LS_OPERATION_EMITTER_DB_", ProtectedBranchRead.EMITTER).entrySet()) {
                properties.put(channel.getKey() + "HOST", "127.0.0.1");
                properties.put(channel.getKey() + "PORT", Integer.toString(reader.getPortNumbers()[0]));
                properties.put(channel.getKey() + "NAME", "ls_core_dev");
                properties.put(channel.getKey() + "PASSWORD", System.getenv("LS_BRANCH_READ_TEST_" + channel.getValue().toUpperCase(java.util.Locale.ROOT)));
            }
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("owned-cluster", properties));
            context.register(PasswordCredentialConfiguration.class, TenantAuthorizationConfiguration.class, BranchOperationConfiguration.class);
            context.refresh();
            var coordinator = context.getBean(BranchReadCoordinator.class);
            var pool = context.getBean("branchOperationPool", HikariDataSource.class);
            pool.setMaximumPoolSize(1); // Prove later reuse of exactly the same physical backend.
            var registration = new JdbcManualIdentityRegistration(role(JdbcManualIdentityRegistration.DATABASE_ROLE));
            var lifecycle = new JdbcPasswordLifecycle(role(JdbcPasswordLifecycle.ISSUER_ROLE),
                    role(JdbcPasswordLifecycle.CHANGER_ROLE), Duration.ofHours(72));
            UUID user = registration.register(new ManualIdentityInput("f29-operation", "Operation fixture", null));
            try (var temporary = lifecycle.issue(user)) {
                char[] secret = temporary.take();
                try {
                    lifecycle.exchange("f29-operation", secret, password);
                } finally {
                    Arrays.fill(secret, '\0');
                }
            }
            UUID tenantA = UUID.randomUUID();
            UUID tenantB = UUID.randomUUID();
            UUID branchA = UUID.randomUUID();
            UUID branchB = UUID.randomUUID();
            UUID membership = UUID.randomUUID();
            UUID application = UUID.fromString("3bc09866-f9f0-573c-82a0-fd72ec6be41f");
            sql.execute("INSERT INTO tenant.tenants(id,name,status) VALUES ('" + tenantA + "','Operation A','ACTIVE'),('" + tenantB + "','Operation B','ACTIVE')");
            sql.execute("INSERT INTO tenant.branches(id,tenant_id,code,name,status) VALUES ('" + branchA + "','" + tenantA + "','a','Branch A','ACTIVE'),('" + branchB + "','" + tenantB + "','b','Branch B','ACTIVE')");
            sql.execute("INSERT INTO tenant.memberships(id,tenant_id,user_id,status) VALUES ('" + membership + "','" + tenantA + "','" + user + "','ACTIVE')");
            sql.execute("INSERT INTO tenant.branch_memberships(tenant_id,membership_id,branch_id,status) VALUES ('" + tenantA + "','" + membership + "','" + branchA + "','ACTIVE')");
            sql.execute("INSERT INTO catalog.tenant_applications(tenant_id,application_id,status) VALUES ('" + tenantA + "','" + application + "','ACTIVE'),('" + tenantB + "','" + application + "','ACTIVE')");
            var scopeA = new Scope(tenantA, application, branchA);
            var attacked = new AtomicBoolean();
            var issued = new AtomicReference<Snapshot>();
            // Instrument the real JDBC channel to run hostile SQL while the real protected admission is live.
            pool.setDataSource(new AttackingSource(pool.getDataSource(), connection -> {
                if (!attacked.compareAndSet(false, true)) {
                    return;
                }
                issued.set(snapshot(connection));
                try (var statement = connection.createStatement()) {
                    String originalTenant = scalar(connection, "SELECT current_setting('ls.tenant_id')");
                    String originalBranch = scalar(connection, "SELECT current_setting('ls.branch_id')");
                    statement.execute("SELECT set_config('ls.tenant_id','" + tenantB + "',true),set_config('ls.branch_id','" + branchB + "',true)");
                    assertEquals("0", scalar(connection, "SELECT count(id) FROM tenant.branches WHERE id='" + branchB + "'"));
                    assertEquals(branchA.toString(), scalar(connection, "SELECT id::text FROM tenant.branches"));
                    assertEquals("1", scalar(connection, "SELECT count(id) FROM tenant.branches"));
                    try (var foreign = reader.getConnection()) {
                        foreign.setReadOnly(true);
                        foreign.setAutoCommit(false);
                        scalar(foreign, "SELECT pg_current_xact_id()::text");
                        try (var attack = foreign.createStatement()) {
                            attack.execute("SELECT set_config('ls.tenant_id','" + tenantA + "',true),set_config('ls.branch_id','" + branchA + "',true),set_config('ls.transaction_id','" + issued.get().xid() + "',true)");
                        }
                        assertEquals("0", scalar(foreign, "SELECT count(branch_id) FROM security.branch_read_admissions"));
                        assertEquals("0", scalar(foreign, "SELECT count(id) FROM tenant.branches"));
                        deniedSql(foreign, "SET ROLE " + ProtectedBranchRead.EMITTER);
                        foreign.rollback();
                        foreign.setReadOnly(false); // Prove ACL denial, independently of transaction read-only mode.
                        deniedSql(foreign, "INSERT INTO security.branch_read_admissions(database_oid) VALUES (1)");
                        foreign.rollback();
                    }
                    statement.execute("SELECT set_config('ls.tenant_id','" + originalTenant + "',true),set_config('ls.branch_id','" + originalBranch + "',true)");
                }
            }));
            var result = coordinator.read("f29-operation", password, scopeA);
            assertEquals(branchA, result.id());
            assertEquals(tenantA, result.tenantId());
            assertEquals("Branch A", result.name());
            assertTrue(attacked.get());
            assertEquals("0", scalar(admin, "SELECT count(*) FROM security.branch_read_admissions"));
            assertThrows(ContextRejectedException.class, () -> coordinator.read("f29-operation", password, new Scope(tenantB, application, branchB)));
            assertThrows(ContextRejectedException.class, () -> coordinator.read("f29-operation", password, new Scope(tenantB, application, branchA)));
            assertThrows(ContextRejectedException.class, () -> coordinator.read("f29-operation", password, new Scope(tenantA, application, branchB)));

            // Reinsert ONLY captured stale evidence as an orphaned-cleanup fixture. It must authorize nothing.
            Snapshot old = issued.get();
            try (var insert = admin.prepareStatement("INSERT INTO security.branch_read_admissions "
                    + "(database_oid,login_oid,backend_pid,backend_start,transaction_id,tenant_id,application_id,branch_id,actor_id,membership_id,branch_membership_id) "
                    + "VALUES (?::oid,?::oid,?,?,?::xid8,?,?,?,?,?,?)")) {
                old.bind(insert);
                insert.executeUpdate();
            }
            try (var recycled = pool.getConnection()) {
                recycled.setAutoCommit(false);
                assertEquals(Integer.toString(old.pid()), scalar(recycled, "SELECT pg_backend_pid()::text"));
                assertNotEquals(old.xid(), scalar(recycled, "SELECT pg_current_xact_id()::text"));
                assertEquals("0", scalar(recycled, "SELECT count(branch_id) FROM security.branch_read_admissions"));
                assertEquals("0", scalar(recycled, "SELECT count(id) FROM tenant.branches"));
                recycled.rollback();
                recycled.setAutoCommit(true);
            }
            sql.execute("DELETE FROM security.branch_read_admissions");
            sql.execute("UPDATE tenant.memberships SET status='SUSPENDED',updated_at=clock_timestamp() WHERE id='" + membership + "'");
            assertThrows(ContextRejectedException.class, () -> coordinator.read("f29-operation", password, scopeA));
            assertEquals("0", scalar(admin, "SELECT count(*) FROM security.branch_read_admissions"));
            assertEquals("t", scalar(admin, "SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class "
                    + "WHERE oid IN ('tenant.branches'::regclass,'security.branch_read_admissions'::regclass)"));
            assertEquals("t", scalar(admin, "SELECT NOT has_any_column_privilege('ls_core_app_dev','tenant.branches','SELECT')"));
            String log = Files.readString(Path.of(System.getenv("LS_BRANCH_READ_TEST_ROOT")).resolve("server.log"));
            assertTrue(!log.contains(new String(password)));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @FunctionalInterface
    private interface Attack {
        void run(Connection connection) throws Exception;
    }

    /** Delegates all results to the real driver; inserts adversarial SQL before the one production SELECT. */
    private static final class AttackingSource implements DataSource {
        private final DataSource source;
        private final Attack attack;

        private AttackingSource(DataSource source, Attack attack) {
            this.source = source;
            this.attack = attack;
        }

        private Connection wrap(Connection real) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                try {
                    Object value = method.invoke(real, args);
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                            && sql.startsWith("SELECT id,tenant_id,code,name FROM tenant.branches")) {
                        var statement = (java.sql.PreparedStatement) value;
                        return Proxy.newProxyInstance(java.sql.PreparedStatement.class.getClassLoader(), new Class<?>[] {java.sql.PreparedStatement.class}, (p, call, parameters) -> {
                            try {
                                if (call.getName().equals("executeQuery")) {
                                    attack.run(real);
                                }
                                return call.invoke(statement, parameters);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
                    }
                    return value;
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
        }

        public Connection getConnection() throws SQLException {
            return wrap(source.getConnection());
        }

        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(source.getConnection(username, password));
        }

        public java.io.PrintWriter getLogWriter() throws SQLException {
            return source.getLogWriter();
        }

        public void setLogWriter(java.io.PrintWriter writer) throws SQLException {
            source.setLogWriter(writer);
        }

        public void setLoginTimeout(int seconds) throws SQLException {
            source.setLoginTimeout(seconds);
        }

        public int getLoginTimeout() throws SQLException {
            return source.getLoginTimeout();
        }

        public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            return source.getParentLogger();
        }

        public <T> T unwrap(Class<T> type) throws SQLException {
            return source.unwrap(type);
        }

        public boolean isWrapperFor(Class<?> type) throws SQLException {
            return source.isWrapperFor(type);
        }
    }

    private record Snapshot(long database, long login, int pid, java.sql.Timestamp start, String xid,
            UUID tenant, UUID application, UUID branch, UUID actor, UUID membership, UUID access) {
        void bind(java.sql.PreparedStatement statement) throws SQLException {
            statement.setLong(1, database);
            statement.setLong(2, login);
            statement.setInt(3, pid);
            statement.setTimestamp(4, start);
            statement.setString(5, xid);
            statement.setObject(6, tenant);
            statement.setObject(7, application);
            statement.setObject(8, branch);
            statement.setObject(9, actor);
            statement.setObject(10, membership);
            statement.setObject(11, access);
        }
    }

    private static Snapshot snapshot(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT database_oid,login_oid,backend_pid,backend_start,transaction_id::text,tenant_id,application_id,branch_id,actor_id,membership_id,branch_membership_id FROM security.branch_read_admissions")) {
            assertTrue(rows.next());
            return new Snapshot(rows.getLong(1), rows.getLong(2), rows.getInt(3), rows.getTimestamp(4), rows.getString(5),
                    rows.getObject(6, UUID.class), rows.getObject(7, UUID.class), rows.getObject(8, UUID.class), rows.getObject(9, UUID.class), rows.getObject(10, UUID.class), rows.getObject(11, UUID.class));
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static void deniedSql(Connection connection, String sql) throws SQLException {
        var savepoint = connection.setSavepoint();
        try (var statement = connection.createStatement()) {
            assertEquals("42501", assertThrows(SQLException.class, () -> statement.execute(sql)).getSQLState());
        } finally {
            connection.rollback(savepoint);
        }
    }
}

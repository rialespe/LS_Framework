package com.lssoluciones.lscore.access.infrastructure.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lssoluciones.lscore.access.application.context.ContextResolver.AuthorizedContext;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.AuthenticatedIdentity;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Scope;
import com.lssoluciones.lscore.identity.application.ManualIdentityInput;
import com.lssoluciones.lscore.identity.application.PasswordAuthentication;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcManualIdentityRegistration;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordLifecycle;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.PasswordCredentialConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** One real F04 plus tenant/application/branch authorization scenario on the owned disposable PostgreSQL 17. */
@EnabledIfEnvironmentVariable(named = "LS_AUTHORIZATION_TEST_ROOT", matches = ".+")
class TenantAuthorizationPostgresTest {
    private PGSimpleDataSource source(String user, String password) throws Exception {
        Path root = Path.of(System.getenv("LS_AUTHORIZATION_TEST_ROOT")).toRealPath();
        assertEquals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath(), root.getParent());
        assertTrue(root.getFileName().toString().startsWith("ls-core-migrations-test-"));
        assertEquals("17", Files.readString(root.resolve("data/PG_VERSION")).trim());
        var pid = Files.readAllLines(root.resolve("data/postmaster.pid"));
        int port = Integer.parseInt(System.getenv("LS_AUTHORIZATION_TEST_PORT"));
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
        return source(role, System.getenv("LS_AUTHORIZATION_TEST_" + role.toUpperCase(java.util.Locale.ROOT)));
    }

    @Test
    void realCredentialsAndDatabaseRelationsDetermineOnlyCurrentContextEligibility() throws Exception {
        var adminSource = role("postgres");
        var reader = role(JdbcTenantAuthorization.ROLE);
        char[] secret = "Contexto-290!".toCharArray();
        char[] wrong = "Incorrecto-291!".toCharArray();
        try (var admin = adminSource.getConnection(); var sql = admin.createStatement();
                var context = new AnnotationConfigApplicationContext()) {
            var properties = new HashMap<String, Object>();
            properties.put("ls.identity.credentials.enabled", "true");
            properties.put("ls.access.tenant-authorization.enabled", "true");
            for (String prefix : new String[] {"LS_AUTHORIZATION_DB_", "LS_AUTHENTICATION_DB_"}) {
                String principal = prefix.equals("LS_AUTHORIZATION_DB_") ? JdbcTenantAuthorization.ROLE : JdbcPasswordAuthentication.ROLE;
                properties.put(prefix + "HOST", "127.0.0.1");
                properties.put(prefix + "PORT", Integer.toString(reader.getPortNumbers()[0]));
                properties.put(prefix + "NAME", "ls_core_dev");
                properties.put(prefix + "PASSWORD", System.getenv("LS_AUTHORIZATION_TEST_" + principal.toUpperCase(java.util.Locale.ROOT)));
            }
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated", properties));
            context.register(PasswordCredentialConfiguration.class, TenantAuthorizationConfiguration.class);
            context.refresh();
            var service = context.getBean(JdbcTenantAuthorization.class);
            var authentication = (JdbcPasswordAuthentication) context.getBean(PasswordAuthentication.class);
            var registrar = new JdbcManualIdentityRegistration(role(JdbcManualIdentityRegistration.DATABASE_ROLE));
            var lifecycle = new JdbcPasswordLifecycle(role(JdbcPasswordLifecycle.ISSUER_ROLE),
                    role(JdbcPasswordLifecycle.CHANGER_ROLE), Duration.ofHours(72));
            UUID alice = active(registrar, lifecycle, "f29-alice", secret);
            UUID bob = active(registrar, lifecycle, "f29-bob", secret);
            UUID tenantA = UUID.randomUUID();
            UUID tenantB = UUID.randomUUID();
            UUID branchA = UUID.randomUUID();
            UUID branchB = UUID.randomUUID();
            UUID memberA = UUID.randomUUID();
            UUID memberB = UUID.randomUUID();
            UUID accessA = UUID.randomUUID();
            UUID application = UUID.fromString("3bc09866-f9f0-573c-82a0-fd72ec6be41f");
            sql.execute("INSERT INTO tenant.tenants(id,name,status) VALUES ('" + tenantA + "','Fixture A','ACTIVE'),('" + tenantB + "','Fixture B','ACTIVE')");
            sql.execute("INSERT INTO tenant.branches(id,tenant_id,code,name,status) VALUES ('" + branchA + "','" + tenantA + "','main','Fixture A','ACTIVE'),('" + branchB + "','" + tenantB + "','main','Fixture B','ACTIVE')");
            sql.execute("INSERT INTO tenant.memberships(id,tenant_id,user_id,status) VALUES ('" + memberA + "','" + tenantA + "','" + alice + "','ACTIVE'),('" + memberB + "','" + tenantB + "','" + bob + "','ACTIVE')");
            sql.execute("INSERT INTO tenant.branch_memberships(id,tenant_id,membership_id,branch_id,status) VALUES ('" + accessA + "','" + tenantA + "','" + memberA + "','" + branchA + "','ACTIVE'),('" + UUID.randomUUID() + "','" + tenantB + "','" + memberB + "','" + branchB + "','ACTIVE')");
            sql.execute("INSERT INTO catalog.tenant_applications(tenant_id,application_id,status) VALUES ('" + tenantA + "','" + application + "','ACTIVE'),('" + tenantB + "','" + application + "','ACTIVE')");
            var selected = new Scope(tenantA, application, branchA);
            var other = new Scope(tenantB, application, branchB);
            var initial = allowed(service, "f29-alice", secret, selected);
            assertTrue(initial.observedAt().isPresent());
            assertTrue(flag(admin, "SELECT accepted_at IS NULL FROM tenant.memberships WHERE id='" + memberA + "'"));
            allowed(service, "f29-bob", secret, other);
            denied(service, "f29-bob", secret, selected); // Existing enabled user, no membership in A.
            denied(service, "f29-alice", secret, other); // Complete B tuple still belongs to a different subject.
            denied(service, "f29-alice", secret, new Scope(tenantB, application, branchA));
            denied(service, "f29-alice", secret, new Scope(UUID.randomUUID(), application, branchA));
            denied(service, "f29-alice", secret, new Scope(tenantA, application, branchB));
            denied(service, "f29-alice", secret, new Scope(tenantA, UUID.randomUUID(), branchA));
            denied(service, "f29-alice", secret, new Scope(tenantA, application, null));
            denied(service, "f29-alice", secret, null);
            denied(service, "f29-missing", secret, selected);
            denied(service, "f29-alice", wrong, selected);
            assertEquals("1", text(admin, "SELECT failed_attempts FROM identity.user_credentials WHERE user_id='" + alice + "'"));
            sql.execute("UPDATE identity.user_credentials SET locked_until=clock_timestamp()+interval '1 minute' WHERE user_id='" + alice + "'");
            denied(service, "f29-alice", secret, selected);
            sql.execute("UPDATE identity.user_credentials SET locked_until=clock_timestamp()-interval '1 second' WHERE user_id='" + alice + "'");
            allowed(service, "f29-alice", secret, selected);
            assertEquals("0", text(admin, "SELECT failed_attempts FROM identity.user_credentials WHERE user_id='" + alice + "'"));

            // Each negative mutates only one authoritative fixture relation, preserving the other positive evidence.
            for (String target : new String[] {"tenant.tenants WHERE id='" + tenantA + "'",
                    "tenant.memberships WHERE id='" + memberA + "'", "tenant.branches WHERE id='" + branchA + "'",
                    "tenant.branch_memberships WHERE id='" + accessA + "'", "identity.users WHERE id='" + alice + "'"}) {
                updateStatus(admin, target, "SUSPENDED");
                denied(service, "f29-alice", secret, selected);
                updateStatus(admin, target, "ACTIVE");
            }
            String entitlement = "catalog.tenant_applications WHERE tenant_id='" + tenantA + "' AND application_id='" + application + "'";
            for (String state : new String[] {"DISABLED", "READ_ONLY"}) {
                updateStatus(admin, entitlement, state);
                denied(service, "f29-alice", secret, selected);
            }
            updateStatus(admin, entitlement, "ACTIVE");
            for (String target : new String[] {"tenant.memberships WHERE id='" + memberA + "'",
                    "tenant.branch_memberships WHERE id='" + accessA + "'"}) {
                updateStatus(admin, target, "PENDING");
                denied(service, "f29-alice", secret, selected);
                updateStatus(admin, target, "ACTIVE"); // Authorized administrative fixture setup only.
            }
            for (String target : new String[] {"tenant.memberships WHERE id='" + memberA + "'",
                    "tenant.branches WHERE id='" + branchA + "'", "tenant.branch_memberships WHERE id='" + accessA + "'"}) {
                updateStatus(admin, target, "UNRECOGNIZED_FIXTURE_STATE");
                denied(service, "f29-alice", secret, selected);
                updateStatus(admin, target, "ACTIVE");
            }
            String globalApplication = "catalog.applications WHERE id='" + application + "'";
            updateStatus(admin, globalApplication, "UNRECOGNIZED_FIXTURE_STATE");
            denied(service, "f29-alice", secret, selected);
            updateStatus(admin, globalApplication, "PUBLISHED");
            allowed(service, "f29-alice", secret, selected);

            // Hostile ls.* are selectors, never a subject or permission, even on the real reader connection.
            var falsified = role(JdbcTenantAuthorization.ROLE);
            falsified.setOptions("-c ls.tenant_id=" + tenantB + " -c ls.user_id=" + bob + " -c ls.is_admin=true");
            var hostileSelectors = new JdbcTenantAuthorization(falsified, authentication);
            denied(hostileSelectors, "f29-alice", secret, other);
            allowed(hostileSelectors, "f29-alice", secret, selected);
            for (String principal : new String[] {"ls_core_app_dev", "ls_core_password_authenticator_dev"}) {
                try (var connection = role(principal).getConnection(); var statement = connection.createStatement()) {
                    statement.execute("SELECT set_config('ls.tenant_id','" + tenantB + "',false),set_config('ls.user_id','" + bob + "',false),set_config('ls.is_admin','true',false)");
                    for (String query : new String[] {"SELECT user_id FROM tenant.memberships", "SELECT branch_id FROM tenant.branch_memberships",
                            "SELECT tenant_id FROM catalog.tenant_applications", "SET ROLE " + JdbcTenantAuthorization.ROLE}) {
                        deniedSql(connection, query);
                    }
                }
            }
            for (String principal : new String[] {"ls_core_app_dev", "ls_core_migrator_dev", "postgres"}) {
                denied(new JdbcTenantAuthorization(role(principal), authentication), "f29-alice", secret, selected);
            }
            try (var connection = reader.getConnection()) {
                for (String query : new String[] {"SELECT password_hash FROM identity.user_credentials", "SELECT email FROM identity.users",
                        "SELECT name FROM tenant.tenants", "SELECT name FROM catalog.applications", "SELECT id FROM security.roles",
                        "UPDATE tenant.memberships SET status='ACTIVE'", "INSERT INTO tenant.tenants(name,status) VALUES ('x','ACTIVE')",
                        "DELETE FROM tenant.branch_memberships", "SET ROLE ls_core_app_dev", "SET ROLE ls_core_migrator_dev", "SET ROLE postgres"}) {
                    deniedSql(connection, query);
                }
                assertEquals("2", text(connection, "SELECT count(id) FROM tenant.memberships WHERE tenant_id IN ('" + tenantA + "','" + tenantB + "')"));
            }

            // A saved ALLOWED result is historical data and cannot be submitted as identity, admission or SQL authority.
            assertFalse(AuthenticatedIdentity.class.isAssignableFrom(initial.getClass()));
            assertFalse(AuthorizedContext.class.isAssignableFrom(initial.getClass()));
            assertEquals(0, JdbcTenantAuthorization.Decision.class.getConstructors().length);
            for (var method : JdbcTenantAuthorization.class.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                    assertEquals("evaluate", method.getName());
                    assertTrue(Arrays.equals(new Class<?>[] {String.class, char[].class, Scope.class}, method.getParameterTypes()));
                }
            }
            sql.execute("DELETE FROM tenant.branch_memberships WHERE id='" + accessA + "'");
            denied(service, "f29-alice", secret, initial.authorizedScope().orElseThrow());
            // The old view retains what was observed; it has no method to consume it as an authorization credential.
            assertEquals(JdbcTenantAuthorization.Outcome.ALLOWED, initial.outcome());
            assertTrue(flag(admin, "SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class WHERE oid IN "
                    + "('identity.users'::regclass,'tenant.tenants'::regclass,'tenant.memberships'::regclass,"
                    + "'tenant.branches'::regclass,'tenant.branch_memberships'::regclass,'catalog.tenant_applications'::regclass)"));
            assertEquals("0", text(admin, "SELECT count(*) FROM pg_stat_activity WHERE usename='" + JdbcTenantAuthorization.ROLE + "'"));
            String log = Files.readString(Path.of(System.getenv("LS_AUTHORIZATION_TEST_ROOT")).resolve("server.log"));
            assertTrue(!log.contains(new String(secret)) && !log.contains(new String(wrong)));
        } finally {
            Arrays.fill(secret, '\0');
            Arrays.fill(wrong, '\0');
        }
    }

    private static UUID active(JdbcManualIdentityRegistration registrar, JdbcPasswordLifecycle lifecycle,
            String username, char[] password) {
        UUID id = registrar.register(new ManualIdentityInput(username, "Authorization fixture", null));
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

    private static void updateStatus(Connection connection, String target, String state) throws SQLException {
        int split = target.indexOf(" WHERE ");
        try (var statement = connection.createStatement()) {
            statement.execute("UPDATE " + target.substring(0, split) + " SET status='" + state + "'" + target.substring(split));
        }
    }

    private static JdbcTenantAuthorization.Decision allowed(JdbcTenantAuthorization service, String username,
            char[] password, Scope selected) {
        var result = service.evaluate(username, password, selected);
        assertEquals(JdbcTenantAuthorization.Outcome.ALLOWED, result.outcome());
        assertTrue(selected.equals(result.authorizedScope().orElseThrow()));
        return result;
    }

    private static void denied(JdbcTenantAuthorization service, String username, char[] password, Scope selected) {
        var result = service.evaluate(username, password, selected);
        assertEquals(JdbcTenantAuthorization.Outcome.DENIED, result.outcome());
        assertTrue(result.authorizedScope().isEmpty() && result.observedAt().isEmpty());
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

    private static void deniedSql(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            assertEquals("42501", assertThrows(SQLException.class, () -> statement.execute(sql)).getSQLState());
        }
    }
}

package com.lssoluciones.lscore.access.infrastructure.jdbc;

import static com.lssoluciones.lscore.access.infrastructure.jdbc.BranchOperationPostgresTest.deniedSql;
import static com.lssoluciones.lscore.access.infrastructure.jdbc.BranchOperationPostgresTest.scalar;
import static com.lssoluciones.lscore.access.infrastructure.jdbc.BranchOperationPostgresTest.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lssoluciones.lscore.access.application.context.ContextRejectedException;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Scope;
import com.lssoluciones.lscore.access.infrastructure.jdbc.BranchOperationPostgresTest.AttackingSource;
import com.lssoluciones.lscore.access.infrastructure.jdbc.BranchOperationPostgresTest.Snapshot;
import com.lssoluciones.lscore.identity.application.ManualIdentityInput;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcManualIdentityRegistration;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordLifecycle;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.PasswordCredentialConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** One A/cleanup/B/replay/revocation flow; all credentials, admissions and SQL results are real. */
@EnabledIfEnvironmentVariable(named = "LS_BRANCH_READ_TEST_ROOT", matches = ".+")
class TenantIsolationPoolPostgresTest {
    private static final List<String> KEYS = List.of("ls.tenant_id", "ls.application_id", "ls.branch_id",
            "ls.origin", "ls.actor_id", "ls.credential_id", "ls.session_context_id", "ls.membership_id", "ls.branch_membership_id", "ls.transaction_id");

    @Test
    void independentUsersRemainIsolatedAcrossOneReusedPhysicalConnection() throws Exception {
        var fixture = new BranchOperationPostgresTest();
        var reader = fixture.role(ProtectedBranchRead.READER);
        char[] passwordA = "Aislamiento-A30!".toCharArray();
        char[] passwordB = "Aislamiento-B30!".toCharArray();
        try (var admin = fixture.role("postgres").getConnection(); var sql = admin.createStatement();
                var context = new AnnotationConfigApplicationContext()) {
            var properties = new HashMap<String, Object>();
            properties.put("ls.identity.credentials.enabled", "true");
            properties.put("ls.access.tenant-authorization.enabled", "true");
            properties.put("ls.access.branch-operation.enabled", "true");
            for (var channel : java.util.Map.of("LS_AUTHORIZATION_DB_", JdbcTenantAuthorization.ROLE,
                    "LS_AUTHENTICATION_DB_", "ls_core_password_authenticator_dev", "LS_BRANCH_OPERATION_DB_", ProtectedBranchRead.READER,
                    "LS_OPERATION_EMITTER_DB_", ProtectedBranchRead.EMITTER).entrySet()) {
                properties.put(channel.getKey() + "HOST", "127.0.0.1");
                properties.put(channel.getKey() + "PORT", Integer.toString(reader.getPortNumbers()[0]));
                properties.put(channel.getKey() + "NAME", "ls_core_dev");
                properties.put(channel.getKey() + "PASSWORD", System.getenv("LS_BRANCH_READ_TEST_" + channel.getValue().toUpperCase(java.util.Locale.ROOT)));
            }
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("owned-pool-isolation", properties));
            context.register(PasswordCredentialConfiguration.class, TenantAuthorizationConfiguration.class, BranchOperationConfiguration.class);
            context.refresh();
            var coordinator = context.getBean(BranchReadCoordinator.class);
            var pool = context.getBean("branchOperationPool", HikariDataSource.class);
            pool.setMaximumPoolSize(1); // Test-only limit; production configuration remains unchanged.
            var registrar = new JdbcManualIdentityRegistration(fixture.role(JdbcManualIdentityRegistration.DATABASE_ROLE));
            var lifecycle = new JdbcPasswordLifecycle(fixture.role(JdbcPasswordLifecycle.ISSUER_ROLE),
                    fixture.role(JdbcPasswordLifecycle.CHANGER_ROLE), Duration.ofHours(72));
            UUID userA = active(registrar, lifecycle, "f30-user-a", passwordA);
            UUID userB = active(registrar, lifecycle, "f30-user-b", passwordB);
            assertNotEquals(userA, userB);
            UUID tenantA = UUID.randomUUID();
            UUID tenantB = UUID.randomUUID();
            UUID branchA = UUID.randomUUID();
            UUID branchB = UUID.randomUUID();
            UUID memberA = UUID.randomUUID();
            UUID memberB = UUID.randomUUID();
            UUID application = UUID.fromString("3bc09866-f9f0-573c-82a0-fd72ec6be41f");
            sql.execute("INSERT INTO tenant.tenants(id,name,status) VALUES ('" + tenantA + "','Pool A','ACTIVE'),('" + tenantB + "','Pool B','ACTIVE')");
            sql.execute("INSERT INTO tenant.branches(id,tenant_id,code,name,status) VALUES ('" + branchA + "','" + tenantA + "','a','Pool branch A','ACTIVE'),('" + branchB + "','" + tenantB + "','b','Pool branch B','ACTIVE')");
            sql.execute("INSERT INTO tenant.memberships(id,tenant_id,user_id,status) VALUES ('" + memberA + "','" + tenantA + "','" + userA + "','ACTIVE'),('" + memberB + "','" + tenantB + "','" + userB + "','ACTIVE')");
            sql.execute("INSERT INTO tenant.branch_memberships(tenant_id,membership_id,branch_id,status) VALUES ('" + tenantA + "','" + memberA + "','" + branchA + "','ACTIVE'),('" + tenantB + "','" + memberB + "','" + branchB + "','ACTIVE')");
            sql.execute("INSERT INTO catalog.tenant_applications(tenant_id,application_id,status) VALUES ('" + tenantA + "','" + application + "','ACTIVE'),('" + tenantB + "','" + application + "','ACTIVE')");
            var scopeA = new Scope(tenantA, application, branchA);
            var scopeB = new Scope(tenantB, application, branchB);
            var reads = new AtomicInteger();
            List<Snapshot> admitted = new ArrayList<>();
            pool.setDataSource(new AttackingSource(pool.getDataSource(), connection -> {
                int index = reads.getAndIncrement();
                assertTrue(index < 2);
                Snapshot evidence = snapshot(connection);
                admitted.add(evidence);
                Scope expected = index == 0 ? scopeA : scopeB;
                Scope foreign = index == 0 ? scopeB : scopeA;
                assertEquals(expected.tenantId(), evidence.tenant());
                assertEquals(expected.branchId(), evidence.branch());
                assertEquals(index == 0 ? userA : userB, evidence.actor());
                assertEquals(index == 0 ? memberA : memberB, evidence.membership());
                try (var statement = connection.createStatement()) {
                    String actor = scalar(connection, "SELECT current_setting('ls.actor_id')");
                    statement.execute("SELECT set_config('ls.tenant_id','" + foreign.tenantId() + "',true),set_config('ls.branch_id','" + foreign.branchId() + "',true),set_config('ls.actor_id','" + (index == 0 ? userB : userA) + "',true)");
                    assertEquals(expected.branchId().toString(), scalar(connection, "SELECT id::text FROM tenant.branches"));
                    assertEquals("1", scalar(connection, "SELECT count(id) FROM tenant.branches"));
                    assertEquals("0", scalar(connection, "SELECT count(id) FROM tenant.branches WHERE tenant_id='" + foreign.tenantId() + "'"));
                    statement.execute("SELECT set_config('ls.tenant_id','" + expected.tenantId() + "',true),set_config('ls.branch_id','" + expected.branchId() + "',true),set_config('ls.actor_id','" + actor + "',true)");
                }
            }));

            var a = coordinator.read("f30-user-a", passwordA, scopeA);
            assertEquals(branchA, a.id());
            assertEquals(tenantA, a.tenantId());
            cleanPool(admin, pool, admitted.getFirst());
            assertThrows(ContextRejectedException.class, () -> coordinator.read("f30-user-a", passwordA, scopeB));
            assertThrows(ContextRejectedException.class, () -> coordinator.read("f30-user-a", passwordA, new Scope(tenantB, application, branchA)));
            assertThrows(ContextRejectedException.class, () -> coordinator.read("f30-user-a", passwordA, new Scope(tenantA, application, branchB)));

            var b = coordinator.read("f30-user-b", passwordB, scopeB);
            assertEquals(branchB, b.id());
            assertEquals(tenantB, b.tenantId());
            assertEquals(2, reads.get());
            Snapshot previous = admitted.getFirst();
            Snapshot next = admitted.getLast();
            assertEquals(previous.pid(), next.pid());
            assertEquals(previous.start(), next.start());
            assertEquals(previous.login(), next.login());
            assertNotEquals(previous.xid(), next.xid());
            cleanPool(admin, pool, next);

            // Only captured stale evidence is reinserted as an orphan fixture; no positive authorization is fabricated.
            try (var insert = admin.prepareStatement("INSERT INTO security.branch_read_admissions "
                    + "(database_oid,login_oid,backend_pid,backend_start,transaction_id,tenant_id,application_id,branch_id,actor_id,membership_id,branch_membership_id) "
                    + "VALUES (?::oid,?::oid,?,?,?::xid8,?,?,?,?,?,?)")) {
                previous.bind(insert);
                insert.executeUpdate();
            }
            try (var recycled = pool.getConnection(); var statement = recycled.createStatement()) {
                recycled.setAutoCommit(false);
                assertEquals(Integer.toString(previous.pid()), scalar(recycled, "SELECT pg_backend_pid()::text"));
                assertNotEquals(previous.xid(), scalar(recycled, "SELECT pg_current_xact_id()::text"));
                statement.execute("SELECT set_config('ls.tenant_id','" + tenantA + "',true),set_config('ls.branch_id','" + branchA + "',true),set_config('ls.actor_id','" + userA + "',true),set_config('ls.transaction_id','" + previous.xid() + "',true)");
                assertEquals("0", scalar(recycled, "SELECT count(branch_id) FROM security.branch_read_admissions"));
                assertEquals("0", scalar(recycled, "SELECT count(id) FROM tenant.branches"));
                recycled.rollback();
                recycled.setAutoCommit(true);
            }
            sql.execute("DELETE FROM security.branch_read_admissions");
            try (var runtime = fixture.role("ls_core_app_dev").getConnection()) {
                runtime.setReadOnly(false);
                runtime.setAutoCommit(false);
                assertEquals("off", scalar(runtime, "SHOW transaction_read_only"));
                for (String query : new String[] {"SELECT id FROM tenant.branches", "SELECT user_id FROM tenant.memberships",
                        "SELECT id FROM identity.users", "SELECT actor_id FROM security.branch_read_admissions",
                        "INSERT INTO tenant.branches(tenant_id,code,name,status) VALUES ('" + tenantA + "','forbidden','Forbidden','ACTIVE')",
                        "UPDATE tenant.branches SET name='Forbidden' WHERE id='" + branchA + "'", "DELETE FROM tenant.branches WHERE id='" + branchA + "'",
                        "INSERT INTO security.branch_read_admissions(database_oid) VALUES (1)",
                        "UPDATE security.branch_read_admissions SET tenant_id='" + tenantB + "'", "DELETE FROM security.branch_read_admissions"}) {
                    deniedSql(runtime, query);
                }
                runtime.rollback();
            }
            sql.execute("UPDATE tenant.memberships SET status='SUSPENDED',updated_at=clock_timestamp() WHERE id='" + memberA + "'");
            assertThrows(ContextRejectedException.class, () -> coordinator.read("f30-user-a", passwordA, scopeA));
            assertEquals(2, reads.get());
            cleanPool(admin, pool, next);
            assertEquals("t", scalar(admin, "SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class "
                    + "WHERE oid IN ('tenant.branches'::regclass,'security.branch_read_admissions'::regclass)"));
        } finally {
            Arrays.fill(passwordA, '\0');
            Arrays.fill(passwordB, '\0');
        }
    }

    private static UUID active(JdbcManualIdentityRegistration registrar, JdbcPasswordLifecycle lifecycle,
            String username, char[] password) {
        UUID id = registrar.register(new ManualIdentityInput(username, "Pool isolation fixture", null));
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

    private static void cleanPool(Connection admin, HikariDataSource pool, Snapshot previous) throws Exception {
        assertEquals("0", scalar(admin, "SELECT count(*) FROM security.branch_read_admissions"));
        assertEquals("t", scalar(admin, "SELECT xact_start IS NULL AND state='idle' FROM pg_stat_activity WHERE pid=" + previous.pid()));
        try (var reused = pool.getConnection()) {
            assertTrue(reused.getAutoCommit());
            assertTrue(reused.isReadOnly());
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, reused.getTransactionIsolation());
            assertEquals(Integer.toString(previous.pid()), scalar(reused, "SELECT pg_backend_pid()::text"));
            for (String key : KEYS) {
                assertEquals("", scalar(reused, "SELECT coalesce(current_setting('" + key + "',true),'')"));
            }
            reused.setAutoCommit(false);
            assertNotEquals(previous.xid(), scalar(reused, "SELECT pg_current_xact_id()::text"));
            assertEquals("0", scalar(reused, "SELECT count(branch_id) FROM security.branch_read_admissions"));
            assertEquals("0", scalar(reused, "SELECT count(id) FROM tenant.branches"));
            reused.rollback();
            reused.setAutoCommit(true);
        }
    }
}

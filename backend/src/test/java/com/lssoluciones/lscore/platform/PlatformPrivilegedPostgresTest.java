package com.lssoluciones.lscore.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lssoluciones.lscore.identity.application.ManualIdentityInput;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcManualIdentityRegistration;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordLifecycle;
import com.lssoluciones.lscore.session.ApplicationLoginPostgresTest;
import com.lssoluciones.lscore.session.JdbcApplicationSessions;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** One owned PostgreSQL 17 flow: real F04, transaction audit, fault injection and concurrent executors. */
@EnabledIfEnvironmentVariable(named = "LS_LOGIN_TEST_ROOT", matches = ".+")
class PlatformPrivilegedPostgresTest {
    @Test
    void recentOwnerPasswordAtomicAuditRejectedEvidenceAndConcurrentRevocations() throws Exception {
        var fixture = new ApplicationLoginPostgresTest();
        char[] password = "Privileged-391!".toCharArray();
        char[] wrong = "Incorrect-392!".toCharArray();
        try (var admin = fixture.role("postgres").getConnection(); var sql = admin.createStatement()) {
            var registrar = new JdbcManualIdentityRegistration(fixture.role(JdbcManualIdentityRegistration.DATABASE_ROLE));
            var lifecycle = new JdbcPasswordLifecycle(fixture.role(JdbcPasswordLifecycle.ISSUER_ROLE),
                    fixture.role(JdbcPasswordLifecycle.CHANGER_ROLE), Duration.ofHours(72));
            UUID owner = definitive(registrar, lifecycle, "f39-owner", password);
            UUID business = definitive(registrar, lifecycle, "f39-business", password);
            UUID first = definitive(registrar, lifecycle, "f39-first", password);
            UUID second = definitive(registrar, lifecycle, "f39-second", password);
            UUID third = definitive(registrar, lifecycle, "f39-third", password);
            var authentication = new JdbcPasswordAuthentication(fixture.role(JdbcPasswordAuthentication.ROLE), JdbcPasswordAuthentication.Limits.defaults());
            new JdbcPlatformBootstrap(fixture.role(JdbcPlatformBootstrap.ROLE), authentication).initialize("f39-owner", password);
            var executor = new JdbcPlatformExecutor(fixture.role(JdbcPlatformExecutor.ROLE), fixture.role(JdbcPlatformExecutor.SECURITY_LOG_ROLE), authentication);
            try (var input = getClass().getResourceAsStream("/session-context-fixture.sql")) {
                sql.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("__USER__", business.toString())
                        .replace("35000000", "39000000").replace("f35-", "f39-"));
            }
            String memberships = scalar(admin, "SELECT count(*) FROM tenant.memberships WHERE user_id='" + business + "'");
            var deniedPassword = executor.execute("f39-owner", wrong, first, JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN);
            evidence(admin, deniedPassword, "DENIED", null, first);
            assertEquals("1", scalar(admin, "SELECT failed_attempts FROM identity.user_credentials WHERE user_id='" + owner + "'"));
            evidence(admin, executor.execute("f39-business", password, first, JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN), "DENIED", business, first);
            var granted = executor.execute("f39-owner", password, first, JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN);
            evidence(admin, granted, "SUCCESS", owner, first);
            assertEquals("0", scalar(admin, "SELECT failed_attempts FROM identity.user_credentials WHERE user_id='" + owner + "'"));
            evidence(admin, executor.execute("f39-first", password, second, JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN), "DENIED", first, second);
            evidence(admin, executor.execute("f39-owner", password, second, JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN), "SUCCESS", owner, second);
            evidence(admin, executor.execute("f39-owner", password, first, JdbcPlatformAuthorization.Action.REVOKE_SUPERADMIN), "SUCCESS", owner, first);
            evidence(admin, executor.execute("f39-owner", password, second, JdbcPlatformAuthorization.Action.REVOKE_SUPERADMIN), "DENIED", owner, second);
            evidence(admin, executor.execute("f39-owner", password, owner, JdbcPlatformAuthorization.Action.REVOKE_SUPERADMIN), "DENIED", owner, owner);
            evidence(admin, executor.execute("f39-owner", password, owner, JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN), "DENIED", owner, owner);
            assertEquals("1", scalar(admin, active()));

            // Failure exists only in this owned fixture and is always removed. Success cannot commit without evidence.
            sql.execute("CREATE FUNCTION audit.fixture_reject_success() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.outcome='SUCCESS' THEN RAISE EXCEPTION 'Owned audit fault'; END IF; RETURN NEW; END $$");
            sql.execute("CREATE TRIGGER zz_fixture_reject_success BEFORE INSERT ON audit.events FOR EACH ROW EXECUTE FUNCTION audit.fixture_reject_success()");
            try {
                var failedAudit = executor.execute("f39-owner", password, third, JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN);
                evidence(admin, failedAudit, "DENIED", owner, third);
                assertEquals("0", scalar(admin, "SELECT count(*) FROM platform.administrators WHERE user_id='" + third + "'"));
            } finally {
                sql.execute("DROP TRIGGER zz_fixture_reject_success ON audit.events");
                sql.execute("DROP FUNCTION audit.fixture_reject_success()");
            }
            evidence(admin, executor.execute("f39-owner", password, third, JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN), "SUCCESS", owner, third);

            // Hold the same transaction lock as V20, observe both real executors waiting, then release together.
            var start = new CyclicBarrier(2);
            try (var gate = fixture.role("postgres").getConnection(); var gateSql = gate.createStatement();
                    var workers = Executors.newFixedThreadPool(2)) {
                gate.setAutoCommit(false);
                gateSql.execute("SELECT pg_advisory_xact_lock(370019,1)");
                var a = workers.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return executor.execute("f39-owner", password, second, JdbcPlatformAuthorization.Action.REVOKE_SUPERADMIN);
                });
                var b = workers.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return executor.execute("f39-owner", password, third, JdbcPlatformAuthorization.Action.REVOKE_SUPERADMIN);
                });
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                    String waiting;
                    do {
                        waiting = scalar(admin, "SELECT count(*) FROM pg_locks l JOIN pg_stat_activity s ON s.pid=l.pid WHERE l.locktype='advisory' AND NOT l.granted AND s.usename='ls_core_platform_mutator_dev'");
                        Thread.yield();
                    } while (!"2".equals(waiting) && System.nanoTime() < deadline);
                    assertEquals("2", waiting, "Both independent executors must reach V20 serialization");
                } finally {
                    gate.commit();
                }
                var results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(r -> r.outcome() == JdbcPlatformExecutor.Outcome.CONFIRMED).count());
                assertEquals(1, results.stream().filter(r -> r.outcome() == JdbcPlatformExecutor.Outcome.DENIED).count());
                for (var result : results) {
                    assertTrue(result.evidenceRecorded());
                    assertEquals("1", scalar(admin, "SELECT count(*) FROM audit.events WHERE correlation_id='" + result.correlation() + "'"));
                }
            }
            assertEquals("1", scalar(admin, active()));
            assertEquals("1", scalar(admin, "SELECT count(*) FROM platform.administrators WHERE administrator_kind='OWNER' AND status='ACTIVE' AND user_id='" + owner + "'"));
            assertEquals("23503", assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE identity.users SET status='SUSPENDED' WHERE id='" + owner + "'")).getSQLState());
            assertEquals("42501", assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE platform.administrators SET status='REVOKED' WHERE user_id='" + owner + "'")).getSQLState());
            assertEquals(memberships, scalar(admin, "SELECT count(*) FROM tenant.memberships WHERE user_id='" + business + "'"));
            assertEquals("0", scalar(admin, "SELECT count(*) FROM tenant.memberships WHERE user_id='" + first + "'"));
            assertEquals("3", scalar(admin, "SELECT count(*) FROM platform.administrators WHERE administrator_kind='SUPERADMIN'"));
            for (String role : List.of("ls_core_app_dev", JdbcApplicationSessions.ROLE, JdbcPlatformAuthorization.ROLE)) {
                try (var connection = fixture.role(role).getConnection(); var statement = connection.createStatement()) {
                    assertEquals("42501", assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE platform.administrators SET status='REVOKED'")).getSQLState());
                    assertEquals("42501", assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM audit.events")).getSQLState());
                    assertEquals("42501", assertThrows(SQLException.class, () -> statement.execute("SET ROLE ls_core_platform_mutator_dev")).getSQLState());
                }
            }
            var wrongChannel = new JdbcPlatformExecutor(fixture.role(JdbcApplicationSessions.ROLE), fixture.role(JdbcPlatformExecutor.SECURITY_LOG_ROLE), authentication);
            evidence(admin, wrongChannel.execute("f39-owner", password, business, JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN), "DENIED", null, business);
            for (String role : List.of(JdbcPlatformExecutor.ROLE, JdbcPlatformExecutor.SECURITY_LOG_ROLE)) {
                try (var connection = fixture.role(role).getConnection(); var statement = connection.createStatement()) {
                    assertEquals("42501", assertThrows(SQLException.class, () -> statement.executeUpdate(
                            "INSERT INTO audit.events(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata) VALUES(clock_timestamp(),'USER','" + owner + "','PLATFORM_DESIGNATE_SUPERADMIN','PLATFORM_USER','" + business + "','SUCCESS',gen_random_uuid(),'{\"designation_id\":\"forged\"}')")).getSQLState());
                    assertEquals("42501", assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM audit.events")).getSQLState());
                }
            }
            assertEquals("t", scalar(admin, "SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class WHERE oid IN ('platform.administrators'::regclass,'audit.events'::regclass,'identity.users'::regclass)"));
            assertEquals("t", scalar(admin, "SELECT NOT has_table_privilege('ls_core_platform_security_log_dev','audit.events','SELECT,UPDATE,DELETE,TRUNCATE') "
                    + "AND NOT has_any_column_privilege('ls_core_platform_security_log_dev','platform.administrators','INSERT,UPDATE') "
                    + "AND NOT has_column_privilege('ls_core_platform_mutator_dev','identity.user_credentials','password_hash','SELECT')"));
            try (var statement = admin.createStatement(); var rows = statement.executeQuery("SELECT metadata::text FROM audit.events WHERE action_code LIKE 'PLATFORM_%'")) {
                while (rows.next()) {
                    String metadata = rows.getString(1);
                    assertFalse(metadata.contains(new String(password)) || metadata.contains(new String(wrong)));
                    assertTrue(metadata.matches("\\{\"(designation_id|reason)\": \"[a-zA-Z0-9_-]+\"\\}"));
                }
            }
            assertEquals(JdbcPlatformAuthorization.Condition.NONE, new JdbcPlatformAuthorization(fixture.role(JdbcPlatformAuthorization.ROLE), authentication).evaluate(owner.toString(), password));
            assertThrows(JdbcPlatformAuthorization.MutationDisabled.class, () -> new JdbcPlatformAuthorization(fixture.role(JdbcPlatformAuthorization.ROLE), authentication)
                    .execute(new JdbcPlatformAuthorization.Proposal(business, JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN)));
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(wrong, '\0');
        }
    }

    private static void evidence(Connection connection, JdbcPlatformExecutor.Result result, String outcome, UUID actor, UUID target) throws SQLException {
        assertEquals("SUCCESS".equals(outcome) ? JdbcPlatformExecutor.Outcome.CONFIRMED : JdbcPlatformExecutor.Outcome.DENIED, result.outcome());
        assertTrue(result.evidenceRecorded());
        try (var statement = connection.prepareStatement("SELECT actor_user_id,resource_id,outcome,tenant_id,application_id,session_id,actor_api_key_id,occurred_at<=clock_timestamp() FROM audit.events WHERE correlation_id=?")) {
            statement.setObject(1, result.correlation());
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(actor, rows.getObject(1, UUID.class));
                assertEquals(target, rows.getObject(2, UUID.class));
                assertEquals(outcome, rows.getString(3));
                for (int column = 4; column <= 7; column++) {
                    assertEquals(null, rows.getObject(column));
                }
                assertTrue(rows.getBoolean(8));
                assertFalse(rows.next());
            }
        }
    }

    private static UUID definitive(JdbcManualIdentityRegistration registrar, JdbcPasswordLifecycle lifecycle, String username, char[] password) {
        UUID user = registrar.register(new ManualIdentityInput(username, "Owned privileged fixture", null));
        try (var issued = lifecycle.issue(user)) {
            char[] temporary = issued.take();
            try {
                lifecycle.exchange(username, temporary, password);
            } finally {
                Arrays.fill(temporary, '\0');
            }
        }
        return user;
    }

    private static String active() {
        return "SELECT count(*) FROM platform.administrators WHERE administrator_kind='SUPERADMIN' AND status='ACTIVE'";
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}

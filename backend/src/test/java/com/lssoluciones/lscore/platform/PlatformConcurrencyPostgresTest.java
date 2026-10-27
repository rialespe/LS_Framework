package com.lssoluciones.lscore.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** One owned PG17 scenario: real independent connections, lock observation, commit and rollback. */
@EnabledIfEnvironmentVariable(named = "LS_LOGIN_TEST_ROOT", matches = ".+")
class PlatformConcurrencyPostgresTest {
    @Test
    void serializedRevocationsPreserveLastDelegateAndProtectedIdentities() throws Exception {
        var fixture = new ApplicationLoginPostgresTest();
        char[] password = "Concurrency-381!".toCharArray();
        try (var admin = fixture.role("postgres").getConnection(); var sql = admin.createStatement()) {
            var registration = new JdbcManualIdentityRegistration(fixture.role(JdbcManualIdentityRegistration.DATABASE_ROLE));
            var lifecycle = new JdbcPasswordLifecycle(fixture.role(JdbcPasswordLifecycle.ISSUER_ROLE),
                    fixture.role(JdbcPasswordLifecycle.CHANGER_ROLE), Duration.ofHours(72));
            UUID owner = definitive(registration, lifecycle, "f38-owner", password);
            UUID first = definitive(registration, lifecycle, "f38-first", password);
            UUID second = definitive(registration, lifecycle, "f38-second", password);
            var authentication = new JdbcPasswordAuthentication(fixture.role(JdbcPasswordAuthentication.ROLE), JdbcPasswordAuthentication.Limits.defaults());
            new JdbcPlatformBootstrap(fixture.role(JdbcPlatformBootstrap.ROLE), authentication).initialize("f38-owner", password);
            assertEquals("0", scalar(admin, "SELECT count(*) FROM platform.administrators WHERE administrator_kind='SUPERADMIN'"));
            var authorization = new JdbcPlatformAuthorization(fixture.role(JdbcPlatformAuthorization.ROLE), authentication);
            assertEquals(JdbcPlatformAuthorization.Condition.OWNER, authorization.evaluate("f38-owner", password));
            try (var input = getClass().getResourceAsStream("/session-context-fixture.sql")) {
                sql.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("__USER__", first.toString())
                        .replace("35000000", "38000000").replace("f35-", "f38-"));
            }
            String memberships = scalar(admin, "SELECT count(*) FROM tenant.memberships WHERE user_id='" + first + "'");
            try (var writer = fixture.role(PlatformMutationCoordination.ROLE).getConnection()) {
                writer.setAutoCommit(false);
                var proposal = new JdbcPlatformAuthorization.Proposal(first, JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN);
                var decision = PlatformMutationCoordination.inspect(writer, authentication, "f38-owner", password, proposal);
                assertEquals(owner, decision.verifiedOwner());
                assertEquals(PlatformMutationCoordination.Decision.READY, decision.decision());
                designate(writer, owner, first);
                designate(writer, owner, second);
                writer.commit();
                var denied = PlatformMutationCoordination.inspect(writer, authentication, "f38-first", password,
                        new JdbcPlatformAuthorization.Proposal(second, JdbcPlatformAuthorization.Action.REVOKE_SUPERADMIN));
                assertEquals(PlatformMutationCoordination.Decision.DENIED, denied.decision());
                writer.rollback();
            }
            assertEquals(JdbcPlatformAuthorization.Condition.SUPERADMIN, authorization.evaluate("f38-first", password));
            assertThrows(JdbcPlatformAuthorization.Unavailable.class, () -> authorization.prepare("f38-first", password,
                    second, JdbcPlatformAuthorization.Action.REVOKE_SUPERADMIN));
            assertThrows(JdbcPlatformAuthorization.MutationDisabled.class, () -> authorization.execute(
                    new JdbcPlatformAuthorization.Proposal(first, JdbcPlatformAuthorization.Action.REVOKE_SUPERADMIN)));

            var start = new CyclicBarrier(2);
            var changed = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var a = executor.submit(() -> revoke(fixture, owner, first, start, changed, release));
                var b = executor.submit(() -> revoke(fixture, owner, second, start, changed, release));
                try {
                    boolean reached = changed.await(10, TimeUnit.SECONDS);
                    assertTrue(reached, () -> "One revocation must reach the uncommitted state; SQLSTATEs="
                            + completed(a) + "," + completed(b));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    boolean waiting;
                    do {
                        waiting = "t".equals(scalar(admin, "SELECT EXISTS(SELECT FROM pg_locks l JOIN pg_stat_activity s ON s.pid=l.pid "
                                + "WHERE s.usename='ls_core_platform_mutator_dev' AND l.locktype='advisory' AND NOT l.granted)"));
                        Thread.yield();
                    } while (!waiting && System.nanoTime() < deadline);
                    assertTrue(waiting, "Independent second connection must wait for the global transaction lock");
                    assertEquals("2", scalar(admin, activeDelegates())); // Uncommitted revocation is invisible.
                } finally {
                    release.countDown();
                }
                List<String> outcomes = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
                assertEquals(1, outcomes.stream().filter("COMMITTED"::equals).count());
                assertEquals(1, outcomes.stream().filter("23514"::equals).count());
            }
            assertEquals("1", scalar(admin, activeDelegates()));
            assertEquals("1", scalar(admin, "SELECT count(*) FROM platform.administrators WHERE status='REVOKED' AND revoked_at IS NOT NULL AND revoked_by_user_id='" + owner + "'"));
            UUID survivor = UUID.fromString(scalar(admin, "SELECT user_id FROM platform.administrators WHERE administrator_kind='SUPERADMIN' AND status='ACTIVE'"));
            UUID revoked = survivor.equals(first) ? second : first;
            try (var writer = fixture.role(PlatformMutationCoordination.ROLE).getConnection(); var statement = writer.createStatement()) {
                assertEquals("23514", assertThrows(SQLException.class, () -> statement.executeUpdate(revocation(owner, survivor))).getSQLState());
                assertEquals("23505", assertThrows(SQLException.class, () -> designate(writer, owner, survivor)).getSQLState());
                assertEquals("23514", assertThrows(SQLException.class, () -> designate(writer, first, revoked)).getSQLState());
                assertEquals("23514", assertThrows(SQLException.class, () -> statement.executeUpdate(revocation(owner, owner))).getSQLState());
                // Replacement is a new designation, never a history rewrite.
                designate(writer, owner, revoked);
                writer.setAutoCommit(false);
                assertEquals("23514", assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "UPDATE platform.administrators SET status='REVOKED',revoked_by_user_id='" + owner + "' WHERE administrator_kind='SUPERADMIN' AND status='ACTIVE'")).getSQLState());
                writer.rollback();
                assertEquals("2", scalar(admin, activeDelegates()));
                writer.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                assertEquals("25000", assertThrows(SQLException.class, () -> statement.executeUpdate(revocation(owner, survivor))).getSQLState());
                writer.rollback();
            }
            for (UUID protectedUser : List.of(owner, first, second)) {
                assertEquals("23503", assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE identity.users SET status='SUSPENDED' WHERE id='" + protectedUser + "'")).getSQLState());
                assertEquals("23503", assertThrows(SQLException.class, () -> sql.executeUpdate("DELETE FROM identity.users WHERE id='" + protectedUser + "'")).getSQLState());
            }
            // Even a privileged fixture connection cannot take an ordinary designation DML shortcut.
            assertEquals("42501", assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE platform.administrators SET administrator_kind='SUPERADMIN' WHERE user_id='" + owner + "'")).getSQLState());
            assertEquals("42501", assertThrows(SQLException.class, () -> sql.executeUpdate("DELETE FROM platform.administrators")).getSQLState());
            assertEquals("42501", assertThrows(SQLException.class, () -> sql.execute("TRUNCATE platform.administrators")).getSQLState());
            for (String role : List.of("ls_core_app_dev", JdbcApplicationSessions.ROLE, JdbcPlatformAuthorization.ROLE, "ls_core_authorization_reader_dev")) {
                try (var connection = fixture.role(role).getConnection(); var statement = connection.createStatement()) {
                    assertEquals("42501", assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE platform.administrators SET status='REVOKED'")).getSQLState());
                    assertEquals("42501", assertThrows(SQLException.class, () -> statement.execute("SET ROLE ls_core_platform_mutator_dev")).getSQLState());
                }
            }
            assertEquals(memberships, scalar(admin, "SELECT count(*) FROM tenant.memberships WHERE user_id='" + first + "'"));
            assertEquals("0", scalar(admin, "SELECT count(*) FROM tenant.memberships WHERE user_id='" + owner + "'"));
            assertEquals("1", scalar(admin, "SELECT count(*) FROM platform.administrators WHERE administrator_kind='OWNER' AND status='ACTIVE' AND user_id='" + owner + "'"));
            assertEquals("t", scalar(admin, "SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class WHERE oid IN ('platform.administrators'::regclass,'identity.users'::regclass)"));
            assertEquals("t", scalar(admin, "SELECT NOT has_table_privilege('ls_core_platform_mutator_dev','platform.administrators','DELETE,TRUNCATE,TRIGGER') "
                    + "AND NOT has_column_privilege('ls_core_platform_mutator_dev','identity.user_credentials','password_hash','SELECT') "
                    + "AND NOT has_any_column_privilege('ls_core_platform_mutator_dev','tenant.memberships','SELECT,UPDATE,INSERT') "
                    + "AND NOT has_column_privilege('ls_core_platform_mutator_dev','platform.administrators','user_id','UPDATE')"));
            assertEquals("0", scalar(admin, "SELECT count(*) FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='ls_core_platform_mutator_dev') OR roleid=(SELECT oid FROM pg_roles WHERE rolname='ls_core_platform_mutator_dev')"));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static String completed(java.util.concurrent.Future<String> outcome) {
        try {
            return outcome.isDone() ? outcome.get() : "PENDING";
        } catch (Exception failure) {
            return "WORKER_FAILED";
        }
    }

    private static String revoke(ApplicationLoginPostgresTest fixture, UUID owner, UUID target, CyclicBarrier start,
            CountDownLatch changed, CountDownLatch release) throws Exception {
        try (var connection = fixture.role(PlatformMutationCoordination.ROLE).getConnection(); var statement = connection.createStatement()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            statement.execute("SET LOCAL statement_timeout='15s'");
            start.await(10, TimeUnit.SECONDS);
            try {
                assertEquals(1, statement.executeUpdate(revocation(owner, target)));
                changed.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                connection.commit(); // Never retry an uncertain commit.
                return "COMMITTED";
            } catch (SQLException failure) {
                connection.rollback();
                return failure.getSQLState();
            }
        }
    }

    private static String revocation(UUID owner, UUID target) {
        return "UPDATE platform.administrators SET status='REVOKED',revoked_by_user_id='" + owner + "' WHERE user_id='" + target + "' AND status='ACTIVE'";
    }

    private static void designate(Connection connection, UUID owner, UUID target) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO platform.administrators(user_id,administrator_kind,status,granted_by_user_id) VALUES (?,'SUPERADMIN','ACTIVE',?)")) {
            statement.setObject(1, target);
            statement.setObject(2, owner);
            statement.executeUpdate();
        }
    }

    private static UUID definitive(JdbcManualIdentityRegistration registrar, JdbcPasswordLifecycle lifecycle,
            String username, char[] password) {
        UUID user = registrar.register(new ManualIdentityInput(username, "Owned concurrency fixture", null));
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

    private static String activeDelegates() {
        return "SELECT count(*) FROM platform.administrators WHERE administrator_kind='SUPERADMIN' AND status='ACTIVE'";
    }

    private static String scalar(Connection connection, String query) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}

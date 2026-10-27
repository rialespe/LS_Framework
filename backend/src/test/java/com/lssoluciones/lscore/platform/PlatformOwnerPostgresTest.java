package com.lssoluciones.lscore.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Scope;
import com.lssoluciones.lscore.access.infrastructure.jdbc.JdbcTenantAuthorization;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** One owned PG17 flow; no HTTP adapter, mock authenticator, global seed or historical test invocation. */
@EnabledIfEnvironmentVariable(named = "LS_LOGIN_TEST_ROOT", matches = ".+")
class PlatformOwnerPostgresTest {
    @Test
    void explicitOwnerInstallationProtectedIdentityAndDisabledDelegation() throws Exception {
        var fixture = new ApplicationLoginPostgresTest();
        char[] password = "Owner-371!".toCharArray();
        try (var admin = fixture.role("postgres").getConnection(); var sql = admin.createStatement()) {
            assertEquals("0", scalar(admin, "SELECT count(*) FROM platform.administrators"));
            var registrar = new JdbcManualIdentityRegistration(fixture.role(JdbcManualIdentityRegistration.DATABASE_ROLE));
            var lifecycle = new JdbcPasswordLifecycle(fixture.role(JdbcPasswordLifecycle.ISSUER_ROLE),
                    fixture.role(JdbcPasswordLifecycle.CHANGER_ROLE), Duration.ofHours(72));
            UUID owner = definitive(registrar, lifecycle, "f37-owner", password);
            UUID business = definitive(registrar, lifecycle, "f37-business", password);
            UUID delegate = definitive(registrar, lifecycle, "f37-delegate", password);
            UUID pending = registrar.register(new ManualIdentityInput("f37-pending", "Pending fixture", null));
            var authentication = new JdbcPasswordAuthentication(fixture.role(JdbcPasswordAuthentication.ROLE), JdbcPasswordAuthentication.Limits.defaults());
            var bootstrap = new JdbcPlatformBootstrap(fixture.role(JdbcPlatformBootstrap.ROLE), authentication);
            var authorization = new JdbcPlatformAuthorization(fixture.role(JdbcPlatformAuthorization.ROLE), authentication);
            assertEquals(JdbcPlatformAuthorization.Condition.NONE, authorization.evaluate("f37-owner", password));
            assertThrows(JdbcPlatformBootstrap.Rejected.class, () -> bootstrap.initialize("f37-owner", "Incorrect-372!".toCharArray()));
            assertThrows(JdbcPlatformBootstrap.Rejected.class, () -> bootstrap.initialize("f37-pending", password));
            assertEquals("0", scalar(admin, "SELECT count(*) FROM platform.administrators"));
            try (var issued = lifecycle.issue(pending)) {
                char[] temporary = issued.take();
                try {
                    assertThrows(JdbcPlatformBootstrap.Rejected.class, () -> bootstrap.initialize("f37-pending", temporary));
                } finally {
                    Arrays.fill(temporary, '\0');
                }
            }
            sql.execute("UPDATE identity.user_credentials SET password_expires_at=clock_timestamp()-interval '1 second' WHERE user_id='" + owner + "'");
            assertThrows(JdbcPlatformBootstrap.Rejected.class, () -> bootstrap.initialize("f37-owner", password));
            sql.execute("UPDATE identity.user_credentials SET password_expires_at=clock_timestamp()+interval '90 days',locked_until=clock_timestamp()+interval '1 hour' WHERE user_id='" + owner + "'");
            assertThrows(JdbcPlatformBootstrap.Rejected.class, () -> bootstrap.initialize("f37-owner", password));
            sql.execute("UPDATE identity.user_credentials SET locked_until=NULL WHERE user_id='" + owner + "'");
            var unauthorizedBootstrap = new JdbcPlatformBootstrap(fixture.role(JdbcApplicationSessions.ROLE), authentication);
            assertThrows(JdbcPlatformBootstrap.Rejected.class, () -> unauthorizedBootstrap.initialize("f37-owner", password));
            bootstrap.initialize("f37-owner", password);
            assertEquals("1", scalar(admin, "SELECT count(*) FROM platform.administrators WHERE user_id='" + owner + "' AND administrator_kind='OWNER' AND status='ACTIVE'"));
            assertEquals(JdbcPlatformAuthorization.Condition.OWNER, authorization.evaluate("f37-owner", password));
            assertThrows(JdbcPlatformBootstrap.Rejected.class, () -> bootstrap.initialize("f37-delegate", password));
            assertThrows(JdbcPlatformBootstrap.Rejected.class, () -> bootstrap.initialize("f37-owner", password));
            try (var install = fixture.role(JdbcPlatformBootstrap.ROLE).getConnection(); var direct = install.createStatement()) {
                assertEquals("23505", assertThrows(SQLException.class, () -> direct.executeUpdate("INSERT INTO platform.administrators(user_id,administrator_kind,status) VALUES ('" + delegate + "','OWNER','ACTIVE')")).getSQLState());
                assertEquals("42501", assertThrows(SQLException.class, () -> direct.executeQuery("SELECT password_hash FROM identity.user_credentials")).getSQLState());
            }
            try (var input = getClass().getResourceAsStream("/session-context-fixture.sql")) {
                sql.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("__USER__", business.toString())
                        .replace("35000000", "37000000").replace("f35-", "f37-"));
            }
            sql.execute("INSERT INTO tenant.administrators(tenant_id,membership_id,status,granted_at) VALUES ('" + id(10) + "','" + id(110) + "','ACTIVE',clock_timestamp())");
            var tenantAuthorization = new JdbcTenantAuthorization(fixture.role(JdbcTenantAuthorization.ROLE), authentication);
            var tenantScope = new Scope(id(10), id(1), id(11));
            assertEquals(JdbcTenantAuthorization.Outcome.ALLOWED, tenantAuthorization.evaluate("f37-business", password, tenantScope).outcome());
            assertEquals(JdbcPlatformAuthorization.Condition.NONE, authorization.evaluate("f37-business", password));
            assertEquals(JdbcTenantAuthorization.Outcome.DENIED, tenantAuthorization.evaluate("f37-owner", password, tenantScope).outcome());
            assertThrows(JdbcPlatformAuthorization.Unavailable.class, () -> authorization.delegates("f37-business", password));
            assertThrows(JdbcPlatformAuthorization.Unavailable.class, () -> authorization.designation("f37-business", password, owner));
            assertTrue(authorization.delegates("f37-owner", password).isEmpty());
            assertEquals(JdbcPlatformAuthorization.Condition.OWNER, authorization.designation("f37-owner", password, owner));
            var proposal = authorization.prepare("f37-owner", password, delegate, JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN);
            assertEquals(delegate, proposal.targetUserId());
            assertEquals(JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN, proposal.action());
            assertThrows(JdbcPlatformAuthorization.MutationDisabled.class, () -> authorization.execute(proposal));
            assertThrows(JdbcPlatformAuthorization.MutationDisabled.class, () -> authorization.execute(new JdbcPlatformAuthorization.Proposal(owner, JdbcPlatformAuthorization.Action.REVOKE_SUPERADMIN)));
            assertThrows(JdbcPlatformAuthorization.Unavailable.class, () -> authorization.prepare("f37-owner", password, owner, JdbcPlatformAuthorization.Action.REVOKE_SUPERADMIN));
            assertThrows(JdbcPlatformAuthorization.Unavailable.class, () -> authorization.prepare("f37-owner", password, pending, JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN));
            assertThrows(JdbcPlatformAuthorization.Unavailable.class, () -> authorization.prepare("f37-owner", password, UUID.randomUUID(), JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN));
            assertThrows(JdbcPlatformAuthorization.Unavailable.class, () -> authorization.prepare("f37-business", password, delegate, JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN));
            assertThrows(JdbcPlatformAuthorization.Unavailable.class, () -> authorization.prepare("f37-owner", password, delegate, JdbcPlatformAuthorization.Action.REVOKE_SUPERADMIN));
            assertEquals("1", scalar(admin, "SELECT count(*) FROM platform.administrators"));
            assertEquals("0", scalar(admin, "SELECT count(*) FROM tenant.memberships WHERE user_id='" + delegate + "'"));
            assertEquals("t", scalar(admin, "SELECT status='ACTIVE' FROM identity.users WHERE id='" + delegate + "'"));
            assertEquals(JdbcPlatformAuthorization.Condition.NONE, authorization.evaluate(owner.toString(), password));
            assertEquals(JdbcPlatformAuthorization.Condition.NONE, authorization.evaluate("__Secure-LS-POS-SESSION=not-an-identity", password));
            assertEquals(JdbcPlatformAuthorization.Condition.NONE, new JdbcPlatformAuthorization(fixture.role(JdbcApplicationSessions.ROLE), authentication).evaluate("f37-owner", password));
            for (String principal : new String[] {"ls_core_app_dev", JdbcApplicationSessions.ROLE, JdbcPlatformAuthorization.ROLE, JdbcManualIdentityRegistration.DATABASE_ROLE}) {
                try (var connection = fixture.role(principal).getConnection(); var statement = connection.createStatement()) {
                    assertEquals("42501", assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE platform.administrators SET status='REVOKED'")).getSQLState());
                    assertEquals("42501", assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM platform.administrators")).getSQLState());
                }
            }
            // Constraint/trigger proof even with the disposable fixture administrator: not merely hidden HTTP controls.
            assertEquals("23503", assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE identity.users SET status='SUSPENDED' WHERE id='" + owner + "'")).getSQLState());
            assertEquals("23503", assertThrows(SQLException.class, () -> sql.executeUpdate("DELETE FROM identity.users WHERE id='" + owner + "'")).getSQLState());
            assertEquals("P0001", assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE platform.administrators SET administrator_kind='SUPERADMIN' WHERE user_id='" + owner + "'")).getSQLState());
            assertEquals("P0001", assertThrows(SQLException.class, () -> sql.executeUpdate("DELETE FROM platform.administrators WHERE user_id='" + owner + "'")).getSQLState());
            assertEquals("P0001", assertThrows(SQLException.class, () -> sql.execute("TRUNCATE platform.administrators")).getSQLState());
            assertEquals("P0001", assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO platform.administrators(user_id,administrator_kind,status,granted_by_user_id) VALUES ('" + delegate + "','SUPERADMIN','ACTIVE','" + owner + "')")).getSQLState());
            try (var reader = fixture.role(JdbcPlatformAuthorization.ROLE).getConnection(); var statement = reader.createStatement()) {
                assertEquals("42501", assertThrows(SQLException.class, () -> statement.executeQuery("SELECT password_hash FROM identity.user_credentials")).getSQLState());
                assertEquals("42501", assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM tenant.branches")).getSQLState());
            }
            assertEquals("t", scalar(admin, "SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class WHERE oid IN ('platform.administrators'::regclass,'identity.users'::regclass,'identity.user_credentials'::regclass)"));
            assertEquals("t", scalar(admin, "SELECT NOT has_any_column_privilege('ls_core_platform_reader_dev','platform.administrators','INSERT,UPDATE,REFERENCES') AND NOT has_table_privilege('ls_core_platform_reader_dev','platform.administrators','DELETE,TRUNCATE,TRIGGER')"));
            assertEquals("1", scalar(admin, "SELECT count(*) FROM platform.administrators WHERE administrator_kind='OWNER' AND status='ACTIVE'"));
            assertFalse(java.util.Arrays.stream(JdbcPlatformAuthorization.class.getDeclaredMethods()).anyMatch(method -> method.getName().equals("evaluate") && Arrays.asList(method.getParameterTypes()).contains(UUID.class)));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static UUID definitive(JdbcManualIdentityRegistration registrar, JdbcPasswordLifecycle lifecycle,
            String username, char[] password) {
        UUID user = registrar.register(new ManualIdentityInput(username, "Disposable platform fixture", null));
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

    private static UUID id(int number) {
        return UUID.fromString("37000000-0000-0000-0000-" + String.format(java.util.Locale.ROOT, "%012d", number));
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}

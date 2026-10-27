package com.lssoluciones.lscore.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import com.lssoluciones.lscore.LsCoreApplication;
import com.lssoluciones.lscore.identity.application.ManualIdentityInput;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcManualIdentityRegistration;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordLifecycle;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.MapPropertySource;

/** One HTTPS/owned PG17 flow; real F04 identities, V16 cookies and V17 relations. */
@EnabledIfEnvironmentVariable(named = "LS_LOGIN_TEST_ROOT", matches = ".+")
class MultidevicePostgresTest {
    @Test
    void realConcurrentSessionsIdleExpiryAndScopedRevocation() throws Exception {
        var fixture = new ApplicationLoginPostgresTest();
        char[] password = "Multi-361!".toCharArray();
        Path root = Path.of(System.getenv("LS_LOGIN_TEST_ROOT"));
        var fiscal = LoginApplication.FISCAL;
        var pos = LoginApplication.POS;
        try (var admin = fixture.role("postgres").getConnection(); var sql = admin.createStatement()) {
            var registration = new JdbcManualIdentityRegistration(fixture.role(JdbcManualIdentityRegistration.DATABASE_ROLE));
            UUID user = registration.register(new ManualIdentityInput("f36-context", "Multidevice fixture", null));
            var lifecycle = new JdbcPasswordLifecycle(fixture.role(JdbcPasswordLifecycle.ISSUER_ROLE),
                    fixture.role(JdbcPasswordLifecycle.CHANGER_ROLE), Duration.ofHours(72));
            try (var temporary = lifecycle.issue(user)) {
                char[] initial = temporary.take();
                try {
                    lifecycle.exchange("f36-context", initial, password);
                } finally {
                    Arrays.fill(initial, '\0');
                }
            }
            try (var input = getClass().getResourceAsStream("/session-context-fixture.sql")) {
                sql.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("__USER__", user.toString()).replace("35000000", "36000000").replace("f35-", "f36-"));
            }
            sql.execute("INSERT INTO tenant.administrators(tenant_id,membership_id,status,granted_at) VALUES ('" + id(10) + "','" + id(110) + "','ACTIVE',clock_timestamp())");
            sql.execute("INSERT INTO security.sessions(id,user_id,application_id,token_hash,expires_at) VALUES ('" + id(501)
                    + "','" + id(40) + "','" + id(1) + "',repeat('e',64),clock_timestamp()+interval '1 hour')");
            var properties = new HashMap<String, Object>();
            properties.put("ls.identity.credentials.enabled", "true");
            properties.put("ls.login.enabled", "true");
            properties.put("ls.login.contexts.enabled", "true");
            properties.put("ls.login.multidevice.enabled", "true");
            properties.put("ls.login.fiscal.idle-seconds", "60");
            properties.put("ls.login.pos.idle-seconds", "120");
            properties.put("ls.login.fiscal.application-id", id(1).toString());
            properties.put("ls.login.pos.application-id", id(2).toString());
            properties.put("ls.login.session-seconds", "3600");
            properties.put("ls.access.tenant-authorization.enabled", "false");
            properties.put("ls.access.branch-operation.enabled", "false");
            properties.put("ls.identity.recovery.enabled", "false");
            properties.put("server.address", "127.0.0.1");
            properties.put("server.port", "0");
            properties.put("server.ssl.enabled", "true");
            properties.put("server.ssl.key-store", root.resolve("login-test.p12").toUri().toString());
            properties.put("server.ssl.key-store-type", "PKCS12");
            properties.put("server.ssl.key-alias", "localhost");
            properties.put("server.ssl.key-store-password", System.getenv("LS_LOGIN_TEST_TLS_PASSWORD"));
            properties.put("spring.main.banner-mode", "off");
            for (var channel : Map.of("LS_AUTHENTICATION_DB_", "ls_core_password_authenticator_dev", "LS_LOGIN_DB_", JdbcApplicationSessions.ROLE).entrySet()) {
                properties.put(channel.getKey() + "HOST", "127.0.0.1");
                properties.put(channel.getKey() + "PORT", System.getenv("LS_LOGIN_TEST_PORT"));
                properties.put(channel.getKey() + "NAME", "ls_core_dev");
                properties.put(channel.getKey() + "PASSWORD", System.getenv("LS_LOGIN_TEST_" + channel.getValue().toUpperCase(java.util.Locale.ROOT)));
            }
            try (var server = new SpringApplicationBuilder(LsCoreApplication.class).profiles("test")
                    .initializers(context -> context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("owned-context-selector", properties))).run();
                    var client = HttpClient.newBuilder().sslContext(ApplicationLoginPostgresTest.trust(root.resolve("login-test.p12")))
                            .connectTimeout(Duration.ofSeconds(5)).build()) {
                URI base = URI.create("https://127.0.0.1:" + server.getEnvironment().getRequiredProperty("local.server.port"));
                var browser = new ApplicationLoginPostgresTest.Browser(client, base);
                var other = new ApplicationLoginPostgresTest.Browser(client, base);
                var pointOfSale = new ApplicationLoginPostgresTest.Browser(client, base);
                String credentials = "{\"username\":\"f36-context\",\"password\":\"" + new String(password) + "\"}";
                assertEquals(204, browser.post(fiscal, "login", browser.csrf(fiscal), credentials).statusCode());
                assertEquals(204, other.post(fiscal, "login", other.csrf(fiscal), credentials).statusCode());
                assertEquals(204, pointOfSale.post(pos, "login", pointOfSale.csrf(pos), credentials).statusCode());
                var listing = browser.get(fiscal, "sessions");
                assertEquals(200, listing.statusCode());
                List<Map<String, Object>> entries = JsonPath.read(listing.body(), "$[*]");
                assertEquals(2, entries.size());
                assertEquals(1, entries.stream().filter(entry -> Boolean.TRUE.equals(entry.get("current"))).count());
                String current = entries.stream().filter(entry -> Boolean.TRUE.equals(entry.get("current"))).findFirst().orElseThrow().get("id").toString();
                String remote = entries.stream().filter(entry -> Boolean.FALSE.equals(entry.get("current"))).findFirst().orElseThrow().get("id").toString();
                assertFalse(listing.body().contains("token") || listing.body().contains("hash") || listing.body().contains(id(501).toString()));
                String expiry = scalar(admin, "SELECT expires_at FROM security.sessions WHERE id='" + current + "'");
                assertEquals(200, browser.get(fiscal, "session").statusCode());
                assertEquals(expiry, scalar(admin, "SELECT expires_at FROM security.sessions WHERE id='" + current + "'"));
                assertEquals("t", scalar(admin, "SELECT last_seen_at IS NOT NULL AND last_seen_at>=created_at AND last_seen_at<=clock_timestamp() FROM security.sessions WHERE id='" + current + "'"));
                assertEquals(403, browser.post(fiscal, "sessions/" + remote + "/revoke", null, "").statusCode());
                assertEquals(403, browser.post(fiscal, "sessions/" + id(501) + "/revoke", browser.csrf(fiscal), "").statusCode());
                assertEquals(200, browser.post(fiscal, "sessions/" + remote + "/revoke", browser.csrf(fiscal), "").statusCode());
                assertEquals("t", scalar(admin, "SELECT revoked_at IS NOT NULL FROM security.sessions WHERE id='" + remote + "'"));
                assertEquals(401, other.get(fiscal, "session").statusCode());
                assertEquals(200, browser.get(fiscal, "session").statusCode());
                var idle = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(204, idle.post(fiscal, "login", idle.csrf(fiscal), credentials).statusCode());
                String idleId = currentId(idle.get(fiscal, "sessions").body());
                sql.execute("UPDATE security.sessions SET last_seen_at=clock_timestamp()-interval '61 seconds' WHERE id='" + idleId + "'");
                assertEquals(401, idle.get(fiscal, "session").statusCode());
                assertEquals(401, idle.get(fiscal, "context").statusCode());
                String posId = currentId(pointOfSale.get(pos, "sessions").body());
                sql.execute("UPDATE security.sessions SET last_seen_at=clock_timestamp()-interval '61 seconds' WHERE id='" + posId + "'");
                assertEquals(200, pointOfSale.get(pos, "session").statusCode()); // Independent 120 second POS bound.
                sql.execute("UPDATE security.sessions SET last_seen_at=NULL,created_at=clock_timestamp()-interval '121 seconds' WHERE id='" + posId + "'");
                assertEquals(401, pointOfSale.get(pos, "session").statusCode()); // NULL never means recent activity.
                var target = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(204, target.post(fiscal, "login", target.csrf(fiscal), credentials).statusCode());
                String targetId = currentId(target.get(fiscal, "sessions").body());
                assertEquals(200, target.post(fiscal, "context", target.csrf(fiscal), selection(10, 11)).statusCode());
                assertEquals(200, target.post(fiscal, "context", target.csrf(fiscal), selection(20, 21)).statusCode());
                assertEquals(200, browser.post(fiscal, "context", browser.csrf(fiscal), selection(10, 11)).statusCode());
                assertEquals(403, browser.get(fiscal, "tenants/" + id(20) + "/sessions").statusCode());
                assertEquals(200, browser.get(fiscal, "tenants/" + id(10) + "/session-administration").statusCode());
                var tenantSessions = browser.get(fiscal, "tenants/" + id(10) + "/sessions");
                assertEquals(200, tenantSessions.statusCode());
                assertTrue(tenantSessions.body().contains(targetId));
                assertFalse(tenantSessions.body().contains(id(501).toString()) || tenantSessions.body().contains(posId));
                assertEquals(403, browser.post(fiscal, "tenants/" + id(10) + "/sessions/" + id(501) + "/revoke", browser.csrf(fiscal), "").statusCode());
                assertEquals(204, browser.post(fiscal, "tenants/" + id(10) + "/sessions/" + targetId + "/revoke", browser.csrf(fiscal), "").statusCode());
                assertEquals("1", scalar(admin, "SELECT count(*) FROM security.session_tenant_revocations WHERE session_id='" + targetId + "' AND tenant_id='" + id(10) + "'"));
                var unaffected = target.get(fiscal, "context");
                assertEquals(200, unaffected.statusCode());
                assertTrue(unaffected.body().contains(id(20).toString()));
                assertEquals(403, target.post(fiscal, "context", target.csrf(fiscal), selection(10, 11)).statusCode());
                assertEquals(200, target.post(fiscal, "context", target.csrf(fiscal), selection(20, 21)).statusCode());
                assertEquals(200, target.get(fiscal, "permissions").statusCode());
                sql.execute("UPDATE tenant.administrators SET status='PENDING' WHERE tenant_id='" + id(10) + "'");
                assertEquals("{\"authorized\":false}", browser.get(fiscal, "tenants/" + id(10) + "/session-administration").body());
                assertEquals(403, browser.get(fiscal, "tenants/" + id(10) + "/sessions").statusCode());
                assertEquals(204, pointOfSale.post(pos, "login", pointOfSale.csrf(pos), credentials).statusCode());
                String activePos = currentId(pointOfSale.get(pos, "sessions").body());
                assertEquals(403, browser.post(fiscal, "sessions/" + activePos + "/revoke", browser.csrf(fiscal), "").statusCode());
                assertEquals(200, browser.post(fiscal, "sessions/revoke-others", browser.csrf(fiscal), "").statusCode());
                assertEquals(401, target.get(fiscal, "session").statusCode());
                assertEquals(401, target.get(fiscal, "context").statusCode());
                assertEquals(200, browser.get(fiscal, "context").statusCode());
                assertEquals(200, pointOfSale.get(pos, "session").statusCode());
                try (var restricted = fixture.role(JdbcApplicationSessions.ROLE).getConnection(); var runtime = restricted.createStatement()) {
                    assertEquals("42501", assertThrows(SQLException.class, () -> runtime.executeUpdate("UPDATE security.sessions SET expires_at=clock_timestamp()+interval '2 hours'")).getSQLState());
                    assertEquals("42501", assertThrows(SQLException.class, () -> runtime.executeUpdate("DELETE FROM security.session_tenant_revocations")).getSQLState());
                    assertEquals("42501", assertThrows(SQLException.class, () -> runtime.executeQuery("SELECT * FROM tenant.branches")).getSQLState());
                }
                assertEquals("t", scalar(admin, "SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class WHERE oid IN ('security.sessions'::regclass,'security.session_contexts'::regclass,'security.session_tenant_revocations'::regclass)"));
                assertEquals(204, browser.post(fiscal, "logout", browser.csrf(fiscal), "").statusCode());
                assertEquals(401, browser.get(fiscal, "sessions").statusCode());
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static String currentId(String json) {
        List<String> values = JsonPath.read(json, "$[?(@.current == true)].id");
        assertEquals(1, values.size());
        return values.getFirst();
    }

    private static UUID id(int number) {
        return UUID.fromString("36000000-0000-0000-0000-" + String.format(java.util.Locale.ROOT, "%012d", number));
    }

    private static String selection(int tenant, int branch) {
        return "{\"tenantId\":\"" + id(tenant) + "\",\"branchId\":\"" + id(branch) + "\"}";
    }

    private static String scalar(java.sql.Connection connection, String sql) throws Exception {
        return ApplicationLoginPostgresTest.scalar(connection, sql);
    }
}

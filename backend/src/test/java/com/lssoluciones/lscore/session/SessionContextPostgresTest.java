package com.lssoluciones.lscore.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import com.lssoluciones.lscore.LsCoreApplication;
import com.lssoluciones.lscore.access.application.context.ContextRejectedException;
import com.lssoluciones.lscore.access.application.context.ContextResolver;
import com.lssoluciones.lscore.access.application.context.OperationAdmission;
import com.lssoluciones.lscore.access.application.context.OperationEvidence;
import com.lssoluciones.lscore.access.application.context.OperationIdentity;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource;
import com.lssoluciones.lscore.identity.application.ManualIdentityInput;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcManualIdentityRegistration;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordLifecycle;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.MapPropertySource;

/** One real HTTPS + owned PG17 flow. Reuses V16 client, trust and disposable-root safeguards. */
@EnabledIfEnvironmentVariable(named = "LS_LOGIN_TEST_ROOT", matches = ".+")
class SessionContextPostgresTest {
    @Test
    void realSessionContextSelectionEffectivePermissionsAndRevocation() throws Exception {
        var fixture = new ApplicationLoginPostgresTest();
        char[] password = "Context-351!".toCharArray();
        Path root = Path.of(System.getenv("LS_LOGIN_TEST_ROOT"));
        var fiscal = LoginApplication.FISCAL;
        var pos = LoginApplication.POS;
        try (var admin = fixture.role("postgres").getConnection(); var sql = admin.createStatement()) {
            var registration = new JdbcManualIdentityRegistration(fixture.role(JdbcManualIdentityRegistration.DATABASE_ROLE));
            UUID user = registration.register(new ManualIdentityInput("f35-context", "Context fixture", null));
            var lifecycle = new JdbcPasswordLifecycle(fixture.role(JdbcPasswordLifecycle.ISSUER_ROLE),
                    fixture.role(JdbcPasswordLifecycle.CHANGER_ROLE), Duration.ofHours(72));
            try (var temporary = lifecycle.issue(user)) {
                char[] initial = temporary.take();
                try {
                    lifecycle.exchange("f35-context", initial, password);
                } finally {
                    Arrays.fill(initial, '\0');
                }
            }
            try (var input = getClass().getResourceAsStream("/session-context-fixture.sql")) {
                sql.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("__USER__", user.toString()));
            }
            var properties = new HashMap<String, Object>();
            properties.put("ls.identity.credentials.enabled", "true");
            properties.put("ls.login.enabled", "true");
            properties.put("ls.login.contexts.enabled", "true");
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
                var anonymous = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(401, anonymous.get(fiscal, "tenants").statusCode());
                assertEquals(204, browser.post(fiscal, "login", browser.csrf(fiscal),
                        "{\"username\":\"f35-context\",\"password\":\"" + new String(password) + "\"}").statusCode());
                var tenants = browser.get(fiscal, "tenants");
                assertEquals(200, tenants.statusCode());
                List<String> tenantIds = JsonPath.read(tenants.body(), "$[*].id");
                assertEquals(Set.of(id(10).toString(), id(20).toString()), Set.copyOf(tenantIds));
                var branches = browser.get(fiscal, "tenants/" + id(10) + "/branches");
                assertEquals(200, branches.statusCode());
                List<String> branchIds = JsonPath.read(branches.body(), "$[*].id");
                assertEquals(Set.of(id(11).toString(), id(12).toString()), Set.copyOf(branchIds));
                assertEquals(204, browser.get(fiscal, "context").statusCode());
                assertEquals(403, browser.get(fiscal, "permissions").statusCode());
                assertEquals(403, browser.post(fiscal, "context", null, selection(10, 11)).statusCode());
                assertEquals("0", scalar(admin, "SELECT count(*) FROM security.session_contexts WHERE user_id='" + user + "'"));
                String csrf = browser.csrf(fiscal);
                var selected = browser.post(fiscal, "context", csrf, selection(10, 11));
                assertEquals(200, selected.statusCode());
                List<String> codes = JsonPath.read(selected.body(), "$.permissions[*].code");
                assertEquals(Set.of("p60", "p61", "p69"), Set.copyOf(codes));
                assertEquals(3, codes.size()); // p60 is assigned through both active profiles.
                String contextId = JsonPath.read(selected.body(), "$.id");
                assertEquals("1", scalar(admin, "SELECT count(*) FROM security.session_contexts WHERE id='" + contextId
                        + "' AND user_id='" + user + "' AND application_id='" + id(1) + "' AND tenant_id='" + id(10)
                        + "' AND membership_id='" + id(110) + "' AND branch_id='" + id(11) + "' AND revoked_at IS NULL"));
                var navigation = browser.get(fiscal, "navigation");
                assertEquals(200, navigation.statusCode());
                List<String> modules = JsonPath.read(navigation.body(), "$[*].code");
                assertEquals(List.of("m50"), modules);
                List<String> modulePermissions = JsonPath.read(navigation.body(), "$[0].permissions[*].code");
                assertEquals(Set.of("p60", "p61"), Set.copyOf(modulePermissions));
                assertEquals(200, browser.get(fiscal, "permissions").statusCode());
                assertEquals(403, browser.post(fiscal, "context", csrf, selection(30, 31)).statusCode());
                var foreign = browser.get(fiscal, "tenants/" + id(30) + "/branches");
                assertEquals(403, foreign.statusCode());
                assertFalse(foreign.body().contains("Tenant 30"));
                assertEquals(403, browser.post(fiscal, "context", csrf, selection(20, 11)).statusCode());
                assertEquals(403, browser.post(fiscal, "context", csrf, selection(10, 13)).statusCode());
                assertEquals(200, browser.post(fiscal, "context", csrf, selection(20, 21)).statusCode());
                var companyB = browser.get(fiscal, "permissions");
                List<String> companyCodes = JsonPath.read(companyB.body(), "$[*].code");
                assertEquals(List.of("p63"), companyCodes);
                var changed = browser.post(fiscal, "context", csrf, selection(10, 12));
                assertEquals(200, changed.statusCode());
                List<String> changedCodes = JsonPath.read(changed.body(), "$.permissions[*].code");
                assertEquals(List.of("p62"), changedCodes);
                assertFalse(changed.body().contains("p60") || changed.body().contains("p61") || changed.body().contains("p63"));
                assertEquals("1", scalar(admin, "SELECT count(*) FROM security.session_contexts WHERE user_id='" + user + "' AND revoked_at IS NULL"));
                assertEquals("2", scalar(admin, "SELECT count(*) FROM security.session_contexts WHERE user_id='" + user + "' AND revoked_at IS NOT NULL"));
                assertEquals(204, other.post(pos, "login", other.csrf(pos),
                        "{\"username\":\"f35-context\",\"password\":\"" + new String(password) + "\"}").statusCode());
                assertEquals(403, other.post(pos, "context", other.csrf(pos), selection(20, 21)).statusCode());
                assertEquals(204, other.get(pos, "context").statusCode()); // Fiscal selection never leaks to POS.
                var crossApp = HttpRequest.newBuilder(base.resolve(fiscal.path() + "/context"))
                        .timeout(Duration.ofSeconds(10)).header("Cookie", fiscal.sessionCookie() + "=" + other.token(pos)).GET().build();
                assertEquals(401, client.send(crossApp, HttpResponse.BodyHandlers.ofString()).statusCode());
                var scope = new TrustedContextSource.Scope(id(10), id(1), id(12));
                var operation = OperationIdentity.pending();
                UUID sessionId = UUID.fromString(scalar(admin, "SELECT id FROM security.sessions WHERE user_id='" + user + "' AND application_id='" + id(1) + "'"));
                UUID currentId = UUID.fromString(JsonPath.read(changed.body(), "$.id"));
                var claims = new ContextResolver.Claims(scope, TrustedContextSource.Origin.SESSION, user, sessionId, currentId, id(110), id(112));
                assertThrows(ContextRejectedException.class, () -> new OperationAdmission(Clock.systemUTC()).session(operation, scope,
                        new OperationEvidence.Session(operation, claims, Instant.now().plusSeconds(600))));
                try (var restricted = fixture.role(JdbcApplicationSessions.ROLE).getConnection(); var runtime = restricted.createStatement()) {
                    assertEquals("42501", assertThrows(SQLException.class, () -> runtime.executeQuery("SELECT * FROM tenant.branches")).getSQLState());
                    assertEquals("42501", assertThrows(SQLException.class, () -> runtime.executeUpdate("UPDATE tenant.branches SET name='forbidden'")).getSQLState());
                    assertEquals("42501", assertThrows(SQLException.class, () -> runtime.executeQuery("SELECT password_hash FROM identity.user_credentials")).getSQLState());
                    assertEquals("42501", assertThrows(SQLException.class, () -> runtime.executeUpdate("UPDATE security.session_contexts SET tenant_id='" + id(20) + "'")).getSQLState());
                }
                sql.execute("UPDATE security.branch_profile_assignments SET status='DISABLED' WHERE branch_membership_id='" + id(112) + "'");
                var noProfiles = browser.get(fiscal, "context");
                assertEquals(200, noProfiles.statusCode());
                List<String> empty = JsonPath.read(noProfiles.body(), "$.permissions[*].code");
                assertTrue(empty.isEmpty());
                assertEquals("[]", browser.get(fiscal, "navigation").body());
                sql.execute("UPDATE tenant.memberships SET status='DISABLED' WHERE id='" + id(110) + "'");
                assertEquals(403, browser.get(fiscal, "context").statusCode());
                assertEquals(403, browser.get(fiscal, "permissions").statusCode());
                assertEquals(403, browser.get(fiscal, "navigation").statusCode());
                assertEquals(403, browser.post(fiscal, "context", csrf, selection(10, 11)).statusCode());
                assertEquals(200, browser.get(fiscal, "session").statusCode());
                assertEquals("t", scalar(admin, "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid='security.session_contexts'::regclass"));
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static UUID id(int number) {
        return UUID.fromString("35000000-0000-0000-0000-" + String.format(java.util.Locale.ROOT, "%012d", number));
    }

    private static String selection(int tenant, int branch) {
        return "{\"tenantId\":\"" + id(tenant) + "\",\"branchId\":\"" + id(branch) + "\"}";
    }

    private static String scalar(java.sql.Connection connection, String sql) throws Exception {
        return ApplicationLoginPostgresTest.scalar(connection, sql);
    }
}

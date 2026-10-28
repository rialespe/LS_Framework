package com.lssoluciones.lscore.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lssoluciones.lscore.LsCoreApplication;
import com.lssoluciones.lscore.identity.application.ManualIdentityInput;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcManualIdentityRegistration;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordLifecycle;
import com.lssoluciones.lscore.platform.JdbcPlatformAuthorization;
import com.lssoluciones.lscore.platform.JdbcPlatformBootstrap;
import com.lssoluciones.lscore.platform.JdbcPlatformExecutor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.MapPropertySource;

@EnabledIfEnvironmentVariable(named = "LS_LOGIN_TEST_ROOT", matches = ".+")
class TenantRegistrationPostgresTest {
    @Test
    void coreHttpsRegistrationAtomicPendingAdministratorIdempotencyAndIsolation() throws Exception {
        var fixture = new ApplicationLoginPostgresTest();
        char[] password = "Registration-401!".toCharArray();
        try (var admin = fixture.role("postgres").getConnection(); var sql = admin.createStatement()) {
            var registrar = new JdbcManualIdentityRegistration(fixture.role(JdbcManualIdentityRegistration.DATABASE_ROLE));
            var lifecycle = new JdbcPasswordLifecycle(fixture.role(JdbcPasswordLifecycle.ISSUER_ROLE), fixture.role(JdbcPasswordLifecycle.CHANGER_ROLE), Duration.ofHours(72));
            UUID owner = definitive(registrar, lifecycle, "f40-owner", password);
            UUID business = definitive(registrar, lifecycle, "f40-business", password);
            UUID delegate = definitive(registrar, lifecycle, "f40-delegate", password);
            UUID initial = definitive(registrar, lifecycle, "f40-initial", password);
            var authentication = new JdbcPasswordAuthentication(fixture.role(JdbcPasswordAuthentication.ROLE), JdbcPasswordAuthentication.Limits.defaults());
            new JdbcPlatformBootstrap(fixture.role(JdbcPlatformBootstrap.ROLE), authentication).initialize("f40-owner", password);
            var global = new JdbcPlatformExecutor(fixture.role(JdbcPlatformExecutor.ROLE), fixture.role(JdbcPlatformExecutor.SECURITY_LOG_ROLE), authentication);
            assertEquals(JdbcPlatformExecutor.Outcome.CONFIRMED, global.execute("f40-owner", password, delegate, JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN).outcome());
            UUID fiscal = UUID.randomUUID();
            UUID pos = UUID.randomUUID();
            sql.execute("INSERT INTO catalog.applications(id,code,name,status) VALUES ('" + fiscal + "','f40-fiscal','Fiscal fixture','PUBLISHED'),('" + pos + "','f40-pos','POS fixture','PUBLISHED')");
            String core = ApplicationLoginPostgresTest.scalar(admin, "SELECT id FROM catalog.applications WHERE code='ls-core' AND status='PUBLISHED'");
            var properties = new HashMap<String, Object>();
            properties.put("ls.identity.credentials.enabled", "true");
            properties.put("ls.login.enabled", "true");
            properties.put("ls.login.multidevice.enabled", "true");
            properties.put("ls.login.contexts.enabled", "true");
            properties.put("ls.login.core.enabled", "true");
            properties.put("ls.core.registration.enabled", "true");
            properties.put("ls.login.fiscal.application-id", fiscal.toString());
            properties.put("ls.login.pos.application-id", pos.toString());
            properties.put("ls.login.core.application-id", core);
            properties.put("server.address", "127.0.0.1");
            properties.put("server.port", "0");
            properties.put("server.ssl.enabled", "true");
            properties.put("server.ssl.key-store", Path.of(System.getenv("LS_LOGIN_TEST_ROOT")).resolve("login-test.p12").toUri().toString());
            properties.put("server.ssl.key-store-type", "PKCS12");
            properties.put("server.ssl.key-alias", "localhost");
            properties.put("server.ssl.key-store-password", System.getenv("LS_LOGIN_TEST_TLS_PASSWORD"));
            properties.put("spring.main.banner-mode", "off");
            for (var channel : Map.of("LS_AUTHENTICATION_DB_", JdbcPasswordAuthentication.ROLE, "LS_LOGIN_DB_", JdbcApplicationSessions.ROLE,
                    "LS_TENANT_REGISTRATION_DB_", JdbcTenantRegistration.ROLE, "LS_TENANT_REGISTRATION_LOG_DB_", JdbcTenantRegistration.LOG_ROLE).entrySet()) {
                properties.put(channel.getKey() + "HOST", "127.0.0.1");
                properties.put(channel.getKey() + "PORT", System.getenv("LS_LOGIN_TEST_PORT"));
                properties.put(channel.getKey() + "NAME", "ls_core_dev");
                properties.put(channel.getKey() + "PASSWORD", System.getenv("LS_LOGIN_TEST_" + channel.getValue().toUpperCase(java.util.Locale.ROOT)));
            }
            String identityCount = ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM identity.users");
            try (var server = new SpringApplicationBuilder(LsCoreApplication.class).profiles("test")
                    .initializers(context -> context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("owned-registration", properties))).run();
                    var client = HttpClient.newBuilder().sslContext(ApplicationLoginPostgresTest.trust(Path.of(System.getenv("LS_LOGIN_TEST_ROOT")).resolve("login-test.p12"))).build()) {
                URI base = URI.create("https://127.0.0.1:" + server.getEnvironment().getRequiredProperty("local.server.port"));
                var browser = new ApplicationLoginPostgresTest.Browser(client, base);
                var logged = browser.post(LoginApplication.CORE, "login", browser.csrf(LoginApplication.CORE), credentials("f40-owner", password));
                assertEquals(204, logged.statusCode());
                String cookie = logged.headers().allValues("Set-Cookie").stream().filter(v -> v.startsWith(LoginApplication.CORE.sessionCookie())).findFirst().orElseThrow();
                assertTrue(cookie.contains("Secure") && cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict") && cookie.contains("Path=/api/auth/core"));
                assertEquals(200, browser.get(LoginApplication.CORE, "authority").statusCode());
                assertEquals(200, browser.post(LoginApplication.CORE, "initial-administrator", browser.csrf(LoginApplication.CORE), "{\"username\":\"f40-initial\"}").statusCode());
                UUID request = UUID.randomUUID();
                String payload = payload(request, initial, "  Empresa nueva  ", password);
                assertEquals(403, browser.post(LoginApplication.CORE, "registrations", null, payload).statusCode());
                assertEquals(403, browser.post(LoginApplication.CORE, "registrations", browser.csrf(LoginApplication.CORE), payload(UUID.randomUUID(), initial, "Incorrecta", "Incorrect-402!".toCharArray())).statusCode());
                var created = browser.post(LoginApplication.CORE, "registrations", browser.csrf(LoginApplication.CORE), payload);
                assertEquals(200, created.statusCode());
                assertTrue(created.body().contains("CONFIRMED") && created.body().contains("PENDING"));
                UUID tenant = id(created.body());
                var replay = browser.post(LoginApplication.CORE, "registrations", browser.csrf(LoginApplication.CORE), payload);
                assertEquals(200, replay.statusCode());
                assertEquals(tenant, id(replay.body()));
                assertEquals(200, browser.get(LoginApplication.CORE, "registrations/" + request).statusCode());
                assertEquals("1", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM tenant.initial_registrations WHERE request_id='" + request + "'"));
                assertEquals("1", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM tenant.tenants t JOIN tenant.memberships m ON m.tenant_id=t.id JOIN tenant.administrators a ON a.tenant_id=t.id AND a.membership_id=m.id WHERE t.id='" + tenant + "' AND t.status='PENDING' AND m.status='PENDING' AND a.status='PENDING' AND m.user_id='" + initial + "'"));
                assertEquals("1", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM audit.events WHERE action_code='TENANT_REGISTER' AND outcome='SUCCESS' AND resource_id='" + tenant + "' AND actor_user_id='" + owner + "' AND correlation_id='" + request + "'"));
                for (var application : new LoginApplication[] {LoginApplication.FISCAL, LoginApplication.POS}) {
                    var other = new ApplicationLoginPostgresTest.Browser(client, base);
                    assertEquals(204, other.post(application, "login", other.csrf(application), credentials("f40-owner", password)).statusCode());
                    var forged = HttpRequest.newBuilder(base.resolve("/api/auth/core/authority")).header("Cookie", LoginApplication.CORE.sessionCookie() + "=" + other.token(application)).GET().build();
                    assertEquals(401, client.send(forged, HttpResponse.BodyHandlers.ofString()).statusCode());
                }
                var ordinary = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(204, ordinary.post(LoginApplication.CORE, "login", ordinary.csrf(LoginApplication.CORE), credentials("f40-business", password)).statusCode());
                assertEquals(403, ordinary.get(LoginApplication.CORE, "authority").statusCode());
                assertEquals(403, ordinary.post(LoginApplication.CORE, "registrations", ordinary.csrf(LoginApplication.CORE), payload(UUID.randomUUID(), initial, "Sin permiso", password)).statusCode());
                var delegated = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(204, delegated.post(LoginApplication.CORE, "login", delegated.csrf(LoginApplication.CORE), credentials("f40-delegate", password)).statusCode());
                assertEquals(403, delegated.get(LoginApplication.CORE, "authority").statusCode());
                sql.execute("INSERT INTO platform.tenant_registration_grants(designation_id) SELECT id FROM platform.administrators WHERE user_id='" + delegate + "' AND administrator_kind='SUPERADMIN' AND status='ACTIVE'");
                assertEquals(200, delegated.get(LoginApplication.CORE, "authority").statusCode());
                assertEquals(200, delegated.post(LoginApplication.CORE, "registrations", delegated.csrf(LoginApplication.CORE), payload(UUID.randomUUID(), business, "Delegada", password)).statusCode());
                var pendingAdmin = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(204, pendingAdmin.post(LoginApplication.FISCAL, "login", pendingAdmin.csrf(LoginApplication.FISCAL), credentials("f40-initial", password)).statusCode());
                assertEquals("[]", pendingAdmin.get(LoginApplication.FISCAL, "tenants").body());
                String before = ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM tenant.tenants");
                for (String table : new String[] {"tenant.administrators", "audit.events"}) {
                    sql.execute("CREATE FUNCTION tenant.fixture_registration_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF TG_TABLE_SCHEMA='audit' THEN IF NEW.outcome<>'SUCCESS' THEN RETURN NEW; END IF; END IF; RAISE EXCEPTION 'Owned registration fault'; END $$");
                    sql.execute("CREATE TRIGGER zz_fixture_registration_fault BEFORE INSERT ON " + table + " FOR EACH ROW EXECUTE FUNCTION tenant.fixture_registration_fault()");
                    try {
                        assertEquals(403, browser.post(LoginApplication.CORE, "registrations", browser.csrf(LoginApplication.CORE), payload(UUID.randomUUID(), initial, "Debe revertirse", password)).statusCode());
                    } finally {
                        sql.execute("DROP TRIGGER zz_fixture_registration_fault ON " + table);
                        sql.execute("DROP FUNCTION tenant.fixture_registration_fault()");
                    }
                    assertEquals(before, ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM tenant.tenants"));
                }
                assertEquals(identityCount, ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM identity.users"));
                assertEquals(204, browser.post(LoginApplication.CORE, "logout", browser.csrf(LoginApplication.CORE), "{}").statusCode());
                assertEquals(401, browser.get(LoginApplication.CORE, "authority").statusCode());
            }
            assertEquals("t", ApplicationLoginPostgresTest.scalar(admin, "SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class WHERE oid IN ('tenant.tenants'::regclass,'tenant.memberships'::regclass,'tenant.administrators'::regclass,'tenant.initial_registrations'::regclass,'platform.tenant_registration_grants'::regclass,'audit.events'::regclass)"));
            try (var runtime = fixture.role(JdbcApplicationSessions.ROLE).getConnection(); var direct = runtime.createStatement()) {
                assertEquals("42501", assertThrows(SQLException.class, () -> direct.executeUpdate("INSERT INTO tenant.tenants(name,status) VALUES('Forbidden','PENDING')")).getSQLState());
            }
            assertNotEquals(owner, business);
        } finally { Arrays.fill(password, '\0'); }
    }

    private static String payload(UUID request, UUID initial, String name, char[] password) {
        return "{\"requestId\":\"" + request + "\",\"name\":\"" + name + "\",\"administratorId\":\"" + initial + "\",\"password\":\"" + new String(password) + "\"}";
    }
    private static String credentials(String username, char[] password) { return "{\"username\":\"" + username + "\",\"password\":\"" + new String(password) + "\"}"; }
    private static UUID id(String json) {
        var match = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(json);
        assertTrue(match.find());
        return UUID.fromString(match.group(1));
    }
    private static UUID definitive(JdbcManualIdentityRegistration registration, JdbcPasswordLifecycle lifecycle, String username, char[] password) {
        UUID id = registration.register(new ManualIdentityInput(username, "Owned registration fixture", null));
        try (var issued = lifecycle.issue(id)) {
            char[] temporary = issued.take();
            try {
                lifecycle.exchange(username, temporary, password);
            } finally {
                Arrays.fill(temporary, '\0');
            }
        }
        return id;
    }
}

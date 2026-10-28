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
class TenantLifecyclePostgresTest {
    @Test
    void coreLifecycleWithAtomicAuditRevocationAndConcurrentConnections() throws Exception {
        var fixture = new ApplicationLoginPostgresTest();
        char[] password = "Registration-401!".toCharArray();
        try (var admin = fixture.role("postgres").getConnection(); var sql = admin.createStatement(); var operationPool = new com.zaxxer.hikari.HikariDataSource()) {
            var registrar = new JdbcManualIdentityRegistration(fixture.role(JdbcManualIdentityRegistration.DATABASE_ROLE));
            var lifecycle = new JdbcPasswordLifecycle(fixture.role(JdbcPasswordLifecycle.ISSUER_ROLE), fixture.role(JdbcPasswordLifecycle.CHANGER_ROLE), Duration.ofHours(72));
            UUID owner = definitive(registrar, lifecycle, "f41-owner", password);
            UUID business = definitive(registrar, lifecycle, "f41-business", password);
            UUID delegate = definitive(registrar, lifecycle, "f41-delegate", password);
            UUID initial = definitive(registrar, lifecycle, "f41-initial", password);
            var authentication = new JdbcPasswordAuthentication(fixture.role(JdbcPasswordAuthentication.ROLE), JdbcPasswordAuthentication.Limits.defaults());
            new JdbcPlatformBootstrap(fixture.role(JdbcPlatformBootstrap.ROLE), authentication).initialize("f41-owner", password);
            var global = new JdbcPlatformExecutor(fixture.role(JdbcPlatformExecutor.ROLE), fixture.role(JdbcPlatformExecutor.SECURITY_LOG_ROLE), authentication);
            assertEquals(JdbcPlatformExecutor.Outcome.CONFIRMED, global.execute("f41-owner", password, delegate, JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN).outcome());
            UUID fiscal = UUID.randomUUID();
            UUID pos = UUID.randomUUID();
            sql.execute("INSERT INTO catalog.applications(id,code,name,status) VALUES ('" + fiscal + "','f41-fiscal','Fiscal fixture','PUBLISHED'),('" + pos + "','f41-pos','POS fixture','PUBLISHED')");
            String core = ApplicationLoginPostgresTest.scalar(admin, "SELECT id FROM catalog.applications WHERE code='ls-core' AND status='PUBLISHED'");
            var properties = new HashMap<String, Object>();
            properties.put("ls.identity.credentials.enabled", "true");
            properties.put("ls.login.enabled", "true");
            properties.put("ls.login.multidevice.enabled", "true");
            properties.put("ls.login.contexts.enabled", "true");
            properties.put("ls.login.core.enabled", "true");
            properties.put("ls.core.registration.enabled", "true");
            properties.put("ls.core.lifecycle.enabled", "true");
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
            for (var channel : Map.of("LS_TENANT_LIFECYCLE_DB_", JdbcTenantLifecycle.ROLE, "LS_TENANT_LIFECYCLE_LOG_DB_", JdbcTenantLifecycle.LOG_ROLE, "LS_AUTHENTICATION_DB_", JdbcPasswordAuthentication.ROLE, "LS_LOGIN_DB_", JdbcApplicationSessions.ROLE,
                    "LS_TENANT_REGISTRATION_DB_", JdbcTenantRegistration.ROLE, "LS_TENANT_REGISTRATION_LOG_DB_", JdbcTenantRegistration.LOG_ROLE).entrySet()) {
                properties.put(channel.getKey() + "HOST", "127.0.0.1");
                properties.put(channel.getKey() + "PORT", System.getenv("LS_LOGIN_TEST_PORT"));
                properties.put(channel.getKey() + "NAME", "ls_core_dev");
                properties.put(channel.getKey() + "PASSWORD", System.getenv("LS_LOGIN_TEST_" + channel.getValue().toUpperCase(java.util.Locale.ROOT)));
            }
            try (var server = new SpringApplicationBuilder(LsCoreApplication.class).profiles("test")
                    .initializers(context -> context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("owned-registration", properties))).run();
                    var client = HttpClient.newBuilder().sslContext(ApplicationLoginPostgresTest.trust(Path.of(System.getenv("LS_LOGIN_TEST_ROOT")).resolve("login-test.p12"))).build()) {
                URI base = URI.create("https://127.0.0.1:" + server.getEnvironment().getRequiredProperty("local.server.port"));
                var browser = new ApplicationLoginPostgresTest.Browser(client, base);
                var logged = browser.post(LoginApplication.CORE, "login", browser.csrf(LoginApplication.CORE), credentials("f41-owner", password));
                assertEquals(204, logged.statusCode());
                assertEquals(200, browser.get(LoginApplication.CORE, "tenant-authority").statusCode());
                UUID request = UUID.randomUUID();
                var created = browser.post(LoginApplication.CORE, "registrations", browser.csrf(LoginApplication.CORE), payload(request, initial, "Lifecycle tenant", password));
                assertEquals(200, created.statusCode());
                UUID tenant = id(created.body());
                assertEquals(200, browser.get(LoginApplication.CORE, "tenants").statusCode());
                assertEquals(200, browser.get(LoginApplication.CORE, "tenants/" + tenant).statusCode());
                String activate = "tenants/" + tenant + "/activate";
                assertEquals(403, browser.post(LoginApplication.CORE, activate, null, operation(password)).statusCode());
                assertEquals(403, browser.post(LoginApplication.CORE, activate, browser.csrf(LoginApplication.CORE), operation("Incorrect-402!".toCharArray())).statusCode());
                sql.execute("UPDATE identity.users SET status='PENDING' WHERE id='" + initial + "'");
                assertEquals(422, browser.post(LoginApplication.CORE, activate, browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                assertEquals("PENDING", ApplicationLoginPostgresTest.scalar(admin, "SELECT status FROM tenant.tenants WHERE id='" + tenant + "'"));
                sql.execute("UPDATE identity.users SET status='ACTIVE' WHERE id='" + initial + "'");
                assertEquals(200, browser.post(LoginApplication.CORE, activate, browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                assertEquals("1", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM tenant.memberships m JOIN tenant.administrators a ON a.membership_id=m.id WHERE m.tenant_id='" + tenant + "' AND m.status='ACTIVE' AND a.status='ACTIVE'"));
                assertEquals(409, browser.post(LoginApplication.CORE, activate, browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                // Existing V17 fixture supplies two authorized tenants for the same real F04 identity.
                String fixtureSql = java.nio.file.Files.readString(Path.of("src/test/resources/session-context-fixture.sql")).replace("__USER__", initial.toString());
                sql.execute(fixtureSql);
                String otherTenant = "35000000-0000-0000-0000-000000000020";
                // Attach the initial registration to its own company by provisioning only branch/application fixture data.
                String membership = ApplicationLoginPostgresTest.scalar(admin, "SELECT membership_id FROM tenant.initial_registrations WHERE tenant_id='" + tenant + "'");
                sql.execute("INSERT INTO tenant.branches(id,tenant_id,code,name,status) VALUES('41000000-0000-0000-0000-000000000001','" + tenant + "','fixture','Owned branch','ACTIVE')");
                sql.execute("INSERT INTO tenant.branch_memberships(tenant_id,membership_id,branch_id,status) VALUES('" + tenant + "','" + membership + "','41000000-0000-0000-0000-000000000001','ACTIVE')");
                sql.execute("INSERT INTO catalog.tenant_applications(tenant_id,application_id,status) VALUES('" + tenant + "','35000000-0000-0000-0000-000000000001','ACTIVE')");
                // The configured Fiscal application uses the fixture application for context traffic.
                var sessionService = new JdbcApplicationSessions(fixture.role(JdbcApplicationSessions.ROLE), authentication,
                        Map.of(LoginApplication.FISCAL, UUID.fromString("35000000-0000-0000-0000-000000000001"), LoginApplication.POS, UUID.fromString("35000000-0000-0000-0000-000000000002")), Duration.ofHours(1), Map.of(LoginApplication.FISCAL, Duration.ofMinutes(30), LoginApplication.POS, Duration.ofMinutes(30)));
                operationPool.setDataSource(fixture.role("ls_core_branch_operation_dev"));
                operationPool.setReadOnly(true);
                operationPool.setAutoCommit(true);
                operationPool.setMaximumPoolSize(1);
                operationPool.setMinimumIdle(0);
                var authorization = new com.lssoluciones.lscore.access.infrastructure.jdbc.JdbcTenantAuthorization(fixture.role("ls_core_authorization_reader_dev"), authentication);
                var coordinator = new com.lssoluciones.lscore.access.infrastructure.jdbc.BranchReadCoordinator(authorization, operationPool, fixture.role("ls_core_operation_emitter_dev"));
                var scope = new com.lssoluciones.lscore.access.application.context.TrustedContextSource.Scope(tenant,
                        UUID.fromString("35000000-0000-0000-0000-000000000001"), UUID.fromString("41000000-0000-0000-0000-000000000001"));
                assertEquals(tenant, coordinator.read("f41-initial", password, scope).tenantId());
                var contexts = new JdbcSessionContexts(sessionService);
                var sessionA = sessionService.login(LoginApplication.FISCAL, "f41-initial", password);
                var sessionB = sessionService.login(LoginApplication.FISCAL, "f41-initial", password);
                var contextA = contexts.select(LoginApplication.FISCAL, sessionA, tenant, UUID.fromString("41000000-0000-0000-0000-000000000001"));
                contexts.select(LoginApplication.FISCAL, sessionB, UUID.fromString(otherTenant), UUID.fromString("35000000-0000-0000-0000-000000000021"));
                assertEquals(200, browser.post(LoginApplication.CORE, "tenants/" + tenant + "/suspend", browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                assertThrows(com.lssoluciones.lscore.access.application.context.ContextRejectedException.class, () -> coordinator.read("f41-initial", password, scope));
                assertTrue(contexts.tenants(LoginApplication.FISCAL, sessionA).stream().noneMatch(t -> t.id().equals(tenant)));
                assertThrows(JdbcSessionContexts.Denied.class, () -> contexts.select(LoginApplication.FISCAL, sessionA, tenant, UUID.fromString("41000000-0000-0000-0000-000000000001")));
                assertEquals(null, contexts.current(LoginApplication.FISCAL, sessionA));
                assertEquals(UUID.fromString(otherTenant), contexts.current(LoginApplication.FISCAL, sessionB).tenantId());
                assertEquals(200, browser.post(LoginApplication.CORE, "tenants/" + tenant + "/reactivate", browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                assertEquals(null, contexts.current(LoginApplication.FISCAL, sessionA));
                assertEquals("1", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM security.session_contexts WHERE id='" + contextA.id() + "' AND revoked_at IS NOT NULL"));
                // Audit failure rolls back status and context revocation together.
                contexts.select(LoginApplication.FISCAL, sessionA, tenant, UUID.fromString("41000000-0000-0000-0000-000000000001"));
                sql.execute("CREATE FUNCTION tenant.fixture_lifecycle_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action_code='TENANT_SUSPEND' AND NEW.outcome='SUCCESS' THEN RAISE EXCEPTION 'Owned audit fault'; END IF; RETURN NEW; END $$");
                sql.execute("CREATE TRIGGER zz_fixture_lifecycle_fault BEFORE INSERT ON audit.events FOR EACH ROW EXECUTE FUNCTION tenant.fixture_lifecycle_fault()");
                try {
                    assertEquals(503, browser.post(LoginApplication.CORE, "tenants/" + tenant + "/suspend", browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                } finally {
                    sql.execute("DROP TRIGGER zz_fixture_lifecycle_fault ON audit.events");
                    sql.execute("DROP FUNCTION tenant.fixture_lifecycle_fault()");
                }
                assertEquals("ACTIVE", ApplicationLoginPostgresTest.scalar(admin, "SELECT status FROM tenant.tenants WHERE id='" + tenant + "'"));
                assertEquals(tenant, contexts.current(LoginApplication.FISCAL, sessionA).tenantId());
                // Two physical writer connections race for the same transition: exactly one can commit.
                try (var first = fixture.role(JdbcTenantLifecycle.ROLE).getConnection(); var second = fixture.role(JdbcTenantLifecycle.ROLE).getConnection()) {
                    first.setAutoCommit(false);
                    second.setAutoCommit(false);
                    insertOperation(first, owner, tenant);
                    var blocked = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            insertOperation(second, owner, tenant);
                            second.commit();
                            return "COMMITTED";
                        } catch (SQLException failure) {
                            try { second.rollback(); } catch (SQLException ignored) { }
                            return failure.getSQLState();
                        }
                    });
                    Thread.sleep(150);
                    assertTrue(!blocked.isDone());
                    first.commit();
                    assertEquals("P2301", blocked.get(5, java.util.concurrent.TimeUnit.SECONDS));
                }
                assertEquals("4", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM tenant.lifecycle_operations WHERE tenant_id='" + tenant + "'"));
                assertEquals("4", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM audit.events WHERE resource_id='" + tenant + "' AND action_code IN ('TENANT_ACTIVATE','TENANT_SUSPEND','TENANT_REACTIVATE') AND outcome='SUCCESS'"));
                assertEquals("0", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM tenant.lifecycle_operations o LEFT JOIN audit.events e ON e.correlation_id=o.request_id AND e.outcome='SUCCESS' WHERE e.id IS NULL"));
                for (var application : new LoginApplication[] {LoginApplication.FISCAL, LoginApplication.POS}) {
                    var other = new ApplicationLoginPostgresTest.Browser(client, base);
                    assertEquals(204, other.post(application, "login", other.csrf(application), credentials("f41-owner", password)).statusCode());
                    var forged = HttpRequest.newBuilder(base.resolve("/api/auth/core/authority")).header("Cookie", LoginApplication.CORE.sessionCookie() + "=" + other.token(application)).GET().build();
                    assertEquals(401, client.send(forged, HttpResponse.BodyHandlers.ofString()).statusCode());
                }
                var ordinary = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(204, ordinary.post(LoginApplication.CORE, "login", ordinary.csrf(LoginApplication.CORE), credentials("f41-business", password)).statusCode());
                assertEquals(403, ordinary.get(LoginApplication.CORE, "tenants").statusCode());
                assertEquals(403, ordinary.post(LoginApplication.CORE, "tenants/" + tenant + "/reactivate", ordinary.csrf(LoginApplication.CORE), operation(password)).statusCode());
                var delegated = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(204, delegated.post(LoginApplication.CORE, "login", delegated.csrf(LoginApplication.CORE), credentials("f41-delegate", password)).statusCode());
                assertEquals(403, delegated.get(LoginApplication.CORE, "tenant-authority").statusCode());
                sql.execute("INSERT INTO platform.tenant_lifecycle_grants(designation_id,capability) SELECT id,'READ' FROM platform.administrators WHERE user_id='" + delegate + "' AND status='ACTIVE'");
                assertEquals(200, delegated.get(LoginApplication.CORE, "tenants").statusCode());
                assertEquals(403, delegated.post(LoginApplication.CORE, "tenants/" + tenant + "/reactivate", delegated.csrf(LoginApplication.CORE), operation(password)).statusCode());
                assertEquals(204, browser.post(LoginApplication.CORE, "logout", browser.csrf(LoginApplication.CORE), "{}").statusCode());
                assertEquals(401, browser.get(LoginApplication.CORE, "authority").statusCode());
            }
            assertEquals("t", ApplicationLoginPostgresTest.scalar(admin, "SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class WHERE oid IN ('tenant.tenants'::regclass,'tenant.memberships'::regclass,'tenant.administrators'::regclass,'tenant.initial_registrations'::regclass,'platform.tenant_lifecycle_grants'::regclass,'tenant.lifecycle_operations'::regclass,'security.session_contexts'::regclass,'audit.events'::regclass)"));
            try (var runtime = fixture.role(JdbcApplicationSessions.ROLE).getConnection(); var direct = runtime.createStatement()) {
                assertEquals("42501", assertThrows(SQLException.class, () -> direct.executeUpdate("INSERT INTO tenant.tenants(name,status) VALUES('Forbidden','PENDING')")).getSQLState());
            }
            assertNotEquals(owner, business);
        } finally { Arrays.fill(password, '\0'); }
    }

    private static String operation(char[] password) {
        return "{\"requestId\":\"" + UUID.randomUUID() + "\",\"password\":\"" + new String(password) + "\"}";
    }
    private static void insertOperation(java.sql.Connection connection, UUID actor, UUID tenant) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO tenant.lifecycle_operations(request_id,actor_user_id,tenant_id,action,previous_status,resulting_status) VALUES(?,?,?,'SUSPEND','','')")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, actor);
            statement.setObject(3, tenant);
            statement.executeUpdate();
        }
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

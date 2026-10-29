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
class TenantAdministratorsPostgresTest {
    @Test
    void administratorsWithAtomicReplacementConcurrencyAndReconciliation() throws Exception {
        var fixture = new ApplicationLoginPostgresTest();
        char[] password = "Registration-401!".toCharArray();
        try (var admin = fixture.role("postgres").getConnection(); var sql = admin.createStatement(); var operationPool = new com.zaxxer.hikari.HikariDataSource()) {
            var registrar = new JdbcManualIdentityRegistration(fixture.role(JdbcManualIdentityRegistration.DATABASE_ROLE));
            var lifecycle = new JdbcPasswordLifecycle(fixture.role(JdbcPasswordLifecycle.ISSUER_ROLE), fixture.role(JdbcPasswordLifecycle.CHANGER_ROLE), Duration.ofHours(72));
            UUID owner = definitive(registrar, lifecycle, "f42-owner", password);
            UUID business = definitive(registrar, lifecycle, "f42-business", password);
            UUID delegate = definitive(registrar, lifecycle, "f42-delegate", password);
            UUID initial = definitive(registrar, lifecycle, "f42-initial", password);
            var authentication = new JdbcPasswordAuthentication(fixture.role(JdbcPasswordAuthentication.ROLE), JdbcPasswordAuthentication.Limits.defaults());
            new JdbcPlatformBootstrap(fixture.role(JdbcPlatformBootstrap.ROLE), authentication).initialize("f42-owner", password);
            var global = new JdbcPlatformExecutor(fixture.role(JdbcPlatformExecutor.ROLE), fixture.role(JdbcPlatformExecutor.SECURITY_LOG_ROLE), authentication);
            assertEquals(JdbcPlatformExecutor.Outcome.CONFIRMED, global.execute("f42-owner", password, delegate, JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN).outcome());
            UUID fiscal = UUID.randomUUID();
            UUID pos = UUID.randomUUID();
            sql.execute("INSERT INTO catalog.applications(id,code,name,status) VALUES ('" + fiscal + "','f42-fiscal','Fiscal fixture','PUBLISHED'),('" + pos + "','f42-pos','POS fixture','PUBLISHED')");
            String core = ApplicationLoginPostgresTest.scalar(admin, "SELECT id FROM catalog.applications WHERE code='ls-core' AND status='PUBLISHED'");
            var properties = new HashMap<String, Object>();
            properties.put("ls.identity.credentials.enabled", "true");
            properties.put("ls.login.enabled", "true");
            properties.put("ls.login.multidevice.enabled", "true");
            properties.put("ls.login.contexts.enabled", "true");
            properties.put("ls.login.core.enabled", "true");
            properties.put("ls.core.registration.enabled", "true");
            properties.put("ls.core.lifecycle.enabled", "true");
            properties.put("ls.core.administrators.enabled", "true");
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
            for (var channel : Map.of("LS_TENANT_ADMIN_DB_", JdbcTenantAdministrators.ROLE, "LS_TENANT_ADMIN_LOG_DB_", JdbcTenantAdministrators.LOG_ROLE, "LS_TENANT_LIFECYCLE_DB_", JdbcTenantLifecycle.ROLE, "LS_TENANT_LIFECYCLE_LOG_DB_", JdbcTenantLifecycle.LOG_ROLE, "LS_AUTHENTICATION_DB_", JdbcPasswordAuthentication.ROLE, "LS_LOGIN_DB_", JdbcApplicationSessions.ROLE,
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
                var logged = browser.post(LoginApplication.CORE, "login", browser.csrf(LoginApplication.CORE), credentials("f42-owner", password));
                assertEquals(204, logged.statusCode());
                assertEquals(200, browser.get(LoginApplication.CORE, "tenant-authority").statusCode());
                UUID request = UUID.randomUUID();
                var created = browser.post(LoginApplication.CORE, "registrations", browser.csrf(LoginApplication.CORE), payload(request, initial, "Administrators tenant", password));
                assertEquals(200, created.statusCode());
                UUID tenant = id(created.body());
                assertEquals(200, browser.get(LoginApplication.CORE, "tenants").statusCode());
                assertEquals(200, browser.get(LoginApplication.CORE, "tenants/" + tenant).statusCode());
                String path = "tenants/" + tenant + "/administrators";
                assertEquals(200, browser.get(LoginApplication.CORE, path).statusCode());
                assertEquals(404, browser.get(LoginApplication.CORE, "tenants/" + UUID.randomUUID() + "/administrators").statusCode());
                assertEquals(403, browser.post(LoginApplication.CORE, path + "/designate", null, command(null, "f42-business", password)).statusCode());
                assertEquals(403, browser.post(LoginApplication.CORE, path + "/designate", browser.csrf(LoginApplication.CORE), command(null, "f42-business", "Wrong-password-42!".toCharArray())).statusCode());
                assertEquals(404, browser.post(LoginApplication.CORE, path + "/candidate", browser.csrf(LoginApplication.CORE), "{\"username\":\"F42-business\"}").statusCode());
                UUID pending = registrar.register(new ManualIdentityInput("f42-pending", "Prepared administrator", null));
                assertEquals(200, browser.post(LoginApplication.CORE, path + "/designate", browser.csrf(LoginApplication.CORE), command(null, "f42-pending", password)).statusCode());
                UUID prepared = designation(admin, tenant, pending);
                assertEquals("PENDING", ApplicationLoginPostgresTest.scalar(admin, "SELECT status FROM tenant.tenants WHERE id='" + tenant + "'"));
                assertEquals("PENDING", ApplicationLoginPostgresTest.scalar(admin, "SELECT status FROM tenant.administrators WHERE id='" + prepared + "'"));
                assertEquals(200, browser.post(LoginApplication.CORE, "tenants/" + tenant + "/activate", browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                UUID original = designation(admin, tenant, initial);
                assertEquals(422, browser.post(LoginApplication.CORE, path + "/" + prepared + "/activate", browser.csrf(LoginApplication.CORE), command(null, null, password)).statusCode());
                try (var issued = lifecycle.issue(pending)) {
                    char[] temporary = issued.take();
                    try {
                        lifecycle.exchange("f42-pending", temporary, password);
                    } finally {
                        Arrays.fill(temporary, '\0');
                    }
                }
                assertEquals(200, browser.post(LoginApplication.CORE, path + "/" + prepared + "/activate", browser.csrf(LoginApplication.CORE), command(null, null, password)).statusCode());
                assertEquals(200, browser.post(LoginApplication.CORE, path + "/" + prepared + "/revoke", browser.csrf(LoginApplication.CORE), command(null, null, password)).statusCode());
                assertEquals(200, browser.post(LoginApplication.CORE, path + "/designate", browser.csrf(LoginApplication.CORE), command(null, "f42-business", password)).statusCode());
                UUID secondAdmin = designation(admin, tenant, business);
                assertEquals(409, browser.post(LoginApplication.CORE, path + "/designate", browser.csrf(LoginApplication.CORE), command(null, "f42-business", password)).statusCode());
                // A nominally ACTIVE designation with a disabled identity is not a valid administrator.
                sql.execute("UPDATE identity.users SET status='SUSPENDED' WHERE id='" + business + "'");
                assertEquals(409, browser.post(LoginApplication.CORE, path + "/" + original + "/revoke", browser.csrf(LoginApplication.CORE), command(null, null, password)).statusCode());
                sql.execute("UPDATE identity.users SET status='ACTIVE' WHERE id='" + business + "'");
                sql.execute("UPDATE tenant.memberships SET status='SUSPENDED' WHERE tenant_id='" + tenant + "' AND user_id='" + business + "'");
                assertEquals(409, browser.post(LoginApplication.CORE, path + "/" + original + "/revoke", browser.csrf(LoginApplication.CORE), command(null, null, password)).statusCode());
                sql.execute("UPDATE tenant.memberships SET status='ACTIVE' WHERE tenant_id='" + tenant + "' AND user_id='" + business + "'");
                sql.execute("UPDATE identity.user_credentials SET locked_until=clock_timestamp()+interval '1 hour' WHERE user_id='" + business + "'");
                assertEquals(409, browser.post(LoginApplication.CORE, path + "/" + original + "/revoke", browser.csrf(LoginApplication.CORE), command(null, null, password)).statusCode());
                sql.execute("UPDATE identity.user_credentials SET locked_until=NULL WHERE user_id='" + business + "'");
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
                var contexts = new JdbcSessionContexts(sessionService);
                var sessionA = sessionService.login(LoginApplication.FISCAL, "f42-initial", password);
                var sessionB = sessionService.login(LoginApplication.FISCAL, "f42-initial", password);
                var contextA = contexts.select(LoginApplication.FISCAL, sessionA, tenant, UUID.fromString("41000000-0000-0000-0000-000000000001"));
                contexts.select(LoginApplication.FISCAL, sessionB, UUID.fromString(otherTenant), UUID.fromString("35000000-0000-0000-0000-000000000021"));
                assertEquals(200, browser.post(LoginApplication.CORE, path + "/" + original + "/revoke", browser.csrf(LoginApplication.CORE), command(null, null, password)).statusCode());
                assertEquals(null, contexts.current(LoginApplication.FISCAL, sessionA));
                assertEquals(UUID.fromString(otherTenant), contexts.current(LoginApplication.FISCAL, sessionB).tenantId());
                assertEquals(409, browser.post(LoginApplication.CORE, path + "/" + original + "/revoke", browser.csrf(LoginApplication.CORE), command(null, null, password)).statusCode());
                assertEquals(409, browser.post(LoginApplication.CORE, path + "/" + secondAdmin + "/revoke", browser.csrf(LoginApplication.CORE), command(null, null, password)).statusCode());
                var businessBrowser = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(204, businessBrowser.post(LoginApplication.CORE, "login", businessBrowser.csrf(LoginApplication.CORE), credentials("f42-business", password)).statusCode());
                assertEquals(200, businessBrowser.get(LoginApplication.CORE, path).statusCode());
                assertEquals(403, businessBrowser.get(LoginApplication.CORE, "tenants/" + otherTenant + "/administrators").statusCode());
                assertEquals(403, businessBrowser.post(LoginApplication.CORE, "tenants/" + otherTenant + "/administrators/designate", businessBrowser.csrf(LoginApplication.CORE), command(null, "f42-owner", password)).statusCode());
                assertEquals(403, businessBrowser.get(LoginApplication.CORE, "tenant-authority").statusCode());
                assertEquals(409, businessBrowser.post(LoginApplication.CORE, path + "/" + secondAdmin + "/revoke", businessBrowser.csrf(LoginApplication.CORE), command(null, null, password)).statusCode());
                // The initial identity has no current designation: the additional administrator suffices.
                assertEquals(200, browser.post(LoginApplication.CORE, "tenants/" + tenant + "/suspend", browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                assertEquals(200, browser.post(LoginApplication.CORE, "tenants/" + tenant + "/reactivate", browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                assertEquals(200, browser.post(LoginApplication.CORE, path + "/" + secondAdmin + "/replace", browser.csrf(LoginApplication.CORE), command(null, "f42-initial", password)).statusCode());
                assertEquals(200, browser.post(LoginApplication.CORE, "tenants/" + tenant + "/suspend", browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                assertEquals(200, browser.post(LoginApplication.CORE, "tenants/" + tenant + "/reactivate", browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                assertEquals("REVOKED", ApplicationLoginPostgresTest.scalar(admin, "SELECT status FROM tenant.administrators WHERE id='" + original + "'"));
                UUID replacement = designation(admin, tenant, initial);
                var delegated = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(204, delegated.post(LoginApplication.CORE, "login", delegated.csrf(LoginApplication.CORE), credentials("f42-delegate", password)).statusCode());
                assertEquals(403, delegated.get(LoginApplication.CORE, path).statusCode());
                sql.execute("INSERT INTO platform.tenant_administrator_grants(designation_id,capability) SELECT id,'READ' FROM platform.administrators WHERE user_id='" + delegate + "' AND status='ACTIVE'");
                assertEquals(200, delegated.get(LoginApplication.CORE, path).statusCode());
                assertEquals(403, delegated.post(LoginApplication.CORE, path + "/designate", delegated.csrf(LoginApplication.CORE), command(null, "f42-business", password)).statusCode());
                sql.execute("INSERT INTO platform.tenant_administrator_grants(designation_id,capability) SELECT id,'MANAGE' FROM platform.administrators WHERE user_id='" + delegate + "' AND status='ACTIVE'");
                sql.execute("INSERT INTO audit.events(occurred_at,actor_kind,action_code,resource_type,resource_id,outcome,correlation_id,metadata) VALUES(clock_timestamp(),'INSTALLATION_OPERATOR','TENANT_ADMINISTRATOR_CAPABILITY_INSTALLED','PLATFORM_DESIGNATION','" + delegate + "','SUCCESS',gen_random_uuid(),jsonb_build_object('capability','MANAGE'))");
                // A failed success-audit insert rolls back the designation and all associated changes.
                sql.execute("CREATE FUNCTION tenant.fixture_admin_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action_code='TENANT_ADMIN_DESIGNATE' AND NEW.outcome='SUCCESS' THEN RAISE EXCEPTION 'Owned audit fault'; END IF; RETURN NEW; END $$");
                sql.execute("CREATE TRIGGER zz_fixture_admin_fault BEFORE INSERT ON audit.events FOR EACH ROW EXECUTE FUNCTION tenant.fixture_admin_fault()");
                try { assertEquals(503, browser.post(LoginApplication.CORE, path + "/designate", browser.csrf(LoginApplication.CORE), command(null, "f42-business", password)).statusCode()); }
                finally {
                    sql.execute("DROP TRIGGER zz_fixture_admin_fault ON audit.events");
                    sql.execute("DROP FUNCTION tenant.fixture_admin_fault()");
                }
                assertEquals("0", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM tenant.administrators a JOIN tenant.memberships m ON m.id=a.membership_id WHERE a.tenant_id='" + tenant + "' AND m.user_id='" + business + "' AND a.revoked_at IS NULL"));
                // Competing physical JDBC connections: exactly one designation can commit.
                race(fixture, owner, tenant, "DESIGNATE", null, "f42-business", owner, "DESIGNATE", null, "f42-business", "P2401");
                secondAdmin = designation(admin, tenant, business);
                // Competing administrators remove one another: revoked authority cannot admit a second command.
                race(fixture, initial, tenant, "REVOKE", secondAdmin, null, business, "REVOKE", replacement, null, "42501");
                assertEquals("1", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM tenant.valid_administrators('" + tenant + "')"));
                // The driver loses the reply AFTER a real COMMIT. Reconciliation must find that exact receipt.
                var uncertainSource = uncertain(fixture.role(JdbcTenantAdministrators.ROLE));
                var administrators = new JdbcTenantAdministrators(server.getBean(JdbcApplicationSessions.class), authentication, uncertainSource, fixture.role(JdbcTenantAdministrators.LOG_ROLE));
                UUID uncertainRequest = UUID.randomUUID();
                var unknown = administrators.mutate(browser.token(LoginApplication.CORE), tenant, uncertainRequest, JdbcTenantAdministrators.Action.DESIGNATE, null, "f42-business", password);
                assertEquals("UNKNOWN", unknown.outcome());
                assertEquals(uncertainRequest, unknown.correlation());
                var receipt = browser.get(LoginApplication.CORE, "administrator-operations/" + uncertainRequest);
                assertEquals(200, receipt.statusCode());
                assertTrue(receipt.body().contains("CONFIRMED"));
                assertEquals(409, browser.post(LoginApplication.CORE, path + "/designate", browser.csrf(LoginApplication.CORE), command(uncertainRequest, "f42-business", password)).statusCode());
                assertEquals("1", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM tenant.administrator_operations WHERE request_id='" + uncertainRequest + "'"));
                var beforeFailedRevocation = contexts.select(LoginApplication.FISCAL, sessionA, tenant, UUID.fromString("41000000-0000-0000-0000-000000000001"));
                sql.execute("CREATE FUNCTION tenant.fixture_admin_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action_code='TENANT_ADMIN_REVOKE' AND NEW.outcome='SUCCESS' THEN RAISE EXCEPTION 'Owned audit fault'; END IF; RETURN NEW; END $$");
                sql.execute("CREATE TRIGGER zz_fixture_admin_fault BEFORE INSERT ON audit.events FOR EACH ROW EXECUTE FUNCTION tenant.fixture_admin_fault()");
                try { assertEquals(503, browser.post(LoginApplication.CORE, path + "/" + replacement + "/revoke", browser.csrf(LoginApplication.CORE), command(null, null, password)).statusCode()); }
                finally {
                    sql.execute("DROP TRIGGER zz_fixture_admin_fault ON audit.events");
                    sql.execute("DROP FUNCTION tenant.fixture_admin_fault()");
                }
                assertEquals(beforeFailedRevocation.id(), contexts.current(LoginApplication.FISCAL, sessionA).id());
                assertEquals("ACTIVE", ApplicationLoginPostgresTest.scalar(admin, "SELECT status FROM tenant.administrators WHERE id='" + replacement + "'"));
                secondAdmin = designation(admin, tenant, business);
                race(fixture, owner, tenant, "REVOKE", secondAdmin, null, owner, "REVOKE", replacement, null, "P2401");
                assertEquals("1", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM tenant.valid_administrators('" + tenant + "')"));
                // Reactivation waits for administrator mutations and checks their committed eligibility set.
                assertEquals(200, browser.post(LoginApplication.CORE, "tenants/" + tenant + "/suspend", browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                try (var first = fixture.role(JdbcTenantAdministrators.ROLE).getConnection(); var second = fixture.role(JdbcTenantLifecycle.ROLE).getConnection()) {
                    first.setAutoCommit(false);
                    second.setAutoCommit(false);
                    insert(first, owner, tenant, "REVOKE", replacement, null);
                    var blocked = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try (var statement = second.prepareStatement("INSERT INTO tenant.lifecycle_operations(request_id,actor_user_id,tenant_id,action,previous_status,resulting_status) VALUES(?,?,?,'REACTIVATE','','')")) {
                            statement.setObject(1, UUID.randomUUID());
                            statement.setObject(2, owner);
                            statement.setObject(3, tenant);
                            statement.executeUpdate();
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
                    assertEquals("P2302", blocked.get(5, java.util.concurrent.TimeUnit.SECONDS));
                }
                assertEquals("SUSPENDED", ApplicationLoginPostgresTest.scalar(admin, "SELECT status FROM tenant.tenants WHERE id='" + tenant + "'"));
                assertEquals(200, delegated.post(LoginApplication.CORE, path + "/designate", delegated.csrf(LoginApplication.CORE), command(null, "f42-business", password)).statusCode());
                assertEquals(200, browser.post(LoginApplication.CORE, "tenants/" + tenant + "/reactivate", browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                assertEquals("0", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM tenant.administrator_operations o LEFT JOIN audit.events e ON e.correlation_id=o.request_id AND e.outcome='SUCCESS' WHERE e.id IS NULL"));
                assertEquals(UUID.fromString(otherTenant), contexts.current(LoginApplication.FISCAL, sessionB).tenantId());
                for (var application : new LoginApplication[] {LoginApplication.FISCAL, LoginApplication.POS}) {
                    var other = new ApplicationLoginPostgresTest.Browser(client, base);
                    assertEquals(204, other.post(application, "login", other.csrf(application), credentials("f42-owner", password)).statusCode());
                    var forged = HttpRequest.newBuilder(base.resolve("/api/auth/core/" + path)).header("Cookie", LoginApplication.CORE.sessionCookie() + "=" + other.token(application)).GET().build();
                    assertEquals(401, client.send(forged, HttpResponse.BodyHandlers.ofString()).statusCode());
                }
            }
            assertEquals("t", ApplicationLoginPostgresTest.scalar(admin, "SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class WHERE oid IN ('tenant.tenants'::regclass,'tenant.memberships'::regclass,'tenant.administrators'::regclass,'tenant.administrator_operations'::regclass,'platform.tenant_administrator_grants'::regclass,'security.session_contexts'::regclass,'audit.events'::regclass)"));
            for (String role : new String[] {JdbcApplicationSessions.ROLE, JdbcTenantAdministrators.ROLE}) {
                try (var runtime = fixture.role(role).getConnection(); var direct = runtime.createStatement()) {
                    assertEquals("42501", assertThrows(SQLException.class, () -> direct.executeUpdate("UPDATE tenant.administrators SET status='REVOKED'")).getSQLState());
                    assertEquals("42501", assertThrows(SQLException.class, () -> direct.executeUpdate("UPDATE tenant.memberships SET status='ACTIVE'")).getSQLState());
                }
            }
            assertNotEquals(owner, business);
        } finally { Arrays.fill(password, '\0'); }
    }

    private static UUID designation(java.sql.Connection connection, UUID tenant, UUID user) throws Exception {
        return UUID.fromString(ApplicationLoginPostgresTest.scalar(connection, "SELECT a.id FROM tenant.administrators a JOIN tenant.memberships m ON m.id=a.membership_id AND m.tenant_id=a.tenant_id WHERE a.tenant_id='" + tenant + "' AND m.user_id='" + user + "' AND a.revoked_at IS NULL"));
    }
    private static String command(UUID request, String username, char[] password) {
        return "{\"requestId\":\"" + (request == null ? UUID.randomUUID() : request) + "\",\"username\":" + (username == null ? "null" : "\"" + username + "\"") + ",\"password\":\"" + new String(password) + "\"}";
    }
    private static void insert(java.sql.Connection connection, UUID actor, UUID tenant, String action, UUID target, String username) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO tenant.administrator_operations(request_id,actor_user_id,tenant_id,action,target_id,candidate_username,designation_id,resulting_status) VALUES(?,?,?,?,?,?,?,'')")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, actor);
            statement.setObject(3, tenant);
            statement.setString(4, action);
            statement.setObject(5, target);
            statement.setString(6, username);
            statement.setObject(7, UUID.randomUUID());
            statement.executeUpdate();
        }
    }
    private static void race(ApplicationLoginPostgresTest fixture, UUID firstActor, UUID tenant, String firstAction, UUID firstTarget, String firstUsername,
            UUID secondActor, String secondAction, UUID secondTarget, String secondUsername, String rejected) throws Exception {
        try (var first = fixture.role(JdbcTenantAdministrators.ROLE).getConnection(); var second = fixture.role(JdbcTenantAdministrators.ROLE).getConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            insert(first, firstActor, tenant, firstAction, firstTarget, firstUsername);
            var started = new java.util.concurrent.CountDownLatch(1);
            var blocked = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                started.countDown();
                try {
                    insert(second, secondActor, tenant, secondAction, secondTarget, secondUsername);
                    second.commit();
                    return "COMMITTED";
                } catch (SQLException failure) {
                    try { second.rollback(); } catch (SQLException ignored) { }
                    return failure.getSQLState();
                }
            });
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            Thread.sleep(150);
            assertTrue(!blocked.isDone());
            first.commit();
            assertEquals(rejected, blocked.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
    private static javax.sql.DataSource uncertain(javax.sql.DataSource original) {
        return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(javax.sql.DataSource.class.getClassLoader(), new Class<?>[] {javax.sql.DataSource.class}, (proxy, method, arguments) -> {
            try {
                Object value = method.invoke(original, arguments);
                if (value instanceof java.sql.Connection connection) {
                    return java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(), new Class<?>[] {java.sql.Connection.class}, (nested, call, parameters) -> {
                        try {
                            Object result = call.invoke(connection, parameters);
                            if ("commit".equals(call.getName())) { throw new SQLException("Owned lost commit reply", "08006"); }
                            return result;
                        } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    });
                }
                return value;
            } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        });
    }
    private static String operation(char[] password) {
        return "{\"requestId\":\"" + UUID.randomUUID() + "\",\"password\":\"" + new String(password) + "\"}";
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

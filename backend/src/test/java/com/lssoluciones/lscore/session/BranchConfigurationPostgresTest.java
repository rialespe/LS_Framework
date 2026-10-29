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
class BranchConfigurationPostgresTest {
    @Test
    void progressiveConfigurationWithStrictContractsAndConcurrentAuthority() throws Exception {
        var fixture = new ApplicationLoginPostgresTest();
        char[] password = "Registration-401!".toCharArray();
        try (var admin = fixture.role("postgres").getConnection(); var sql = admin.createStatement(); var operationPool = new com.zaxxer.hikari.HikariDataSource()) {
            var registrar = new JdbcManualIdentityRegistration(fixture.role(JdbcManualIdentityRegistration.DATABASE_ROLE));
            var lifecycle = new JdbcPasswordLifecycle(fixture.role(JdbcPasswordLifecycle.ISSUER_ROLE), fixture.role(JdbcPasswordLifecycle.CHANGER_ROLE), Duration.ofHours(72));
            UUID owner = definitive(registrar, lifecycle, "f44-owner", password);
            UUID business = definitive(registrar, lifecycle, "f44-business", password);
            UUID delegate = definitive(registrar, lifecycle, "f44-delegate", password);
            UUID initial = definitive(registrar, lifecycle, "f44-initial", password);
            var authentication = new JdbcPasswordAuthentication(fixture.role(JdbcPasswordAuthentication.ROLE), JdbcPasswordAuthentication.Limits.defaults());
            new JdbcPlatformBootstrap(fixture.role(JdbcPlatformBootstrap.ROLE), authentication).initialize("f44-owner", password);
            var global = new JdbcPlatformExecutor(fixture.role(JdbcPlatformExecutor.ROLE), fixture.role(JdbcPlatformExecutor.SECURITY_LOG_ROLE), authentication);
            assertEquals(JdbcPlatformExecutor.Outcome.CONFIRMED, global.execute("f44-owner", password, delegate, JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN).outcome());
            UUID fiscal = UUID.randomUUID();
            UUID pos = UUID.randomUUID();
            sql.execute("INSERT INTO catalog.applications(id,code,name,status) VALUES ('" + fiscal + "','f44-fiscal','Fiscal fixture','PUBLISHED'),('" + pos + "','f44-pos','POS fixture','PUBLISHED')");
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
            properties.put("ls.core.branches.enabled", "true");
            properties.put("ls.core.branch-configuration.enabled", "true");
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
            for (var channel : Map.ofEntries(Map.entry("LS_BRANCH_ADMIN_DB_", JdbcBranchAdministration.ROLE),
                    Map.entry("LS_BRANCH_ADMIN_LOG_DB_", JdbcBranchAdministration.LOG_ROLE),
                    Map.entry("LS_TENANT_ADMIN_DB_", JdbcTenantAdministrators.ROLE),
                    Map.entry("LS_TENANT_ADMIN_LOG_DB_", JdbcTenantAdministrators.LOG_ROLE),
                    Map.entry("LS_TENANT_LIFECYCLE_DB_", JdbcTenantLifecycle.ROLE),
                    Map.entry("LS_TENANT_LIFECYCLE_LOG_DB_", JdbcTenantLifecycle.LOG_ROLE),
                    Map.entry("LS_AUTHENTICATION_DB_", JdbcPasswordAuthentication.ROLE),
                    Map.entry("LS_LOGIN_DB_", JdbcApplicationSessions.ROLE),
                    Map.entry("LS_TENANT_REGISTRATION_DB_", JdbcTenantRegistration.ROLE),
                    Map.entry("LS_TENANT_REGISTRATION_LOG_DB_", JdbcTenantRegistration.LOG_ROLE),
                    Map.entry("LS_BRANCH_CONFIG_DB_", JdbcBranchConfiguration.ROLE),
                    Map.entry("LS_BRANCH_CONFIG_LOG_DB_", JdbcBranchConfiguration.LOG_ROLE)).entrySet()) {
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
                var logged = browser.post(LoginApplication.CORE, "login", browser.csrf(LoginApplication.CORE), credentials("f44-owner", password));
                assertEquals(204, logged.statusCode());
                assertEquals(200, browser.get(LoginApplication.CORE, "tenant-authority").statusCode());
                UUID request = UUID.randomUUID();
                var created = browser.post(LoginApplication.CORE, "registrations", browser.csrf(LoginApplication.CORE), payload(request, initial, "Branch tenant", password));
                assertEquals(200, created.statusCode());
                UUID tenant = id(created.body());
                assertEquals(200, browser.get(LoginApplication.CORE, "tenants").statusCode());
                assertEquals(200, browser.get(LoginApplication.CORE, "tenants/" + tenant).statusCode());
                assertEquals(200, browser.post(LoginApplication.CORE, "tenants/" + tenant + "/activate", browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                String branches = "branch-administration/tenants/" + tenant + "/branches";
                var createdBranch = browser.post(LoginApplication.CORE, branches, browser.csrf(LoginApplication.CORE), branchPayload("CASA MATRIZ", password));
                assertEquals(201, createdBranch.statusCode());
                UUID branch = id(createdBranch.body());
                String modules = branches + "/" + branch + "/module-configurations";
                assertEquals("[]", browser.get(LoginApplication.CORE, modules).body());
                assertEquals("0", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM tenant.branch_configurations"));
                assertEquals("3", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM catalog.modules m JOIN catalog.applications a ON a.id=m.application_id WHERE a.code='ls-core'"));
                sql.execute(java.nio.file.Files.readString(Path.of("../scripts/pilot/branch_configuration_fixture.sql")).replace("__TENANT__", tenant.toString()));
                UUID app = UUID.fromString(ApplicationLoginPostgresTest.scalar(admin, "SELECT id FROM catalog.applications WHERE code='fixture-config-lfd44'"));
                UUID module = UUID.fromString(ApplicationLoginPostgresTest.scalar(admin, "SELECT id FROM catalog.modules WHERE application_id='" + app + "' AND code='fixture-settings'"));
                var scope = new JdbcBranchConfiguration.Scope(tenant, branch, app, module, 1);
                String path = modules + "/" + app + "/" + module + "/1";
                assertTrue(browser.get(LoginApplication.CORE, modules).body().contains("ABSENT"));
                assertTrue(browser.get(LoginApplication.CORE, path).body().contains("\"configuration\":null"));
                assertEquals(403, browser.post(LoginApplication.CORE, path + "/prepare", null, command(null, null, "{}", password)).statusCode());
                assertEquals(403, browser.post(LoginApplication.CORE, path + "/prepare", browser.csrf(LoginApplication.CORE), command(null, null, "{}", "Wrong-configuration-44!".toCharArray())).statusCode());
                assertEquals(200, browser.post(LoginApplication.CORE, path + "/prepare", browser.csrf(LoginApplication.CORE), command(null, null, "{}", password)).statusCode());
                assertEquals(409, browser.post(LoginApplication.CORE, path + "/prepare", browser.csrf(LoginApplication.CORE), command(null, null, "{}", password)).statusCode());
                var incomplete = browser.post(LoginApplication.CORE, path + "/validate", browser.csrf(LoginApplication.CORE), command(null, 1L, "{}", password));
                assertEquals(422, incomplete.statusCode());
                assertTrue(incomplete.body().contains("capacity") && incomplete.body().contains("label"));
                for (String invalid : new String[] {"{\"unknown\":true}", "{\"capacity\":\"2\"}", "{\"label\":{}}", "{\"capacity\":11}", "{\"verified\":null}"}) {
                    assertEquals(422, browser.post(LoginApplication.CORE, path + "/update", browser.csrf(LoginApplication.CORE), command(null, 1L, invalid, password)).statusCode());
                }
                String valid = "{\"label\":\"Disposable fixture\",\"capacity\":2,\"verified\":false}";
                assertEquals(200, browser.post(LoginApplication.CORE, path + "/update", browser.csrf(LoginApplication.CORE), command(null, 1L, valid, password)).statusCode());
                assertEquals(200, browser.post(LoginApplication.CORE, path + "/validate", browser.csrf(LoginApplication.CORE), command(null, 2L, "{}", password)).statusCode());
                assertTrue(browser.get(LoginApplication.CORE, path).body().contains("READY"));
                assertEquals(409, browser.post(LoginApplication.CORE, path + "/update", browser.csrf(LoginApplication.CORE), command(null, 1L, valid, password)).statusCode());
                var businessBrowser = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(204, businessBrowser.post(LoginApplication.CORE, "login", businessBrowser.csrf(LoginApplication.CORE), credentials("f44-initial", password)).statusCode());
                assertEquals(403, businessBrowser.get(LoginApplication.CORE, path).statusCode());
                sql.execute("INSERT INTO tenant.branch_configuration_grants(tenant_id,designation_id,application_id,module_id,capability) SELECT a.tenant_id,a.id,'" + app + "','" + module + "',c FROM tenant.administrators a JOIN tenant.memberships m ON m.id=a.membership_id CROSS JOIN (VALUES('READ'),('MANAGE')) caps(c) WHERE a.tenant_id='" + tenant + "' AND m.user_id='" + initial + "' AND a.status='ACTIVE'");
                assertEquals(200, businessBrowser.get(LoginApplication.CORE, path).statusCode());
                assertEquals(200, businessBrowser.post(LoginApplication.CORE, path + "/update", businessBrowser.csrf(LoginApplication.CORE), command(null, 3L, valid, password)).statusCode());
                assertEquals("DRAFT", ApplicationLoginPostgresTest.scalar(admin, "SELECT readiness FROM tenant.branch_configurations WHERE tenant_id='" + tenant + "'"));
                // Another company has no entitlement or configuration. Identity and composite scope are independent.
                var createdB = browser.post(LoginApplication.CORE, "registrations", browser.csrf(LoginApplication.CORE), payload(UUID.randomUUID(), business, "Configuration B", password));
                UUID companyB = id(createdB.body());
                assertEquals(200, browser.post(LoginApplication.CORE, "tenants/" + companyB + "/activate", browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                var branchBResponse = browser.post(LoginApplication.CORE, "branch-administration/tenants/" + companyB + "/branches", browser.csrf(LoginApplication.CORE), branchPayload("CASA MATRIZ", password));
                UUID branchB = id(branchBResponse.body());
                String pathB = "branch-administration/tenants/" + companyB + "/branches/" + branchB + "/module-configurations/" + app + "/" + module + "/1";
                assertEquals(409, browser.post(LoginApplication.CORE, pathB + "/prepare", browser.csrf(LoginApplication.CORE), command(null, null, valid, password)).statusCode());
                assertEquals(403, businessBrowser.get(LoginApplication.CORE, pathB).statusCode());
                assertEquals(404, browser.get(LoginApplication.CORE, branches + "/" + branchB + "/module-configurations").statusCode());
                assertEquals(404, browser.get(LoginApplication.CORE, modules + "/" + fiscal + "/" + module + "/1").statusCode());
                assertEquals("0", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM tenant.branch_configurations WHERE tenant_id='" + companyB + "'"));
                var delegated = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(204, delegated.post(LoginApplication.CORE, "login", delegated.csrf(LoginApplication.CORE), credentials("f44-delegate", password)).statusCode());
                sql.execute("INSERT INTO platform.branch_administration_grants(designation_id,capability) SELECT id,c FROM platform.administrators CROSS JOIN (VALUES('READ'),('MANAGE')) caps(c) WHERE user_id='" + delegate + "' AND status='ACTIVE'");
                assertEquals(403, delegated.post(LoginApplication.CORE, path + "/update", delegated.csrf(LoginApplication.CORE), command(null, 4L, valid, password)).statusCode());
                sql.execute("INSERT INTO platform.branch_configuration_grants(designation_id,application_id,module_id,capability) SELECT id,'" + app + "','" + module + "',c FROM platform.administrators CROSS JOIN (VALUES('READ'),('MANAGE')) caps(c) WHERE user_id='" + delegate + "' AND status='ACTIVE'");
                assertEquals(200, delegated.get(LoginApplication.CORE, path).statusCode());
                // A failure of success evidence rolls back values, revision and preparation together.
                sql.execute("CREATE FUNCTION tenant.fixture_config_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action_code='BRANCH_CONFIG_VALIDATE' AND NEW.outcome='SUCCESS' THEN RAISE EXCEPTION 'Owned audit fault'; END IF; RETURN NEW; END $$");
                sql.execute("CREATE TRIGGER zz_fixture_config_fault BEFORE INSERT ON audit.events FOR EACH ROW EXECUTE FUNCTION tenant.fixture_config_fault()");
                try { assertEquals(503, browser.post(LoginApplication.CORE, path + "/validate", browser.csrf(LoginApplication.CORE), command(null, 4L, "{}", password)).statusCode()); }
                finally {
                    sql.execute("DROP TRIGGER zz_fixture_config_fault ON audit.events");
                    sql.execute("DROP FUNCTION tenant.fixture_config_fault()");
                }
                assertEquals(4L, revision(admin, scope));
                try (var direct = fixture.role(JdbcBranchConfiguration.ROLE).getConnection()) {
                    direct.setAutoCommit(false);
                    for (String invalid : new String[] {"{\"capacity\":\"2\"}", "{\"label\":{}}", "{\"extra\":true}", "{\"capacity\":11}"}) {
                        assertEquals("P2602", assertThrows(SQLException.class, () -> insert(direct, owner, scope, "UPDATE", 4L, invalid)).getSQLState());
                        direct.rollback();
                    }
                    var crossed = new JdbcBranchConfiguration.Scope(tenant, branchB, app, module, 1);
                    assertEquals("P2604", assertThrows(SQLException.class, () -> insert(direct, owner, crossed, "PREPARE", null, valid)).getSQLState());
                    direct.rollback();
                }
                try (var first = fixture.role(JdbcBranchConfiguration.ROLE).getConnection()) {
                    first.setAutoCommit(false);
                    insert(first, owner, scope, "UPDATE", 4L, valid);
                    race(fixture, first, owner, scope, "UPDATE", 4L, valid, "P2601");
                }
                UUID anotherBranch = id(browser.post(LoginApplication.CORE, branches, browser.csrf(LoginApplication.CORE), branchPayload("SECOND", password)).body());
                var anotherScope = new JdbcBranchConfiguration.Scope(tenant, anotherBranch, app, module, 1);
                try (var first = fixture.role(JdbcBranchConfiguration.ROLE).getConnection()) {
                    first.setAutoCommit(false);
                    insert(first, owner, anotherScope, "PREPARE", null, valid);
                    race(fixture, first, owner, anotherScope, "PREPARE", null, valid, "P2601");
                }
                // State/catalog/grant changes queue on the protocol BEFORE taking rows.
                try (var first = fixture.role("postgres").getConnection(); var update = first.createStatement()) {
                    first.setAutoCommit(false);
                    update.execute("UPDATE catalog.tenant_modules SET status='DISABLED' WHERE tenant_id='" + tenant + "' AND module_id='" + module + "'");
                    race(fixture, first, owner, scope, "UPDATE", 5L, valid, "P2603");
                }
                assertTrue(browser.get(LoginApplication.CORE, path).body().contains("\"moduleEnabled\":false"));
                sql.execute("UPDATE catalog.tenant_modules SET status='ACTIVE' WHERE tenant_id='" + tenant + "' AND module_id='" + module + "'");
                try (var first = fixture.role("postgres").getConnection(); var update = first.createStatement()) {
                    first.setAutoCommit(false);
                    update.execute("DELETE FROM platform.branch_configuration_grants WHERE application_id='" + app + "' AND module_id='" + module + "' AND capability='MANAGE'");
                    race(fixture, first, delegate, scope, "UPDATE", 5L, valid, "42501");
                }
                try (var first = fixture.role(JdbcTenantLifecycle.ROLE).getConnection(); var update = first.prepareStatement("INSERT INTO tenant.lifecycle_operations(request_id,actor_user_id,tenant_id,action,previous_status,resulting_status) VALUES(?,?,?,'SUSPEND','','')")) {
                    first.setAutoCommit(false);
                    update.setObject(1, UUID.randomUUID());
                    update.setObject(2, owner);
                    update.setObject(3, tenant);
                    update.executeUpdate();
                    race(fixture, first, owner, scope, "UPDATE", 5L, valid, "P2601");
                }
                assertEquals(200, browser.post(LoginApplication.CORE, "tenants/" + tenant + "/reactivate", browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                var uncertainService = new JdbcBranchConfiguration(server.getBean(JdbcApplicationSessions.class), authentication, uncertain(fixture.role(JdbcBranchConfiguration.ROLE)), fixture.role(JdbcBranchConfiguration.LOG_ROLE));
                UUID uncertainRequest = UUID.randomUUID();
                assertEquals("UNKNOWN", uncertainService.mutate(browser.token(LoginApplication.CORE), scope, uncertainRequest, JdbcBranchConfiguration.Action.UPDATE, 5L, tools.jackson.databind.json.JsonMapper.builder().build().readTree(valid), password).outcome());
                assertTrue(browser.get(LoginApplication.CORE, "branch-administration/configuration-operations/" + uncertainRequest).body().contains("CONFIRMED"));
                assertEquals(409, browser.post(LoginApplication.CORE, path + "/update", browser.csrf(LoginApplication.CORE), command(uncertainRequest, 6L, valid, password)).statusCode());
                assertEquals(6L, revision(admin, scope));
                try (var first = fixture.role(JdbcBranchConfiguration.ROLE).getConnection()) {
                    first.setAutoCommit(false);
                    var missingScope = new JdbcBranchConfiguration.Scope(tenant, branch, app, module, 2);
                    assertEquals("P2603", assertThrows(SQLException.class, () -> insert(first, owner, missingScope, "PREPARE", null, valid)).getSQLState());
                    first.rollback();
                }
                // Suspension preserves values; CLOSED remains readable but rejects further writes.
                assertEquals(200, browser.post(LoginApplication.CORE, branches + "/" + branch + "/suspend", browser.csrf(LoginApplication.CORE), branchTransition(admin, branch, password)).statusCode());
                assertEquals(6L, revision(admin, scope));
                assertEquals(409, browser.post(LoginApplication.CORE, path + "/update", browser.csrf(LoginApplication.CORE), command(null, 6L, valid, password)).statusCode());
                assertEquals(200, browser.post(LoginApplication.CORE, branches + "/" + branch + "/reactivate", browser.csrf(LoginApplication.CORE), branchTransition(admin, branch, password)).statusCode());
                try (var first = fixture.role(JdbcBranchAdministration.ROLE).getConnection(); var update = first.prepareStatement("INSERT INTO tenant.branch_operations(request_id,actor_user_id,tenant_id,branch_id,action,expected_updated_at,code,name,resulting_status,branch_created_at) VALUES(?,?,?,?,'CLOSE',?,'','','',clock_timestamp())")) {
                    first.setAutoCommit(false);
                    update.setObject(1, UUID.randomUUID());
                    update.setObject(2, owner);
                    update.setObject(3, tenant);
                    update.setObject(4, branch);
                    update.setTimestamp(5, stamp(admin, branch));
                    update.executeUpdate();
                    race(fixture, first, owner, scope, "UPDATE", 6L, valid, "P2601");
                }
                assertEquals(200, browser.get(LoginApplication.CORE, path).statusCode());
                assertEquals(409, browser.post(LoginApplication.CORE, path + "/validate", browser.csrf(LoginApplication.CORE), command(null, 6L, "{}", password)).statusCode());
                assertEquals("0", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM tenant.branch_configuration_operations o LEFT JOIN audit.events e ON e.correlation_id=o.request_id AND e.outcome='SUCCESS' WHERE e.id IS NULL"));
                assertEquals("0", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM audit.events WHERE action_code LIKE 'BRANCH_CONFIG_%' AND (metadata ? 'values' OR metadata ? 'password')"));
                for (var application : new LoginApplication[] {LoginApplication.FISCAL, LoginApplication.POS}) {
                    var other = new ApplicationLoginPostgresTest.Browser(client, base);
                    assertEquals(204, other.post(application, "login", other.csrf(application), credentials("f44-owner", password)).statusCode());
                    var forged = HttpRequest.newBuilder(base.resolve("/api/auth/core/" + path)).header("Cookie", LoginApplication.CORE.sessionCookie() + "=" + other.token(application)).GET().build();
                    assertEquals(401, client.send(forged, HttpResponse.BodyHandlers.ofString()).statusCode());
                }
            }
            assertEquals("t", ApplicationLoginPostgresTest.scalar(admin, "SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class WHERE oid IN ('tenant.branch_configurations'::regclass,'tenant.branch_configuration_operations'::regclass,'catalog.branch_configuration_definitions'::regclass,'catalog.branch_configuration_fields'::regclass,'tenant.branch_configuration_grants'::regclass,'platform.branch_configuration_grants'::regclass)"));
            try (var runtime = fixture.role(JdbcBranchConfiguration.ROLE).getConnection(); var direct = runtime.createStatement()) {
                assertEquals("42501", assertThrows(SQLException.class, () -> direct.executeUpdate("UPDATE tenant.branch_configurations SET readiness='READY'")).getSQLState());
                assertEquals("42501", assertThrows(SQLException.class, () -> direct.executeUpdate("INSERT INTO tenant.branch_configurations VALUES(gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),1,'{}','READY',1,clock_timestamp(),gen_random_uuid(),gen_random_uuid())")).getSQLState());
                assertEquals("42501", assertThrows(SQLException.class, () -> direct.executeUpdate("UPDATE catalog.modules SET id=id")).getSQLState());
            }
            assertNotEquals(owner, business);
        } finally { Arrays.fill(password, '\0'); }
    }
    private static String branchPayload(String code, char[] password) { return "{\"requestId\":\"" + UUID.randomUUID() + "\",\"code\":\"" + code + "\",\"name\":\"General only\",\"password\":\"" + new String(password) + "\"}"; }
    private static String command(UUID request, Long revision, String values, char[] password) { return "{\"requestId\":\"" + (request == null ? UUID.randomUUID() : request) + "\",\"expectedRevision\":" + revision + ",\"values\":" + values + ",\"password\":\"" + new String(password) + "\"}"; }
    private static java.sql.Timestamp stamp(java.sql.Connection connection, UUID branch) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT updated_at FROM tenant.branches WHERE id=?")) {
            statement.setObject(1, branch);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getTimestamp(1);
            }
        }
    }
    private static String branchTransition(java.sql.Connection connection, UUID branch, char[] password) throws SQLException { return "{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedUpdatedAt\":\"" + stamp(connection, branch).toInstant() + "\",\"password\":\"" + new String(password) + "\"}"; }
    private static long revision(java.sql.Connection connection, JdbcBranchConfiguration.Scope scope) throws Exception { return Long.parseLong(ApplicationLoginPostgresTest.scalar(connection, "SELECT revision FROM tenant.branch_configurations WHERE tenant_id='" + scope.tenantId() + "' AND branch_id='" + scope.branchId() + "'")); }
    private static void insert(java.sql.Connection connection, UUID actor, JdbcBranchConfiguration.Scope scope, String action, Long revision, String values) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO tenant.branch_configuration_operations(request_id,actor_user_id,tenant_id,branch_id,application_id,module_id,definition_version,action,expected_revision,values_json,readiness,revision) VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,'DRAFT',1)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, actor);
            statement.setObject(3, scope.tenantId());
            statement.setObject(4, scope.branchId());
            statement.setObject(5, scope.applicationId());
            statement.setObject(6, scope.moduleId());
            statement.setInt(7, scope.version());
            statement.setString(8, action);
            statement.setObject(9, revision);
            statement.setString(10, values);
            statement.executeUpdate();
        }
    }
    private static void race(ApplicationLoginPostgresTest fixture, java.sql.Connection first, UUID actor, JdbcBranchConfiguration.Scope scope, String action, Long revision, String values, String rejected) throws Exception {
        try (var second = fixture.role(JdbcBranchConfiguration.ROLE).getConnection()) {
            second.setAutoCommit(false);
            assertNotEquals(ApplicationLoginPostgresTest.scalar(first, "SELECT pg_backend_pid()"), ApplicationLoginPostgresTest.scalar(second, "SELECT pg_backend_pid()"));
            var started = new java.util.concurrent.CountDownLatch(1);
            var blocked = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                started.countDown();
                try {
                    insert(second, actor, scope, action, revision, values);
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

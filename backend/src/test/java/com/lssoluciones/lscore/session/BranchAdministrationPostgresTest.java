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
class BranchAdministrationPostgresTest {
    @Test
    void branchesWithAtomicAuditIsolationAndConcurrentVersions() throws Exception {
        var fixture = new ApplicationLoginPostgresTest();
        char[] password = "Registration-401!".toCharArray();
        try (var admin = fixture.role("postgres").getConnection(); var sql = admin.createStatement(); var operationPool = new com.zaxxer.hikari.HikariDataSource()) {
            var registrar = new JdbcManualIdentityRegistration(fixture.role(JdbcManualIdentityRegistration.DATABASE_ROLE));
            var lifecycle = new JdbcPasswordLifecycle(fixture.role(JdbcPasswordLifecycle.ISSUER_ROLE), fixture.role(JdbcPasswordLifecycle.CHANGER_ROLE), Duration.ofHours(72));
            UUID owner = definitive(registrar, lifecycle, "f43-owner", password);
            UUID business = definitive(registrar, lifecycle, "f43-business", password);
            UUID delegate = definitive(registrar, lifecycle, "f43-delegate", password);
            UUID initial = definitive(registrar, lifecycle, "f43-initial", password);
            var authentication = new JdbcPasswordAuthentication(fixture.role(JdbcPasswordAuthentication.ROLE), JdbcPasswordAuthentication.Limits.defaults());
            new JdbcPlatformBootstrap(fixture.role(JdbcPlatformBootstrap.ROLE), authentication).initialize("f43-owner", password);
            var global = new JdbcPlatformExecutor(fixture.role(JdbcPlatformExecutor.ROLE), fixture.role(JdbcPlatformExecutor.SECURITY_LOG_ROLE), authentication);
            assertEquals(JdbcPlatformExecutor.Outcome.CONFIRMED, global.execute("f43-owner", password, delegate, JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN).outcome());
            UUID fiscal = UUID.randomUUID();
            UUID pos = UUID.randomUUID();
            sql.execute("INSERT INTO catalog.applications(id,code,name,status) VALUES ('" + fiscal + "','f43-fiscal','Fiscal fixture','PUBLISHED'),('" + pos + "','f43-pos','POS fixture','PUBLISHED')");
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
            for (var channel : Map.of("LS_BRANCH_ADMIN_DB_", JdbcBranchAdministration.ROLE, "LS_BRANCH_ADMIN_LOG_DB_", JdbcBranchAdministration.LOG_ROLE, "LS_TENANT_ADMIN_DB_", JdbcTenantAdministrators.ROLE, "LS_TENANT_ADMIN_LOG_DB_", JdbcTenantAdministrators.LOG_ROLE, "LS_TENANT_LIFECYCLE_DB_", JdbcTenantLifecycle.ROLE, "LS_TENANT_LIFECYCLE_LOG_DB_", JdbcTenantLifecycle.LOG_ROLE, "LS_AUTHENTICATION_DB_", JdbcPasswordAuthentication.ROLE, "LS_LOGIN_DB_", JdbcApplicationSessions.ROLE,
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
                var logged = browser.post(LoginApplication.CORE, "login", browser.csrf(LoginApplication.CORE), credentials("f43-owner", password));
                assertEquals(204, logged.statusCode());
                assertEquals(200, browser.get(LoginApplication.CORE, "tenant-authority").statusCode());
                UUID request = UUID.randomUUID();
                var created = browser.post(LoginApplication.CORE, "registrations", browser.csrf(LoginApplication.CORE), payload(request, initial, "Branch tenant", password));
                assertEquals(200, created.statusCode());
                UUID tenant = id(created.body());
                assertEquals(200, browser.get(LoginApplication.CORE, "tenants").statusCode());
                assertEquals(200, browser.get(LoginApplication.CORE, "tenants/" + tenant).statusCode());
                String path = "branch-administration/tenants/" + tenant + "/branches";
                assertEquals(200, browser.get(LoginApplication.CORE, "branch-administration/tenants").statusCode());
                assertEquals(404, browser.get(LoginApplication.CORE, "branch-administration/tenants/" + UUID.randomUUID() + "/branches").statusCode());
                assertEquals(403, browser.post(LoginApplication.CORE, path, null, branchCommand(null, "CASA MATRIZ", "Matriz", null, password)).statusCode());
                assertEquals(403, browser.post(LoginApplication.CORE, path, browser.csrf(LoginApplication.CORE), branchCommand(null, "CASA MATRIZ", "Matriz", null, "Wrong-branch-43!".toCharArray())).statusCode());
                assertEquals(422, browser.post(LoginApplication.CORE, path, browser.csrf(LoginApplication.CORE), branchCommand(null, "   ", "Matriz", null, password)).statusCode());
                assertEquals(422, browser.post(LoginApplication.CORE, path, browser.csrf(LoginApplication.CORE), branchCommand(null, "BAD/CODE", "Matriz", null, password)).statusCode());
                var matrixCreated = browser.post(LoginApplication.CORE, path, browser.csrf(LoginApplication.CORE), branchCommand(null, "CASA MATRIZ", "Casa Matriz", null, password));
                assertEquals(201, matrixCreated.statusCode());
                UUID matrix = id(matrixCreated.body());
                assertEquals("PENDING", ApplicationLoginPostgresTest.scalar(admin, "SELECT status FROM tenant.tenants WHERE id='" + tenant + "'"));
                var tenantBrowser = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(204, tenantBrowser.post(LoginApplication.CORE, "login", tenantBrowser.csrf(LoginApplication.CORE), credentials("f43-initial", password)).statusCode());
                assertEquals(403, tenantBrowser.get(LoginApplication.CORE, path).statusCode());
                assertEquals(200, browser.post(LoginApplication.CORE, "tenants/" + tenant + "/activate", browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                assertEquals(200, tenantBrowser.get(LoginApplication.CORE, path).statusCode());
                assertEquals(403, tenantBrowser.get(LoginApplication.CORE, "tenant-authority").statusCode());
                var createdSecond = tenantBrowser.post(LoginApplication.CORE, path, tenantBrowser.csrf(LoginApplication.CORE), branchCommand(null, "SUCURSAL 02", "Sucursal dos", null, password));
                assertEquals(201, createdSecond.statusCode());
                UUID branch = id(createdSecond.body());
                assertEquals(200, browser.get(LoginApplication.CORE, path + "/" + branch).statusCode());
                assertEquals(200, browser.post(LoginApplication.CORE, path + "/" + branch + "/edit", browser.csrf(LoginApplication.CORE), branchCommand(null, "SUC02", "Sucursal dos editada", version(admin, branch), password)).statusCode());
                assertEquals("SUC02", ApplicationLoginPostgresTest.scalar(admin, "SELECT code FROM tenant.branches WHERE id='" + branch + "'"));
                assertEquals(409, browser.post(LoginApplication.CORE, path, browser.csrf(LoginApplication.CORE), branchCommand(null, " casa matriz ", "Duplicada normalizada", null, password)).statusCode());
                // A second company may use the same normalized code without sharing any rows or authority.
                var registeredB = browser.post(LoginApplication.CORE, "registrations", browser.csrf(LoginApplication.CORE), payload(UUID.randomUUID(), business, "Company B", password));
                assertEquals(200, registeredB.statusCode());
                UUID companyB = id(registeredB.body());
                String pathB = "branch-administration/tenants/" + companyB + "/branches";
                var createdB = browser.post(LoginApplication.CORE, pathB, browser.csrf(LoginApplication.CORE), branchCommand(null, "casa matriz", "Matriz B", null, password));
                assertEquals(201, createdB.statusCode());
                UUID branchB = id(createdB.body());
                assertEquals(200, browser.post(LoginApplication.CORE, "tenants/" + companyB + "/activate", browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                assertEquals(403, tenantBrowser.get(LoginApplication.CORE, pathB).statusCode());
                assertEquals(403, tenantBrowser.post(LoginApplication.CORE, pathB, tenantBrowser.csrf(LoginApplication.CORE), branchCommand(null, "FORGED", "Foreign company", null, password)).statusCode());
                assertEquals(404, tenantBrowser.get(LoginApplication.CORE, path + "/" + branchB).statusCode());
                // Administrative support remains global when a company is suspended.
                var businessBrowser = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(204, businessBrowser.post(LoginApplication.CORE, "login", businessBrowser.csrf(LoginApplication.CORE), credentials("f43-business", password)).statusCode());
                assertEquals(200, businessBrowser.get(LoginApplication.CORE, pathB).statusCode());
                assertEquals(200, browser.post(LoginApplication.CORE, "tenants/" + companyB + "/suspend", browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                assertEquals(403, businessBrowser.post(LoginApplication.CORE, pathB, businessBrowser.csrf(LoginApplication.CORE), branchCommand(null, "NOT-ALLOWED", "Suspended company", null, password)).statusCode());
                assertEquals(201, browser.post(LoginApplication.CORE, pathB, browser.csrf(LoginApplication.CORE), branchCommand(null, "SUPPORT", "Explicit global support", null, password)).statusCode());
                assertEquals(200, browser.post(LoginApplication.CORE, "tenants/" + companyB + "/reactivate", browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                // Own test-only branch memberships and application grants; the API never creates these defaults.
                sql.execute(java.nio.file.Files.readString(Path.of("src/test/resources/session-context-fixture.sql")).replace("__USER__", initial.toString()));
                String membership = ApplicationLoginPostgresTest.scalar(admin, "SELECT membership_id FROM tenant.initial_registrations WHERE tenant_id='" + tenant + "'");
                for (UUID selected : new UUID[] {matrix, branch}) {
                    sql.execute("INSERT INTO tenant.branch_memberships(tenant_id,membership_id,branch_id,status) VALUES('" + tenant + "','" + membership + "','" + selected + "','ACTIVE')");
                }
                sql.execute("INSERT INTO catalog.tenant_applications(tenant_id,application_id,status) VALUES('" + tenant + "','35000000-0000-0000-0000-000000000001','ACTIVE')");
                var sessionService = new JdbcApplicationSessions(fixture.role(JdbcApplicationSessions.ROLE), authentication,
                        Map.of(LoginApplication.FISCAL, UUID.fromString("35000000-0000-0000-0000-000000000001"), LoginApplication.POS, UUID.fromString("35000000-0000-0000-0000-000000000002")), Duration.ofHours(1), Map.of(LoginApplication.FISCAL, Duration.ofMinutes(30), LoginApplication.POS, Duration.ofMinutes(30)));
                var contexts = new JdbcSessionContexts(sessionService);
                var sessionMatrix = sessionService.login(LoginApplication.FISCAL, "f43-initial", password);
                var sessionBranch = sessionService.login(LoginApplication.FISCAL, "f43-initial", password);
                var sessionOther = sessionService.login(LoginApplication.FISCAL, "f43-initial", password);
                var contextMatrix = contexts.select(LoginApplication.FISCAL, sessionMatrix, tenant, matrix);
                var contextBranch = contexts.select(LoginApplication.FISCAL, sessionBranch, tenant, branch);
                var otherTenant = UUID.fromString("35000000-0000-0000-0000-000000000020");
                var contextOther = contexts.select(LoginApplication.FISCAL, sessionOther, otherTenant, UUID.fromString("35000000-0000-0000-0000-000000000021"));
                operationPool.setDataSource(fixture.role("ls_core_branch_operation_dev"));
                operationPool.setReadOnly(true);
                operationPool.setAutoCommit(true);
                operationPool.setMaximumPoolSize(1);
                operationPool.setMinimumIdle(0);
                var authorization = new com.lssoluciones.lscore.access.infrastructure.jdbc.JdbcTenantAuthorization(fixture.role("ls_core_authorization_reader_dev"), authentication);
                var coordinator = new com.lssoluciones.lscore.access.infrastructure.jdbc.BranchReadCoordinator(authorization, operationPool, fixture.role("ls_core_operation_emitter_dev"));
                var scope = new com.lssoluciones.lscore.access.application.context.TrustedContextSource.Scope(tenant, UUID.fromString("35000000-0000-0000-0000-000000000001"), branch);
                assertEquals(branch, coordinator.read("f43-initial", password, scope).id());
                assertTrue(browser.get(LoginApplication.CORE, path + "/" + branch).body().contains("\"codeEditable\":false"));
                assertEquals(409, browser.post(LoginApplication.CORE, path + "/" + branch + "/edit", browser.csrf(LoginApplication.CORE), branchCommand(null, "HISTORICAL-CHANGE", "Forbidden code", version(admin, branch), password)).statusCode());
                assertEquals(200, browser.post(LoginApplication.CORE, path + "/" + branch + "/edit", browser.csrf(LoginApplication.CORE), branchCommand(null, "SUC02", "Name may change", version(admin, branch), password)).statusCode());
                var beforeFault = version(admin, branch);
                sql.execute("CREATE FUNCTION tenant.fixture_branch_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action_code='BRANCH_SUSPEND' AND NEW.outcome='SUCCESS' THEN RAISE EXCEPTION 'Owned audit fault'; END IF; RETURN NEW; END $$");
                sql.execute("CREATE TRIGGER zz_fixture_branch_fault BEFORE INSERT ON audit.events FOR EACH ROW EXECUTE FUNCTION tenant.fixture_branch_fault()");
                try { assertEquals(503, browser.post(LoginApplication.CORE, path + "/" + branch + "/suspend", browser.csrf(LoginApplication.CORE), branchCommand(null, null, null, beforeFault, password)).statusCode()); }
                finally {
                    sql.execute("DROP TRIGGER zz_fixture_branch_fault ON audit.events");
                    sql.execute("DROP FUNCTION tenant.fixture_branch_fault()");
                }
                assertEquals(beforeFault, version(admin, branch));
                assertEquals(contextBranch.id(), contexts.current(LoginApplication.FISCAL, sessionBranch).id());
                // Two distinct writer connections race with one expected version: no lost update or duplicate transition.
                race(fixture, owner, tenant, branch, "SUSPEND", null, null, beforeFault, "P2501");
                assertEquals(null, contexts.current(LoginApplication.FISCAL, sessionBranch));
                assertEquals(contextMatrix.id(), contexts.current(LoginApplication.FISCAL, sessionMatrix).id());
                assertEquals(contextOther.id(), contexts.current(LoginApplication.FISCAL, sessionOther).id());
                assertThrows(JdbcSessionContexts.Denied.class, () -> contexts.select(LoginApplication.FISCAL, sessionBranch, tenant, branch));
                assertTrue(contexts.branches(LoginApplication.FISCAL, sessionBranch, tenant).stream().noneMatch(b -> b.id().equals(branch)));
                assertThrows(com.lssoluciones.lscore.access.application.context.ContextRejectedException.class, () -> coordinator.read("f43-initial", password, scope));
                assertEquals(200, browser.post(LoginApplication.CORE, path + "/" + branch + "/reactivate", browser.csrf(LoginApplication.CORE), branchCommand(null, null, null, version(admin, branch), password)).statusCode());
                assertEquals(null, contexts.current(LoginApplication.FISCAL, sessionBranch));
                contexts.select(LoginApplication.FISCAL, sessionBranch, tenant, branch);
                assertEquals(200, browser.post(LoginApplication.CORE, path + "/" + branch + "/close", browser.csrf(LoginApplication.CORE), branchCommand(null, null, null, version(admin, branch), password)).statusCode());
                assertEquals(null, contexts.current(LoginApplication.FISCAL, sessionBranch));
                for (String action : new String[] {"edit", "suspend", "reactivate", "close"}) {
                    assertEquals(409, browser.post(LoginApplication.CORE, path + "/" + branch + "/" + action, browser.csrf(LoginApplication.CORE), branchCommand(null, "SUC02", "Closed", version(admin, branch), password)).statusCode());
                }
                assertEquals(409, browser.post(LoginApplication.CORE, path, browser.csrf(LoginApplication.CORE), branchCommand(null, "suc02", "Closed code reserved", null, password)).statusCode());
                race(fixture, owner, tenant, null, "CREATE", "RACE", "Concurrent creation", null, "23505");
                assertEquals("1", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM tenant.branches WHERE tenant_id='" + tenant + "' AND code='RACE'"));
                race(fixture, owner, tenant, matrix, "EDIT", "CASA MATRIZ", "Concurrent edit", version(admin, matrix), "P2501");
                var uncertainService = new JdbcBranchAdministration(server.getBean(JdbcApplicationSessions.class), authentication, uncertain(fixture.role(JdbcBranchAdministration.ROLE)), fixture.role(JdbcBranchAdministration.LOG_ROLE));
                UUID uncertainRequest = UUID.randomUUID();
                var unknown = uncertainService.mutate(browser.token(LoginApplication.CORE), tenant, uncertainRequest, JdbcBranchAdministration.Action.CREATE, null, "UNCERTAIN", "Lost commit reply", null, password);
                assertEquals("UNKNOWN", unknown.outcome());
                var receipt = browser.get(LoginApplication.CORE, "branch-administration/operations/" + uncertainRequest);
                assertEquals(200, receipt.statusCode());
                assertTrue(receipt.body().contains("CONFIRMED"));
                assertEquals(409, browser.post(LoginApplication.CORE, path, browser.csrf(LoginApplication.CORE), branchCommand(uncertainRequest, "UNCERTAIN", "Duplicate request", null, password)).statusCode());
                assertEquals("1", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM tenant.branch_operations WHERE request_id='" + uncertainRequest + "'"));
                var delegated = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(204, delegated.post(LoginApplication.CORE, "login", delegated.csrf(LoginApplication.CORE), credentials("f43-delegate", password)).statusCode());
                assertEquals(403, delegated.get(LoginApplication.CORE, path).statusCode());
                assertEquals(403, delegated.post(LoginApplication.CORE, path, delegated.csrf(LoginApplication.CORE), branchCommand(null, "DENIED", "No branch faculty", null, password)).statusCode());
                sql.execute("INSERT INTO platform.branch_administration_grants(designation_id,capability) SELECT id,'READ' FROM platform.administrators WHERE user_id='" + delegate + "' AND status='ACTIVE'");
                assertEquals(200, delegated.get(LoginApplication.CORE, path).statusCode());
                assertEquals(403, delegated.post(LoginApplication.CORE, path, delegated.csrf(LoginApplication.CORE), branchCommand(null, "DENIED", "Read only", null, password)).statusCode());
                sql.execute("INSERT INTO platform.branch_administration_grants(designation_id,capability) SELECT id,'MANAGE' FROM platform.administrators WHERE user_id='" + delegate + "' AND status='ACTIVE'");
                assertEquals(200, delegated.post(LoginApplication.CORE, path + "/" + matrix + "/edit", delegated.csrf(LoginApplication.CORE), branchCommand(null, "CASA MATRIZ", "Explicitly delegated", version(admin, matrix), password)).statusCode());
                assertEquals(contextMatrix.id(), contexts.current(LoginApplication.FISCAL, sessionMatrix).id());
                assertEquals(contextOther.id(), contexts.current(LoginApplication.FISCAL, sessionOther).id());
                assertEquals("0", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM tenant.branch_operations o LEFT JOIN audit.events e ON e.correlation_id=o.request_id AND e.outcome='SUCCESS' AND e.resource_id=o.branch_id AND e.metadata->>'tenant_id'=o.tenant_id::text WHERE e.id IS NULL"));
                for (var application : new LoginApplication[] {LoginApplication.FISCAL, LoginApplication.POS}) {
                    var other = new ApplicationLoginPostgresTest.Browser(client, base);
                    assertEquals(204, other.post(application, "login", other.csrf(application), credentials("f43-owner", password)).statusCode());
                    var forged = HttpRequest.newBuilder(base.resolve("/api/auth/core/" + path)).header("Cookie", LoginApplication.CORE.sessionCookie() + "=" + other.token(application)).GET().build();
                    assertEquals(401, client.send(forged, HttpResponse.BodyHandlers.ofString()).statusCode());
                }
            }
            assertEquals("t", ApplicationLoginPostgresTest.scalar(admin, "SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class WHERE oid IN ('tenant.branches'::regclass,'tenant.branch_operations'::regclass,'platform.branch_administration_grants'::regclass,'security.session_contexts'::regclass,'audit.events'::regclass)"));
            for (String role : new String[] {JdbcApplicationSessions.ROLE, JdbcBranchAdministration.ROLE}) {
                try (var runtime = fixture.role(role).getConnection(); var direct = runtime.createStatement()) {
                    assertEquals("42501", assertThrows(SQLException.class, () -> direct.executeUpdate("UPDATE tenant.branches SET name='Forbidden'")).getSQLState());
                    assertEquals("42501", assertThrows(SQLException.class, () -> direct.executeUpdate("INSERT INTO tenant.branches(tenant_id,code,name,status) VALUES(gen_random_uuid(),'Forbidden','Forbidden','ACTIVE')")).getSQLState());
                    assertEquals("42501", assertThrows(SQLException.class, () -> direct.executeUpdate("DELETE FROM tenant.branches")).getSQLState());
                }
            }
            assertNotEquals(owner, business);
        } finally { Arrays.fill(password, '\0'); }
    }

    private static java.time.Instant version(java.sql.Connection connection, UUID branch) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT updated_at FROM tenant.branches WHERE id=?")) {
            statement.setObject(1, branch);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getTimestamp(1).toInstant();
            }
        }
    }
    private static String branchCommand(UUID request, String code, String name, java.time.Instant expected, char[] password) {
        return "{\"requestId\":\"" + (request == null ? UUID.randomUUID() : request) + "\",\"code\":" + quoted(code) + ",\"name\":" + quoted(name)
                + ",\"expectedUpdatedAt\":" + quoted(expected == null ? null : expected.toString()) + ",\"password\":\"" + new String(password) + "\"}";
    }
    private static String quoted(String value) { return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static void insert(java.sql.Connection connection, UUID actor, UUID tenant, UUID branch, String action, String code, String name, java.time.Instant expected) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO tenant.branch_operations(request_id,actor_user_id,tenant_id,branch_id,action,expected_updated_at,code,name,resulting_status,branch_created_at) VALUES(?,?,?,?,?,?,?,?,'',clock_timestamp())")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, actor);
            statement.setObject(3, tenant);
            statement.setObject(4, branch == null ? UUID.randomUUID() : branch);
            statement.setString(5, action);
            statement.setTimestamp(6, expected == null ? null : java.sql.Timestamp.from(expected));
            statement.setString(7, code);
            statement.setString(8, name);
            statement.executeUpdate();
        }
    }
    private static void race(ApplicationLoginPostgresTest fixture, UUID actor, UUID tenant, UUID branch, String action, String code, String name, java.time.Instant expected, String rejected) throws Exception {
        try (var first = fixture.role(JdbcBranchAdministration.ROLE).getConnection(); var second = fixture.role(JdbcBranchAdministration.ROLE).getConnection()) {
            assertNotEquals(ApplicationLoginPostgresTest.scalar(first, "SELECT pg_backend_pid()"), ApplicationLoginPostgresTest.scalar(second, "SELECT pg_backend_pid()"));
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            insert(first, actor, tenant, branch, action, code, name, expected);
            var started = new java.util.concurrent.CountDownLatch(1);
            var blocked = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                started.countDown();
                try {
                    insert(second, actor, tenant, branch, action, code, name, expected);
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

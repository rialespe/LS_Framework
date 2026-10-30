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
class InactiveBranchPostgresTest {
    @Test
    void inactiveBranchesDrainAdmissionsAndSeparateHistoricalAuthority() throws Exception {
        var fixture = new ApplicationLoginPostgresTest();
        char[] password = "Registration-401!".toCharArray();
        try (var admin = fixture.role("postgres").getConnection();
                var sql = admin.createStatement();
                var operationPool = new com.zaxxer.hikari.HikariDataSource()) {
            var registrar = new JdbcManualIdentityRegistration(fixture.role(JdbcManualIdentityRegistration.DATABASE_ROLE));
            var lifecycle = new JdbcPasswordLifecycle(fixture.role(JdbcPasswordLifecycle.ISSUER_ROLE), fixture.role(JdbcPasswordLifecycle.CHANGER_ROLE), Duration.ofHours(72));
            UUID owner = definitive(registrar, lifecycle, "f45-owner", password);
            UUID business = definitive(registrar, lifecycle, "f45-business", password);
            UUID delegate = definitive(registrar, lifecycle, "f45-delegate", password);
            UUID initial = definitive(registrar, lifecycle, "f45-initial", password);
            var authentication = new JdbcPasswordAuthentication(fixture.role(JdbcPasswordAuthentication.ROLE), JdbcPasswordAuthentication.Limits.defaults());
            new JdbcPlatformBootstrap(fixture.role(JdbcPlatformBootstrap.ROLE), authentication).initialize("f45-owner", password);
            var global = new JdbcPlatformExecutor(fixture.role(JdbcPlatformExecutor.ROLE), fixture.role(JdbcPlatformExecutor.SECURITY_LOG_ROLE), authentication);
            assertEquals(JdbcPlatformExecutor.Outcome.CONFIRMED, global.execute("f45-owner", password, delegate, JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN).outcome());
            UUID fiscal = UUID.randomUUID();
            UUID pos = UUID.randomUUID();
            sql.execute("INSERT INTO catalog.applications(id,code,name,status) VALUES ('" + fiscal + "','f45-fiscal','Fiscal fixture','PUBLISHED'),('" + pos + "','f45-pos','POS fixture','PUBLISHED')");
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
            properties.put("ls.core.branch-history.enabled", "true");
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
            for (var channel : Map.ofEntries(Map.entry("LS_BRANCH_HISTORY_DB_", JdbcBranchHistory.ROLE), Map.entry("LS_BRANCH_ADMIN_DB_", JdbcBranchAdministration.ROLE),
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
                var logged = browser.post(LoginApplication.CORE, "login", browser.csrf(LoginApplication.CORE), credentials("f45-owner", password));
                assertEquals(204, logged.statusCode());
                assertEquals(200, browser.get(LoginApplication.CORE, "tenant-authority").statusCode());
                UUID tenant = id(browser.post(LoginApplication.CORE, "registrations", browser.csrf(LoginApplication.CORE), payload(UUID.randomUUID(), initial, "Guard tenant A", password)).body());
                assertEquals(200, browser.post(LoginApplication.CORE, "tenants/" + tenant + "/activate", browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                UUID companyB = id(browser.post(LoginApplication.CORE, "registrations", browser.csrf(LoginApplication.CORE), payload(UUID.randomUUID(), business, "Guard tenant B", password)).body());
                assertEquals(200, browser.post(LoginApplication.CORE, "tenants/" + companyB + "/activate", browser.csrf(LoginApplication.CORE), operation(password)).statusCode());
                String branches = "branch-administration/tenants/" + tenant + "/branches";
                UUID branch = id(browser.post(LoginApplication.CORE, branches, browser.csrf(LoginApplication.CORE), branchPayload("SUCURSAL 02", password)).body());
                UUID otherBranch = id(browser.post(LoginApplication.CORE, branches, browser.csrf(LoginApplication.CORE), branchPayload("CASA MATRIZ", password)).body());
                UUID foreign = id(browser.post(LoginApplication.CORE, "branch-administration/tenants/" + companyB + "/branches", browser.csrf(LoginApplication.CORE), branchPayload("CASA MATRIZ", password)).body());
                UUID app = UUID.fromString(core);
                UUID module = UUID.fromString(ApplicationLoginPostgresTest.scalar(admin, "SELECT id FROM catalog.modules WHERE application_id='" + app + "' AND code='branches'"));
                // Explicit disposable F09-like permissions;
                // None are installed by V27 or implied by OWNER.
                access(sql, owner, tenant, app, module, branch, otherBranch);
                access(sql, owner, companyB, app, module, foreign);
                UUID context = context(admin, owner, tenant, branch, app);
                UUID contextOther = context(admin, owner, tenant, otherBranch, app);
                UUID contextForeign = context(admin, owner, companyB, foreign, app);
                var authorization = new com.lssoluciones.lscore.access.infrastructure.jdbc.JdbcTenantAuthorization(fixture.role("ls_core_authorization_reader_dev"), authentication);
                operationPool.setDataSource(fixture.role("ls_core_branch_operation_dev"));
                operationPool.setReadOnly(true);
                operationPool.setMaximumPoolSize(2);
                var coordinator = new com.lssoluciones.lscore.access.infrastructure.jdbc.BranchReadCoordinator(authorization, operationPool, fixture.role("ls_core_operation_emitter_dev"));
                var selected = new com.lssoluciones.lscore.access.application.context.TrustedContextSource.Scope(tenant, app, branch);
                assertEquals(branch, coordinator.read("f45-owner", password, selected).id());
                String history = "branch-administration/tenants/" + tenant + "/historical-changes?branches=" + branch;
                // OWNER is not implicit historical authority, and an admin of A has no authority over B.
                assertEquals(403, browser.get(LoginApplication.CORE, history).statusCode());
                grant(sql, owner, tenant, app, module, "HISTORY_READ", branch, otherBranch);
                assertEquals(200, browser.get(LoginApplication.CORE, history).statusCode());
                assertEquals(403, browser.get(LoginApplication.CORE, history + "," + otherBranch).statusCode());
                sql.execute("INSERT INTO security.branch_multihistory_grants SELECT tenant_id,id,clock_timestamp()+interval '1 hour' FROM tenant.memberships WHERE tenant_id='" + tenant + "' AND user_id='" + owner + "'");
                assertEquals(200, browser.get(LoginApplication.CORE, history + "," + otherBranch).statusCode());
                assertEquals(403, browser.get(LoginApplication.CORE, history + "," + foreign).statusCode());
                assertEquals(403, browser.get(LoginApplication.CORE, "branch-administration/tenants/" + companyB + "/historical-changes?branches=" + foreign).statusCode());
                var enterprise = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(204, enterprise.post(LoginApplication.CORE, "login", enterprise.csrf(LoginApplication.CORE), credentials("f45-initial", password)).statusCode());
                assertEquals(403, enterprise.get(LoginApplication.CORE, history).statusCode());
                assertEquals(403, enterprise.get(LoginApplication.CORE, "branch-administration/tenants/" + companyB + "/historical-changes?branches=" + foreign).statusCode());
                var delegateBrowser = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(204, delegateBrowser.post(LoginApplication.CORE, "login", delegateBrowser.csrf(LoginApplication.CORE), credentials("f45-delegate", password)).statusCode());
                assertEquals(403, delegateBrowser.get(LoginApplication.CORE, history).statusCode());
                assertEquals(400, browser.get(LoginApplication.CORE, history + "," + branch).statusCode());
                assertEquals(400, browser.get(LoginApplication.CORE, history + "&page=-1").statusCode());
                // Read authority cannot pass a write probe. No business DML is enabled for this test.
                rejected(fixture, "42501", owner, tenant, branch, app, module, context, null);
                grant(sql, owner, tenant, app, module, "WRITE_PROBE", branch, otherBranch);
                rejected(fixture, "42501", initial, tenant, branch, app, module, context, null);
                rejected(fixture, "42501", owner, companyB, branch, app, module, context, null);
                rejected(fixture, "42501", owner, tenant, foreign, app, module, context, null);
                rejected(fixture, "42501", owner, tenant, branch, app, module, contextOther, null);
                try (var first = fixture.role("ls_core_branch_guard_dev").getConnection()) {
                    first.setAutoCommit(false);
                    probe(first, owner, tenant, branch, app, module, context, null);
                    drain(fixture, first, admin, owner, tenant, branch, "SUSPEND");
                }
                assertEquals("SUSPENDED", ApplicationLoginPostgresTest.scalar(admin, "SELECT status FROM tenant.branches WHERE id='" + branch + "'"));
                assertThrows(com.lssoluciones.lscore.access.application.context.ContextRejectedException.class, () -> coordinator.read("f45-owner", password, selected));
                rejected(fixture, "P2702", owner, tenant, branch, app, module, context, null);
                assertEquals(200, browser.get(LoginApplication.CORE, history).statusCode());
                assertTrue(browser.get(LoginApplication.CORE, history).body().contains("SUSPEND"));
                assertEquals("1", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM security.session_contexts WHERE id='" + context + "' AND revoked_at IS NOT NULL"));
                for (UUID preserved : new UUID[] {
                        contextOther, contextForeign
                }
                ) {
                    assertEquals("1", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM security.session_contexts WHERE id='" + preserved + "' AND revoked_at IS NULL"));
                }
                assertEquals(200, browser.get(LoginApplication.CORE, "session").statusCode());
                assertEquals(200, browser.post(LoginApplication.CORE, branches + "/" + branch + "/reactivate", browser.csrf(LoginApplication.CORE), branchTransition(admin, branch, password)).statusCode());
                rejected(fixture, "42501", owner, tenant, branch, app, module, context, null);
                UUID renewed = context(admin, owner, tenant, branch, app);
                assertNotEquals(context, renewed);
                try (var first = fixture.role("ls_core_branch_guard_dev").getConnection()) {
                    first.setAutoCommit(false);
                    probe(first, owner, tenant, branch, app, module, renewed, null);
                    drain(fixture, first, admin, owner, tenant, branch, "CLOSE");
                }
                rejected(fixture, "P2702", owner, tenant, branch, app, module, renewed, null);
                assertThrows(com.lssoluciones.lscore.access.application.context.ContextRejectedException.class, () -> coordinator.read("f45-owner", password, selected));
                assertEquals(200, browser.get(LoginApplication.CORE, history).statusCode());
                assertTrue(browser.get(LoginApplication.CORE, history).body().contains("CLOSE"));
                assertEquals(409, browser.post(LoginApplication.CORE, branches + "/" + branch + "/reactivate", browser.csrf(LoginApplication.CORE), branchTransition(admin, branch, password)).statusCode());
                var otherScope = new com.lssoluciones.lscore.access.application.context.TrustedContextSource.Scope(tenant, app, otherBranch);
                assertEquals(otherBranch, coordinator.read("f45-owner", password, otherScope).id());
                // Explicit F05 selection also cannot resurrect an inactive branch.
                try (var selector = fixture.role(JdbcApplicationSessions.ROLE).getConnection();
                        var statement = selector.createStatement()) {
                    assertEquals("42501", assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO security.session_contexts(id,session_id,user_id,application_id,tenant_id,membership_id,branch_id,expires_at) SELECT gen_random_uuid(),session_id,user_id,application_id,tenant_id,membership_id,branch_id,clock_timestamp()+interval '5 minutes' FROM security.session_contexts WHERE id='" + renewed + "'")).getSQLState());
                }
                // Tenant and module states remain independent gates.
                sql.execute("UPDATE catalog.tenant_modules SET status='DISABLED' WHERE tenant_id='" + tenant + "' AND module_id='" + module + "'");
                rejected(fixture, "P2703", owner, tenant, otherBranch, app, module, contextOther, null);
                assertEquals(403, browser.get(LoginApplication.CORE, history).statusCode());
                sql.execute("UPDATE catalog.tenant_modules SET status='ACTIVE' WHERE tenant_id='" + tenant + "' AND module_id='" + module + "'");
                sql.execute("UPDATE catalog.tenant_applications SET status='DISABLED' WHERE tenant_id='" + tenant + "' AND application_id='" + app + "'");
                rejected(fixture, "P2703", owner, tenant, otherBranch, app, module, contextOther, null);
                sql.execute("UPDATE catalog.tenant_applications SET status='ACTIVE' WHERE tenant_id='" + tenant + "' AND application_id='" + app + "'");
                sql.execute("UPDATE security.branch_operation_grants SET expires_at=clock_timestamp()-interval '1 second' WHERE tenant_id='" + tenant + "' AND capability='WRITE_PROBE'");
                rejected(fixture, "42501", owner, tenant, otherBranch, app, module, contextOther, null);
                sql.execute("UPDATE security.branch_operation_grants SET expires_at=clock_timestamp()+interval '1 hour' WHERE tenant_id='" + tenant + "'");
                rejected(fixture, "P2704", owner, tenant, otherBranch, app, module, contextOther, 1);
                // Optional READY contract is checked only when requested by a consumer.
                sql.execute(java.nio.file.Files.readString(Path.of("../scripts/pilot/branch_configuration_fixture.sql")).replace("__TENANT__", tenant.toString()));
                UUID fixtureApp = UUID.fromString(ApplicationLoginPostgresTest.scalar(admin, "SELECT id FROM catalog.applications WHERE code='fixture-config-lfd44'"));
                UUID fixtureModule = UUID.fromString(ApplicationLoginPostgresTest.scalar(admin, "SELECT id FROM catalog.modules WHERE application_id='" + fixtureApp + "'"));
                grant(sql, owner, tenant, fixtureApp, fixtureModule, "WRITE_PROBE", otherBranch);
                UUID fixtureContext = context(admin, owner, tenant, otherBranch, fixtureApp);
                var configuration = server.getBean(JdbcBranchConfiguration.class);
                var configScope = new JdbcBranchConfiguration.Scope(tenant, otherBranch, fixtureApp, fixtureModule, 1);
                var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree("{\"label\":\"Guard fixture\",\"capacity\":2}");
                assertEquals("CONFIRMED", configuration.mutate(browser.token(LoginApplication.CORE), configScope, UUID.randomUUID(), JdbcBranchConfiguration.Action.PREPARE, null, json, password).outcome());
                rejected(fixture, "P2704", owner, tenant, otherBranch, fixtureApp, fixtureModule, fixtureContext, 1);
                assertEquals("CONFIRMED", configuration.mutate(browser.token(LoginApplication.CORE), configScope, UUID.randomUUID(), JdbcBranchConfiguration.Action.VALIDATE, 1L, json, password).outcome());
                try (var connection = fixture.role("ls_core_branch_guard_dev").getConnection()) {
                    connection.setAutoCommit(false);
                    probe(connection, owner, tenant, otherBranch, fixtureApp, fixtureModule, fixtureContext, 1);
                    connection.commit();
                }
                sql.execute("UPDATE catalog.tenant_modules SET status='DISABLED' WHERE tenant_id='" + tenant + "' AND module_id='" + fixtureModule + "'");
                rejected(fixture, "P2703", owner, tenant, otherBranch, fixtureApp, fixtureModule, fixtureContext, 1);
                sql.execute("UPDATE catalog.tenant_modules SET status='ACTIVE' WHERE tenant_id='" + tenant + "' AND module_id='" + fixtureModule + "'");
                rejected(fixture, "P2704", owner, tenant, otherBranch, fixtureApp, fixtureModule, fixtureContext, 2);
                sql.execute("UPDATE catalog.branch_configuration_definitions SET status='RETIRED' WHERE application_id='" + fixtureApp + "'");
                rejected(fixture, "P2704", owner, tenant, otherBranch, fixtureApp, fixtureModule, fixtureContext, 1);
                sql.execute("UPDATE tenant.tenants SET status='SUSPENDED' WHERE id='" + tenant + "'");
                rejected(fixture, "P2701", owner, tenant, otherBranch, app, module, contextOther, null);
                assertEquals(403, browser.get(LoginApplication.CORE, history).statusCode());
                sql.execute("UPDATE tenant.tenants SET status='ACTIVE' WHERE id='" + tenant + "'");
                // Audit fault rolls back the admission evidence, with no result leaked.
                String before = ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM security.branch_history_requests");
                sql.execute("CREATE FUNCTION security.fixture_history_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action_code='BRANCH_HISTORY_ADMITTED' THEN RAISE EXCEPTION 'Owned audit fault'; END IF; RETURN NEW; END $$");
                sql.execute("CREATE TRIGGER zz_history_fixture_fault BEFORE INSERT ON audit.events FOR EACH ROW EXECUTE FUNCTION security.fixture_history_fault()");
                assertEquals(503, browser.get(LoginApplication.CORE, history).statusCode());
                sql.execute("DROP TRIGGER zz_history_fixture_fault ON audit.events");
                sql.execute("DROP FUNCTION security.fixture_history_fault()");
                assertEquals(before, ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM security.branch_history_requests"));
                try (var historical = fixture.role(JdbcBranchHistory.ROLE).getConnection();
                        var statement = historical.createStatement()) {
                    assertEquals("0", ApplicationLoginPostgresTest.scalar(historical, "SELECT count(*) FROM tenant.branch_operations"));
                    assertEquals("42501", assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE tenant.branches SET status='ACTIVE'")).getSQLState());
                    assertEquals("42501", assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO tenant.branch_operations(request_id) VALUES(gen_random_uuid())")).getSQLState());
                    assertEquals("42501", assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM security.branch_history_requests")).getSQLState());
                }
                // Even genuine historical evidence cannot be reused after expiry or COMMIT.
                try (var historical = fixture.role(JdbcBranchHistory.ROLE).getConnection()) {
                    historical.setAutoCommit(false);
                    try (var statement = historical.prepareStatement("INSERT INTO security.branch_history_requests(request_id,actor_user_id,tenant_id,application_id,module_id,branch_ids) VALUES(?,?,?,?,?,?)")) {
                        statement.setObject(1, UUID.randomUUID());
                        statement.setObject(2, owner);
                        statement.setObject(3, tenant);
                        statement.setObject(4, app);
                        statement.setObject(5, module);
                        statement.setArray(6, historical.createArrayOf("uuid", new UUID[] {branch}));
                        statement.executeUpdate();
                    }
                    assertTrue(Integer.parseInt(ApplicationLoginPostgresTest.scalar(historical, "SELECT count(*) FROM tenant.branch_operations")) > 0);
                    Thread.sleep(10100);
                    assertEquals("0", ApplicationLoginPostgresTest.scalar(historical, "SELECT count(*) FROM tenant.branch_operations"));
                    historical.commit();
                    assertEquals("0", ApplicationLoginPostgresTest.scalar(historical, "SELECT count(*) FROM tenant.branch_operations"));
                    historical.rollback();
                }
                try (var guard = fixture.role("ls_core_branch_guard_dev").getConnection(); var statement = guard.createStatement()) {
                    assertEquals("42501", assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE tenant.branches SET id=id")).getSQLState());
                    assertEquals("42501", assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM tenant.branches")).getSQLState());
                    assertEquals("42501", assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO audit.events(actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata) VALUES('USER','" + owner + "','BRANCH_WRITE_PROBE_ADMITTED','TENANT_BRANCH','" + branch + "','SUCCESS',gen_random_uuid(),'{}')")).getSQLState());
                }
                for (var application : new LoginApplication[] {LoginApplication.FISCAL, LoginApplication.POS}) {
                    var other = new ApplicationLoginPostgresTest.Browser(client, base);
                    assertEquals(204, other.post(application, "login", other.csrf(application), credentials("f45-owner", password)).statusCode());
                    var forged = HttpRequest.newBuilder(base.resolve("/api/auth/core/" + history)).header("Cookie", LoginApplication.CORE.sessionCookie() + "=" + other.token(application)).GET().build();
                    assertEquals(401, client.send(forged, HttpResponse.BodyHandlers.ofString()).statusCode());
                }
                assertEquals("0", ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM security.branch_history_requests r LEFT JOIN audit.events e ON e.correlation_id=r.request_id AND e.action_code='BRANCH_HISTORY_ADMITTED' WHERE e.id IS NULL"));
                assertEquals("t", ApplicationLoginPostgresTest.scalar(admin, "SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class WHERE oid IN ('security.branch_history_requests'::regclass,'security.branch_operation_grants'::regclass,'security.branch_multihistory_grants'::regclass,'security.branch_write_probes'::regclass)"));
            }
        }
        finally {
            Arrays.fill(password, '\0');
        }
    }
    private static void access(java.sql.Statement sql, UUID actor, UUID tenant, UUID app, UUID module, UUID... branches) throws SQLException {
        sql.execute("INSERT INTO tenant.memberships(id,tenant_id,user_id,status) VALUES(gen_random_uuid(),'" + tenant + "','" + actor + "','ACTIVE')");
        sql.execute("INSERT INTO catalog.tenant_applications(tenant_id,application_id,status) VALUES('" + tenant + "','" + app + "','ACTIVE')");
        sql.execute("INSERT INTO catalog.tenant_modules(tenant_id,application_id,module_id,status) VALUES('" + tenant + "','" + app + "','" + module + "','ACTIVE')");
        for (UUID branch : branches) {
            sql.execute("INSERT INTO tenant.branch_memberships(id,tenant_id,membership_id,branch_id,status) SELECT gen_random_uuid(),tenant_id,id,'" + branch + "','ACTIVE' FROM tenant.memberships WHERE tenant_id='" + tenant + "' AND user_id='" + actor + "'");
        }
    }
    private static void grant(java.sql.Statement sql, UUID actor, UUID tenant, UUID app, UUID module, String capability, UUID... branches) throws SQLException {
        for (UUID branch : branches) {
            sql.execute("INSERT INTO security.branch_operation_grants SELECT tenant_id,id,'" + branch + "','" + app + "','" + module + "','" + capability + "',clock_timestamp()+interval '1 hour' FROM tenant.memberships WHERE tenant_id='" + tenant + "' AND user_id='" + actor + "'");
        }
    }
    private static UUID context(java.sql.Connection admin, UUID actor, UUID tenant, UUID branch, UUID app) throws SQLException {
        UUID session = UUID.randomUUID();
        UUID context = UUID.randomUUID();
        try (var statement = admin.createStatement()) {
            statement.execute("INSERT INTO security.sessions(id,user_id,application_id,token_hash,expires_at) VALUES('" + session + "','" + actor + "','" + app + "','" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "") + "',clock_timestamp()+interval '1 hour')");
            statement.execute("INSERT INTO security.session_contexts(id,session_id,user_id,application_id,tenant_id,membership_id,branch_id,expires_at) SELECT '" + context + "','" + session + "','" + actor + "','" + app + "',tenant_id,id,'" + branch + "',clock_timestamp()+interval '30 minutes' FROM tenant.memberships WHERE tenant_id='" + tenant + "' AND user_id='" + actor + "'");
        }
        return context;
    }
    private static void probe(java.sql.Connection connection, UUID actor, UUID tenant, UUID branch, UUID app, UUID module, UUID context, Integer version) throws SQLException {
        com.lssoluciones.lscore.access.infrastructure.jdbc.BranchOperationGuard.probe(connection, UUID.randomUUID(), actor, tenant, branch, app, module, context, version);
    }
    private static void rejected(ApplicationLoginPostgresTest fixture, String state, UUID actor, UUID tenant, UUID branch, UUID app, UUID module, UUID context, Integer version) throws Exception {
        try (var connection = fixture.role("ls_core_branch_guard_dev").getConnection()) {
            connection.setAutoCommit(false);
            assertEquals(state, assertThrows(SQLException.class, () -> probe(connection, actor, tenant, branch, app, module, context, version)).getSQLState());
            connection.rollback();
        }
    }
    private static void drain(ApplicationLoginPostgresTest fixture, java.sql.Connection admitted, java.sql.Connection admin, UUID actor, UUID tenant, UUID branch, String action) throws Exception {
        try (var second = fixture.role(JdbcBranchAdministration.ROLE).getConnection()) {
            second.setAutoCommit(false);
            int pid = Integer.parseInt(ApplicationLoginPostgresTest.scalar(second, "SELECT pg_backend_pid()"));
            assertNotEquals(Integer.toString(pid), ApplicationLoginPostgresTest.scalar(admitted, "SELECT pg_backend_pid()"));
            var version = stamp(admin, branch);
            var pending = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (var statement = second.prepareStatement("INSERT INTO tenant.branch_operations(request_id,actor_user_id,tenant_id,branch_id,action,expected_updated_at,code,name,resulting_status,branch_created_at) VALUES(?,?,?,?,?,?,'','','',clock_timestamp())")) {
                    statement.setObject(1, UUID.randomUUID());
                    statement.setObject(2, actor);
                    statement.setObject(3, tenant);
                    statement.setObject(4, branch);
                    statement.setString(5, action);
                    statement.setTimestamp(6, version);
                    statement.executeUpdate();
                    second.commit();
                    return "CONFIRMED";
                }
                catch (SQLException failure) {
                    return failure.getSQLState();
                }
            }
                );
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (System.nanoTime() < deadline && !pending.isDone()) {
                waiting = "1".equals(ApplicationLoginPostgresTest.scalar(admin, "SELECT count(*) FROM pg_locks WHERE pid=" + pid + " AND locktype='advisory' AND NOT granted"));
                if (waiting) {
                    break;
                }
                Thread.sleep(20);
            }
            assertTrue(waiting, "Lifecycle change must queue behind an admitted transaction");
            admitted.commit();
            assertEquals("CONFIRMED", pending.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
    private static String branchPayload(String code, char[] password) {
        return "{\"requestId\":\"" + UUID.randomUUID() + "\",\"code\":\"" + code + "\",\"name\":\"General only\",\"password\":\"" + new String(password) + "\"}";
    }
    private static String command(UUID request, Long revision, String values, char[] password) {
        return "{\"requestId\":\"" + (request == null ? UUID.randomUUID() : request) + "\",\"expectedRevision\":" + revision + ",\"values\":" + values + ",\"password\":\"" + new String(password) + "\"}";
    }
    private static java.sql.Timestamp stamp(java.sql.Connection connection, UUID branch) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT updated_at FROM tenant.branches WHERE id=?")) {
            statement.setObject(1, branch);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getTimestamp(1);
            }
        }
    }
    private static String branchTransition(java.sql.Connection connection, UUID branch, char[] password) throws SQLException {
        return "{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedUpdatedAt\":\"" + stamp(connection, branch).toInstant() + "\",\"password\":\"" + new String(password) + "\"}";
    }
    private static String operation(char[] password) {
        return "{\"requestId\":\"" + UUID.randomUUID() + "\",\"password\":\"" + new String(password) + "\"}";
    }
    private static String payload(UUID request, UUID initial, String name, char[] password) {
        return "{\"requestId\":\"" + request + "\",\"name\":\"" + name + "\",\"administratorId\":\"" + initial + "\",\"password\":\"" + new String(password) + "\"}";
    }
    private static String credentials(String username, char[] password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + new String(password) + "\"}";
    }
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
            }
            finally {
                Arrays.fill(temporary, '\0');
            }
        }
        return id;
    }
}

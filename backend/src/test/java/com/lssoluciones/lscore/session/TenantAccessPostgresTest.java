package com.lssoluciones.lscore.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@EnabledIfEnvironmentVariable(named = "LS_LOGIN_TEST_ROOT", matches = ".+")
class TenantAccessPostgresTest {
    @Test
    void selectiveRevocationConsentContinuityAndConcurrentAccess() throws Exception {
        var fixture = new ApplicationLoginPostgresTest();
        char[] password = "Registration-401!".toCharArray();
        try (var admin = fixture.role("postgres").getConnection();
                var sql = admin.createStatement()) {
            var registrar =
                    new JdbcManualIdentityRegistration(
                            fixture.role(JdbcManualIdentityRegistration.DATABASE_ROLE));
            var lifecycle =
                    new JdbcPasswordLifecycle(
                            fixture.role(JdbcPasswordLifecycle.ISSUER_ROLE),
                            fixture.role(JdbcPasswordLifecycle.CHANGER_ROLE),
                            Duration.ofHours(72));
            UUID owner = definitive(registrar, lifecycle, "f48-owner", password);
            UUID business = definitive(registrar, lifecycle, "f48-business", password);
            UUID delegate = definitive(registrar, lifecycle, "f48-delegate", password);
            UUID initial = definitive(registrar, lifecycle, "f48-initial", password);
            UUID outsider = definitive(registrar, lifecycle, "f48-outsider", password);
            var authentication =
                    new JdbcPasswordAuthentication(
                            fixture.role(JdbcPasswordAuthentication.ROLE),
                            JdbcPasswordAuthentication.Limits.defaults());
            new JdbcPlatformBootstrap(fixture.role(JdbcPlatformBootstrap.ROLE), authentication)
                    .initialize("f48-owner", password);
            var global =
                    new JdbcPlatformExecutor(
                            fixture.role(JdbcPlatformExecutor.ROLE),
                            fixture.role(JdbcPlatformExecutor.SECURITY_LOG_ROLE),
                            authentication);
            assertEquals(
                    JdbcPlatformExecutor.Outcome.CONFIRMED,
                    global.execute(
                                    "f48-owner",
                                    password,
                                    delegate,
                                    JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN)
                            .outcome());
            UUID fiscal = UUID.randomUUID();
            UUID pos = UUID.randomUUID();
            sql.execute(
                    "INSERT INTO catalog.applications(id,code,name,status) VALUES ('"
                            + fiscal
                            + "','f48-fiscal','Fiscal fixture','PUBLISHED'),('"
                            + pos
                            + "','f48-pos','POS fixture','PUBLISHED')");
            String core =
                    ApplicationLoginPostgresTest.scalar(
                            admin,
                            "SELECT id FROM catalog.applications WHERE code='ls-core' AND"
                                    + " status='PUBLISHED'");
            var properties = new HashMap<String, Object>();
            properties.put("ls.identity.credentials.enabled", "true");
            properties.put("ls.login.enabled", "true");
            properties.put("ls.login.multidevice.enabled", "true");
            properties.put("ls.login.contexts.enabled", "true");
            properties.put("ls.login.core.enabled", "true");
            properties.put("ls.core.registration.enabled", "true");
            properties.put("ls.core.lifecycle.enabled", "true");
            properties.put("ls.core.administrators.enabled", "true");
            properties.put("ls.core.tenant-users.enabled", "true");
            properties.put("ls.core.identity-links.enabled", "true");
            properties.put("ls.core.tenant-access.enabled", "true");
            properties.put("ls.core.branches.enabled", "true");
            properties.put("ls.identity.password-lifecycle.enabled", "true");
            properties.put("ls.login.fiscal.application-id", fiscal.toString());
            properties.put("ls.login.pos.application-id", pos.toString());
            properties.put("ls.login.core.application-id", core);
            properties.put("server.address", "127.0.0.1");
            properties.put("server.port", "0");
            properties.put("server.ssl.enabled", "true");
            properties.put(
                    "server.ssl.key-store",
                    Path.of(System.getenv("LS_LOGIN_TEST_ROOT"))
                            .resolve("login-test.p12")
                            .toUri()
                            .toString());
            properties.put("server.ssl.key-store-type", "PKCS12");
            properties.put("server.ssl.key-alias", "localhost");
            properties.put(
                    "server.ssl.key-store-password", System.getenv("LS_LOGIN_TEST_TLS_PASSWORD"));
            properties.put("spring.main.banner-mode", "off");
            for (var channel :
                    Map.ofEntries(
                                    Map.entry("LS_TENANT_ADMIN_DB_", JdbcTenantAdministrators.ROLE),
                                    Map.entry(
                                            "LS_TENANT_ADMIN_LOG_DB_",
                                            JdbcTenantAdministrators.LOG_ROLE),
                                    Map.entry("LS_TENANT_USERS_DB_", JdbcTenantUsers.ROLE),
                                    Map.entry("LS_TENANT_ACCESS_DB_", JdbcTenantAccess.ROLE),
                                    Map.entry("LS_TENANT_ACCESS_LOG_DB_", JdbcTenantAccess.LOG_ROLE),
                                    Map.entry("LS_BRANCH_ADMIN_DB_", JdbcBranchAdministration.ROLE),
                                    Map.entry("LS_BRANCH_ADMIN_LOG_DB_", JdbcBranchAdministration.LOG_ROLE),
                                    Map.entry("LS_IDENTITY_LINKS_DB_", JdbcIdentityLinks.ROLE),
                                    Map.entry("LS_IDENTITY_LINKS_LOG_DB_", JdbcIdentityLinks.LOG_ROLE),
                                    Map.entry("LS_TENANT_USERS_LOG_DB_", JdbcTenantUsers.LOG_ROLE),
                                    Map.entry("LS_TENANT_LIFECYCLE_DB_", JdbcTenantLifecycle.ROLE),
                                    Map.entry(
                                            "LS_TENANT_LIFECYCLE_LOG_DB_",
                                            JdbcTenantLifecycle.LOG_ROLE),
                                    Map.entry(
                                            "LS_AUTHENTICATION_DB_",
                                            JdbcPasswordAuthentication.ROLE),
                                    Map.entry("LS_LOGIN_DB_", JdbcApplicationSessions.ROLE),
                                    Map.entry(
                                            "LS_TENANT_REGISTRATION_DB_",
                                            JdbcTenantRegistration.ROLE),
                                    Map.entry(
                                            "LS_TENANT_REGISTRATION_LOG_DB_",
                                            JdbcTenantRegistration.LOG_ROLE),
                                    Map.entry(
                                            "LS_PASSWORD_ISSUER_DB_",
                                            JdbcPasswordLifecycle.ISSUER_ROLE),
                                    Map.entry(
                                            "LS_PASSWORD_CHANGER_DB_",
                                            JdbcPasswordLifecycle.CHANGER_ROLE))
                            .entrySet()) {
                properties.put(channel.getKey() + "HOST", "127.0.0.1");
                properties.put(channel.getKey() + "PORT", System.getenv("LS_LOGIN_TEST_PORT"));
                properties.put(channel.getKey() + "NAME", "ls_core_dev");
                properties.put(
                        channel.getKey() + "PASSWORD",
                        System.getenv(
                                "LS_LOGIN_TEST_"
                                        + channel.getValue().toUpperCase(java.util.Locale.ROOT)));
            }
            try (var server =
                            new SpringApplicationBuilder(LsCoreApplication.class)
                                    .profiles("test")
                                    .initializers(
                                            context ->
                                                    context.getEnvironment()
                                                            .getPropertySources()
                                                            .addFirst(
                                                                    new MapPropertySource(
                                                                            "owned-registration",
                                                                            properties)))
                                    .run();
                    var client =
                            HttpClient.newBuilder()
                                    .sslContext(
                                            ApplicationLoginPostgresTest.trust(
                                                    Path.of(System.getenv("LS_LOGIN_TEST_ROOT"))
                                                            .resolve("login-test.p12")))
                                    .build()) {
                URI base =
                        URI.create(
                                "https://127.0.0.1:"
                                        + server.getEnvironment()
                                                .getRequiredProperty("local.server.port"));
                var browser = new ApplicationLoginPostgresTest.Browser(client, base);
                var logged =
                        browser.post(
                                LoginApplication.CORE,
                                "login",
                                browser.csrf(LoginApplication.CORE),
                                credentials("f48-owner", password));
                assertEquals(204, logged.statusCode());
                assertEquals(
                        200, browser.get(LoginApplication.CORE, "tenant-authority").statusCode());
                UUID tenant = company(browser, initial, password);
                UUID other = company(browser, outsider, password);
                var access = server.getBean(JdbcTenantAccess.class);
                var links = server.getBean(JdbcIdentityLinks.class);
                var contexts = server.getBean(JdbcSessionContexts.class);
                var holder = new ApplicationLoginPostgresTest.Browser(client, base);
                var enterprise = new ApplicationLoginPostgresTest.Browser(client, base);
                var delegated = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(204, post(holder, "login", credentials("f48-business", password)).statusCode());
                assertEquals(204, post(enterprise, "login", credentials("f48-outsider", password)).statusCode());
                assertEquals(204, post(delegated, "login", credentials("f48-delegate", password)).statusCode());
                for (UUID company : new UUID[] {tenant, other}) {
                    assertEquals(200, post(browser, "identity-links/tenants/" + company + "/requests", linkRequest(UUID.randomUUID(), "f48-business", password)).statusCode());
                    UUID link = links.received(holder.token(LoginApplication.CORE), 0).requests().stream()
                            .filter(value -> value.tenantId().equals(company)).findFirst().orElseThrow().id();
                    assertEquals(200, post(holder, "identity-links/received/" + link + "/accept", operation(password)).statusCode());
                }
                UUID member = UUID.fromString(scalar(admin, "SELECT id FROM tenant.memberships WHERE tenant_id='" + tenant + "' AND user_id='" + business + "'"));
                UUID foreignMember = UUID.fromString(scalar(admin, "SELECT id FROM tenant.memberships WHERE tenant_id='" + other + "' AND user_id='" + business + "'"));
                String consent = scalar(admin, "SELECT json_agg(r ORDER BY id)::text FROM tenant.identity_link_requests r WHERE user_id='" + business + "'");
                String otherMembership = scalar(admin, "SELECT row_to_json(m)::text FROM tenant.memberships m WHERE id='" + foreignMember + "'");
                assertTrue(access.access(browser.token(LoginApplication.CORE), tenant, member).branches().isEmpty());
                String path = "tenant-users/tenants/" + tenant + "/users/" + member;
                assertEquals(404, post(browser, path + "/branch-accesses/" + UUID.randomUUID() + "/reactivate", change(UUID.randomUUID(), java.time.Instant.now(), password)).statusCode());
                UUID matrix = branch(browser, tenant, "CASA MATRIZ", password);
                UUID second = branch(browser, tenant, "SUCURSAL 02", password);
                UUID foreignBranch = branch(browser, other, "CASA MATRIZ", password);
                UUID module = UUID.randomUUID();
                sql.execute("INSERT INTO catalog.modules(id,application_id,code,name,status) VALUES('" + module + "','" + fiscal + "','f48-probe','Disposable admission contract','PUBLISHED')");
                for (UUID company : new UUID[] {tenant, other}) {
                    sql.execute("INSERT INTO catalog.tenant_applications(tenant_id,application_id,status) VALUES('" + company + "','" + fiscal + "','ACTIVE')");
                    sql.execute("INSERT INTO catalog.tenant_modules(tenant_id,application_id,module_id,status) VALUES('" + company + "','" + fiscal + "','" + module + "','ACTIVE')");
                }
                assignments(sql, business, tenant, fiscal, module, matrix, second);
                assignments(sql, business, other, fiscal, module, foreignBranch);
                sql.execute("INSERT INTO tenant.memberships(tenant_id,user_id,status) VALUES('" + tenant + "','" + delegate + "','ACTIVE')");
                assignments(sql, delegate, tenant, fiscal, module, second);
                var matrixBrowser = fiscalLogin(client, base, "f48-business", password);
                var secondBrowser = fiscalLogin(client, base, "f48-business", password);
                var foreignBrowser = fiscalLogin(client, base, "f48-business", password);
                var otherUser = fiscalLogin(client, base, "f48-delegate", password);
                UUID matrixContext = select(matrixBrowser, tenant, matrix);
                UUID secondContext = select(secondBrowser, tenant, second);
                UUID foreignContext = select(foreignBrowser, other, foreignBranch);
                UUID otherContext = select(otherUser, tenant, second);
                var snapshot = access.access(browser.token(LoginApplication.CORE), tenant, member);
                String memberBody = change(UUID.randomUUID(), snapshot.updatedAt(), password);
                assertEquals(403, browser.post(LoginApplication.CORE, path + "/access/suspend", null, memberBody).statusCode());
                assertEquals(403, post(browser, path + "/access/suspend", change(UUID.randomUUID(), snapshot.updatedAt(), "Incorrect-48!".toCharArray())).statusCode());
                assertEquals(403, post(enterprise, path + "/access/suspend", memberBody).statusCode());
                assertEquals(403, post(delegated, path + "/access/suspend", memberBody).statusCode());
                assertEquals(403, enterprise.get(LoginApplication.CORE, path + "/access").statusCode());
                assertEquals(404, browser.get(LoginApplication.CORE, "tenant-users/tenants/" + other + "/users/" + member + "/access").statusCode());
                // Admission holds the existing F06 shared barrier; suspension must drain it.
                try (var admitted = fixture.role("ls_core_branch_guard_dev").getConnection();
                        var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                    admitted.setAutoCommit(false);
                    probe(admitted, business, tenant, second, fiscal, module, secondContext);
                    var started = new java.util.concurrent.CountDownLatch(1);
                    var suspended = executor.submit(() -> {
                        try (var connection = fixture.role(JdbcTenantAccess.ROLE).getConnection()) {
                            com.lssoluciones.lscore.platform.PlatformDatabase.begin(connection, JdbcTenantAccess.ROLE, false);
                            started.countDown();
                            command(connection, owner, tenant, member, null, "MEMBER_SUSPEND", snapshot.updatedAt());
                            connection.commit();
                            return true;
                        }
                    });
                    assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
                    Thread.sleep(150);
                    assertTrue(!suspended.isDone());
                    admitted.commit();
                    assertTrue(suspended.get(8, java.util.concurrent.TimeUnit.SECONDS));
                }
                assertEquals("SUSPENDED", access.access(browser.token(LoginApplication.CORE), tenant, member).status());
                revoked(admin, matrixContext, true);
                revoked(admin, secondContext, true);
                revoked(admin, foreignContext, false);
                revoked(admin, otherContext, false);
                assertEquals(200, foreignBrowser.get(LoginApplication.FISCAL, "context").statusCode());
                assertEquals(200, otherUser.get(LoginApplication.FISCAL, "context").statusCode());
                for (var session : new ApplicationLoginPostgresTest.Browser[] {matrixBrowser, secondBrowser, foreignBrowser, otherUser}) {
                    assertEquals(200, session.get(LoginApplication.FISCAL, "session").statusCode());
                }
                deniedProbe(fixture, business, tenant, second, fiscal, module, secondContext);
                assertEquals(403, secondBrowser.post(LoginApplication.FISCAL, "context", secondBrowser.csrf(LoginApplication.FISCAL), selection(tenant, second)).statusCode());
                assertEquals(otherMembership, scalar(admin, "SELECT row_to_json(m)::text FROM tenant.memberships m WHERE id='" + foreignMember + "'"));
                assertEquals(200, post(browser, path + "/access/reactivate", change(UUID.randomUUID(), access.access(browser.token(LoginApplication.CORE), tenant, member).updatedAt(), password)).statusCode());
                revoked(admin, secondContext, true);
                UUID renewedMatrix = select(matrixBrowser, tenant, matrix);
                UUID renewedSecond = select(secondBrowser, tenant, second);
                assertNotEquals(secondContext, renewedSecond);
                assertEquals(consent, scalar(admin, "SELECT json_agg(r ORDER BY id)::text FROM tenant.identity_link_requests r WHERE user_id='" + business + "'"));
                var branchAccess = access.access(browser.token(LoginApplication.CORE), tenant, member).branches().stream().filter(value -> value.branchId().equals(second)).findFirst().orElseThrow();
                String branchPath = path + "/branch-accesses/" + branchAccess.id();
                UUID suspension = UUID.randomUUID();
                String branchCommand = change(suspension, branchAccess.updatedAt(), password);
                assertEquals(200, post(browser, branchPath + "/suspend", branchCommand).statusCode());
                assertEquals(200, post(browser, branchPath + "/suspend", branchCommand).statusCode());
                assertEquals(409, post(browser, branchPath + "/suspend", change(UUID.randomUUID(), branchAccess.updatedAt(), password)).statusCode());
                revoked(admin, renewedSecond, true);
                revoked(admin, renewedMatrix, false);
                revoked(admin, otherContext, false);
                revoked(admin, foreignContext, false);
                assertEquals("ACTIVE", scalar(admin, "SELECT status FROM tenant.branches WHERE id='" + second + "'"));
                assertEquals(200, matrixBrowser.get(LoginApplication.FISCAL, "context").statusCode());
                assertEquals(200, otherUser.get(LoginApplication.FISCAL, "context").statusCode());
                // CLOSED/global SUSPENDED branches cannot be re-enabled by an individual access action.
                sql.execute("UPDATE tenant.branches SET status='CLOSED' WHERE id='" + second + "'");
                assertEquals(422, post(browser, branchPath + "/reactivate", change(UUID.randomUUID(), stamp(admin, "tenant.branch_memberships", branchAccess.id()), password)).statusCode());
                assertEquals(403, secondBrowser.post(LoginApplication.FISCAL, "context", secondBrowser.csrf(LoginApplication.FISCAL), selection(tenant, second)).statusCode());
                sql.execute("UPDATE tenant.branches SET status='ACTIVE' WHERE id='" + second + "'");
                assertEquals(200, post(browser, branchPath + "/reactivate", change(UUID.randomUUID(), stamp(admin, "tenant.branch_memberships", branchAccess.id()), password)).statusCode());
                revoked(admin, renewedSecond, true);
                select(secondBrowser, tenant, second);
                // A confirmed suspension blocks a context selection already waiting at F06.
                try (var first = fixture.role(JdbcTenantAccess.ROLE).getConnection();
                        var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                    com.lssoluciones.lscore.platform.PlatformDatabase.begin(first, JdbcTenantAccess.ROLE, false);
                    command(first, owner, tenant, member, branchAccess.id(), "BRANCH_SUSPEND", stamp(admin, "tenant.branch_memberships", branchAccess.id()));
                    var started = new java.util.concurrent.CountDownLatch(1);
                    var pendingSelection = executor.submit(() -> {
                        started.countDown();
                        return contexts.select(LoginApplication.FISCAL, secondBrowser.token(LoginApplication.FISCAL), tenant, second);
                    });
                    assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
                    Thread.sleep(150);
                    assertTrue(!pendingSelection.isDone());
                    first.commit();
                    var rejected = assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> pendingSelection.get(8, java.util.concurrent.TimeUnit.SECONDS));
                    assertTrue(rejected.getCause() instanceof JdbcSessionContexts.Denied);
                }
                // Two actors and two reactivations use the same resource version: one commit, one conflict.
                race(fixture, admin, owner, initial, tenant, member, branchAccess.id(), "BRANCH_REACTIVATE", stamp(admin, "tenant.branch_memberships", branchAccess.id()), "P3001");
                race(fixture, admin, owner, initial, tenant, member, null, "MEMBER_SUSPEND", stamp(admin, "tenant.memberships", member), "P3001");
                race(fixture, admin, owner, initial, tenant, member, null, "MEMBER_REACTIVATE", stamp(admin, "tenant.memberships", member), "P3001");
                // Rollback includes the membership, context revocation and success receipt.
                UUID live = select(secondBrowser, tenant, second);
                sql.execute("CREATE FUNCTION audit.fail_access_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action_code LIKE 'TENANT_ACCESS_%' AND NEW.outcome='SUCCESS' THEN RAISE EXCEPTION 'Owned failure'; END IF; RETURN NEW; END $$");
                sql.execute("CREATE TRIGGER fail_access_fixture BEFORE INSERT ON audit.events FOR EACH ROW EXECUTE FUNCTION audit.fail_access_fixture()");
                UUID rollback = UUID.randomUUID();
                assertEquals(503, post(browser, path + "/access/suspend", change(rollback, stamp(admin, "tenant.memberships", member), password)).statusCode());
                sql.execute("DROP TRIGGER fail_access_fixture ON audit.events");
                sql.execute("DROP FUNCTION audit.fail_access_fixture()");
                revoked(admin, live, false);
                assertEquals("ACTIVE", scalar(admin, "SELECT status FROM tenant.memberships WHERE id='" + member + "'"));
                assertEquals("0", scalar(admin, "SELECT count(*) FROM tenant.access_operations WHERE request_id='" + rollback + "'"));
                UUID lost = UUID.randomUUID();
                var uncertain = new JdbcTenantAccess(server.getBean(JdbcApplicationSessions.class), authentication,
                        uncertain(fixture.role(JdbcTenantAccess.ROLE)), fixture.role(JdbcTenantAccess.LOG_ROLE));
                assertEquals("UNKNOWN", uncertain.mutate(browser.token(LoginApplication.CORE), tenant, lost,
                        JdbcTenantAccess.Action.MEMBER_SUSPEND, member, null, stamp(admin, "tenant.memberships", member), password).outcome());
                assertEquals("CONFIRMED", access.find(browser.token(LoginApplication.CORE), lost).outcome());
                assertNull(access.find(holder.token(LoginApplication.CORE), lost));
                revoked(admin, live, true);
                sql.execute("UPDATE identity.user_credentials SET locked_until=clock_timestamp()+interval '1 hour' WHERE user_id='" + business + "'");
                assertEquals(422, post(browser, path + "/access/reactivate", change(UUID.randomUUID(), stamp(admin, "tenant.memberships", member), password)).statusCode());
                sql.execute("UPDATE identity.user_credentials SET locked_until=NULL WHERE user_id='" + business + "'");
                assertEquals(200, post(browser, "tenants/" + tenant + "/suspend", operation(password)).statusCode());
                assertEquals(422, post(browser, path + "/access/reactivate", change(UUID.randomUUID(), stamp(admin, "tenant.memberships", member), password)).statusCode());
                assertEquals(200, post(browser, "tenants/" + tenant + "/reactivate", operation(password)).statusCode());
                assertEquals(200, post(browser, path + "/access/reactivate", change(UUID.randomUUID(), stamp(admin, "tenant.memberships", member), password)).statusCode());
                UUID pendingUser = definitive(registrar, lifecycle, "f48-pending", password);
                sql.execute("INSERT INTO tenant.memberships(tenant_id,user_id,status) VALUES('" + tenant + "','" + pendingUser + "','PENDING')");
                UUID pendingMember = UUID.fromString(scalar(admin, "SELECT id FROM tenant.memberships WHERE tenant_id='" + tenant + "' AND user_id='" + pendingUser + "'"));
                assertEquals(409, post(browser, "tenant-users/tenants/" + tenant + "/users/" + pendingMember + "/access/reactivate", change(UUID.randomUUID(), stamp(admin, "tenant.memberships", pendingMember), password)).statusCode());
                UUID administratorMember = UUID.fromString(scalar(admin, "SELECT id FROM tenant.memberships WHERE tenant_id='" + tenant + "' AND user_id='" + initial + "'"));
                sql.execute("INSERT INTO tenant.memberships(tenant_id,user_id,status) VALUES('" + tenant + "','" + owner + "','ACTIVE')");
                UUID ownerMember = UUID.fromString(scalar(admin, "SELECT id FROM tenant.memberships WHERE tenant_id='" + tenant + "' AND user_id='" + owner + "'"));
                assertEquals(409, post(browser, "tenant-users/tenants/" + tenant + "/users/" + ownerMember + "/access/suspend", change(UUID.randomUUID(), stamp(admin, "tenant.memberships", ownerMember), password)).statusCode());
                assertEquals(409, post(browser, "tenant-users/tenants/" + tenant + "/users/" + administratorMember + "/access/suspend", change(UUID.randomUUID(), stamp(admin, "tenant.memberships", administratorMember), password)).statusCode());
                assertEquals("1", scalar(admin, "SELECT count(*) FROM tenant.valid_administrators('" + tenant + "')"));
                // Two concurrent attempts against the last administrator are both rejected in PostgreSQL.
                try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                    var version = stamp(admin, "tenant.memberships", administratorMember);
                    var one = executor.submit(() -> raw(fixture, owner, tenant, administratorMember, null, "MEMBER_SUSPEND", version));
                    var two = executor.submit(() -> raw(fixture, initial, tenant, administratorMember, null, "MEMBER_SUSPEND", version));
                    assertEquals("P3001", one.get(8, java.util.concurrent.TimeUnit.SECONDS));
                    assertEquals("P3001", two.get(8, java.util.concurrent.TimeUnit.SECONDS));
                }
                // V24 revocation commits first; a waiting former administrator cannot mutate afterward.
                assertEquals(200, post(browser, "tenants/" + tenant + "/administrators/designate", linkRequest(UUID.randomUUID(), "f48-outsider", password)).statusCode());
                UUID designation = UUID.fromString(scalar(admin, "SELECT id FROM tenant.administrators WHERE tenant_id='" + tenant + "' AND membership_id='" + administratorMember + "' AND revoked_at IS NULL"));
                try (var first = fixture.role(JdbcTenantAdministrators.ROLE).getConnection();
                        var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                    com.lssoluciones.lscore.platform.PlatformDatabase.begin(first, JdbcTenantAdministrators.ROLE, false);
                    try (var statement = first.createStatement()) {
                        statement.execute("INSERT INTO tenant.administrator_operations(request_id,actor_user_id,tenant_id,action,target_id,designation_id,resulting_status) VALUES(gen_random_uuid(),'" + owner + "','" + tenant + "','REVOKE','" + designation + "','" + designation + "','REVOKED')");
                    }
                    var version = stamp(admin, "tenant.memberships", member);
                    var denied = executor.submit(() -> raw(fixture, initial, tenant, member, null, "MEMBER_SUSPEND", version));
                    Thread.sleep(150);
                    assertTrue(!denied.isDone());
                    first.commit();
                    assertEquals("42501", denied.get(8, java.util.concurrent.TimeUnit.SECONDS));
                }
                String retiredPath = "tenant-users/tenants/" + tenant + "/users/" + administratorMember + "/access/";
                assertEquals(200, post(browser, retiredPath + "suspend", change(UUID.randomUUID(), stamp(admin, "tenant.memberships", administratorMember), password)).statusCode());
                assertEquals(200, post(browser, retiredPath + "reactivate", change(UUID.randomUUID(), stamp(admin, "tenant.memberships", administratorMember), password)).statusCode());
                assertEquals("0", scalar(admin, "SELECT count(*) FROM tenant.valid_administrators('" + tenant + "') WHERE user_id='" + initial + "'"));
                // Membership and tenant suspension share the exact writer barrier and lock order.
                try (var first = fixture.role(JdbcTenantAccess.ROLE).getConnection();
                        var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                    com.lssoluciones.lscore.platform.PlatformDatabase.begin(first, JdbcTenantAccess.ROLE, false);
                    command(first, owner, tenant, member, null, "MEMBER_SUSPEND", stamp(admin, "tenant.memberships", member));
                    var tenantSuspension = executor.submit(() -> post(browser, "tenants/" + tenant + "/suspend", operation(password)).statusCode());
                    Thread.sleep(150);
                    assertTrue(!tenantSuspension.isDone());
                    first.commit();
                    assertEquals(200, tenantSuspension.get(8, java.util.concurrent.TimeUnit.SECONDS));
                }
                assertEquals(otherMembership, scalar(admin, "SELECT row_to_json(m)::text FROM tenant.memberships m WHERE id='" + foreignMember + "'"));
                assertEquals(consent, scalar(admin, "SELECT json_agg(r ORDER BY id)::text FROM tenant.identity_link_requests r WHERE user_id='" + business + "'"));
                revoked(admin, foreignContext, false);
                assertEquals("t", scalar(admin, "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid='tenant.access_operations'::regclass"));
                for (String role : new String[] {JdbcTenantAccess.ROLE, JdbcTenantUsers.ROLE, "ls_core_app_dev"}) {
                    try (var connection = fixture.role(role).getConnection(); var statement = connection.createStatement()) {
                        assertThrows(SQLException.class, () -> statement.execute("UPDATE tenant.memberships SET status='ACTIVE' WHERE id='" + member + "'"));
                        assertThrows(SQLException.class, () -> statement.execute("UPDATE security.session_contexts SET revoked_at=NULL WHERE id='" + live + "'"));
                        assertThrows(SQLException.class, () -> statement.execute("DELETE FROM tenant.access_operations WHERE request_id='" + lost + "'"));
                    }
                }
                assertThrows(SQLException.class, () -> sql.execute("UPDATE audit.events SET metadata='{}'::jsonb WHERE action_code='TENANT_ACCESS_MEMBER_SUSPEND'"));
                assertEquals("0", scalar(admin, "SELECT count(*) FROM tenant.access_operations o WHERE NOT EXISTS(SELECT FROM audit.events e WHERE e.correlation_id=o.request_id AND e.action_code='TENANT_ACCESS_'||o.action AND e.outcome='SUCCESS')"));
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static java.time.Instant stamp(java.sql.Connection admin, String table, UUID id) throws Exception {
        return java.time.OffsetDateTime.parse(scalar(admin, "SELECT to_char(updated_at AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') FROM " + table + " WHERE id='" + id + "'")).toInstant();
    }

    private static String change(UUID request, java.time.Instant expected, char[] password) {
        return "{\"requestId\":\"" + request + "\",\"expectedUpdatedAt\":\"" + expected + "\",\"password\":\"" + new String(password) + "\"}";
    }

    private static UUID branch(ApplicationLoginPostgresTest.Browser browser, UUID tenant, String name, char[] password) throws Exception {
        var result = post(browser, "branch-administration/tenants/" + tenant + "/branches", "{\"requestId\":\"" + UUID.randomUUID() + "\",\"code\":\"" + name + "\",\"name\":\"" + name + "\",\"password\":\"" + new String(password) + "\"}");
        assertEquals(201, result.statusCode());
        return id(result.body());
    }

    private static ApplicationLoginPostgresTest.Browser fiscalLogin(HttpClient client, URI base, String username, char[] password) throws Exception {
        var browser = new ApplicationLoginPostgresTest.Browser(client, base);
        assertEquals(204, browser.post(LoginApplication.FISCAL, "login", browser.csrf(LoginApplication.FISCAL), credentials(username, password)).statusCode());
        return browser;
    }

    private static String selection(UUID tenant, UUID branch) {
        return "{\"tenantId\":\"" + tenant + "\",\"branchId\":\"" + branch + "\"}";
    }

    private static UUID select(ApplicationLoginPostgresTest.Browser browser, UUID tenant, UUID branch) throws Exception {
        var result = browser.post(LoginApplication.FISCAL, "context", browser.csrf(LoginApplication.FISCAL), selection(tenant, branch));
        assertEquals(200, result.statusCode());
        return id(result.body());
    }

    private static void assignments(java.sql.Statement sql, UUID actor, UUID tenant, UUID app, UUID module, UUID... branches) throws SQLException {
        for (UUID branch : branches) {
            sql.execute("INSERT INTO tenant.branch_memberships(tenant_id,membership_id,branch_id,status) SELECT tenant_id,id,'" + branch + "','ACTIVE' FROM tenant.memberships WHERE tenant_id='" + tenant + "' AND user_id='" + actor + "'");
            sql.execute("INSERT INTO security.branch_operation_grants SELECT tenant_id,id,'" + branch + "','" + app + "','" + module + "','WRITE_PROBE',clock_timestamp()+interval '1 hour' FROM tenant.memberships WHERE tenant_id='" + tenant + "' AND user_id='" + actor + "'");
        }
    }

    private static void revoked(java.sql.Connection admin, UUID context, boolean expected) throws Exception {
        assertEquals(expected ? "t" : "f", scalar(admin, "SELECT revoked_at IS NOT NULL FROM security.session_contexts WHERE id='" + context + "'"));
    }

    private static void command(java.sql.Connection connection, UUID actor, UUID tenant, UUID member, UUID branch, String action, java.time.Instant version) throws SQLException {
        try (var query = connection.prepareStatement("INSERT INTO tenant.access_operations(request_id,actor_user_id,tenant_id,membership_id,branch_membership_id,action,expected_updated_at) VALUES(?,?,?,?,?,?,?)")) {
            query.setObject(1, UUID.randomUUID());
            query.setObject(2, actor);
            query.setObject(3, tenant);
            query.setObject(4, member);
            query.setObject(5, branch);
            query.setString(6, action);
            query.setTimestamp(7, java.sql.Timestamp.from(version));
            query.executeUpdate();
        }
    }

    private static String raw(ApplicationLoginPostgresTest fixture, UUID actor, UUID tenant, UUID member, UUID branch, String action, java.time.Instant version) throws Exception {
        try (var connection = fixture.role(JdbcTenantAccess.ROLE).getConnection()) {
            com.lssoluciones.lscore.platform.PlatformDatabase.begin(connection, JdbcTenantAccess.ROLE, false);
            try {
                command(connection, actor, tenant, member, branch, action, version);
                connection.commit();
                return "CONFIRMED";
            } catch (SQLException failure) {
                connection.rollback();
                return failure.getSQLState();
            }
        }
    }

    private static void race(ApplicationLoginPostgresTest fixture, java.sql.Connection admin, UUID actor, UUID secondActor, UUID tenant, UUID member, UUID branch, String action, java.time.Instant version, String expected) throws Exception {
        try (var first = fixture.role(JdbcTenantAccess.ROLE).getConnection(); var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            com.lssoluciones.lscore.platform.PlatformDatabase.begin(first, JdbcTenantAccess.ROLE, false);
            command(first, actor, tenant, member, branch, action, version);
            assertNotEquals(scalar(admin, "SELECT pg_backend_pid()"), scalar(first, "SELECT pg_backend_pid()"));
            var pending = executor.submit(() -> raw(fixture, secondActor, tenant, member, branch, action, version));
            Thread.sleep(150);
            assertTrue(!pending.isDone());
            first.commit();
            assertEquals(expected, pending.get(8, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private static void probe(java.sql.Connection connection, UUID actor, UUID tenant, UUID branch, UUID app, UUID module, UUID context) throws SQLException {
        com.lssoluciones.lscore.access.infrastructure.jdbc.BranchOperationGuard.probe(connection, UUID.randomUUID(), actor, tenant, branch, app, module, context, null);
    }

    private static void deniedProbe(ApplicationLoginPostgresTest fixture, UUID actor, UUID tenant, UUID branch, UUID app, UUID module, UUID context) throws Exception {
        try (var connection = fixture.role("ls_core_branch_guard_dev").getConnection()) {
            connection.setAutoCommit(false);
            assertEquals("42501", assertThrows(SQLException.class, () -> probe(connection, actor, tenant, branch, app, module, context)).getSQLState());
            connection.rollback();
        }
    }
    private static String scalar(java.sql.Connection connection, String query) throws Exception {
        return ApplicationLoginPostgresTest.scalar(connection, query);
    }

    private static String linkRequest(UUID request, String username, char[] password) {
        return "{\"requestId\":\"" + request + "\",\"username\":"
                + (username == null ? "null" : "\"" + username + "\"")
                + ",\"password\":\"" + new String(password) + "\"}";
    }

    private static HttpResponse<String> post(
            ApplicationLoginPostgresTest.Browser browser, String path, String body)
            throws Exception {
        return browser.post(LoginApplication.CORE, path, browser.csrf(LoginApplication.CORE), body);
    }

    private static UUID company(
            ApplicationLoginPostgresTest.Browser browser, UUID initial, char[] password)
            throws Exception {
        var created =
                post(
                        browser,
                        "registrations",
                        payload(UUID.randomUUID(), initial, "Users tenant", password));
        assertEquals(200, created.statusCode());
        UUID tenant = id(created.body());
        assertEquals(
                200,
                post(browser, "tenants/" + tenant + "/activate", operation(password)).statusCode());
        return tenant;
    }

    private static javax.sql.DataSource uncertain(javax.sql.DataSource original) {
        return (javax.sql.DataSource)
                java.lang.reflect.Proxy.newProxyInstance(
                        javax.sql.DataSource.class.getClassLoader(),
                        new Class<?>[] {javax.sql.DataSource.class},
                        (proxy, method, arguments) -> {
                            try {
                                Object value = method.invoke(original, arguments);
                                if (value instanceof java.sql.Connection connection) {
                                    return java.lang.reflect.Proxy.newProxyInstance(
                                            java.sql.Connection.class.getClassLoader(),
                                            new Class<?>[] {java.sql.Connection.class},
                                            (nested, call, parameters) -> {
                                                try {
                                                    Object result =
                                                            call.invoke(connection, parameters);
                                                    if ("commit".equals(call.getName())) {
                                                        throw new SQLException(
                                                                "Owned lost commit reply", "08006");
                                                    }
                                                    return result;
                                                } catch (
                                                    java.lang.reflect.InvocationTargetException
                                                                failure) {
                                                    throw failure.getCause();
                                                }
                                            });
                                }
                                return value;
                            } catch (java.lang.reflect.InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
    }

    private static String operation(char[] password) {
        return "{\"requestId\":\""
                + UUID.randomUUID()
                + "\",\"password\":\""
                + new String(password)
                + "\"}";
    }

    private static String payload(UUID request, UUID initial, String name, char[] password) {
        return "{\"requestId\":\""
                + request
                + "\",\"name\":\""
                + name
                + "\",\"administratorId\":\""
                + initial
                + "\",\"password\":\""
                + new String(password)
                + "\"}";
    }

    private static String credentials(String username, char[] password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + new String(password) + "\"}";
    }

    private static UUID id(String json) {
        var match = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(json);
        assertTrue(match.find());
        return UUID.fromString(match.group(1));
    }

    private static UUID definitive(
            JdbcManualIdentityRegistration registration,
            JdbcPasswordLifecycle lifecycle,
            String username,
            char[] password) {
        UUID id =
                registration.register(
                        new ManualIdentityInput(username, "Owned registration fixture", null));
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

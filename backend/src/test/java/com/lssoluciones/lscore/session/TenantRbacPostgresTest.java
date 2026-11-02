package com.lssoluciones.lscore.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import tools.jackson.databind.json.JsonMapper;

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
class TenantRbacPostgresTest {
    @Test
    void platformBoundedCatalogsTemplatesCompositionAndConcurrency() throws Exception {
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
            UUID owner = definitive(registrar, lifecycle, "f49-owner", password);
            UUID business = definitive(registrar, lifecycle, "f49-business", password);
            UUID delegate = definitive(registrar, lifecycle, "f49-delegate", password);
            UUID initial = definitive(registrar, lifecycle, "f49-initial", password);
            UUID outsider = definitive(registrar, lifecycle, "f49-outsider", password);
            var authentication =
                    new JdbcPasswordAuthentication(
                            fixture.role(JdbcPasswordAuthentication.ROLE),
                            JdbcPasswordAuthentication.Limits.defaults());
            new JdbcPlatformBootstrap(fixture.role(JdbcPlatformBootstrap.ROLE), authentication)
                    .initialize("f49-owner", password);
            var global =
                    new JdbcPlatformExecutor(
                            fixture.role(JdbcPlatformExecutor.ROLE),
                            fixture.role(JdbcPlatformExecutor.SECURITY_LOG_ROLE),
                            authentication);
            assertEquals(
                    JdbcPlatformExecutor.Outcome.CONFIRMED,
                    global.execute(
                                    "f49-owner",
                                    password,
                                    delegate,
                                    JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN)
                            .outcome());
            UUID fiscal = UUID.randomUUID();
            UUID pos = UUID.randomUUID();
            sql.execute(
                    "INSERT INTO catalog.applications(id,code,name,status) VALUES ('"
                            + fiscal
                            + "','f49-fiscal','Fiscal fixture','PUBLISHED'),('"
                            + pos
                            + "','f49-pos','POS fixture','PUBLISHED')");
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
            properties.put("ls.core.tenant-rbac.enabled", "true");
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
                                    Map.entry("LS_TENANT_RBAC_DB_", JdbcTenantRbac.ROLE),
                                    Map.entry("LS_TENANT_RBAC_LOG_DB_", JdbcTenantRbac.LOG_ROLE),
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
                                credentials("f49-owner", password));
                assertEquals(204, logged.statusCode());
                assertEquals(
                        200, browser.get(LoginApplication.CORE, "tenant-authority").statusCode());
                UUID tenant = company(browser, initial, password);
                UUID other = company(browser, outsider, password);
                UUID app = UUID.fromString(core);
                UUID read = UUID.fromString("8322b61e-868e-51a6-bb15-37cb7bb99086");
                UUID assignmentRead = UUID.fromString("edbd064d-8215-5e32-8d03-82cc108b3d6d");
                UUID write = UUID.fromString("48520524-890b-5d11-ab98-14716a11ecab");
                UUID roleTemplate = UUID.fromString("a801d124-aa58-5055-bcbd-af8280cf19dd");
                UUID profileTemplate = UUID.fromString("a2ff7d6a-ded6-5fdc-81e7-8e103dde3f77");
                UUID editorTemplate = UUID.fromString("69cdb6db-a07e-5e5f-8d25-a6cf6410b0c8");
                var rbac = server.getBean(JdbcTenantRbac.class);
                var initialBrowser = new ApplicationLoginPostgresTest.Browser(client, base);
                var foreign = new ApplicationLoginPostgresTest.Browser(client, base);
                var delegated = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(204, post(initialBrowser, "login", credentials("f49-initial", password)).statusCode());
                assertEquals(204, post(foreign, "login", credentials("f49-outsider", password)).statusCode());
                assertEquals(204, post(delegated, "login", credentials("f49-delegate", password)).statusCode());
                String path = "rbac/tenants/" + tenant + "/applications/" + app;
                String foreignPath = "rbac/tenants/" + other + "/applications/" + app;
                for (UUID company : new UUID[] {tenant, other}) {
                    sql.execute("INSERT INTO catalog.tenant_applications(tenant_id,application_id,status) VALUES('" + company + "','" + app + "','ACTIVE')");
                }
                assertEquals(200, browser.get(LoginApplication.CORE, "rbac/tenants").statusCode());
                assertEquals(200, browser.get(LoginApplication.CORE, "rbac/tenants/" + tenant + "/applications").statusCode());
                assertEquals(200, browser.get(LoginApplication.CORE, path + "/permissions").statusCode());
                assertEquals(9, rbac.workspace(browser.token(LoginApplication.CORE), tenant, app).permissions().size());
                assertTrue(rbac.workspace(browser.token(LoginApplication.CORE), tenant, app).permissions().stream().noneMatch(JdbcTenantRbac.Permission::delegable));
                assertEquals(403, submit(browser, path, JdbcTenantRbac.Action.ROLE_CREATE, UUID.randomUUID(), role("no-ceiling", read), password).statusCode());
                grantPermission(sql, tenant, app, read, "READ");
                assertEquals(403, submit(browser, path, JdbcTenantRbac.Action.ROLE_CREATE, UUID.randomUUID(), role("module-not-enabled", read), password).statusCode());
                for (UUID company : new UUID[] {tenant, other}) {
                    sql.execute("INSERT INTO catalog.tenant_modules(tenant_id,application_id,module_id,status) SELECT '" + company + "',application_id,id,'ACTIVE' FROM catalog.modules WHERE application_id='" + app + "'");
                }
                var valid = role("custom-reader", read);
                UUID created = UUID.randomUUID();
                assertEquals(403, browser.post(LoginApplication.CORE, path + "/commands/role-create", null, body(created, valid, password)).statusCode());
                assertEquals(403, submit(browser, path, JdbcTenantRbac.Action.ROLE_CREATE, created, valid, "Incorrect-49!".toCharArray()).statusCode());
                var accepted = submit(browser, path, JdbcTenantRbac.Action.ROLE_CREATE, created, valid, password);
                assertEquals(200, accepted.statusCode());
                UUID custom = resource(accepted);
                assertEquals(200, submit(browser, path, JdbcTenantRbac.Action.ROLE_CREATE, created, valid, password).statusCode());
                assertEquals(409, submit(browser, path, JdbcTenantRbac.Action.ROLE_CREATE, created, role("different", read), password).statusCode());
                assertEquals(409, submit(browser, path, JdbcTenantRbac.Action.ROLE_CREATE, UUID.randomUUID(), role(" CUSTOM-READER ", read), password).statusCode());
                assertEquals(403, submit(delegated, path, JdbcTenantRbac.Action.ROLE_CREATE, UUID.randomUUID(), valid, password).statusCode());
                assertEquals(403, submit(initialBrowser, path, JdbcTenantRbac.Action.ROLE_CREATE, UUID.randomUUID(), valid, password).statusCode());
                assertEquals(403, foreign.get(LoginApplication.CORE, path).statusCode());
                assertEquals(403, submit(browser, path, JdbcTenantRbac.Action.PROFILE_TEMPLATE, UUID.randomUUID(), template(profileTemplate), password).statusCode());
                assertEquals("1", scalar(admin, "SELECT count(*) FROM security.roles WHERE tenant_id='" + tenant + "'"));
                grantPermission(sql, tenant, app, assignmentRead, "READ");
                var initialized = submit(browser, path, JdbcTenantRbac.Action.PROFILE_TEMPLATE, UUID.randomUUID(), template(profileTemplate), password);
                assertEquals(200, initialized.statusCode());
                UUID templateProfile = resource(initialized);
                var templateRoleResult = submit(browser, path, JdbcTenantRbac.Action.ROLE_TEMPLATE, UUID.randomUUID(), template(roleTemplate), password);
                assertEquals(200, templateRoleResult.statusCode());
                UUID templateRole = resource(templateRoleResult);
                assertEquals(200, submit(browser, path, JdbcTenantRbac.Action.PROFILE_TEMPLATE, UUID.randomUUID(), template(profileTemplate), password).statusCode());
                assertEquals("2", scalar(admin, "SELECT count(*) FROM security.roles WHERE tenant_id='" + tenant + "'"));
                assertEquals("1", scalar(admin, "SELECT source_template_version FROM security.roles WHERE id='" + templateRole + "'"));
                assertEquals(roleTemplate.toString(), scalar(admin, "SELECT source_role_template_id FROM security.roles WHERE id='" + templateRole + "'"));
                assertEquals("2", scalar(admin, "SELECT count(*) FROM security.role_permissions WHERE role_id='" + templateRole + "'"));
                assertTrue(!rbac.history(browser.token(LoginApplication.CORE), tenant, app, templateRole).isEmpty());
                assertEquals(200, browser.get(LoginApplication.CORE, path + "/profiles/" + templateProfile + "/roles").statusCode());
                assertEquals(200, browser.get(LoginApplication.CORE, path + "/roles/" + templateRole + "/permissions").statusCode());
                assertEquals(403, submit(browser, path, JdbcTenantRbac.Action.ROLE_CREATE, UUID.randomUUID(), role("not-delegable", write), password).statusCode());
                sql.execute("INSERT INTO platform.rbac_permission_delegations SELECT '" + tenant + "',application_id,id,CASE WHEN code IN ('roles.manage','profiles.manage','assignments.manage') THEN 'WRITE' ELSE 'READ' END,clock_timestamp()+interval '1 hour' FROM security.permissions WHERE application_id='" + app + "' AND id NOT IN ('" + read + "','" + assignmentRead + "')");
                var editInitialized = submit(browser, path, JdbcTenantRbac.Action.ROLE_TEMPLATE, UUID.randomUUID(), template(editorTemplate), password);
                assertEquals(200, editInitialized.statusCode());
                UUID editorRole = resource(editInitialized);
                assertEquals("7", scalar(admin, "SELECT count(*) FROM security.role_permissions WHERE role_id='" + editorRole + "'"));
                // A declared roles.manage permission cannot confer the independent administrative capability.
                assertEquals(403, submit(initialBrowser, path, JdbcTenantRbac.Action.ROLE_CREATE, UUID.randomUUID(), role("no-self-elevation", read), password).statusCode());
                UUID initialDesignation = UUID.fromString(scalar(admin, "SELECT a.id FROM tenant.administrators a JOIN tenant.memberships m ON m.tenant_id=a.tenant_id AND m.id=a.membership_id WHERE a.tenant_id='" + tenant + "' AND m.user_id='" + initial + "' AND a.revoked_at IS NULL"));
                UUID foreignDesignation = UUID.fromString(scalar(admin, "SELECT a.id FROM tenant.administrators a JOIN tenant.memberships m ON m.tenant_id=a.tenant_id AND m.id=a.membership_id WHERE a.tenant_id='" + other + "' AND m.user_id='" + outsider + "' AND a.revoked_at IS NULL"));
                grantAdministrator(sql, tenant, app, initialDesignation);
                grantAdministrator(sql, other, app, foreignDesignation);
                grantPermission(sql, other, app, read, "READ");
                assertEquals(200, submit(initialBrowser, path, JdbcTenantRbac.Action.ROLE_CREATE, UUID.randomUUID(), role("admin-created", read), password).statusCode());
                var foreignResult = submit(foreign, foreignPath, JdbcTenantRbac.Action.ROLE_CREATE, UUID.randomUUID(), role("other-tenant", read), password);
                assertEquals(200, foreignResult.statusCode());
                UUID foreignRole = resource(foreignResult);
                assertEquals(403, submit(foreign, path, JdbcTenantRbac.Action.ROLE_CREATE, UUID.randomUUID(), role("foreign-authority", read), password).statusCode());
                assertEquals(403, submit(browser, path, JdbcTenantRbac.Action.PROFILE_CREATE, UUID.randomUUID(), profile("foreign-role", foreignRole), password).statusCode());
                assertEquals(422, submit(browser, "rbac/tenants/" + tenant + "/applications/" + fiscal, JdbcTenantRbac.Action.ROLE_CREATE, UUID.randomUUID(), role("disabled-application", read), password).statusCode());
                sql.execute("INSERT INTO catalog.tenant_applications(tenant_id,application_id,status) VALUES('" + tenant + "','" + fiscal + "','ACTIVE')");
                assertEquals(403, submit(browser, "rbac/tenants/" + tenant + "/applications/" + fiscal, JdbcTenantRbac.Action.ROLE_CREATE, UUID.randomUUID(), role("wrong-application-permission", read), password).statusCode());
                sql.execute("UPDATE security.permissions SET status='DRAFT' WHERE id='" + read + "'");
                assertEquals(403, submit(browser, path, JdbcTenantRbac.Action.ROLE_CREATE, UUID.randomUUID(), role("not-published", read), password).statusCode());
                sql.execute("UPDATE security.permissions SET status='PUBLISHED' WHERE id='" + read + "'");
                sql.execute("UPDATE security.role_templates SET status='DRAFT' WHERE id='" + roleTemplate + "'");
                assertEquals(403, submit(browser, path, JdbcTenantRbac.Action.ROLE_TEMPLATE, UUID.randomUUID(), template(roleTemplate), password).statusCode());
                sql.execute("UPDATE security.role_templates SET status='PUBLISHED' WHERE id='" + roleTemplate + "'");
                sql.execute("UPDATE catalog.tenant_modules SET status='READ_ONLY' WHERE tenant_id='" + tenant + "' AND application_id='" + app + "' AND module_id=(SELECT module_id FROM security.permissions WHERE id='" + write + "')");
                assertEquals(403, submit(browser, path, JdbcTenantRbac.Action.ROLE_CREATE, UUID.randomUUID(), role("read-only-write", write), password).statusCode());
                assertEquals(200, submit(browser, path, JdbcTenantRbac.Action.ROLE_CREATE, UUID.randomUUID(), role("read-only-read", assignmentRead), password).statusCode());
                sql.execute("UPDATE catalog.tenant_modules SET status='ACTIVE' WHERE tenant_id='" + tenant + "' AND application_id='" + app + "'");
                var composed = submit(browser, path, JdbcTenantRbac.Action.PROFILE_CREATE, UUID.randomUUID(), profile("multiple-roles", custom, editorRole), password);
                assertEquals(200, composed.statusCode());
                UUID composedProfile = resource(composed);
                assertEquals("2", scalar(admin, "SELECT count(*) FROM security.profile_roles WHERE profile_id='" + composedProfile + "'"));
                assertEquals(409, submit(browser, path, JdbcTenantRbac.Action.ROLE_DISABLE, UUID.randomUUID(), disable(custom, 1), password).statusCode());
                assertEquals(409, submit(browser, path, JdbcTenantRbac.Action.ROLE_UPDATE, UUID.randomUUID(), update(custom, 0, "custom-reader", read), password).statusCode());
                assertEquals("0", scalar(admin, "SELECT count(*) FROM security.branch_profile_assignments"));
                assertEquals("0", scalar(admin, "SELECT count(*) FROM tenant.branch_memberships"));
                // Competing role codes, identical request IDs and profile creations use physical JDBC sessions.
                race(fixture, owner, initial, tenant, app, JdbcTenantRbac.Action.ROLE_CREATE, role("race-code", read),
                        JdbcTenantRbac.Action.ROLE_CREATE, role(" RACE-CODE ", read), "23505");
                race(fixture, owner, initial, tenant, app, JdbcTenantRbac.Action.PROFILE_CREATE, profile("race-profile", custom),
                        JdbcTenantRbac.Action.PROFILE_CREATE, profile("race-profile", editorRole), "23505");
                var updateRole = update(custom, 1, "custom-reader", assignmentRead);
                var updateProfile = new JdbcTenantRbac.Input(composedProfile, null, 1L, "multiple-roles", "Multiple roles", null, java.util.List.of(), java.util.List.of(custom, editorRole));
                race(fixture, owner, initial, tenant, app, JdbcTenantRbac.Action.ROLE_UPDATE, updateRole,
                        JdbcTenantRbac.Action.PROFILE_UPDATE, updateProfile, "CONFIRMED");
                assertEquals("2", scalar(admin, "SELECT revision FROM security.profiles WHERE id='" + composedProfile + "'"));
                try (var first = fixture.role(JdbcTenantRbac.ROLE).getConnection(); var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                    com.lssoluciones.lscore.platform.PlatformDatabase.begin(first, JdbcTenantRbac.ROLE, false);
                    UUID identical = UUID.randomUUID();
                    var input = role("identical-request", read);
                    command(first, owner, tenant, app, identical, JdbcTenantRbac.Action.ROLE_CREATE, input);
                    var same = executor.submit(() -> rbac.mutate(browser.token(LoginApplication.CORE), tenant, app, identical, JdbcTenantRbac.Action.ROLE_CREATE, input, password));
                    Thread.sleep(150);
                    assertTrue(!same.isDone());
                    first.commit();
                    assertEquals("CONFIRMED", same.get(8, java.util.concurrent.TimeUnit.SECONDS).outcome());
                    assertEquals("1", scalar(admin, "SELECT count(*) FROM security.rbac_operations WHERE request_id='" + identical + "'"));
                }
                // Module disable is serialized before a waiting grant; publication cannot bypass entitlement.
                try (var first = fixture.role("postgres").getConnection(); var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                    first.setAutoCommit(false);
                    try (var statement = first.createStatement()) {
                        statement.execute("UPDATE catalog.tenant_modules SET status='DISABLED' WHERE tenant_id='" + tenant + "' AND application_id='" + app + "' AND module_id=(SELECT module_id FROM security.permissions WHERE id='" + read + "')");
                    }
                    var pending = executor.submit(() -> raw(fixture, owner, tenant, app, JdbcTenantRbac.Action.ROLE_CREATE, role("disabled-during-grant", read)));
                    Thread.sleep(150);
                    assertTrue(!pending.isDone());
                    first.commit();
                    assertEquals("42501", pending.get(8, java.util.concurrent.TimeUnit.SECONDS));
                }
                sql.execute("UPDATE catalog.tenant_modules SET status='ACTIVE' WHERE tenant_id='" + tenant + "' AND application_id='" + app + "'");
                // Failed audit rolls back the resource, associations and receipt together.
                sql.execute("CREATE FUNCTION audit.fail_rbac_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action_code LIKE 'TENANT_RBAC_%' AND NEW.outcome='SUCCESS' THEN RAISE EXCEPTION 'Owned failure'; END IF; RETURN NEW; END $$");
                sql.execute("CREATE TRIGGER fail_rbac_fixture BEFORE INSERT ON audit.events FOR EACH ROW EXECUTE FUNCTION audit.fail_rbac_fixture()");
                UUID rollback = UUID.randomUUID();
                assertEquals(503, submit(browser, path, JdbcTenantRbac.Action.ROLE_CREATE, rollback, role("rollback-role", read, write), password).statusCode());
                sql.execute("DROP TRIGGER fail_rbac_fixture ON audit.events");
                sql.execute("DROP FUNCTION audit.fail_rbac_fixture()");
                assertEquals("0", scalar(admin, "SELECT count(*) FROM security.roles WHERE code='rollback-role'"));
                assertEquals("0", scalar(admin, "SELECT count(*) FROM security.rbac_operations WHERE request_id='" + rollback + "'"));
                UUID lost = UUID.randomUUID();
                var uncertain = new JdbcTenantRbac(server.getBean(JdbcApplicationSessions.class), authentication,
                        uncertain(fixture.role(JdbcTenantRbac.ROLE)), fixture.role(JdbcTenantRbac.LOG_ROLE));
                assertEquals("UNKNOWN", uncertain.mutate(initialBrowser.token(LoginApplication.CORE), tenant, app, lost,
                        JdbcTenantRbac.Action.ROLE_CREATE, role("lost-reply", read), password).outcome());
                assertEquals("CONFIRMED", rbac.find(initialBrowser.token(LoginApplication.CORE), lost).outcome());
                assertNull(rbac.find(browser.token(LoginApplication.CORE), lost));
                // Independent capability revocation drains the writer barrier before another mutation.
                try (var first = fixture.role("postgres").getConnection(); var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                    first.setAutoCommit(false);
                    try (var statement = first.createStatement()) {
                        statement.execute("DELETE FROM platform.rbac_administrator_grants WHERE tenant_id='" + tenant + "' AND tenant_designation_id='" + initialDesignation + "' AND capability='MANAGE'");
                    }
                    var pending = executor.submit(() -> raw(fixture, initial, tenant, app, JdbcTenantRbac.Action.ROLE_CREATE, role("revoked-authority", read)));
                    Thread.sleep(150);
                    assertTrue(!pending.isDone());
                    first.commit();
                    assertEquals("42501", pending.get(8, java.util.concurrent.TimeUnit.SECONDS));
                }
                assertEquals(200, initialBrowser.get(LoginApplication.CORE, "rbac/operations/" + lost).statusCode());
                assertEquals(404, browser.get(LoginApplication.CORE, "rbac/operations/" + lost).statusCode());
                // Declared composition changes never restore or create an administrative grant.
                assertEquals(403, submit(initialBrowser, path, JdbcTenantRbac.Action.ROLE_CREATE, UUID.randomUUID(), role("still-no-authority", write), password).statusCode());
                var changedProfile = new JdbcTenantRbac.Input(composedProfile, null, 2L, "multiple-roles", "Multiple roles", null, java.util.List.of(), java.util.List.of(editorRole));
                assertEquals(200, submit(browser, path, JdbcTenantRbac.Action.PROFILE_UPDATE, UUID.randomUUID(), changedProfile, password).statusCode());
                UUID raceProfile = UUID.fromString(scalar(admin, "SELECT id FROM security.profiles WHERE tenant_id='" + tenant + "' AND code='race-profile'"));
                assertEquals(200, submit(browser, path, JdbcTenantRbac.Action.PROFILE_DISABLE, UUID.randomUUID(), disable(raceProfile, 1), password).statusCode());
                assertEquals(200, submit(browser, path, JdbcTenantRbac.Action.ROLE_DISABLE, UUID.randomUUID(), disable(custom, 2), password).statusCode());
                assertEquals(200, submit(browser, path, JdbcTenantRbac.Action.PROFILE_DISABLE, UUID.randomUUID(), disable(composedProfile, 3), password).statusCode());
                assertTrue(rbac.history(browser.token(LoginApplication.CORE), tenant, app, composedProfile).size() >= 4);
                assertEquals("DISABLED", rbac.detail(browser.token(LoginApplication.CORE), tenant, app, "PROFILE", composedProfile).path("status").asText());
                // Suspended tenant commits ahead of a waiting RBAC command.
                try (var first = fixture.role(JdbcTenantLifecycle.ROLE).getConnection(); var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                    com.lssoluciones.lscore.platform.PlatformDatabase.begin(first, JdbcTenantLifecycle.ROLE, false);
                    try (var statement = first.createStatement()) {
                        statement.execute("INSERT INTO tenant.lifecycle_operations(request_id,actor_user_id,tenant_id,action,previous_status,resulting_status) VALUES(gen_random_uuid(),'" + owner + "','" + tenant + "','SUSPEND','ACTIVE','SUSPENDED')");
                    }
                    var pending = executor.submit(() -> raw(fixture, owner, tenant, app, JdbcTenantRbac.Action.ROLE_CREATE, role("tenant-suspended", read)));
                    Thread.sleep(150);
                    assertTrue(!pending.isDone());
                    first.commit();
                    assertEquals("42501", pending.get(8, java.util.concurrent.TimeUnit.SECONDS));
                }
                assertEquals(200, post(browser, "tenants/" + tenant + "/reactivate", operation(password)).statusCode());
                assertEquals("0", scalar(admin, "SELECT count(*) FROM security.branch_profile_assignments"));
                assertEquals("t", scalar(admin, "SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class WHERE oid IN ('security.rbac_operations'::regclass,'platform.rbac_administrator_grants'::regclass,'platform.rbac_permission_delegations'::regclass,'security.roles'::regclass,'security.role_permissions'::regclass,'security.profiles'::regclass,'security.profile_roles'::regclass)"));
                for (String role : new String[] {JdbcTenantRbac.ROLE, "ls_core_app_dev", JdbcApplicationSessions.ROLE}) {
                    try (var connection = fixture.role(role).getConnection(); var statement = connection.createStatement()) {
                        assertThrows(SQLException.class, () -> statement.execute("UPDATE security.roles SET name='forged' WHERE id='" + templateRole + "'"));
                        assertThrows(SQLException.class, () -> statement.execute("UPDATE security.permissions SET status='PUBLISHED' WHERE id='" + read + "'"));
                        assertThrows(SQLException.class, () -> statement.execute("INSERT INTO platform.rbac_permission_delegations VALUES('" + tenant + "','" + app + "','" + read + "','READ',clock_timestamp()+interval '1 hour')"));
                        assertThrows(SQLException.class, () -> statement.execute("DELETE FROM security.rbac_operations WHERE request_id='" + lost + "'"));
                    }
                }
                assertThrows(SQLException.class, () -> sql.execute("UPDATE audit.events SET metadata='{}'::jsonb WHERE action_code='TENANT_RBAC_ROLE_CREATE'"));
                assertEquals("0", scalar(admin, "SELECT count(*) FROM security.rbac_operations o WHERE NOT EXISTS(SELECT FROM audit.events e WHERE e.correlation_id=o.request_id AND e.action_code='TENANT_RBAC_'||o.action AND e.outcome='SUCCESS')"));
                assertEquals("0", scalar(admin, "SELECT count(*) FROM pg_proc WHERE proname LIKE '%rbac%' AND prosecdef"));
                // Explicit negative fixture only: an existing assigned roles.manage still cannot grant RBAC administration.
                var assigned = submit(browser, path, JdbcTenantRbac.Action.PROFILE_CREATE, UUID.randomUUID(), profile("assigned-guard", editorRole), password);
                assertEquals(200, assigned.statusCode());
                UUID assignedProfile = resource(assigned);
                UUID branch = UUID.randomUUID();
                UUID branchMembership = UUID.randomUUID();
                sql.execute("INSERT INTO tenant.branches(id,tenant_id,code,name,status) VALUES('" + branch + "','" + tenant + "','guard-fixture','Assigned guard fixture','ACTIVE')");
                sql.execute("INSERT INTO tenant.branch_memberships(id,tenant_id,membership_id,branch_id,status) SELECT '" + branchMembership + "',tenant_id,id,'" + branch + "','ACTIVE' FROM tenant.memberships WHERE tenant_id='" + tenant + "' AND user_id='" + initial + "'");
                sql.execute("INSERT INTO security.branch_profile_assignments(tenant_id,application_id,branch_membership_id,profile_id,status) VALUES('" + tenant + "','" + app + "','" + branchMembership + "','" + assignedProfile + "','ACTIVE')");
                assertEquals(403, submit(initialBrowser, path, JdbcTenantRbac.Action.ROLE_CREATE, UUID.randomUUID(), role("assigned-manage-is-not-authority", read), password).statusCode());
                assertEquals(409, submit(browser, path, JdbcTenantRbac.Action.ROLE_UPDATE, UUID.randomUUID(), update(editorRole, 1, "access_editor_v1", read), password).statusCode());
                assertEquals(409, submit(browser, path, JdbcTenantRbac.Action.PROFILE_UPDATE, UUID.randomUUID(), new JdbcTenantRbac.Input(assignedProfile, null, 1L, "assigned-guard", "Assigned", null, java.util.List.of(), java.util.List.of(templateRole)), password).statusCode());
                sql.execute("DELETE FROM security.branch_profile_assignments WHERE profile_id='" + assignedProfile + "'");
                sql.execute("DELETE FROM tenant.branch_memberships WHERE id='" + branchMembership + "'");
                sql.execute("DELETE FROM tenant.branches WHERE id='" + branch + "'");
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JdbcTenantRbac.Input role(String code, UUID... permissions) {
        return new JdbcTenantRbac.Input(null, null, null, code, "Role " + code, null, java.util.List.of(permissions), java.util.List.of());
    }

    private static JdbcTenantRbac.Input profile(String code, UUID... roles) {
        return new JdbcTenantRbac.Input(null, null, null, code, "Profile " + code, null, java.util.List.of(), java.util.List.of(roles));
    }

    private static JdbcTenantRbac.Input template(UUID id) {
        return new JdbcTenantRbac.Input(null, id, null, null, null, null, null, null);
    }

    private static JdbcTenantRbac.Input disable(UUID id, long revision) {
        return new JdbcTenantRbac.Input(id, null, revision, null, null, null, null, null);
    }

    private static JdbcTenantRbac.Input update(UUID id, long revision, String code, UUID... permissions) {
        return new JdbcTenantRbac.Input(id, null, revision, code, "Role " + code, null, java.util.List.of(permissions), java.util.List.of());
    }

    private static String body(UUID request, JdbcTenantRbac.Input input, char[] password) {
        var value = new java.util.LinkedHashMap<String, Object>();
        value.put("requestId", request);
        value.put("resourceId", input.resourceId());
        value.put("templateId", input.templateId());
        value.put("expectedRevision", input.expectedRevision());
        value.put("code", input.code());
        value.put("name", input.name());
        value.put("description", input.description());
        value.put("permissionIds", input.permissionIds());
        value.put("roleIds", input.roleIds());
        value.put("password", password);
        return JSON.writeValueAsString(value);
    }

    private static HttpResponse<String> submit(ApplicationLoginPostgresTest.Browser browser, String path,
            JdbcTenantRbac.Action action, UUID request, JdbcTenantRbac.Input input, char[] password) throws Exception {
        return post(browser, path + "/commands/" + action.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'), body(request, input, password));
    }

    private static UUID resource(HttpResponse<String> response) {
        return UUID.fromString(JSON.readTree(response.body()).path("receipt").path("resourceId").asText());
    }

    private static void grantPermission(java.sql.Statement sql, UUID tenant, UUID app, UUID permission, String kind) throws SQLException {
        sql.execute("INSERT INTO platform.rbac_permission_delegations VALUES('" + tenant + "','" + app + "','" + permission + "','" + kind + "',clock_timestamp()+interval '1 hour')");
    }

    private static void grantAdministrator(java.sql.Statement sql, UUID tenant, UUID app, UUID designation) throws SQLException {
        for (String cap : new String[] {"READ", "MANAGE"}) {
            sql.execute("INSERT INTO platform.rbac_administrator_grants(tenant_id,application_id,tenant_designation_id,capability,expires_at) VALUES('" + tenant + "','" + app + "','" + designation + "','" + cap + "',clock_timestamp()+interval '1 hour')");
        }
    }

    private static void command(java.sql.Connection connection, UUID actor, UUID tenant, UUID app, UUID request,
            JdbcTenantRbac.Action action, JdbcTenantRbac.Input input) throws SQLException {
        try (var query = connection.prepareStatement("INSERT INTO security.rbac_operations(request_id,actor_user_id,tenant_id,application_id,action,resource_id,expected_revision,template_id,code,name,description,permission_ids,role_ids) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            JdbcTenantRbac.bind(query, connection, actor, tenant, app, request, action, input);
            query.executeUpdate();
        }
    }

    private static String raw(ApplicationLoginPostgresTest fixture, UUID actor, UUID tenant, UUID app,
            JdbcTenantRbac.Action action, JdbcTenantRbac.Input input) throws Exception {
        try (var connection = fixture.role(JdbcTenantRbac.ROLE).getConnection()) {
            com.lssoluciones.lscore.platform.PlatformDatabase.begin(connection, JdbcTenantRbac.ROLE, false);
            try {
                command(connection, actor, tenant, app, UUID.randomUUID(), action, input);
                connection.commit();
                return "CONFIRMED";
            } catch (SQLException failure) {
                connection.rollback();
                return failure.getSQLState();
            }
        }
    }

    private static void race(ApplicationLoginPostgresTest fixture, UUID actor, UUID secondActor, UUID tenant, UUID app,
            JdbcTenantRbac.Action firstAction, JdbcTenantRbac.Input firstInput, JdbcTenantRbac.Action secondAction,
            JdbcTenantRbac.Input secondInput, String expected) throws Exception {
        try (var first = fixture.role(JdbcTenantRbac.ROLE).getConnection(); var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            com.lssoluciones.lscore.platform.PlatformDatabase.begin(first, JdbcTenantRbac.ROLE, false);
            command(first, actor, tenant, app, UUID.randomUUID(), firstAction, firstInput);
            var pending = executor.submit(() -> raw(fixture, secondActor, tenant, app, secondAction, secondInput));
            Thread.sleep(150);
            assertTrue(!pending.isDone());
            first.commit();
            assertEquals(expected, pending.get(8, java.util.concurrent.TimeUnit.SECONDS));
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

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
class ProfileAssignmentsPostgresTest {
    @Test
    void contextualAssignmentsIsolationRevocationAndConcurrency() throws Exception {
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
            UUID owner = definitive(registrar, lifecycle, "f50-owner", password);
            UUID business = definitive(registrar, lifecycle, "f50-business", password);
            UUID delegate = definitive(registrar, lifecycle, "f50-delegate", password);
            UUID initial = definitive(registrar, lifecycle, "f50-initial", password);
            UUID outsider = definitive(registrar, lifecycle, "f50-outsider", password);
            var authentication =
                    new JdbcPasswordAuthentication(
                            fixture.role(JdbcPasswordAuthentication.ROLE),
                            JdbcPasswordAuthentication.Limits.defaults());
            new JdbcPlatformBootstrap(fixture.role(JdbcPlatformBootstrap.ROLE), authentication)
                    .initialize("f50-owner", password);
            var global =
                    new JdbcPlatformExecutor(
                            fixture.role(JdbcPlatformExecutor.ROLE),
                            fixture.role(JdbcPlatformExecutor.SECURITY_LOG_ROLE),
                            authentication);
            assertEquals(
                    JdbcPlatformExecutor.Outcome.CONFIRMED,
                    global.execute(
                                    "f50-owner",
                                    password,
                                    delegate,
                                    JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN)
                            .outcome());
            UUID fiscal = UUID.randomUUID();
            UUID pos = UUID.randomUUID();
            sql.execute(
                    "INSERT INTO catalog.applications(id,code,name,status) VALUES ('"
                            + fiscal
                            + "','f50-fiscal','Fiscal fixture','PUBLISHED'),('"
                            + pos
                            + "','f50-pos','POS fixture','PUBLISHED')");
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
            properties.put("ls.core.profile-assignments.enabled", "true");
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
                                    Map.entry(
                                            "LS_PROFILE_ASSIGNMENTS_DB_",
                                            JdbcProfileAssignments.ROLE),
                                    Map.entry(
                                            "LS_PROFILE_ASSIGNMENTS_LOG_DB_",
                                            JdbcProfileAssignments.LOG_ROLE),
                                    Map.entry("LS_TENANT_RBAC_DB_", JdbcTenantRbac.ROLE),
                                    Map.entry("LS_TENANT_RBAC_LOG_DB_", JdbcTenantRbac.LOG_ROLE),
                                    Map.entry("LS_IDENTITY_LINKS_DB_", JdbcIdentityLinks.ROLE),
                                    Map.entry(
                                            "LS_IDENTITY_LINKS_LOG_DB_",
                                            JdbcIdentityLinks.LOG_ROLE),
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
                                credentials("f50-owner", password));
                assertEquals(204, logged.statusCode());
                assertEquals(
                        200, browser.get(LoginApplication.CORE, "tenant-authority").statusCode());
                UUID tenant = company(browser, initial, password);
                UUID other = company(browser, outsider, password);
                UUID app = UUID.fromString(core);

                var holder = new ApplicationLoginPostgresTest.Browser(client, base);
                var foreign = new ApplicationLoginPostgresTest.Browser(client, base);
                var delegated = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(
                        204,
                        post(holder, "login", credentials("f50-business", password)).statusCode());
                assertEquals(
                        204,
                        post(foreign, "login", credentials("f50-outsider", password)).statusCode());
                assertEquals(
                        204,
                        post(delegated, "login", credentials("f50-delegate", password))
                                .statusCode());
                // Both memberships come from authenticated V29 acceptance, not fixture inserts.
                for (UUID company : new UUID[] {tenant, other}) {
                    assertEquals(
                            200,
                            post(
                                            browser,
                                            "identity-links/tenants/" + company + "/requests",
                                            linkRequest(
                                                    UUID.randomUUID(), "f50-business", password))
                                    .statusCode());
                    UUID link =
                            UUID.fromString(
                                    scalar(
                                            admin,
                                            "SELECT id FROM tenant.identity_link_requests WHERE"
                                                    + " tenant_id='"
                                                    + company
                                                    + "' AND user_id='"
                                                    + business
                                                    + "'"));
                    assertEquals(
                            200,
                            post(
                                            holder,
                                            "identity-links/received/" + link + "/accept",
                                            operation(password))
                                    .statusCode());
                    sql.execute(
                            "INSERT INTO"
                                + " catalog.tenant_applications(tenant_id,application_id,status)"
                                + " VALUES('"
                                    + company
                                    + "','"
                                    + app
                                    + "','ACTIVE')");
                    sql.execute(
                            "INSERT INTO"
                                + " catalog.tenant_modules(tenant_id,application_id,module_id,status)"
                                + " SELECT '"
                                    + company
                                    + "',application_id,id,'ACTIVE' FROM catalog.modules WHERE"
                                    + " application_id='"
                                    + app
                                    + "'");
                }
                UUID member =
                        UUID.fromString(
                                scalar(
                                        admin,
                                        "SELECT id FROM tenant.memberships WHERE tenant_id='"
                                                + tenant
                                                + "' AND user_id='"
                                                + business
                                                + "'"));
                UUID memberB =
                        UUID.fromString(
                                scalar(
                                        admin,
                                        "SELECT id FROM tenant.memberships WHERE tenant_id='"
                                                + other
                                                + "' AND user_id='"
                                                + business
                                                + "'"));
                UUID branch = branch(sql, tenant, "01");
                UUID second = branch(sql, tenant, "02");
                UUID otherBranch = branch(sql, other, "01");
                UUID read = UUID.fromString("8322b61e-868e-51a6-bb15-37cb7bb99086");
                UUID write = UUID.fromString("48520524-890b-5d11-ab98-14716a11ecab");
                UUID assignmentRead = UUID.fromString("edbd064d-8215-5e32-8d03-82cc108b3d6d");
                for (UUID company : new UUID[] {tenant, other}) {
                    grantPermission(sql, company, app, read, "READ");
                    grantPermission(sql, company, app, write, "WRITE");
                    grantPermission(sql, company, app, assignmentRead, "READ");
                }
                sql.execute(
                        "INSERT INTO platform.permission_execution_rules SELECT"
                                + " application_id,id,CASE WHEN id='"
                                + write
                                + "' THEN 'WRITE' ELSE 'READ' END,'SINGLE_BRANCH' FROM"
                                + " security.permissions WHERE id IN ('"
                                + read
                                + "','"
                                + write
                                + "','"
                                + assignmentRead
                                + "')");
                String path = "rbac/tenants/" + tenant + "/applications/" + app;
                UUID reader =
                        resource(
                                submit(
                                        browser,
                                        path,
                                        JdbcTenantRbac.Action.ROLE_CREATE,
                                        UUID.randomUUID(),
                                        role("reader", read),
                                        password));
                UUID editor =
                        resource(
                                submit(
                                        browser,
                                        path,
                                        JdbcTenantRbac.Action.ROLE_CREATE,
                                        UUID.randomUUID(),
                                        role("editor", read, write, assignmentRead),
                                        password));
                UUID profile =
                        resource(
                                submit(
                                        browser,
                                        path,
                                        JdbcTenantRbac.Action.PROFILE_CREATE,
                                        UUID.randomUUID(),
                                        profile("reader", reader),
                                        password));
                UUID profile2 =
                        resource(
                                submit(
                                        browser,
                                        path,
                                        JdbcTenantRbac.Action.PROFILE_CREATE,
                                        UUID.randomUUID(),
                                        profile("editor", editor),
                                        password));
                String pathB = "rbac/tenants/" + other + "/applications/" + app;
                UUID roleB =
                        resource(
                                submit(
                                        browser,
                                        pathB,
                                        JdbcTenantRbac.Action.ROLE_CREATE,
                                        UUID.randomUUID(),
                                        role("reader", assignmentRead),
                                        password));
                UUID profileB =
                        resource(
                                submit(
                                        browser,
                                        pathB,
                                        JdbcTenantRbac.Action.PROFILE_CREATE,
                                        UUID.randomUUID(),
                                        profile("reader", roleB),
                                        password));
                var service = server.getBean(JdbcProfileAssignments.class);
                String token = browser.token(LoginApplication.CORE);
                var joined =
                        service.mutate(
                                token,
                                tenant,
                                app,
                                UUID.randomUUID(),
                                JdbcProfileAssignments.Action.JOIN_BRANCH,
                                input(member, branch, null, null),
                                password);
                assertEquals("CONFIRMED", joined.outcome());
                var joined2 =
                        service.mutate(
                                token,
                                tenant,
                                app,
                                UUID.randomUUID(),
                                JdbcProfileAssignments.Action.JOIN_BRANCH,
                                input(member, second, null, null),
                                password);
                var joinedB =
                        service.mutate(
                                token,
                                other,
                                app,
                                UUID.randomUUID(),
                                JdbcProfileAssignments.Action.JOIN_BRANCH,
                                input(memberB, otherBranch, null, null),
                                password);
                assertEquals("CONFIRMED", joined2.outcome());
                assertEquals("CONFIRMED", joinedB.outcome());
                assertEquals(
                        "0",
                        scalar(admin, "SELECT count(*) FROM security.branch_profile_assignments"));
                UUID bm =
                        UUID.fromString(
                                joined.receipt().result().path("branchMembershipId").asText());
                UUID bm2 =
                        UUID.fromString(
                                joined2.receipt().result().path("branchMembershipId").asText());
                UUID bmB =
                        UUID.fromString(
                                joinedB.receipt().result().path("branchMembershipId").asText());
                var add = input(member, branch, bm, version(admin, bm), profile, profile2);
                UUID request = UUID.randomUUID();
                String commandPath = "rbac/assignments/tenants/" + tenant + "/applications/" + app;
                assertEquals(
                        403,
                        browser.post(
                                        LoginApplication.CORE,
                                        commandPath + "/commands/assign",
                                        null,
                                        assignmentBody(request, add, password))
                                .statusCode());
                assertEquals(
                        "DENIED",
                        service.mutate(
                                        token,
                                        tenant,
                                        app,
                                        request,
                                        JdbcProfileAssignments.Action.ASSIGN,
                                        add,
                                        "Incorrect-50!".toCharArray())
                                .outcome());
                var assigned =
                        service.mutate(
                                token,
                                tenant,
                                app,
                                request,
                                JdbcProfileAssignments.Action.ASSIGN,
                                add,
                                password);
                assertEquals("CONFIRMED", assigned.outcome());
                assertEquals(
                        "CONFIRMED",
                        service.mutate(
                                        token,
                                        tenant,
                                        app,
                                        request,
                                        JdbcProfileAssignments.Action.ASSIGN,
                                        add,
                                        password)
                                .outcome());
                assertEquals(
                        "CONFLICT",
                        service.mutate(
                                        token,
                                        tenant,
                                        app,
                                        UUID.randomUUID(),
                                        JdbcProfileAssignments.Action.ASSIGN,
                                        input(member, branch, bm, version(admin, bm), profile),
                                        password)
                                .outcome());
                assertEquals(
                        "DENIED",
                        service.mutate(
                                        token,
                                        tenant,
                                        app,
                                        UUID.randomUUID(),
                                        JdbcProfileAssignments.Action.ASSIGN,
                                        input(member, branch, bm, version(admin, bm), profileB),
                                        password)
                                .outcome());
                assertEquals(
                        "DENIED",
                        service.mutate(
                                        delegated.token(LoginApplication.CORE),
                                        tenant,
                                        app,
                                        UUID.randomUUID(),
                                        JdbcProfileAssignments.Action.ASSIGN,
                                        add,
                                        password)
                                .outcome());
                assertThrows(
                        JdbcTenantUsers.Denied.class,
                        () ->
                                service.workspace(
                                        foreign.token(LoginApplication.CORE), tenant, app, member));
                assertEquals(
                        "CONFIRMED",
                        service.mutate(
                                        token,
                                        tenant,
                                        app,
                                        UUID.randomUUID(),
                                        JdbcProfileAssignments.Action.ASSIGN,
                                        input(member, second, bm2, version(admin, bm2), profile),
                                        password)
                                .outcome());
                assertEquals(
                        "CONFIRMED",
                        service.mutate(
                                        token,
                                        other,
                                        app,
                                        UUID.randomUUID(),
                                        JdbcProfileAssignments.Action.ASSIGN,
                                        input(
                                                memberB,
                                                otherBranch,
                                                bmB,
                                                version(admin, bmB),
                                                profileB),
                                        password)
                                .outcome());
                var secondHolder = new ApplicationLoginPostgresTest.Browser(client, base);
                var foreignHolder = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(
                        204,
                        post(secondHolder, "login", credentials("f50-business", password))
                                .statusCode());
                assertEquals(
                        204,
                        post(foreignHolder, "login", credentials("f50-business", password))
                                .statusCode());
                var contexts = server.getBean(JdbcSessionContexts.class);
                var secondContext =
                        contexts.select(
                                LoginApplication.CORE,
                                secondHolder.token(LoginApplication.CORE),
                                tenant,
                                second);
                var foreignContext =
                        contexts.select(
                                LoginApplication.CORE,
                                foreignHolder.token(LoginApplication.CORE),
                                other,
                                otherBranch);

                String holderToken = holder.token(LoginApplication.CORE);
                var selected = contexts.select(LoginApplication.CORE, holderToken, tenant, branch);
                assertEquals(3, selected.permissions().size());
                assertEquals(
                        1,
                        contexts.select(LoginApplication.CORE, holderToken, tenant, second)
                                .permissions()
                                .size());
                assertEquals(
                        assignmentRead,
                        contexts.select(LoginApplication.CORE, holderToken, other, otherBranch)
                                .permissions()
                                .getFirst()
                                .id());
                selected = contexts.select(LoginApplication.CORE, holderToken, tenant, branch);
                UUID session =
                        UUID.fromString(
                                scalar(
                                        admin,
                                        "SELECT session_id FROM security.session_contexts WHERE"
                                                + " id='"
                                                + selected.id()
                                                + "'"));
                var binding =
                        new JdbcContextPermissions.Binding(
                                business, tenant, app, branch, member, bm, selected.id(), session);
                String writeCode =
                        scalar(
                                admin,
                                "SELECT code FROM security.permissions WHERE id='" + write + "'");
                UUID assignment =
                        UUID.fromString(
                                assigned.receipt().result().path("assignmentIds").get(1).asText());
                var revoke =
                        new JdbcProfileAssignments.Input(
                                member,
                                branch,
                                bm,
                                assignment,
                                version(admin, bm),
                                1L,
                                java.util.List.of());
                // Admission holds native barriers until COMMIT; revoke waits on an independent JDBC
                // connection.
                try (var admitted = fixture.role(JdbcContextPermissions.ROLE).getConnection();
                        var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                    JdbcContextPermissions.begin(admitted);
                    JdbcContextPermissions.require(
                            admitted, binding, writeCode, JdbcContextPermissions.Operation.WRITE);
                    var pending =
                            executor.submit(
                                    () ->
                                            service.mutate(
                                                    token,
                                                    tenant,
                                                    app,
                                                    UUID.randomUUID(),
                                                    JdbcProfileAssignments.Action.REVOKE,
                                                    revoke,
                                                    password));
                    Thread.sleep(150);
                    assertTrue(!pending.isDone());
                    admitted.commit();
                    assertEquals(
                            "CONFIRMED",
                            pending.get(8, java.util.concurrent.TimeUnit.SECONDS).outcome());
                }
                try (var admitted = fixture.role(JdbcContextPermissions.ROLE).getConnection()) {
                    JdbcContextPermissions.begin(admitted);
                    assertThrows(
                            SQLException.class,
                            () ->
                                    JdbcContextPermissions.require(
                                            admitted,
                                            binding,
                                            writeCode,
                                            JdbcContextPermissions.Operation.WRITE));
                    admitted.rollback();
                }
                assertNull(contexts.current(LoginApplication.CORE, holderToken));
                assertEquals(
                        secondContext.id(),
                        contexts.current(
                                        LoginApplication.CORE,
                                        secondHolder.token(LoginApplication.CORE))
                                .id());
                assertEquals(
                        foreignContext.id(),
                        contexts.current(
                                        LoginApplication.CORE,
                                        foreignHolder.token(LoginApplication.CORE))
                                .id());
                assertEquals(
                        1,
                        contexts.select(LoginApplication.CORE, holderToken, tenant, branch)
                                .permissions()
                                .size());
                assertEquals(
                        "1",
                        scalar(
                                admin,
                                "SELECT count(*) FROM security.sessions WHERE id='"
                                        + session
                                        + "' AND revoked_at IS NULL"));
                assertEquals(
                        "2",
                        scalar(
                                admin,
                                "SELECT count(*) FROM tenant.identity_link_requests WHERE user_id='"
                                        + business
                                        + "' AND status='ACCEPTED'"));
                // Add again never restores the old context. Lost COMMIT acknowledgement reconciles
                // by durable UUID.
                var uncertain =
                        new JdbcProfileAssignments(
                                server.getBean(JdbcApplicationSessions.class),
                                authentication,
                                uncertain(fixture.role(JdbcProfileAssignments.ROLE)),
                                fixture.role(JdbcProfileAssignments.LOG_ROLE));
                UUID uncertainId = UUID.randomUUID();
                assertEquals(
                        "UNKNOWN",
                        uncertain
                                .mutate(
                                        token,
                                        tenant,
                                        app,
                                        uncertainId,
                                        JdbcProfileAssignments.Action.ASSIGN,
                                        input(member, branch, bm, version(admin, bm), profile2),
                                        password)
                                .outcome());
                assertEquals("CONFIRMED", service.find(token, uncertainId).outcome());
                assertEquals(
                        "1",
                        scalar(
                                admin,
                                "SELECT count(*) FROM security.session_contexts WHERE id='"
                                        + binding.context()
                                        + "' AND revoked_at IS NOT NULL"));
                sql.execute(
                        "UPDATE catalog.tenant_modules SET status='READ_ONLY' WHERE tenant_id='"
                                + tenant
                                + "' AND application_id='"
                                + app
                                + "'");
                assertTrue(
                        contexts
                                .select(LoginApplication.CORE, holderToken, tenant, branch)
                                .permissions()
                                .stream()
                                .noneMatch(p -> "WRITE".equals(p.operationKind())));
                sql.execute(
                        "UPDATE catalog.tenant_modules SET status='DISABLED' WHERE tenant_id='"
                                + tenant
                                + "' AND application_id='"
                                + app
                                + "'");
                assertEquals(
                        0,
                        contexts.current(LoginApplication.CORE, holderToken).permissions().size());
                sql.execute(
                        "UPDATE catalog.tenant_modules SET status='ACTIVE' WHERE tenant_id='"
                                + tenant
                                + "' AND application_id='"
                                + app
                                + "'");
                sql.execute(
                        "UPDATE platform.permission_execution_rules SET context_mode='RESERVED'"
                                + " WHERE permission_id='"
                                + read
                                + "'");
                assertTrue(
                        contexts.current(LoginApplication.CORE, holderToken).permissions().stream()
                                .noneMatch(p -> read.equals(p.id())));
                sql.execute(
                        "UPDATE platform.permission_execution_rules SET"
                                + " context_mode='SINGLE_BRANCH' WHERE permission_id='"
                                + read
                                + "'");
                assertEquals(
                        409,
                        submit(
                                        browser,
                                        path,
                                        JdbcTenantRbac.Action.ROLE_UPDATE,
                                        UUID.randomUUID(),
                                        update(reader, 1, "reader", write),
                                        password)
                                .statusCode());
                for (String role :
                        new String[] {
                            JdbcProfileAssignments.ROLE,
                            JdbcContextPermissions.ROLE,
                            JdbcProfileAssignments.LOG_ROLE
                        }) {
                    try (var c = fixture.role(role).getConnection();
                            var q = c.createStatement()) {
                        assertThrows(
                                SQLException.class,
                                () ->
                                        q.executeUpdate(
                                                "UPDATE security.branch_profile_assignments SET"
                                                        + " revoked_at=NULL"));
                        assertThrows(
                                SQLException.class,
                                () ->
                                        q.executeUpdate(
                                                "INSERT INTO platform.permission_execution_rules"
                                                    + " SELECT"
                                                    + " application_id,id,'WRITE','SINGLE_BRANCH'"
                                                    + " FROM security.permissions"));
                    }
                }

                sql.execute(
                        "INSERT INTO catalog.tenant_applications(tenant_id,application_id,status)"
                            + " VALUES('"
                                + tenant
                                + "','"
                                + fiscal
                                + "','ACTIVE')");
                UUID wrongAppProfile = UUID.randomUUID();
                sql.execute(
                        "INSERT INTO"
                            + " security.profiles(id,tenant_id,application_id,code,name,status)"
                            + " VALUES('"
                                + wrongAppProfile
                                + "','"
                                + tenant
                                + "','"
                                + fiscal
                                + "','wrong-app','Wrong application fixture','ACTIVE')");
                assertEquals(
                        "DENIED",
                        service.mutate(
                                        token,
                                        tenant,
                                        app,
                                        UUID.randomUUID(),
                                        JdbcProfileAssignments.Action.ASSIGN,
                                        input(
                                                member,
                                                branch,
                                                bm,
                                                version(admin, bm),
                                                wrongAppProfile),
                                        password)
                                .outcome());
                sql.execute(
                        "UPDATE catalog.tenant_applications SET status='READ_ONLY' WHERE"
                            + " tenant_id='"
                                + tenant
                                + "' AND application_id='"
                                + app
                                + "'");
                assertTrue(
                        contexts
                                .select(LoginApplication.CORE, holderToken, tenant, branch)
                                .permissions()
                                .stream()
                                .noneMatch(p -> "WRITE".equals(p.operationKind())));
                sql.execute(
                        "UPDATE catalog.tenant_applications SET status='DISABLED' WHERE tenant_id='"
                                + tenant
                                + "' AND application_id='"
                                + app
                                + "'");
                assertEquals(0, service.preview(token, tenant, app, member, bm).size());
                sql.execute(
                        "UPDATE catalog.tenant_applications SET status='ACTIVE' WHERE tenant_id='"
                                + tenant
                                + "' AND application_id='"
                                + app
                                + "'");
                for (String[] state :
                        new String[][] {
                            {"security.roles", editor.toString()},
                            {"security.profiles", profile2.toString()}
                        }) {
                    sql.execute(
                            "UPDATE "
                                    + state[0]
                                    + " SET status='DISABLED' WHERE id='"
                                    + state[1]
                                    + "'");
                    assertTrue(
                            contexts
                                    .current(LoginApplication.CORE, holderToken)
                                    .permissions()
                                    .stream()
                                    .noneMatch(p -> write.equals(p.id())));
                    sql.execute(
                            "UPDATE "
                                    + state[0]
                                    + " SET status='ACTIVE' WHERE id='"
                                    + state[1]
                                    + "'");
                }
                sql.execute(
                        "UPDATE security.permissions SET status='DRAFT' WHERE id='" + write + "'");
                assertTrue(
                        contexts.current(LoginApplication.CORE, holderToken).permissions().stream()
                                .noneMatch(p -> write.equals(p.id())));
                sql.execute(
                        "UPDATE security.permissions SET status='PUBLISHED' WHERE id='"
                                + write
                                + "'");
                try (var c = fixture.role(JdbcContextPermissions.ROLE).getConnection();
                        var q = c.createStatement()) {
                    assertThrows(
                            SQLException.class,
                            () -> q.executeQuery("SELECT token_hash FROM security.sessions"));
                }
                // Dynamic states are rechecked on every resolution, independently of published
                // metadata.
                for (String[] state :
                        new String[][] {
                            {"tenant.tenants", "id", tenant.toString(), "SUSPENDED"},
                            {"tenant.memberships", "id", member.toString(), "SUSPENDED"},
                            {"tenant.branch_memberships", "id", bm.toString(), "SUSPENDED"},
                            {"tenant.branches", "id", branch.toString(), "CLOSED"},
                            {"identity.users", "id", business.toString(), "SUSPENDED"}
                        }) {
                    sql.execute(
                            "UPDATE "
                                    + state[0]
                                    + " SET status='"
                                    + state[3]
                                    + "' WHERE "
                                    + state[1]
                                    + "='"
                                    + state[2]
                                    + "'");
                    if ("tenant.tenants".equals(state[0])) {
                        assertThrows(
                                JdbcTenantUsers.Denied.class,
                                () -> service.preview(token, tenant, app, member, bm));
                    } else {
                        assertEquals(0, service.preview(token, tenant, app, member, bm).size());
                    }
                    sql.execute(
                            "UPDATE "
                                    + state[0]
                                    + " SET status='ACTIVE' WHERE "
                                    + state[1]
                                    + "='"
                                    + state[2]
                                    + "'");
                }
                assertThrows(
                        JdbcTenantUsers.Denied.class,
                        () -> service.workspace(holderToken, tenant, app, member));
                // Atomic evidence failure must roll back assignment and all context revocations.
                var rollbackContext =
                        contexts.select(LoginApplication.CORE, holderToken, tenant, branch);
                sql.execute(
                        "CREATE FUNCTION audit.fail_assignment_fixture() RETURNS trigger LANGUAGE"
                            + " plpgsql AS $$ BEGIN IF NEW.action_code LIKE 'TENANT_PROFILE_%' THEN"
                            + " RAISE EXCEPTION 'Owned audit failure'; END IF; RETURN NEW; END $$");
                sql.execute(
                        "CREATE TRIGGER z_fail_assignment_fixture BEFORE INSERT ON audit.events FOR"
                                + " EACH ROW EXECUTE FUNCTION audit.fail_assignment_fixture()");
                var active =
                        service.workspace(token, tenant, app, member).assignments().stream()
                                .filter(
                                        a ->
                                                a.path("branch_membership_id")
                                                                .asText()
                                                                .equals(bm.toString())
                                                        && a.path("status")
                                                                .asText()
                                                                .equals("ACTIVE"))
                                .findFirst()
                                .orElseThrow();
                UUID failedId = UUID.randomUUID();
                var failedInput =
                        new JdbcProfileAssignments.Input(
                                member,
                                branch,
                                bm,
                                UUID.fromString(active.path("id").asText()),
                                version(admin, bm),
                                active.path("revision").asLong(),
                                java.util.List.of());
                assertEquals(
                        "UNAVAILABLE",
                        service.mutate(
                                        token,
                                        tenant,
                                        app,
                                        failedId,
                                        JdbcProfileAssignments.Action.REVOKE,
                                        failedInput,
                                        password)
                                .outcome());
                sql.execute("DROP TRIGGER z_fail_assignment_fixture ON audit.events");
                sql.execute("DROP FUNCTION audit.fail_assignment_fixture()");
                assertNull(service.find(token, failedId));
                assertEquals(
                        rollbackContext.id(),
                        contexts.current(LoginApplication.CORE, holderToken).id());
                assertEquals(
                        "2",
                        scalar(
                                admin,
                                "SELECT count(*) FROM pg_class WHERE oid IN"
                                    + " ('security.profile_assignment_operations'::regclass,'platform.permission_execution_rules'::regclass)"
                                    + " AND relrowsecurity AND relforcerowsecurity"));

                sql.execute(
                        "UPDATE tenant.memberships SET status='PENDING' WHERE id='" + member + "'");
                assertEquals(
                        "INCOMPLETE_REQUIREMENTS",
                        service.mutate(
                                        token,
                                        tenant,
                                        app,
                                        UUID.randomUUID(),
                                        JdbcProfileAssignments.Action.JOIN_BRANCH,
                                        input(member, branch, null, null),
                                        password)
                                .outcome());
                assertEquals(
                        "PENDING",
                        scalar(
                                admin,
                                "SELECT status FROM tenant.memberships WHERE id='" + member + "'"));
                sql.execute(
                        "UPDATE tenant.memberships SET status='ACTIVE' WHERE id='" + member + "'");
                var initialBrowser = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(
                        204,
                        post(initialBrowser, "login", credentials("f50-initial", password))
                                .statusCode());
                UUID designation =
                        UUID.fromString(
                                scalar(
                                        admin,
                                        "SELECT a.id FROM tenant.valid_administrators('"
                                                + tenant
                                                + "') a WHERE a.user_id='"
                                                + initial
                                                + "'"));
                grantAdministrator(sql, tenant, app, designation);
                UUID initialMember =
                        UUID.fromString(
                                scalar(
                                        admin,
                                        "SELECT id FROM tenant.memberships WHERE tenant_id='"
                                                + tenant
                                                + "' AND user_id='"
                                                + initial
                                                + "'"));
                var initialJoined =
                        service.mutate(
                                token,
                                tenant,
                                app,
                                UUID.randomUUID(),
                                JdbcProfileAssignments.Action.JOIN_BRANCH,
                                input(initialMember, branch, null, null),
                                password);
                UUID initialBm =
                        UUID.fromString(
                                initialJoined
                                        .receipt()
                                        .result()
                                        .path("branchMembershipId")
                                        .asText());
                assertEquals(
                        "DENIED",
                        service.mutate(
                                        initialBrowser.token(LoginApplication.CORE),
                                        tenant,
                                        app,
                                        UUID.randomUUID(),
                                        JdbcProfileAssignments.Action.ASSIGN,
                                        input(
                                                initialMember,
                                                branch,
                                                initialBm,
                                                version(admin, initialBm),
                                                profile),
                                        password)
                                .outcome());
                var concurrentInput = input(member, second, bm2, version(admin, bm2), profile2);
                try (var first = fixture.role(JdbcProfileAssignments.ROLE).getConnection();
                        var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                    com.lssoluciones.lscore.platform.PlatformDatabase.begin(
                            first, JdbcProfileAssignments.ROLE, false);
                    assignmentCommand(
                            first,
                            owner,
                            tenant,
                            app,
                            UUID.randomUUID(),
                            JdbcProfileAssignments.Action.ASSIGN,
                            concurrentInput);
                    var pending =
                            executor.submit(
                                    () ->
                                            service.mutate(
                                                    initialBrowser.token(LoginApplication.CORE),
                                                    tenant,
                                                    app,
                                                    UUID.randomUUID(),
                                                    JdbcProfileAssignments.Action.ASSIGN,
                                                    concurrentInput,
                                                    password));
                    Thread.sleep(150);
                    assertTrue(!pending.isDone());
                    first.commit();
                    assertEquals(
                            "CONFLICT",
                            pending.get(8, java.util.concurrent.TimeUnit.SECONDS).outcome());
                }
                // An administrator grant revoked ahead of a waiting command is authoritative at
                // COMMIT.
                try (var first = fixture.role("postgres").getConnection();
                        var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                    first.setAutoCommit(false);
                    try (var q = first.createStatement()) {
                        q.execute(
                                "DELETE FROM platform.rbac_administrator_grants WHERE"
                                        + " tenant_designation_id='"
                                        + designation
                                        + "' AND capability='MANAGE'");
                    }
                    var pending =
                            executor.submit(
                                    () ->
                                            service.mutate(
                                                    initialBrowser.token(LoginApplication.CORE),
                                                    tenant,
                                                    app,
                                                    UUID.randomUUID(),
                                                    JdbcProfileAssignments.Action.JOIN_BRANCH,
                                                    input(member, branch, null, null),
                                                    password));
                    Thread.sleep(150);
                    assertTrue(!pending.isDone());
                    first.commit();
                    assertEquals(
                            "DENIED",
                            pending.get(8, java.util.concurrent.TimeUnit.SECONDS).outcome());
                }
                assertTrue(service.workspace(token, tenant, app, member).history().size() >= 4);
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static void assignmentCommand(
            java.sql.Connection connection,
            UUID actor,
            UUID tenant,
            UUID app,
            UUID request,
            JdbcProfileAssignments.Action action,
            JdbcProfileAssignments.Input input)
            throws SQLException {
        try (var query =
                connection.prepareStatement(
                        "INSERT INTO"
                            + " security.profile_assignment_operations(request_id,actor_user_id,tenant_id,application_id,action,membership_id,branch_id,branch_membership_id,assignment_id,expected_updated_at,expected_revision,profile_ids)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            JdbcProfileAssignments.bind(
                    query, connection, actor, tenant, app, request, action, input);
            query.executeUpdate();
        }
    }

    private static UUID branch(java.sql.Statement sql, UUID tenant, String code) throws Exception {
        UUID id = UUID.randomUUID();
        sql.execute(
                "INSERT INTO tenant.branches(id,tenant_id,code,name,status) VALUES('"
                        + id
                        + "','"
                        + tenant
                        + "','"
                        + code
                        + "','Branch "
                        + code
                        + "','ACTIVE')");
        return id;
    }

    private static java.time.Instant version(java.sql.Connection c, UUID bm) throws Exception {
        return java.time.OffsetDateTime.parse(
                        scalar(
                                        c,
                                        "SELECT updated_at FROM tenant.branch_memberships WHERE"
                                                + " id='"
                                                + bm
                                                + "'")
                                .replace(" ", "T")
                                .replaceAll("([+-][0-9]{2})$", "$1:00"))
                .toInstant();
    }

    private static JdbcProfileAssignments.Input input(
            UUID m, UUID b, UUID bm, java.time.Instant version, UUID... profiles) {
        return new JdbcProfileAssignments.Input(
                m, b, bm, null, version, null, java.util.List.of(profiles));
    }

    private static String assignmentBody(
            UUID request, JdbcProfileAssignments.Input input, char[] password) {
        return JSON.writeValueAsString(
                Map.of(
                        "requestId",
                        request,
                        "membershipId",
                        input.membershipId(),
                        "branchId",
                        input.branchId(),
                        "branchMembershipId",
                        input.branchMembershipId(),
                        "expectedUpdatedAt",
                        input.expectedUpdatedAt().toString(),
                        "profileIds",
                        input.profileIds(),
                        "password",
                        password));
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JdbcTenantRbac.Input role(String code, UUID... permissions) {
        return new JdbcTenantRbac.Input(
                null,
                null,
                null,
                code,
                "Role " + code,
                null,
                java.util.List.of(permissions),
                java.util.List.of());
    }

    private static JdbcTenantRbac.Input profile(String code, UUID... roles) {
        return new JdbcTenantRbac.Input(
                null,
                null,
                null,
                code,
                "Profile " + code,
                null,
                java.util.List.of(),
                java.util.List.of(roles));
    }

    private static JdbcTenantRbac.Input update(
            UUID id, long revision, String code, UUID... permissions) {
        return new JdbcTenantRbac.Input(
                id,
                null,
                revision,
                code,
                "Role " + code,
                null,
                java.util.List.of(permissions),
                java.util.List.of());
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

    private static HttpResponse<String> submit(
            ApplicationLoginPostgresTest.Browser browser,
            String path,
            JdbcTenantRbac.Action action,
            UUID request,
            JdbcTenantRbac.Input input,
            char[] password)
            throws Exception {
        return post(
                browser,
                path
                        + "/commands/"
                        + action.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                body(request, input, password));
    }

    private static UUID resource(HttpResponse<String> response) {
        return UUID.fromString(
                JSON.readTree(response.body()).path("receipt").path("resourceId").asText());
    }

    private static void grantPermission(
            java.sql.Statement sql, UUID tenant, UUID app, UUID permission, String kind)
            throws SQLException {
        sql.execute(
                "INSERT INTO platform.rbac_permission_delegations VALUES('"
                        + tenant
                        + "','"
                        + app
                        + "','"
                        + permission
                        + "','"
                        + kind
                        + "',clock_timestamp()+interval '1 hour')");
    }

    private static void grantAdministrator(
            java.sql.Statement sql, UUID tenant, UUID app, UUID designation) throws SQLException {
        for (String cap : new String[] {"READ", "MANAGE"}) {
            sql.execute(
                    "INSERT INTO"
                        + " platform.rbac_administrator_grants(tenant_id,application_id,tenant_designation_id,capability,expires_at)"
                        + " VALUES('"
                            + tenant
                            + "','"
                            + app
                            + "','"
                            + designation
                            + "','"
                            + cap
                            + "',clock_timestamp()+interval '1 hour')");
        }
    }

    private static String scalar(java.sql.Connection connection, String query) throws Exception {
        return ApplicationLoginPostgresTest.scalar(connection, query);
    }

    private static String linkRequest(UUID request, String username, char[] password) {
        return "{\"requestId\":\""
                + request
                + "\",\"username\":"
                + (username == null ? "null" : "\"" + username + "\"")
                + ",\"password\":\""
                + new String(password)
                + "\"}";
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

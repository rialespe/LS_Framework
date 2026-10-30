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
import com.lssoluciones.lscore.identity.infrastructure.jdbc.TemporaryCredential;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@EnabledIfEnvironmentVariable(named = "LS_LOGIN_TEST_ROOT", matches = ".+")
class TenantUsersPostgresTest {
    @Test
    void newUsersAtomicDeliveryOnboardingConcurrencyAndIsolation() throws Exception {
        var fixture = new ApplicationLoginPostgresTest();
        char[] password = "Registration-401!".toCharArray();
        try (var admin = fixture.role("postgres").getConnection();
                var sql = admin.createStatement();
                var operationPool = new com.zaxxer.hikari.HikariDataSource()) {
            var registrar =
                    new JdbcManualIdentityRegistration(
                            fixture.role(JdbcManualIdentityRegistration.DATABASE_ROLE));
            var lifecycle =
                    new JdbcPasswordLifecycle(
                            fixture.role(JdbcPasswordLifecycle.ISSUER_ROLE),
                            fixture.role(JdbcPasswordLifecycle.CHANGER_ROLE),
                            Duration.ofHours(72));
            UUID owner = definitive(registrar, lifecycle, "f46-owner", password);
            UUID business = definitive(registrar, lifecycle, "f46-business", password);
            UUID delegate = definitive(registrar, lifecycle, "f46-delegate", password);
            UUID initial = definitive(registrar, lifecycle, "f46-initial", password);
            var authentication =
                    new JdbcPasswordAuthentication(
                            fixture.role(JdbcPasswordAuthentication.ROLE),
                            JdbcPasswordAuthentication.Limits.defaults());
            new JdbcPlatformBootstrap(fixture.role(JdbcPlatformBootstrap.ROLE), authentication)
                    .initialize("f46-owner", password);
            var global =
                    new JdbcPlatformExecutor(
                            fixture.role(JdbcPlatformExecutor.ROLE),
                            fixture.role(JdbcPlatformExecutor.SECURITY_LOG_ROLE),
                            authentication);
            assertEquals(
                    JdbcPlatformExecutor.Outcome.CONFIRMED,
                    global.execute(
                                    "f46-owner",
                                    password,
                                    delegate,
                                    JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN)
                            .outcome());
            UUID fiscal = UUID.randomUUID();
            UUID pos = UUID.randomUUID();
            sql.execute(
                    "INSERT INTO catalog.applications(id,code,name,status) VALUES ('"
                            + fiscal
                            + "','f46-fiscal','Fiscal fixture','PUBLISHED'),('"
                            + pos
                            + "','f46-pos','POS fixture','PUBLISHED')");
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
                                credentials("f46-owner", password));
                assertEquals(204, logged.statusCode());
                assertEquals(
                        200, browser.get(LoginApplication.CORE, "tenant-authority").statusCode());
                UUID tenant = company(browser, initial, password);
                String path = "tenant-users/tenants/" + tenant + "/users";
                var users = server.getBean(JdbcTenantUsers.class);
                var json = JsonMapper.builder().build();
                assertEquals(
                        200,
                        browser.get(LoginApplication.CORE, "tenant-users/tenants").statusCode());
                assertEquals(200, browser.get(LoginApplication.CORE, path).statusCode());
                assertEquals(
                        400, browser.get(LoginApplication.CORE, path + "?page=-1").statusCode());
                assertEquals(
                        403,
                        browser.post(
                                        LoginApplication.CORE,
                                        path,
                                        null,
                                        create(UUID.randomUUID(), "f46-no-csrf", password))
                                .statusCode());
                assertEquals(
                        403,
                        post(
                                        browser,
                                        path,
                                        create(
                                                UUID.randomUUID(),
                                                "f46-wrong-proof",
                                                "Incorrect-46!".toCharArray()))
                                .statusCode());
                assertEquals(
                        400,
                        post(
                                        browser,
                                        path,
                                        "{\"requestId\":\""
                                                + UUID.randomUUID()
                                                + "\",\"username\":\"f46-empty\",\"displayName\":\""
                                                + " \",\"password\":\""
                                                + new String(password)
                                                + "\"}")
                                .statusCode());
                UUID request = UUID.randomUUID();
                var created = post(browser, path, create(request, "f46-new", password));
                assertEquals(201, created.statusCode());
                var result = json.readTree(created.body());
                assertEquals("CONFIRMED", result.path("outcome").asText());
                String member = result.path("change").path("membershipId").asText();
                String user = result.path("change").path("userId").asText();
                char[] temporary = result.path("temporaryPassword").asText().toCharArray();
                assertEquals(32, temporary.length);
                assertTrue(
                        created.headers()
                                .firstValue("Cache-Control")
                                .orElseThrow()
                                .contains("no-store"));
                assertEquals(
                        "PENDING",
                        scalar(admin, "SELECT status FROM identity.users WHERE id='" + user + "'"));
                assertEquals(
                        "t",
                        scalar(
                                admin,
                                "SELECT email IS NULL AND email_verified_at IS NULL FROM"
                                        + " identity.users WHERE id='"
                                        + user
                                        + "'"));
                assertEquals(
                        "t",
                        scalar(
                                admin,
                                "SELECT must_change_password AND abs(extract(epoch FROM"
                                        + " password_expires_at-password_changed_at)-259200)<1 FROM"
                                        + " identity.user_credentials WHERE user_id='"
                                        + user
                                        + "'"));
                assertEquals(
                        "PENDING",
                        scalar(
                                admin,
                                "SELECT status FROM tenant.memberships WHERE id='" + member + "'"));
                var receipt =
                        browser.get(LoginApplication.CORE, "tenant-users/operations/" + request);
                assertEquals(200, receipt.statusCode());
                assertTrue(json.readTree(receipt.body()).path("temporaryPassword").isNull());
                assertTrue(!receipt.body().contains(new String(temporary)));
                assertEquals(
                        409,
                        post(browser, path, create(request, "f46-retry", password)).statusCode());
                assertEquals(
                        409,
                        post(browser, path, create(UUID.randomUUID(), " F46-NEW ", password))
                                .statusCode());
                assertEquals(
                        409,
                        post(browser, path, create(UUID.randomUUID(), "f46-owner", password))
                                .statusCode());
                assertEquals(
                        "0",
                        scalar(
                                admin,
                                "SELECT count(*) FROM tenant.memberships WHERE tenant_id='"
                                        + tenant
                                        + "' AND user_id='"
                                        + owner
                                        + "'"));
                assertEquals(
                        422,
                        post(browser, path + "/" + member + "/activate", operation(password))
                                .statusCode());
                assertThrows(
                        RuntimeException.class, () -> authentication.verify("f46-new", temporary));
                assertEquals(
                        401,
                        post(browser, "login", credentials("f46-new", temporary)).statusCode());
                char[] weak = "short1!".toCharArray();
                assertThrows(
                        RuntimeException.class,
                        () -> lifecycle.exchange("f46-new", temporary, weak));
                // Exchange is one-use even across independent connections, using F04 row locks.
                var barrier = new java.util.concurrent.CountDownLatch(1);
                var exchanges = new ArrayList<java.util.concurrent.CompletableFuture<Boolean>>();
                for (int i = 0; i < 2; i++) {
                    exchanges.add(
                            java.util.concurrent.CompletableFuture.supplyAsync(
                                    () -> {
                                        try {
                                            barrier.await();
                                            lifecycle.exchange("f46-new", temporary, password);
                                            return true;
                                        } catch (Exception failure) {
                                            return false;
                                        }
                                    }));
                }
                barrier.countDown();
                int exchanged = 0;
                for (var attempt : exchanges) {
                    if (attempt.get(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        exchanged++;
                    }
                }
                assertEquals(1, exchanged);
                Arrays.fill(temporary, '\0');
                assertEquals(UUID.fromString(user), authentication.verify("f46-new", password));
                assertEquals(
                        "PENDING",
                        scalar(
                                admin,
                                "SELECT status FROM tenant.memberships WHERE id='" + member + "'"));
                assertEquals(
                        "t",
                        scalar(
                                admin,
                                "SELECT NOT must_change_password AND abs(extract(epoch FROM"
                                    + " password_expires_at-password_changed_at)-7776000)<1 FROM"
                                    + " identity.user_credentials WHERE user_id='"
                                        + user
                                        + "'"));
                assertEquals(
                        200,
                        post(browser, path + "/" + member + "/activate", operation(password))
                                .statusCode());
                assertEquals(
                        "ACTIVE",
                        scalar(
                                admin,
                                "SELECT status FROM tenant.memberships WHERE id='" + member + "'"));
                assertEquals(
                        409,
                        post(browser, path + "/" + member + "/activate", operation(password))
                                .statusCode());
                assertEquals(
                        409,
                        post(browser, path + "/" + member + "/reissue", operation(password))
                                .statusCode());
                assertEquals(
                        "0",
                        scalar(
                                admin,
                                "SELECT count(*) FROM tenant.branch_memberships WHERE"
                                        + " membership_id='"
                                        + member
                                        + "'"));
                assertEquals(
                        "0",
                        scalar(
                                admin,
                                "SELECT count(*) FROM tenant.administrators WHERE membership_id='"
                                        + member
                                        + "'"));
                assertEquals(
                        "0",
                        scalar(
                                admin,
                                "SELECT count(*) FROM security.branch_profile_assignments a JOIN"
                                    + " tenant.branch_memberships b ON b.id=a.branch_membership_id"
                                    + " WHERE b.membership_id='"
                                        + member
                                        + "'"));
                var newcomer = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(
                        204,
                        post(newcomer, "login", credentials("f46-new", password)).statusCode());
                assertEquals(403, newcomer.get(LoginApplication.CORE, path).statusCode());
                assertEquals(
                        403,
                        newcomer.get(LoginApplication.CORE, "tenant-users/tenants").statusCode());
                var contexts =
                        new JdbcSessionContexts(server.getBean(JdbcApplicationSessions.class));
                var fiscalToken =
                        server.getBean(JdbcApplicationSessions.class)
                                .login(LoginApplication.FISCAL, "f46-new", password);
                assertTrue(contexts.tenants(LoginApplication.FISCAL, fiscalToken).isEmpty());
                // Valid enterprise administrator, independently scoped company selector and detail.
                var enterprise = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(
                        204,
                        post(enterprise, "login", credentials("f46-initial", password))
                                .statusCode());
                var enterpriseCreated =
                        post(
                                enterprise,
                                path,
                                create(UUID.randomUUID(), "f46-enterprise", password));
                assertEquals(201, enterpriseCreated.statusCode());
                String enterpriseMember =
                        json.readTree(enterpriseCreated.body())
                                .path("change")
                                .path("membershipId")
                                .asText();
                UUID otherTenant = company(browser, business, password);
                String otherPath = "tenant-users/tenants/" + otherTenant + "/users";
                assertEquals(403, enterprise.get(LoginApplication.CORE, otherPath).statusCode());
                assertEquals(
                        403,
                        post(
                                        enterprise,
                                        otherPath,
                                        create(UUID.randomUUID(), "f46-cross", password))
                                .statusCode());
                assertEquals(
                        404,
                        enterprise
                                .get(LoginApplication.CORE, path + "/" + UUID.randomUUID())
                                .statusCode());
                assertTrue(
                        !enterprise
                                .get(LoginApplication.CORE, "tenant-users/tenants")
                                .body()
                                .contains(otherTenant.toString()));
                assertEquals(
                        404,
                        browser.get(LoginApplication.CORE, otherPath + "/" + enterpriseMember)
                                .statusCode());
                var delegated = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(
                        204,
                        post(delegated, "login", credentials("f46-delegate", password))
                                .statusCode());
                assertEquals(403, delegated.get(LoginApplication.CORE, path).statusCode());
                assertEquals(
                        403,
                        post(
                                        delegated,
                                        path,
                                        create(UUID.randomUUID(), "f46-not-granted", password))
                                .statusCode());
                sql.execute(
                        "INSERT INTO platform.tenant_user_grants(designation_id,capability) SELECT"
                                + " id,c FROM platform.administrators CROSS JOIN"
                                + " (VALUES('READ'),('MANAGE')) v(c) WHERE user_id='"
                                + delegate
                                + "' AND status='ACTIVE'");
                try (var withMail =
                        users.mutate(
                                delegated.token(LoginApplication.CORE),
                                tenant,
                                UUID.randomUUID(),
                                JdbcTenantUsers.Action.CREATE,
                                null,
                                new ManualIdentityInput(
                                        "f46-mail", "Mail optional", "optional@example.test"),
                                password)) {
                    assertEquals("CONFIRMED", withMail.outcome());
                    assertEquals(
                            "t",
                            scalar(
                                    admin,
                                    "SELECT email='optional@example.test' AND email_verified_at IS"
                                            + " NULL FROM identity.users WHERE id='"
                                            + withMail.change().userId()
                                            + "'"));
                }
                // Lost delivery: explicit new operation rotates the credential, never recovers it.
                String oldTemporary =
                        json.readTree(enterpriseCreated.body()).path("temporaryPassword").asText();
                var reissued =
                        post(
                                browser,
                                path + "/" + enterpriseMember + "/reissue",
                                operation(password));
                assertEquals(200, reissued.statusCode());
                char[] replacement =
                        json.readTree(reissued.body())
                                .path("temporaryPassword")
                                .asText()
                                .toCharArray();
                assertNotEquals(oldTemporary, new String(replacement));
                assertThrows(
                        RuntimeException.class,
                        () ->
                                lifecycle.exchange(
                                        "f46-enterprise", oldTemporary.toCharArray(), password));
                assertEquals(
                        204,
                        post(
                                        browser,
                                        "tenant-users/exchange",
                                        "{\"username\":\"f46-enterprise\",\"temporaryPassword\":\""
                                                + new String(replacement)
                                                + "\",\"newPassword\":\""
                                                + new String(password)
                                                + "\"}")
                                .statusCode());
                Arrays.fill(replacement, '\0');
                var activationA =
                        java.util.concurrent.CompletableFuture.supplyAsync(
                                () ->
                                        users.mutate(
                                                browser.token(LoginApplication.CORE),
                                                tenant,
                                                UUID.randomUUID(),
                                                JdbcTenantUsers.Action.ACTIVATE,
                                                UUID.fromString(enterpriseMember),
                                                null,
                                                password));
                var activationB =
                        java.util.concurrent.CompletableFuture.supplyAsync(
                                () ->
                                        users.mutate(
                                                delegated.token(LoginApplication.CORE),
                                                tenant,
                                                UUID.randomUUID(),
                                                JdbcTenantUsers.Action.ACTIVATE,
                                                UUID.fromString(enterpriseMember),
                                                null,
                                                password));
                try (var first = activationA.get(10, java.util.concurrent.TimeUnit.SECONDS);
                        var second = activationB.get(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    assertEquals(
                            1L,
                            java.util.stream.Stream.of(first, second)
                                    .filter(value -> "CONFIRMED".equals(value.outcome()))
                                    .count());
                    assertEquals(
                            1L,
                            java.util.stream.Stream.of(first, second)
                                    .filter(value -> "CONFLICT".equals(value.outcome()))
                                    .count());
                }
                assertEquals(
                        "1",
                        scalar(
                                admin,
                                "SELECT count(*) FROM tenant.memberships WHERE tenant_id='"
                                        + tenant
                                        + "' AND id='"
                                        + enterpriseMember
                                        + "'"));
                assertEquals(
                        422,
                        post(
                                        browser,
                                        "tenant-users/exchange",
                                        "{\"username\":\"f46-enterprise\",\"temporaryPassword\":\""
                                                + oldTemporary
                                                + "\",\"newPassword\":\""
                                                + new String(password)
                                                + "\"}")
                                .statusCode());
                // Faults in credential, membership and success audit must roll back identity as
                // well.
                for (String table :
                        new String[] {
                            "identity.user_credentials", "tenant.memberships", "audit.events"
                        }) {
                    sql.execute(
                            "CREATE FUNCTION tenant.fixture_user_fault() RETURNS trigger LANGUAGE"
                                + " plpgsql AS $$ BEGIN IF current_user='ls_core_tenant_users_dev'"
                                + " THEN RAISE EXCEPTION 'Owned injected fault'; END IF; RETURN"
                                + " NEW; END $$");
                    sql.execute(
                            "CREATE TRIGGER zz_fixture_user_fault BEFORE INSERT ON "
                                    + table
                                    + " FOR EACH ROW EXECUTE FUNCTION tenant.fixture_user_fault()");
                    try {
                        assertEquals(
                                503,
                                post(
                                                browser,
                                                path,
                                                create(UUID.randomUUID(), "f46-rollback", password))
                                        .statusCode());
                    } finally {
                        sql.execute("DROP TRIGGER zz_fixture_user_fault ON " + table);
                        sql.execute("DROP FUNCTION tenant.fixture_user_fault()");
                    }
                    assertEquals(
                            "0",
                            scalar(
                                    admin,
                                    "SELECT count(*) FROM identity.users WHERE"
                                            + " username='f46-rollback'"));
                }
                // Concurrent duplicate username / membership: a single new identity can commit.
                try (var material = new TemporaryCredential();
                        var first = fixture.role(JdbcTenantUsers.ROLE).getConnection();
                        var second = fixture.role(JdbcTenantUsers.ROLE).getConnection()) {
                    first.setAutoCommit(false);
                    second.setAutoCommit(false);
                    insert(first, owner, tenant, "f46-race", UUID.randomUUID(), material.hash());
                    var competing =
                            java.util.concurrent.CompletableFuture.supplyAsync(
                                    () -> {
                                        try {
                                            insert(
                                                    second,
                                                    initial,
                                                    tenant,
                                                    " F46-RACE ",
                                                    UUID.randomUUID(),
                                                    material.hash());
                                            second.commit();
                                            return "COMMITTED";
                                        } catch (SQLException failure) {
                                            try {
                                                second.rollback();
                                            } catch (SQLException ignored) {
                                            }
                                            return failure.getSQLState();
                                        }
                                    });
                    Thread.sleep(150);
                    assertTrue(!competing.isDone());
                    first.commit();
                    assertEquals("23505", competing.get(5, java.util.concurrent.TimeUnit.SECONDS));
                }
                assertEquals(
                        "1",
                        scalar(
                                admin,
                                "SELECT count(*) FROM identity.users WHERE"
                                        + " lower(btrim(username))='f46-race'"));
                // Suspension commits before the waiting registrar: no pending/active orphan
                // admitted.
                try (var suspending = fixture.role(JdbcTenantLifecycle.ROLE).getConnection()) {
                    suspending.setAutoCommit(false);
                    try (var statement =
                            suspending.prepareStatement(
                                    "INSERT INTO"
                                        + " tenant.lifecycle_operations(request_id,actor_user_id,tenant_id,action,previous_status,resulting_status)"
                                        + " VALUES(?,?,?,'SUSPEND','','')")) {
                        statement.setObject(1, UUID.randomUUID());
                        statement.setObject(2, owner);
                        statement.setObject(3, tenant);
                        statement.executeUpdate();
                    }
                    var waiting =
                            java.util.concurrent.CompletableFuture.supplyAsync(
                                    () ->
                                            users.mutate(
                                                    browser.token(LoginApplication.CORE),
                                                    tenant,
                                                    UUID.randomUUID(),
                                                    JdbcTenantUsers.Action.CREATE,
                                                    null,
                                                    new ManualIdentityInput(
                                                            "f46-suspended-race", "Race", null),
                                                    password));
                    Thread.sleep(150);
                    assertTrue(!waiting.isDone());
                    suspending.commit();
                    try (var denied = waiting.get(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        assertEquals("INCOMPLETE_REQUIREMENTS", denied.outcome());
                    }
                }
                assertEquals(
                        "0",
                        scalar(
                                admin,
                                "SELECT count(*) FROM identity.users WHERE"
                                        + " username='f46-suspended-race'"));
                assertEquals(
                        200,
                        post(browser, "tenants/" + tenant + "/reactivate", operation(password))
                                .statusCode());
                // Authority revoked while registration is waiting behind the same F06/F07 barriers.
                try (var revoking = fixture.role(JdbcTenantAdministrators.ROLE).getConnection()) {
                    revoking.setAutoCommit(false);
                    try (var statement =
                            revoking.prepareStatement(
                                    "INSERT INTO"
                                        + " tenant.administrator_operations(request_id,actor_user_id,tenant_id,action,target_id,candidate_username,designation_id,resulting_status)"
                                        + " VALUES(?,?,?,'DESIGNATE',NULL,'f46-business',?,'')")) {
                        statement.setObject(1, UUID.randomUUID());
                        statement.setObject(2, owner);
                        statement.setObject(3, tenant);
                        statement.setObject(4, UUID.randomUUID());
                        statement.executeUpdate();
                    }
                    String initialDesignation =
                            scalar(
                                    admin,
                                    "SELECT designation_id FROM tenant.initial_registrations WHERE"
                                            + " tenant_id='"
                                            + tenant
                                            + "'");
                    try (var statement =
                            revoking.prepareStatement(
                                    "INSERT INTO"
                                        + " tenant.administrator_operations(request_id,actor_user_id,tenant_id,action,target_id,candidate_username,designation_id,resulting_status)"
                                        + " VALUES(?,?,?,'REVOKE',?,NULL,?,'')")) {
                        statement.setObject(1, UUID.randomUUID());
                        statement.setObject(2, owner);
                        statement.setObject(3, tenant);
                        statement.setObject(4, UUID.fromString(initialDesignation));
                        statement.setObject(5, UUID.randomUUID());
                        statement.executeUpdate();
                    }
                    var waiting =
                            java.util.concurrent.CompletableFuture.supplyAsync(
                                    () ->
                                            users.mutate(
                                                    enterprise.token(LoginApplication.CORE),
                                                    tenant,
                                                    UUID.randomUUID(),
                                                    JdbcTenantUsers.Action.CREATE,
                                                    null,
                                                    new ManualIdentityInput(
                                                            "f46-revoked-race", "Race", null),
                                                    password));
                    Thread.sleep(150);
                    assertTrue(!waiting.isDone());
                    revoking.commit();
                    try (var denied = waiting.get(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        assertEquals("DENIED", denied.outcome());
                    }
                }
                assertEquals(
                        "0",
                        scalar(
                                admin,
                                "SELECT count(*) FROM identity.users WHERE"
                                        + " username='f46-revoked-race'"));
                // Driver discards a real COMMIT acknowledgement; secret must be destroyed, never
                // replayed.
                var uncertain =
                        new JdbcTenantUsers(
                                server.getBean(JdbcApplicationSessions.class),
                                authentication,
                                uncertain(fixture.role(JdbcTenantUsers.ROLE)),
                                fixture.role(JdbcTenantUsers.LOG_ROLE));
                UUID lost = UUID.randomUUID();
                try (var unknown =
                        uncertain.mutate(
                                browser.token(LoginApplication.CORE),
                                tenant,
                                lost,
                                JdbcTenantUsers.Action.CREATE,
                                null,
                                new ManualIdentityInput("f46-lost", "Lost delivery", null),
                                password)) {
                    assertEquals("UNKNOWN", unknown.outcome());
                    assertNull(unknown.temporaryPassword());
                    assertNull(unknown.change());
                }
                var reconciled = users.find(browser.token(LoginApplication.CORE), lost);
                assertEquals("CONFIRMED", reconciled.outcome());
                assertNull(reconciled.temporaryPassword());
                assertNull(users.find(delegated.token(LoginApplication.CORE), lost));
                assertEquals(
                        409, post(browser, path, create(lost, "f46-lost", password)).statusCode());
                assertEquals(
                        "1",
                        scalar(
                                admin,
                                "SELECT count(*) FROM tenant.user_operations WHERE request_id='"
                                        + lost
                                        + "'"));
                assertEquals(
                        "0",
                        scalar(
                                admin,
                                "SELECT count(*) FROM tenant.user_operations WHERE temporary_hash"
                                    + " IS NOT NULL OR username IS NOT NULL OR email IS NOT NULL"));
                assertEquals(
                        "0",
                        scalar(
                                admin,
                                "SELECT count(*) FROM tenant.user_operations o LEFT JOIN"
                                        + " audit.events e ON e.correlation_id=o.request_id AND"
                                        + " e.outcome='SUCCESS' WHERE e.id IS NULL"));
                assertEquals(
                        "t",
                        scalar(
                                admin,
                                "SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM"
                                    + " pg_class WHERE oid IN"
                                    + " ('tenant.user_operations'::regclass,'platform.tenant_user_grants'::regclass,'identity.users'::regclass,'identity.user_credentials'::regclass,'tenant.memberships'::regclass)"));
                for (String role :
                        new String[] {
                            JdbcTenantUsers.ROLE, JdbcApplicationSessions.ROLE, "ls_core_app_dev"
                        }) {
                    try (var connection = fixture.role(role).getConnection();
                            var direct = connection.createStatement()) {
                        for (String forbidden :
                                new String[] {
                                    "UPDATE tenant.memberships SET status='ACTIVE'",
                                    "INSERT INTO identity.users(id,username,display_name,email)"
                                            + " VALUES(gen_random_uuid(),'f46-direct','Direct',NULL)",
                                    "UPDATE identity.user_credentials SET password_hash='bad'",
                                    "UPDATE tenant.user_operations SET resulting_status='ACTIVE'",
                                    "DELETE FROM audit.events"
                                }) {
                            assertEquals(
                                    "42501",
                                    assertThrows(
                                                    SQLException.class,
                                                    () -> direct.executeUpdate(forbidden))
                                            .getSQLState());
                        }
                    }
                }
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static String scalar(java.sql.Connection connection, String query) throws Exception {
        return ApplicationLoginPostgresTest.scalar(connection, query);
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

    private static String create(UUID request, String username, char[] password) {
        return "{\"requestId\":\""
                + request
                + "\",\"username\":\""
                + username
                + "\",\"displayName\":\"New member\",\"password\":\""
                + new String(password)
                + "\"}";
    }

    private static void insert(
            java.sql.Connection connection,
            UUID actor,
            UUID tenant,
            String username,
            UUID request,
            String hash)
            throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        "INSERT INTO"
                            + " tenant.user_operations(request_id,actor_user_id,tenant_id,action,user_id,membership_id,username,display_name,temporary_hash,resulting_status)"
                            + " VALUES(?,?,?,'CREATE',?,?,?,'New member',?,'PENDING')")) {
            statement.setObject(1, request);
            statement.setObject(2, actor);
            statement.setObject(3, tenant);
            statement.setObject(4, UUID.randomUUID());
            statement.setObject(5, UUID.randomUUID());
            statement.setString(6, username);
            statement.setString(7, hash);
            statement.executeUpdate();
        }
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

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
class IdentityLinksPostgresTest {
    @Test
    void authenticatedConsentNeutralRequestsAndTransactionalIsolation() throws Exception {
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
            UUID owner = definitive(registrar, lifecycle, "f47-owner", password);
            UUID business = definitive(registrar, lifecycle, "f47-business", password);
            UUID delegate = definitive(registrar, lifecycle, "f47-delegate", password);
            UUID initial = definitive(registrar, lifecycle, "f47-initial", password);
            UUID outsider = definitive(registrar, lifecycle, "f47-outsider", password);
            var authentication =
                    new JdbcPasswordAuthentication(
                            fixture.role(JdbcPasswordAuthentication.ROLE),
                            JdbcPasswordAuthentication.Limits.defaults());
            new JdbcPlatformBootstrap(fixture.role(JdbcPlatformBootstrap.ROLE), authentication)
                    .initialize("f47-owner", password);
            var global =
                    new JdbcPlatformExecutor(
                            fixture.role(JdbcPlatformExecutor.ROLE),
                            fixture.role(JdbcPlatformExecutor.SECURITY_LOG_ROLE),
                            authentication);
            assertEquals(
                    JdbcPlatformExecutor.Outcome.CONFIRMED,
                    global.execute(
                                    "f47-owner",
                                    password,
                                    delegate,
                                    JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN)
                            .outcome());
            UUID fiscal = UUID.randomUUID();
            UUID pos = UUID.randomUUID();
            sql.execute(
                    "INSERT INTO catalog.applications(id,code,name,status) VALUES ('"
                            + fiscal
                            + "','f47-fiscal','Fiscal fixture','PUBLISHED'),('"
                            + pos
                            + "','f47-pos','POS fixture','PUBLISHED')");
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
                                credentials("f47-owner", password));
                assertEquals(204, logged.statusCode());
                assertEquals(
                        200, browser.get(LoginApplication.CORE, "tenant-authority").statusCode());
                UUID tenant = company(browser, initial, password);
                UUID other = company(browser, outsider, password);
                var links = server.getBean(JdbcIdentityLinks.class);
                var json = JsonMapper.builder().build();
                String path = "identity-links/tenants/" + tenant + "/requests";
                var holder = new ApplicationLoginPostgresTest.Browser(client, base);
                var foreign = new ApplicationLoginPostgresTest.Browser(client, base);
                var delegated = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(204, post(holder, "login", credentials("f47-business", password)).statusCode());
                assertEquals(204, post(foreign, "login", credentials("f47-outsider", password)).statusCode());
                assertEquals(204, post(delegated, "login", credentials("f47-delegate", password)).statusCode());
                assertEquals(200, holder.get(LoginApplication.CORE, "identity-links/received").statusCode());
                assertEquals(403, holder.get(LoginApplication.CORE, "tenant-users/tenants").statusCode());
                assertEquals(400, holder.get(LoginApplication.CORE, "identity-links/received?page=-1").statusCode());
                assertEquals(401, new ApplicationLoginPostgresTest.Browser(client, base)
                        .get(LoginApplication.CORE, "identity-links/received").statusCode());
                assertEquals(403, browser.post(LoginApplication.CORE, path, null,
                        linkRequest(UUID.randomUUID(), "f47-business", password)).statusCode());
                assertEquals(403, post(browser, path, linkRequest(UUID.randomUUID(), "f47-business",
                        "Wrong-47!".toCharArray())).statusCode());
                assertEquals(403, post(foreign, path, linkRequest(UUID.randomUUID(), "f47-business", password)).statusCode());
                assertEquals(403, post(delegated, path, linkRequest(UUID.randomUUID(), "f47-business", password)).statusCode());
                assertEquals(403, foreign.get(LoginApplication.CORE,
                        "identity-links/tenants/" + tenant + "/sent").statusCode());
                UUID request = UUID.randomUUID();
                var submitted = post(browser, path, linkRequest(request, " F47-BUSINESS ", password));
                assertEquals(200, submitted.statusCode());
                var receipt = json.readTree(submitted.body());
                assertEquals("SUBMITTED", receipt.path("receipt").path("status").asText());
                assertTrue(receipt.path("receipt").path("linkId").isNull());
                var absent = post(browser, path, linkRequest(UUID.randomUUID(), "f47-absent", password));
                assertEquals(200, absent.statusCode());
                assertEquals("SUBMITTED", json.readTree(absent.body()).path("receipt").path("status").asText());
                assertTrue(json.readTree(absent.body()).path("receipt").path("linkId").isNull());
                assertEquals(200, post(browser, path, linkRequest(request, " F47-BUSINESS ", password)).statusCode());
                assertEquals(200, post(browser, path, linkRequest(UUID.randomUUID(), "f47-business", password)).statusCode());
                UUID link = inbox(links, holder, tenant);
                assertEquals("1", scalar(admin, "SELECT count(*) FROM tenant.identity_link_requests WHERE tenant_id='" + tenant + "' AND user_id='" + business + "'"));
                assertEquals("0", scalar(admin, "SELECT count(*) FROM tenant.memberships WHERE tenant_id='" + tenant + "' AND user_id='" + business + "'"));
                assertEquals(404, post(browser, "identity-links/received/" + link + "/accept", operation(password)).statusCode());
                assertEquals(404, post(foreign, "identity-links/received/" + link + "/accept", operation(password)).statusCode());
                assertEquals(404, foreign.get(LoginApplication.CORE, "identity-links/operations/" + request).statusCode());
                // An existing membership in B survives acceptance in A unchanged.
                UUID previous = UUID.randomUUID();
                sql.execute("INSERT INTO tenant.memberships(id,tenant_id,user_id,status) VALUES('" + previous + "','" + other + "','" + business + "','ACTIVE')");
                String before = scalar(admin, "SELECT row_to_json(m)::text FROM tenant.memberships m WHERE id='" + previous + "'");
                UUID decision = UUID.randomUUID();
                String acceptPath = "identity-links/received/" + link + "/accept";
                var accepted = post(holder, acceptPath, linkRequest(decision, null, password));
                assertEquals(200, accepted.statusCode());
                assertEquals("ACCEPTED", json.readTree(accepted.body()).path("receipt").path("status").asText());
                assertEquals(200, post(holder, acceptPath, linkRequest(decision, null, password)).statusCode());
                assertEquals(409, post(holder, acceptPath, operation(password)).statusCode());
                assertEquals(409, post(holder, "identity-links/received/" + link + "/reject", operation(password)).statusCode());
                assertEquals(before, scalar(admin, "SELECT row_to_json(m)::text FROM tenant.memberships m WHERE id='" + previous + "'"));
                assertEquals("1", scalar(admin, "SELECT count(*) FROM tenant.memberships WHERE tenant_id='" + tenant + "' AND user_id='" + business + "' AND status='ACTIVE' AND accepted_at IS NOT NULL"));
                assertEquals("0", scalar(admin, "SELECT count(*) FROM tenant.branch_memberships b JOIN tenant.memberships m ON m.id=b.membership_id WHERE m.tenant_id='" + tenant + "' AND m.user_id='" + business + "'"));
                assertEquals("0", scalar(admin, "SELECT count(*) FROM tenant.administrators a JOIN tenant.memberships m ON m.id=a.membership_id WHERE m.tenant_id='" + tenant + "' AND m.user_id='" + business + "'"));
                assertEquals(200, holder.get(LoginApplication.CORE, "identity-links/operations/" + decision).statusCode());
                // Rejection persists evidence and cannot be reused to create a membership.
                assertEquals(200, post(browser, path, linkRequest(UUID.randomUUID(), "f47-outsider", password)).statusCode());
                UUID rejectedLink = inbox(links, foreign, tenant);
                assertEquals(200, post(foreign, "identity-links/received/" + rejectedLink + "/reject", operation(password)).statusCode());
                assertEquals(409, post(foreign, "identity-links/received/" + rejectedLink + "/accept", operation(password)).statusCode());
                assertEquals("0", scalar(admin, "SELECT count(*) FROM tenant.memberships WHERE tenant_id='" + tenant + "' AND user_id='" + outsider + "'"));
                // Elapsed time fixture only; no production clock or protected migration changes.
                assertEquals(200, post(browser, path, linkRequest(UUID.randomUUID(), "f47-delegate", password)).statusCode());
                UUID expired = inbox(links, delegated, tenant);
                sql.execute("SET session_replication_role=replica");
                try {
                    sql.execute("UPDATE tenant.identity_link_requests SET created_at=clock_timestamp()-interval '8 days',expires_at=clock_timestamp()-interval '1 day' WHERE id='" + expired + "'");
                } finally {
                    sql.execute("SET session_replication_role=origin");
                }
                assertEquals("EXPIRED", links.received(delegated.token(LoginApplication.CORE), 0).requests().getFirst().status());
                var elapsed = post(delegated, "identity-links/received/" + expired + "/accept", operation(password));
                assertEquals(200, elapsed.statusCode());
                assertEquals("EXPIRED", json.readTree(elapsed.body()).path("receipt").path("status").asText());
                assertEquals("0", scalar(admin, "SELECT count(*) FROM tenant.memberships WHERE tenant_id='" + tenant + "' AND user_id='" + delegate + "'"));
                assertEquals("1", scalar(admin, "SELECT count(*) FROM audit.events WHERE action_code='IDENTITY_LINK_EXPIRED' AND correlation_id='" + expired + "'"));
                // Suspended tenants and stale administrative authority fail at the command boundary.
                assertEquals(200, post(browser, path, linkRequest(UUID.randomUUID(), "f47-delegate", password)).statusCode());
                UUID waiting = inbox(links, delegated, tenant);
                assertNotEquals(expired, waiting);
                assertEquals(200, post(browser, "tenants/" + tenant + "/suspend", operation(password)).statusCode());
                assertEquals(422, post(delegated, "identity-links/received/" + waiting + "/accept", operation(password)).statusCode());
                assertEquals(422, post(browser, path, linkRequest(UUID.randomUUID(), "f47-owner", password)).statusCode());
                assertEquals(200, post(browser, "tenants/" + tenant + "/reactivate", operation(password)).statusCode());
                // Audit failure rolls back both receipt and the newly created pending request.
                sql.execute("CREATE FUNCTION audit.fail_link_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action_code='IDENTITY_LINK_REQUEST' AND NEW.outcome='SUCCESS' THEN RAISE EXCEPTION 'Owned failure'; END IF; RETURN NEW; END $$");
                sql.execute("CREATE TRIGGER fail_link_fixture BEFORE INSERT ON audit.events FOR EACH ROW EXECUTE FUNCTION audit.fail_link_fixture()");
                UUID rolledBack = UUID.randomUUID();
                assertEquals(503, post(browser, path, linkRequest(rolledBack, "f47-owner", password)).statusCode());
                sql.execute("DROP TRIGGER fail_link_fixture ON audit.events");
                sql.execute("DROP FUNCTION audit.fail_link_fixture()");
                assertEquals("0", scalar(admin, "SELECT count(*) FROM tenant.identity_link_operations WHERE request_id='" + rolledBack + "'"));
                assertEquals("0", scalar(admin, "SELECT count(*) FROM tenant.identity_link_requests WHERE tenant_id='" + tenant + "' AND user_id='" + owner + "'"));
                // Lost acknowledgement after real COMMIT: no mutation replay, only the original actor's receipt.
                UUID lost = UUID.randomUUID();
                var uncertain = new JdbcIdentityLinks(server.getBean(JdbcApplicationSessions.class), authentication,
                        uncertain(fixture.role(JdbcIdentityLinks.ROLE)), fixture.role(JdbcIdentityLinks.LOG_ROLE));
                assertEquals("UNKNOWN", uncertain.mutate(browser.token(LoginApplication.CORE), tenant, lost,
                        JdbcIdentityLinks.Action.REQUEST, null, "f47-owner", password).outcome());
                assertEquals("CONFIRMED", links.find(browser.token(LoginApplication.CORE), lost).outcome());
                assertNull(links.find(holder.token(LoginApplication.CORE), lost));
                assertEquals("1", scalar(admin, "SELECT count(*) FROM tenant.identity_link_requests WHERE tenant_id='" + tenant + "' AND user_id='" + owner + "'"));
                UUID raceLink = inbox(links, browser, tenant);
                // Two physical JDBC connections contend on the same decision; locks remain until COMMIT.
                try (var first = fixture.role(JdbcIdentityLinks.ROLE).getConnection();
                        var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                    com.lssoluciones.lscore.platform.PlatformDatabase.begin(first, JdbcIdentityLinks.ROLE, false);
                    command(first, owner, tenant, raceLink, "ACCEPT", UUID.randomUUID(), null);
                    var second = executor.submit(() -> {
                        try (var connection = fixture.role(JdbcIdentityLinks.ROLE).getConnection()) {
                            com.lssoluciones.lscore.platform.PlatformDatabase.begin(connection, JdbcIdentityLinks.ROLE, false);
                            try {
                                command(connection, owner, tenant, raceLink, "REJECT", UUID.randomUUID(), null);
                                connection.commit();
                                return "unexpected";
                            } catch (SQLException failure) {
                                connection.rollback();
                                return failure.getSQLState();
                            }
                        }
                    });
                    Thread.sleep(150);
                    assertTrue(!second.isDone());
                    first.commit();
                    assertEquals("P2901", second.get(8, java.util.concurrent.TimeUnit.SECONDS));
                }
                // A suspended or PENDING existing membership is never activated through consent.
                UUID fresh = definitive(registrar, lifecycle, "f47-late", password);
                var late = new ApplicationLoginPostgresTest.Browser(client, base);
                assertEquals(204, post(late, "login", credentials("f47-late", password)).statusCode());
                assertEquals(200, post(browser, path, linkRequest(UUID.randomUUID(), "f47-late", password)).statusCode());
                UUID lateLink = inbox(links, late, tenant);
                sql.execute("INSERT INTO tenant.memberships(tenant_id,user_id,status) VALUES('" + tenant + "','" + fresh + "','PENDING')");
                assertEquals(409, post(late, "identity-links/received/" + lateLink + "/accept", operation(password)).statusCode());
                sql.execute("UPDATE tenant.memberships SET status='SUSPENDED' WHERE tenant_id='" + tenant + "' AND user_id='" + fresh + "'");
                assertEquals(409, post(late, "identity-links/received/" + lateLink + "/accept", operation(password)).statusCode());
                assertEquals("SUSPENDED", scalar(admin, "SELECT status FROM tenant.memberships WHERE tenant_id='" + tenant + "' AND user_id='" + fresh + "'"));
                // A tenant administrator cannot leave an effective invitation after revocation.
                String otherPath = "identity-links/tenants/" + other + "/requests";
                assertEquals(200, post(foreign, otherPath, linkRequest(UUID.randomUUID(), "f47-late", password)).statusCode());
                UUID stale = inbox(links, late, other);
                // Fixture simulates loss of F04 authority without mutating unrelated memberships.
                sql.execute("UPDATE identity.user_credentials SET locked_until=clock_timestamp()+interval '1 hour' WHERE user_id='" + outsider + "'");
                assertEquals(403, post(late, "identity-links/received/" + stale + "/accept", operation(password)).statusCode());
                sql.execute("UPDATE identity.user_credentials SET locked_until=NULL WHERE user_id='" + outsider + "'");
                assertEquals("2", scalar(admin, "SELECT count(*) FROM pg_class WHERE oid IN ('tenant.identity_link_requests'::regclass,'tenant.identity_link_operations'::regclass) AND relrowsecurity AND relforcerowsecurity"));
                assertEquals("0", scalar(admin, "SELECT count(*) FROM pg_proc WHERE pronamespace='tenant'::regnamespace AND proname LIKE '%identity_link%' AND prosecdef"));
                for (String role : new String[] {JdbcIdentityLinks.ROLE, "ls_core_app_dev", JdbcApplicationSessions.ROLE}) {
                    try (var connection = fixture.role(role).getConnection(); var statement = connection.createStatement()) {
                        assertThrows(SQLException.class, () -> statement.execute("INSERT INTO tenant.memberships(tenant_id,user_id,status) VALUES('" + tenant + "','" + delegate + "','ACTIVE')"));
                        assertThrows(SQLException.class, () -> statement.execute("UPDATE tenant.identity_link_requests SET status='PENDING' WHERE id='" + raceLink + "'"));
                    }
                }
                assertThrows(SQLException.class, () -> sql.execute("UPDATE audit.events SET metadata='{}'::jsonb WHERE action_code='IDENTITY_LINK_ACCEPT'"));
                assertThrows(SQLException.class, () -> sql.execute("DELETE FROM tenant.identity_link_operations WHERE request_id='" + lost + "'"));
                assertEquals("0", scalar(admin, "SELECT count(*) FROM tenant.identity_link_operations o WHERE NOT EXISTS(SELECT FROM audit.events e WHERE e.correlation_id=o.request_id AND e.outcome='SUCCESS' AND e.action_code='IDENTITY_LINK_'||o.action)"));
                assertTrue(!submitted.body().contains(business.toString()));
                assertTrue(!browser.get(LoginApplication.CORE, "identity-links/tenants/" + tenant + "/sent").body().contains(business.toString()));
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static String scalar(java.sql.Connection connection, String query) throws Exception {
        return ApplicationLoginPostgresTest.scalar(connection, query);
    }

    private static UUID inbox(JdbcIdentityLinks links, ApplicationLoginPostgresTest.Browser browser, UUID tenant) {
        return links.received(browser.token(LoginApplication.CORE), 0).requests().stream()
                .filter(value -> value.tenantId().equals(tenant) && value.status().equals("PENDING"))
                .findFirst().orElseThrow().id();
    }

    private static String linkRequest(UUID request, String username, char[] password) {
        return "{\"requestId\":\"" + request + "\",\"username\":"
                + (username == null ? "null" : "\"" + username + "\"")
                + ",\"password\":\"" + new String(password) + "\"}";
    }

    private static void command(java.sql.Connection connection, UUID actor, UUID tenant, UUID link,
            String action, UUID request, String username) throws SQLException {
        try (var query = connection.prepareStatement("INSERT INTO tenant.identity_link_operations"
                + "(request_id,actor_user_id,tenant_id,action,link_id,candidate_username,resulting_status) VALUES(?,?,?,?,?,?,'SUBMITTED')")) {
            query.setObject(1, request);
            query.setObject(2, actor);
            query.setObject(3, tenant);
            query.setString(4, action);
            query.setObject(5, link);
            query.setString(6, username);
            query.executeUpdate();
        }
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

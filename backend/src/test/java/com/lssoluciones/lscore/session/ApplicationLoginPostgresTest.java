package com.lssoluciones.lscore.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lssoluciones.lscore.LsCoreApplication;
import com.lssoluciones.lscore.identity.application.ManualIdentityInput;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcManualIdentityRegistration;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordLifecycle;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.MapPropertySource;

/** Exactly one real HTTPS/PG17 flow; the client trusts only the owned temporary test certificate. */
@EnabledIfEnvironmentVariable(named = "LS_LOGIN_TEST_ROOT", matches = ".+")
public class ApplicationLoginPostgresTest {
    public PGSimpleDataSource role(String user) throws Exception {
        Path root = Path.of(System.getenv("LS_LOGIN_TEST_ROOT")).toRealPath();
        assertEquals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath(), root.getParent());
        assertTrue(root.getFileName().toString().startsWith("ls-core-migrations-test-"));
        assertEquals("17", Files.readString(root.resolve("data/PG_VERSION")).trim());
        int port = Integer.parseInt(System.getenv("LS_LOGIN_TEST_PORT"));
        assertNotEquals(5432, port);
        assertNotEquals(55432, port);
        var pid = Files.readAllLines(root.resolve("data/postmaster.pid"));
        assertEquals(Integer.toString(port), pid.get(3));
        assertEquals(root.resolve("data").toRealPath(), Path.of(pid.get(1)).toRealPath());
        var source = new PGSimpleDataSource();
        source.setServerNames(new String[] {"127.0.0.1"});
        source.setPortNumbers(new int[] {port});
        source.setDatabaseName("ls_core_dev");
        source.setUser(user);
        source.setPassword(System.getenv("LS_LOGIN_TEST_" + user.toUpperCase(java.util.Locale.ROOT)));
        source.setConnectTimeout(2);
        source.setSocketTimeout(5);
        return source;
    }

    @Test
    void realHttpsLoginCsrfPerApplicationSessionsAndTargetedLogout() throws Exception {
        char[] password = "Login-341!".toCharArray();
        Path root = Path.of(System.getenv("LS_LOGIN_TEST_ROOT"));
        try (var admin = role("postgres").getConnection(); var sql = admin.createStatement()) {
            var registration = new JdbcManualIdentityRegistration(role(JdbcManualIdentityRegistration.DATABASE_ROLE));
            UUID user = registration.register(new ManualIdentityInput("f34-login", "Login fixture", null));
            var lifecycle = new JdbcPasswordLifecycle(role(JdbcPasswordLifecycle.ISSUER_ROLE),
                    role(JdbcPasswordLifecycle.CHANGER_ROLE), Duration.ofHours(72));
            try (var temporary = lifecycle.issue(user)) {
                char[] initial = temporary.take();
                try {
                    lifecycle.exchange("f34-login", initial, password);
                } finally {
                    Arrays.fill(initial, '\0');
                }
            }
            UUID fiscal = UUID.randomUUID();
            UUID pos = UUID.randomUUID();
            sql.execute("INSERT INTO catalog.applications(id,code,name,status) VALUES ('" + fiscal + "','f34-fiscal','LS Fiscal fixture','PUBLISHED'),('" + pos + "','f34-pos','LS Punto de Venta fixture','PUBLISHED')");
            var properties = new HashMap<String, Object>();
            properties.put("ls.identity.credentials.enabled", "true");
            properties.put("ls.login.enabled", "true");
            properties.put("ls.login.fiscal.application-id", fiscal.toString());
            properties.put("ls.login.pos.application-id", pos.toString());
            properties.put("ls.login.session-seconds", "3600");
            properties.put("ls.access.tenant-authorization.enabled", "false");
            properties.put("ls.access.branch-operation.enabled", "false");
            properties.put("ls.identity.recovery.enabled", "false");
            properties.put("server.address", "127.0.0.1");
            properties.put("server.port", "0");
            properties.put("server.ssl.enabled", "true");
            properties.put("server.ssl.key-store", root.resolve("login-test.p12").toUri().toString());
            properties.put("server.ssl.key-store-type", "PKCS12");
            properties.put("server.ssl.key-alias", "localhost");
            properties.put("server.ssl.key-store-password", System.getenv("LS_LOGIN_TEST_TLS_PASSWORD"));
            properties.put("spring.main.banner-mode", "off");
            for (var channel : Map.of("LS_AUTHENTICATION_DB_", "ls_core_password_authenticator_dev", "LS_LOGIN_DB_", JdbcApplicationSessions.ROLE).entrySet()) {
                properties.put(channel.getKey() + "HOST", "127.0.0.1");
                properties.put(channel.getKey() + "PORT", System.getenv("LS_LOGIN_TEST_PORT"));
                properties.put(channel.getKey() + "NAME", "ls_core_dev");
                properties.put(channel.getKey() + "PASSWORD", System.getenv("LS_LOGIN_TEST_" + channel.getValue().toUpperCase(java.util.Locale.ROOT)));
            }
            try (var server = new SpringApplicationBuilder(LsCoreApplication.class).profiles("test")
                    .initializers(context -> context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("owned-https-login", properties))).run();
                    var client = HttpClient.newBuilder().sslContext(trust(root.resolve("login-test.p12")))
                            .connectTimeout(Duration.ofSeconds(5)).build()) {
                int port = server.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
                URI base = URI.create("https://127.0.0.1:" + port);
                var first = new Browser(client, base);
                var second = new Browser(client, base);
                var pointOfSale = new Browser(client, base);
                String csrf = first.csrf(LoginApplication.FISCAL);
                var loggedIn = first.post(LoginApplication.FISCAL, "login", csrf, credentials(new String(password)));
                assertEquals(204, loggedIn.statusCode());
                secureCookie(loggedIn, LoginApplication.FISCAL, false);
                String original = first.token(LoginApplication.FISCAL);
                assertTrue(original.matches("[A-Za-z0-9_-]{43}"));
                assertEquals(200, first.get(LoginApplication.FISCAL, "session").statusCode());
                String hash = digest(original);
                try (var read = admin.prepareStatement("SELECT token_hash,user_id,application_id,revoked_at,"
                        + "abs(extract(epoch FROM expires_at-created_at)-3600)<2 FROM security.sessions WHERE token_hash=?")) {
                    read.setString(1, hash);
                    try (var rows = read.executeQuery()) {
                        assertTrue(rows.next());
                        assertTrue(rows.getString(1).matches("[0-9a-f]{64}") && !rows.getString(1).equals(original));
                        assertEquals(user, rows.getObject(2, UUID.class));
                        assertEquals(fiscal, rows.getObject(3, UUID.class));
                        assertTrue(rows.getTimestamp(4) == null && rows.getBoolean(5));
                    }
                }
                var crossApp = HttpRequest.newBuilder(base.resolve(LoginApplication.POS.path() + "/session"))
                        .timeout(Duration.ofSeconds(10)).header("Cookie", LoginApplication.POS.sessionCookie() + "=" + original).GET().build();
                assertEquals(401, client.send(crossApp, HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(403, first.post(LoginApplication.FISCAL, "login", null, credentials(new String(password))).statusCode());
                assertEquals("0", scalar(admin, "SELECT failed_attempts FROM identity.user_credentials WHERE user_id='" + user + "'"));
                csrf = first.csrf(LoginApplication.FISCAL);
                assertEquals(401, first.post(LoginApplication.FISCAL, "login", csrf, credentials("Incorrect-342!")).statusCode());
                assertEquals("1", scalar(admin, "SELECT failed_attempts FROM identity.user_credentials WHERE user_id='" + user + "'"));
                assertEquals(204, second.post(LoginApplication.FISCAL, "login", second.csrf(LoginApplication.FISCAL), credentials(new String(password))).statusCode());
                assertTrue(!original.equals(second.token(LoginApplication.FISCAL)));
                assertEquals(204, pointOfSale.post(LoginApplication.POS, "login", pointOfSale.csrf(LoginApplication.POS), credentials(new String(password))).statusCode());
                secureCookie(pointOfSale.last, LoginApplication.POS, false);
                assertEquals(200, pointOfSale.get(LoginApplication.POS, "session").statusCode());
                assertEquals("3", scalar(admin, "SELECT count(*) FROM security.sessions WHERE user_id='" + user + "' AND revoked_at IS NULL"));
                assertEquals(403, first.post(LoginApplication.FISCAL, "logout", null, "").statusCode());
                assertEquals(200, first.get(LoginApplication.FISCAL, "session").statusCode());
                var loggedOut = first.post(LoginApplication.FISCAL, "logout", first.csrf(LoginApplication.FISCAL), "");
                assertEquals(204, loggedOut.statusCode());
                secureCookie(loggedOut, LoginApplication.FISCAL, true);
                assertTrue(!first.cookies.containsKey(LoginApplication.FISCAL.sessionCookie()));
                try (var read = admin.prepareStatement("SELECT revoked_at IS NOT NULL FROM security.sessions WHERE token_hash=?")) {
                    read.setString(1, hash);
                    try (var rows = read.executeQuery()) {
                        assertTrue(rows.next() && rows.getBoolean(1));
                    }
                }
                var replay = HttpRequest.newBuilder(base.resolve(LoginApplication.FISCAL.path() + "/session"))
                        .timeout(Duration.ofSeconds(10)).header("Cookie", LoginApplication.FISCAL.sessionCookie() + "=" + original).GET().build();
                assertEquals(401, client.send(replay, HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(200, second.get(LoginApplication.FISCAL, "session").statusCode());
                assertEquals(200, pointOfSale.get(LoginApplication.POS, "session").statusCode());
                assertEquals("2", scalar(admin, "SELECT count(*) FROM security.sessions WHERE user_id='" + user + "' AND revoked_at IS NULL"));
                assertEquals("t", scalar(admin, "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid='security.sessions'::regclass"));
                assertEquals("t", scalar(admin, "SELECT NOT has_any_column_privilege('ls_core_session_manager_dev','tenant.branches','SELECT,INSERT,UPDATE')"));
                String log = Files.readString(root.resolve("server.log"));
                assertTrue(!log.contains(original) && !log.contains(new String(password)));
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static String credentials(String password) {
        return "{\"username\":\"f34-login\",\"password\":\"" + password + "\"}";
    }

    private static String digest(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
    }

    static SSLContext trust(Path path) throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        char[] password = System.getenv("LS_LOGIN_TEST_TLS_PASSWORD").toCharArray();
        try (var input = Files.newInputStream(path)) {
            store.load(input, password);
        } finally {
            Arrays.fill(password, '\0');
        }
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        var context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    private static void secureCookie(HttpResponse<String> response, LoginApplication application, boolean deletion) {
        String raw = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(application.sessionCookie() + "=")).findFirst().orElseThrow();
        HttpCookie cookie = HttpCookie.parse(raw).getFirst();
        assertTrue(cookie.getSecure() && cookie.isHttpOnly());
        assertEquals(application.path(), cookie.getPath());
        assertTrue(cookie.getDomain() == null);
        assertTrue(raw.toLowerCase(java.util.Locale.ROOT).contains("samesite=strict"));
        assertTrue(deletion ? cookie.getMaxAge() == 0 : cookie.getMaxAge() == 3600);
    }

    static String scalar(java.sql.Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    static final class Browser {
        private final HttpClient client;
        private final URI base;
        private final Map<String, HttpCookie> cookies = new HashMap<>();
        private HttpResponse<String> last;

        Browser(HttpClient client, URI base) {
            this.client = client;
            this.base = base;
        }

        String token(LoginApplication application) {
            return cookies.get(application.sessionCookie()).getValue();
        }

        HttpResponse<String> get(LoginApplication application, String action) throws Exception {
            return send(application.path() + "/" + action, null, null);
        }

        String csrf(LoginApplication application) throws Exception {
            var response = get(application, "csrf");
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
            HttpCookie cookie = cookies.get(application.csrfCookie());
            assertTrue(cookie.getSecure() && cookie.isHttpOnly() && "/".equals(cookie.getPath()) && cookie.getDomain() == null);
            var match = Pattern.compile("\"csrfToken\":\"([^\"]+)\"").matcher(response.body());
            assertTrue(match.find());
            return match.group(1);
        }

        HttpResponse<String> post(LoginApplication application, String action, String csrf, String body) throws Exception {
            return send(application.path() + "/" + action, csrf, body);
        }

        private HttpResponse<String> send(String path, String csrf, String body) throws Exception {
            var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10));
            String jar = cookies.values().stream().filter(cookie -> !cookie.hasExpired() && path.startsWith(cookie.getPath()))
                    .map(cookie -> cookie.getName() + "=" + cookie.getValue()).collect(java.util.stream.Collectors.joining("; "));
            if (!jar.isEmpty()) {
                request.header("Cookie", jar);
            }
            if (csrf != null) {
                request.header("X-CSRF-TOKEN", csrf);
            }
            if (body == null) {
                request.GET();
            } else {
                request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            }
            last = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String header : last.headers().allValues("Set-Cookie")) {
                var cookie = HttpCookie.parse(header).getFirst();
                if (cookie.getMaxAge() == 0) {
                    cookies.remove(cookie.getName());
                } else {
                    cookies.put(cookie.getName(), cookie);
                }
            }
            return last;
        }
    }
}

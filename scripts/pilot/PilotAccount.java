import com.lssoluciones.lscore.identity.application.ManualIdentityInput;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcManualIdentityRegistration;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordLifecycle;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import com.lssoluciones.lscore.platform.JdbcPlatformBootstrap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import org.postgresql.ds.PGSimpleDataSource;

/** Source-file tooling for the owned disposable pilot only; never an HTTP endpoint or production initializer. */
class PilotAccount {
    private static PGSimpleDataSource source(String role) {
        var result = new PGSimpleDataSource();
        result.setServerNames(new String[] {"127.0.0.1"});
        result.setPortNumbers(new int[] {Integer.parseInt(System.getenv("LS_PILOT_PORT"))});
        result.setDatabaseName("ls_core_dev");
        result.setUser(role);
        result.setPassword(System.getenv("LS_PILOT_" + role.toUpperCase(java.util.Locale.ROOT)));
        result.setConnectTimeout(3);
        result.setSocketTimeout(5);
        return result;
    }

    public static void main(String[] args) {
        char[] password = null;
        try {
            Path root = Path.of(System.getenv("LS_PILOT_ROOT")).toRealPath();
            int port = Integer.parseInt(System.getenv("LS_PILOT_PORT"));
            var pid = Files.readAllLines(root.resolve("data/postmaster.pid"));
            if (!root.getParent().equals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath())
                    || !root.getFileName().toString().startsWith("ls-core-migrations-test-")
                    || !Files.readString(root.resolve("data/PG_VERSION")).trim().equals("17")
                    || port == 5432 || port == 55432 || !pid.get(3).equals(Integer.toString(port))
                    || !Path.of(pid.get(1)).toRealPath().equals(root.resolve("data").toRealPath())) {
                throw new IllegalStateException();
            }
            String username = System.getenv("LS_PILOT_USERNAME");
            password = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).toCharArray();
            var registration = new JdbcManualIdentityRegistration(source(JdbcManualIdentityRegistration.DATABASE_ROLE));
            var user = registration.register(new ManualIdentityInput(username, "Cuenta de desarrollo local", null));
            var lifecycle = new JdbcPasswordLifecycle(source(JdbcPasswordLifecycle.ISSUER_ROLE),
                    source(JdbcPasswordLifecycle.CHANGER_ROLE), Duration.ofHours(72));
            try (var issued = lifecycle.issue(user)) {
                char[] temporary = issued.take();
                try {
                    lifecycle.exchange(username, temporary, password);
                } finally {
                    Arrays.fill(temporary, '\0');
                }
            }
            if (args.length == 2 && "--core".equals(args[1])) {
                String coreName = System.getenv("LS_PILOT_CORE_USERNAME");
                var coreUser = registration.register(new ManualIdentityInput(coreName, "Propietario del piloto desechable", null));
                try (var issued = lifecycle.issue(coreUser)) {
                    char[] temporary = issued.take();
                    try {
                        lifecycle.exchange(coreName, temporary, password);
                    } finally {
                        Arrays.fill(temporary, '\0');
                    }
                }
                registration.register(new ManualIdentityInput(System.getenv("LS_PILOT_INITIAL_USERNAME"), "Administrador inicial del piloto", null));
                var authentication = new JdbcPasswordAuthentication(source(JdbcPasswordAuthentication.ROLE), JdbcPasswordAuthentication.Limits.defaults());
                new JdbcPlatformBootstrap(source(JdbcPlatformBootstrap.ROLE), authentication).initialize(coreName, password);
            }
            // Administrative fixture only. All later functional traffic uses the V16 restricted LOGIN.
            try (var admin = source("postgres").getConnection(); var sql = admin.createStatement()) {
                sql.execute(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8).replace("__USER__", user.toString()));
                sql.execute("INSERT INTO tenant.administrators(tenant_id,membership_id,status,granted_at) "
                        + "VALUES ('35000000-0000-0000-0000-000000000010','35000000-0000-0000-0000-000000000110','ACTIVE',clock_timestamp())");
            }
            System.out.println("Disposable pilot account and V17 context fixture prepared.");
        } catch (Exception failure) {
            System.err.println("Disposable pilot account unavailable; diagnostics withheld.");
            System.exit(1);
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }
}

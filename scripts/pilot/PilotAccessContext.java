import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import com.lssoluciones.lscore.session.JdbcApplicationSessions;
import com.lssoluciones.lscore.session.JdbcSessionContexts;
import com.lssoluciones.lscore.session.LoginApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.postgresql.ds.PGSimpleDataSource;

/** Disposable F05 selector only. Reads the real Core cookie from stdin; never exposes it. */
class PilotAccessContext {
    private static PGSimpleDataSource source(String role) {
        var result = new PGSimpleDataSource();
        result.setServerNames(new String[] {"127.0.0.1"});
        result.setPortNumbers(new int[] {Integer.parseInt(System.getenv("LS_PILOT_PORT"))});
        result.setDatabaseName("ls_core_dev");
        result.setUser(role);
        result.setPassword(System.getenv("LS_PILOT_" + role.toUpperCase(java.util.Locale.ROOT)));
        result.setConnectTimeout(3);
        result.setSocketTimeout(10);
        return result;
    }

    public static void main(String[] args) {
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
            String token = new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII);
            var auth = new JdbcPasswordAuthentication(source(JdbcPasswordAuthentication.ROLE), JdbcPasswordAuthentication.Limits.defaults());
            var sessions = new JdbcApplicationSessions(source(JdbcApplicationSessions.ROLE), auth,
                    Map.of(LoginApplication.CORE, UUID.fromString(System.getenv("LS_PILOT_CORE_APP")),
                            LoginApplication.FISCAL, UUID.fromString("35000000-0000-0000-0000-000000000001"),
                            LoginApplication.POS, UUID.fromString("35000000-0000-0000-0000-000000000002")),
                    Duration.ofHours(8), Map.of(LoginApplication.CORE, Duration.ofMinutes(30),
                            LoginApplication.FISCAL, Duration.ofMinutes(30), LoginApplication.POS, Duration.ofMinutes(15)));
            // Core administrative sessions do not acquire the operational barriers themselves.
            // This owned tooling holds the existing F06/V23 protocol around the entire F05 selection.
            try (var barrier = source(JdbcApplicationSessions.ROLE).getConnection(); var sql = barrier.createStatement()) {
                barrier.setAutoCommit(false);
                sql.execute("SET LOCAL lock_timeout='5s'");
                sql.execute("SELECT pg_advisory_xact_lock_shared(370019,1)");
                sql.execute("SELECT pg_advisory_xact_lock_shared(410023,1)");
                var context = new JdbcSessionContexts(sessions).select(LoginApplication.CORE, token,
                        UUID.fromString(args[0]), UUID.fromString(args[1]));
                barrier.commit();
                System.out.println("CONTEXT_ID=" + context.id());
            }
        } catch (Exception failure) {
            System.err.println("Disposable context selection unavailable; diagnostics withheld.");
            System.exit(1);
        }
    }
}

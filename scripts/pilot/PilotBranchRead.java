import com.lssoluciones.lscore.access.infrastructure.jdbc.BranchReadCoordinator;
import com.lssoluciones.lscore.access.infrastructure.jdbc.JdbcTenantAuthorization;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Scope;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.postgresql.ds.PGSimpleDataSource;

/** Existing bounded F04 reader, standalone disposable tooling. Never creates business session admission. */
class PilotBranchRead {
    private static PGSimpleDataSource source(String role) {
        var result = new PGSimpleDataSource();
        result.setServerNames(new String[] {"127.0.0.1"});
        result.setPortNumbers(new int[] {Integer.parseInt(System.getenv("LS_PILOT_PORT"))});
        result.setDatabaseName("ls_core_dev"); result.setUser(role);
        result.setPassword(System.getenv("LS_PILOT_" + role.toUpperCase(java.util.Locale.ROOT)));
        result.setConnectTimeout(3); result.setSocketTimeout(5); return result;
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getenv("LS_PILOT_ROOT")).toRealPath();
        int port = Integer.parseInt(System.getenv("LS_PILOT_PORT"));
        var pid = Files.readAllLines(root.resolve("data/postmaster.pid"));
        if (!root.getParent().equals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath())
                || !root.getFileName().toString().startsWith("ls-core-migrations-test-")
                || !Files.readString(root.resolve("data/PG_VERSION")).trim().equals("17")
                || port == 5432 || port == 55432 || !pid.get(3).equals(Integer.toString(port))
                || !Path.of(pid.get(1)).toRealPath().equals(root.resolve("data").toRealPath())) { throw new IllegalStateException(); }
        char[] password = new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).toCharArray();
        try (var pool = new HikariDataSource()) {
            pool.setDataSource(source("ls_core_branch_operation_dev")); pool.setReadOnly(true); pool.setMaximumPoolSize(1);
            var authentication = new JdbcPasswordAuthentication(source(JdbcPasswordAuthentication.ROLE), JdbcPasswordAuthentication.Limits.defaults());
            var authorization = new JdbcTenantAuthorization(source(JdbcTenantAuthorization.ROLE), authentication);
            var reader = new BranchReadCoordinator(authorization, pool, source("ls_core_operation_emitter_dev"));
            try {
                reader.read(System.getenv("LS_PILOT_CORE_USERNAME"), password, new Scope(UUID.fromString(args[0]),UUID.fromString(args[1]),UUID.fromString(args[2])));
                System.out.println("BRANCH_READ=ALLOWED");
            } catch (com.lssoluciones.lscore.access.application.context.ContextRejectedException denied) { System.out.println("BRANCH_READ=DENIED"); }
        } finally { Arrays.fill(password, '\0'); }
    }
}

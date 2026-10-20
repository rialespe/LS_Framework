import com.lssoluciones.lscore.operations.infrastructure.jdbc.PostgresConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Tooling only: use the backend pool without starting Spring Boot or an HTTP server. */
class VerifyDockerJdbc {
    public static void main(String[] args) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("postgres");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("docker-only", Map.of(
                    "LS_DB_HOST", "127.0.0.1", "LS_DB_PORT", "55432", "LS_DB_NAME", "ls_core_dev",
                    "LS_DB_USERNAME", "ls_core_app_dev", "LS_DB_PASSWORD", System.getenv("LS_DB_PASSWORD"))));
            context.register(PostgresConfiguration.class);
            context.refresh();
            try (Connection connection = context.getBean(HikariDataSource.class).getConnection();
                    Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (ResultSet result = statement.executeQuery(
                        "SELECT 1,current_user,current_database(),current_setting('server_version_num')::int")) {
                    if (!connection.isReadOnly() || !result.next() || result.getInt(1) != 1
                            || !"ls_core_app_dev".equals(result.getString(2))
                            || !"ls_core_dev".equals(result.getString(3))
                            || result.getInt(4) < 170000 || result.getInt(4) >= 180000) {
                        throw new IllegalStateException();
                    }
                }
            }
            System.out.println("DOCKER_BACKEND_JDBC_SELECT_1_OK");
        } catch (Exception failure) {
            System.err.println("Docker backend JDBC verification failed; diagnostics withheld.");
            System.exit(1);
        }
    }
}

package com.lssoluciones.lscore.operations.infrastructure.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Local JDBC infrastructure, activated only by the explicit postgres profile. */
@Configuration(proxyBeanMethods = false)
@Profile("postgres")
public class PostgresConfiguration {

    @Bean(destroyMethod = "close")
    HikariDataSource postgresDataSource(Environment environment) {
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        // Set each field separately: credentials never become part of a JDBC URL.
        postgres.setServerNames(new String[] {required(environment, "LS_DB_HOST")});
        postgres.setPortNumbers(new int[] {port(environment)});
        postgres.setDatabaseName(required(environment, "LS_DB_NAME"));
        postgres.setUser(required(environment, "LS_DB_USERNAME"));
        postgres.setPassword(required(environment, "LS_DB_PASSWORD"));
        postgres.setConnectTimeout(3);
        postgres.setSocketTimeout(5);
        postgres.setApplicationName("ls-core-dev");

        HikariConfig pool = new HikariConfig();
        pool.setDataSource(postgres);
        pool.setPoolName("ls-core-postgres");
        pool.setMaximumPoolSize(3);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(5000);
        pool.setValidationTimeout(2000);
        pool.setIdleTimeout(60000);
        pool.setMaxLifetime(600000);
        // Unavailable PostgreSQL must yield health DOWN instead of aborting HTTP startup.
        pool.setInitializationFailTimeout(-1);
        pool.setReadOnly(true);
        return new HikariDataSource(pool);
    }

    @Bean
    HealthIndicator postgresHealthIndicator(HikariDataSource postgresDataSource) {
        return () -> {
            try (Connection connection = postgresDataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(3);
                try (ResultSet result = statement.executeQuery("SELECT 1")) {
                    return result.next() && result.getInt(1) == 1
                            ? Health.up().build() : Health.down().build();
                }
            } catch (Exception failure) {
                // Do not attach exceptions or log driver messages: they may contain private data.
                return Health.down().build();
            }
        };
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required PostgreSQL setting: " + name);
        }
        return value;
    }

    private static int port(Environment environment) {
        String value = required(environment, "LS_DB_PORT");
        try {
            int port = Integer.parseInt(value);
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // Report the setting name only, never its supplied value.
        }
        throw new IllegalStateException("Invalid PostgreSQL setting: LS_DB_PORT");
    }
}

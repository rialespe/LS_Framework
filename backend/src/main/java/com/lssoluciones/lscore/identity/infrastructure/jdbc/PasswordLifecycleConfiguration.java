package com.lssoluciones.lscore.identity.infrastructure.jdbc;

import java.time.Duration;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Opt-in internal service only: ls.identity.password-lifecycle.enabled=true. No HTTP mapping.
 * Independently supplied LS_PASSWORD_ISSUER_DB_* and LS_PASSWORD_CHANGER_DB_* settings, no runtime fallback.
 * Restrict issuer credentials and the issue() result to authorized administrative operators.
 * ls.identity.password-lifecycle.temporary-hours defaults to 72 (positive, at most 72).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ls.identity.password-lifecycle.enabled", havingValue = "true")
public class PasswordLifecycleConfiguration {
    @Bean
    JdbcPasswordLifecycle passwordLifecycle(Environment environment) {
        long hours = environment.getProperty("ls.identity.password-lifecycle.temporary-hours", Long.class, 72L);
        return new JdbcPasswordLifecycle(source(environment, "LS_PASSWORD_ISSUER_DB_", JdbcPasswordLifecycle.ISSUER_ROLE),
                source(environment, "LS_PASSWORD_CHANGER_DB_", JdbcPasswordLifecycle.CHANGER_ROLE),
                Duration.ofHours(hours));
    }

    static PGSimpleDataSource source(Environment environment, String prefix, String role) {
        var source = new PGSimpleDataSource();
        source.setServerNames(new String[] {required(environment, prefix + "HOST")});
        int port;
        try {
            port = Integer.parseInt(required(environment, prefix + "PORT"));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid password lifecycle port");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid password lifecycle port");
        }
        source.setPortNumbers(new int[] {port});
        source.setDatabaseName(required(environment, prefix + "NAME"));
        source.setUser(role);
        source.setPassword(required(environment, prefix + "PASSWORD"));
        source.setApplicationName("ls-core-password-lifecycle");
        source.setConnectTimeout(3);
        source.setSocketTimeout(10);
        return source;
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing password lifecycle setting: " + name);
        }
        return value;
    }
}

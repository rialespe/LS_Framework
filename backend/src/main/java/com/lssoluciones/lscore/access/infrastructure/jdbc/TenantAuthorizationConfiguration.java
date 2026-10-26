package com.lssoluciones.lscore.access.infrastructure.jdbc;

import com.lssoluciones.lscore.identity.application.PasswordAuthentication;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Explicit opt-in: ls.access.tenant-authorization.enabled=true and existing F04 credentials enabled.
 * Separate LS_AUTHORIZATION_DB_HOST/PORT/NAME/PASSWORD; never falls back to enterprise/authentication credentials.
 * Provision the dedicated LOGIN after V13 and apply V14 before activation. No HTTP, session or admission bean.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ls.access.tenant-authorization.enabled", havingValue = "true")
public class TenantAuthorizationConfiguration {
    @Bean
    JdbcTenantAuthorization tenantAuthorization(PasswordAuthentication authentication, Environment environment) {
        if (!(authentication instanceof JdbcPasswordAuthentication jdbcAuthentication)) {
            throw new IllegalStateException("Transactional F04 authentication required");
        }
        return new JdbcTenantAuthorization(database(environment, "LS_AUTHORIZATION_DB_", JdbcTenantAuthorization.ROLE), jdbcAuthentication);
    }

    static PGSimpleDataSource database(Environment environment, String prefix, String role) {
        var source = new PGSimpleDataSource();
        source.setServerNames(new String[] {required(environment, prefix + "HOST")});
        int port;
        try {
            port = Integer.parseInt(required(environment, prefix + "PORT"));
        } catch (NumberFormatException failure) {
            throw new IllegalStateException("Invalid authorization port");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("Invalid authorization port");
        }
        source.setPortNumbers(new int[] {port});
        source.setDatabaseName(required(environment, prefix + "NAME"));
        source.setUser(role);
        source.setPassword(required(environment, prefix + "PASSWORD"));
        source.setApplicationName("ls-core-authorization-reader");
        source.setConnectTimeout(3);
        source.setSocketTimeout(5);
        return source;
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing authorization setting: " + name);
        }
        return value;
    }
}

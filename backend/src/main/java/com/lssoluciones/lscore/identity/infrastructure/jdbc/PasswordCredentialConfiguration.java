package com.lssoluciones.lscore.identity.infrastructure.jdbc;

import com.lssoluciones.lscore.identity.application.PasswordCredentialVerifier;
import java.time.Clock;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Production verifier, opt-in through ls.identity.credentials.enabled=true.
 * Separate LS_IDENTITY_DB_* settings; no fallback to enterprise/catalog credentials.
 * Does not provision DATABASE_ROLE, ACL or RLS: unavailable/denied reads reject verification.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ls.identity.credentials.enabled", havingValue = "true")
public class PasswordCredentialConfiguration {
    @Bean
    PasswordCredentialVerifier passwordCredentialVerifier(Environment environment) {
        var source = new PGSimpleDataSource();
        source.setServerNames(new String[] {required(environment, "LS_IDENTITY_DB_HOST")});
        int port;
        try {
            port = Integer.parseInt(required(environment, "LS_IDENTITY_DB_PORT"));
        } catch (NumberFormatException failure) {
            throw new IllegalStateException("Invalid LS_IDENTITY_DB_PORT");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("Invalid LS_IDENTITY_DB_PORT");
        }
        source.setPortNumbers(new int[] {port});
        source.setDatabaseName(required(environment, "LS_IDENTITY_DB_NAME"));
        source.setUser(JdbcPasswordCredentialSource.DATABASE_ROLE);
        source.setPassword(required(environment, "LS_IDENTITY_DB_PASSWORD"));
        source.setApplicationName("ls-core-password-verifier");
        source.setConnectTimeout(3);
        source.setSocketTimeout(5);
        return new PasswordCredentialVerifier(new JdbcPasswordCredentialSource(source), Clock.systemUTC());
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing identity setting: " + name);
        }
        return value;
    }
}

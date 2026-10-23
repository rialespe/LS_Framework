package com.lssoluciones.lscore.identity.infrastructure.jdbc;

import com.lssoluciones.lscore.identity.application.PasswordAuthentication;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Mandatory F05 password boundary, opt-in through ls.identity.credentials.enabled=true.
 * Requires LS_AUTHENTICATION_DB_HOST/PORT/NAME/PASSWORD, never the retired LS_IDENTITY_DB_* reader.
 * No PasswordCredentialVerifier bean is published; V13 also revokes that old reader's database access.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ls.identity.credentials.enabled", havingValue = "true")
public class PasswordCredentialConfiguration {
    @Bean
    PasswordAuthentication passwordAuthentication(Environment environment) {
        return new JdbcPasswordAuthentication(PasswordLifecycleConfiguration.source(environment,
                "LS_AUTHENTICATION_DB_", JdbcPasswordAuthentication.ROLE), new JdbcPasswordAuthentication.Limits(
                environment.getProperty("ls.identity.authentication.failure-threshold", Integer.class, 5),
                Duration.ofSeconds(environment.getProperty("ls.identity.authentication.initial-lock-seconds", Long.class, 30L)),
                Duration.ofSeconds(environment.getProperty("ls.identity.authentication.maximum-lock-seconds", Long.class, 1800L))));
    }
}

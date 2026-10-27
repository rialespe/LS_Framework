package com.lssoluciones.lscore.platform;

import com.lssoluciones.lscore.identity.application.PasswordAuthentication;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Default OFF internal reader only; no trusted LS Core HTTP session/portal adapter exists yet. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ls.platform.authorization.enabled", havingValue = "true")
public class PlatformAuthorizationConfiguration {
    @Bean
    JdbcPlatformAuthorization platformAuthorization(PasswordAuthentication authentication, Environment environment) {
        if (!(authentication instanceof JdbcPasswordAuthentication jdbc)) {
            throw new IllegalStateException("Real transactional F04 is required");
        }
        return new JdbcPlatformAuthorization(PlatformDatabase.source(environment, "LS_PLATFORM_DB_", JdbcPlatformAuthorization.ROLE), jdbc);
    }
}

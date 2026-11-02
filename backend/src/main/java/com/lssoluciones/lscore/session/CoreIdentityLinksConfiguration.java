package com.lssoluciones.lscore.session;

import com.lssoluciones.lscore.identity.application.PasswordAuthentication;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import com.lssoluciones.lscore.platform.PlatformDatabase;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ls.core.identity-links.enabled", havingValue = "true")
public class CoreIdentityLinksConfiguration {
    @Bean
    JdbcIdentityLinks identityLinks(JdbcApplicationSessions sessions, PasswordAuthentication authentication,
            Environment environment) {
        if (!environment.getProperty("ls.login.core.enabled", Boolean.class, false)
                || !(authentication instanceof JdbcPasswordAuthentication jdbc)) {
            throw new IllegalStateException("Independent Core session and real F04 required");
        }
        return new JdbcIdentityLinks(sessions, jdbc,
                PlatformDatabase.source(environment, "LS_IDENTITY_LINKS_DB_", JdbcIdentityLinks.ROLE),
                PlatformDatabase.source(environment, "LS_IDENTITY_LINKS_LOG_DB_", JdbcIdentityLinks.LOG_ROLE));
    }
}

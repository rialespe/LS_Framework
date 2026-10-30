package com.lssoluciones.lscore.session;

import com.lssoluciones.lscore.identity.application.PasswordAuthentication;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import com.lssoluciones.lscore.platform.PlatformDatabase;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ls.core.tenant-users.enabled", havingValue = "true")
public class CoreTenantUsersConfiguration {
    @Bean
    JdbcTenantUsers tenantUsers(
            JdbcApplicationSessions sessions,
            PasswordAuthentication authentication,
            Environment environment) {
        if (!environment.getProperty("ls.login.core.enabled", Boolean.class, false)
                || !(authentication instanceof JdbcPasswordAuthentication jdbc)) {
            throw new IllegalStateException("Independent Core session and real F04 required");
        }
        for (String prefix : new String[] {"LS_PASSWORD_ISSUER_DB_", "LS_PASSWORD_CHANGER_DB_"}) {
            for (String key : new String[] {"HOST", "PORT", "NAME"}) {
                if (!environment.getRequiredProperty(prefix + key)
                        .equals(environment.getRequiredProperty("LS_AUTHENTICATION_DB_" + key))) {
                    throw new IllegalStateException("Core incorporation and F04 require the same database");
                }
            }
        }
        return new JdbcTenantUsers(
                sessions,
                jdbc,
                PlatformDatabase.source(environment, "LS_TENANT_USERS_DB_", JdbcTenantUsers.ROLE),
                PlatformDatabase.source(
                        environment, "LS_TENANT_USERS_LOG_DB_", JdbcTenantUsers.LOG_ROLE));
    }
}

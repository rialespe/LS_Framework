package com.lssoluciones.lscore.session;

import com.lssoluciones.lscore.identity.application.PasswordAuthentication;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import com.lssoluciones.lscore.platform.PlatformDatabase;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ls.core.tenant-rbac.enabled", havingValue = "true")
public class CoreTenantRbacConfiguration {
    @Bean
    JdbcTenantRbac tenantRbac(JdbcApplicationSessions sessions, PasswordAuthentication authentication,
            Environment environment) {
        if (!environment.getProperty("ls.login.core.enabled", Boolean.class, false)
                || !(authentication instanceof JdbcPasswordAuthentication jdbc)) {
            throw new IllegalStateException("Independent Core session and real F04 required");
        }
        return new JdbcTenantRbac(sessions, jdbc,
                PlatformDatabase.source(environment, "LS_TENANT_RBAC_DB_", JdbcTenantRbac.ROLE),
                PlatformDatabase.source(environment, "LS_TENANT_RBAC_LOG_DB_", JdbcTenantRbac.LOG_ROLE));
    }
}

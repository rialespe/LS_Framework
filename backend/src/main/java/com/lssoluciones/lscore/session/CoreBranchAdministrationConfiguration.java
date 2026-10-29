package com.lssoluciones.lscore.session;

import com.lssoluciones.lscore.identity.application.PasswordAuthentication;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import com.lssoluciones.lscore.platform.PlatformDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ls.core.branches.enabled", havingValue = "true")
public class CoreBranchAdministrationConfiguration {
    @Bean
    JdbcBranchAdministration branchAdministration(JdbcApplicationSessions sessions, PasswordAuthentication authentication, Environment environment) {
        if (!environment.getProperty("ls.login.core.enabled", Boolean.class, false) || !(authentication instanceof JdbcPasswordAuthentication jdbc)) {
            throw new IllegalStateException("Independent Core session and real F04 required");
        }
        return new JdbcBranchAdministration(sessions, jdbc,
                PlatformDatabase.source(environment, "LS_BRANCH_ADMIN_DB_", JdbcBranchAdministration.ROLE),
                PlatformDatabase.source(environment, "LS_BRANCH_ADMIN_LOG_DB_", JdbcBranchAdministration.LOG_ROLE));
    }
}

package com.lssoluciones.lscore.session;

import com.lssoluciones.lscore.platform.PlatformDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ls.core.branch-history.enabled", havingValue = "true")
public class CoreBranchHistoryConfiguration {
    @Bean
    JdbcBranchHistory branchHistory(JdbcApplicationSessions sessions, Environment environment) {
        if (!environment.getProperty("ls.login.core.enabled", Boolean.class, false)
                || !environment.getProperty("ls.identity.credentials.enabled", Boolean.class, false)) {
            throw new IllegalStateException("Independent Core session and F04 required");
        }
        return new JdbcBranchHistory(sessions, PlatformDatabase.source(environment, "LS_BRANCH_HISTORY_DB_", JdbcBranchHistory.ROLE));
    }
}

package com.lssoluciones.lscore.access.infrastructure.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Internal only, default disabled. Enable ls.access.branch-operation.enabled with existing F04/V14 beans.
 * Separate LS_BRANCH_OPERATION_DB_* and LS_OPERATION_EMITTER_DB_* credentials; no role/runtime fallback.
 * All four channels must target the same trusted host/port/database. Provision V15 roles before migration.
 * No HTTP endpoint, session, API Key or generalized business-execution bean is installed.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ls.access.branch-operation.enabled", havingValue = "true")
public class BranchOperationConfiguration {
    @Bean(destroyMethod = "close")
    HikariDataSource branchOperationPool(Environment environment) {
        for (String suffix : new String[] {"HOST", "PORT", "NAME"}) {
            String expected = environment.getRequiredProperty("LS_AUTHORIZATION_DB_" + suffix);
            for (String prefix : new String[] {"LS_AUTHENTICATION_DB_", "LS_BRANCH_OPERATION_DB_", "LS_OPERATION_EMITTER_DB_"}) {
                if (!expected.equals(environment.getRequiredProperty(prefix + suffix))) {
                    throw new IllegalStateException("Operation channels must use the same trusted database endpoint");
                }
            }
        }
        var pool = new HikariDataSource();
        pool.setDataSource(TenantAuthorizationConfiguration.database(environment, "LS_BRANCH_OPERATION_DB_", ProtectedBranchRead.READER));
        pool.setPoolName("ls-branch-operation");
        pool.setReadOnly(true);
        pool.setAutoCommit(true);
        pool.setMaximumPoolSize(3);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(3000);
        pool.setInitializationFailTimeout(-1);
        return pool;
    }

    @Bean
    BranchReadCoordinator branchReadCoordinator(JdbcTenantAuthorization authorization,
            @Qualifier("branchOperationPool") HikariDataSource pool, Environment environment) {
        return new BranchReadCoordinator(authorization, pool,
                TenantAuthorizationConfiguration.database(environment, "LS_OPERATION_EMITTER_DB_", ProtectedBranchRead.EMITTER));
    }
}

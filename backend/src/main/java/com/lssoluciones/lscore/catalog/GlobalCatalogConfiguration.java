package com.lssoluciones.lscore.catalog;

import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Opt-in infrastructure; never uses LS_DB_* or the enterprise runtime pool. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ls.catalog.enabled", havingValue = "true")
public class GlobalCatalogConfiguration {
    @Bean
    GlobalCatalogReader globalCatalogReader(Environment environment) {
        var source = new PGSimpleDataSource();
        source.setServerNames(new String[] {required(environment, "LS_CATALOG_DB_HOST")});
        int port;
        try {
            port = Integer.parseInt(required(environment, "LS_CATALOG_DB_PORT"));
        } catch (NumberFormatException failure) {
            throw new IllegalStateException("Invalid LS_CATALOG_DB_PORT");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("Invalid LS_CATALOG_DB_PORT");
        }
        source.setPortNumbers(new int[] {port});
        source.setDatabaseName(required(environment, "LS_CATALOG_DB_NAME"));
        source.setUser("ls_core_catalog_service_dev");
        source.setPassword(required(environment, "LS_CATALOG_DB_PASSWORD"));
        source.setApplicationName("ls-core-global-catalog");
        source.setConnectTimeout(3);
        source.setSocketTimeout(10);
        return new GlobalCatalogReader(source);
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing catalog setting: " + name);
        }
        return value;
    }
}

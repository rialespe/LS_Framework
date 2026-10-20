package com.lssoluciones.lscore.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GlobalCatalogConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(GlobalCatalogConfiguration.class);

    @Test
    void disabledByDefaultAndIndependentOfRuntime() {
        context.withPropertyValues("LS_DB_USERNAME=runtime", "LS_DB_PASSWORD=unused")
                .run(application -> assertThat(application).doesNotHaveBean(GlobalCatalogReader.class));
    }

    @Test
    void explicitActivationRequiresSeparateCredentials() {
        context.withPropertyValues("ls.catalog.enabled=true")
                .run(application -> assertThat(application).hasFailed());
        context.withPropertyValues("ls.catalog.enabled=true", "LS_CATALOG_DB_HOST=127.0.0.1",
                "LS_CATALOG_DB_PORT=12345", "LS_CATALOG_DB_NAME=fixture", "LS_CATALOG_DB_PASSWORD=unused")
                .run(application -> assertThat(application).hasSingleBean(GlobalCatalogReader.class));
    }
}

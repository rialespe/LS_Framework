package com.lssoluciones.lscore.identity.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.lssoluciones.lscore.identity.application.PasswordCredentialVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PasswordCredentialConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PasswordCredentialConfiguration.class);

    @Test
    void requiresExplicitActivationAndNeverReusesEnterpriseCredentials() {
        context.withPropertyValues("LS_DB_USERNAME=runtime", "LS_DB_PASSWORD=unused")
                .run(application -> assertThat(application).doesNotHaveBean(PasswordCredentialVerifier.class));
        context.withPropertyValues("ls.identity.credentials.enabled=true", "LS_DB_HOST=127.0.0.1",
                "LS_DB_PORT=5432", "LS_DB_NAME=unused", "LS_DB_PASSWORD=unused")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void createsRealVerifierWithoutOpeningDatabaseConnectionsDuringStartup() {
        context.withPropertyValues("ls.identity.credentials.enabled=true", "LS_IDENTITY_DB_HOST=127.0.0.1",
                "LS_IDENTITY_DB_PORT=1", "LS_IDENTITY_DB_NAME=unused", "LS_IDENTITY_DB_PASSWORD=unused")
                .run(application -> assertThat(application).hasSingleBean(PasswordCredentialVerifier.class));
        context.withPropertyValues("ls.identity.credentials.enabled=true", "LS_IDENTITY_DB_HOST=127.0.0.1",
                "LS_IDENTITY_DB_PORT=invalid", "LS_IDENTITY_DB_NAME=unused", "LS_IDENTITY_DB_PASSWORD=unused")
                .run(application -> assertThat(application).hasFailed());
    }
}

package com.lssoluciones.lscore.identity.infrastructure.jdbc;

import com.lssoluciones.lscore.identity.application.TrustedRecoveryMail;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** No installed mail provider or default activation. A trusted provider must be configured separately. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ls.identity.recovery.enabled", havingValue = "true")
public class PasswordRecoveryConfiguration {
    @Bean
    JdbcPasswordRecovery passwordRecovery(Environment environment, ObjectProvider<TrustedRecoveryMail> providers) {
        return new JdbcPasswordRecovery(PasswordLifecycleConfiguration.source(environment,
                "LS_RECOVERY_DB_", JdbcPasswordRecovery.ROLE), providers.getIfAvailable(), new JdbcPasswordRecovery.Limits(
                Duration.ofSeconds(environment.getProperty("ls.identity.recovery.token-seconds", Long.class, 900L)),
                Duration.ofSeconds(environment.getProperty("ls.identity.recovery.cooldown-seconds", Long.class, 60L)),
                environment.getProperty("ls.identity.recovery.account-hour-limit", Integer.class, 3),
                environment.getProperty("ls.identity.recovery.global-hour-limit", Integer.class, 100)));
    }
}

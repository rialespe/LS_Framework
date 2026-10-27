package com.lssoluciones.lscore.session;

import com.lssoluciones.lscore.identity.application.PasswordAuthentication;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Default OFF: ls.login.enabled=true and existing F04 credentials enabled are both required.
 * Server-owned ls.login.fiscal.application-id / ls.login.pos.application-id must be distinct existing PUBLISHED apps.
 * LS_LOGIN_DB_HOST/PORT/NAME/PASSWORD configure only the dedicated V16 session LOGIN, never enterprise credentials.
 * ls.login.session-seconds defaults to 28800, bounded to 60..86400. HTTPS is mandatory at the servlet boundary.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ls.login.enabled", havingValue = "true")
public class ApplicationLoginConfiguration {
    @Bean
    JdbcApplicationSessions applicationSessions(PasswordAuthentication authentication, Environment environment) {
        var source = new PGSimpleDataSource();
        for (String suffix : new String[] {"HOST", "PORT", "NAME"}) {
            if (!required(environment, "LS_LOGIN_DB_" + suffix).equals(required(environment, "LS_AUTHENTICATION_DB_" + suffix))) {
                throw new IllegalStateException("Session and F04 channels must target the same trusted database");
            }
        }
        source.setServerNames(new String[] {required(environment, "LS_LOGIN_DB_HOST")});
        int port = Integer.parseInt(required(environment, "LS_LOGIN_DB_PORT"));
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("Invalid login database port");
        }
        source.setPortNumbers(new int[] {port});
        source.setDatabaseName(required(environment, "LS_LOGIN_DB_NAME"));
        source.setUser(JdbcApplicationSessions.ROLE);
        source.setPassword(required(environment, "LS_LOGIN_DB_PASSWORD"));
        source.setApplicationName("ls-core-application-sessions");
        source.setConnectTimeout(3);
        source.setSocketTimeout(5);
        return new JdbcApplicationSessions(source, authentication, Map.of(
                LoginApplication.FISCAL, UUID.fromString(required(environment, "ls.login.fiscal.application-id")),
                LoginApplication.POS, UUID.fromString(required(environment, "ls.login.pos.application-id"))),
                Duration.ofSeconds(environment.getProperty("ls.login.session-seconds", Long.class, 28800L)));
    }

    @Bean
    FilterRegistrationBean<ApplicationLoginFilter> applicationLoginCsrf() {
        var registration = new FilterRegistrationBean<>(new ApplicationLoginFilter());
        registration.addUrlPatterns("/api/auth/*");
        registration.setOrder(0);
        return registration;
    }

    private static String required(Environment environment, String key) {
        return environment.getRequiredProperty(key);
    }
}

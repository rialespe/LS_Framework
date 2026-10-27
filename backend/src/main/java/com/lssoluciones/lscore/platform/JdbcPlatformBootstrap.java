package com.lssoluciones.lscore.platform;

import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/** Exceptional installation only. Never a bean, HTTP endpoint, delegation writer or credential verifier. */
public final class JdbcPlatformBootstrap {
    public static final String ROLE = "ls_core_platform_bootstrap_dev";
    private final DataSource source;
    private final JdbcPasswordAuthentication authentication;

    public JdbcPlatformBootstrap(DataSource source, JdbcPasswordAuthentication authentication) {
        this.source = Objects.requireNonNull(source);
        this.authentication = Objects.requireNonNull(authentication);
    }

    public void initialize(String username, char[] password) {
        try (var connection = source.getConnection()) {
            PlatformDatabase.begin(connection, ROLE, false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("SELECT pg_advisory_xact_lock(370019,1)");
                    try (var rows = statement.executeQuery("SELECT EXISTS(SELECT FROM platform.administrators)")) {
                        if (!rows.next() || rows.getBoolean(1)) {
                            throw new Rejected();
                        }
                    }
                }
                var verified = authentication.verify(username, password); // Existing F04 transaction and lockout policy.
                try (var statement = connection.prepareStatement("INSERT INTO platform.administrators(user_id,administrator_kind,status) "
                        + "SELECT u.id,'OWNER','ACTIVE' FROM identity.users u JOIN identity.user_credentials c ON c.user_id=u.id "
                        + "WHERE u.id=? AND u.status='ACTIVE' AND NOT c.must_change_password AND c.password_expires_at>clock_timestamp() "
                        + "AND (c.locked_until IS NULL OR c.locked_until<=clock_timestamp())")) {
                    statement.setObject(1, verified);
                    statement.setQueryTimeout(5);
                    if (statement.executeUpdate() != 1) {
                        throw new Rejected();
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException | RuntimeException failure) {
            throw new Rejected();
        }
    }

    public static final class Rejected extends RuntimeException {
        private Rejected() {
            super("Platform bootstrap unavailable");
        }
    }
}

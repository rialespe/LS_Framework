package com.lssoluciones.lscore.platform;

import java.sql.Connection;
import java.sql.SQLException;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.env.Environment;

/** Fixed installation, reader and controlled executor principals; never selected by HTTP or by a claimed user UUID. */
public final class PlatformDatabase {
    private PlatformDatabase() { }

    public static PGSimpleDataSource source(Environment environment, String prefix, String role) {
        var source = new PGSimpleDataSource();
        for (String key : new String[] {"HOST", "PORT", "NAME"}) {
            if (!environment.getRequiredProperty(prefix + key).equals(environment.getRequiredProperty("LS_AUTHENTICATION_DB_" + key))) {
                throw new IllegalArgumentException("Platform and F04 require the same trusted database");
            }
        }
        source.setServerNames(new String[] {environment.getRequiredProperty(prefix + "HOST")});
        int port = Integer.parseInt(environment.getRequiredProperty(prefix + "PORT"));
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid platform port");
        }
        source.setPortNumbers(new int[] {port});
        source.setDatabaseName(environment.getRequiredProperty(prefix + "NAME"));
        source.setUser(role);
        source.setPassword(environment.getRequiredProperty(prefix + "PASSWORD"));
        source.setConnectTimeout(3);
        source.setSocketTimeout(10);
        source.setApplicationName("ls-core-platform-internal");
        return source;
    }

    public static void begin(Connection connection, String role, boolean readOnly) throws SQLException {
        connection.setReadOnly(readOnly);
        connection.setTransactionIsolation(readOnly ? Connection.TRANSACTION_REPEATABLE_READ : Connection.TRANSACTION_READ_COMMITTED);
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement("SELECT session_user,current_user,rolcanlogin "
                + "AND rolconnlimit=? AND NOT (rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication) "
                + "AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid) "
                + "AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o') "
                + "FROM pg_roles r WHERE rolname=session_user AND current_database()='ls_core_dev' "
                + "AND current_setting('server_version_num')::integer BETWEEN 170000 AND 179999")) {
            statement.setInt(1, JdbcPlatformBootstrap.ROLE.equals(role) ? 1
                    : PlatformMutationCoordination.ROLE.equals(role) || JdbcPlatformExecutor.SECURITY_LOG_ROLE.equals(role)
                    || "ls_core_tenant_lifecycle_log_dev".equals(role) || "ls_core_tenant_registration_log_dev".equals(role) ? 2 : 3);
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !role.equals(rows.getString(1)) || !role.equals(rows.getString(2)) || !rows.getBoolean(3)) {
                    throw new SQLException();
                }
            }
        }
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout='5s'");
            statement.execute("SET LOCAL statement_timeout='5s'");
        }
    }
}

package com.lssoluciones.lscore.catalog;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.postgresql.ds.PGSimpleDataSource;

/** Fixed public-catalog queries. Each call owns and closes an independent physical connection. */
public final class GlobalCatalogReader {
    private final PGSimpleDataSource source;

    public GlobalCatalogReader(PGSimpleDataSource source) {
        this.source = Objects.requireNonNull(source);
    }

    public record Application(UUID id, String code, String name, String description, String status) { }

    public record Module(UUID id, UUID applicationId, String code, String name,
            String description, String status) { }

    public List<Application> applications() {
        return read(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT id, code, name, description, status FROM catalog.applications ORDER BY code, id")) {
                statement.setQueryTimeout(5);
                try (var rows = statement.executeQuery()) {
                    List<Application> result = new ArrayList<>();
                    while (rows.next()) {
                        result.add(new Application(rows.getObject(1, UUID.class), rows.getString(2),
                                rows.getString(3), rows.getString(4), rows.getString(5)));
                    }
                    return List.copyOf(result);
                }
            }
        });
    }

    public List<Module> modules(UUID applicationId) {
        Objects.requireNonNull(applicationId, "applicationId");
        return read(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT id, application_id, code, name, description, status FROM catalog.modules "
                    + "WHERE application_id = ? ORDER BY code, id")) {
                statement.setQueryTimeout(5);
                statement.setObject(1, applicationId);
                try (var rows = statement.executeQuery()) {
                    List<Module> result = new ArrayList<>();
                    while (rows.next()) {
                        result.add(new Module(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class),
                                rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6)));
                    }
                    return List.copyOf(result);
                }
            }
        });
    }

    private <T> T read(Query<T> query) {
        try (Connection connection = source.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(5);
                    try (var identity = statement.executeQuery("SELECT session_user, current_user")) {
                        if (!identity.next() || !"ls_core_catalog_service_dev".equals(identity.getString(1))
                                || !"ls_core_catalog_service_dev".equals(identity.getString(2))) {
                            throw new SQLException("Unexpected catalog identity");
                        }
                    }
                    statement.execute("SET LOCAL ROLE ls_core_catalog_reader_dev");
                }
                T result = query.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException | RuntimeException failure) {
            // Do not expose driver diagnostics, credentials, SQL or server details to callers.
            throw new CatalogUnavailableException();
        }
    }

    @FunctionalInterface
    private interface Query<T> {
        T run(Connection connection) throws SQLException;
    }

    public static final class CatalogUnavailableException extends RuntimeException {
        private CatalogUnavailableException() {
            super("Global catalog unavailable");
        }
    }
}

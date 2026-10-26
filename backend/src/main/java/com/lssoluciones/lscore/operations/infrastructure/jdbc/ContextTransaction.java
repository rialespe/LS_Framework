package com.lssoluciones.lscore.operations.infrastructure.jdbc;

import com.lssoluciones.lscore.access.application.context.ContextRejectedException;
import com.lssoluciones.lscore.access.application.context.ContextResolver.AuthorizedContext;
import com.lssoluciones.lscore.access.application.context.OperationAdmission.AuthorizedAdmission;
import java.util.UUID;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Explicit read-only transactions; not registered as a bean until a trusted source exists. */
public final class ContextTransaction {
    private static final List<String> KEYS = List.of("ls.tenant_id", "ls.application_id", "ls.branch_id",
            "ls.origin", "ls.actor_id", "ls.credential_id", "ls.session_context_id",
            "ls.membership_id", "ls.branch_membership_id");
    private final HikariDataSource pool;
    private final ThreadLocal<Boolean> running = new ThreadLocal<>();

    public ContextTransaction(HikariDataSource pool) {
        this.pool = Objects.requireNonNull(pool);
    }

    @FunctionalInterface
    public interface Work {
        void run(Queries queries) throws Exception;
    }

    /** No live JDBC resource or deferred result can be returned. */
    public interface Queries {
        List<List<String>> query(String sql, List<String> parameters);
    }

    @FunctionalInterface
    public interface BranchAdmission {
        AuthorizedAdmission admit(Connection ownedConnection) throws Exception;
    }

    public record BranchView(UUID id, UUID tenantId, String code, String name) { }

    /** One fixed, bounded SELECT only; no caller-supplied SQL, cursor, JDBC resource or deferred result. */
    public BranchView readBranch(BranchAdmission issuer) {
        if (issuer == null) {
            throw new ContextRejectedException();
        }
        AuthorizedAdmission[] bound = new AuthorizedAdmission[1];
        List<BranchView> result = new ArrayList<>();
        execute(null, queries -> {
            var scope = bound[0].context().claims().scope();
            var rows = queries.query("SELECT id,tenant_id,code,name FROM tenant.branches WHERE id=?::uuid AND tenant_id=?::uuid",
                    List.of(scope.branchId().toString(), scope.tenantId().toString()));
            if (rows.size() != 1 || !scope.branchId().toString().equals(rows.get(0).get(0))
                    || !scope.tenantId().toString().equals(rows.get(0).get(1))) {
                throw new ContextRejectedException();
            }
            var row = rows.get(0);
            result.add(new BranchView(UUID.fromString(row.get(0)), UUID.fromString(row.get(1)), row.get(2), row.get(3)));
        }, connection -> {
            bound[0] = issuer.admit(connection);
            return bound[0];
        });
        return result.getFirst();
    }

    public void execute(AuthorizedContext context, Work work) {
        execute(context, work, null);
    }

    private void execute(AuthorizedContext context, Work work, BranchAdmission issuer) {
        if (context == null && issuer == null || work == null || running.get() != null) {
            throw new ContextRejectedException();
        }
        running.set(true);
        Connection connection = null;
        ScopedQueries queries = null;
        boolean safe = false;
        AuthorizedAdmission admission = null;
        try {
            if (context != null) {
                context.begin();
            }
            connection = pool.getConnection();
            if (!connection.getAutoCommit() || !connection.isReadOnly()) {
                throw new ContextRejectedException();
            }
            checkSettings(connection, null);
            if (issuer != null) {
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            }
            connection.setAutoCommit(false);
            if (issuer != null) {
                admission = issuer.admit(connection);
                admission.verify(connection);
                context = admission.context();
                context.begin();
            }
            // pgJDBC sends BEGIN before this first transactional statement.
            context.revalidate();
            List<String> values = values(context);
            for (int index = 0; index < KEYS.size(); index++) {
                try (PreparedStatement statement = connection.prepareStatement("SELECT set_config(?, ?, true)")) {
                    statement.setString(1, KEYS.get(index));
                    statement.setString(2, values.get(index));
                    statement.executeQuery().close();
                }
            }
            queries = new ScopedQueries(connection, values);
            work.run(queries);
            queries.check();
            if (admission != null) {
                admission.verify(connection);
            }
            context.revalidate();
            queries.open = false;
            connection.commit();
            connection.setAutoCommit(true);
            checkSettings(connection, null);
            safe = true;
        } catch (Exception failure) {
            throw new ContextRejectedException();
        } finally {
            if (queries != null) {
                queries.open = false;
            }
            try {
                if (connection != null) {
                    if (!safe) {
                        // Any uncertain state (including rollback failure) is evicted, never recycled.
                        // Hikari eviction closes the physical connection and cancels its leak task.
                        // Do not close that proxy again: Hikari 7 may reset a detached PoolEntry.
                        try {
                            if (!connection.getAutoCommit()) {
                                connection.rollback();
                            }
                        } catch (SQLException ignored) {
                            // Eviction below also covers a broken physical connection.
                        } finally {
                            pool.evictConnection(connection);
                        }
                    } else {
                        connection.close();
                    }
                }
            } catch (Exception failure) {
                if (connection != null) {
                    pool.evictConnection(connection);
                }
                throw new ContextRejectedException();
            } finally {
                try {
                    if (admission != null) {
                        admission.close();
                    }
                } finally {
                    running.remove();
                }
            }
        }
    }

    private static List<String> values(AuthorizedContext context) {
        var claims = context.claims();
        return List.of(text(claims.scope().tenantId()), text(claims.scope().applicationId()),
                text(claims.scope().branchId()), claims.origin().name(), text(claims.actorId()),
                text(claims.credentialId()), text(claims.sessionContextId()), text(claims.membershipId()),
                text(claims.branchMembershipId()));
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static void checkSettings(Connection connection, List<String> expected) throws SQLException {
        for (int index = 0; index < KEYS.size(); index++) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT current_setting(?, true)")) {
                statement.setString(1, KEYS.get(index));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new ContextRejectedException();
                    }
                    String actual = result.getString(1);
                    String target = expected == null ? "" : expected.get(index);
                    if (!target.equals(actual == null ? "" : actual)) {
                        throw new ContextRejectedException();
                    }
                }
            }
        }
    }

    private static final class ScopedQueries implements Queries {
        private final Connection connection;
        private final List<String> values;
        private final Thread owner = Thread.currentThread();
        private volatile boolean open = true;
        private volatile boolean failed;
        private final String transactionId;

        private ScopedQueries(Connection connection, List<String> values) throws SQLException {
            this.connection = connection;
            this.values = values;
            this.transactionId = transactionId(connection);
        }

        private void check() throws SQLException {
            if (!open || failed || Thread.currentThread() != owner) {
                throw new ContextRejectedException();
            }
            if (connection.getAutoCommit() || !connection.isReadOnly()
                    || !transactionId.equals(transactionId(connection))) {
                throw new ContextRejectedException();
            }
            checkSettings(connection, values);
        }

        private static String transactionId(Connection connection) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_current_xact_id()::text");
                    ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ContextRejectedException();
                }
                return result.getString(1);
            }
        }

        @Override
        public List<List<String>> query(String sql, List<String> parameters) {
            try {
                check();
                // SQL belongs to trusted repository code, never HTTP input. This is no SQL sandbox.
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setQueryTimeout(5);
                    for (int index = 0; index < parameters.size(); index++) {
                        statement.setString(index + 1, parameters.get(index));
                    }
                    List<List<String>> rows = new ArrayList<>();
                    try (ResultSet result = statement.executeQuery()) {
                        int columns = result.getMetaData().getColumnCount();
                        while (result.next()) {
                            List<String> row = new ArrayList<>();
                            for (int column = 1; column <= columns; column++) {
                                row.add(result.getString(column));
                            }
                            rows.add(Collections.unmodifiableList(row));
                        }
                    }
                    check();
                    return List.copyOf(rows);
                }
            } catch (Exception failure) {
                failed = true;
                throw new ContextRejectedException();
            }
        }
    }
}

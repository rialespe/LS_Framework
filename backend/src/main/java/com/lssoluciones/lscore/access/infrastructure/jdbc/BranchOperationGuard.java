package com.lssoluciones.lscore.access.infrastructure.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Internal future-consumer contract, intentionally not a bean or OperationAdmission verifier.
 * Only an approved server composition may supply its authenticated actor and context.
 * No SQL callback, business table rights, HTTP endpoint or session/API-key admission is enabled here.
 */
public final class BranchOperationGuard {
    public static final String ROLE = "ls_core_branch_guard_dev";
    private BranchOperationGuard() { }
    public static void probe(Connection connection, UUID request, UUID actor, UUID tenant, UUID branch,
            UUID application, UUID module, UUID context, Integer requiredVersion) throws SQLException {
        if (connection.getAutoCommit() || connection.isReadOnly() || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new SQLException("Owned transaction required", "42501");
        }
        try (var statement = connection.prepareStatement("INSERT INTO security.branch_write_probes "
                + "(request_id,actor_user_id,tenant_id,branch_id,application_id,module_id,context_id,required_version) VALUES(?,?,?,?,?,?,?,?)")) {
            statement.setObject(1, request);
            statement.setObject(2, actor);
            statement.setObject(3, tenant);
            statement.setObject(4, branch);
            statement.setObject(5, application);
            statement.setObject(6, module);
            statement.setObject(7, context);
            statement.setObject(8, requiredVersion);
            statement.setQueryTimeout(5);
            if (statement.executeUpdate() != 1) { throw new SQLException("Admission unavailable", "42501"); }
        }
        // Caller retains the owned connection and barriers until its COMMIT/ROLLBACK. No business DML here.
    }
}

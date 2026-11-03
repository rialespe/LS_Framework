package com.lssoluciones.lscore.session;

import com.lssoluciones.lscore.platform.PlatformDatabase;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Internal trusted coordinator utility. Keep the borrowed connection open through business COMMIT.
 * No HTTP endpoint accepts this binding as authorization. No cached permission set is a proof.
 */
public final class JdbcContextPermissions {
    public static final String ROLE = "ls_core_context_permissions_dev";

    private JdbcContextPermissions() {}

    public record Binding(
            UUID user,
            UUID tenant,
            UUID application,
            UUID branch,
            UUID membership,
            UUID branchMembership,
            UUID context,
            UUID session) {}

    public enum Operation {
        READ,
        WRITE
    }

    public static void begin(Connection connection) throws SQLException {
        PlatformDatabase.begin(connection, ROLE, false);
    }

    public static void require(
            Connection connection, Binding binding, String permission, Operation operation)
            throws SQLException {
        if (connection.getAutoCommit()
                || binding == null
                || permission == null
                || operation == null) {
            throw new SQLException("Context unavailable", "42501");
        }
        try (var query =
                connection.prepareStatement(
                        "SELECT security.require_context_permission(?,?,?,?,?,?,?,?,?,?)")) {
            UUID[] values = {
                binding.user(),
                binding.tenant(),
                binding.application(),
                binding.branch(),
                binding.membership(),
                binding.branchMembership(),
                binding.context(),
                binding.session()
            };
            for (int i = 0; i < values.length; i++) {
                query.setObject(i + 1, values[i]);
            }
            query.setString(9, permission);
            query.setString(10, operation.name());
            query.setQueryTimeout(10);
            query.execute();
        }
    }
}

package com.lssoluciones.lscore.platform;

import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Transaction-local coordination for the controlled executor. No bean, HTTP adapter or reusable proof. */
final class PlatformMutationCoordination {
    static final String ROLE = "ls_core_platform_mutator_dev";

    private PlatformMutationCoordination() { }

    enum Decision { READY, DENIED }
    record LockedDecision(UUID verifiedOwner, UUID target, JdbcPlatformAuthorization.Action action,
            JdbcPlatformAuthorization.Condition current, int activeDelegates, Decision decision) { }

    /** Caller owns a fresh transaction and rollback/commit. Result is valid only inside that transaction. */
    static LockedDecision inspect(Connection connection, JdbcPasswordAuthentication authentication,
            String username, char[] password, JdbcPlatformAuthorization.Proposal proposal) throws SQLException {
        if (connection.getAutoCommit() || connection.isReadOnly()
                || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new SQLException("Expected owned READ COMMITTED transaction", "25000");
        }
        UUID verified = authentication.verify(username, password);
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT session_user=current_user AND current_user='ls_core_platform_mutator_dev' "
                + "AND NOT (rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication) "
                + "AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid) "
                + "FROM pg_roles r WHERE rolname=session_user AND current_database()='ls_core_dev' "
                + "AND current_setting('server_version_num')::integer BETWEEN 170000 AND 179999")) {
            if (!rows.next() || !rows.getBoolean(1)) {
                throw new SQLException("Platform coordination unavailable", "42501");
            }
        }
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout='5s'");
            statement.execute("SET LOCAL statement_timeout='5s'");
            statement.execute("SELECT pg_advisory_xact_lock(370019,1)");
            statement.execute("SELECT id FROM platform.administrators ORDER BY id FOR UPDATE");
        }
        UUID owner = null;
        int count = 0;
        var current = JdbcPlatformAuthorization.Condition.NONE;
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT a.user_id,a.administrator_kind FROM platform.administrators a JOIN identity.users u ON u.id=a.user_id "
                + "WHERE a.status='ACTIVE' AND u.status='ACTIVE'")) {
            while (rows.next()) {
                var user = rows.getObject(1, UUID.class);
                var kind = JdbcPlatformAuthorization.Condition.valueOf(rows.getString(2));
                if (kind == JdbcPlatformAuthorization.Condition.OWNER) {
                    owner = user;
                } else {
                    count++;
                }
                if (proposal != null && user.equals(proposal.targetUserId())) {
                    current = kind;
                }
            }
        }
        boolean activeTarget = false;
        if (proposal != null && proposal.targetUserId() != null) {
            try (var statement = connection.prepareStatement("SELECT id FROM identity.users WHERE id=? AND status='ACTIVE'")) {
                statement.setObject(1, proposal.targetUserId());
                try (var rows = statement.executeQuery()) {
                    activeTarget = rows.next();
                }
            }
        }
        boolean ready = verified.equals(owner) && proposal != null && activeTarget && !verified.equals(proposal.targetUserId())
                && (proposal.action() == JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN && current == JdbcPlatformAuthorization.Condition.NONE
                || proposal.action() == JdbcPlatformAuthorization.Action.REVOKE_SUPERADMIN && current == JdbcPlatformAuthorization.Condition.SUPERADMIN && count > 1);
        return new LockedDecision(verified, proposal == null ? null : proposal.targetUserId(), proposal == null ? null : proposal.action(),
                current, count, ready ? Decision.READY : Decision.DENIED);
    }
}

package com.lssoluciones.lscore.access.infrastructure.jdbc;

import com.lssoluciones.lscore.access.application.context.ContextRejectedException;
import com.lssoluciones.lscore.access.application.context.ContextResolver.Claims;
import com.lssoluciones.lscore.access.application.context.OperationIdentity;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Origin;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

/** Opaque server-owned evidence. Native PostgreSQL binding, not a GUC or a transferable bearer credential. */
public final class ProtectedBranchRead implements AutoCloseable {
    public static final String READER = "ls_core_branch_operation_dev";
    public static final String EMITTER = "ls_core_operation_emitter_dev";
    private static final String NATIVE = "SELECT d.oid,r.oid,pg_backend_pid(),a.backend_start,"
            + "pg_current_xact_id()::text FROM pg_database d,pg_roles r,pg_stat_activity a "
            + "WHERE d.datname=current_database() AND r.rolname=session_user AND a.pid=pg_backend_pid() "
            + "AND current_database()='ls_core_dev' AND session_user=current_user "
            + "AND current_user='ls_core_branch_operation_dev' AND r.rolcanlogin AND r.rolconnlimit=3 "
            + "AND NOT (r.rolsuper OR r.rolbypassrls OR r.rolinherit OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication) "
            + "AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid) "
            + "AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o')";
    private static final String KEY = "database_oid=?::oid AND login_oid=?::oid AND backend_pid=? "
            + "AND backend_start=? AND transaction_id=?::xid8";
    private final Connection connection;
    private final DataSource emitter;
    private final OperationIdentity operation;
    private final Claims claims;
    private final Binding binding;
    private final Thread owner = Thread.currentThread();
    private boolean open = true;

    private record Binding(long database, long login, int pid, java.sql.Timestamp start, String xid) {
        void parameters(java.sql.PreparedStatement statement) throws SQLException {
            statement.setLong(1, database);
            statement.setLong(2, login);
            statement.setInt(3, pid);
            statement.setTimestamp(4, start);
            statement.setString(5, xid);
        }
    }

    private ProtectedBranchRead(Connection connection, DataSource emitter, OperationIdentity operation,
            JdbcTenantAuthorization.Decision decision, Binding binding) {
        this.connection = connection;
        this.emitter = emitter;
        this.operation = operation;
        this.binding = binding;
        this.claims = new Claims(decision.authorizedScope().orElseThrow(), Origin.PASSWORD_OPERATION,
                decision.subject(), null, null, decision.membership(), decision.branchMembership());
    }

    /** Package-only issuance after real F04/V14; callers cannot supply physical identifiers or a subject UUID. */
    static ProtectedBranchRead issue(Connection connection, DataSource emitter, OperationIdentity operation,
            JdbcTenantAuthorization.Decision decision) throws SQLException {
        if (decision.outcome() != JdbcTenantAuthorization.Outcome.ALLOWED || decision.subject() == null
                || decision.membership() == null || decision.branchMembership() == null) {
            throw new ContextRejectedException();
        }
        Binding binding;
        try (var statement = connection.prepareStatement(NATIVE)) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException();
                }
                binding = new Binding(rows.getLong(1), rows.getLong(2), rows.getInt(3), rows.getTimestamp(4), rows.getString(5));
            }
        }
        var proof = new ProtectedBranchRead(connection, emitter, operation, decision, binding);
        try (var writer = emitter.getConnection()) {
            checkEmitter(writer);
            try (var statement = writer.prepareStatement("INSERT INTO security.branch_read_admissions "
                    + "(database_oid,login_oid,backend_pid,backend_start,transaction_id,tenant_id,application_id,branch_id,"
                    + "actor_id,membership_id,branch_membership_id) VALUES (?::oid,?::oid,?,?,?::xid8,?,?,?,?,?,?)")) {
                binding.parameters(statement);
                var scope = proof.claims.scope();
                statement.setObject(6, scope.tenantId());
                statement.setObject(7, scope.applicationId());
                statement.setObject(8, scope.branchId());
                statement.setObject(9, proof.claims.actorId());
                statement.setObject(10, proof.claims.membershipId());
                statement.setObject(11, proof.claims.branchMembershipId());
                statement.setQueryTimeout(5);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException();
                }
            }
            // Autocommit writer: commit/close uncertainty is never retried. Native transaction ends on failure.
        }
        return proof;
    }

    public boolean belongsTo(OperationIdentity expected) {
        return open && operation == expected && Thread.currentThread() == owner;
    }

    public Claims verifiedClaims() {
        verify(connection);
        return claims;
    }

    public void verify(Connection expected) {
        try {
            if (!open || expected != connection || Thread.currentThread() != owner || connection.isClosed()
                    || connection.getAutoCommit() || !connection.isReadOnly()
                    || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                throw new SQLException();
            }
            // Registry RLS independently compares native PID/start/XID/database/LOGIN and TTL, never ls.*.
            try (var statement = connection.prepareStatement("SELECT tenant_id,application_id,branch_id,actor_id,"
                    + "membership_id,branch_membership_id FROM security.branch_read_admissions WHERE " + KEY)) {
                binding.parameters(statement);
                statement.setQueryTimeout(5);
                try (var rows = statement.executeQuery()) {
                    var scope = claims.scope();
                    if (!rows.next() || !scope.tenantId().equals(rows.getObject(1, UUID.class))
                            || !scope.applicationId().equals(rows.getObject(2, UUID.class))
                            || !scope.branchId().equals(rows.getObject(3, UUID.class))
                            || !claims.actorId().equals(rows.getObject(4, UUID.class))
                            || !claims.membershipId().equals(rows.getObject(5, UUID.class))
                            || !claims.branchMembershipId().equals(rows.getObject(6, UUID.class)) || rows.next()) {
                        throw new SQLException();
                    }
                }
            }
        } catch (SQLException | RuntimeException failure) {
            throw new ContextRejectedException();
        }
    }

    private static void checkEmitter(Connection writer) throws SQLException {
        if (!writer.getAutoCommit()) {
            throw new SQLException();
        }
        try (var statement = writer.createStatement()) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery("SELECT session_user,current_user,rolcanlogin AND rolconnlimit=3 "
                    + "AND NOT (rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication) "
                    + "AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid) "
                    + "AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o') "
                    + "FROM pg_roles r WHERE rolname=session_user AND current_database()='ls_core_dev'")) {
                if (!rows.next() || !EMITTER.equals(rows.getString(1)) || !EMITTER.equals(rows.getString(2)) || !rows.getBoolean(3)) {
                    throw new SQLException();
                }
            }
        }
    }

    @Override
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        try (var writer = emitter.getConnection()) {
            checkEmitter(writer);
            try (var statement = writer.prepareStatement("DELETE FROM security.branch_read_admissions WHERE " + KEY)) {
                binding.parameters(statement);
                statement.setQueryTimeout(5);
                statement.executeUpdate();
            }
        } catch (SQLException | RuntimeException failure) {
            throw new ContextRejectedException();
        }
    }
}

package com.lssoluciones.lscore.operations.infrastructure.jdbc;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lssoluciones.lscore.access.application.context.ContextFixture;
import com.lssoluciones.lscore.access.application.context.ContextRejectedException;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** Driver failures injected using mocks: distinct from real PostgreSQL integration evidence. */
class ContextTransactionTest {
    @Test
    void missingContextOrUnavailableIdentityNeverBorrowsAConnection() throws Exception {
        var pool = mock(HikariDataSource.class);
        var executor = new ContextTransaction(pool);
        assertThrows(ContextRejectedException.class, () -> executor.execute(null, q -> { }));
        var fixture = new ContextFixture();
        var context = fixture.authorized();
        fixture.unavailable = true;
        assertThrows(ContextRejectedException.class, () -> executor.execute(context, q -> { }));
        verify(pool, never()).getConnection();
    }

    @Test
    void acquisitionFailureHasNoPrivateCause() throws Exception {
        var pool = mock(HikariDataSource.class);
        when(pool.getConnection()).thenThrow(new SQLException("private server detail"));
        var fixture = new ContextFixture();
        var error = assertThrows(ContextRejectedException.class,
                () -> new ContextTransaction(pool).execute(fixture.authorized(), q -> { }));
        assertNull(error.getCause());
    }

    @Test
    void setupAndRollbackFailureStillPhysicallyEvict() throws Exception {
        var pool = mock(HikariDataSource.class);
        var connection = mock(Connection.class);
        when(pool.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true, false);
        when(connection.isReadOnly()).thenReturn(true);
        var statement = mock(PreparedStatement.class);
        var result = mock(ResultSet.class);
        when(connection.prepareStatement("SELECT current_setting(?, true)")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getString(1)).thenReturn(null);
        when(connection.prepareStatement("SELECT set_config(?, ?, true)"))
                .thenThrow(new SQLException("synthetic setup failure"));
        doThrow(new SQLException("synthetic rollback failure")).when(connection).rollback();
        var fixture = new ContextFixture();
        assertThrows(ContextRejectedException.class,
                () -> new ContextTransaction(pool).execute(fixture.authorized(), q -> {
                    throw new AssertionError("callback must not run");
                }));
        verify(connection).setAutoCommit(false);
        verify(connection).rollback();
        verify(pool).evictConnection(connection);
        verify(connection, never()).close();
        verify(connection, never()).commit();
    }

    @Test
    void errorBeforeContextSetupDiscardsConnection() throws Exception {
        var pool = mock(HikariDataSource.class);
        var connection = mock(Connection.class);
        when(pool.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.isReadOnly()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("synthetic baseline failure"));
        var fixture = new ContextFixture();
        assertThrows(ContextRejectedException.class,
                () -> new ContextTransaction(pool).execute(fixture.authorized(), q -> { }));
        verify(connection, never()).setAutoCommit(false);
        verify(pool).evictConnection(connection);
        verify(connection, never()).close();
    }
}

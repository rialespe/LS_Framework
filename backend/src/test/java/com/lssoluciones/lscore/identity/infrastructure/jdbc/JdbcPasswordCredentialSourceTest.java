package com.lssoluciones.lscore.identity.infrastructure.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JdbcPasswordCredentialSourceTest {
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement identity = mock(PreparedStatement.class);
    private final PreparedStatement lookup = mock(PreparedStatement.class);
    private final ResultSet principal = mock(ResultSet.class);
    private final ResultSet credentials = mock(ResultSet.class);
    private final JdbcPasswordCredentialSource source = new JdbcPasswordCredentialSource(dataSource);

    @BeforeEach
    void prepare() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation ->
                invocation.<String>getArgument(0).contains("FROM pg_roles") ? identity : lookup);
        when(identity.executeQuery()).thenReturn(principal);
        when(principal.next()).thenReturn(true);
        when(principal.getString(1)).thenReturn(JdbcPasswordCredentialSource.DATABASE_ROLE);
        when(principal.getString(2)).thenReturn(JdbcPasswordCredentialSource.DATABASE_ROLE);
        when(principal.getBoolean(3)).thenReturn(true);
        when(lookup.executeQuery()).thenReturn(credentials);
    }

    @Test
    void readsOnlyRequiredColumnsInOneBoundReadOnlyQueryAndClosesResources() throws Exception {
        UUID user = UUID.randomUUID();
        var expiry = OffsetDateTime.parse("2026-11-01T00:00:00Z");
        when(credentials.next()).thenReturn(true, false);
        when(credentials.getObject(1, UUID.class)).thenReturn(user);
        when(credentials.getString(2)).thenReturn("ACTIVE");
        when(credentials.getString(3)).thenReturn("private-hash");
        when(credentials.getObject(4, OffsetDateTime.class)).thenReturn(expiry);
        when(credentials.getObject(6, Boolean.class)).thenReturn(false);
        String selector = "name' OR 1=1 --";
        var result = source.findByUsername(selector).orElseThrow();
        assertEquals(user, result.userId());
        assertEquals(expiry.toInstant(), result.passwordExpiresAt());
        assertNull(result.lockedUntil());
        verify(lookup).setString(1, selector);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection, org.mockito.Mockito.times(2)).prepareStatement(sql.capture());
        String query = sql.getAllValues().get(1);
        assertTrue(query.contains("lower(btrim(u.username))=lower(btrim(?)) LIMIT 2"));
        assertTrue(query.contains("JOIN identity.user_credentials"));
        org.junit.jupiter.api.Assertions.assertFalse(query.contains(selector) || query.contains("SELECT *"));
        var order = inOrder(connection);
        order.verify(connection).setReadOnly(true);
        order.verify(connection).setAutoCommit(false);
        order.verify(connection, org.mockito.Mockito.times(2)).prepareStatement(anyString());
        verify(lookup).setQueryTimeout(5);
        verify(connection).commit();
        verify(connection).close();
        verify(credentials).close();
        verify(principal).close();
        verify(lookup).close();
        verify(identity).close();
    }

    @Test
    void emptyOrRlsFilteredSourceDoesNotInventCredentials() throws Exception {
        when(credentials.next()).thenReturn(false);
        assertTrue(source.findByUsername("unknown").isEmpty());
        verify(connection).commit();
    }

    @Test
    void refusesRuntimeOrElevatedDatabaseIdentityBeforeCredentialLookup() throws Exception {
        when(principal.getString(1)).thenReturn("ls_core_app_dev");
        rejected();
        verify(lookup, never()).executeQuery();
        verify(connection).rollback();
        verify(connection).close();
    }

    @Test
    void refusesElevatedVerifierAndAmbiguousRows() throws Exception {
        when(principal.getBoolean(3)).thenReturn(false);
        rejected();
        when(principal.getBoolean(3)).thenReturn(true);
        when(credentials.next()).thenReturn(true, true);
        rejected();
        verify(connection, org.mockito.Mockito.times(2)).rollback();
    }

    @Test
    void sanitizesAclAndConnectionErrorsIncludingRollbackFailure() throws Exception {
        when(lookup.executeQuery()).thenThrow(new SQLException("private hash/token", "42501"));
        org.mockito.Mockito.doThrow(new SQLException("private rollback diagnostic")).when(connection).rollback();
        rejected();
        when(dataSource.getConnection()).thenThrow(new SQLException("private password"));
        rejected();
    }

    private void rejected() {
        var failure = assertThrows(JdbcPasswordCredentialSource.CredentialSourceUnavailableException.class,
                () -> source.findByUsername("user"));
        assertEquals("Credential source unavailable", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }
}

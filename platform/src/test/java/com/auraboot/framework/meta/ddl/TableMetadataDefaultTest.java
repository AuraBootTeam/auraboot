package com.auraboot.framework.meta.ddl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TableMetadataDefaultTest {
    static Stream<Arguments> defaults() {
        return Stream.of(Arguments.of("1", true), Arguments.of("1::integer", true),
                Arguments.of("'1'::integer", true), Arguments.of("1::bigint", true),
                Arguments.of(" 1 ", true), Arguments.of("0", false),
                Arguments.of("random()", false), Arguments.of(null, false));
    }

    @ParameterizedTest
    @MethodSource("defaults")
    void recognizesOnlyTheKnownConstantOneDefault(String expression, boolean expected) throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getString(1)).thenReturn(expression);
        TableMetadataService service = new TableMetadataService(source, mock(DdlDialectProvider.class));
        assertEquals(expected, service.hasPostgresIntegerDefaultOne("mt_owned", "row_version"));
        verify(statement).setString(1, "mt_owned");
        verify(statement).setString(2, "row_version");
        verify(connection).close();
    }

    @Test
    void missingDefaultRequiresRepair() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(false);
        assertFalse(new TableMetadataService(source, mock(DdlDialectProvider.class))
                .hasPostgresIntegerDefaultOne("mt_owned", "row_version"));
        verify(connection).close();
    }

    @Test
    void metadataFailureDoesNotPretendTheDefaultIsCorrect() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("denied", "42501"));
        assertThrows(IllegalStateException.class, () ->
                new TableMetadataService(source, mock(DdlDialectProvider.class))
                        .hasPostgresIntegerDefaultOne("mt_owned", "row_version"));
        verify(connection).close();
    }

    @Test
    void reusesAndPreservesTheImportTransactionConnection() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getString(1)).thenReturn("1");
        TransactionSynchronizationManager.bindResource(source, new ConnectionHolder(connection));
        try {
            assertTrue(new TableMetadataService(source, mock(DdlDialectProvider.class))
                    .hasPostgresIntegerDefaultOne("mt_owned", "row_version"));
            verify(source, never()).getConnection();
            verify(connection, never()).close();
        } finally {
            TransactionSynchronizationManager.unbindResource(source);
        }
    }
}

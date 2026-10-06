package com.auraboot.framework.meta.ddl;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TableMetadataServiceTest {
    @Test
    void allMetadataReadsReuseAndRetainTheTransactionConnection() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection transaction = mock(Connection.class);
        Connection separate = mock(Connection.class);
        when(source.getConnection()).thenReturn(separate);
        DdlDialectProvider provider = mock(DdlDialectProvider.class);
        DdlDialect dialect = mock(DdlDialect.class);
        when(provider.getDialect()).thenReturn(dialect);
        when(dialect.tableExists(transaction, "sample")).thenReturn(true);
        when(dialect.columnExists(transaction, "sample", "value")).thenReturn(true);
        when(dialect.getColumnTypeDefinition(transaction, "sample", "value")).thenReturn("TEXT");
        when(dialect.isColumnNullable(transaction, "sample", "value")).thenReturn(true);
        when(dialect.indexExists(transaction, "sample", "sample_idx")).thenReturn(true);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(transaction.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        TableMetadataService service = new TableMetadataService(source, provider);
        TransactionSynchronizationManager.bindResource(source, new ConnectionHolder(transaction));
        try {
            assertTrue(service.tableExists("sample"));
            assertTrue(service.columnExists("sample", "value"));
            assertEquals("TEXT", service.getColumnTypeDefinition("sample", "value"));
            assertTrue(service.isColumnNullable("sample", "value"));
            assertTrue(service.indexExists("sample", "sample_idx"));
            assertTrue(service.hasGeneratedSingleColumnUniqueConstraint("sample", "value"));
            assertTrue(service.hasGeneratedTenantUniqueIndex("sample", "value"));
            verify(source, never()).getConnection();
            verify(transaction, never()).close();
            verify(statement, times(2)).close();
            verify(result, times(2)).close();
        } finally {
            TransactionSynchronizationManager.unbindResource(source);
        }
    }

    @Test
    void releasesOwnedConnectionOutsideATransaction() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        DdlDialectProvider provider = mock(DdlDialectProvider.class);
        DdlDialect dialect = mock(DdlDialect.class);
        when(provider.getDialect()).thenReturn(dialect);
        when(dialect.tableExists(connection, "sample")).thenReturn(true);
        assertTrue(new TableMetadataService(source, provider).tableExists("sample"));
        verify(connection).close();
    }
}

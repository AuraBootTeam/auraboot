package com.auraboot.framework.behavior.sitekey;

import com.auraboot.framework.meta.dto.IndexType;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.plugin.event.PluginImportCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring events and JDBC transaction boundaries; database operations are mocked. */
class SiteKeyPublicationTransactionTest {
    @Test
    void committedImportPublishesInSeparateTransaction() throws Exception {
        exercise(true, false);
    }

    @Test
    void publicationFailureReachesImportCallerAfterImportCommit() throws Exception {
        exercise(false, false);
    }

    @Test
    void rolledBackImportNeverPublishesIndex() throws Exception {
        exercise(true, true);
    }

    private void exercise(boolean validIndex, boolean rollback) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection importConnection = mock(Connection.class);
        Connection publicationConnection = mock(Connection.class);
        when(importConnection.getAutoCommit()).thenReturn(true);
        when(publicationConnection.getAutoCommit()).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(importConnection, publicationConnection);
        var manager = new DataSourceTransactionManager(dataSource);
        var schema = mock(SchemaManagementService.class);
        var jdbc = mock(JdbcTemplate.class);
        when(schema.createFieldIndex("behavior_site_key", "site_key", IndexType.UNIQUE))
                .thenReturn(SchemaOperationResult.builder().success(true).build());
        when(jdbc.queryForObject(contains("pg_catalog.pg_index"), eq(Boolean.class))).thenReturn(validIndex);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SiteKeyIndexInitializer.class,
                    () -> new SiteKeyIndexInitializer(schema, jdbc, manager));
            context.refresh();
            Runnable execute = () -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                context.publishEvent(new PluginImportCompletedEvent(this, 1L, "behavior"));
                verifyNoInteractions(schema);
                if (rollback) status.setRollbackOnly();
            });
            if (!validIndex && !rollback) assertThrows(IllegalStateException.class, execute::run);
            else execute.run();
            if (rollback) {
                verify(importConnection).rollback();
                verify(importConnection, never()).commit();
                verifyNoInteractions(schema, publicationConnection);
            } else {
                verify(importConnection).commit();
                verify(schema).createFieldIndex("behavior_site_key", "site_key", IndexType.UNIQUE);
                if (validIndex) {
                    verify(publicationConnection).commit();
                    verify(publicationConnection, never()).rollback();
                } else {
                    verify(publicationConnection).rollback();
                    verify(publicationConnection, never()).commit();
                }
            }
        }
    }
}

package com.auraboot.framework.meta.ddl;

import com.auraboot.framework.plugin.event.PluginImportCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hermetic schema boundary tests; real PostgreSQL publication is verified separately. */
class SoftDeleteColumnInitializerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final SoftDeleteColumnInitializer init = new SoftDeleteColumnInitializer(
            jdbc, new DataSourceTransactionManager(dataSource));

    private void model(String table, boolean exists, boolean valid) {
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of(table));
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("public." + table))).thenReturn(table);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("public." + table))).thenReturn(exists);
        when(jdbc.queryForObject(contains("attnotnull"), eq(Boolean.class), eq("public." + table))).thenReturn(valid);
    }

    @Test void startupRejectsMissingColumnWithoutDdl() {
        model("mt_fixture", false, false);
        assertThrows(IllegalStateException.class, init::onApplicationReady);
        verify(jdbc, never()).execute(anyString());
    }
    @Test void startupAcceptsValidStaticColumnWithoutDdl() {
        model("ab_fixture", true, true);
        init.onApplicationReady();
        verify(jdbc, never()).execute(anyString());
    }
    @Test void startupRejectsInvalidColumnWithoutRepair() {
        model("mt_fixture", true, false);
        assertThrows(IllegalStateException.class, init::onApplicationReady);
        verify(jdbc, never()).execute(anyString());
    }
    @Test void startupRejectsMissingPhysicalTable() {
        model("mt_fixture", false, false);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("public.mt_fixture"))).thenReturn(null);
        assertThrows(IllegalStateException.class, init::onApplicationReady);
        verify(jdbc, never()).execute(anyString());
    }
    @Test void discoveryFailureIsNotSwallowed() {
        when(jdbc.queryForList(anyString(), eq(String.class))).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class, init::onApplicationReady);
    }
    @Test void emptyInventoryIsReadOnly() {
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        init.onApplicationReady();
        verify(jdbc, never()).execute(anyString());
    }
    @Test void explicitImportPublishesDynamicColumnAndCommits() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(connection);
        model("mt_fixture", false, true);
        init.onPluginImportCompleted(new PluginImportCompletedEvent(this, 1L, "fixture"));
        verify(jdbc).execute("ALTER TABLE public.mt_fixture ADD COLUMN deleted_flag BOOLEAN NOT NULL DEFAULT FALSE");
        verify(connection).commit();
    }
    @Test void explicitImportCannotRepairStaticSchema() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(connection);
        model("ab_fixture", false, false);
        assertThrows(IllegalStateException.class,
                () -> init.onPluginImportCompleted(new PluginImportCompletedEvent(this, 1L, "fixture")));
        verify(jdbc, never()).execute(anyString());
        verify(connection).rollback();
    }
}

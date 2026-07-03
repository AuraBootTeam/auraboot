package com.auraboot.framework.meta.ddl;

import com.auraboot.framework.plugin.event.PluginImportCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link SoftDeleteColumnInitializer} — the dual-trigger {@code deleted_flag}
 * column convergence for soft-delete dynamic models. Verifies it ALTERs a soft-delete mt_ table
 * that lacks the column, skips one that already has it, and skips a table not yet created. The
 * real end-to-end (import → command soft-delete → row hidden from list) is covered by the
 * quote/BOM golden.
 */
class SoftDeleteColumnInitializerTest {

    private final TableMetadataService tableMeta = mock(TableMetadataService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SoftDeleteColumnInitializer init = new SoftDeleteColumnInitializer(tableMeta, jdbc);

    @Test
    void addsDeletedFlagColumnToSoftDeleteTableMissingIt() {
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("mt_qo_quote_common"));
        when(tableMeta.tableExists("mt_qo_quote_common")).thenReturn(true);
        when(tableMeta.columnExists("mt_qo_quote_common", "deleted_flag")).thenReturn(false);

        init.onPluginImportCompleted(new PluginImportCompletedEvent(this, 1L, "quote-core"));

        verify(jdbc).execute(contains("ALTER TABLE mt_qo_quote_common ADD COLUMN deleted_flag"));
    }

    @Test
    void skipsTableThatAlreadyHasColumn() {
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("ab_team"));
        when(tableMeta.tableExists("ab_team")).thenReturn(true);
        when(tableMeta.columnExists("ab_team", "deleted_flag")).thenReturn(true);

        init.onApplicationReady();

        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void skipsTableThatDoesNotExistYet() {
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("mt_not_created_yet"));
        when(tableMeta.tableExists("mt_not_created_yet")).thenReturn(false);

        init.onApplicationReady();

        verify(jdbc, never()).execute(anyString());
        verify(tableMeta, never()).columnExists(anyString(), anyString());
    }

    @Test
    void noSoftDeleteModels_noop() {
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        init.onApplicationReady();

        verify(jdbc, never()).execute(anyString());
    }
}

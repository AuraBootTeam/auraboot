package com.auraboot.framework.behavior.sitekey;

import com.auraboot.framework.meta.dto.IndexType;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.plugin.event.PluginImportCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Import owns index DDL; startup only validates the published schema. */
class SiteKeyIndexInitializerTest {

    private final SchemaManagementService schema = mock(SchemaManagementService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SiteKeyIndexInitializer init = new SiteKeyIndexInitializer(schema, jdbc, mock(org.springframework.transaction.PlatformTransactionManager.class));

    @Test
    void importOfBehaviorPlugin_createsIndex() {
        when(schema.createFieldIndex("behavior_site_key", "site_key", IndexType.UNIQUE))
                .thenReturn(SchemaOperationResult.builder().success(true).build());
        when(jdbc.queryForObject(contains("pg_catalog.pg_index"), eq(Boolean.class))).thenReturn(true);
        init.onPluginImportCompleted(new PluginImportCompletedEvent(this, 1L, "behavior"));
        verify(schema).createFieldIndex("behavior_site_key", "site_key", IndexType.UNIQUE);
    }

    @Test
    void importOfOtherPlugin_noop() {
        init.onPluginImportCompleted(new PluginImportCompletedEvent(this, 1L, "crm"));
        verifyNoInteractions(schema);
    }

    @Test
    void appReady_whenIndexValid_onlyReadsCatalog() {
        when(jdbc.queryForObject(contains("to_regclass"), eq(String.class)))
                .thenReturn("mt_behavior_site_key");
        when(jdbc.queryForObject(contains("pg_catalog.pg_index"), eq(Boolean.class))).thenReturn(true);
        init.onApplicationReady();
        verifyNoInteractions(schema);
    }

    @Test
    void appReady_whenTableMissing_noop() {
        when(jdbc.queryForObject(contains("to_regclass"), eq(String.class))).thenReturn(null);
        init.onApplicationReady();
        verifyNoInteractions(schema);
        verify(jdbc, never()).queryForObject(contains("pg_catalog.pg_index"), eq(Boolean.class));
    }

    @Test
    void appReady_whenIndexMissing_failsWithoutRepair() {
        when(jdbc.queryForObject(contains("to_regclass"), eq(String.class)))
                .thenReturn("mt_behavior_site_key");
        when(jdbc.queryForObject(contains("pg_catalog.pg_index"), eq(Boolean.class))).thenReturn(false);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, init::onApplicationReady);
        verifyNoInteractions(schema);
    }

    @Test
    void importFailure_isNotSwallowed() {
        doThrow(new IllegalStateException("DDL denied")).when(schema)
                .createFieldIndex("behavior_site_key", "site_key", IndexType.UNIQUE);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> init.onPluginImportCompleted(new PluginImportCompletedEvent(this, 1L, "behavior")));
    }
    @Test
    void importFailedResult_isRejected() {
        when(schema.createFieldIndex("behavior_site_key", "site_key", IndexType.UNIQUE))
                .thenReturn(SchemaOperationResult.builder().success(false).build());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> init.onPluginImportCompleted(new PluginImportCompletedEvent(this, 1L, "behavior")));
    }

    @Test
    void importSuccessWithoutValidIndex_isRejected() {
        when(schema.createFieldIndex("behavior_site_key", "site_key", IndexType.UNIQUE))
                .thenReturn(SchemaOperationResult.builder().success(true).build());
        when(jdbc.queryForObject(contains("pg_catalog.pg_index"), eq(Boolean.class))).thenReturn(false);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> init.onPluginImportCompleted(new PluginImportCompletedEvent(this, 1L, "behavior")));
    }

}

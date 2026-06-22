package com.auraboot.framework.behavior.sitekey;

import com.auraboot.framework.meta.dto.IndexType;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.plugin.event.PluginImportCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit test for {@link SiteKeyIndexInitializer} — the dual-trigger index convergence. Verifies
 * the import trigger only fires for the {@code behavior} plugin and the app-ready backstop only
 * fires when the table already exists. The real DDL (global unique, index scan) is asserted in
 * {@code KeyedCollectIT}.
 */
class SiteKeyIndexInitializerTest {

    private final SchemaManagementService schema = mock(SchemaManagementService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SiteKeyIndexInitializer init = new SiteKeyIndexInitializer(schema, jdbc);

    @Test
    void importOfBehaviorPlugin_createsIndex() {
        init.onPluginImportCompleted(new PluginImportCompletedEvent(this, 1L, "behavior"));
        verify(schema).createFieldIndex("behavior_site_key", "site_key", IndexType.UNIQUE);
    }

    @Test
    void importOfOtherPlugin_noop() {
        init.onPluginImportCompleted(new PluginImportCompletedEvent(this, 1L, "crm"));
        verifyNoInteractions(schema);
    }

    @Test
    void appReady_whenTableExists_createsIndexUnderOwningTenant() {
        when(jdbc.queryForObject(contains("to_regclass"), eq(String.class)))
                .thenReturn("mt_behavior_site_key");
        // Backstop must resolve the model's owning tenant so the createFieldIndex logging path
        // (getModelDefinition → getCurrentTenantId) does not throw on the bare startup thread.
        when(jdbc.queryForObject(contains("ab_meta_model"), eq(Long.class), eq("behavior_site_key")))
                .thenReturn(42L);
        init.onApplicationReady();
        verify(schema).createFieldIndex("behavior_site_key", "site_key", IndexType.UNIQUE);
    }

    @Test
    void appReady_whenTableMissing_noop() {
        when(jdbc.queryForObject(contains("to_regclass"), eq(String.class))).thenReturn(null);
        init.onApplicationReady();
        verifyNoInteractions(schema);
    }

    @Test
    void appReady_whenNoOwningTenant_skipsToAvoidTenantlessLoggingCrash() {
        when(jdbc.queryForObject(contains("to_regclass"), eq(String.class)))
                .thenReturn("mt_behavior_site_key");
        when(jdbc.queryForObject(contains("ab_meta_model"), eq(Long.class), eq("behavior_site_key")))
                .thenReturn(null);
        init.onApplicationReady();
        verifyNoInteractions(schema);
    }
}

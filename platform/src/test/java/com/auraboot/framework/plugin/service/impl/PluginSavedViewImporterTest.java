package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.dto.imports.SavedViewDefinitionDTO;
import com.auraboot.framework.view.entity.SavedView;
import com.auraboot.framework.view.entity.ViewConfig;
import com.auraboot.framework.view.mapper.SavedViewMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hermetic contracts for preset identity, import outcomes and metadata precedence. */
class PluginSavedViewImporterTest {
    private SavedViewMapper views;
    private PageSchemaMapper pages;
    private PluginSavedViewImporter importer;

    @BeforeEach
    void setup() {
        views = mock(SavedViewMapper.class);
        pages = mock(PageSchemaMapper.class);
        importer = new PluginSavedViewImporter(views, pages, new ObjectMapper());
    }

    @Test
    void stableKeyAllowsRenameButRejectsSameNameWithAnotherKey() {
        SavedViewDefinitionDTO dto = preset();
        dto.setViewKey("  orders.open  ");
        SavedView existing = existing("Old name", "orders.open");
        assertTrue(importer.matchesPluginSavedView(dto, existing));
        existing.setName(dto.getName());
        existing.getViewConfig().getMeta().setViewKey("orders.closed");
        assertFalse(importer.matchesPluginSavedView(dto, existing));
    }

    @Test
    void legacyIdentityRequiresNameAndViewTypeAndRejectsNull() {
        SavedViewDefinitionDTO dto = preset();
        SavedView existing = existing(dto.getName(), null);
        assertTrue(importer.matchesPluginSavedView(dto, existing));
        existing.setViewType("KANBAN");
        assertFalse(importer.matchesPluginSavedView(dto, existing));
        assertFalse(importer.matchesPluginSavedView(dto, null));
        assertFalse(importer.matchesPluginSavedView(null, existing));
    }

    @Test
    void createsTenantPresetWithDefaultsAndCountsCreation() {
        SavedViewDefinitionDTO dto = preset();
        when(views.findGlobalViews("orders", null)).thenReturn(List.of());
        ImportExecuteResult result = execute(dto);
        ArgumentCaptor<SavedView> row = ArgumentCaptor.forClass(SavedView.class);
        verify(views).insertSavedView(row.capture());
        SavedView saved = row.getValue();
        assertEquals(42L, saved.getTenantId());
        assertNotNull(saved.getPid());
        assertEquals("global", saved.getScope());
        assertFalse(saved.getDeletedFlag());
        assertFalse(saved.getIsDefault());
        assertEquals(0, saved.getSortOrder());
        assertNotNull(saved.getCreatedAt());
        assertEquals("plugin", saved.getViewConfig().getMeta().getManagedBy());
        assertTrue(saved.getViewConfig().getMeta().getLocked());
        assertTrue(saved.getViewConfig().getMeta().getAllowUserCopy());
        assertTrue(saved.getViewConfig().getMeta().getAllowUserOverride());
        assertEquals(Map.of("CREATE", 1), result.getResourceCounts().get("SAVED_VIEW"));
        verify(views, never()).updateSavedView(any());
    }

    @Test
    void upgradeRenamesOneStablePresetWithoutChangingExistingDefaults() {
        SavedViewDefinitionDTO dto = preset();
        dto.setViewKey("orders.open");
        dto.setDescription("New description");
        SavedView existing = existing("Old name", "orders.open");
        existing.setIsDefault(true);
        existing.setSortOrder(7);
        when(views.findGlobalViews("orders", null)).thenReturn(List.of(existing));
        ImportExecuteResult result = execute(dto);
        verify(views).updateSavedView(same(existing));
        verify(views, never()).insertSavedView(any());
        assertEquals("Open orders", existing.getName());
        assertEquals("New description", existing.getDescription());
        assertTrue(existing.getIsDefault());
        assertEquals(7, existing.getSortOrder());
        assertNotNull(existing.getUpdatedAt());
        assertEquals(Map.of("UPDATE", 1), result.getResourceCounts().get("SAVED_VIEW"));
    }

    @Test
    void explicitFalseAndZeroOverrideEmbeddedMetadata() {
        SavedViewDefinitionDTO dto = preset();
        dto.setLocked(false);
        dto.setAllowUserCopy(false);
        dto.setAllowUserOverride(false);
        dto.setPinAsQuickFilter(false);
        dto.setQuickFilterOrder(0);
        dto.setManagedBy("  owner  ");
        dto.setQuickFilterIcon("  icon  ");
        dto.setViewConfig(Map.of("meta", Map.of("locked", true, "allowUserCopy", true,
                "allowUserOverride", true, "pinnedAsQuickFilter", true,
                "quickFilterOrder", 9, "originViewPid", "source-preset")));
        when(views.findGlobalViews("orders", null)).thenReturn(List.of());
        execute(dto);
        ArgumentCaptor<SavedView> row = ArgumentCaptor.forClass(SavedView.class);
        verify(views).insertSavedView(row.capture());
        ViewConfig.Meta meta = row.getValue().getViewConfig().getMeta();
        assertFalse(meta.getLocked());
        assertFalse(meta.getAllowUserCopy());
        assertFalse(meta.getAllowUserOverride());
        assertFalse(meta.getPinnedAsQuickFilter());
        assertEquals(0, meta.getQuickFilterOrder());
        assertEquals("owner", meta.getManagedBy());
        assertEquals("icon", meta.getQuickFilterIcon());
        assertEquals("source-preset", meta.getOriginViewPid());
    }

    @Test
    void missingTopLevelValuesRetainEmbeddedFalseAndMetadata() {
        SavedViewDefinitionDTO dto = preset();
        dto.setViewConfig(Map.of("meta", Map.of("locked", false, "allowUserCopy", false,
                "allowUserOverride", false, "managedBy", "embedded-owner",
                "pinnedAsQuickFilter", false, "quickFilterOrder", 0)));
        when(views.findGlobalViews("orders", null)).thenReturn(List.of());
        execute(dto);
        ArgumentCaptor<SavedView> row = ArgumentCaptor.forClass(SavedView.class);
        verify(views).insertSavedView(row.capture());
        ViewConfig.Meta meta = row.getValue().getViewConfig().getMeta();
        assertFalse(meta.getLocked());
        assertFalse(meta.getAllowUserCopy());
        assertFalse(meta.getAllowUserOverride());
        assertFalse(meta.getPinnedAsQuickFilter());
        assertEquals(0, meta.getQuickFilterOrder());
        assertEquals("embedded-owner", meta.getManagedBy());
    }

    @Test
    void missingPageSkipsWriteAndAddsWarningWithoutResourceCount() {
        SavedViewDefinitionDTO dto = preset();
        dto.setPageKey("orders-list");
        ImportExecuteResult result = execute(dto);
        verify(pages).selectAnyByPageKey("orders-list");
        verifyNoInteractions(views);
        assertEquals(1, result.getWarnings().size());
        assertTrue(result.getWarnings().get(0).contains("orders-list"));
        assertTrue(result.getResourceCounts().isEmpty());
    }

    @Test
    void validPageAndExplicitOrderingArePreserved() {
        SavedViewDefinitionDTO dto = preset();
        dto.setPageKey("orders-list");
        dto.setScope("team");
        dto.setIsDefault(true);
        dto.setSortOrder(4);
        when(pages.selectAnyByPageKey("orders-list")).thenReturn(new PageSchema());
        when(views.findGlobalViews("orders", "orders-list")).thenReturn(List.of());
        execute(dto);
        ArgumentCaptor<SavedView> row = ArgumentCaptor.forClass(SavedView.class);
        verify(views).insertSavedView(row.capture());
        assertEquals("orders-list", row.getValue().getPageKey());
        assertEquals("team", row.getValue().getScope());
        assertTrue(row.getValue().getIsDefault());
        assertEquals(4, row.getValue().getSortOrder());
    }

    @Test
    void absentEmptyAndInvalidPresetsDoNotWriteOrCount() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        ImportExecuteResult result = new ImportExecuteResult();
        importer.importSavedViews(manifest, result, 42L);
        manifest.setSavedViews(List.of());
        importer.importSavedViews(manifest, result, 42L);
        manifest.setSavedViews(List.of(new SavedViewDefinitionDTO()));
        importer.importSavedViews(manifest, result, 42L);
        verifyNoInteractions(views, pages);
        assertTrue(result.getResourceCounts().isEmpty());
    }

    private ImportExecuteResult execute(SavedViewDefinitionDTO dto) {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setSavedViews(List.of(dto));
        ImportExecuteResult result = new ImportExecuteResult();
        importer.importSavedViews(manifest, result, 42L);
        return result;
    }

    private SavedViewDefinitionDTO preset() {
        return SavedViewDefinitionDTO.builder().name("Open orders")
                .modelCode("orders").viewType("TABLE").build();
    }

    private SavedView existing(String name, String key) {
        SavedView row = new SavedView();
        row.setName(name);
        row.setViewType("TABLE");
        ViewConfig config = new ViewConfig();
        config.setMeta(ViewConfig.Meta.builder().viewKey(key).build());
        row.setViewConfig(config);
        return row;
    }
}

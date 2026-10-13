package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * menus.json is the plugin's complete menu surface: codes this plugin family imported
 * before but no longer declares are pruned on re-import, and uninstall deactivates the
 * whole imported surface. Scope is the ab_plugin_resource tracking rows of the same
 * plugin_id; authoring-managed rows are excluded in the tracking query itself.
 */
@ExtendWith(MockitoExtension.class)
class PluginResourceImporterMenuPruneTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private MenuMapper menuMapper;
    @Mock private MenuService menuService;
    @Mock private PluginResourceMapper pluginResourceMapper;

    private final PluginResourceImporterImpl importer =
            mock(PluginResourceImporterImpl.class, Mockito.CALLS_REAL_METHODS);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(importer, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(importer, "menuMapper", menuMapper);
        ReflectionTestUtils.setField(importer, "menuService", menuService);
        ReflectionTestUtils.setField(importer, "pluginResourceMapper", pluginResourceMapper);
    }

    private void trackedCodes(String... codes) {
        lenient().when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any(), any()))
                .thenReturn(List.of(codes));
    }

    private void activeMenu(String code, Long id, String pid) {
        lenient().when(menuMapper.findIdByCode(42L, code)).thenReturn(id);
        lenient().when(menuMapper.findPidByCode(42L, code)).thenReturn(pid);
        lenient().when(menuService.deleteMenu(id)).thenReturn(true);
    }

    @Test
    @DisplayName("prune removes tracked codes absent from the declaration and audits each row")
    void prunesStaleCodesAndWritesAuditRows() {
        trackedCodes("jiejia_api_errors", "kept_menu");
        activeMenu("jiejia_api_errors", 11L, "menu-pid-1");

        int pruned = importer.pruneStaleMenus("plugin-pid", "import-1", 42L, List.of("kept_menu"));

        assertThat(pruned).isEqualTo(1);
        verify(menuService).deleteMenu(11L);
        verify(pluginResourceMapper).insert(argThat((PluginResource r) ->
                "menu".equals(r.getResourceType())
                        && "jiejia_api_errors".equals(r.getResourceCode())
                        && "delete".equals(r.getAction())
                        && "plugin-pid".equals(r.getPluginPid())
                        && "import-1".equals(r.getImportId())));
    }

    @Test
    @DisplayName("prune is a no-op when every tracked code is still declared")
    void keepsFullyDeclaredSurface() {
        trackedCodes("kept_menu");

        int pruned = importer.pruneStaleMenus("plugin-pid", "import-1", 42L, List.of("kept_menu"));

        assertThat(pruned).isZero();
        verify(menuService, never()).deleteMenu(any());
        verify(pluginResourceMapper, never()).insert(any(PluginResource.class));
    }

    @Test
    @DisplayName("prune is idempotent when the menu row is already gone")
    void skipsCodesWithoutActiveMenuRow() {
        trackedCodes("ghost_menu");
        when(menuMapper.findIdByCode(42L, "ghost_menu")).thenReturn(null);

        int pruned = importer.pruneStaleMenus("plugin-pid", "import-1", 42L, List.of());

        assertThat(pruned).isZero();
        verify(menuService, never()).deleteMenu(any());
    }

    @Test
    @DisplayName("uninstall deactivation removes the whole imported surface without audit rows")
    void deactivateAllRemovesEveryTrackedMenu() {
        trackedCodes("jiejia_api_errors", "jiejia_sync_tasks");
        activeMenu("jiejia_api_errors", 11L, "menu-pid-1");
        activeMenu("jiejia_sync_tasks", 12L, "menu-pid-2");

        int deactivated = importer.deactivateAllPluginMenus("plugin-pid", 42L);

        assertThat(deactivated).isEqualTo(2);
        verify(menuService).deleteMenu(11L);
        verify(menuService).deleteMenu(12L);
        verify(pluginResourceMapper, never()).insert(any(PluginResource.class));
    }

    @Test
    @DisplayName("a menu the service refuses (active children) still converges via the direct soft-delete fallback")
    void serviceRefusalFallsBackToDirectSoftDelete() {
        trackedCodes("broken_menu", "healthy_menu");
        activeMenu("healthy_menu", 12L, "menu-pid-2");
        lenient().when(menuMapper.findIdByCode(42L, "broken_menu")).thenReturn(11L);
        lenient().when(menuMapper.findPidByCode(42L, "broken_menu")).thenReturn("menu-pid-1");
        when(menuService.deleteMenu(11L)).thenThrow(new IllegalStateException("child menus exist"));

        int pruned = importer.deactivateAllPluginMenus("plugin-pid", 42L);

        assertThat(pruned).isEqualTo(2);
        verify(menuService).deleteMenu(12L);
        verify(menuMapper).softDeleteById(11L);
    }
}

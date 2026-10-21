package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.menu.entity.Menu;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

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
        when(pluginResourceMapper.findByTenantPluginAndResource(42L, "plugin-pid", "menu", "jiejia_api_errors"))
                .thenReturn(null);
        when(pluginResourceMapper.findByTypeAndCode(42L, "menu", "jiejia_api_errors")).thenReturn(null);

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
    @DisplayName("prune audit re-homes the existing tracking row instead of inserting a duplicate")
    void pruneAuditUpdatesExistingTrackingRow() {
        trackedCodes("jiejia_api_errors");
        activeMenu("jiejia_api_errors", 11L, "menu-pid-1");
        PluginResource tracked = new PluginResource();
        when(pluginResourceMapper.findByTenantPluginAndResource(42L, "plugin-pid", "menu", "jiejia_api_errors"))
                .thenReturn(tracked);

        int pruned = importer.pruneStaleMenus("plugin-pid", "import-1", 42L, List.of());

        assertThat(pruned).isEqualTo(1);
        verify(pluginResourceMapper).updateById(argThat((PluginResource r) ->
                r == tracked && "delete".equals(r.getAction()) && "menu-pid-1".equals(r.getResourcePid())));
        verify(pluginResourceMapper, never()).insert(any(PluginResource.class));
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
    @DisplayName("uninstall deactivation removes the whole imported surface and audits under the plugin")
    void deactivateAllRemovesEveryTrackedMenu() {
        trackedCodes("jiejia_api_errors", "jiejia_sync_tasks");
        activeMenu("jiejia_api_errors", 11L, "menu-pid-1");
        activeMenu("jiejia_sync_tasks", 12L, "menu-pid-2");
        when(pluginResourceMapper.findByTenantPluginAndResource(any(), any(), any(), any()))
                .thenReturn(null);
        when(pluginResourceMapper.findByTypeAndCode(any(), any(), any())).thenReturn(null);

        int deactivated = importer.deactivateAllPluginMenus("plugin-pid", 42L);

        assertThat(deactivated).isEqualTo(2);
        verify(menuService).deleteMenu(11L);
        verify(menuService).deleteMenu(12L);
        verify(pluginResourceMapper, times(2)).insert(argThat((PluginResource r) ->
                "delete".equals(r.getAction()) && "plugin-pid".equals(r.getPluginPid())));
    }

    @Test
    @DisplayName("service refusal aborts import without a fallback that hides a rollback-only transaction")
    void serviceRefusalAbortsWithoutFallback() {
        trackedCodes("broken_menu", "healthy_menu");
        lenient().when(menuMapper.findIdByCode(42L, "healthy_menu")).thenReturn(12L);
        lenient().when(menuMapper.findPidByCode(42L, "healthy_menu")).thenReturn("menu-pid-2");
        lenient().when(menuMapper.findIdByCode(42L, "broken_menu")).thenReturn(11L);
        lenient().when(menuMapper.findPidByCode(42L, "broken_menu")).thenReturn("menu-pid-1");
        when(menuService.deleteMenu(11L)).thenThrow(new IllegalStateException("child menus exist"));

        assertThatThrownBy(() -> importer.deactivateAllPluginMenus("plugin-pid", 42L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("child menus");
        verify(menuService, never()).deleteMenu(12L);
        verify(menuMapper, never()).softDeleteById(any());
        verify(pluginResourceMapper, never()).insert(any(PluginResource.class));
    }

    private Menu menu(long id, long parentId, String code) {
        Menu menu = new Menu();
        menu.setId(id);
        menu.setParentId(parentId);
        menu.setCode(code);
        return menu;
    }

    @Test
    void prunesNestedStaleMenusChildrenFirst() {
        trackedCodes("root", "branch", "leaf");
        activeMenu("root", 11L, "root-pid");
        activeMenu("branch", 12L, "branch-pid");
        activeMenu("leaf", 13L, "leaf-pid");
        when(menuMapper.selectList(any())).thenReturn(List.of(
                menu(11, 0, "root"), menu(12, 11, "branch"), menu(13, 12, "leaf")));

        assertThat(importer.pruneStaleMenus("plugin-pid", "import-1", 42L, List.of())).isEqualTo(3);

        var order = inOrder(menuService);
        order.verify(menuService).deleteMenu(13L);
        order.verify(menuService).deleteMenu(12L);
        order.verify(menuService).deleteMenu(11L);
    }

    @Test
    void refusesRetainedChildBeforeAnyDeletion() {
        trackedCodes("root", "other");
        activeMenu("root", 11L, "root-pid");
        activeMenu("other", 12L, "other-pid");
        when(menuMapper.selectList(any())).thenReturn(List.of(
                menu(11, 0, "root"), menu(12, 0, "other"), menu(13, 11, "tenant-owned-child")));

        assertThatThrownBy(() -> importer.deactivateAllPluginMenus("plugin-pid", 42L))
                .hasMessageContaining("retained child");
        verify(menuService, never()).deleteMenu(any());
        verify(pluginResourceMapper, never()).insert(any(PluginResource.class));
    }

    @Test
    void refusesCycleBeforeAnyDeletion() {
        trackedCodes("root", "branch");
        activeMenu("root", 11L, "root-pid");
        activeMenu("branch", 12L, "branch-pid");
        when(menuMapper.selectList(any())).thenReturn(List.of(
                menu(11, 12, "root"), menu(12, 11, "branch")));

        assertThatThrownBy(() -> importer.deactivateAllPluginMenus("plugin-pid", 42L))
                .hasMessageContaining("cyclic");
        verify(menuService, never()).deleteMenu(any());
    }

    @Test
    void doesNotAuditAnUnsuccessfulDelete() {
        trackedCodes("root");
        activeMenu("root", 11L, "root-pid");
        when(menuService.deleteMenu(11L)).thenReturn(false);

        assertThatThrownBy(() -> importer.pruneStaleMenus("plugin-pid", "import-1", 42L, List.of()))
                .hasMessageContaining("not deleted");
        verify(pluginResourceMapper, never()).insert(any(PluginResource.class));
    }
}

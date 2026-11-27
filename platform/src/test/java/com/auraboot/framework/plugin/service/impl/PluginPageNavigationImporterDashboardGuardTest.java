package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.dashboard.dto.DashboardDTO;
import com.auraboot.framework.dashboard.service.DashboardService;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.service.DictService;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.auraboot.framework.plugin.dto.imports.DashboardDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.ResourceAction;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Global-flagged dashboards carry an ownership guard. A governed import into a tenant that
 * neither created the dashboard nor holds dashboard:manage used to abort the WHOLE tenant
 * import with FORBIDDEN; the importer must degrade to a read-only reference instead.
 */
class PluginPageNavigationImporterDashboardGuardTest {

    final PluginResourceImporterImpl facadeReceiver = mock(PluginResourceImporterImpl.class);
    final DictService dicts = mock(DictService.class);
    final MenuService menus = mock(MenuService.class);
    final PageSchemaService pages = mock(PageSchemaService.class);
    final DashboardService dashboards = mock(DashboardService.class);
    final EnvironmentService environments = mock(EnvironmentService.class);
    final MenuMapper menuMapper = mock(MenuMapper.class);
    final PageSchemaMapper pageSchemaMapper = mock(PageSchemaMapper.class);
    final DictMapper dictMapper = mock(DictMapper.class);
    final PluginImportSupport support = mock(PluginImportSupport.class);

    final PluginPageNavigationImporter importer = new PluginPageNavigationImporter(
            facadeReceiver, dicts, menus, pages, dashboards, environments,
            menuMapper, new ObjectMapper(), pageSchemaMapper, dictMapper,
            new HashMap<>(), LoggerFactory.getLogger("test"), support);

    DashboardDefinitionDTO dashboard(String code) {
        DashboardDefinitionDTO dto = new DashboardDefinitionDTO();
        dto.setCode(code);
        dto.setTitle("Impact Board");
        dto.setWidgets(List.of(Map.of("type", "metric")));
        return dto;
    }

    @Test
    void forbiddenGlobalDashboardUpdateDegradesToSkipInsteadOfFailingTheTenantImport() {
        DashboardDTO existing = new DashboardDTO();
        existing.setPid("dash-pid");
        when(dashboards.findByCode("impact_board")).thenReturn(existing);
        when(dashboards.update(eq("dash-pid"), any())).thenThrow(new ValidationException(
                ResponseCode.FORBIDDEN, "You don't have permission to modify this global dashboard"));

        PluginResource resource = importer.importDashboard(dashboard("impact_board"),
                "plugin-pid", "import-id", 42L, ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(resource.getAction()).isEqualToIgnoringCase(ResourceAction.SKIP.name());
        assertThat(resource.getResourcePid()).isEqualTo("dash-pid");
        assertThat(resource.getCurrentState()).containsEntry("skipReason",
                "global dashboard ownership guard — kept read-only reference");
        verify(dashboards, never()).publish(any());
    }

    @Test
    void nonForbiddenValidationFailuresStillEscape() {
        DashboardDTO existing = new DashboardDTO();
        existing.setPid("dash-pid");
        when(dashboards.findByCode("impact_board")).thenReturn(existing);
        when(dashboards.update(eq("dash-pid"), any())).thenThrow(new ValidationException(
                ResponseCode.NOT_FOUND, "malformed layout"));

        assertThatThrownBy(() -> importer.importDashboard(dashboard("impact_board"),
                "plugin-pid", "import-id", 42L, ImportRequest.ConflictStrategy.OVERWRITE))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("malformed layout");
    }

    @Test
    void updatableDashboardStillReportsUpdate() {
        DashboardDTO existing = new DashboardDTO();
        existing.setPid("dash-pid");
        when(dashboards.findByCode("impact_board")).thenReturn(existing);

        PluginResource resource = importer.importDashboard(dashboard("impact_board"),
                "plugin-pid", "import-id", 42L, ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(resource.getAction()).isEqualToIgnoringCase(ResourceAction.UPDATE.name());
        verify(dashboards).update(eq("dash-pid"), any());
        verify(dashboards).publish("dash-pid");
    }
}

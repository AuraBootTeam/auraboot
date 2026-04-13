package com.auraboot.framework.integration.dashboard;

import com.auraboot.framework.dashboard.dto.DashboardDTO;
import com.auraboot.framework.dashboard.service.DashboardService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for DashboardService.getOrCreateWorkbench().
 * Validates auto-creation from template, idempotency, and default widget structure.
 */
class WorkbenchIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DashboardService dashboardService;

    @Test
    void getOrCreateWorkbench_createsOnFirstCall() {
        DashboardDTO workbench = dashboardService.getOrCreateWorkbench();

        assertThat(workbench).isNotNull();
        assertThat(workbench.getPid()).isNotBlank();
        assertThat(workbench.getScope()).isEqualTo("workbench");
        assertThat(workbench.getStatus()).isEqualTo("published");
        assertThat(workbench.getOwnerId()).isNotBlank();
        assertThat(workbench.getTitle()).isEqualTo("My Workbench");
        assertThat(workbench.getWidgets()).isNotNull();
    }

    @Test
    void getOrCreateWorkbench_returnsSameOnSecondCall() {
        DashboardDTO first = dashboardService.getOrCreateWorkbench();
        DashboardDTO second = dashboardService.getOrCreateWorkbench();

        assertThat(first.getPid()).isEqualTo(second.getPid());
        assertThat(first.getScope()).isEqualTo(second.getScope());
    }

    @Test
    void getOrCreateWorkbench_hasDefaultWidgets() {
        DashboardDTO workbench = dashboardService.getOrCreateWorkbench();

        JsonNode widgets = workbench.getWidgets();
        assertThat(widgets).isNotNull();
        assertThat(widgets.isArray()).isTrue();
        // Default template has 4 widgets: StatsRowWidget, InboxWidget, ShortcutsWidget, RecentWidget
        assertThat(widgets.size()).isEqualTo(4);

        // Verify widget types
        assertThat(widgets.get(0).get("type").asText()).isEqualTo("StatsRowWidget");
        assertThat(widgets.get(1).get("type").asText()).isEqualTo("InboxWidget");
        assertThat(widgets.get(2).get("type").asText()).isEqualTo("ShortcutsWidget");
        assertThat(widgets.get(3).get("type").asText()).isEqualTo("RecentWidget");

        // Verify each widget has config with title
        for (int i = 0; i < widgets.size(); i++) {
            JsonNode widget = widgets.get(i);
            assertThat(widget.has("config")).isTrue();
            assertThat(widget.get("config").has("title")).isTrue();
            assertThat(widget.get("config").get("title").asText()).isNotBlank();
        }
    }
}

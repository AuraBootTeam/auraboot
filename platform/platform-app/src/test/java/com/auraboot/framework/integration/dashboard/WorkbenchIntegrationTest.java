package com.auraboot.framework.integration.dashboard;

import com.auraboot.framework.dashboard.dto.DashboardDTO;
import com.auraboot.framework.dashboard.service.DashboardService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for DashboardService.getOrCreateWorkbench().
 * Validates auto-creation from template, idempotency, and default widget structure.
 */
class WorkbenchIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private JdbcTemplate jdbc;

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

    @Test
    void getOrCreateWorkbench_composesOnlyPublishedGlobalContributionsWithoutPersistingThem() {
        String code = "workbench_it_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String expectedWidgetId = "workbench-contribution-" + code + "-trend";
        jdbc.update("""
                INSERT INTO ab_dashboard (
                    pid, tenant_id, code, title, scope, owner_id,
                    layout_config, widgets, status, is_default, sort_order,
                    extension, deleted_flag, created_at, updated_at, created_by, updated_by
                ) VALUES (
                    ?, ?, ?, 'Workbench contribution IT', 'global', NULL,
                    '{"columns":12,"rowHeight":100,"gap":16}'::jsonb,
                    '[{"id":"trend","type":"smart-line-chart","x":0,"y":0,"w":12,"h":4,"config":{"title":"Trend"}}]'::jsonb,
                    'published', FALSE, 100,
                    '{"workbenchContribution":{"enabled":true}}'::jsonb,
                    FALSE, NOW(), NOW(), ?, ?
                )
                """, UUID.randomUUID().toString().replace("-", "").substring(0, 26),
                getTestTenant().getId(), code,
                getTestUser().getPid().substring(0, 26), getTestUser().getPid().substring(0, 26));

        DashboardDTO composed = dashboardService.getOrCreateWorkbench();

        assertThat(composed.getWidgets())
                .noneSatisfy(widget -> assertThat(widget.path("type").asText())
                        .isIn("StatsRowWidget", "InboxWidget", "ShortcutsWidget", "RecentWidget"));
        assertThat(composed.getWidgets())
                .anySatisfy(widget -> assertThat(widget.path("id").asText()).isEqualTo(expectedWidgetId));

        Boolean persistedContribution = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM ab_dashboard d,
                         jsonb_array_elements(d.widgets) widget
                    WHERE d.tenant_id = ?
                      AND d.scope = 'workbench'
                      AND d.owner_id = ?
                      AND d.deleted_flag = FALSE
                      AND widget ->> 'id' = ?
                )
                """, Boolean.class, getTestTenant().getId(), getTestUser().getPid(), expectedWidgetId);
        assertThat(persistedContribution).isFalse();
    }
}

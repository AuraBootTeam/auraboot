package com.auraboot.framework.aurabot.skill.builtin;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.dashboard.dto.DashboardDTO;
import com.auraboot.framework.dashboard.service.DashboardService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5 — deterministic real-stack IT for {@link DashboardGeneratorSkill}: the execute path turns a
 * model-shaped widget spec (type + title + data source) into a persisted dashboard with the widgets
 * auto-laid-out on the 12-column grid, persisted via {@code DashboardService.create} and re-readable.
 *
 * <p>This pins the persistence + layout contract (no LLM); the generation quality is measured live in
 * {@code DashboardGenerationLiveIT}.
 */
@Slf4j
@DisplayName("S5: dashboard:create skill → auto-layout widgets → persisted dashboard")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DashboardGeneratorSkillIT extends BaseIntegrationTest {

    @Autowired private DashboardGeneratorSkill skill;
    @Autowired private DashboardService dashboardService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private final String code = "salesdash_" + suffix;
    private String createdPid;

    @BeforeEach
    void ctx() {
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
    }

    @AfterAll
    void cleanup() {
        try {
            if (createdPid != null) dashboardService.delete(createdPid);
            jdbcTemplate.update("DELETE FROM ab_dashboard WHERE code = ?", code);
        } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("execute persists a dashboard whose widgets are auto-laid-out on the grid")
    void execute_buildsAndPersistsDashboard_withAutoLayout() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("code", code);
        params.put("title", "Sales Overview");
        params.put("description", "auto-generated");
        ArrayNode ws = params.putArray("widgets");
        ws.addObject().put("type", "smart-number-card").put("title", "Total Sales")
                .put("dataSourceType", "namedQuery").put("dataSourceCode", "total_sales");
        ws.addObject().put("type", "smart-bar-chart").put("title", "Sales by Region")
                .put("dataSourceType", "model").put("dataSourceCode", "sales_order")
                .put("dimension", "region").put("metricField", "amount").put("aggregation", "sum");
        ws.addObject().put("type", "smart-table-chart").put("title", "Top Orders")
                .put("dataSourceType", "model").put("dataSourceCode", "sales_order");

        SkillResult res = skill.execute(SkillRequest.builder().skillName("dashboard:create").params(params).build());

        assertThat(res.getStatus()).as("skill must succeed").isEqualTo(SkillResult.Status.SUCCESS);
        JsonNode payload = objectMapper.valueToTree(res.getPayload());
        assertThat(payload.get("widgetCount").asInt()).isEqualTo(3);
        createdPid = payload.get("dashboardPid").asText();

        DashboardDTO dto = dashboardService.findByCode(code);
        assertThat(dto).as("dashboard must be persisted + re-readable by code").isNotNull();
        assertThat(dto.getTitle()).isEqualTo("Sales Overview");

        JsonNode widgets = dto.getWidgets();
        assertThat(widgets.isArray()).isTrue();
        assertThat(widgets).hasSize(3);

        // every widget got a full grid placement + the data source carried through
        for (JsonNode w : widgets) {
            assertThat(w.hasNonNull("id")).as("widget id").isTrue();
            assertThat(w.hasNonNull("type")).isTrue();
            assertThat(w.has("x") && w.has("y") && w.has("w") && w.has("h")).as("grid placement").isTrue();
        }
        // number-card (w=3, x=0) | bar-chart (w=6, x=3) on row 0; table (w=12) wraps to row 1 (y=4, x=0)
        assertThat(widgets.get(0).get("type").asText()).isEqualTo("smart-number-card");
        assertThat(widgets.get(0).get("w").asInt()).isEqualTo(3);
        assertThat(widgets.get(0).get("x").asInt()).isEqualTo(0);
        assertThat(widgets.get(1).get("type").asText()).isEqualTo("smart-bar-chart");
        assertThat(widgets.get(1).get("w").asInt()).isEqualTo(6);
        assertThat(widgets.get(1).get("x").asInt()).isEqualTo(3);
        assertThat(widgets.get(1).get("y").asInt()).isEqualTo(0);
        assertThat(widgets.get(2).get("type").asText()).isEqualTo("smart-table-chart");
        assertThat(widgets.get(2).get("w").asInt()).isEqualTo(12);
        assertThat(widgets.get(2).get("y").asInt()).as("full-width table wraps to a new row").isEqualTo(4);
        // Data source emitted in the ChartDataSource shape the widget renderers
        // read (web-admin .../hooks/useChartData.ts) — NOT the old {type, code}
        // shape that rendered empty charts. namedQuery -> queryCode.
        JsonNode numberCardDs = widgets.get(0).get("config").get("dataSource");
        assertThat(numberCardDs.get("type").asText()).isEqualTo("namedQuery");
        assertThat(numberCardDs.get("queryCode").asText()).isEqualTo("total_sales");
        assertThat(numberCardDs.has("code")).as("must not emit the dead {code} key").isFalse();

        // model chart -> aggregate(modelCode + dimensions + metrics) — a complete,
        // renderable aggregate query (isDataSourceComplete: modelCode + metrics).
        JsonNode barDs = widgets.get(1).get("config").get("dataSource");
        assertThat(barDs.get("type").asText()).isEqualTo("aggregate");
        assertThat(barDs.get("modelCode").asText()).isEqualTo("sales_order");
        assertThat(barDs.get("dimensions").get(0).asText()).isEqualTo("region");
        JsonNode barMetric = barDs.get("metrics").get(0);
        assertThat(barMetric.get("field").asText()).isEqualTo("amount");
        assertThat(barMetric.get("aggregation").asText()).isEqualTo("sum");
        assertThat(barMetric.get("alias").asText()).isEqualTo("value");

        // model widget without an explicit metric still gets a count metric so the
        // aggregate query is complete (renders a value rather than "No data yet").
        JsonNode tableDs = widgets.get(2).get("config").get("dataSource");
        assertThat(tableDs.get("type").asText()).isEqualTo("aggregate");
        assertThat(tableDs.get("modelCode").asText()).isEqualTo("sales_order");
        assertThat(tableDs.get("metrics").get(0).get("aggregation").asText()).isEqualTo("count");

        log.info("[S5 dashboard skill] PASS — dashboard {} persisted with {} auto-laid widgets", code, widgets.size());
    }
}

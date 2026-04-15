package com.auraboot.framework.dashboard.migration;

import com.auraboot.framework.dashboard.entity.Dashboard;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for BlockToDashboardConverter.
 * Pure unit tests — no Spring context, no DB.
 */
class BlockToDashboardConverterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------ helpers

    private PageSchemaDTO buildPage(String pageKey, Map<String, Object> title, List<Map<String, Object>> blocks) {
        PageSchemaDTO dto = new PageSchemaDTO();
        dto.setPageKey(pageKey);
        dto.setKind("dashboard");
        dto.setTitle(title);
        // blocks field is List<Object>
        dto.setBlocks(blocks == null ? null : List.copyOf(blocks));
        return dto;
    }

    private PageSchemaDTO buildPage(String pageKey, List<Map<String, Object>> blocks) {
        Map<String, Object> title = new HashMap<>();
        title.put("en", "Test Dashboard");
        return buildPage(pageKey, title, blocks);
    }

    /** Parse Dashboard.widgets (JsonNode) into a JsonNode array for easy assertion. */
    private JsonNode parseWidgets(Dashboard d) {
        return d.getWidgets();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void convert_simpleChartBlock_mapsToBarChartWidget() {
        Map<String, Object> block = new HashMap<>();
        block.put("id", "block1");
        block.put("blockType", "chart");
        block.put("colSpan", 6);
        block.put("rowSpan", 3);
        block.put("chartType", "bar");

        PageSchemaDTO page = buildPage("sales_dashboard", List.of(block));
        Dashboard dashboard = BlockToDashboardConverter.convert(page);

        JsonNode widgets = parseWidgets(dashboard);
        assertThat(widgets).isNotNull();
        assertThat(widgets.isArray()).isTrue();
        assertThat(widgets.size()).isEqualTo(1);

        JsonNode w = widgets.get(0);
        assertThat(w.get("type").asText()).isEqualTo("smart-bar-chart");
        assertThat(w.get("w").asInt()).isEqualTo(6);
        assertThat(w.get("h").asInt()).isEqualTo(3);
        assertThat(w.get("x").asInt()).isEqualTo(0);
        assertThat(w.get("y").asInt()).isEqualTo(0);
    }

    @Test
    void convert_multipleBlocks_derivesYCoordinateByOrder() {
        Map<String, Object> block1 = new HashMap<>();
        block1.put("id", "b1");
        block1.put("blockType", "chart");
        block1.put("rowSpan", 2);
        block1.put("chartType", "line");

        Map<String, Object> block2 = new HashMap<>();
        block2.put("id", "b2");
        block2.put("blockType", "stat-card");
        block2.put("rowSpan", 3);

        PageSchemaDTO page = buildPage("multi_dashboard", List.of(block1, block2));
        Dashboard dashboard = BlockToDashboardConverter.convert(page);

        JsonNode widgets = parseWidgets(dashboard);
        assertThat(widgets.size()).isEqualTo(2);

        JsonNode w1 = widgets.get(0);
        assertThat(w1.get("y").asInt()).isEqualTo(0);
        assertThat(w1.get("h").asInt()).isEqualTo(2);

        JsonNode w2 = widgets.get(1);
        assertThat(w2.get("y").asInt()).isEqualTo(2);
        assertThat(w2.get("h").asInt()).isEqualTo(3);
    }

    @Test
    void convert_statCardBlock_mapsToNumberCardWidget() {
        Map<String, Object> block = new HashMap<>();
        block.put("id", "sc1");
        block.put("blockType", "stat-card");
        block.put("colSpan", 4);
        block.put("rowSpan", 1);

        PageSchemaDTO page = buildPage("kpi_dashboard", List.of(block));
        Dashboard dashboard = BlockToDashboardConverter.convert(page);

        JsonNode w = parseWidgets(dashboard).get(0);
        assertThat(w.get("type").asText()).isEqualTo("smart-number-card");
        assertThat(w.get("w").asInt()).isEqualTo(4);
    }

    @Test
    void convert_tableBlock_mapsToTableWidget() {
        Map<String, Object> block = new HashMap<>();
        block.put("id", "tbl1");
        block.put("blockType", "table");
        block.put("colSpan", 12);
        block.put("rowSpan", 4);

        PageSchemaDTO page = buildPage("report_dashboard", List.of(block));
        Dashboard dashboard = BlockToDashboardConverter.convert(page);

        JsonNode w = parseWidgets(dashboard).get(0);
        assertThat(w.get("type").asText()).isEqualTo("smart-table-chart");
        assertThat(w.get("w").asInt()).isEqualTo(12);
        assertThat(w.get("h").asInt()).isEqualTo(4);
    }

    @Test
    void convert_unknownBlockType_logsAndIncludesPlaceholder() {
        Map<String, Object> block = new HashMap<>();
        block.put("id", "unk1");
        block.put("blockType", "whatever");
        block.put("colSpan", 6);
        block.put("rowSpan", 2);

        PageSchemaDTO page = buildPage("misc_dashboard", List.of(block));
        Dashboard dashboard = BlockToDashboardConverter.convert(page);

        JsonNode widgets = parseWidgets(dashboard);
        assertThat(widgets.size()).isEqualTo(1);
        assertThat(widgets.get(0).get("type").asText()).isEqualTo("smart-unknown");
    }

    @Test
    void convert_preservesCodeAndTitle() {
        Map<String, Object> title = new HashMap<>();
        title.put("zh-CN", "CRM 看板");
        title.put("en", "CRM Dashboard");

        Map<String, Object> block = new HashMap<>();
        block.put("id", "b1");
        block.put("blockType", "stat-card");

        PageSchemaDTO page = buildPage("crm_dashboard", title, List.of(block));
        Dashboard dashboard = BlockToDashboardConverter.convert(page);

        assertThat(dashboard.getCode()).isEqualTo("crm_dashboard");
        // Title resolution: prefer zh-CN locale key
        assertThat(dashboard.getTitle()).isEqualTo("CRM 看板");
    }
}

package com.auraboot.framework.aurabot.skill.builtin;

import com.auraboot.framework.aurabot.skill.AuraBotSkill;
import com.auraboot.framework.aurabot.skill.RiskLevel;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.aurabot.skill.error.SkillErrorCode;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.dashboard.dto.DashboardCreateRequest;
import com.auraboot.framework.dashboard.dto.DashboardDTO;
import com.auraboot.framework.dashboard.service.DashboardService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Built-in {@code dashboard:create} skill — schema-constrained NL → dashboard generation (S5).
 *
 * <p>Closes the platform gap "generate a dashboard from natural language": ChatBI only produced a
 * single ephemeral ad-hoc chart intent; there was no agent path to assemble + persist a multi-widget
 * dashboard. This skill's {@link #paramsSchema()} is the native-tool-use contract the model fills
 * (one entry per widget: type + title + optional data source); {@link #execute(SkillRequest)} turns
 * that into a valid dashboard DSL — auto-laying widgets out on the 12-column grid (so the model need
 * not reason about pixel geometry) — and persists it via {@link DashboardService#create}.
 *
 * <p>The widget {@code type} enum mirrors {@code plugins/schemas/dashboards.schema.json}, so anything
 * the model can emit is a renderable widget kind. Reversibility/UI follow-ups are out of scope here;
 * the value is the generation + persistence path that did not exist.
 */
@Slf4j
@Component
public class DashboardGeneratorSkill implements AuraBotSkill {

    /** Widget kinds renderable by the platform (mirrors dashboards.schema.json widget type examples). */
    private static final Set<String> WIDGET_TYPES = Set.of(
            "smart-bar-chart", "smart-line-chart", "smart-pie-chart",
            "smart-number-card", "smart-table-chart", "smart-rich-text");

    private static final String SCHEMA_JSON = "{"
            + "\"type\":\"object\",\"additionalProperties\":false,"
            + "\"properties\":{"
            + "  \"code\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9_-]*$\",\"minLength\":2,\"maxLength\":64,"
            + "    \"description\":\"Unique dashboard code, lower-kebab/snake.\"},"
            + "  \"title\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":200},"
            + "  \"description\":{\"type\":\"string\",\"maxLength\":500},"
            + "  \"widgets\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":12,\"items\":{"
            + "    \"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
            + "      \"type\":{\"type\":\"string\",\"enum\":[\"smart-bar-chart\",\"smart-line-chart\",\"smart-pie-chart\",\"smart-number-card\",\"smart-table-chart\",\"smart-rich-text\"],"
            + "        \"description\":\"Widget kind. Use number-card for a single KPI, bar/line/pie for aggregations, table for row lists, rich-text for notes.\"},"
            + "      \"title\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":100},"
            + "      \"dataSourceType\":{\"type\":\"string\",\"enum\":[\"namedQuery\",\"model\",\"static\"],"
            + "        \"description\":\"Where the widget's data comes from (omit for rich-text).\"},"
            + "      \"dataSourceCode\":{\"type\":\"string\",\"description\":\"The named-query code or model code to read.\"},"
            + "      \"dimension\":{\"type\":\"string\",\"description\":\"For bar/line/pie charts over a model: the field to group by (x-axis / pie slices).\"},"
            + "      \"metricField\":{\"type\":\"string\",\"description\":\"The numeric field to aggregate for a model chart/number-card (defaults to a row count).\"},"
            + "      \"aggregation\":{\"type\":\"string\",\"enum\":[\"count\",\"sum\",\"avg\",\"min\",\"max\"],\"description\":\"Aggregation for the model metric (default count).\"}"
            + "    },\"required\":[\"type\",\"title\"]}}"
            + "},"
            + "\"required\":[\"code\",\"title\",\"widgets\"]"
            + "}";

    private static final int GRID_COLUMNS = 12;
    private static final int ROW_HEIGHT_UNITS = 4;

    private final DashboardService dashboardService;
    private final ObjectMapper objectMapper;

    private JsonNode schema;

    public DashboardGeneratorSkill(DashboardService dashboardService, ObjectMapper objectMapper) {
        this.dashboardService = dashboardService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() throws Exception {
        this.schema = objectMapper.readTree(SCHEMA_JSON);
    }

    @Override
    public String name() {
        return "dashboard:create";
    }

    @Override
    public String displayName() {
        return "aurabot.skill.dashboard.create.displayName";
    }

    @Override
    public String category() {
        return "dashboard";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.MEDIUM;
    }

    @Override
    public JsonNode paramsSchema() {
        return schema;
    }

    @Override
    public SkillResult execute(SkillRequest req) {
        if (req.getParams() == null) {
            throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID, "params is required", "/");
        }
        JsonNode params = req.getParams();
        String code = text(params, "code");
        String title = text(params, "title");
        JsonNode widgetSpecs = params.get("widgets");
        if (code == null || code.isBlank() || title == null || title.isBlank()
                || widgetSpecs == null || !widgetSpecs.isArray() || widgetSpecs.isEmpty()) {
            throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID,
                    "code, title and a non-empty widgets array are required", "/widgets");
        }

        ArrayNode widgets = buildWidgets(code, widgetSpecs);

        DashboardCreateRequest request = new DashboardCreateRequest();
        request.setCode(code);
        request.setTitle(title);
        request.setDescription(text(params, "description"));
        // Scope values are lowercase per the DB check constraint chk_dashboard_scope
        // (global|personal|team) — the DTO javadoc's uppercase is stale.
        request.setScope("personal");
        request.setWidgets(widgets);
        request.setLayoutConfig(objectMapper.createObjectNode()
                .put("columns", GRID_COLUMNS).put("rowHeight", 100).put("gap", 16));

        DashboardDTO created;
        try {
            created = dashboardService.create(request);
        } catch (RuntimeException e) {
            log.error("dashboard:create failed for code={}", code, e);
            throw new SkillSpiException(SkillErrorCode.SKILL_INTERNAL_ERROR,
                    "failed to create dashboard: " + e.getMessage(), null, e);
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("dashboardPid", created.getPid());
        payload.put("dashboardCode", created.getCode());
        payload.put("title", created.getTitle());
        payload.put("widgetCount", widgets.size());
        return SkillResult.builder()
                .status(SkillResult.Status.SUCCESS)
                .skillName(name())
                .payload(payload)
                .riskLevel(riskLevel())
                .build();
    }

    /**
     * Turn the model-supplied widget specs (type + title + optional data source) into the full
     * dashboards.schema.json widget DSL, auto-laying them out on the 12-column grid: number cards span
     * 3 cols, tables and rich-text span the full row, other charts span 6; rows wrap automatically so
     * the model never has to reason about x/y/w/h.
     */
    private ArrayNode buildWidgets(String code, JsonNode widgetSpecs) {
        ArrayNode widgets = objectMapper.createArrayNode();
        int col = 0;
        int row = 0;
        int idx = 0;
        for (JsonNode spec : widgetSpecs) {
            String type = text(spec, "type");
            if (type == null || !WIDGET_TYPES.contains(type)) {
                throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID,
                        "unsupported widget type: " + type, "/widgets");
            }
            int width = widthFor(type);
            if (col + width > GRID_COLUMNS) {
                col = 0;
                row += ROW_HEIGHT_UNITS;
            }

            ObjectNode w = objectMapper.createObjectNode();
            w.put("id", code + "-w" + (idx++));
            w.put("type", type);
            w.put("x", col);
            w.put("y", row);
            w.put("w", width);
            w.put("h", ROW_HEIGHT_UNITS);
            String wt = text(spec, "title");
            if (wt != null) {
                w.put("title", wt);
            }
            ObjectNode config = w.putObject("config");
            if (wt != null) {
                config.put("title", wt);
            }
            String dsType = text(spec, "dataSourceType");
            String dsCode = text(spec, "dataSourceCode");
            if (dsType != null && !dsType.isBlank()) {
                // Emit the ChartDataSource shape the widget renderers actually read
                // (web-admin .../hooks/useChartData.ts): namedQuery -> queryCode,
                // model -> aggregate(modelCode + metrics[, dimensions]). The earlier
                // {type, code} shape never satisfied isDataSourceComplete(), so a
                // generated dashboard rendered empty charts ("No data yet").
                ObjectNode ds = config.putObject("dataSource");
                if ("namedQuery".equals(dsType)) {
                    ds.put("type", "namedQuery");
                    if (dsCode != null && !dsCode.isBlank()) {
                        ds.put("queryCode", dsCode);
                    }
                } else if ("model".equals(dsType)) {
                    ds.put("type", "aggregate");
                    if (dsCode != null && !dsCode.isBlank()) {
                        ds.put("modelCode", dsCode);
                    }
                    String dimension = text(spec, "dimension");
                    if (dimension != null && !dimension.isBlank()) {
                        ds.putArray("dimensions").add(dimension);
                    }
                    ObjectNode metric = ds.putArray("metrics").addObject();
                    String metricField = text(spec, "metricField");
                    String aggregation = text(spec, "aggregation");
                    metric.put("field", metricField != null && !metricField.isBlank() ? metricField : "pid");
                    metric.put("aggregation", aggregation != null && !aggregation.isBlank() ? aggregation : "count");
                    metric.put("alias", "value");
                } else {
                    // static (or any future kind) — carry through verbatim.
                    ds.put("type", dsType);
                    if (dsCode != null && !dsCode.isBlank()) {
                        ds.put("code", dsCode);
                    }
                }
            }
            widgets.add(w);

            col += width;
            if (col >= GRID_COLUMNS) {
                col = 0;
                row += ROW_HEIGHT_UNITS;
            }
        }
        return widgets;
    }

    private int widthFor(String type) {
        return switch (type) {
            case "smart-number-card" -> 3;
            case "smart-table-chart", "smart-rich-text" -> GRID_COLUMNS;
            default -> 6;
        };
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    @Override
    public Set<String> requiredPermissions() {
        // Dashboard creation is governed by DashboardService scope rules; no extra meta permission.
        return Set.of();
    }
}

package com.auraboot.framework.aurabot.skill.builtin;

import com.auraboot.framework.aurabot.skill.AuraBotSkill;
import com.auraboot.framework.aurabot.skill.RiskLevel;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.aurabot.skill.error.SkillErrorCode;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.dto.AggregateQueryResponse;
import com.auraboot.framework.meta.dto.MetricConfig;
import com.auraboot.framework.meta.service.AggregateQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Built-in {@code chat_bi} skill — governed, zero-setup NL → ad-hoc chart inside AuraBot
 * (convergence endgame: docs/backlog/2026-06-18-aurabot-conversational-viz-convergence-endgame.md).
 *
 * <p>This is the agent-tool form of "ask a data question, get a chart". The model fills the
 * {@link #paramsSchema()} (modelCode + dimensions + metrics + filters) from the user's natural
 * language via native tool-use; {@link #execute(SkillRequest)} runs it through the shared
 * {@link AggregateQueryService} <strong>raw aggregate path</strong> (NO semantic model required —
 * the same backbone the dashboard chart widgets use) and returns a {@code {records, columns,
 * chartType}} payload that the AuraBot chat renders inline as an ECharts card (ChatBiResultCard).
 *
 * <p>Why a tool and not the old {@code /api/ai/chat-bi} API: routing through the agent runtime
 * means the read is governed by the same gates as every other tool (RuntimeAuth / ACL / trace /
 * tenant scope), instead of a direct ungoverned NL→SQL endpoint. The semantic-layer
 * ({@code chatbi/v2}) remains the advanced path for governed metrics / multi-turn; this baseline
 * works zero-setup over any published model.
 */
@Slf4j
@Component
public class ChatBiSkill implements AuraBotSkill {

    /** Aggregations accepted by AggregateQueryService (mirrors its impl whitelist). */
    private static final Set<String> AGGREGATIONS =
            Set.of("count", "count_distinct", "sum", "avg", "max", "min");

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    private static final String SCHEMA_JSON = "{"
            + "\"type\":\"object\",\"additionalProperties\":false,"
            + "\"properties\":{"
            + "  \"modelCode\":{\"type\":\"string\",\"description\":\"The model to aggregate (e.g. crm_lead).\"},"
            + "  \"dimensions\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
            + "    \"description\":\"Group-by fields (category axis / pie slices). Omit for a single KPI.\"},"
            + "  \"metrics\":{\"type\":\"array\",\"minItems\":1,\"items\":{"
            + "    \"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
            + "      \"field\":{\"type\":\"string\",\"description\":\"Field to aggregate (use the primary key for a row count).\"},"
            + "      \"aggregation\":{\"type\":\"string\",\"enum\":[\"count\",\"count_distinct\",\"sum\",\"avg\",\"max\",\"min\"]},"
            + "      \"alias\":{\"type\":\"string\",\"description\":\"Output column name for this metric.\"}"
            + "    },\"required\":[\"field\",\"aggregation\"]}},"
            + "  \"filters\":{\"type\":\"array\",\"items\":{"
            + "    \"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
            + "      \"field\":{\"type\":\"string\"},"
            + "      \"operator\":{\"type\":\"string\",\"description\":\"EQ / NE / IN / GT / LT / GTE / LTE / LIKE.\"},"
            + "      \"value\":{}"
            + "    },\"required\":[\"field\",\"operator\"]}},"
            + "  \"chartType\":{\"type\":\"string\",\"enum\":[\"bar\",\"line\",\"pie\",\"table\"],"
            + "    \"description\":\"How to visualize the result. Defaults to bar (or a number when there is no dimension).\"},"
            + "  \"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000},"
            + "  \"interpretation\":{\"type\":\"string\",\"description\":\"One-line restatement of what was asked, for the result header.\"}"
            + "},"
            + "\"required\":[\"modelCode\",\"metrics\"]"
            + "}";

    private final AggregateQueryService aggregateQueryService;
    private final ObjectMapper objectMapper;

    private JsonNode schema;

    public ChatBiSkill(AggregateQueryService aggregateQueryService, ObjectMapper objectMapper) {
        this.aggregateQueryService = aggregateQueryService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() throws Exception {
        this.schema = objectMapper.readTree(SCHEMA_JSON);
    }

    @Override
    public String name() {
        // Must match AuraBotSkillRegistry.NAME_PATTERN (^[a-z][a-z0-9-]*(:[a-z][a-z0-9-]*)?$) —
        // no underscores; hyphen form mirrors the historical /api/ai/chat-bi naming.
        return "chat-bi";
    }

    @Override
    public String displayName() {
        return "aurabot.skill.chat-bi.displayName";
    }

    @Override
    public String category() {
        return "analytics";
    }

    @Override
    public RiskLevel riskLevel() {
        // Read-only aggregate over the caller's tenant — no writes, no side effects.
        return RiskLevel.LOW;
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
        String modelCode = text(params, "modelCode");
        if (modelCode == null || modelCode.isBlank()) {
            throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID, "modelCode is required", "/modelCode");
        }

        AggregateQueryRequest request;
        try {
            // Lenient: the skill schema carries presentation-only fields (chartType,
            // interpretation) that are not on AggregateQueryRequest — ignore unknowns.
            request = objectMapper.readerFor(AggregateQueryRequest.class)
                    .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(params);
        } catch (Exception e) {
            throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID,
                    "invalid chat_bi params: " + e.getMessage(), "/");
        }

        // Force the zero-setup raw aggregate path (governed by tenant scope); never let the model
        // smuggle in a semantic-model / named-query code through this tool.
        request.setType("aggregate");
        request.setSemanticModelCode(null);
        request.setQueryCode(null);

        List<MetricConfig> metrics = request.getMetrics();
        if (metrics == null || metrics.isEmpty()) {
            throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID,
                    "at least one metric is required", "/metrics");
        }
        for (MetricConfig m : metrics) {
            String agg = m.getAggregation() == null ? null : m.getAggregation().toLowerCase();
            if (agg == null || !AGGREGATIONS.contains(agg)) {
                throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID,
                        "unsupported aggregation: " + m.getAggregation(), "/metrics");
            }
            m.setAggregation(agg);
        }
        if (request.getLimit() == null || request.getLimit() <= 0 || request.getLimit() > MAX_LIMIT) {
            request.setLimit(DEFAULT_LIMIT);
        }

        AggregateQueryResponse response;
        try {
            response = aggregateQueryService.execute(request);
        } catch (RuntimeException e) {
            log.error("chat_bi aggregate failed for model={}", modelCode, e);
            throw new SkillSpiException(SkillErrorCode.SKILL_INTERNAL_ERROR,
                    "failed to run aggregate query: " + e.getMessage(), null, e);
        }

        List<Map<String, Object>> rows = response.getRows() != null ? response.getRows() : List.of();

        // columns = dimensions + each metric's output name (alias, else field) — drives the
        // ChatBiResultCard label/value auto-detection.
        ArrayNode columns = objectMapper.createArrayNode();
        if (request.getDimensions() != null) {
            request.getDimensions().forEach(columns::add);
        }
        for (MetricConfig m : metrics) {
            columns.add(m.getAlias() != null && !m.getAlias().isBlank() ? m.getAlias() : m.getField());
        }

        String chartType = text(params, "chartType");
        if (chartType == null || chartType.isBlank()) {
            chartType = (request.getDimensions() == null || request.getDimensions().isEmpty())
                    ? "number" : "bar";
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("modelCode", modelCode);
        payload.put("chartType", chartType);
        payload.set("columns", columns);
        payload.set("records", objectMapper.valueToTree(rows));
        payload.put("rowCount", rows.size());
        String interpretation = text(params, "interpretation");
        if (interpretation != null && !interpretation.isBlank()) {
            payload.put("interpretation", interpretation);
        }

        return SkillResult.builder()
                .status(SkillResult.Status.SUCCESS)
                .skillName(name())
                .payload(payload)
                .riskLevel(riskLevel())
                .build();
    }

    @Override
    public Set<String> requiredPermissions() {
        // Read governed by the agent runtime gates + per-tenant aggregate scoping.
        return Set.of();
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}

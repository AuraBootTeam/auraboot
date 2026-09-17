package com.auraboot.framework.aurabot.skill.builtin;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.service.AnalyticsJourneyService;
import com.auraboot.framework.aurabot.skill.*;
import com.auraboot.framework.aurabot.skill.error.SkillErrorCode;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.dto.AggregateQueryResponse;
import com.auraboot.framework.meta.dto.MetricConfig;
import com.auraboot.framework.meta.service.AggregateQueryService;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import com.auraboot.framework.semantic.service.SemanticCatalogService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Structured analytics within the existing AuraBot conversation and tool governance. */
@Component
public class ChatBiSkill implements AuraBotSkill {
    private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();
    private static final String SCHEMA_JSON = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "action": {
      "type": "string",
      "enum": [
        "catalog",
        "query"
      ],
      "description": "Discover accessible governed metrics first; query only known codes. Default query."
    },
    "modelCode": {
      "type": "string",
      "description": "Published business model for raw exploration; supplied from catalog for semantic queries."
    },
    "semanticModelCode": {
      "type": "string",
      "description": "Governed semantic model from catalog. Prefer governed metrics for business KPIs."
    },
    "dimensions": {
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "metrics": {
      "type": "array",
      "minItems": 1,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "properties": {
          "field": {
            "type": "string",
            "description": "Catalog metric code in semantic mode; physical model field for raw mode."
          },
          "aggregation": {
            "type": "string",
            "enum": [
              "count",
              "count_distinct",
              "sum",
              "avg",
              "max",
              "min"
            ]
          },
          "alias": {
            "type": "string"
          }
        },
        "required": [
          "field",
          "aggregation"
        ]
      }
    },
    "filters": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "properties": {
          "field": {
            "type": "string"
          },
          "operator": {
            "type": "string",
            "enum": [
              "eq",
              "ne",
              "gt",
              "gte",
              "lt",
              "lte",
              "in",
              "not_in",
              "like"
            ]
          },
          "value": {}
        },
        "required": [
          "field",
          "operator",
          "value"
        ]
      }
    },
    "orderBy": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "properties": {
          "field": {
            "type": "string"
          },
          "direction": {
            "type": "string",
            "enum": [
              "asc",
              "desc"
            ]
          }
        },
        "required": [
          "field",
          "direction"
        ]
      }
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 1000
    },
    "timeRange": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "field": {
          "type": "string"
        },
        "preset": {
          "type": "string",
          "enum": [
            "ytd",
            "mtd",
            "qtd",
            "last_7_days",
            "last_30_days",
            "last_month",
            "custom"
          ]
        },
        "from": {
          "type": "string"
        },
        "to": {
          "type": "string"
        }
      },
      "required": [
        "field",
        "preset"
      ]
    },
    "chartType": {
      "type": "string",
      "enum": [
        "bar",
        "line",
        "pie",
        "table"
      ]
    },
    "interpretation": {
      "type": "string",
      "description": "Restate the question; do not invent conclusions."
    }
  }
}
        """;
    private static final Set<String> AGGREGATIONS =
            Set.of("count", "count_distinct", "sum", "avg", "max", "min");
    private final AggregateQueryService queries;
    private final ObjectMapper mapper;
    private final SemanticCatalogService catalog;
    private final UserPermissionService permissions;

    private final AnalyticsJourneyService journey;

    public ChatBiSkill(AggregateQueryService queries, ObjectMapper mapper,
                       SemanticCatalogService catalog, UserPermissionService permissions, AnalyticsJourneyService journey) {
        this.queries = queries;
        this.mapper = mapper;
        this.catalog = catalog;
        this.permissions = permissions;
        this.journey = journey;
    }

    /** One contract for native tool exposure and skill validation. */
    public static Map<String, Object> inputSchema() {
        try {
            return SCHEMA_MAPPER.readValue(SCHEMA_JSON, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid analytics tool schema", e);
        }
    }

    @Override public String name() { return "chat-bi"; }
    @Override public String displayName() { return "aurabot.skill.chat-bi.displayName"; }
    @Override public String category() { return "analytics"; }
    @Override public RiskLevel riskLevel() { return RiskLevel.LOW; }
    @Override public JsonNode paramsSchema() { return mapper.valueToTree(inputSchema()); }
    @Override public Set<String> requiredPermissions() { return Set.of(); }

    @Override
    public SkillResult execute(SkillRequest req) {
        JsonNode params = req.getParams();
        if (params == null || !params.isObject()) throw invalid("params must be an object");
        // Reject unknown input even when invoked outside the usual schema validator.
        params.fieldNames().forEachRemaining(key -> {
            if (!paramsSchema().path("properties").has(key)) throw invalid("Unknown analytics parameter: " + key);
        });
        String action = params.path("action").asText("query");
        if ("catalog".equals(action)) return success(mapper.valueToTree(accessibleCatalog()));
        if (!"query".equals(action)) throw invalid("Unknown analytics action");

        String analysisId = journey.requested();
        SkillResult result;
        try {
            result = executeQuery(params);
        } catch (RuntimeException error) {
            journey.failed(analysisId);
            throw error;
        }
        ((ObjectNode) result.getPayload()).put("analysisId", analysisId);
        journey.succeeded(analysisId, ((ObjectNode) result.getPayload()).path("rowCount").asInt(),
                ((ObjectNode) result.getPayload()).path("dataSource"));
        return result;
    }

    private SkillResult executeQuery(JsonNode params) {
        ObjectNode queryJson = ((ObjectNode) params).deepCopy();
        queryJson.remove(List.of("action", "chartType", "interpretation"));
        AggregateQueryRequest query;
        try {
            query = mapper.treeToValue(queryJson, AggregateQueryRequest.class);
        } catch (JsonProcessingException e) {
            throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID, "Invalid analytics query", "/", e);
        }
        query.setType("aggregate");
        boolean semantic = query.getSemanticModelCode() != null && !query.getSemanticModelCode().isBlank();
        if (semantic) {
            SemanticMetaResponse.ModelMeta model = accessibleCatalog().getModels().stream()
                    .filter(m -> m.getCode().equals(query.getSemanticModelCode())).findFirst()
                    .orElseThrow(() -> invalid("Semantic model is unavailable in the accessible catalog"));
            query.setModelCode(model.getModelRef());
            if (query.getMetrics() != null) for (MetricConfig metric : query.getMetrics()) {
                if (metric == null || metric.getField() == null || model.getMetrics().stream()
                        .noneMatch(m -> m.getCode().equals(metric.getField()))) {
                    throw invalid("Metric is unavailable in the accessible catalog");
                }
                // Catalog identity is authoritative; presentation aliases must not select another metric.
                if (metric.getAlias() != null && !metric.getAlias().equals(metric.getField())) {
                    throw invalid("Semantic metric aliases must match catalog codes");
                }
            }
        } else if (query.getTimeRange() != null) {
            throw invalid("timeRange requires a semantic model");
        }
        if (query.getModelCode() == null || query.getModelCode().isBlank()) throw invalid("modelCode is required");
        if (query.getMetrics() == null || query.getMetrics().isEmpty()) throw invalid("At least one metric is required");
        for (MetricConfig metric : query.getMetrics()) {
            if (metric == null || metric.getField() == null || metric.getField().isBlank()
                    || metric.getAggregation() == null
                    || !AGGREGATIONS.contains(metric.getAggregation().toLowerCase(Locale.ROOT))) {
                throw invalid("Invalid metric aggregation");
            }
            metric.setAggregation(metric.getAggregation().toLowerCase(Locale.ROOT));
        }
        if (query.getLimit() == null) query.setLimit(100);
        if (query.getLimit() < 1 || query.getLimit() > 1000) throw invalid("limit must be between 1 and 1000");
        AggregateQueryResponse response = queries.execute(query);
        List<Map<String, Object>> rows = response.getRows();
        if (rows == null) throw new IllegalStateException("Analytics query returned no result envelope");
        ObjectNode payload = mapper.createObjectNode();
        payload.put("modelCode", query.getModelCode());
        payload.put("chartType", params.path("chartType").asText(
                query.getDimensions() == null || query.getDimensions().isEmpty() ? "number" : "bar"));
        payload.set("records", mapper.valueToTree(rows));
        payload.set("columns", mapper.valueToTree(rows.isEmpty() ? List.of() : rows.get(0).keySet()));
        payload.put("rowCount", rows.size());
        payload.put("total", rows.size());
        payload.set("dataSource", mapper.valueToTree(query));
        payload.set("dimensions", mapper.valueToTree(query.getDimensions()));
        payload.set("metrics", mapper.valueToTree(query.getMetrics()));
        if (params.hasNonNull("interpretation")) payload.put("interpretation", params.get("interpretation").asText());
        return success(payload);
    }

    private SemanticMetaResponse accessibleCatalog() {
        Long tenant = MetaContext.getCurrentTenantId();
        Long user = MetaContext.getCurrentUserId();
        if (tenant == null || user == null || !permissions.hasPermission(user, MetaPermission.META_SEMANTIC_USE)) {
            throw new SkillSpiException(SkillErrorCode.PERMISSION_DENIED, "Semantic analytics access denied", "/");
        }
        SemanticMetaResponse result = mapper.convertValue(catalog.listCatalog(tenant), SemanticMetaResponse.class);
        for (SemanticMetaResponse.ModelMeta model : result.getModels()) {
            model.setMetrics(model.getMetrics().stream().filter(metric ->
                    metric.getRequiredPermissions() == null || metric.getRequiredPermissions().stream()
                            .allMatch(code -> permissions.hasPermission(user, code))).toList());
        }
        result.setModels(result.getModels().stream().filter(model -> !model.getMetrics().isEmpty()).toList());
        return result;
    }

    private SkillResult success(JsonNode payload) {
        return SkillResult.builder().status(SkillResult.Status.SUCCESS).skillName(name())
                .payload(payload).riskLevel(riskLevel()).build();
    }

    private SkillSpiException invalid(String message) {
        return new SkillSpiException(SkillErrorCode.PARAMS_INVALID, message, "/");
    }
}

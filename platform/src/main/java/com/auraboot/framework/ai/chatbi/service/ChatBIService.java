package com.auraboot.framework.ai.chatbi.service;

import com.auraboot.framework.ai.chatbi.dto.ChatBIRequest;
import com.auraboot.framework.ai.chatbi.dto.ChatBIResponse;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.QueryBuilderDTO;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ChatBI Service — natural language question → structured query → chart results.
 *
 * <p>Current implementation uses keyword-based parsing (mock/fallback mode).
 * LLM integration is left as a TODO for when providers are configured in ACP.</p>
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Load model metadata from {@link MetaModelService}</li>
 *   <li>Parse question keywords to infer model, aggregation, filters, sorting</li>
 *   <li>Build a {@link QueryBuilderDTO} and execute via {@link DynamicDataMapper}</li>
 *   <li>Suggest chart type based on result shape</li>
 * </ol>
 * </p>
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatBIService {

    private final MetaModelService metaModelService;
    private final DynamicDataMapper dynamicDataMapper;

    // --- Keyword sets for intent detection ---

    private static final Set<String> COUNT_KEYWORDS = Set.of(
            "count", "how many", "number of", "total count", "quantity"
    );
    private static final Set<String> SUM_KEYWORDS = Set.of(
            "sum", "total", "revenue", "amount", "value", "sales"
    );
    private static final Set<String> AVG_KEYWORDS = Set.of(
            "average", "avg", "mean", "typical"
    );
    private static final Set<String> GROUP_KEYWORDS = Set.of(
            "by", "group by", "per", "breakdown", "each", "category", "status", "type"
    );
    private static final Set<String> TOP_KEYWORDS = Set.of(
            "top", "highest", "largest", "biggest", "most"
    );
    private static final Set<String> BOTTOM_KEYWORDS = Set.of(
            "bottom", "lowest", "smallest", "least"
    );
    private static final Set<String> TREND_KEYWORDS = Set.of(
            "trend", "over time", "monthly", "daily", "weekly", "timeline", "history"
    );
    private static final Set<String> PIE_KEYWORDS = Set.of(
            "distribution", "share", "proportion", "percentage", "breakdown", "pie"
    );

    // Default limit for queries
    private static final int DEFAULT_LIMIT = 50;
    private static final int TOP_N_LIMIT = 10;

    /**
     * Analyze a natural language question and return query results with chart hints.
     *
     * @param request the ChatBI request containing the question and optional modelCode
     * @return populated ChatBIResponse with records, columns, chartType, and sql
     */
    public ChatBIResponse analyzeQuestion(ChatBIRequest request) {
        String question = request.getQuestion().toLowerCase(Locale.ROOT);
        String modelCode = request.getModelCode();

        log.info("ChatBI analyzeQuestion: question='{}', modelCode='{}'", request.getQuestion(), modelCode);

        // TODO: Integrate with ACP LLM provider when configured.
        // The LLM would parse the question into a structured QueryBuilderDTO.
        // For now we use keyword-based parsing as a functional fallback.

        // Step 1: Resolve model
        ModelDefinition model = resolveModel(question, modelCode);
        if (model == null) {
            return buildErrorResponse(request.getQuestion(), "Could not determine which data model to query. Please specify a modelCode or mention a model name in your question.");
        }

        // Step 2: Build QueryBuilderDTO from parsed question
        ParsedIntent intent = parseIntent(question, model);
        QueryBuilderDTO dto = buildQueryDTO(intent, model);

        // Step 3: Execute query
        String tableName = resolveTableName(model);
        Map<String, String> fieldToColumn = buildFieldColumnMap(model);
        String sql = buildSql(dto, tableName, fieldToColumn);
        Map<String, Object> params = buildParams(dto);

        log.debug("ChatBI SQL: {}", sql);

        List<Map<String, Object>> rows;
        try {
            rows = dynamicDataMapper.selectByQuery(sql, params);
        } catch (Exception e) {
            log.warn("ChatBI query failed: {}", e.getMessage());
            return buildErrorResponse(request.getQuestion(), "Query execution failed: " + e.getMessage());
        }

        if (rows == null) rows = Collections.emptyList();

        // Step 4: Extract columns from first row
        List<String> columns = rows.isEmpty()
                ? extractColumnsFromDTO(dto, model)
                : new ArrayList<>(rows.get(0).keySet());

        // Step 5: Suggest chart type
        String chartType = suggestChartType(intent, columns, rows);
        Map<String, Object> chartConfig = buildChartConfig(chartType, intent, columns);

        // Step 6: Build interpretation text
        String interpretation = buildInterpretation(intent, model);

        return ChatBIResponse.builder()
                .interpretation(interpretation)
                .modelCode(model.getCode())
                .columns(columns)
                .records(rows)
                .chartType(chartType)
                .chartConfig(chartConfig)
                .sql(sql)
                .total(rows.size())
                .build();
    }

    // ==================== Model Resolution ====================

    private ModelDefinition resolveModel(String question, String modelCode) {
        // If explicit modelCode provided, use it
        if (modelCode != null && !modelCode.isBlank()) {
            return metaModelService.getModelDefinition(modelCode).orElse(null);
        }

        // Try to find model by matching model code or name keywords in the question
        // Load a sample of models and find best match
        try {
            var modelsPage = metaModelService.searchModels(1, 100, null, null, null, null, null, true);
            if (modelsPage == null || modelsPage.getRecords() == null) return null;

            for (var m : modelsPage.getRecords()) {
                String code = m.getCode() != null ? m.getCode().toLowerCase() : "";
                String displayName = m.getDisplayName() != null ? m.getDisplayName().toLowerCase() : "";

                // Match model code fragments or display name in question
                if (!code.isBlank() && question.contains(code)) return metaModelService.getModelDefinition(m.getCode()).orElse(null);
                if (!displayName.isBlank() && question.contains(displayName)) return metaModelService.getModelDefinition(m.getCode()).orElse(null);
            }
        } catch (Exception e) {
            log.warn("ChatBI: failed to search models: {}", e.getMessage());
        }

        return null;
    }

    // ==================== Intent Parsing ====================

    /**
     * Parsed intent from the natural language question.
     */
    static class ParsedIntent {
        String aggregationFunction = null; // COUNT, SUM, AVG, or null for raw select
        String aggregationField = null;    // field to aggregate on (null = *)
        String groupByField = null;        // field to group by
        String sortOrder = "desc";
        int limit = DEFAULT_LIMIT;
        boolean isTopN = false;
        boolean isTrend = false;
        String filterField = null;
        Object filterValue = null;
        String filterOperator = "EQ";
    }

    private ParsedIntent parseIntent(String question, ModelDefinition model) {
        ParsedIntent intent = new ParsedIntent();

        // Detect top/bottom N
        if (containsAny(question, TOP_KEYWORDS)) {
            intent.isTopN = true;
            intent.sortOrder = "desc";
            intent.limit = TOP_N_LIMIT;
            extractTopN(question, intent);
        } else if (containsAny(question, BOTTOM_KEYWORDS)) {
            intent.isTopN = true;
            intent.sortOrder = "asc";
            intent.limit = TOP_N_LIMIT;
        }

        // Detect trend/time series
        if (containsAny(question, TREND_KEYWORDS)) {
            intent.isTrend = true;
        }

        // Detect aggregation function
        if (containsAny(question, COUNT_KEYWORDS)) {
            intent.aggregationFunction = "count";
            intent.aggregationField = "*";
        } else if (containsAny(question, SUM_KEYWORDS)) {
            intent.aggregationFunction = "sum";
            intent.aggregationField = findNumericField(model);
        } else if (containsAny(question, AVG_KEYWORDS)) {
            intent.aggregationFunction = "avg";
            intent.aggregationField = findNumericField(model);
        }

        // Detect group by
        if (containsAny(question, GROUP_KEYWORDS) && model.getFields() != null) {
            intent.groupByField = findGroupByField(question, model);
        }

        return intent;
    }

    private void extractTopN(String question, ParsedIntent intent) {
        // Try to extract the N from "top 5", "top 10", etc.
        Pattern topNPattern = Pattern.compile("top\\s+(\\d+)");
        var matcher = topNPattern.matcher(question);
        if (matcher.find()) {
            try {
                intent.limit = Math.min(Integer.parseInt(matcher.group(1)), 100);
            } catch (NumberFormatException ignored) {
                intent.limit = TOP_N_LIMIT;
            }
        }
    }

    private String findNumericField(ModelDefinition model) {
        if (model.getFields() == null) return null;
        // Prefer fields named amount, value, price, total, count, quantity
        String[] numericHints = {"amount", "value", "price", "total", "count", "qty", "quantity", "sum"};
        for (String hint : numericHints) {
            for (FieldDefinition f : model.getFields()) {
                String code = f.getCode() != null ? f.getCode().toLowerCase() : "";
                if (code.contains(hint)) return f.getCode();
            }
        }
        // Fall back to first DECIMAL/INTEGER field
        for (FieldDefinition f : model.getFields()) {
            String type = f.getDataType() != null ? f.getDataType().toUpperCase() : "";
            if ("decimal".equals(type) || "integer".equals(type) || "number".equals(type)) {
                return f.getCode();
            }
        }
        return null;
    }

    private String findGroupByField(String question, ModelDefinition model) {
        if (model.getFields() == null) return null;
        // Prefer status, type, category fields
        String[] groupHints = {"status", "type", "category", "stage", "kind", "level", "state"};
        for (String hint : groupHints) {
            // Check if question contains this hint
            if (question.contains(hint)) {
                for (FieldDefinition f : model.getFields()) {
                    String code = f.getCode() != null ? f.getCode().toLowerCase() : "";
                    if (code.contains(hint)) return f.getCode();
                }
            }
        }
        // Also look for date/time fields for trend queries
        for (FieldDefinition f : model.getFields()) {
            String code = f.getCode() != null ? f.getCode().toLowerCase() : "";
            String type = f.getDataType() != null ? f.getDataType().toUpperCase() : "";
            if ("date".equals(type) || "datetime".equals(type) || code.contains("date") || code.contains("time")) {
                // Only use date for trend queries
                return null; // Skip date groupby for now — trend is handled differently
            }
        }
        // Default: first STATUS-type field
        for (FieldDefinition f : model.getFields()) {
            String code = f.getCode() != null ? f.getCode().toLowerCase() : "";
            if (code.contains("status") || code.contains("type") || code.contains("state")) {
                return f.getCode();
            }
        }
        return null;
    }

    // ==================== QueryBuilderDTO Construction ====================

    private QueryBuilderDTO buildQueryDTO(ParsedIntent intent, ModelDefinition model) {
        QueryBuilderDTO dto = new QueryBuilderDTO();
        dto.setModelCode(model.getCode());
        dto.setLimit(intent.limit);
        dto.setSortOrder(intent.sortOrder);

        // Aggregation
        if (intent.aggregationFunction != null) {
            QueryBuilderDTO.AggregationConfig agg = new QueryBuilderDTO.AggregationConfig();
            agg.setFunction(intent.aggregationFunction);

            if ("count".equals(intent.aggregationFunction)) {
                // COUNT(*) — use first non-null field or id
                String countField = findIdField(model);
                agg.setFieldCode(countField != null ? countField : "id");
                agg.setAlias("count_value");
            } else {
                String aggField = intent.aggregationField != null ? intent.aggregationField : findNumericField(model);
                if (aggField != null) {
                    agg.setFieldCode(aggField);
                    agg.setAlias(aggField + "_" + intent.aggregationFunction.toLowerCase());
                }
            }

            if (agg.getFieldCode() != null) {
                dto.setAggregations(List.of(agg));
            }

            // Group by
            if (intent.groupByField != null) {
                dto.setGroupBy(List.of(intent.groupByField));
                // Sort by aggregation result
                String aggAlias = agg.getAlias();
                if (aggAlias != null) {
                    dto.setSortField(aggAlias);
                }
            }
        }

        return dto;
    }

    private String findIdField(ModelDefinition model) {
        if (model.getFields() == null) return null;
        for (FieldDefinition f : model.getFields()) {
            if (f.isPrimaryKey()) return f.getCode();
            String code = f.getCode() != null ? f.getCode().toLowerCase() : "";
            if ("id".equals(code) || code.endsWith("_id")) return f.getCode();
        }
        return null;
    }

    // ==================== SQL Building (mirrors QueryBuilderController logic) ====================

    private String resolveTableName(ModelDefinition model) {
        if (model.getTableName() != null && !model.getTableName().isBlank()) {
            return model.getTableName();
        }
        try {
            String tableName = metaModelService.getTableName(model.getCode());
            if (tableName != null && !tableName.isBlank()) return tableName;
        } catch (Exception e) {
            log.warn("ChatBI: failed to resolve table name for {}: {}", model.getCode(), e.getMessage());
        }
        return SystemFieldConstants.generateTableName(model.getCode());
    }

    private Map<String, String> buildFieldColumnMap(ModelDefinition model) {
        if (model.getFields() == null) return Collections.emptyMap();
        Map<String, String> map = new HashMap<>();
        for (FieldDefinition field : model.getFields()) {
            String col = (field.getColumnName() != null && !field.getColumnName().isBlank())
                    ? field.getColumnName()
                    : field.getCode();
            map.put(field.getCode(), col);
        }
        return map;
    }

    private String buildSql(QueryBuilderDTO dto, String tableName, Map<String, String> fieldToColumn) {
        StringBuilder sql = new StringBuilder("SELECT ");

        // SELECT clause
        List<String> selectClauses = new ArrayList<>();
        if (dto.getAggregations() != null && !dto.getAggregations().isEmpty()) {
            if (dto.getGroupBy() != null) {
                for (String fc : dto.getGroupBy()) {
                    selectClauses.add(resolveColumn(fc, fieldToColumn));
                }
            }
            for (QueryBuilderDTO.AggregationConfig agg : dto.getAggregations()) {
                String col = "count".equals(agg.getFunction())
                        ? "COUNT(*)"
                        : agg.getFunction() + "(" + resolveColumn(agg.getFieldCode(), fieldToColumn) + ")";
                selectClauses.add(col + " AS " + agg.getAlias());
            }
        } else if (dto.getFields() != null && !dto.getFields().isEmpty()) {
            for (String fc : dto.getFields()) {
                selectClauses.add(resolveColumn(fc, fieldToColumn));
            }
        } else {
            selectClauses.add("*");
        }
        sql.append(String.join(", ", selectClauses));

        sql.append(" FROM ").append(tableName);

        // WHERE clause
        List<String> whereClauses = new ArrayList<>();
        if (!tableName.startsWith("mt_")) {
            whereClauses.add("deleted_flag = FALSE");
        }
        if (!whereClauses.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", whereClauses));
        }

        // GROUP BY
        if (dto.getGroupBy() != null && !dto.getGroupBy().isEmpty()) {
            List<String> groupByCols = dto.getGroupBy().stream()
                    .map(fc -> resolveColumn(fc, fieldToColumn))
                    .collect(Collectors.toList());
            sql.append(" GROUP BY ").append(String.join(", ", groupByCols));
        }

        // ORDER BY
        if (dto.getSortField() != null && !dto.getSortField().isBlank()) {
            String sortCol = dto.getSortField(); // alias — already safe
            String direction = "desc".equalsIgnoreCase(dto.getSortOrder()) ? "desc" : "asc";
            sql.append(" ORDER BY ").append(sortCol).append(" ").append(direction);
        }

        // LIMIT
        int limit = dto.getLimit() != null ? Math.min(dto.getLimit(), 5000) : DEFAULT_LIMIT;
        sql.append(" LIMIT ").append(limit);

        return sql.toString();
    }

    private String resolveColumn(String fieldCode, Map<String, String> fieldToColumn) {
        if (fieldCode == null || fieldCode.isBlank()) return fieldCode;
        String col = fieldToColumn.get(fieldCode);
        return col != null ? col : fieldCode;
    }

    private Map<String, Object> buildParams(QueryBuilderDTO dto) {
        return new HashMap<>(); // No parameterised filters in current implementation
    }

    // ==================== Chart Type Suggestion ====================

    /**
     * Suggest a chart type based on the parsed intent and result shape.
     * <ul>
     *   <li>Time series → line</li>
     *   <li>Aggregation with groupBy (few groups, count/proportion) → pie</li>
     *   <li>Aggregation with groupBy (many groups or sum/avg) → bar</li>
     *   <li>Plain select → table</li>
     * </ul>
     */
    private String suggestChartType(ParsedIntent intent, List<String> columns, List<Map<String, Object>> rows) {
        if (intent.isTrend) return "line";
        if (intent.aggregationFunction != null && intent.groupByField != null) {
            int rowCount = rows.size();
            if (("count".equals(intent.aggregationFunction) || containsAny(intent.aggregationFunction.toLowerCase(), PIE_KEYWORDS))
                    && rowCount > 0 && rowCount <= 8) {
                return "pie";
            }
            return "bar";
        }
        if (intent.aggregationFunction != null) return "bar";
        return "table";
    }

    private Map<String, Object> buildChartConfig(String chartType, ParsedIntent intent, List<String> columns) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", chartType);

        if (!columns.isEmpty()) {
            // For bar/pie: first column is category, second is value
            if ("bar".equals(chartType) || "pie".equals(chartType)) {
                config.put("labelField", columns.get(0));
                if (columns.size() > 1) config.put("valueField", columns.get(1));
            } else if ("line".equals(chartType)) {
                config.put("xField", columns.get(0));
                if (columns.size() > 1) config.put("yField", columns.get(1));
            }
        }

        return config;
    }

    // ==================== Helpers ====================

    private boolean containsAny(String text, Set<String> keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private List<String> extractColumnsFromDTO(QueryBuilderDTO dto, ModelDefinition model) {
        if (dto.getAggregations() != null && !dto.getAggregations().isEmpty()) {
            List<String> cols = new ArrayList<>();
            if (dto.getGroupBy() != null) cols.addAll(dto.getGroupBy());
            dto.getAggregations().forEach(a -> cols.add(a.getAlias() != null ? a.getAlias() : a.getFieldCode()));
            return cols;
        }
        if (dto.getFields() != null && !dto.getFields().isEmpty()) return dto.getFields();
        if (model.getFields() != null) {
            return model.getFields().stream().map(FieldDefinition::getCode).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private String buildInterpretation(ParsedIntent intent, ModelDefinition model) {
        StringBuilder sb = new StringBuilder("Querying ");
        String modelName = model.getDisplayName() != null ? model.getDisplayName() : model.getCode();
        sb.append(modelName);

        if (intent.aggregationFunction != null) {
            sb.append(" — ").append(intent.aggregationFunction);
            if (intent.aggregationField != null && !"*".equals(intent.aggregationField)) {
                sb.append(" of ").append(intent.aggregationField);
            }
        }
        if (intent.groupByField != null) {
            sb.append(" grouped by ").append(intent.groupByField);
        }
        if (intent.isTopN) {
            sb.append(", top ").append(intent.limit).append(" by ").append(intent.sortOrder.equals("desc") ? "highest" : "lowest");
        }
        sb.append(".");
        return sb.toString();
    }

    private ChatBIResponse buildErrorResponse(String question, String message) {
        return ChatBIResponse.builder()
                .interpretation(message)
                .modelCode(null)
                .columns(Collections.emptyList())
                .records(Collections.emptyList())
                .chartType("table")
                .chartConfig(Collections.emptyMap())
                .sql("")
                .total(0)
                .build();
    }
}

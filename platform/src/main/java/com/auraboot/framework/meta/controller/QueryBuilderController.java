package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.QueryBuilderDTO;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.QueryBuilderService;
import com.auraboot.framework.common.dto.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Query Builder Controller.
 *
 * <p>Provides a safe, structured query execution API that accepts a {@link QueryBuilderDTO}
 * instead of raw SQL. SQL is generated server-side using the model's field registry so that
 * only whitelisted column names and operators can reach the database.</p>
 *
 * <p>Tenant isolation is enforced automatically via {@code TenantLineInterceptor} on
 * {@link QueryBuilderService#executeRaw}.</p>
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/query-builder")
@RequiredArgsConstructor
@Tag(name = "Query Builder", description = "Safe structured query API — accepts DTOs, never raw SQL")
public class QueryBuilderController {

    /** Valid SQL identifier pattern — table names, column names, aliases. */
    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /** Operators that require a value parameter. */
    private static final Set<String> VALUE_OPERATORS = Set.of(
            "EQ", "NEQ", "GT", "GTE", "LT", "LTE", "LIKE", "IN", "NOT_IN"
    );

    /** Operators that require no value parameter. */
    private static final Set<String> NULL_OPERATORS = Set.of("IS_NULL", "IS_NOT_NULL");

    /** All supported filter operators. */
    private static final Set<String> ALL_OPERATORS = new HashSet<>();

    static {
        ALL_OPERATORS.addAll(VALUE_OPERATORS);
        ALL_OPERATORS.addAll(NULL_OPERATORS);
    }

    /** Supported aggregation functions (uppercase to match toUpperCase() comparison). */
    private static final Set<String> SUPPORTED_FUNCTIONS = Set.of(
            "COUNT", "SUM", "AVG", "MIN", "MAX"
    );

    private final MetaModelService metaModelService;
    private final QueryBuilderService queryBuilderService;

    // ==================== Endpoints ====================

    /**
     * Execute a structured query and return results.
     *
     * <p>The controller resolves field codes to physical column names, builds safe SQL,
     * and executes it via {@link QueryBuilderService#executeRaw} which automatically
     * appends the tenant filter.</p>
     */
    @PostMapping("/execute")
    @Operation(
            summary = "Execute query",
            description = "Execute a structured query DTO and return matching rows. "
                    + "Field codes are resolved to column names; raw SQL is never accepted.")
    public ApiResponse<List<Map<String, Object>>> execute(
            @Valid @RequestBody QueryBuilderDTO dto) {

        log.info("query-builder execute: modelCode={}, fields={}, filters={}, groupBy={}, aggregations={}, limit={}",
                dto.getModelCode(), dto.getFields(), dto.getFilters(),
                dto.getGroupBy(), dto.getAggregations(), dto.getLimit());

        // 1. Resolve model definition
        ModelDefinition model = requireModel(dto.getModelCode());
        String tableName = resolveTableName(dto.getModelCode(), model);

        // 2. Verify the physical table exists
        queryBuilderService.verifyTableExists(tableName, dto.getModelCode());

        // 3. Build field-code → column-name map from model definition
        Map<String, String> fieldToColumn = buildFieldColumnMap(model);

        // 4. Validate and build SQL
        String sql = buildSql(dto, tableName, fieldToColumn, model);
        Map<String, Object> params = buildParams(dto);

        log.debug("query-builder SQL: {}", sql);

        // 5. Execute — TenantLineInterceptor appends tenant_id automatically
        List<Map<String, Object>> rows = queryBuilderService.executeRaw(sql, params);

        return ApiResponse.success(rows);
    }

    /**
     * List available models (proxies to the existing model service).
     */
    @GetMapping("/models")
    @Operation(summary = "List models", description = "Returns a paginated list of available models")
    public ApiResponse<List<Map<String, Object>>> listModels(
            @Parameter(description = "Page number (1-based)") @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") Integer size,
            @Parameter(description = "Keyword filter") @RequestParam(required = false) String keyword) {

        log.info("query-builder listModels: page={}, size={}, keyword={}", page, size, keyword);
        PageResult<MetaModelDTO> result = metaModelService.searchModels(
                page, size, keyword, null, null, null, "published", null, null, null, true);
        List<Map<String, Object>> models = result == null || result.getRecords() == null
                ? Collections.emptyList()
                : result.getRecords().stream()
                .map(model -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("code", model.getCode());
                    item.put("name", model.getDisplayName());
                    item.put("description", model.getDescription());
                    return item;
                })
                .collect(Collectors.toList());
        return ApiResponse.success(models);
    }

    /**
     * Get the fields available for a given model.
     */
    @GetMapping("/models/{modelCode}/fields")
    @Operation(summary = "Get model fields", description = "Returns field definitions for the specified model")
    public ApiResponse<List<Map<String, Object>>> getModelFields(
            @Parameter(description = "Model code") @PathVariable String modelCode) {

        log.info("query-builder getModelFields: modelCode={}", modelCode);

        ModelDefinition model = requireModel(modelCode);

        List<Map<String, Object>> fields = model.getFields() == null
                ? Collections.emptyList()
                : model.getFields().stream()
                        .map(f -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("code", f.getCode());
                            m.put("columnName", f.getColumnName());
                            m.put("dataType", f.getDataType());
                            m.put("displayName", f.getDisplayName());
                            m.put("required", Boolean.TRUE.equals(f.getRequired()));
                            m.put("primaryKey", Boolean.TRUE.equals(f.getPrimaryKey()));
                            return m;
                        })
                        .collect(Collectors.toList());

        return ApiResponse.success(fields);
    }

    // ==================== Internal helpers ====================

    /**
     * Load a model definition or throw a 400-style exception.
     */
    private ModelDefinition requireModel(String modelCode) {
        validateIdentifier(modelCode, "modelCode");
        return metaModelService.getModelDefinition(modelCode)
                .orElseThrow(() -> new MetaServiceException("Model not found: " + modelCode));
    }

    /**
     * Resolve the physical table name for a model.
     */
    private String resolveTableName(String modelCode, ModelDefinition model) {
        // Prefer the table name stored in the model definition
        if (model.getTableName() != null && !model.getTableName().isBlank()) {
            return model.getTableName();
        }
        // Fallback: use MetaModelService which also handles system tables
        try {
            String tableName = metaModelService.getTableName(modelCode);
            if (tableName != null && !tableName.isBlank()) {
                return tableName;
            }
        } catch (Exception e) {
            log.warn("Failed to resolve table name via MetaModelService for {}: {}", modelCode, e.getMessage());
        }
        // Last resort: derive from model code using the standard naming convention
        return SystemFieldConstants.generateTableName(modelCode);
    }

    /**
     * Build a fieldCode → columnName map from the model definition.
     * Falls back to the field code itself if no column name is configured.
     */
    private Map<String, String> buildFieldColumnMap(ModelDefinition model) {
        if (model.getFields() == null) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new HashMap<>();
        for (FieldDefinition field : model.getFields()) {
            String col = (field.getColumnName() != null && !field.getColumnName().isBlank())
                    ? field.getColumnName()
                    : field.getCode();
            map.put(field.getCode(), col);
        }
        return map;
    }

    /**
     * Build the full SELECT SQL from the DTO.
     *
     * <p>All identifiers (table name, column names, aliases) are validated against
     * {@link #IDENTIFIER_PATTERN} before inclusion in the SQL string. Query values
     * are always bound via MyBatis {@code #{params.key}} placeholders.</p>
     */
    private String buildSql(QueryBuilderDTO dto, String tableName,
                             Map<String, String> fieldToColumn, ModelDefinition model) {

        // Validate the table name itself
        validateIdentifier(tableName, "tableName");

        StringBuilder sql = new StringBuilder("SELECT ");

        // -- SELECT clause
        List<String> selectClauses = buildSelectClauses(dto, fieldToColumn, model);
        sql.append(String.join(", ", selectClauses));

        // -- FROM clause
        sql.append(" FROM ").append(tableName);

        // -- WHERE clause
        List<String> whereClauses = buildWhereClauses(dto, fieldToColumn, tableName);
        if (!whereClauses.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", whereClauses));
        }

        // -- GROUP BY clause
        if (dto.getGroupBy() != null && !dto.getGroupBy().isEmpty()) {
            List<String> groupByCols = dto.getGroupBy().stream()
                    .map(fc -> resolveColumn(fc, fieldToColumn, "groupBy"))
                    .collect(Collectors.toList());
            sql.append(" GROUP BY ").append(String.join(", ", groupByCols));
        }

        // -- ORDER BY clause
        if (dto.getSortField() != null && !dto.getSortField().isBlank()) {
            String col = resolveColumn(dto.getSortField(), fieldToColumn, "sortField");
            String direction = "desc".equalsIgnoreCase(dto.getSortOrder()) ? "desc" : "asc";
            sql.append(" ORDER BY ").append(col).append(" ").append(direction);
        }

        // -- LIMIT clause
        int limit = dto.getLimit() != null ? Math.min(dto.getLimit(), 5000) : 500;
        sql.append(" LIMIT ").append(limit);

        return sql.toString();
    }

    /**
     * Build the SELECT clause list.
     *
     * <ul>
     *   <li>If aggregations are present, only aggregation expressions are selected
     *       (plus any explicit groupBy fields).</li>
     *   <li>If explicit fields are specified, only those columns are selected.</li>
     *   <li>Otherwise, {@code *} is used.</li>
     * </ul>
     */
    private List<String> buildSelectClauses(QueryBuilderDTO dto,
                                             Map<String, String> fieldToColumn,
                                             ModelDefinition model) {
        List<String> clauses = new ArrayList<>();

        // Add groupBy fields to SELECT when aggregations are present
        if (dto.getAggregations() != null && !dto.getAggregations().isEmpty()) {
            if (dto.getGroupBy() != null) {
                for (String fc : dto.getGroupBy()) {
                    clauses.add(resolveColumn(fc, fieldToColumn, "groupBy(select)"));
                }
            }
            for (QueryBuilderDTO.AggregationConfig agg : dto.getAggregations()) {
                clauses.add(buildAggregationClause(agg, fieldToColumn));
            }
            return clauses;
        }

        // Explicit field list
        if (dto.getFields() != null && !dto.getFields().isEmpty()) {
            for (String fc : dto.getFields()) {
                clauses.add(resolveColumn(fc, fieldToColumn, "fields"));
            }
            return clauses;
        }

        // Default: select all
        clauses.add("*");
        return clauses;
    }

    /**
     * Build a single aggregation expression, e.g. {@code SUM(amount) AS total_amount}.
     */
    private String buildAggregationClause(QueryBuilderDTO.AggregationConfig agg,
                                           Map<String, String> fieldToColumn) {
        if (agg.getFieldCode() == null || agg.getFieldCode().isBlank()) {
            throw new MetaServiceException("Aggregation fieldCode is required");
        }
        String func = agg.getFunction() != null ? agg.getFunction().toUpperCase() : null;
        if (func == null || !SUPPORTED_FUNCTIONS.contains(func)) {
            throw new MetaServiceException(
                    "Unsupported aggregation function: " + agg.getFunction()
                            + ". Supported: " + SUPPORTED_FUNCTIONS);
        }

        String col = resolveColumn(agg.getFieldCode(), fieldToColumn, "aggregation.fieldCode");
        String alias;
        if (agg.getAlias() != null && !agg.getAlias().isBlank()) {
            validateIdentifier(agg.getAlias(), "aggregation.alias");
            alias = agg.getAlias();
        } else {
            alias = agg.getFieldCode() + "_" + func.toLowerCase();
        }

        return func + "(" + col + ") AS " + alias;
    }

    /**
     * Build WHERE clause fragments, applying deleted_flag guard for non-mt_ tables.
     */
    private List<String> buildWhereClauses(QueryBuilderDTO dto, Map<String, String> fieldToColumn,
                                            String tableName) {
        List<String> clauses = new ArrayList<>();

        // System / static tables carry a deleted_flag column; dynamic biz tables do not
        if (!tableName.startsWith("mt_")) {
            clauses.add("deleted_flag = FALSE");
        }

        if (dto.getFilters() == null) {
            return clauses;
        }

        for (int i = 0; i < dto.getFilters().size(); i++) {
            QueryBuilderDTO.FilterCondition fc = dto.getFilters().get(i);
            String clause = buildFilterClause(fc, fieldToColumn, i);
            if (clause != null) {
                clauses.add(clause);
            }
        }

        return clauses;
    }

    /**
     * Build a single parameterised WHERE clause fragment.
     */
    private String buildFilterClause(QueryBuilderDTO.FilterCondition filter,
                                      Map<String, String> fieldToColumn,
                                      int index) {
        if (filter == null || filter.getFieldName() == null || filter.getFieldName().isBlank()) {
            return null;
        }

        String col = resolveColumn(filter.getFieldName(), fieldToColumn, "filter.fieldName");
        String op = filter.getOperator() != null ? filter.getOperator().toUpperCase() : "EQ";

        if (!ALL_OPERATORS.contains(op)) {
            throw new MetaServiceException(
                    "Unsupported filter operator: " + filter.getOperator()
                            + ". Supported: " + ALL_OPERATORS);
        }

        String paramKey = "f_" + index;

        if (NULL_OPERATORS.contains(op)) {
            return switch (op) {
                case "IS_NULL" -> col + " IS NULL";
                case "IS_NOT_NULL" -> col + " IS NOT NULL";
                default -> null;
            };
        }

        return switch (op) {
            case "EQ"     -> col + " = #{params." + paramKey + "}";
            case "NEQ"    -> col + " != #{params." + paramKey + "}";
            case "GT"     -> col + " > #{params." + paramKey + "}";
            case "GTE"    -> col + " >= #{params." + paramKey + "}";
            case "LT"     -> col + " < #{params." + paramKey + "}";
            case "LTE"    -> col + " <= #{params." + paramKey + "}";
            case "LIKE"   -> col + " LIKE #{params." + paramKey + "}";
            case "IN"     -> col + " IN (#{params." + paramKey + "})";
            case "NOT_IN" -> col + " NOT IN (#{params." + paramKey + "})";
            default       -> col + " = #{params." + paramKey + "}";
        };
    }

    /**
     * Build the params map for MyBatis placeholder substitution.
     */
    private Map<String, Object> buildParams(QueryBuilderDTO dto) {
        Map<String, Object> params = new HashMap<>();
        if (dto.getFilters() == null) {
            return params;
        }
        for (int i = 0; i < dto.getFilters().size(); i++) {
            QueryBuilderDTO.FilterCondition fc = dto.getFilters().get(i);
            if (fc == null) continue;
            String op = fc.getOperator() != null ? fc.getOperator().toUpperCase() : "EQ";
            if (NULL_OPERATORS.contains(op)) continue; // no value needed
            if (fc.getValue() != null) {
                Object val = fc.getValue();
                // Wrap LIKE values with % wildcards if not already present
                if ("LIKE".equals(op) && val instanceof String s && !s.contains("%")) {
                    val = "%" + s + "%";
                }
                params.put("f_" + i, val);
            }
        }
        return params;
    }

    /**
     * Resolve a field code to its physical column name.
     * If the field code is not in the map (e.g. system fields like tenant_id),
     * the code itself is validated as a safe identifier and used directly.
     */
    private String resolveColumn(String fieldCode, Map<String, String> fieldToColumn, String context) {
        if (fieldCode == null || fieldCode.isBlank()) {
            throw new MetaServiceException("Empty field code in " + context);
        }
        String col = fieldToColumn.get(fieldCode);
        if (col != null) {
            validateIdentifier(col, context + " (column)");
            return col;
        }
        // Not in model registry — treat as a raw column name but validate it
        validateIdentifier(fieldCode, context + " (raw)");
        return fieldCode;
    }

    /**
     * Validate that an identifier contains only safe characters to prevent SQL injection.
     */
    private void validateIdentifier(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new MetaServiceException("Empty identifier for: " + label);
        }
        if (!IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw new MetaServiceException(
                    "Invalid identifier for " + label + ": '" + value
                            + "'. Only [a-zA-Z_][a-zA-Z0-9_]* is allowed.");
        }
    }
}

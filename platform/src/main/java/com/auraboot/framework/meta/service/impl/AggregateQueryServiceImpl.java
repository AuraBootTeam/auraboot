package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.dto.AggregateQueryResponse;
import com.auraboot.framework.meta.dto.MetricConfig;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.NamedQueryFieldMapper;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.auraboot.framework.meta.service.AggregateQueryService;
import com.auraboot.framework.meta.service.base.BaseMetaService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Implementation of AggregateQueryService using DynamicDataMapper for query execution.
 *
 * <p><b>Design Note:</b> This implementation uses DynamicDataMapper (MyBatis) for executing
 * dynamic SQL queries. This approach maintains consistency with other meta services and
 * leverages the existing SQL provider infrastructure.
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AggregateQueryServiceImpl extends BaseMetaService implements AggregateQueryService {

    private final DynamicDataMapper dynamicDataMapper;
    private final NamedQueryMapper namedQueryMapper;
    private final NamedQueryFieldMapper namedQueryFieldMapper;
    private final com.auraboot.framework.meta.service.MetaModelService metaModelService;

    /**
     * Valid identifier pattern for SQL identifiers (table names, column names, aliases).
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /**
     * Supported aggregation functions.
     */
    private static final Set<String> SUPPORTED_AGGREGATIONS = Set.of(
            "count", "count_distinct", "sum", "avg", "max", "min"
    );

    /**
     * Supported filter operators.
     */
    private static final Set<String> SUPPORTED_OPERATORS = Set.of(
            "eq", "ne", "neq", "gt", "gte", "ge", "lt", "lte", "le", "like", "in", "not_in", "is_null", "is_not_null"
    );

    @Override
    @Transactional(readOnly = true)
    @Cacheable(
            value = "aggregateQuery",
            key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix() + ':' + #request.hashCode()",
            unless = "#result == null || #result.getRows() == null || #result.getRows().isEmpty()"
    )
    public AggregateQueryResponse execute(AggregateQueryRequest request) {
        validateRequest(request);

        if ("namedQuery".equals(request.getType())) {
            return executeNamedQuery(request);
        }
        return executeAggregateQuery(request);
    }

    /**
     * Execute a dynamic aggregate query.
     */
    private AggregateQueryResponse executeAggregateQuery(AggregateQueryRequest request) {
        String tableName = resolveTableName(request.getModelCode());
        String sql = buildAggregateSql(request, tableName);
        Map<String, Object> params = buildParams(request);

        log.debug("Executing aggregate query: SQL={}, params={}", sql, params);

        List<Map<String, Object>> rawRows = dynamicDataMapper.selectByQuery(sql, params);
        // Filter out null entries that MyBatis can return for all-NULL aggregate rows
        List<Map<String, Object>> rows = rawRows != null
                ? rawRows.stream().filter(Objects::nonNull).collect(Collectors.toList())
                : List.of();

        AggregateQueryResponse response = new AggregateQueryResponse();
        response.setRows(rows);
        response.setMeta(buildMeta(request));
        response.setSummary(calculateSummary(rows, request.getMetrics()));

        return response;
    }

    /**
     * Execute a named query with aggregation on top of the Named Query's fromSql.
     *
     * <p>Flow:
     * 1. Load Named Query definition and field whitelist
     * 2. Validate dimensions/metrics/filters against field whitelist
     * 3. Build aggregation SQL using fromSql as subquery source
     * 4. Map fieldCode → columnExpr for safe SQL generation
     * 5. Execute via DynamicDataMapper
     */
    private AggregateQueryResponse executeNamedQuery(AggregateQueryRequest request) {
        String queryCode = request.getQueryCode();
        Long tenantId = getCurrentTenantId();

        // 1. Load Named Query definition
        NamedQuery query = namedQueryMapper.findByCode(queryCode);
        if (query == null) {
            throw new MetaServiceException("Named query not found: " + queryCode);
        }
        if (!query.isExecutable()) {
            throw new MetaServiceException("Named query is not executable (status: " + query.getStatus() + "): " + queryCode);
        }

        // 2. Load field whitelist using MyBatis Plus built-in method (ensures typeHandler for operators JSONB)
        QueryWrapper<NamedQueryField> fieldQuery = new QueryWrapper<>();
        fieldQuery.eq("tenant_id", tenantId).eq("query_code", queryCode).orderByAsc("field_code");
        List<NamedQueryField> fields = namedQueryFieldMapper.selectList(fieldQuery);
        Map<String, NamedQueryField> fieldMap = fields.stream()
                .collect(Collectors.toMap(NamedQueryField::getFieldCode, f -> f));

        // 3. Validate dimensions against whitelist
        if (request.getDimensions() != null) {
            for (String dim : request.getDimensions()) {
                if (!fieldMap.containsKey(dim)) {
                    throw new MetaServiceException("Dimension field not in whitelist: " + dim);
                }
            }
        }

        // 4. Validate metrics against whitelist
        if (request.getMetrics() != null) {
            for (MetricConfig metric : request.getMetrics()) {
                if (!fieldMap.containsKey(metric.getField())) {
                    throw new MetaServiceException("Metric field not in whitelist: " + metric.getField());
                }
            }
        }

        // 5. Validate filter fields against whitelist and check operators
        validateNamedQueryFilters(request.getFilters(), fieldMap);
        validateNamedQueryFilters(request.getDrillFilters(), fieldMap);

        // 6. Build SQL
        String sql = buildNamedQuerySql(request, query, fieldMap, tenantId);
        Map<String, Object> params = buildNamedQueryParams(request, fieldMap);
        // Inject tenantId for fromSql that uses #{params.tenantId} for tenant isolation
        params.put("tenantId", tenantId);

        log.debug("Executing named query aggregate: code={}, SQL={}, params={}", queryCode, sql, params);

        // Use tenant-bypass method since tenant isolation is handled inside the NamedQuery's fromSql.
        // This avoids JSqlParser failures on complex PostgreSQL-specific syntax.
        List<Map<String, Object>> rawRows = dynamicDataMapper.selectByQueryWithoutTenant(sql, params);
        // Filter out null entries that MyBatis can return for all-NULL aggregate rows
        List<Map<String, Object>> rows = rawRows != null
                ? rawRows.stream().filter(Objects::nonNull).collect(Collectors.toList())
                : List.of();

        AggregateQueryResponse response = new AggregateQueryResponse();
        response.setRows(rows);
        response.setMeta(buildMetaForNamedQuery(request, query, fieldMap));
        response.setSummary(calculateSummary(rows, request.getMetrics()));

        return response;
    }

    /**
     * Validate filter fields against Named Query field whitelist and operator permissions.
     */
    private void validateNamedQueryFilters(List<AggregateQueryRequest.FilterConfig> filters,
                                           Map<String, NamedQueryField> fieldMap) {
        if (filters == null) return;
        for (AggregateQueryRequest.FilterConfig filter : filters) {
            if (filter.getField() == null) continue;
            NamedQueryField fieldDef = fieldMap.get(filter.getField());
            if (fieldDef == null) {
                throw new MetaServiceException("Filter field not in whitelist: " + filter.getField());
            }
            String operator = filter.getOperator() != null ? filter.getOperator().toLowerCase() : "eq";
            if (fieldDef.hasOperators() && !fieldDef.supportsOperator(operator)) {
                throw new MetaServiceException(
                        "Operator not allowed for field " + filter.getField() + ": " + operator);
            }
        }
    }

    /**
     * Build SQL for named query aggregation.
     * Uses the Named Query's fromSql as a subquery, with aggregation on top.
     */
    private String buildNamedQuerySql(AggregateQueryRequest request, NamedQuery query,
                                      Map<String, NamedQueryField> fieldMap, Long tenantId) {
        StringBuilder sql = new StringBuilder("SELECT ");

        List<String> selectClauses = new ArrayList<>();

        // Add dimensions to SELECT (mapped via columnExpr)
        if (request.getDimensions() != null && !request.getDimensions().isEmpty()) {
            for (String dim : request.getDimensions()) {
                NamedQueryField field = fieldMap.get(dim);
                selectClauses.add(field.getColumnExpr() + " AS " + dim);
            }
        }

        // Add metrics to SELECT (mapped via columnExpr)
        if (request.getMetrics() != null) {
            for (MetricConfig metric : request.getMetrics()) {
                NamedQueryField field = fieldMap.get(metric.getField());
                String alias = metric.getAlias() != null ? metric.getAlias()
                        : metric.getField() + "_" + metric.getAggregation().toLowerCase();
                String agg = metric.getAggregation().toLowerCase();
                String colExpr = field.getColumnExpr();
                String aggClause = switch (agg) {
                    case "count" -> "COUNT(" + colExpr + ")";
                    case "count_distinct" -> "COUNT(DISTINCT " + colExpr + ")";
                    case "sum" -> "SUM(" + colExpr + ")";
                    case "avg" -> "AVG(" + colExpr + ")";
                    case "max" -> "MAX(" + colExpr + ")";
                    case "min" -> "MIN(" + colExpr + ")";
                    default -> throw new MetaServiceException("Unsupported aggregation: " + agg);
                };
                selectClauses.add(aggClause + " AS " + alias);
            }
        }

        if (selectClauses.isEmpty()) {
            // Identity passthrough — return the named query's full output as-is
            sql.append("*");
        } else {
            sql.append(String.join(", ", selectClauses));
        }

        // FROM clause: use Named Query's fromSql as subquery
        // Tenant isolation is handled by MyBatis tenant interceptor or fromSql definition
        String fromSql = query.getFromSql().trim();
        if (fromSql.startsWith("(")) {
            // fromSql is already a subquery — use directly with alias to avoid double-wrapping
            // Strip any existing alias (e.g., "(SELECT ...) old_alias" → "(SELECT ...)")
            int lastParen = fromSql.lastIndexOf(')');
            if (lastParen >= 0 && lastParen < fromSql.length() - 1) {
                fromSql = fromSql.substring(0, lastParen + 1);
            }
            sql.append(" FROM ").append(fromSql).append(" AS _nq");
        } else if (fromSql.toUpperCase().startsWith("SELECT")) {
            // fromSql is a full SELECT statement — wrap as subquery
            sql.append(" FROM (").append(fromSql).append(") AS _nq");
        } else {
            sql.append(" FROM (SELECT * FROM ").append(fromSql).append(") AS _nq");
        }

        // WHERE clause on the outer query (filters mapped to columnExpr)
        List<String> whereClauses = new ArrayList<>();

        if (request.getFilters() != null) {
            for (int i = 0; i < request.getFilters().size(); i++) {
                AggregateQueryRequest.FilterConfig filter = request.getFilters().get(i);
                NamedQueryField fieldDef = fieldMap.get(filter.getField());
                String whereClause = buildNamedQueryFilterClause(fieldDef.getColumnExpr(),
                        filter, "nqf_" + i);
                if (whereClause != null) {
                    whereClauses.add(whereClause);
                }
            }
        }

        if (request.getDrillFilters() != null) {
            for (int i = 0; i < request.getDrillFilters().size(); i++) {
                AggregateQueryRequest.FilterConfig filter = request.getDrillFilters().get(i);
                NamedQueryField fieldDef = fieldMap.get(filter.getField());
                String whereClause = buildNamedQueryFilterClause(fieldDef.getColumnExpr(),
                        filter, "nqdf_" + i);
                if (whereClause != null) {
                    whereClauses.add(whereClause);
                }
            }
        }

        if (!whereClauses.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", whereClauses));
        }

        // GROUP BY clause (mapped to columnExpr)
        List<String> groupByFields = request.getGroupBy();
        if (groupByFields == null || groupByFields.isEmpty()) {
            groupByFields = request.getDimensions();
        }
        if (groupByFields != null && !groupByFields.isEmpty()) {
            List<String> groupByCols = groupByFields.stream()
                    .map(f -> fieldMap.get(f).getColumnExpr())
                    .collect(Collectors.toList());
            sql.append(" GROUP BY ").append(String.join(", ", groupByCols));
        }

        // ORDER BY clause
        if (request.getOrderBy() != null && !request.getOrderBy().isEmpty()) {
            List<String> orderClauses = request.getOrderBy().stream()
                    .map(o -> {
                        String direction = "desc".equalsIgnoreCase(o.getDirection()) ? "desc" : "asc";
                        // Order by alias (metric alias) or columnExpr (dimension)
                        if (fieldMap.containsKey(o.getField())) {
                            return fieldMap.get(o.getField()).getColumnExpr() + " " + direction;
                        }
                        // Could be a metric alias - use as-is after validation
                        if (IDENTIFIER_PATTERN.matcher(o.getField()).matches()) {
                            return o.getField() + " " + direction;
                        }
                        throw new MetaServiceException("Invalid order by field: " + o.getField());
                    })
                    .collect(Collectors.toList());
            sql.append(" ORDER BY ").append(String.join(", ", orderClauses));
        }

        // LIMIT clause
        if (request.getLimit() != null && request.getLimit() > 0) {
            sql.append(" LIMIT ").append(request.getLimit());
        }

        return sql.toString();
    }

    /**
     * Build filter clause for named query using columnExpr instead of raw field names.
     */
    private String buildNamedQueryFilterClause(String columnExpr,
                                               AggregateQueryRequest.FilterConfig filter,
                                               String paramKey) {
        if (filter == null || filter.getField() == null) {
            return null;
        }

        String operator = filter.getOperator() != null ? filter.getOperator().toLowerCase() : "eq";
        if (!SUPPORTED_OPERATORS.contains(operator)) {
            throw new MetaServiceException("Unsupported filter operator: " + operator);
        }

        return switch (operator) {
            case "eq" -> columnExpr + " = #{params." + paramKey + "}";
            case "ne", "neq" -> columnExpr + " != #{params." + paramKey + "}";
            case "gt" -> columnExpr + " > #{params." + paramKey + "}";
            case "gte", "ge" -> columnExpr + " >= #{params." + paramKey + "}";
            case "lt" -> columnExpr + " < #{params." + paramKey + "}";
            case "lte", "le" -> columnExpr + " <= #{params." + paramKey + "}";
            case "like" -> columnExpr + " LIKE #{params." + paramKey + "}";
            case "in" -> columnExpr + " IN (#{params." + paramKey + "})";
            case "not_in" -> columnExpr + " NOT IN (#{params." + paramKey + "})";
            case "is_null" -> columnExpr + " IS NULL";
            case "is_not_null" -> columnExpr + " IS NOT NULL";
            default -> columnExpr + " = #{params." + paramKey + "}";
        };
    }

    /**
     * Build query parameters for named query filters.
     */
    private Map<String, Object> buildNamedQueryParams(AggregateQueryRequest request,
                                                      Map<String, NamedQueryField> fieldMap) {
        Map<String, Object> params = new HashMap<>();

        if (request.getFilters() != null) {
            for (int i = 0; i < request.getFilters().size(); i++) {
                AggregateQueryRequest.FilterConfig filter = request.getFilters().get(i);
                if (filter.getValue() != null) {
                    params.put("nqf_" + i, prepareFilterValue(filter));
                }
            }
        }

        if (request.getDrillFilters() != null) {
            for (int i = 0; i < request.getDrillFilters().size(); i++) {
                AggregateQueryRequest.FilterConfig filter = request.getDrillFilters().get(i);
                if (filter.getValue() != null) {
                    params.put("nqdf_" + i, prepareFilterValue(filter));
                }
            }
        }

        if (request.getParameters() != null) {
            params.putAll(request.getParameters());
        }

        return params;
    }

    /**
     * Validate the aggregate query request.
     */
    private void validateRequest(AggregateQueryRequest request) {
        if (request == null) {
            throw new MetaServiceException("Aggregate query request cannot be null");
        }

        if ("namedQuery".equals(request.getType())) {
            if (request.getQueryCode() == null || request.getQueryCode().isBlank()) {
                throw new MetaServiceException("Query code is required for named queries");
            }
        } else {
            if (request.getModelCode() == null || request.getModelCode().isBlank()) {
                throw new MetaServiceException("Model code is required for aggregate queries");
            }
            if (!IDENTIFIER_PATTERN.matcher(request.getModelCode()).matches()) {
                throw new MetaServiceException("Invalid model code format: " + request.getModelCode());
            }
        }

        // Validate metrics
        if (request.getMetrics() != null) {
            for (MetricConfig metric : request.getMetrics()) {
                validateMetric(metric);
            }
        }

        // Validate dimensions
        if (request.getDimensions() != null) {
            for (String dimension : request.getDimensions()) {
                if (!IDENTIFIER_PATTERN.matcher(dimension).matches()) {
                    throw new MetaServiceException("Invalid dimension field: " + dimension);
                }
            }
        }
    }

    /**
     * Validate a metric configuration.
     */
    private void validateMetric(MetricConfig metric) {
        if (metric.getField() == null || metric.getField().isBlank()) {
            throw new MetaServiceException("Metric field is required");
        }
        if (!IDENTIFIER_PATTERN.matcher(metric.getField()).matches()) {
            throw new MetaServiceException("Invalid metric field: " + metric.getField());
        }
        if (metric.getAggregation() == null || metric.getAggregation().isBlank()) {
            throw new MetaServiceException("Metric aggregation is required");
        }
        if (!SUPPORTED_AGGREGATIONS.contains(metric.getAggregation().toLowerCase())) {
            throw new MetaServiceException("Unsupported aggregation function: " + metric.getAggregation());
        }
        if (metric.getAlias() != null && !IDENTIFIER_PATTERN.matcher(metric.getAlias()).matches()) {
            throw new MetaServiceException("Invalid metric alias: " + metric.getAlias());
        }
    }

    /**
     * Resolve the actual table name from model code using MetaModelService.
     * Falls back to SystemFieldConstants.generateTableName for dynamic models.
     */
    private String resolveTableName(String modelCode) {
        try {
            String tableName = metaModelService.getTableName(modelCode);
            if (tableName != null && !tableName.isEmpty()) {
                return tableName;
            }
        } catch (Exception e) {
            log.warn("Failed to resolve table name via MetaModelService for {}: {}", modelCode, e.getMessage());
        }
        // Fallback: system tables keep their name, dynamic models use mt_ prefix
        if (modelCode.startsWith("ns_") || modelCode.startsWith("sys_") || modelCode.startsWith("ab_")) {
            return modelCode;
        }
        return com.auraboot.framework.meta.constant.SystemFieldConstants.generateTableName(modelCode);
    }

    /**
     * Build the aggregate SQL query.
     */
    private String buildAggregateSql(AggregateQueryRequest request, String tableName) {
        StringBuilder sql = new StringBuilder("SELECT ");

        List<String> selectClauses = new ArrayList<>();

        // Add dimensions to SELECT
        if (request.getDimensions() != null && !request.getDimensions().isEmpty()) {
            selectClauses.addAll(request.getDimensions());
        }

        // Add metrics to SELECT
        if (request.getMetrics() != null) {
            for (MetricConfig metric : request.getMetrics()) {
                selectClauses.add(buildAggregationClause(metric));
            }
        }

        if (selectClauses.isEmpty()) {
            throw new MetaServiceException("At least one metric or dimension is required");
        }

        sql.append(String.join(", ", selectClauses));
        sql.append(" FROM ").append(tableName);

        // WHERE clause
        List<String> whereClauses = new ArrayList<>();
        // mt_* dynamic tables have no deleted_flag column
        if (!tableName.startsWith("mt_")) {
            whereClauses.add("deleted_flag = false");
        }

        if (request.getFilters() != null) {
            for (AggregateQueryRequest.FilterConfig filter : request.getFilters()) {
                String whereClause = buildFilterClause(filter, "f_");
                if (whereClause != null) {
                    whereClauses.add(whereClause);
                }
            }
        }

        if (request.getDrillFilters() != null) {
            for (AggregateQueryRequest.FilterConfig filter : request.getDrillFilters()) {
                String whereClause = buildFilterClause(filter, "df_");
                if (whereClause != null) {
                    whereClauses.add(whereClause);
                }
            }
        }

        if (!whereClauses.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", whereClauses));
        }

        // GROUP BY clause
        List<String> groupByFields = request.getGroupBy();
        if (groupByFields == null || groupByFields.isEmpty()) {
            groupByFields = request.getDimensions();
        }

        if (groupByFields != null && !groupByFields.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", groupByFields));
        }

        // ORDER BY clause
        if (request.getOrderBy() != null && !request.getOrderBy().isEmpty()) {
            List<String> orderClauses = request.getOrderBy().stream()
                    .map(o -> {
                        String field = o.getField();
                        String direction = "desc".equalsIgnoreCase(o.getDirection()) ? "desc" : "asc";
                        return field + " " + direction;
                    })
                    .collect(Collectors.toList());
            sql.append(" ORDER BY ").append(String.join(", ", orderClauses));
        }

        // LIMIT clause
        if (request.getLimit() != null && request.getLimit() > 0) {
            sql.append(" LIMIT ").append(request.getLimit());
        }

        return sql.toString();
    }

    /**
     * Build an aggregation clause for a metric.
     */
    private String buildAggregationClause(MetricConfig metric) {
        String agg = metric.getAggregation().toLowerCase();
        String field = metric.getField();
        String alias = metric.getAlias() != null ? metric.getAlias() : field + "_" + agg.toLowerCase();

        return switch (agg) {
            case "count" -> "COUNT(" + field + ") AS " + alias;
            case "count_distinct" -> "COUNT(DISTINCT " + field + ") AS " + alias;
            case "sum" -> "SUM(" + field + ") AS " + alias;
            case "avg" -> "AVG(" + field + ") AS " + alias;
            case "max" -> "MAX(" + field + ") AS " + alias;
            case "min" -> "MIN(" + field + ") AS " + alias;
            default -> throw new MetaServiceException("Unsupported aggregation: " + agg);
        };
    }

    /**
     * Build a filter clause with parameterized values.
     */
    private String buildFilterClause(AggregateQueryRequest.FilterConfig filter, String prefix) {
        if (filter == null || filter.getField() == null) {
            return null;
        }

        String field = filter.getField();
        if (!IDENTIFIER_PATTERN.matcher(field).matches()) {
            throw new MetaServiceException("Invalid filter field: " + field);
        }

        String operator = filter.getOperator() != null ? filter.getOperator().toLowerCase() : "eq";
        if (!SUPPORTED_OPERATORS.contains(operator)) {
            throw new MetaServiceException("Unsupported filter operator: " + operator);
        }

        String paramKey = prefix + field;

        return switch (operator) {
            case "eq" -> field + " = #{params." + paramKey + "}";
            case "ne", "neq" -> field + " != #{params." + paramKey + "}";
            case "gt" -> field + " > #{params." + paramKey + "}";
            case "gte", "ge" -> field + " >= #{params." + paramKey + "}";
            case "lt" -> field + " < #{params." + paramKey + "}";
            case "lte", "le" -> field + " <= #{params." + paramKey + "}";
            case "like" -> field + " LIKE #{params." + paramKey + "}";
            case "in" -> field + " IN (#{params." + paramKey + "})";
            case "not_in" -> field + " NOT IN (#{params." + paramKey + "})";
            case "is_null" -> field + " IS NULL";
            case "is_not_null" -> field + " IS NOT NULL";
            default -> field + " = #{params." + paramKey + "}";
        };
    }

    /**
     * Build query parameters from the request.
     */
    private Map<String, Object> buildParams(AggregateQueryRequest request) {
        Map<String, Object> params = new HashMap<>();

        if (request.getFilters() != null) {
            for (AggregateQueryRequest.FilterConfig filter : request.getFilters()) {
                if (filter.getValue() != null) {
                    String paramKey = "f_" + filter.getField();
                    params.put(paramKey, prepareFilterValue(filter));
                }
            }
        }

        if (request.getDrillFilters() != null) {
            for (AggregateQueryRequest.FilterConfig filter : request.getDrillFilters()) {
                if (filter.getValue() != null) {
                    String paramKey = "df_" + filter.getField();
                    params.put(paramKey, prepareFilterValue(filter));
                }
            }
        }

        if (request.getParameters() != null) {
            params.putAll(request.getParameters());
        }

        return params;
    }

    /**
     * Prepare filter value based on operator.
     */
    private Object prepareFilterValue(AggregateQueryRequest.FilterConfig filter) {
        Object value = filter.getValue();
        String operator = filter.getOperator() != null ? filter.getOperator().toLowerCase() : "eq";

        if ("like".equals(operator) && value instanceof String) {
            return "%" + value + "%";
        }

        return value;
    }

    /**
     * Build query metadata for a named query request.
     * When the request has no explicit dimensions or metrics (identity passthrough),
     * meta.metrics is derived from the named query's outputFields whitelist.
     */
    private AggregateQueryResponse.QueryMeta buildMetaForNamedQuery(
            AggregateQueryRequest request,
            NamedQuery query,
            Map<String, NamedQueryField> fieldMap) {
        boolean hasExplicitProjection =
                (request.getDimensions() != null && !request.getDimensions().isEmpty())
                || (request.getMetrics() != null && !request.getMetrics().isEmpty());
        if (hasExplicitProjection) {
            return buildMeta(request);
        }
        // Identity passthrough — meta.metrics derives from named query whitelist
        AggregateQueryResponse.QueryMeta meta = new AggregateQueryResponse.QueryMeta();
        meta.setDimensions(java.util.Collections.emptyList());
        List<String> fieldCodes = fieldMap.values().stream()
                .map(NamedQueryField::getFieldCode)
                .sorted()
                .collect(Collectors.toList());
        meta.setMetrics(fieldCodes);
        return meta;
    }

    /**
     * Build query metadata from the request.
     */
    private AggregateQueryResponse.QueryMeta buildMeta(AggregateQueryRequest request) {
        AggregateQueryResponse.QueryMeta meta = new AggregateQueryResponse.QueryMeta();
        meta.setDimensions(request.getDimensions());

        if (request.getMetrics() != null) {
            List<String> metricNames = request.getMetrics().stream()
                    .map(m -> m.getAlias() != null ? m.getAlias() : m.getField() + "_" + m.getAggregation().toLowerCase())
                    .collect(Collectors.toList());
            meta.setMetrics(metricNames);
        }

        return meta;
    }

    /**
     * Calculate summary statistics from the result rows.
     */
    private Map<String, Object> calculateSummary(List<Map<String, Object>> rows, List<MetricConfig> metrics) {
        Map<String, Object> summary = new HashMap<>();

        if (metrics == null || rows == null || rows.isEmpty()) {
            return summary;
        }

        for (MetricConfig metric : metrics) {
            String key = metric.getAlias() != null ? metric.getAlias() : metric.getField() + "_" + metric.getAggregation().toLowerCase();

            double total = rows.stream()
                    .map(row -> row.get(key))
                    .filter(Objects::nonNull)
                    .mapToDouble(v -> {
                        if (v instanceof Number) {
                            return ((Number) v).doubleValue();
                        }
                        return 0.0;
                    })
                    .sum();

            summary.put(key, total);
        }

        return summary;
    }
}

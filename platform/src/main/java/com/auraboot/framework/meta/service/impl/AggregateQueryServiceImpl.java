package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.dto.AggregateQueryResponse;
import com.auraboot.framework.meta.dto.MetricConfig;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.NamedQueryFieldMapper;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.meta.service.AggregateQueryService;
import com.auraboot.framework.meta.service.DataDomainService;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.base.BaseMetaService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
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
    private final DataPermissionEngine dataPermissionEngine;
    private final DataDomainService dataDomainService;

    /**
     * Optional — when present and the request carries a {@code semanticModelCode},
     * delegate to the semantic layer (PRD 16 §6 W4 D4). Wired via field injection
     * with {@code required=false} so environments without the semantic stack still
     * boot cleanly (canonical Spring 6 single-ctor rule from
     * engineering-gotchas — use field injection, not ctor, for the optional dep).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SemanticAggregateAdapter semanticAggregateAdapter;

    /**
     * Valid identifier pattern for SQL identifiers (table names, column names, aliases).
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /**
     * Time-bucketing grains a dimension may request via a {@code col__grain} suffix
     * (e.g. {@code crm_opp_expected_close_date__month}). Mirrors the semantic layer's
     * {@code ALLOWED_GRAIN} so the two query paths bucket time identically.
     */
    private static final Set<String> ALLOWED_GRAINS = Set.of("day", "week", "month", "quarter", "year");

    /**
     * Supported aggregation functions.
     */
    private static final Set<String> SUPPORTED_AGGREGATIONS = Set.of(
            "count", "count_distinct", "sum", "avg", "max", "min"
    );

    /**
     * Supported filter operators.
     *
     * <p>{@code relative} is a range operator whose {@code value} carries a relative-time
     * token (or {@code {relative,n}} object); the server resolves it into a concrete
     * half-open {@code [start, end)} date range and binds both bounds as parameters.
     */
    private static final Set<String> SUPPORTED_OPERATORS = Set.of(
            "eq", "ne", "neq", "gt", "gte", "ge", "lt", "lte", "le", "like", "in", "not_in",
            "is_null", "is_not_null", "relative"
    );

    /**
     * Maximum nesting depth of a filter group tree. Guards against pathological or abusive
     * deeply-nested payloads (stack safety); the top-level list itself is depth 0.
     */
    private static final int MAX_FILTER_DEPTH = 10;

    /**
     * Clock used to resolve relative-time tokens (e.g. {@code this_month}). Package-private and
     * non-final so tests can pin "now" via {@code ReflectionTestUtils.setField(service, "clock", fixed)};
     * production uses the system clock in the JVM default zone. Not a Lombok-generated ctor arg
     * (only final fields are), so it does not affect constructor / {@code @InjectMocks} wiring.
     */
    Clock clock = Clock.systemDefaultZone();

    private static final Set<String> PUBLIC_FORBIDDEN_OUTPUT_ALIASES = Set.of(
            "id", "record_id", "tenant_id", "created_by", "updated_by"
    );

    @Override
    @Transactional(readOnly = true)
    @Cacheable(
            value = "aggregateQuery",
            key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getDataAccessContextSuffix() + ':' + #request.hashCode()",
            unless = "#result == null || #result.getRows() == null || #result.getRows().isEmpty()"
    )
    public AggregateQueryResponse execute(AggregateQueryRequest request) {
        validateRequest(request);

        // Semantic-routed path: if the caller declared a semanticModelCode AND the
        // adapter is available, delegate the entire request through the governed
        // semantic layer (PRD 16 §6 W4 D4). Bit-identical legacy behaviour
        // preserved when either is absent — existing widgets do not regress.
        if (request.getSemanticModelCode() != null
                && !request.getSemanticModelCode().isBlank()
                && semanticAggregateAdapter != null) {
            return semanticAggregateAdapter.execute(request);
        }

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
        List<String> accessClauses = buildDataAccessClauses(request.getModelCode());
        // Filter (incl. nested OR groups + relative-time) params are bound during SQL build
        // so the WHERE tree and its parameter keys are produced in a single, in-sync pass.
        Map<String, Object> params = new HashMap<>();
        String sql = buildAggregateSql(request, tableName, accessClauses, params);
        if (request.getParameters() != null) {
            params.putAll(request.getParameters());
        }

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
        validatePublicOutputAliases(fields);
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

        Long currentUserId = getCurrentUserId();
        List<String> accessClauses = buildNamedQueryDataAccessClauses(query, tenantId, currentUserId);

        // 6. Build SQL. Filter (incl. nested OR groups + relative-time) params are bound during
        // the build so the WHERE tree and its parameter keys stay in a single in-sync pass.
        Map<String, Object> params = new HashMap<>();
        String sql = buildNamedQuerySql(request, query, fieldMap, tenantId, accessClauses, params);

        // User-supplied named-query parameters may override generated filter keys (unchanged precedence).
        if (request.getParameters() != null) {
            params.putAll(request.getParameters());
        }
        // Inject tenantId for fromSql that uses #{params.tenantId} for tenant isolation.
        params.put("tenantId", tenantId);
        // Inject currentUserId so user-scoped named queries (#{params.currentUserId}) — e.g.
        // "my commission" / team-by-manager dashboards — return the same rows when rendered as
        // a chart (this path) as they do on the /api/datasource/list card/table path. Without
        // it the WHERE clause matches nothing and the chart shows an empty state. Mirrors
        // NamedQueryServiceImpl (the datasource/list executor).
        params.put("currentUserId", currentUserId != null ? currentUserId.toString() : null);

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
     * Recurses into nested AND/OR group nodes so every leaf in the tree is whitelisted.
     */
    private void validateNamedQueryFilters(List<AggregateQueryRequest.FilterConfig> filters,
                                           Map<String, NamedQueryField> fieldMap) {
        if (filters == null) return;
        for (AggregateQueryRequest.FilterConfig filter : filters) {
            validateNamedQueryFilterNode(filter, fieldMap, 0);
        }
    }

    private void validateNamedQueryFilterNode(AggregateQueryRequest.FilterConfig node,
                                              Map<String, NamedQueryField> fieldMap, int depth) {
        if (node == null) return;
        if (isGroup(node)) {
            requireGroupInvariants(node, depth);
            groupJoiner(node.getLogic()); // validates logic ∈ {and, or}
            for (AggregateQueryRequest.FilterConfig child : node.getChildren()) {
                validateNamedQueryFilterNode(child, fieldMap, depth + 1);
            }
            return;
        }
        if (node.getField() == null) return; // empty leaf — ignored, matches SQL-build behaviour
        NamedQueryField fieldDef = fieldMap.get(node.getField());
        if (fieldDef == null) {
            throw new MetaServiceException("Filter field not in whitelist: " + node.getField());
        }
        String operator = node.getOperator() != null ? node.getOperator().toLowerCase(Locale.ROOT) : "eq";
        if (fieldDef.hasOperators() && !fieldDef.supportsOperator(operator)) {
            throw new MetaServiceException(
                    "Operator not allowed for field " + node.getField() + ": " + operator);
        }
    }

    /**
     * Build SQL for named query aggregation.
     * Uses the Named Query's fromSql as a subquery, with aggregation on top.
     */
    private String buildNamedQuerySql(AggregateQueryRequest request, NamedQuery query,
                                      Map<String, NamedQueryField> fieldMap, Long tenantId,
                                      List<String> accessClauses, Map<String, Object> params) {
        // columnExpr values come from the stored NamedQuery definition and are composed into
        // SELECT / WHERE / GROUP BY. The NamedQuery read path (NamedQueryServiceImpl) already
        // runs validateSqlFragment on them; apply the same blacklist check here so both SQL
        // composition paths are aligned (SEC-20260723-10).
        for (NamedQueryField field : fieldMap.values()) {
            if (field != null && field.getColumnExpr() != null) {
                SqlSafetyUtils.validateSqlFragment(field.getColumnExpr());
            }
        }

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
                validatePublicOutputAlias(alias);
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

        // WHERE clause on the outer query (filters mapped to columnExpr). Supports nested AND/OR
        // groups and relative-time ranges; each leaf binds via a unique #{params.nqf_N} key.
        List<String> whereClauses = new ArrayList<>();
        int[] paramCounter = {0};

        if (request.getFilters() != null) {
            for (AggregateQueryRequest.FilterConfig filter : request.getFilters()) {
                String whereClause = compileNamedQueryFilter(filter, fieldMap, "nqf", paramCounter, params, 0);
                if (whereClause != null) {
                    whereClauses.add(whereClause);
                }
            }
        }

        if (request.getDrillFilters() != null) {
            for (AggregateQueryRequest.FilterConfig filter : request.getDrillFilters()) {
                String whereClause = compileNamedQueryFilter(filter, fieldMap, "nqdf", paramCounter, params, 0);
                if (whereClause != null) {
                    whereClauses.add(whereClause);
                }
            }
        }

        if (accessClauses != null && !accessClauses.isEmpty()) {
            whereClauses.addAll(accessClauses);
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

    private void validatePublicOutputAliases(List<NamedQueryField> fields) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        for (NamedQueryField field : fields) {
            validatePublicOutputAlias(field.getFieldCode());
        }
    }

    private void validatePublicOutputAlias(String alias) {
        if (alias == null) {
            return;
        }
        String normalized = alias.trim().toLowerCase(Locale.ROOT);
        if (PUBLIC_FORBIDDEN_OUTPUT_ALIASES.contains(normalized)) {
            throw new MetaServiceException("NamedQuery public output alias is reserved for internal identity: " + alias);
        }
    }

    /**
     * Compile a named-query filter (sub)tree into a SQL boolean expression, mapping each leaf's
     * field to its whitelisted {@code columnExpr} and binding leaf values into {@code params}.
     * Groups are wrapped in parentheses and combined with their {@code logic} (AND/OR).
     */
    private String compileNamedQueryFilter(AggregateQueryRequest.FilterConfig node,
                                           Map<String, NamedQueryField> fieldMap,
                                           String prefix, int[] counter,
                                           Map<String, Object> params, int depth) {
        if (node == null) {
            return null;
        }
        if (isGroup(node)) {
            requireGroupInvariants(node, depth);
            List<String> parts = new ArrayList<>();
            for (AggregateQueryRequest.FilterConfig child : node.getChildren()) {
                String frag = compileNamedQueryFilter(child, fieldMap, prefix, counter, params, depth + 1);
                if (frag != null) {
                    parts.add(frag);
                }
            }
            if (parts.isEmpty()) {
                return null;
            }
            return "(" + String.join(groupJoiner(node.getLogic()), parts) + ")";
        }
        // Leaf — column comes from the field whitelist (already validated in validateNamedQueryFilters).
        if (node.getField() == null) {
            return null;
        }
        NamedQueryField fieldDef = fieldMap.get(node.getField());
        if (fieldDef == null) {
            throw new MetaServiceException("Filter field not in whitelist: " + node.getField());
        }
        return emitLeafPredicate(fieldDef.getColumnExpr(), node, prefix, counter, params);
    }

    // ==================== Shared filter-tree compilation (both query paths) ====================

    /** A node is a group iff it carries child filters; otherwise it is a leaf. */
    private boolean isGroup(AggregateQueryRequest.FilterConfig node) {
        return node.getChildren() != null && !node.getChildren().isEmpty();
    }

    /** Reject ambiguous group nodes and over-deep nesting before descending. */
    private void requireGroupInvariants(AggregateQueryRequest.FilterConfig node, int depth) {
        if (node.getField() != null) {
            throw new MetaServiceException(
                    "Filter node cannot be both a leaf (field) and a group (children): " + node.getField());
        }
        if (depth >= MAX_FILTER_DEPTH) {
            throw new MetaServiceException("Filter nesting exceeds max depth " + MAX_FILTER_DEPTH);
        }
    }

    /** Map a group's {@code logic} to a SQL joiner, validating it is exactly {@code and} or {@code or}. */
    private String groupJoiner(String logic) {
        String normalized = logic == null ? "and" : logic.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "and" -> " AND ";
            case "or" -> " OR ";
            default -> throw new MetaServiceException(
                    "Invalid filter group logic (expected 'and' or 'or'): " + logic);
        };
    }

    /**
     * Emit a single leaf predicate ({@code columnExpr <op> #{params.key}}) and bind its value.
     * All comparison values travel as bound parameters — never concatenated into SQL — so a
     * malicious value cannot alter query structure. {@code columnExpr} is a whitelisted column
     * (aggregate path: validated bare identifier; namedQuery path: whitelist {@code column_expr}).
     */
    private String emitLeafPredicate(String columnExpr, AggregateQueryRequest.FilterConfig filter,
                                     String prefix, int[] counter, Map<String, Object> params) {
        String operator = filter.getOperator() != null
                ? filter.getOperator().toLowerCase(Locale.ROOT) : "eq";
        if (!SUPPORTED_OPERATORS.contains(operator)) {
            throw new MetaServiceException("Unsupported filter operator: " + operator);
        }
        int idx = counter[0]++;

        if ("relative".equals(operator)) {
            return emitRelativeRange(columnExpr, filter.getValue(), prefix, idx, params);
        }
        if ("in".equals(operator) || "not_in".equals(operator)) {
            // IN / NOT IN expand each list element into its own bound placeholder. A single
            // #{} placeholder would bind the whole List to one JDBC parameter, which PostgreSQL
            // rejects (MyBatis #{} does not expand collections — only <foreach> does).
            return emitInListPredicate(columnExpr, operator, filter.getValue(), prefix, idx, params);
        }

        String key = prefix + "_" + idx;
        String placeholder = "#{params." + key + "}";
        String sql = switch (operator) {
            case "eq" -> columnExpr + " = " + placeholder;
            case "ne", "neq" -> columnExpr + " != " + placeholder;
            case "gt" -> columnExpr + " > " + placeholder;
            case "gte", "ge" -> columnExpr + " >= " + placeholder;
            case "lt" -> columnExpr + " < " + placeholder;
            case "lte", "le" -> columnExpr + " <= " + placeholder;
            case "like" -> columnExpr + " LIKE " + placeholder;
            case "is_null" -> columnExpr + " IS NULL";
            case "is_not_null" -> columnExpr + " IS NOT NULL";
            default -> columnExpr + " = " + placeholder;
        };
        // Null-checks take no value; every other operator binds one (null-safe: a missing value
        // binds SQL NULL, preserving prior behaviour).
        if (!"is_null".equals(operator) && !"is_not_null".equals(operator)) {
            params.put(key, prepareFilterValue(operator, filter.getValue()));
        }
        return sql;
    }

    /**
     * Emit an {@code IN} / {@code NOT IN} predicate, expanding each element of the list value into
     * its own bound placeholder ({@code col IN (#{k_0}, #{k_1}, ...)}). A single placeholder would
     * bind the whole {@link java.util.Collection} to one JDBC parameter — PostgreSQL rejects that,
     * and MyBatis {@code #{}} never expands collections. Values stay bound (never concatenated).
     * An empty list degenerates to a constant: {@code IN ()} is invalid SQL, so an empty
     * {@code in} matches nothing and an empty {@code not_in} matches everything.
     */
    private String emitInListPredicate(String columnExpr, String operator, Object value,
                                       String prefix, int idx, Map<String, Object> params) {
        boolean negate = "not_in".equals(operator);
        List<Object> items = toValueList(value);
        if (items.isEmpty()) {
            return negate ? "1 = 1" : "1 = 0";
        }
        List<String> placeholders = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            String key = prefix + "_" + idx + "_" + i;
            params.put(key, items.get(i));
            placeholders.add("#{params." + key + "}");
        }
        return columnExpr + (negate ? " NOT IN (" : " IN (")
                + String.join(", ", placeholders) + ")";
    }

    /** Normalise an {@code in}/{@code not_in} value to a list: a Collection/array as-is, a scalar as a singleton. */
    private List<Object> toValueList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> c) {
            return new ArrayList<>(c);
        }
        if (value instanceof Object[] arr) {
            return Arrays.asList(arr);
        }
        return List.of(value);
    }

    /**
     * Emit a relative-time range predicate, binding the resolved {@code [start, end)} bounds as
     * parameters. Produces {@code (columnExpr >= #{lo} AND columnExpr < #{hi})} — parenthesised so
     * it composes correctly inside OR groups. Bounds are {@link LocalDate}; PostgreSQL compares
     * them correctly against both {@code date} and {@code timestamp} columns.
     */
    private String emitRelativeRange(String columnExpr, Object spec, String prefix, int idx,
                                     Map<String, Object> params) {
        LocalDate[] range = resolveRelativeRange(spec);
        String loKey = prefix + "_" + idx + "_lo";
        String hiKey = prefix + "_" + idx + "_hi";
        params.put(loKey, range[0]);
        params.put(hiKey, range[1]);
        return "(" + columnExpr + " >= #{params." + loKey + "}"
                + " AND " + columnExpr + " < #{params." + hiKey + "})";
    }

    /**
     * Resolve a relative-time spec into a concrete half-open {@code [startInclusive, endExclusive)}
     * date range, evaluated against {@link #clock}. Accepts a token string (e.g. {@code this_month})
     * or an object {@code {"relative":"last_n_days","n":30}}. Windows use whole calendar days;
     * {@code last_n_days} is the trailing {@code n} days ending today inclusive.
     */
    private LocalDate[] resolveRelativeRange(Object spec) {
        String token;
        Integer n = null;
        if (spec instanceof Map<?, ?> map) {
            Object t = map.get("relative");
            token = t != null ? t.toString() : null;
            Object rawN = map.get("n");
            if (rawN instanceof Number num) {
                n = num.intValue();
            } else if (rawN != null) {
                try {
                    n = Integer.parseInt(rawN.toString().trim());
                } catch (NumberFormatException e) {
                    throw new MetaServiceException("Relative time 'n' must be an integer: " + rawN);
                }
            }
        } else if (spec instanceof String s) {
            token = s;
        } else {
            throw new MetaServiceException(
                    "Relative time value must be a token string or {relative, n} object");
        }
        if (token == null || token.isBlank()) {
            throw new MetaServiceException("Relative time token is required");
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        LocalDate today = LocalDate.now(clock);
        return switch (normalized) {
            case "today" -> new LocalDate[]{today, today.plusDays(1)};
            case "yesterday" -> new LocalDate[]{today.minusDays(1), today};
            case "last_7_days" -> lastNDays(today, 7);
            case "last_30_days" -> lastNDays(today, 30);
            case "last_n_days" -> {
                if (n == null || n <= 0) {
                    throw new MetaServiceException("Relative time 'last_n_days' requires a positive 'n'");
                }
                yield lastNDays(today, n);
            }
            case "this_week" -> {
                LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new LocalDate[]{weekStart, weekStart.plusWeeks(1)};
            }
            case "this_month" -> {
                LocalDate monthStart = today.withDayOfMonth(1);
                yield new LocalDate[]{monthStart, monthStart.plusMonths(1)};
            }
            case "this_quarter" -> {
                int firstMonthOfQuarter = ((today.getMonthValue() - 1) / 3) * 3 + 1;
                LocalDate quarterStart = LocalDate.of(today.getYear(), firstMonthOfQuarter, 1);
                yield new LocalDate[]{quarterStart, quarterStart.plusMonths(3)};
            }
            case "this_year" -> {
                LocalDate yearStart = today.withDayOfYear(1);
                yield new LocalDate[]{yearStart, yearStart.plusYears(1)};
            }
            default -> throw new MetaServiceException("Unsupported relative time token: " + token);
        };
    }

    /** Trailing {@code n} calendar days ending today inclusive: {@code [today-(n-1), today+1)}. */
    private LocalDate[] lastNDays(LocalDate today, int n) {
        return new LocalDate[]{today.minusDays(n - 1L), today.plusDays(1)};
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

        // Validate dimensions — a dimension may carry a `col__grain` time-bucketing
        // suffix (validated inside resolveDimension), otherwise it must be a bare column.
        if (request.getDimensions() != null) {
            for (String dimension : request.getDimensions()) {
                resolveDimension(dimension);
            }
        }

        // Validate groupBy / orderBy — these were previously unchecked and
        // concatenated straight into the SQL, so a caller could inject through
        // either. Every referenced field must be a bare identifier; orderBy fields
        // may also be a declared metric alias (ordering by an aggregate column).
        // The namedQuery path already guards these; the aggregate path did not.
        if (request.getGroupBy() != null) {
            for (String field : request.getGroupBy()) {
                if (!IDENTIFIER_PATTERN.matcher(field).matches()) {
                    throw new MetaServiceException("Invalid group by field: " + field);
                }
            }
        }
        if (request.getOrderBy() != null) {
            Set<String> metricAliases = collectMetricAliases(request);
            for (AggregateQueryRequest.OrderByConfig order : request.getOrderBy()) {
                String field = order.getField();
                if (field == null || (!IDENTIFIER_PATTERN.matcher(field).matches()
                        && !metricAliases.contains(field))) {
                    throw new MetaServiceException("Invalid order by field: " + field);
                }
            }
        }
    }

    /**
     * A dimension resolved into its SQL SELECT expression and the output column it
     * lands in. For a plain dimension the two are the same bare column; for a
     * time-bucketed one ({@code col__month}) the expression is a {@code DATE_TRUNC}
     * and the column is the suffixed alias, so GROUP BY / ORDER BY can reference it.
     */
    private record ResolvedDimension(String selectExpr, String outputColumn) {
        boolean isBucketed() {
            return !selectExpr.equals(outputColumn);
        }
    }

    /**
     * Parse a dimension, honouring an optional {@code col__grain} time-bucketing suffix.
     *
     * Without a suffix the dimension must be a bare identifier (unchanged behaviour).
     * With one, the base column must be a bare identifier and the grain must be in
     * {@link #ALLOWED_GRAINS}; the result buckets via {@code DATE_TRUNC(grain, col)}
     * aliased to {@code col__grain}. This is the aggregate-path equivalent of the
     * semantic layer's grain handling — the aggregate DTO carries no field metadata,
     * so the grain travels in the dimension string itself.
     */
    private ResolvedDimension resolveDimension(String dimension) {
        if (dimension == null) {
            throw new MetaServiceException("Dimension field is required");
        }
        int sep = dimension.indexOf("__");
        if (sep < 0) {
            if (!IDENTIFIER_PATTERN.matcher(dimension).matches()) {
                throw new MetaServiceException("Invalid dimension field: " + dimension);
            }
            return new ResolvedDimension(dimension, dimension);
        }
        String column = dimension.substring(0, sep);
        String grain = dimension.substring(sep + 2).toLowerCase(Locale.ROOT);
        if (!IDENTIFIER_PATTERN.matcher(column).matches()) {
            throw new MetaServiceException("Invalid dimension field: " + dimension);
        }
        if (!ALLOWED_GRAINS.contains(grain)) {
            throw new MetaServiceException("Unsupported time grain: " + grain);
        }
        // Return a formatted STRING label, not the raw DATE_TRUNC timestamp. A
        // timestamp renders in the JDBC session's zone (a +08 month boundary comes back
        // as `...-31T16:00Z`), so the frontend cannot recover the calendar month without
        // knowing the zone — and a naive `YYYY-MM` slice lands a month early. to_char on
        // the truncated value formats in the DB session zone, giving a stable label.
        // The alias keeps the `__grain` form so the frontend still recognises the column.
        String bucket = "DATE_TRUNC('" + grain + "', " + column + ")";
        String expr = "to_char(" + bucket + ", '" + grainFormat(grain) + "')";
        return new ResolvedDimension(expr, dimension);
    }

    /** to_char format for each grain, yielding a sortable, zone-stable label. */
    private String grainFormat(String grain) {
        return switch (grain) {
            case "year" -> "YYYY";
            case "quarter" -> "YYYY\"-Q\"Q";
            case "month" -> "YYYY-MM";
            case "week" -> "IYYY\"-W\"IW";
            default -> "YYYY-MM-DD";
        };
    }

    /** Aliases the request exposes for ordering: an explicit alias, else the default {@code field_agg}. */
    private Set<String> collectMetricAliases(AggregateQueryRequest request) {
        if (request.getMetrics() == null) {
            return Collections.emptySet();
        }
        Set<String> aliases = new HashSet<>();
        for (MetricConfig metric : request.getMetrics()) {
            String alias = trimToNull(metric.getAlias());
            // Mirror buildAggregationClause's default alias exactly: `field_agg`, agg lower-cased.
            aliases.add(alias != null
                    ? alias
                    : metric.getField() + "_" + metric.getAggregation().toLowerCase());
        }
        return aliases;
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
     * Dynamic model table names must come from published metadata; blindly
     * guessing mt_{modelCode} turns missing tenant/plugin models into SQL 500s.
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
        throw new MetaServiceException("Model not found or not published for aggregate query: " + modelCode);
    }

    private List<String> buildDataAccessClauses(String modelCode) {
        if (MetaContext.isDataPermissionBypassed()) {
            return Collections.emptyList();
        }

        Long tenantId = getCurrentTenantId();
        Long userId = getCurrentUserId();
        List<String> clauses = new ArrayList<>();

        try {
            appendAccessClause(clauses, dataPermissionEngine.buildRowFilter(tenantId, modelCode, userId));
        } catch (Exception e) {
            log.error("Failed to evaluate row-level access for aggregate model {} — failing closed",
                    modelCode, e);
            throw new MetaServiceException("Data permission evaluation failed for aggregate: " + modelCode, e);
        }

        try {
            appendAccessClause(clauses, dataDomainService.buildDomainFilter(modelCode, userId));
        } catch (Exception e) {
            log.error("Failed to evaluate data domain access for aggregate model {} — failing closed",
                    modelCode, e);
            throw new MetaServiceException("Data domain evaluation failed for aggregate: " + modelCode, e);
        }

        return clauses;
    }

    private List<String> buildNamedQueryDataAccessClauses(NamedQuery query, Long tenantId, Long userId) {
        if (MetaContext.isDataPermissionBypassed()) {
            return Collections.emptyList();
        }

        String resourceCode = trimToNull(query.getResourceCode());
        String actionCode = trimToNull(query.getActionCode());
        if (resourceCode == null || actionCode == null) {
            return Collections.emptyList();
        }

        List<String> clauses = new ArrayList<>();
        try {
            appendAccessClause(clauses, dataPermissionEngine.buildRowFilter(tenantId, resourceCode, actionCode, userId));
        } catch (Exception e) {
            log.error("Failed to evaluate row-level access for named query aggregate {} — failing closed",
                    query.getCode(), e);
            throw new MetaServiceException("Data permission evaluation failed for named query aggregate: "
                    + query.getCode(), e);
        }
        return clauses;
    }

    private void appendAccessClause(List<String> clauses, String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return;
        }
        String clause = fragment.trim()
                .replaceFirst("(?i)^AND\\s+", "")
                .replaceFirst("(?i)^WHERE\\s+", "")
                .trim();
        if (!clause.isBlank()) {
            clauses.add("(" + clause + ")");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Build the aggregate SQL query.
     */
    private String buildAggregateSql(AggregateQueryRequest request, String tableName,
                                     List<String> accessClauses, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder("SELECT ");

        List<String> selectClauses = new ArrayList<>();

        // Resolve dimensions once — a bucketed one (`col__grain`) selects a DATE_TRUNC
        // aliased to `col__grain`, which GROUP BY reuses below.
        List<ResolvedDimension> resolvedDimensions = new ArrayList<>();
        if (request.getDimensions() != null) {
            for (String dimension : request.getDimensions()) {
                resolvedDimensions.add(resolveDimension(dimension));
            }
        }

        // Add dimensions to SELECT
        for (ResolvedDimension dim : resolvedDimensions) {
            selectClauses.add(dim.isBucketed()
                    ? dim.selectExpr() + " AS " + dim.outputColumn()
                    : dim.selectExpr());
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

        // Filters support nested AND/OR groups and relative-time ranges; each leaf binds via a
        // unique #{params.f_N} / #{params.df_N} key generated in this same pass.
        int[] paramCounter = {0};
        if (request.getFilters() != null) {
            for (AggregateQueryRequest.FilterConfig filter : request.getFilters()) {
                String whereClause = compileAggregateFilter(filter, "f", paramCounter, params, 0);
                if (whereClause != null) {
                    whereClauses.add(whereClause);
                }
            }
        }

        if (request.getDrillFilters() != null) {
            for (AggregateQueryRequest.FilterConfig filter : request.getDrillFilters()) {
                String whereClause = compileAggregateFilter(filter, "df", paramCounter, params, 0);
                if (whereClause != null) {
                    whereClauses.add(whereClause);
                }
            }
        }

        if (accessClauses != null && !accessClauses.isEmpty()) {
            whereClauses.addAll(accessClauses);
        }

        if (!whereClauses.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", whereClauses));
        }

        // GROUP BY clause. When it defaults to the dimensions, group by the SELECT
        // expression (the DATE_TRUNC for bucketed dims), not the raw `col__grain`
        // string — that is an output alias, not a real column. An explicit groupBy is
        // a list of bare columns (validated), so it is used as-is.
        List<String> groupByClauses;
        if (request.getGroupBy() != null && !request.getGroupBy().isEmpty()) {
            groupByClauses = request.getGroupBy();
        } else {
            groupByClauses = resolvedDimensions.stream()
                    .map(ResolvedDimension::selectExpr)
                    .collect(Collectors.toList());
        }

        if (!groupByClauses.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", groupByClauses));
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
     * Compile an aggregate-path filter (sub)tree into a SQL boolean expression over bare (validated)
     * column identifiers, binding leaf values into {@code params}. Groups are wrapped in parentheses
     * and combined with their {@code logic} (AND/OR); leaves validate the field identifier so a
     * malicious field name is rejected before any SQL is composed.
     */
    private String compileAggregateFilter(AggregateQueryRequest.FilterConfig node, String prefix,
                                          int[] counter, Map<String, Object> params, int depth) {
        if (node == null) {
            return null;
        }
        if (isGroup(node)) {
            requireGroupInvariants(node, depth);
            List<String> parts = new ArrayList<>();
            for (AggregateQueryRequest.FilterConfig child : node.getChildren()) {
                String frag = compileAggregateFilter(child, prefix, counter, params, depth + 1);
                if (frag != null) {
                    parts.add(frag);
                }
            }
            if (parts.isEmpty()) {
                return null;
            }
            return "(" + String.join(groupJoiner(node.getLogic()), parts) + ")";
        }
        // Leaf — the column is the raw field, which must be a safe bare identifier.
        String field = node.getField();
        if (field == null) {
            return null;
        }
        if (!IDENTIFIER_PATTERN.matcher(field).matches()) {
            throw new MetaServiceException("Invalid filter field: " + field);
        }
        return emitLeafPredicate(field, node, prefix, counter, params);
    }

    /**
     * Prepare a leaf filter value based on operator (e.g. wrap {@code like} in {@code %...%}).
     * The returned value is always <em>bound</em> as a parameter, never concatenated into SQL.
     */
    private Object prepareFilterValue(String operator, Object value) {
        if ("like".equals(operator) && value instanceof String s) {
            return "%" + s + "%";
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

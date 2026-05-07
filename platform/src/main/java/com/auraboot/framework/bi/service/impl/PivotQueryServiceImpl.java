package com.auraboot.framework.bi.service.impl;

import com.auraboot.framework.bi.dto.PivotQueryRequest;
import com.auraboot.framework.bi.dto.PivotQueryResponse;
import com.auraboot.framework.bi.service.PivotQueryService;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Application-level pivot implementation.
 *
 * <p>Builds a parameterised SQL via {@link DynamicDataMapper#selectByQuery} (which routes through
 * {@link com.auraboot.framework.meta.mapper.DynamicSqlProvider#selectByQuery} for SELECT-only
 * safety enforcement), then pivots the rowset in memory. All identifiers (table name, column
 * names) are resolved through {@link MetaModelService} — only fields declared in the model
 * definition are accepted; user input never reaches the SQL string. All values (tenantId,
 * filter values) flow through {@code #&#123;params.*&#125;} JDBC PreparedStatement placeholders.
 *
 * <p>This avoids dependency on PostgreSQL tablefunc extension. True SQL-level GROUPING SETS,
 * time bucketing, and multi-value aggregation are tracked under Pivot Phase B1+ design.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PivotQueryServiceImpl implements PivotQueryService {

    private final DynamicDataMapper dynamicDataMapper;
    private final MetaModelService metaModelService;

    private static final Set<String> ALLOWED_AGGREGATIONS = Set.of("sum", "count", "avg", "min", "max");

    private static final String AGG_VALUE_ALIAS = "agg_value";

    @Override
    public PivotQueryResponse executePivot(PivotQueryRequest request, Long tenantId) {
        validateRequestShape(request);
        ResolvedPivot resolved = resolveAndValidate(request, tenantId);

        String dimColumnsExpr = resolved.dimColumns().stream()
                .map(c -> c.physical() + " AS " + c.logical())
                .collect(Collectors.joining(", "));

        String aggExpr = resolved.aggregation() + "(" + resolved.valueColumnPhysical() + ") AS " + AGG_VALUE_ALIAS;

        StringBuilder sql = new StringBuilder()
                .append("SELECT ").append(dimColumnsExpr).append(", ").append(aggExpr)
                .append(" FROM ").append(resolved.tableName())
                .append(" WHERE tenant_id = #{params.tenantId}");

        if (resolved.softDelete()) {
            sql.append(" AND (deleted_flag = FALSE OR deleted_flag IS NULL)");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);

        for (int i = 0; i < resolved.filters().size(); i++) {
            ResolvedFilter f = resolved.filters().get(i);
            String paramKey = "f" + i;
            String operatorExpr = f.operator().equals("like") ? "LIKE" : f.operator();
            sql.append(" AND ").append(f.physicalColumn()).append(' ').append(operatorExpr)
                    .append(" #{params.").append(paramKey).append('}');
            params.put(paramKey, f.value());
        }

        // GROUP BY / ORDER BY use logical aliases (declared in SELECT) — already whitelist-validated.
        String groupByExpr = resolved.dimColumns().stream()
                .map(DimColumn::logical)
                .collect(Collectors.joining(", "));
        sql.append(" GROUP BY ").append(groupByExpr).append(" ORDER BY ").append(groupByExpr);

        String selectSql = sql.toString();
        log.debug("Pivot select SQL: {}", selectSql);
        List<Map<String, Object>> rawData = dynamicDataMapper.selectByQuery(selectSql, params);

        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) AS cnt FROM ")
                .append(resolved.tableName())
                .append(" WHERE tenant_id = #{params.tenantId}");
        if (resolved.softDelete()) {
            countSql.append(" AND (deleted_flag = FALSE OR deleted_flag IS NULL)");
        }
        for (int i = 0; i < resolved.filters().size(); i++) {
            ResolvedFilter f = resolved.filters().get(i);
            String operatorExpr = f.operator().equals("like") ? "LIKE" : f.operator();
            countSql.append(" AND ").append(f.physicalColumn()).append(' ').append(operatorExpr)
                    .append(" #{params.f").append(i).append('}');
        }
        Long totalRecords = dynamicDataMapper.countByQuery(countSql.toString(), params);

        return buildPivotResponse(rawData, request, resolved, totalRecords != null ? totalRecords : 0);
    }

    // ------------------------------------------------------------------
    // Validation + resolution
    // ------------------------------------------------------------------

    private void validateRequestShape(PivotQueryRequest request) {
        if (request.getAggregation() == null
                || !ALLOWED_AGGREGATIONS.contains(request.getAggregation().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Invalid aggregation: " + request.getAggregation()
                    + ". Allowed: " + ALLOWED_AGGREGATIONS);
        }
    }

    private ResolvedPivot resolveAndValidate(PivotQueryRequest request, Long tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (request.getModelCode() == null) {
            throw new IllegalArgumentException("modelCode is required");
        }

        ModelDefinition model = metaModelService.getModelDefinition(request.getModelCode())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown modelCode: " + request.getModelCode()));

        String tableName = model.getTableName();
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException(
                    "Model has no physical tableName: " + request.getModelCode());
        }
        // Defence in depth — table name comes from metadata but still must look like a SQL identifier.
        SqlSafetyUtils.validateIdentifier(tableName, "pivot table name");

        Map<String, FieldDefinition> fieldsByCode = new LinkedHashMap<>();
        if (model.getFields() != null) {
            for (FieldDefinition f : model.getFields()) {
                if (f.getCode() != null) {
                    fieldsByCode.put(f.getCode(), f);
                }
            }
        }
        if (fieldsByCode.isEmpty()) {
            throw new IllegalArgumentException(
                    "Model has no field definitions: " + request.getModelCode());
        }

        List<DimColumn> dimColumns = new ArrayList<>();
        if (request.getRowDimensions() == null || request.getRowDimensions().isEmpty()) {
            throw new IllegalArgumentException("rowDimensions must contain at least one field");
        }
        for (String code : request.getRowDimensions()) {
            dimColumns.add(resolveDimColumn(code, fieldsByCode, "rowDimension"));
        }

        String colDimLogical = null;
        if (request.getColDimensions() != null && !request.getColDimensions().isEmpty()) {
            DimColumn col = resolveDimColumn(
                    request.getColDimensions().get(0), fieldsByCode, "colDimension");
            dimColumns.add(col);
            colDimLogical = col.logical();
        }

        FieldDefinition valueField = fieldsByCode.get(request.getValueField());
        if (valueField == null) {
            throw new IllegalArgumentException(
                    "Unknown valueField: " + request.getValueField());
        }
        String valueColumnPhysical = resolvePhysicalColumn(valueField, "valueField");

        String aggregation = request.getAggregation().toLowerCase(Locale.ROOT);

        List<ResolvedFilter> filters = new ArrayList<>();
        if (request.getFilters() != null) {
            for (Map<String, Object> rawFilter : request.getFilters()) {
                filters.add(resolveFilter(rawFilter, fieldsByCode));
            }
        }

        return new ResolvedPivot(
                tableName, dimColumns, colDimLogical, valueColumnPhysical,
                aggregation, filters, model.isSoftDelete());
    }

    private DimColumn resolveDimColumn(String fieldCode, Map<String, FieldDefinition> byCode,
                                       String context) {
        if (fieldCode == null) {
            throw new IllegalArgumentException(context + " field code must not be null");
        }
        FieldDefinition def = byCode.get(fieldCode);
        if (def == null) {
            throw new IllegalArgumentException(
                    "Unknown " + context + " field: " + fieldCode);
        }
        // Logical alias = the declared field code (already validated by the metadata loader).
        // Defence in depth — re-validate as identifier.
        SqlSafetyUtils.validateIdentifier(fieldCode, context + " field code");
        String physical = resolvePhysicalColumn(def, context);
        return new DimColumn(fieldCode, physical);
    }

    private String resolvePhysicalColumn(FieldDefinition def, String context) {
        String physical = def.getColumnName();
        if (physical == null || physical.isBlank()) {
            // Models often default columnName == code when not explicitly set.
            physical = def.getCode();
        }
        SqlSafetyUtils.validateIdentifier(physical, context + " physical column");
        return physical;
    }

    private ResolvedFilter resolveFilter(Map<String, Object> raw,
                                         Map<String, FieldDefinition> byCode) {
        Object fieldName = raw.get("fieldName");
        if (fieldName == null) {
            throw new IllegalArgumentException("filter.fieldName is required");
        }
        FieldDefinition def = byCode.get(fieldName.toString());
        if (def == null) {
            throw new IllegalArgumentException("Unknown filter field: " + fieldName);
        }
        String physical = resolvePhysicalColumn(def, "filter");

        Object op = raw.get("operator");
        if (op == null) {
            throw new IllegalArgumentException("filter.operator is required");
        }
        String operator = sanitizeOperator(op.toString());

        return new ResolvedFilter(physical, operator, raw.get("value"));
    }

    private String sanitizeOperator(String op) {
        return switch (op.toLowerCase(Locale.ROOT)) {
            case "eq", "=" -> "=";
            case "neq", "!=" -> "!=";
            case "gt", ">" -> ">";
            case "gte", ">=" -> ">=";
            case "lt", "<" -> "<";
            case "lte", "<=" -> "<=";
            case "like" -> "like";
            default -> throw new IllegalArgumentException("Invalid operator: " + op);
        };
    }

    // ------------------------------------------------------------------
    // Result shaping
    // ------------------------------------------------------------------

    private PivotQueryResponse buildPivotResponse(
            List<Map<String, Object>> rawData,
            PivotQueryRequest request,
            ResolvedPivot resolved,
            long totalRecords) {

        PivotQueryResponse response = new PivotQueryResponse();
        response.setValueField(request.getValueField());
        response.setAggregation(request.getAggregation());
        response.setTotalRecords(totalRecords);

        if (resolved.colDimLogical() == null) {
            return buildSimplePivot(rawData, request, resolved, response);
        }

        response.setColDimensionField(resolved.colDimLogical());

        LinkedHashSet<Object> colHeaderSet = new LinkedHashSet<>();
        for (Map<String, Object> row : rawData) {
            Object colVal = row.get(resolved.colDimLogical());
            if (colVal != null && colHeaderSet.size() < request.getMaxColumns()) {
                colHeaderSet.add(colVal);
            }
        }
        List<Object> colHeaders = new ArrayList<>(colHeaderSet);
        response.setColHeaders(colHeaders);

        // Row dimensions are everything in dimColumns except the trailing colDim.
        List<String> rowDimLogicals = new ArrayList<>();
        for (DimColumn dim : resolved.dimColumns()) {
            if (!dim.logical().equals(resolved.colDimLogical())) {
                rowDimLogicals.add(dim.logical());
            }
        }

        Map<String, Map<Object, Object>> pivotMap = new LinkedHashMap<>();
        Map<String, Map<String, Object>> rowHeaderMap = new LinkedHashMap<>();

        for (Map<String, Object> row : rawData) {
            String rowKey = rowDimLogicals.stream()
                    .map(f -> String.valueOf(row.get(f)))
                    .collect(Collectors.joining("||"));

            if (!rowHeaderMap.containsKey(rowKey)) {
                Map<String, Object> header = new LinkedHashMap<>();
                for (String f : rowDimLogicals) {
                    header.put(f, row.get(f));
                }
                rowHeaderMap.put(rowKey, header);
            }

            pivotMap.computeIfAbsent(rowKey, k -> new LinkedHashMap<>())
                    .put(row.get(resolved.colDimLogical()), row.get(AGG_VALUE_ALIAS));
        }

        List<Map<String, Object>> rowHeaders = new ArrayList<>(rowHeaderMap.values());
        List<List<Object>> cells = new ArrayList<>();
        List<Object> rowSubtotals = new ArrayList<>();

        for (String rowKey : pivotMap.keySet()) {
            Map<Object, Object> colMap = pivotMap.get(rowKey);
            List<Object> rowCells = new ArrayList<>();
            double rowTotal = 0;

            for (Object colHeader : colHeaders) {
                Object cellVal = colMap.getOrDefault(colHeader, null);
                rowCells.add(cellVal);
                if (cellVal instanceof Number) {
                    rowTotal += ((Number) cellVal).doubleValue();
                }
            }
            cells.add(rowCells);
            rowSubtotals.add(rowTotal);
        }

        List<Object> colSubtotals = new ArrayList<>();
        double grandTotal = 0;
        for (int ci = 0; ci < colHeaders.size(); ci++) {
            double colTotal = 0;
            for (List<Object> rowCells : cells) {
                Object val = ci < rowCells.size() ? rowCells.get(ci) : null;
                if (val instanceof Number) {
                    colTotal += ((Number) val).doubleValue();
                }
            }
            colSubtotals.add(colTotal);
            grandTotal += colTotal;
        }

        response.setRowHeaders(rowHeaders);
        response.setCells(cells);
        response.setRowSubtotals(request.isIncludeSubtotals() ? rowSubtotals : null);
        response.setColSubtotals(request.isIncludeSubtotals() ? colSubtotals : null);
        response.setGrandTotal(request.isIncludeGrandTotal() ? grandTotal : null);

        return response;
    }

    private PivotQueryResponse buildSimplePivot(
            List<Map<String, Object>> rawData,
            PivotQueryRequest request,
            ResolvedPivot resolved,
            PivotQueryResponse response) {

        List<String> rowDimLogicals = resolved.dimColumns().stream()
                .map(DimColumn::logical)
                .toList();

        List<Map<String, Object>> rowHeaders = new ArrayList<>();
        List<List<Object>> cells = new ArrayList<>();
        double grandTotal = 0;

        for (Map<String, Object> row : rawData) {
            Map<String, Object> header = new LinkedHashMap<>();
            for (String f : rowDimLogicals) {
                header.put(f, row.get(f));
            }
            rowHeaders.add(header);

            Object val = row.get(AGG_VALUE_ALIAS);
            cells.add(List.of(val != null ? val : 0));
            if (val instanceof Number) {
                grandTotal += ((Number) val).doubleValue();
            }
        }

        response.setRowHeaders(rowHeaders);
        response.setColHeaders(List.of(request.getAggregation() + "(" + request.getValueField() + ")"));
        response.setCells(cells);
        response.setGrandTotal(request.isIncludeGrandTotal() ? grandTotal : null);

        return response;
    }

    // ------------------------------------------------------------------
    // Internal value types
    // ------------------------------------------------------------------

    /** A SELECT/GROUP-BY dimension expressed both as its logical alias and physical column. */
    private record DimColumn(String logical, String physical) {
    }

    /** Filter resolved to a physical column + canonical operator + raw user value (parameterised). */
    private record ResolvedFilter(String physicalColumn, String operator, Object value) {
    }

    /** Fully resolved + validated pivot context, ready for SQL building. */
    private record ResolvedPivot(
            String tableName,
            List<DimColumn> dimColumns,
            String colDimLogical,
            String valueColumnPhysical,
            String aggregation,
            List<ResolvedFilter> filters,
            boolean softDelete) {
    }
}

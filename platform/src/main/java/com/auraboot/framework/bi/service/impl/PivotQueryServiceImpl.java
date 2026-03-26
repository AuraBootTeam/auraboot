package com.auraboot.framework.bi.service.impl;

import com.auraboot.framework.bi.dto.PivotQueryRequest;
import com.auraboot.framework.bi.dto.PivotQueryResponse;
import com.auraboot.framework.bi.service.PivotQueryService;
import com.auraboot.framework.datasource.dao.mapper.DynamicQueryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Application-level pivot implementation.
 * Fetches grouped data via SQL, then pivots in memory.
 * This avoids dependency on PostgreSQL tablefunc extension.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PivotQueryServiceImpl implements PivotQueryService {

    private final DynamicQueryMapper dynamicQueryMapper;

    private static final Set<String> ALLOWED_AGGREGATIONS = Set.of("sum", "count", "avg", "min", "max");

    @Override
    public PivotQueryResponse executePivot(PivotQueryRequest request, Long tenantId) {
        validateRequest(request);

        String tableName = sanitizeIdentifier(request.getModelCode());
        String valueField = sanitizeIdentifier(request.getValueField());
        String aggregation = request.getAggregation().toLowerCase(Locale.ROOT);

        // Build dimension columns for SELECT and GROUP BY
        List<String> allDimensions = new ArrayList<>(request.getRowDimensions().stream()
                .map(this::sanitizeIdentifier).toList());

        String colDimField = null;
        if (request.getColDimensions() != null && !request.getColDimensions().isEmpty()) {
            colDimField = sanitizeIdentifier(request.getColDimensions().get(0));
            allDimensions.add(colDimField);
        }

        String dimColumns = String.join(", ", allDimensions);
        String aggExpr = aggregation + "(" + valueField + ") AS agg_value";

        // Build WHERE clause
        StringBuilder whereClause = new StringBuilder("WHERE tenant_id = " + tenantId);
        // Note: filters support is simplified; production should use parameterized queries
        if (request.getFilters() != null) {
            for (Map<String, Object> filter : request.getFilters()) {
                String fn = sanitizeIdentifier(String.valueOf(filter.get("fieldName")));
                String op = sanitizeOperator(String.valueOf(filter.get("operator")));
                Object val = filter.get("value");
                whereClause.append(" AND ").append(fn).append(" ").append(op).append(" '")
                        .append(sanitizeValue(String.valueOf(val))).append("'");
            }
        }

        String sql = "SELECT " + dimColumns + ", " + aggExpr +
                " FROM " + tableName +
                " " + whereClause +
                " GROUP BY " + dimColumns +
                " ORDER BY " + dimColumns;

        log.info("Executing pivot query: {}", sql);
        List<Map<String, Object>> rawData = dynamicQueryMapper.queryData(sql);

        // Count total
        String countSql = "SELECT COUNT(*) FROM " + tableName + " " + whereClause;
        Long totalRecords = dynamicQueryMapper.countData(countSql);

        // Build pivot response
        return buildPivotResponse(rawData, request, colDimField, totalRecords != null ? totalRecords : 0);
    }

    private PivotQueryResponse buildPivotResponse(
            List<Map<String, Object>> rawData,
            PivotQueryRequest request,
            String colDimField,
            long totalRecords) {

        PivotQueryResponse response = new PivotQueryResponse();
        response.setValueField(request.getValueField());
        response.setAggregation(request.getAggregation());
        response.setTotalRecords(totalRecords);

        if (colDimField == null) {
            // No column dimension - simple aggregation table
            return buildSimplePivot(rawData, request, response);
        }

        response.setColDimensionField(colDimField);

        // Extract unique column header values (ordered)
        LinkedHashSet<Object> colHeaderSet = new LinkedHashSet<>();
        for (Map<String, Object> row : rawData) {
            Object colVal = row.get(colDimField);
            if (colVal != null && colHeaderSet.size() < request.getMaxColumns()) {
                colHeaderSet.add(colVal);
            }
        }
        List<Object> colHeaders = new ArrayList<>(colHeaderSet);
        response.setColHeaders(colHeaders);

        // Group by row dimensions
        List<String> rowDimFields = request.getRowDimensions().stream()
                .map(this::sanitizeIdentifier).toList();

        // Build row key -> col value -> agg_value mapping
        Map<String, Map<Object, Object>> pivotMap = new LinkedHashMap<>();
        Map<String, Map<String, Object>> rowHeaderMap = new LinkedHashMap<>();

        for (Map<String, Object> row : rawData) {
            String rowKey = rowDimFields.stream()
                    .map(f -> String.valueOf(row.get(f)))
                    .collect(Collectors.joining("||"));

            if (!rowHeaderMap.containsKey(rowKey)) {
                Map<String, Object> header = new LinkedHashMap<>();
                for (String f : rowDimFields) {
                    header.put(f, row.get(f));
                }
                rowHeaderMap.put(rowKey, header);
            }

            pivotMap.computeIfAbsent(rowKey, k -> new LinkedHashMap<>())
                    .put(row.get(colDimField), row.get("agg_value"));
        }

        // Build output arrays
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

        // Column subtotals
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
            PivotQueryResponse response) {

        List<String> rowDimFields = request.getRowDimensions().stream()
                .map(this::sanitizeIdentifier).toList();

        List<Map<String, Object>> rowHeaders = new ArrayList<>();
        List<List<Object>> cells = new ArrayList<>();
        double grandTotal = 0;

        for (Map<String, Object> row : rawData) {
            Map<String, Object> header = new LinkedHashMap<>();
            for (String f : rowDimFields) {
                header.put(f, row.get(f));
            }
            rowHeaders.add(header);

            Object val = row.get("agg_value");
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

    private void validateRequest(PivotQueryRequest request) {
        if (!ALLOWED_AGGREGATIONS.contains(request.getAggregation().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Invalid aggregation: " + request.getAggregation()
                    + ". Allowed: " + ALLOWED_AGGREGATIONS);
        }
    }

    /**
     * Basic SQL identifier sanitization to prevent injection.
     * Only allows alphanumeric and underscore.
     */
    private String sanitizeIdentifier(String identifier) {
        if (identifier == null || !identifier.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + identifier);
        }
        return identifier;
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

    private String sanitizeValue(String value) {
        // Escape single quotes
        return value.replace("'", "''");
    }
}

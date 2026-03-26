package com.auraboot.framework.bi.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for pivot/cross-tabulation query results.
 */
@Data
public class PivotQueryResponse {

    /** Ordered list of row header values (each entry is a map of dimension->value) */
    private List<Map<String, Object>> rowHeaders;

    /** Ordered list of column header values */
    private List<Object> colHeaders;

    /** 2D array of cell values: cells[rowIndex][colIndex] */
    private List<List<Object>> cells;

    /** Subtotals per row (aggregated across all columns) */
    private List<Object> rowSubtotals;

    /** Subtotals per column (aggregated across all rows) */
    private List<Object> colSubtotals;

    /** Grand total value */
    private Object grandTotal;

    /** Column dimension field name */
    private String colDimensionField;

    /** Value field name */
    private String valueField;

    /** Aggregation function used */
    private String aggregation;

    /** Total number of data rows before pivoting */
    private long totalRecords;
}

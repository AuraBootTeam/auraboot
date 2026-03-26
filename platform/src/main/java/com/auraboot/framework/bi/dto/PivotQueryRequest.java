package com.auraboot.framework.bi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Request DTO for pivot/cross-tabulation queries.
 */
@Data
public class PivotQueryRequest {

    /** The model/table to query against */
    @NotBlank(message = "modelCode is required")
    private String modelCode;

    /** Fields to use as row dimensions (GROUP BY rows) */
    @NotEmpty(message = "At least one row dimension is required")
    private List<String> rowDimensions;

    /** Fields to use as column dimensions (pivot columns) */
    private List<String> colDimensions;

    /** The field to aggregate */
    @NotBlank(message = "valueField is required")
    private String valueField;

    /** Aggregation function: SUM, COUNT, AVG, MIN, MAX */
    private String aggregation = "sum";

    /** Optional filters in standard format */
    private List<Map<String, Object>> filters;

    /** Whether to include subtotals */
    private boolean includeSubtotals = true;

    /** Whether to include grand total */
    private boolean includeGrandTotal = true;

    /** Maximum number of column dimension values (to prevent explosion) */
    private int maxColumns = 50;
}

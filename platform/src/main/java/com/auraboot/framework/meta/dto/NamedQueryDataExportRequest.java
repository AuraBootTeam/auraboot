package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request DTO for exporting Named Query result data as Excel/CSV/JSON files.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class NamedQueryDataExportRequest {

    /**
     * Export format (EXCEL, CSV, JSON)
     */
    private DataExportRequest.ExportFormat format = DataExportRequest.ExportFormat.EXCEL;

    /**
     * Fields to export (field codes). If empty, exports all whitelisted fields.
     */
    private List<String> fields;

    /**
     * WHERE conditions (same format as NamedQueryTestRequest)
     */
    private JsonNode whereConditions;

    /**
     * ORDER BY conditions
     */
    private JsonNode orderConditions;

    /**
     * Maximum rows to export
     */
    @Min(value = 1, message = "Limit must be at least 1")
    @Max(value = 100000, message = "Limit cannot exceed 100000")
    private Integer limit = 10000;

    /**
     * Whether to include header row
     */
    private Boolean includeHeader = true;

    /**
     * Custom file name (without extension)
     */
    @Size(max = 200, message = "File name cannot exceed 200 characters")
    private String fileName;
}

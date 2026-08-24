package com.auraboot.module.meta.excel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of an Excel import operation, including success/error counts and detailed errors.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExcelImportResult {

    private int totalRows;
    private int successCount;
    private int errorCount;
    /** Number of rows created (INSERT) during UPSERT mode. */
    @Builder.Default
    private int createdCount = 0;
    /** Number of rows updated during UPSERT mode. */
    @Builder.Default
    private int updatedCount = 0;
    private List<ImportValidationError> errors;
    private boolean hasErrors;
    /** Task ID for async imports. Null for synchronous imports. */
    private String taskId;
    /** True only when taskId identifies an asynchronous execution that must be polled. */
    @Builder.Default
    private boolean asyncTask = false;
    /** Authorized API URL for downloading a failed-row correction workbook. */
    private String errorReportUrl;
    /** True when row errors exist but the correction workbook could not be generated. */
    private boolean errorReportFailed;
    /** True when the correction workbook existed but passed its configured retention window. */
    private boolean errorReportExpired;

    /**
     * Factory for a completed import (may have partial errors).
     */
    public static ExcelImportResult success(int successCount, int errorCount,
                                             List<ImportValidationError> validationErrors) {
        return ExcelImportResult.builder()
                .totalRows(successCount + errorCount)
                .successCount(successCount)
                .errorCount(errorCount)
                .errors(validationErrors != null ? validationErrors : new ArrayList<>())
                .hasErrors(errorCount > 0)
                .build();
    }

    /**
     * Factory for a pre-import validation failure (nothing persisted).
     */
    public static ExcelImportResult withErrors(List<ImportValidationError> errors, int totalRows) {
        return ExcelImportResult.builder()
                .totalRows(totalRows)
                .successCount(0)
                .errorCount(errors != null ? errors.size() : 0)
                .errors(errors != null ? errors : new ArrayList<>())
                .hasErrors(true)
                .build();
    }
}

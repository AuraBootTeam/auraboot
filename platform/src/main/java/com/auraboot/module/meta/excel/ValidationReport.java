package com.auraboot.module.meta.excel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Report produced by ExcelValidationEngine after validating an Excel file
 * against a model's field definitions.
 *
 * @author AuraBoot Team
 * @since 6.0.0
 */
@Data
@NoArgsConstructor
public class ValidationReport {

    private int totalRows;
    private int validRows;
    private List<RowError> errors = new ArrayList<>();
    private List<RowWarning> warnings = new ArrayList<>();

    /**
     * Whether the file passed validation (no errors; warnings are acceptable).
     */
    public boolean isValid() {
        return errors.isEmpty();
    }

    /**
     * A hard validation error — the row cannot be imported.
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RowError {
        /** 1-based Excel row number (header = row 1, first data = row 2). */
        private int rowNumber;
        private String fieldCode;
        private String message;
        /** The raw cell value that failed validation. */
        private String value;
    }

    /**
     * A soft validation warning — the row can be imported but may have issues.
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RowWarning {
        /** 1-based Excel row number. */
        private int rowNumber;
        private String fieldCode;
        private String message;
    }
}

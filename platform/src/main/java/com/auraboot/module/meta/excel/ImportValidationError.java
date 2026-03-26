package com.auraboot.module.meta.excel;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Represents a single validation or import error for a specific row/field.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Data
@AllArgsConstructor
public class ImportValidationError {

    /** 1-based row number in the Excel file (header = row 1, first data = row 2). */
    private int rowNumber;

    /** The field code that caused the error, null for row-level errors. */
    private String fieldCode;

    /** Human-readable error message. */
    private String message;
}

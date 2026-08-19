package com.auraboot.framework.user.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Read-only validation result for an employee account workbook.
 */
@Data
@Builder
public class EmployeeAccountImportPreviewResponse {
    private int totalRows;
    private int validCount;
    private int errorCount;
    private List<Row> rows;

    @Data
    @Builder
    public static class Row {
        private int rowNumber;
        private String name;
        private String userName;
        private String mobile;
        private String employeeCode;
        private String departmentCode;
        private String positionCode;
        private String action;
        private List<String> errors;
    }
}

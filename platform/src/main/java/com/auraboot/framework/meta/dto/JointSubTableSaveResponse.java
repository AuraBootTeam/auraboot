package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Joint sub-table save response DTO
 *
 * Contains the result of a joint save operation including master record ID
 * and statistics for each sub-table.
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JointSubTableSaveResponse {

    /**
     * Whether the operation was successful overall
     */
    private Boolean success;

    /**
     * The ID of the saved master record
     */
    private String masterId;

    /**
     * The complete saved master record data
     */
    private Map<String, Object> masterRecord;

    /**
     * Count of records saved for each sub-table
     * Key: relation name
     * Value: number of records saved
     */
    @Builder.Default
    private Map<String, Integer> subTableCounts = new HashMap<>();

    /**
     * Saved records for each sub-table (optional, for detailed response)
     * Key: relation name
     * Value: list of saved child records
     */
    @Builder.Default
    private Map<String, List<Map<String, Object>>> savedRecords = new HashMap<>();

    /**
     * List of error messages (if any)
     */
    @Builder.Default
    private List<String> errors = new ArrayList<>();

    /**
     * Detailed errors per sub-table
     * Key: relation name
     * Value: list of errors for that sub-table
     */
    @Builder.Default
    private Map<String, List<SubTableError>> subTableErrors = new HashMap<>();

    /**
     * Operation duration in milliseconds
     */
    private Long duration;

    /**
     * Whether this was a create or update operation for the master record
     */
    private OperationType operationType;

    /**
     * Sub-table error detail
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubTableError {
        /**
         * Row index in the sub-table data
         */
        private Integer rowIndex;

        /**
         * Error message
         */
        private String message;

        /**
         * Error code (optional)
         */
        private String errorCode;

        /**
         * Original data that caused the error
         */
        private Map<String, Object> data;
    }

    /**
     * Operation type enum
     */
    public enum OperationType {
        CREATE,
        UPDATE
    }

    /**
     * Factory method for success response
     */
    public static JointSubTableSaveResponse success(String masterId, Map<String, Object> masterRecord,
                                                     Map<String, Integer> subTableCounts, long duration) {
        return JointSubTableSaveResponse.builder()
                .success(true)
                .masterId(masterId)
                .masterRecord(masterRecord)
                .subTableCounts(subTableCounts)
                .duration(duration)
                .build();
    }

    /**
     * Factory method for failure response
     */
    public static JointSubTableSaveResponse failure(List<String> errors, long duration) {
        return JointSubTableSaveResponse.builder()
                .success(false)
                .errors(errors)
                .duration(duration)
                .build();
    }
}

package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Field validation result
 * 
 * Contains validation status and detailed error messages
 * 
 * @author AuraBoot Team
 * @since 2.1.2
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaFieldValidationResult {

    /**
     * Overall validation status
     */
    @Builder.Default
    private boolean valid = true;

    /**
     * Field code being validated
     */
    private String code;

    /**
     * Validation error messages
     */
    @Builder.Default
    private List<ValidationError> errors = new ArrayList<>();

    /**
     * Validation warning messages
     */
    @Builder.Default
    private List<ValidationWarning> warnings = new ArrayList<>();

    /**
     * Add validation error
     * 
     * @param field Field name
     * @param message Error message
     */
    public void addError(String field, String message) {
        this.valid = false;
        this.errors.add(new ValidationError(field, message));
    }

    /**
     * Add validation error with error code
     * 
     * @param field Field name
     * @param errorCode Error code
     * @param message Error message
     */
    public void addError(String field, String errorCode, String message) {
        this.valid = false;
        this.errors.add(new ValidationError(field, errorCode, message));
    }

    /**
     * Add validation warning
     * 
     * @param field Field name
     * @param message Warning message
     */
    public void addWarning(String field, String message) {
        this.warnings.add(new ValidationWarning(field, message));
    }

    /**
     * Check if there are any errors
     * 
     * @return true if errors exist
     */
    public boolean hasErrors() {
        return !this.errors.isEmpty();
    }

    /**
     * Check if there are any warnings
     * 
     * @return true if warnings exist
     */
    public boolean hasWarnings() {
        return !this.warnings.isEmpty();
    }

    /**
     * Get error count
     * 
     * @return Number of errors
     */
    public int getErrorCount() {
        return this.errors.size();
    }

    /**
     * Get warning count
     * 
     * @return Number of warnings
     */
    public int getWarningCount() {
        return this.warnings.size();
    }

    /**
     * Check if there is an error for specific field
     * 
     * @param field Field name
     * @return true if error exists for the field
     */
    public boolean hasError(String field) {
        return this.errors.stream()
            .anyMatch(error -> error.getField().equals(field));
    }

    /**
     * Check if there is a warning for specific field
     * 
     * @param field Field name
     * @return true if warning exists for the field
     */
    public boolean hasWarning(String field) {
        return this.warnings.stream()
            .anyMatch(warning -> warning.getField().equals(field));
    }

    /**
     * Validation error detail
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationError {
        /**
         * Field name
         */
        private String field;

        /**
         * Error code
         */
        private String errorCode;

        /**
         * Error message
         */
        private String message;

        /**
         * Constructor without error code
         */
        public ValidationError(String field, String message) {
            this.field = field;
            this.message = message;
        }
    }

    /**
     * Validation warning detail
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationWarning {
        /**
         * Field name
         */
        private String field;

        /**
         * Warning message
         */
        private String message;
    }
}

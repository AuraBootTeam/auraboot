package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.BindingConfiguration;
import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;

/**
 * Field validation service interface
 * Provides enhanced validation for field definitions and binding configurations
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
public interface FieldValidationService {

    /**
     * Validate field definition comprehensively
     * Includes code format, data type, reference target, and dictionary binding validation
     * 
     * @param request Field creation request
     * @return Validation result
     */
    ValidationResult validateFieldDefinition(MetaFieldCreateRequest request);

    /**
     * Validate field code format
     * Code must start with letter and contain only letters, numbers, and underscores
     * 
     * @param code Field code
     * @return true if valid, false otherwise
     */
    boolean validateCodeFormat(String code);

    /**
     * Validate data type
     * Checks if data type is one of the supported types
     * 
     * @param dataType Data type
     * @return true if valid, false otherwise
     */
    boolean validateDataType(String dataType);

    /**
     * Validate reference target existence
     * For REFERENCE type fields, validates that the target model exists
     * 
     * @param refTarget Reference target configuration
     * @return true if valid, false otherwise
     */
    boolean validateRefTarget(java.util.Map<String, Object> refTarget);

    /**
     * Validate dictionary binding
     * Checks if the dictionary exists and is published
     * 
     * @param fieldPid Field PID
     * @param dictCode Dictionary code
     * @return true if valid, false otherwise
     */
    boolean validateDictBinding(String fieldPid, String dictCode);

    /**
     * Validate binding override rules
     * Ensures that override rules are more restrictive than base field rules
     * 
     * @param binding Binding configuration
     * @param field Field definition
     * @return true if valid, false otherwise
     */
    boolean validateBindingOverride(BindingConfiguration binding, MetaFieldDTO field);

    /**
     * Validation result class
     */
    class ValidationResult {
        private boolean valid;
        private java.util.List<String> errors;
        private java.util.List<String> warnings;
        private java.util.List<String> suggestions;

        public ValidationResult() {
            this.valid = true;
            this.errors = new java.util.ArrayList<>();
            this.warnings = new java.util.ArrayList<>();
            this.suggestions = new java.util.ArrayList<>();
        }

        public boolean isValid() {
            return valid && errors.isEmpty();
        }

        public void setValid(boolean valid) {
            this.valid = valid;
        }

        public java.util.List<String> getErrors() {
            return errors;
        }

        public void addError(String error) {
            this.errors.add(error);
            this.valid = false;
        }

        public java.util.List<String> getWarnings() {
            return warnings;
        }

        public void addWarning(String warning) {
            this.warnings.add(warning);
        }

        public java.util.List<String> getSuggestions() {
            return suggestions;
        }

        public void addSuggestion(String suggestion) {
            this.suggestions.add(suggestion);
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }

        public boolean hasSuggestions() {
            return !suggestions.isEmpty();
        }
    }
}

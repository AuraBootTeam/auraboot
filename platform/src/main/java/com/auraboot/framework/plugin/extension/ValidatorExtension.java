package com.auraboot.framework.plugin.extension;

import org.pf4j.ExtensionPoint;

import java.util.List;
import java.util.Map;

/**
 * Extension point for custom validators.
 * Plugins can implement this interface to provide custom validation logic.
 *
 * Example usage:
 * <pre>
 * {@code
 * @Extension
 * public class InvoiceValidator implements ValidatorExtension {
 *     @Override
 *     public String getValidatorKey() {
 *         return "billing:invoice-amount";
 *     }
 *
 *     @Override
 *     public ValidationResult validate(ValidationContext context) {
 *         var amount = (Double) context.value();
 *         if (amount <= 0) {
 *             return ValidationResult.error("Amount must be positive");
 *         }
 *         return ValidationResult.success();
 *     }
 * }
 * }
 * </pre>
 */
public interface ValidatorExtension extends ExtensionPoint {

    /**
     * Get the unique key for this validator.
     * Format: "namespace:validator-name" (e.g., "billing:invoice-amount", "hr:email-domain")
     *
     * @return validator key
     */
    String getValidatorKey();

    /**
     * Perform validation.
     *
     * @param context validation context containing the value and metadata
     * @return validation result
     */
    ValidationResult validate(ValidationContext context);

    /**
     * Check if this validator supports the given key.
     *
     * @param validatorKey the key to check
     * @return true if this validator can handle the validation
     */
    default boolean supports(String validatorKey) {
        return getValidatorKey().equals(validatorKey);
    }

    /**
     * Get the execution order of this validator.
     * Lower values execute first.
     * Default is 100.
     *
     * @return execution order
     */
    default int getOrder() {
        return 100;
    }

    /**
     * Whether validation should stop on first error.
     * Default is false.
     *
     * @return true if validation should stop on first error
     */
    default boolean isFailFast() {
        return false;
    }

    /**
     * Validation context containing the value and metadata.
     */
    record ValidationContext(
            Long tenantId,
            String pluginId,
            String namespace,
            String validatorKey,
            String fieldCode,
            Object value,
            Map<String, Object> recordData,
            Map<String, Object> validatorParams,
            Map<String, Object> settings
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private Long tenantId;
            private String pluginId;
            private String namespace;
            private String validatorKey;
            private String fieldCode;
            private Object value;
            private Map<String, Object> recordData = Map.of();
            private Map<String, Object> validatorParams = Map.of();
            private Map<String, Object> settings = Map.of();

            public Builder tenantId(Long tenantId) {
                this.tenantId = tenantId;
                return this;
            }

            public Builder pluginId(String pluginId) {
                this.pluginId = pluginId;
                return this;
            }

            public Builder namespace(String namespace) {
                this.namespace = namespace;
                return this;
            }

            public Builder validatorKey(String validatorKey) {
                this.validatorKey = validatorKey;
                return this;
            }

            public Builder fieldCode(String fieldCode) {
                this.fieldCode = fieldCode;
                return this;
            }

            public Builder value(Object value) {
                this.value = value;
                return this;
            }

            public Builder recordData(Map<String, Object> recordData) {
                this.recordData = recordData;
                return this;
            }

            public Builder validatorParams(Map<String, Object> validatorParams) {
                this.validatorParams = validatorParams;
                return this;
            }

            public Builder settings(Map<String, Object> settings) {
                this.settings = settings;
                return this;
            }

            public ValidationContext build() {
                return new ValidationContext(tenantId, pluginId, namespace, validatorKey, fieldCode, value, recordData, validatorParams, settings);
            }
        }
    }

    /**
     * Validation result.
     */
    record ValidationResult(
            boolean valid,
            List<ValidationError> errors
    ) {
        public static ValidationResult success() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(false, List.of(new ValidationError(null, message)));
        }

        public static ValidationResult error(String field, String message) {
            return new ValidationResult(false, List.of(new ValidationError(field, message)));
        }

        public static ValidationResult errors(List<ValidationError> errors) {
            return new ValidationResult(errors.isEmpty(), errors);
        }
    }

    /**
     * Validation error.
     */
    record ValidationError(
            String field,
            String message
    ) {}
}

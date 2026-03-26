package com.auraboot.framework.meta.validator;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.dto.MetaFieldUpdateRequest;
import com.auraboot.framework.meta.dto.MetaFieldValidationResult;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.service.DictService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Field validator
 * 
 * Validates field definitions including:
 * - Code format validation
 * - Data type validation
 * - Reference integrity validation
 * - Dictionary binding validation
 * 
 * Note: Uses MetaFieldMapper directly instead of MetaFieldService
 * to avoid circular dependency.
 * 
 * @author AuraBoot Team
 * @since 2.1.2
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetaFieldValidator {

    private final MetaFieldMapper metaFieldMapper;
    private final DictService dictService;

    /**
     * Valid field code pattern: lowercase letters, numbers, underscores
     * Must start with a letter
     */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

    /**
     * Valid data types
     */
    private static final List<String> VALID_DATA_TYPES = Arrays.asList(
        "string", "text", "integer", "long", "decimal", "boolean",
        "date", "datetime", "time", "json", "jsonb", "array", "object",
        "reference", "enum", "file", "image", "money"
    );

    /**
     * Maximum code length
     */
    private static final int MAX_CODE_LENGTH = 64;

    /**
     * Validate field create request
     * 
     * @param request Create request
     * @return Validation result
     */
    public MetaFieldValidationResult validateCreateRequest(MetaFieldCreateRequest request) {
        MetaFieldValidationResult result = MetaFieldValidationResult.builder()
            .code(request.getCode())
            .build();

        // Validate code format
        validateCodeFormat(request.getCode(), result);

        // Validate data type
        validateDataType(request.getDataType(), result);

        // Validate code uniqueness
        if (result.isValid()) {
            validateCodeUniqueness(request.getCode(), null, result);
        }

        // Validate dictionary binding if applicable
        if (result.isValid() && "enum".equals(request.getDataType())) {
            validateDictionaryBinding(request.getDataSourceId(), result);
        }

        return result;
    }

    /**
     * Validate field update request
     * 
     * @param pid Field PID
     * @param request Update request
     * @return Validation result
     */
    public MetaFieldValidationResult validateUpdateRequest(String pid, MetaFieldUpdateRequest request) {
        MetaFieldValidationResult result = MetaFieldValidationResult.builder()
            .build();

        // Validate data type
        validateDataType(request.getDataType(), result);

        // Validate dictionary binding if applicable
        if (result.isValid() && "enum".equals(request.getDataType())) {
            validateDictionaryBinding(request.getDataSourceId(), result);
        }

        return result;
    }

    /**
     * Validate code format
     * 
     * Rules:
     * - Not empty
     * - Length <= 64
     * - Match pattern: ^[a-z][a-z0-9_]*$
     * 
     * @param code Field code
     * @param result Validation result
     */
    private void validateCodeFormat(String code, MetaFieldValidationResult result) {
        if (!StringUtils.hasText(code)) {
            result.addError("code", "required", "Field code is required");
            return;
        }

        if (code.length() > MAX_CODE_LENGTH) {
            result.addError("code", "too_long", 
                String.format("Field code must not exceed %d characters", MAX_CODE_LENGTH));
        }

        if (!CODE_PATTERN.matcher(code).matches()) {
            result.addError("code", "invalid_format", 
                "Field code must start with a lowercase letter and contain only lowercase letters, numbers, and underscores");
        }
    }

    /**
     * Validate data type
     * 
     * @param dataType Data type
     * @param result Validation result
     */
    private void validateDataType(String dataType, MetaFieldValidationResult result) {
        if (!StringUtils.hasText(dataType)) {
            result.addError("dataType", "required", "Data type is required");
            return;
        }

        if (!VALID_DATA_TYPES.contains(dataType)) {
            result.addError("dataType", "invalid", 
                String.format("Invalid data type: %s. Valid types are: %s", 
                    dataType, String.join(", ", VALID_DATA_TYPES)));
        }
    }

    /**
     * Validate code uniqueness
     * 
     * @param code Field code
     * @param excludePid PID to exclude (for updates)
     * @param result Validation result
     */
    private void validateCodeUniqueness(String code, String excludePid, MetaFieldValidationResult result) {
        try {

            
            // Check if field with this code exists
            var existingFields = metaFieldMapper.findAllVersionsByCode( code);
            
            boolean isDuplicate = false;
            if (existingFields != null && !existingFields.isEmpty()) {
                if (excludePid == null) {
                    // Creating new field - any existing field is a duplicate
                    isDuplicate = true;
                } else {
                    // Updating field - check if any existing field has different PID
                    isDuplicate = existingFields.stream()
                        .anyMatch(f -> !excludePid.equals(f.getPid()));
                }
            }
            
            if (isDuplicate) {
                result.addError("code", "duplicate", 
                    String.format("Field code '%s' already exists", code));
            }
        } catch (Exception e) {
            log.warn("Failed to check code uniqueness: code={}, error={}", code, e.getMessage());
            result.addWarning("code", "Unable to verify code uniqueness");
        }
    }

    /**
     * Validate dictionary binding
     * 
     * For ENUM type fields, validate that the dictionary exists
     * 
     * @param dataSourceId Dictionary data source ID
     * @param result Validation result
     */
    private void validateDictionaryBinding(Long dataSourceId, MetaFieldValidationResult result) {
        if (dataSourceId == null) {
            result.addWarning("dataSourceId", 
                "ENUM type field should have a dictionary binding");
            return;
        }

        try {
            // Check if dictionary exists
            // Note: This is a simplified check, actual implementation may vary
            // based on how dictionaries are stored and referenced
            log.debug("Validating dictionary binding: dataSourceId={}", dataSourceId);
            
            // TODO: Implement actual dictionary existence check
            // For now, just log a warning if we can't validate
            result.addWarning("dataSourceId", 
                "Dictionary binding validation not fully implemented");
            
        } catch (Exception e) {
            log.warn("Failed to validate dictionary binding: dataSourceId={}, error={}", 
                dataSourceId, e.getMessage());
            result.addWarning("dataSourceId", 
                "Unable to verify dictionary binding");
        }
    }

    /**
     * Validate reference integrity
     * 
     * For REFERENCE type fields, validate that the referenced model exists
     * 
     * @param refTarget Reference target model code
     * @param result Validation result
     */
    private void validateReferenceIntegrity(String refTarget, MetaFieldValidationResult result) {
        if (!StringUtils.hasText(refTarget)) {
            result.addError("refTarget", "required", 
                "REFERENCE type field must specify a reference target");
            return;
        }

        try {
            // TODO: Implement actual model existence check
            log.debug("Validating reference integrity: refTarget={}", refTarget);
            
            result.addWarning("refTarget", 
                "Reference integrity validation not fully implemented");
            
        } catch (Exception e) {
            log.warn("Failed to validate reference integrity: refTarget={}, error={}", 
                refTarget, e.getMessage());
            result.addWarning("refTarget", 
                "Unable to verify reference integrity");
        }
    }
}

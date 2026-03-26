package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.BindingConfiguration;
import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.service.DictService;
import com.auraboot.framework.meta.service.FieldValidationService;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Field validation service implementation
 * Provides enhanced validation for field definitions and binding configurations
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FieldValidationServiceImpl implements FieldValidationService {

    private final MetaFieldService metaFieldService;
    private final MetaModelService metaModelService;
    private final DictService dictService;

    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");
    
    private static final List<String> SUPPORTED_DATA_TYPES = Arrays.asList(
        "string", "text", "integer", "long", "decimal", "boolean", 
        "date", "datetime", "time", "enum", "reference", "json", "array"
    );

    @Override
    public ValidationResult validateFieldDefinition(MetaFieldCreateRequest request) {
        log.debug("Validating field definition: code={}", request.getCode());

        ValidationResult result = new ValidationResult();

        // 1. Validate code format
        if (!validateCodeFormat(request.getCode())) {
            result.addError("Field code format is invalid. Must start with letter and contain only letters, numbers, and underscores");
        }

        // 2. Check code uniqueness
        if (!metaFieldService.isCodeUnique(request.getCode(), null)) {
            result.addError("Field code already exists: " + request.getCode());
        }

        // 3. Validate data type
        if (!validateDataType(request.getDataType())) {
            result.addError("Unsupported data type: " + request.getDataType() + 
                ". Supported types: " + String.join(", ", SUPPORTED_DATA_TYPES));
        }

        // 4. Validate ENUM type has dictionary binding
        if ("enum".equals(request.getDataType())) {
            if (request.getDataSourceId() == null && 
                (request.getExtension() == null || !request.getExtension().containsKey("dictCode"))) {
                result.addWarning("ENUM type field should have a dictionary binding");
                result.addSuggestion("Set dataSourceId or provide dictCode in extension");
            }
        }

        // 5. Validate REFERENCE type has ref target
        if ("reference".equals(request.getDataType())) {
            if (request.getRefTarget() == null || request.getRefTarget().isEmpty()) {
                result.addError("REFERENCE type field must have refTarget configuration");
            } else if (!validateRefTarget(request.getRefTarget())) {
                result.addError("Reference target validation failed");
            }
        }

        // 6. Validate feature configuration
        if (request.getFeature() != null) {
            validateFeatureConfig(request.getFeature(), result);
        }

        log.debug("Field definition validation completed: code={}, valid={}, errors={}, warnings={}", 
            request.getCode(), result.isValid(), result.getErrors().size(), result.getWarnings().size());

        return result;
    }

    @Override
    public boolean validateCodeFormat(String code) {
        if (!StringUtils.hasText(code)) {
            return false;
        }
        return CODE_PATTERN.matcher(code).matches();
    }

    @Override
    public boolean validateDataType(String dataType) {
        if (!StringUtils.hasText(dataType)) {
            return false;
        }
        return SUPPORTED_DATA_TYPES.contains(dataType);
    }

    @Override
    public boolean validateRefTarget(Map<String, Object> refTarget) {
        if (refTarget == null || refTarget.isEmpty()) {
            return false;
        }

        // Check if target model code is specified
        Object targetModelCode = refTarget.get("modelCode");
        if (targetModelCode == null || !StringUtils.hasText(targetModelCode.toString())) {
            log.warn("Reference target missing modelCode");
            return false;
        }

        // Verify target model exists
        try {
            MetaModelDTO targetModel = metaModelService.findByCode(targetModelCode.toString());
            if (targetModel == null) {
                log.warn("Reference target model not found: {}", targetModelCode);
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to validate reference target: {}", e.getMessage(), e);
            return false;
        }

        return true;
    }

    @Override
    public boolean validateDictBinding(String fieldPid, String dictCode) {
        if (!StringUtils.hasText(fieldPid) || !StringUtils.hasText(dictCode)) {
            return false;
        }

        log.debug("Validating dictionary binding: fieldPid={}, dictCode={}", fieldPid, dictCode);

        try {
            // 1. Verify field exists
            MetaFieldDTO field = metaFieldService.findByPid(fieldPid);
            if (field == null) {
                log.warn("Field not found for dictionary binding: fieldPid={}", fieldPid);
                return false;
            }

            // 2. Verify field is ENUM type
            if (!"enum".equals(field.getDataType())) {
                log.warn("Only ENUM type fields can have dictionary binding: fieldPid={}, dataType={}", 
                    fieldPid, field.getDataType());
                return false;
            }

            // 3. Verify dictionary exists
            var dict = dictService.findByCode(dictCode);
            if (dict == null) {
                log.warn("Dictionary not found: dictCode={}", dictCode);
                return false;
            }

            // 4. Check if dictionary is published
            if (!StatusConstants.PUBLISHED.equals(dict.getStatus())) {
                log.warn("Dictionary is not published: dictCode={}, status={}", dictCode, dict.getStatus());
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to validate dictionary binding: {}", e.getMessage(), e);
            return false;
        }

        return true;
    }

    @Override
    public boolean validateBindingOverride(BindingConfiguration binding, MetaFieldDTO field) {
        if (binding == null || field == null) {
            return false;
        }

        log.debug("Validating binding override: fieldCode={}", field.getCode());

        ValidationResult result = new ValidationResult();

        // 1. Validate validation override is more restrictive
        if (StringUtils.hasText(binding.getValidationOverride())) {
            // Parse validation rules
            // For now, just check that override is not empty
            // TODO: Implement detailed validation rule comparison
            result.addWarning("Validation override detected - ensure it is more restrictive than base rules");
        }

        // 2. Validate dictionary override
        if (StringUtils.hasText(binding.getDictOverrideCode())) {
            if (!"enum".equals(field.getDataType())) {
                result.addError("Dictionary override can only be applied to ENUM type fields");
            } else if (!validateDictBinding(field.getPid(), binding.getDictOverrideCode())) {
                result.addError("Dictionary override validation failed: " + binding.getDictOverrideCode());
            }
        }

        // 3. Validate required override
        if (Boolean.TRUE.equals(binding.getRequired())) {
            // Required override is always more restrictive
            log.debug("Required override is valid (more restrictive)");
        }

        // 4. Validate nullable override
        if (Boolean.FALSE.equals(binding.getNullable())) {
            // Non-nullable override is more restrictive
            log.debug("Non-nullable override is valid (more restrictive)");
        }

        return result.isValid();
    }

    /**
     * Validate feature configuration
     */
    private void validateFeatureConfig(Map<String, Object> feature, ValidationResult result) {
        // Validate common feature properties
        if (feature.containsKey("maxLength")) {
            Object maxLength = feature.get("maxLength");
            if (!(maxLength instanceof Number) || ((Number) maxLength).intValue() <= 0) {
                result.addError("maxLength must be a positive number");
            }
        }

        if (feature.containsKey("minLength")) {
            Object minLength = feature.get("minLength");
            if (!(minLength instanceof Number) || ((Number) minLength).intValue() < 0) {
                result.addError("minLength must be a non-negative number");
            }
        }

        if (feature.containsKey("pattern")) {
            Object pattern = feature.get("pattern");
            if (!(pattern instanceof String) || !StringUtils.hasText((String) pattern)) {
                result.addError("pattern must be a non-empty string");
            } else {
                // Validate regex pattern
                try {
                    Pattern.compile((String) pattern);
                } catch (Exception e) {
                    result.addError("Invalid regex pattern: " + e.getMessage());
                }
            }
        }

        if (feature.containsKey("min") && feature.containsKey("max")) {
            Object min = feature.get("min");
            Object max = feature.get("max");
            if (min instanceof Number && max instanceof Number) {
                if (((Number) min).doubleValue() > ((Number) max).doubleValue()) {
                    result.addError("min value cannot be greater than max value");
                }
            }
        }
    }
}

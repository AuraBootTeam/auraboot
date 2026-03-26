package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.BindingConfigRequest;
import com.auraboot.framework.meta.dto.BindingConfiguration;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.service.FieldBindingContextService;
import com.auraboot.framework.meta.service.FieldValidationService;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Optional;

/**
 * Field binding context service implementation
 * Manages enhanced binding context configuration for field-model bindings
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FieldBindingContextServiceImpl implements FieldBindingContextService {

    private final MetaFieldService metaFieldService;
    private final MetaModelService metaModelService;
    private final FieldValidationService fieldValidationService;
    private final MetaModelFieldBindingMapper bindingMapper;

    @Override
    @Transactional
    public BindingConfiguration configureBinding(String modelPid, String fieldPid, BindingConfigRequest request) {
        if (!StringUtils.hasText(modelPid) || !StringUtils.hasText(fieldPid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "Model PID and Field PID cannot be empty");
        }
        if (request == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "Binding configuration request cannot be null");
        }

        log.info("Configuring field binding: modelPid={}, fieldPid={}", modelPid, fieldPid);

        // 1. Verify model and field exist
        MetaModelDTO model = metaModelService.findByPid(modelPid);
        if (model == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "Model not found: " + modelPid);
        }

        MetaFieldDTO field = metaFieldService.findByPid(fieldPid);
        if (field == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "Field not found: " + fieldPid);
        }

        // 2. Validate binding configuration
        FieldValidationService.ValidationResult validationResult = 
            validateBindingConfiguration(request, field);
        
        if (!validationResult.isValid()) {
            String errors = String.join("; ", validationResult.getErrors());
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "Binding configuration validation failed: " + errors);
        }

        // 3. Find or create binding
        Long tenantId = MetaContext.getCurrentTenantId();
        ModelFieldBinding binding = bindingMapper.findByModelAndField(
            model.getId(), field.getId(), tenantId);

        if (binding == null) {
            // Create new binding
            binding = new ModelFieldBinding();
            binding.setTenantId(tenantId);
            binding.setModelId(model.getId());
            binding.setFieldId(field.getId());
            binding.setFieldOrder(request.getFieldOrder() != null ? request.getFieldOrder() : 0);
            binding.setCreatedAt(Instant.now());
        }

        // 4. Update binding configuration
        updateBindingFromRequest(binding, request);
        binding.setUpdatedAt(Instant.now());

        // 5. Save binding
        if (binding.getId() == null) {
            bindingMapper.insert(binding);
            log.info("Created new binding: modelId={}, fieldId={}, bindingId={}", 
                model.getId(), field.getId(), binding.getId());
        } else {
            bindingMapper.updateById(binding);
            log.info("Updated binding: bindingId={}", binding.getId());
        }

        return convertToConfiguration(binding, field, model);
    }

    @Override
    public Optional<BindingConfiguration> getBindingConfiguration(String modelPid, String fieldPid) {
        if (!StringUtils.hasText(modelPid) || !StringUtils.hasText(fieldPid)) {
            return Optional.empty();
        }

        log.debug("Getting binding configuration: modelPid={}, fieldPid={}", modelPid, fieldPid);

        // 1. Get model and field
        MetaModelDTO model = metaModelService.findByPid(modelPid);
        if (model == null) {
            return Optional.empty();
        }

        MetaFieldDTO field = metaFieldService.findByPid(fieldPid);
        if (field == null) {
            return Optional.empty();
        }

        // 2. Find binding
        Long tenantId = MetaContext.getCurrentTenantId();
        ModelFieldBinding binding = bindingMapper.findByModelAndField(
            model.getId(), field.getId(), tenantId);

        if (binding == null) {
            return Optional.empty();
        }

        return Optional.of(convertToConfiguration(binding, field, model));
    }

    @Override
    @Transactional
    public BindingConfiguration updateBindingConfiguration(Long bindingId, BindingConfigRequest request) {
        if (bindingId == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "Binding ID cannot be null");
        }
        if (request == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "Binding configuration request cannot be null");
        }

        log.info("Updating binding configuration: bindingId={}", bindingId);

        // 1. Find binding
        ModelFieldBinding binding = bindingMapper.selectById(bindingId);
        if (binding == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "Binding not found: " + bindingId);
        }

        // 2. Verify tenant
        Long tenantId = MetaContext.getCurrentTenantId();
        if (!tenantId.equals(binding.getTenantId())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "Binding does not belong to current tenant");
        }

        // 3. Get field for validation
        MetaFieldDTO field = metaFieldService.findByPid(
            bindingMapper.getFieldPidByBinding(bindingId));
        
        if (field == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "Field not found for binding");
        }

        // 4. Validate configuration
        FieldValidationService.ValidationResult validationResult = 
            validateBindingConfiguration(request, field);
        
        if (!validationResult.isValid()) {
            String errors = String.join("; ", validationResult.getErrors());
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "Binding configuration validation failed: " + errors);
        }

        // 5. Update binding
        updateBindingFromRequest(binding, request);
        binding.setUpdatedAt(Instant.now());
        bindingMapper.updateById(binding);

        log.info("Binding configuration updated: bindingId={}", bindingId);

        // 6. Get model for response
        MetaModelDTO model = metaModelService.findByPid(
            bindingMapper.getModelPidByBinding(bindingId));

        return convertToConfiguration(binding, field, model);
    }

    @Override
    public FieldValidationService.ValidationResult validateBindingConfiguration(
            BindingConfigRequest request, MetaFieldDTO field) {
        
        log.debug("Validating binding configuration: fieldCode={}", field.getCode());

        FieldValidationService.ValidationResult result = new FieldValidationService.ValidationResult();

        // 1. Validate alias code format
        if (StringUtils.hasText(request.getAliasCode())) {
            if (!request.getAliasCode().matches("^[a-zA-Z][a-zA-Z0-9_]*$")) {
                result.addError("Alias code must start with letter and contain only letters, numbers, and underscores");
            }
        }

        // 2. Validate dictionary override
        if (StringUtils.hasText(request.getDictOverrideCode())) {
            if (!"enum".equals(field.getDataType())) {
                result.addError("Dictionary override can only be applied to ENUM type fields");
            } else if (!fieldValidationService.validateDictBinding(field.getPid(), request.getDictOverrideCode())) {
                result.addError("Dictionary override validation failed: " + request.getDictOverrideCode());
            }
        }

        // 3. Validate validation override is more restrictive
        if (StringUtils.hasText(request.getValidationOverride())) {
            // Create temporary binding configuration for validation
            BindingConfiguration tempConfig = BindingConfiguration.builder()
                .validationOverride(request.getValidationOverride())
                .dictOverrideCode(request.getDictOverrideCode())
                .required(request.getRequired())
                .nullable(request.getNullable())
                .build();
            
            if (!fieldValidationService.validateBindingOverride(tempConfig, field)) {
                result.addError("Validation override must be more restrictive than base field rules");
            }
        }

        // 4. Validate default value
        if (StringUtils.hasText(request.getDefaultValue())) {
            // TODO: Validate default value matches field data type
            result.addWarning("Default value validation not fully implemented");
        }

        return result;
    }

    @Override
    public BindingConfiguration getDefaultBindingConfiguration(MetaFieldDTO field) {
        if (field == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "Field cannot be null");
        }

        log.debug("Getting default binding configuration: fieldCode={}", field.getCode());

        return BindingConfiguration.builder()
            .fieldPid(field.getPid())
            .fieldCode(field.getCode())
            .required(false)
            .nullable(true)
            .readonly(false)
            .visible(true)
            .editable(true)
            .fieldOrder(0)
            .build();
    }

    /**
     * Update binding entity from request
     */
    private void updateBindingFromRequest(ModelFieldBinding binding, BindingConfigRequest request) {
        if (request.getAliasCode() != null) {
            binding.setAliasCode(request.getAliasCode());
        }
        if (request.getRequired() != null) {
            binding.setRequired(request.getRequired());
        }
        if (request.getVisible() != null) {
            binding.setVisible(request.getVisible());
        }
        if (request.getEditable() != null) {
            binding.setEditable(request.getEditable());
        }
        if (request.getDefaultValue() != null) {
            binding.setDefaultValue(request.getDefaultValue());
        }
        if (request.getDictOverrideCode() != null) {
            binding.setDictOverrideCode(request.getDictOverrideCode());
        }
        if (request.getUiHint() != null) {
            binding.setUiHint(request.getUiHint());
        }
        if (request.getValidationOverride() != null) {
            binding.setValidationOverride(request.getValidationOverride());
        }
        if (request.getFieldOrder() != null) {
            binding.setFieldOrder(request.getFieldOrder());
        }
    }

    /**
     * Convert binding entity to configuration DTO
     */
    private BindingConfiguration convertToConfiguration(
            ModelFieldBinding binding, MetaFieldDTO field, MetaModelDTO model) {
        
        return BindingConfiguration.builder()
            .bindingId(binding.getId())
            .modelPid(model != null ? model.getPid() : null)
            .modelCode(model != null ? model.getCode() : null)
            .fieldPid(field.getPid())
            .fieldCode(field.getCode())
            .aliasCode(binding.getAliasCode())
            .required(binding.getRequired())
            .nullable(!Boolean.TRUE.equals(binding.getRequired())) // nullable is inverse of required
            .readonly(!Boolean.TRUE.equals(binding.getEditable()))
            .visible(binding.getVisible())
            .editable(binding.getEditable())
            .defaultValue(binding.getDefaultValue())
            .dictOverrideCode(binding.getDictOverrideCode())
            .uiHint(binding.getUiHint())
            .validationOverride(binding.getValidationOverride())
            .fieldOrder(binding.getFieldOrder())
            .build();
    }
}

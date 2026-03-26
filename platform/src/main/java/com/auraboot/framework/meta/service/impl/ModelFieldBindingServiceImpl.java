package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.exception.ResourceNotFoundException;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.DateUtil;
import com.auraboot.framework.meta.dto.BatchFieldBindingRequest;
import com.auraboot.framework.meta.dto.FieldBindingRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaModelFieldBindingDTO;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.FieldDictBinding;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.mapper.MetaFieldDictBindingMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.ModelFieldBindingService;
import com.auraboot.framework.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Model-Field Binding Service Implementation
 * 
 * Manages the binding relationships between models and fields.
 * This service resolves the circular dependency between MetaFieldService and MetaModelService.
 * 
 * @author AuraBoot Team
 * @since 3.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelFieldBindingServiceImpl implements ModelFieldBindingService {

    private final MetaModelMapper metaModelMapper;
    private final MetaFieldMapper metaFieldMapper;
    private final MetaModelFieldBindingMapper bindingMapper;
    private final MetaFieldDictBindingMapper fieldDictBindingMapper;

    @Override
    @Transactional
    public MetaModelFieldBindingDTO bindFieldToModel(
            String modelPid,
            String fieldPid,
            Integer displayOrder,
            Boolean isRequired,
            Boolean isReadonly,
            Boolean isVisible) {
        
        log.info("Binding field {} to model {}", fieldPid, modelPid);
        
        // 1. Get model by PID
        Model model = metaModelMapper.findByPid(modelPid);
        if (model == null) {
            throw new ResourceNotFoundException("Model not found: " + modelPid);
        }
        
        // 2. Get field by PID
        Field field = metaFieldMapper.findByPid(fieldPid);
        if (field == null) {
            throw new ResourceNotFoundException("Field not found: " + fieldPid);
        }

        
        // 3. Check if binding already exists
        ModelFieldBinding existing = bindingMapper.selectByModelAndField(model.getId(), field.getId());
        if (existing != null) {
            log.warn("Binding already exists: modelId={}, fieldId={}", model.getId(), field.getId());
            return convertToDTO(existing, model, field);
        }
        
        // 4. Get next display order if not provided
        if (displayOrder == null) {
            Integer maxOrder = bindingMapper.getMaxFieldOrder(model.getId());
            displayOrder = (maxOrder != null ? maxOrder : -1) + 1;
        }
        
        // 5. Create binding
        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setTenantId(MetaContext.getCurrentTenantId());

        binding.setModelId(model.getId());
        binding.setFieldId(field.getId());
        binding.setFieldOrder(displayOrder);
        binding.setRequired(isRequired != null ? isRequired : false);
        binding.setEditable(isReadonly != null ? !isReadonly : true);
        binding.setVisible(isVisible != null ? isVisible : true);
        binding.setCreatedAt(Instant.now());
        binding.setUpdatedAt(Instant.now());
        
        bindingMapper.insert(binding);
        
        log.info("Field bound to model successfully: bindingId={}, modelId={}, fieldId={}", 
            binding.getId(), model.getId(), field.getId());
        
        return convertToDTO(binding, model, field);
    }

    @Override
    @Transactional
    public boolean unbindFieldFromModel(String modelPid, String fieldPid) {
        log.info("Unbinding field {} from model {}", fieldPid, modelPid);
        
        // 1. Get model by PID
        Model model = metaModelMapper.findByPid(modelPid);
        if (model == null) {
            log.warn("Model not found: {}", modelPid);
            return false;
        }
        
        // 2. Get field by PID
        Field field = metaFieldMapper.findByPid(fieldPid);
        if (field == null) {
            log.warn("Field not found: {}", fieldPid);
            return false;
        }
        
        // 3. Delete binding
        int deleted = bindingMapper.deleteByModelAndField(model.getId(), field.getId());
        
        if (deleted > 0) {
            log.info("Field unbound from model: modelId={}, fieldId={}", model.getId(), field.getId());
            return true;
        } else {
            log.warn("Binding not found: modelId={}, fieldId={}", model.getId(), field.getId());
            return false;
        }
    }

    @Override
    public List<MetaFieldDTO> getModelFields(String modelPid) {
        log.debug("Getting fields for model {}", modelPid);

        // 1. Get model by PID
        Model model = metaModelMapper.findByPid(modelPid);
        if (model == null) {
            throw new ResourceNotFoundException("Model not found: " + modelPid);
        }

        // 2. Get all bindings for this model
        List<ModelFieldBinding> bindings = bindingMapper.findByModelId(model.getId());

        if (bindings.isEmpty()) {
            return new ArrayList<>();
        }

        // 3. Get all field IDs
        List<Long> fieldIds = bindings.stream()
            .map(ModelFieldBinding::getFieldId)
            .collect(Collectors.toList());

        // 4. Batch load fields
        List<Field> fields = metaFieldMapper.findByIds(fieldIds);

        // 5. Create a map for quick lookup
        Map<Long, Field> fieldMap = fields.stream()
            .collect(Collectors.toMap(Field::getId, f -> f));

        // 6. Get field PIDs for dictionary lookup
        List<String> fieldPids = fields.stream()
            .map(Field::getPid)
            .collect(Collectors.toList());

        // 7. Batch load dictionary bindings
        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, String> dictCodeMap = new java.util.HashMap<>();
        if (!fieldPids.isEmpty()) {
            List<FieldDictBinding> dictBindings = fieldDictBindingMapper.findByFieldPids(fieldPids, tenantId);
            dictCodeMap = dictBindings.stream()
                .collect(Collectors.toMap(FieldDictBinding::getFieldPid, FieldDictBinding::getDictCode));
        }

        // 8. Convert to DTOs with binding info and dict code
        final Map<String, String> finalDictCodeMap = dictCodeMap;
        return bindings.stream()
            .map(binding -> {
                Field field = fieldMap.get(binding.getFieldId());
                if (field == null) {
                    return null;
                }
                String dictCode = finalDictCodeMap.get(field.getPid());
                return convertFieldToDTO(field, binding, dictCode);
            })
            .filter(dto -> dto != null)
            .collect(Collectors.toList());
    }


    @Override
    public List<MetaModelFieldBindingDTO> getModelBindings(String modelPid) {
        log.debug("Getting bindings for model {}", modelPid);
        
        // 1. Get model by PID
        Model model = metaModelMapper.findByPid(modelPid);
        if (model == null) {
            throw new ResourceNotFoundException("Model not found: " + modelPid);
        }
        
        // 2. Get all bindings for this model
        List<ModelFieldBinding> bindings = bindingMapper.findByModelId(model.getId());
        
        if (bindings.isEmpty()) {
            return new ArrayList<>();
        }
        
        // 3. Get all field IDs
        List<Long> fieldIds = bindings.stream()
            .map(ModelFieldBinding::getFieldId)
            .collect(Collectors.toList());
        
        // 4. Batch load fields
        List<Field> fields = metaFieldMapper.findByIds(fieldIds);
        
        // 5. Create a map for quick lookup
        Map<Long, Field> fieldMap = fields.stream()
            .collect(Collectors.toMap(Field::getId, f -> f));
        
        // 6. Convert to DTOs
        return bindings.stream()
            .map(binding -> {
                Field field = fieldMap.get(binding.getFieldId());
                return convertToDTO(binding, model, field);
            })
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public int reorderFields(String modelPid, Map<String, Integer> fieldOrders) {
        log.info("Reordering fields for model {}", modelPid);
        
        // 1. Get model by PID
        Model model = metaModelMapper.findByPid(modelPid);
        if (model == null) {
            throw new ResourceNotFoundException("Model not found: " + modelPid);
        }
        
        int updated = 0;
        
        // 2. Update each field's order
        for (Map.Entry<String, Integer> entry : fieldOrders.entrySet()) {
            String fieldPid = entry.getKey();
            Integer newOrder = entry.getValue();
            
            // Get field by PID
            Field field = metaFieldMapper.findByPid(fieldPid);
            if (field == null) {
                log.warn("Field not found: {}", fieldPid);
                continue;
            }
            
            // Update order
            int result = bindingMapper.updateFieldOrderByModelAndField(
                model.getId(), 
                field.getId(), 
                newOrder
            );
            
            if (result > 0) {
                updated++;
            }
        }
        
        log.info("Reordered {} fields for model {}", updated, modelPid);
        return updated;
    }

    @Override
    @Transactional
    public MetaModelFieldBindingDTO updateFieldConfig(
            String modelPid,
            String fieldPid,
            Boolean isRequired,
            Boolean isReadonly,
            Boolean isVisible) {
        
        log.info("Updating field config: modelPid={}, fieldPid={}", modelPid, fieldPid);
        
        // 1. Get model by PID
        Model model = metaModelMapper.findByPid(modelPid);
        if (model == null) {
            throw new ResourceNotFoundException("Model not found: " + modelPid);
        }
        
        // 2. Get field by PID
        Field field = metaFieldMapper.findByPid(fieldPid);
        if (field == null) {
            throw new ResourceNotFoundException("Field not found: " + fieldPid);
        }
        
        // 3. Get existing binding
        ModelFieldBinding binding = bindingMapper.selectByModelAndField(model.getId(), field.getId());
        if (binding == null) {
            throw new ResourceNotFoundException("Binding not found for model " + modelPid + " and field " + fieldPid);
        }
        
        // 4. Update binding
        if (isRequired != null) {
            binding.setRequired(isRequired);
        }
        if (isReadonly != null) {
            binding.setEditable(!isReadonly);
        }
        if (isVisible != null) {
            binding.setVisible(isVisible);
        }
        binding.setUpdatedAt(Instant.now());
        
        bindingMapper.updateById(binding);
        
        log.info("Field config updated: bindingId={}", binding.getId());
        
        return convertToDTO(binding, model, field);
    }

    @Override
    @Transactional
    public int batchBindFields(String modelPid, List<String> fieldPids) {
        log.info("Batch binding {} fields to model {}", fieldPids.size(), modelPid);
        
        // 1. Get model by PID
        Model model = metaModelMapper.findByPid(modelPid);
        if (model == null) {
            throw new ResourceNotFoundException("Model not found: " + modelPid);
        }
        
        // 2. Get current max order
        Integer maxOrder = bindingMapper.getMaxFieldOrder(model.getId());
        int currentOrder = (maxOrder != null ? maxOrder : -1) + 1;
        
        int bound = 0;
        
        // 3. Bind each field
        for (String fieldPid : fieldPids) {
            try {
                bindFieldToModel(modelPid, fieldPid, currentOrder++, false, false, true);
                bound++;
            } catch (Exception e) {
                log.warn("Failed to bind field {}: {}", fieldPid, e.getMessage());
            }
        }
        
        log.info("Batch bound {} fields to model {}", bound, modelPid);
        return bound;
    }

    @Override
    @Transactional
    public MetaModelFieldBindingDTO bindFieldWithConfig(String modelPid, FieldBindingRequest request) {
        log.info("Binding field {} to model {} with full configuration", request.getFieldPid(), modelPid);
        
        // 1. Get model by PID
        Model model = metaModelMapper.findByPid(modelPid);
        if (model == null) {
            throw new ResourceNotFoundException("Model not found: " + modelPid);
        }
        
        // 2. Get field by PID
        Field field = metaFieldMapper.findByPid(request.getFieldPid());
        if (field == null) {
            throw new ResourceNotFoundException("Field not found: " + request.getFieldPid());
        }
        
        // 3. Check if binding already exists
        ModelFieldBinding existing = bindingMapper.selectByModelAndField(model.getId(), field.getId());
        if (existing != null) {
            throw new IllegalStateException("Field " + request.getFieldPid() + " is already bound to model " + modelPid);
        }
        
        // 4. Validate alias_code uniqueness if provided
        if (request.getAliasCode() != null && !request.getAliasCode().trim().isEmpty()) {
            List<ModelFieldBinding> bindings = bindingMapper.findByModelId(model.getId());
            boolean aliasExists = bindings.stream()
                .anyMatch(b -> request.getAliasCode().equals(b.getAliasCode()));
            if (aliasExists) {
                throw new IllegalStateException("Alias code " + request.getAliasCode() + " already exists in model " + modelPid);
            }
        }
        
        // 5. Get next display order
        Integer maxOrder = bindingMapper.getMaxFieldOrder(model.getId());
        int displayOrder = (maxOrder != null ? maxOrder : -1) + 1;
        
        // 6. Create binding with full configuration
        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setTenantId(MetaContext.getCurrentTenantId());
        binding.setModelId(model.getId());
        binding.setFieldId(field.getId());
        binding.setFieldOrder(displayOrder);
        
        // Set configuration from request
        binding.setAliasCode(request.getAliasCode());
        binding.setRequired(request.getRequired() != null ? request.getRequired() : false);
        binding.setEditable(request.getEditable() != null ? request.getEditable() : true);
        binding.setVisible(request.getVisible() != null ? request.getVisible() : true);
        binding.setDefaultValue(request.getDefaultValue());
        binding.setDictOverrideCode(request.getDictOverrideCode());
        binding.setUiHint(request.getUiHint());
        binding.setValidationOverride(request.getValidationOverride());
        binding.setDisplayConfig(request.getDisplayConfig());
        binding.setRemarks(request.getRemarks());
        
        binding.setCreatedAt(Instant.now());
        binding.setUpdatedAt(Instant.now());
        
        bindingMapper.insert(binding);
        
        log.info("Field bound to model successfully with full config: bindingId={}, modelId={}, fieldId={}", 
            binding.getId(), model.getId(), field.getId());
        
        return convertToDTO(binding, model, field);
    }

    @Override
    @Transactional
    public List<MetaModelFieldBindingDTO> batchBindFieldsWithConfig(
            String modelPid, 
            BatchFieldBindingRequest request) {
        
        log.info("Batch binding {} fields to model {} with common configuration", 
                request.getFieldPids().size(), modelPid);
        
        // 1. Get model by PID
        Model model = metaModelMapper.findByPid(modelPid);
        if (model == null) {
            throw new ResourceNotFoundException("Model not found: " + modelPid);
        }
        
        // 2. Validate all field PIDs exist
        List<Field> fields = new ArrayList<>();
        for (String fieldPid : request.getFieldPids()) {
            Field field = metaFieldMapper.findByPid(fieldPid);
            if (field == null) {
                throw new ResourceNotFoundException("Field not found: " + fieldPid);
            }
            
            // Check if already bound
            ModelFieldBinding existing = bindingMapper.selectByModelAndField(model.getId(), field.getId());
            if (existing != null) {
                log.warn("Field {} is already bound to model {}, skipping", fieldPid, modelPid);
                continue;
            }
            
            fields.add(field);
        }
        
        if (fields.isEmpty()) {
            log.warn("No fields to bind after validation");
            return new ArrayList<>();
        }
        
        // 3. Get current max order
        Integer maxOrder = bindingMapper.getMaxFieldOrder(model.getId());
        int currentOrder = (maxOrder != null ? maxOrder : -1) + 1;
        
        // 4. Get common configuration
        BatchFieldBindingRequest.CommonBindingConfig commonConfig = request.getCommonConfig();
        
        // 5. Create bindings
        List<MetaModelFieldBindingDTO> results = new ArrayList<>();
        
        for (Field field : fields) {
            ModelFieldBinding binding = new ModelFieldBinding();
            binding.setTenantId(MetaContext.getCurrentTenantId());
            binding.setModelId(model.getId());
            binding.setFieldId(field.getId());
            binding.setFieldOrder(currentOrder++);
            
            // Apply common configuration if provided
            if (commonConfig != null) {
                binding.setRequired(commonConfig.getRequired() != null ? commonConfig.getRequired() : false);
                binding.setEditable(commonConfig.getEditable() != null ? commonConfig.getEditable() : true);
                binding.setVisible(commonConfig.getVisible() != null ? commonConfig.getVisible() : true);
            } else {
                // Default values
                binding.setRequired(false);
                binding.setEditable(true);
                binding.setVisible(true);
            }
            
            binding.setCreatedAt(Instant.now());
            binding.setUpdatedAt(Instant.now());
            
            bindingMapper.insert(binding);
            
            results.add(convertToDTO(binding, model, field));
            
            log.debug("Field bound: fieldId={}, bindingId={}", field.getId(), binding.getId());
        }
        
        log.info("Batch binding completed: {} fields bound to model {}", results.size(), modelPid);
        
        return results;
    }


    // ==================== Helper Methods ====================

    /**
     * Convert binding entity to DTO
     */
    private MetaModelFieldBindingDTO convertToDTO(
            ModelFieldBinding binding,
            Model model,
            Field field) {
        
        MetaModelFieldBindingDTO.MetaModelFieldBindingDTOBuilder builder = MetaModelFieldBindingDTO.builder()
            .id(binding.getId())
            .tenantId(binding.getTenantId())

            .modelId(binding.getModelId())
            .fieldId(binding.getFieldId())
            .fieldOrder(binding.getFieldOrder())
            .required(binding.getRequired())
            .readonly(binding.getEditable() != null ? !binding.getEditable() : false)
            .visible(binding.getVisible())
            .bindingStatus("active");
        
        // Add model info if available
        if (model != null) {
            builder.modelCode(model.getCode());
            // Model name is in extension
        }
        
        // Add field info if available
        if (field != null) {
            builder.code(field.getCode())
                .fieldType(field.getDataType());
        }
        
        // Convert timestamps
        builder.createdAt(DateUtil.toUtcLocalDateTime(binding.getCreatedAt()));
        builder.updatedAt(DateUtil.toUtcLocalDateTime(binding.getUpdatedAt()));
        
        return builder.build();
    }

    /**
     * Convert field entity to DTO with binding info and dict code
     */
    private MetaFieldDTO convertFieldToDTO(Field field, ModelFieldBinding binding, String dictCode) {
        // Flatten ExtensionBean → Map for frontend consumption
        Map<String, Object> extensionMap = null;
        if (field.getExtension() != null) {
            extensionMap = new HashMap<>();
            if (field.getExtension().getExtension() != null) {
                extensionMap.putAll(field.getExtension().getExtension());
            }
            if (field.getExtension().getDynamicProperties() != null) {
                extensionMap.putAll(field.getExtension().getDynamicProperties());
            }
        }

        return MetaFieldDTO.builder()
            .id(field.getId())
            .pid(field.getPid())
            .code(field.getCode())
            .dataType(field.getDataType())
            .dataSourceId(field.getDataSourceId())
            .version(field.getVersion())
            .status(field.getStatus())
            .tenantId(field.getTenantId())
            .feature(convertBeanToMap(field.getFeature()))
            .refTarget(convertBeanToMap(field.getRefTarget()))
            .indexHint(convertBeanToMap(field.getIndexHint()))
            .uiSchema(convertBeanToMap(field.getUiSchema()))
            .querySchema(convertBeanToMap(field.getQuerySchema()))
            .ruleSchema(convertBeanToMap(field.getRuleSchema()))
            .extension(extensionMap)
            // Add binding info
            .fieldOrder(binding.getFieldOrder())
            .required(binding.getRequired())
            .visible(binding.getVisible())
            .editable(binding.getEditable())
            // Add dict code
            .dictCode(dictCode)
            .build();
    }

    private Map<String, Object> convertBeanToMap(Object bean) {
        if (bean == null) return null;
        try {
            return JsonUtil.toMap(bean);
        } catch (Exception e) {
            log.warn("Failed to convert bean {} to map", bean.getClass().getSimpleName(), e);
            return null;
        }
    }
}

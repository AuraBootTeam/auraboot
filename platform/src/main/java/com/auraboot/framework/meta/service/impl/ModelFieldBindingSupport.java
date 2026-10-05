package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.base.BaseMetaService;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.application.tenant.MetaContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.time.Instant;
import java.util.*;

/** Manages model-field bindings and their schema effects inside facade transactions. */
@Slf4j
@RequiredArgsConstructor
final class ModelFieldBindingSupport extends BaseMetaService {
    private final MetaModelMapper metaModelMapper;

    private final MetaFieldMapper metaFieldMapper;

    private final MetaModelFieldBindingMapper fieldBindingMapper;

    private final SchemaManagementService schemaManagementService;

    private final IsModelExistsOperation0 isModelExistsOperation0;

    private final IsFieldExistsOperation1 isFieldExistsOperation1;

    private final IsModelExistsOperation2 isModelExistsOperation2;

    private final IsFieldExistsOperation3 isFieldExistsOperation3;

    @FunctionalInterface
    interface IsModelExistsOperation0 { boolean execute(String modelCode); }

    @FunctionalInterface
    interface IsFieldExistsOperation1 { boolean execute(String modelCode, String fieldCode); }

    @FunctionalInterface
    interface IsModelExistsOperation2 { boolean execute(Long modelId); }

    @FunctionalInterface
    interface IsFieldExistsOperation3 { boolean execute(Long fieldId); }

    private boolean isModelExists(String modelCode) { return isModelExistsOperation0.execute(modelCode); }

    private boolean isFieldExists(String modelCode, String fieldCode) { return isFieldExistsOperation1.execute(modelCode,fieldCode); }

    private boolean isModelExists(Long modelId) { return isModelExistsOperation2.execute(modelId); }

    private boolean isFieldExists(Long fieldId) { return isFieldExistsOperation3.execute(fieldId); }

    boolean isFieldBoundToModel(Long modelId, Long fieldId){
        try {
            return fieldBindingMapper.countByModelAndField(modelId, fieldId) > 0;
        } catch (Exception e) {
            // exists-check semantics: see existsModelById above for full pattern note.
            log.error("检查字段绑定关系失败: modelId={}, fieldId={}, error={}", modelId, fieldId, logSafe(e.getMessage()), e);
            return false;
        }
    }

    ModelFieldBinding bindFieldToModel(Long modelId, Long fieldId, Integer fieldOrder,
                                              Boolean required, Boolean visible, Boolean editable, String defaultValue,
                                              String validationRules, String displayConfig, String remarks){

        logMetaOperation("bindFieldToModel", "modelId=" + modelId + ", fieldId=" + fieldId);

        try {
            // 验证模型是否存在
            if (!isModelExists(modelId)) {
                throw new MetaServiceException("模型不存在: " + modelId);
            }

            // 验证字段是否存在
            if (!isFieldExists(fieldId)) {
                throw new MetaServiceException("字段不存在: " + fieldId);
            }

            // 检查是否已经绑定
            if (isFieldBoundToModel(modelId, fieldId)) {
                throw new MetaServiceException("字段已经绑定到该模型: modelId=" + modelId + ", fieldId=" + fieldId);
            }

            ModelFieldBinding binding = new ModelFieldBinding();
            binding.setModelId(modelId);
            binding.setFieldId(fieldId);
            binding.setFieldOrder(fieldOrder != null ? fieldOrder : 0);
            binding.setRequired(required != null ? required : false);
            binding.setVisible(visible != null ? visible : true);
            binding.setEditable(editable != null ? editable : true);
            binding.setDefaultValue(defaultValue);
            binding.setValidationRules(validationRules);
            binding.setDisplayConfig(displayConfig);
            binding.setRemarks(remarks);
            binding.setTenantId(MetaContext.getCurrentTenantId());

            binding.setCreatedAt(Instant.now());
            binding.setUpdatedAt(Instant.now());

            int result = fieldBindingMapper.insert(binding);
            if (result <= 0) {
                throw new MetaServiceException("绑定字段到模型失败");
            }

            log.info("字段绑定成功: bindingId={}, modelId={}, fieldId={}", binding.getId(), modelId, fieldId);

            // If the model is already PUBLISHED, execute ALTER TABLE ADD COLUMN
            Model model = metaModelMapper.selectById(modelId);
            if (model != null && model.isPublished()) {
                Field field = metaFieldMapper.selectById(fieldId);
                if (field != null) {
                    log.info("模型已发布，执行 ALTER TABLE ADD COLUMN: modelCode={}, fieldCode={}",
                            logSafe(model.getCode()), logSafe(field.getCode()));
                    schemaManagementService.addFieldToModel(model.getCode(), field.getCode());
                }
            }

            return binding;

        } catch (Exception e) {
            log.error("绑定字段到模型失败: modelId={}, fieldId={}, error={}", modelId, fieldId, logSafe(e.getMessage()), e);
            throw new MetaServiceException("绑定字段到模型失败: " + e.getMessage(), e);
        }
    }

    boolean unbindFieldFromModel(Long modelId, Long fieldId){
        logMetaOperation("unbindFieldFromModel", "modelId=" + modelId + ", fieldId=" + fieldId);

        try {
            // Check if this is a system binding - system fields cannot be unbound
            ModelFieldBinding existingBinding = fieldBindingMapper.findByModelAndField(
                modelId, fieldId, MetaContext.getCurrentTenantId());
            if (existingBinding != null && Boolean.TRUE.equals(existingBinding.getIsSystemBinding())) {
                throw new MetaServiceException("Cannot unbind system field, it is required by the system");
            }

            // If the model is already PUBLISHED, execute ALTER TABLE DROP COLUMN before unbinding
            Model model = metaModelMapper.selectById(modelId);
            if (model != null && model.isPublished()) {
                Field field = metaFieldMapper.selectById(fieldId);
                if (field != null) {
                    log.info("模型已发布，执行 ALTER TABLE DROP COLUMN: modelCode={}, fieldCode={}",
                            logSafe(model.getCode()), logSafe(field.getCode()));
                    schemaManagementService.removeFieldFromModel(model.getCode(), field.getCode());
                }
            }

            int result = fieldBindingMapper.deleteByModelAndField(modelId, fieldId);
            boolean success = result > 0;

            if (success) {
                log.info("字段解绑成功: modelId={}, fieldId={}", modelId, fieldId);
            } else {
                log.warn("字段解绑失败，可能绑定关系不存在: modelId={}, fieldId={}", modelId, fieldId);
            }

            return success;

        } catch (Exception e) {
            log.error("解绑字段失败: modelId={}, fieldId={}, error={}", modelId, fieldId, logSafe(e.getMessage()), e);
            throw new MetaServiceException("解绑字段失败: " + e.getMessage(), e);
        }
    }

    List<ModelFieldBinding> getModelFieldBindings(Long modelId, Boolean includeDetails){
        logMetaOperation("getModelFieldBindings", "modelId=" + modelId + ", includeDetails=" + includeDetails);

        try {
            List<ModelFieldBinding> bindings = fieldBindingMapper.findByModelId(modelId);

            if (includeDetails != null && includeDetails) {
                // 如果需要详细信息，可以在这里加载字段的详细信息
                // 这里简化处理，直接返回绑定关系
            }

            log.debug("获取模型字段绑定成功: modelId={}, count={}", modelId, bindings.size());
            return bindings;

        } catch (Exception e) {
            log.error("获取模型字段绑定失败: modelId={}, error={}", modelId, logSafe(e.getMessage()), e);
            throw new MetaServiceException("获取模型字段绑定失败: " + e.getMessage(), e);
        }
    }

    Optional<ModelFieldBinding> getFieldBinding(Long modelId, Long fieldId){
        logMetaOperation("getFieldBinding", "modelId=" + modelId + ", fieldId=" + fieldId);

        try {
            ModelFieldBinding binding = fieldBindingMapper.selectByModelAndField(modelId, fieldId);
            return Optional.ofNullable(binding);

        } catch (Exception e) {
            log.error("获取字段绑定关系失败: modelId={}, fieldId={}, error={}", modelId, fieldId, logSafe(e.getMessage()), e);
            throw new MetaServiceException("获取字段绑定关系失败: " + e.getMessage(), e);
        }
    }

    ModelFieldBinding updateFieldBinding(ModelFieldBinding binding){
        logMetaOperation("updateFieldBinding", "bindingId=" + binding.getId());

        try {
            binding.setUpdatedAt(Instant.now());

            int result = fieldBindingMapper.updateById(binding);
            if (result <= 0) {
                throw new MetaServiceException("更新字段绑定关系失败");
            }

            log.info("字段绑定关系更新成功: bindingId={}", binding.getId());
            return binding;

        } catch (Exception e) {
            log.error("更新字段绑定关系失败: bindingId={}, error={}", binding.getId(), logSafe(e.getMessage()), e);
            throw new MetaServiceException("更新字段绑定关系失败: " + e.getMessage(), e);
        }
    }

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }
}

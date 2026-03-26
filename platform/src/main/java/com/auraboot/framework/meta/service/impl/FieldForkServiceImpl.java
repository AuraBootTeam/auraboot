package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.FieldForkRequest;
import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.entity.FieldForkHistory;
import com.auraboot.framework.meta.mapper.FieldForkHistoryMapper;
import com.auraboot.framework.meta.service.FieldForkService;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.ModelFieldBindingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Field fork service implementation
 * Manages field fork operations for creating field variants
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FieldForkServiceImpl implements FieldForkService {

    private final MetaFieldService metaFieldService;
    private final ModelFieldBindingService modelFieldBindingService;
    private final FieldForkHistoryMapper forkHistoryMapper;

    @Override
    @Transactional
    public MetaFieldDTO forkField(String originalFieldPid, FieldForkRequest request) {
        if (!StringUtils.hasText(originalFieldPid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "Original field PID cannot be empty");
        }
        if (request == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "Fork request cannot be null");
        }
        if (!StringUtils.hasText(request.getNewCode())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "New field code cannot be empty");
        }

        log.info("Forking field: originalFieldPid={}, newCode={}", originalFieldPid, request.getNewCode());

        // 1. Get original field
        MetaFieldDTO originalField = metaFieldService.findByPid(originalFieldPid);
        if (originalField == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "Original field not found: " + originalFieldPid);
        }

        // 2. Check if new code is unique
        if (!metaFieldService.isCodeUnique(request.getNewCode(), null)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "Field code already exists: " + request.getNewCode());
        }

        // 3. Create new field with modifications
        MetaFieldCreateRequest createRequest = new MetaFieldCreateRequest();
        createRequest.setCode(request.getNewCode());
        
        // Copy base properties
        createRequest.setDataType(originalField.getDataType());
        createRequest.setDataSourceId(originalField.getDataSourceId());
        
        // Apply modifications
        if (request.getSemanticType() != null) {
            // Semantic type modification
            if (createRequest.getExtension() == null) {
                createRequest.setExtension(new java.util.HashMap<>());
            }
            createRequest.getExtension().put("semanticType", request.getSemanticType());
        }
        
        if (request.getFeature() != null) {
            createRequest.setFeature(request.getFeature());
        } else if (originalField.getExtension() != null && originalField.getExtension().containsKey("feature")) {
            Object featureObj = originalField.getExtension().get("feature");
            if (featureObj instanceof java.util.Map) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> featureMap = (java.util.Map<String, Object>) featureObj;
                createRequest.setFeature(featureMap);
            }
        }
        
        if (request.getDictCode() != null) {
            // Dictionary binding will be handled after field creation
            createRequest.setExtension(createRequest.getExtension() != null ? 
                createRequest.getExtension() : new java.util.HashMap<>());
            createRequest.getExtension().put("dictCode", request.getDictCode());
        }
        
        // Copy other properties from original field
        if (originalField.getExtension() != null) {
            if (createRequest.getExtension() == null) {
                createRequest.setExtension(new java.util.HashMap<>(originalField.getExtension()));
            } else {
                // Merge extensions
                originalField.getExtension().forEach((key, value) -> {
                    if (!createRequest.getExtension().containsKey(key)) {
                        createRequest.getExtension().put(key, value);
                    }
                });
            }
        }
        
        createRequest.setStatus(StatusConstants.DRAFT);
        createRequest.setVersionNote("Forked from " + originalField.getCode() + 
            (StringUtils.hasText(request.getForkReason()) ? ": " + request.getForkReason() : ""));

        // 4. Create forked field
        MetaFieldDTO forkedField = metaFieldService.create(createRequest);
        
        log.info("Forked field created: originalPid={}, forkedPid={}, forkedCode={}", 
            originalFieldPid, forkedField.getPid(), forkedField.getCode());

        // 5. Record fork history
        FieldForkHistory history = FieldForkHistory.builder()
            .tenantId(MetaContext.getCurrentTenantId())
            .originalFieldId(originalField.getId())
            .originalFieldPid(originalFieldPid)
            .originalFieldCode(originalField.getCode())
            .forkedFieldId(forkedField.getId())
            .forkedFieldPid(forkedField.getPid())
            .forkedFieldCode(forkedField.getCode())
            .forkReason(request.getForkReason())
            .forkedBy(MetaContext.getCurrentUserId() != null ? 
                MetaContext.getCurrentUserId().toString() : "system")
            .forkedAt(Instant.now())
            .build();
        
        forkHistoryMapper.insert(history);
        
        log.info("Fork history recorded: historyId={}", history.getId());

        // 6. Optionally replace binding in current model
        if (Boolean.TRUE.equals(request.getReplaceInCurrentModel()) && 
            StringUtils.hasText(request.getCurrentModelPid())) {
            
            try {
                replaceFieldInBinding(request.getCurrentModelPid(), originalFieldPid, forkedField.getPid());
                log.info("Replaced field in model binding: modelPid={}, originalFieldPid={}, forkedFieldPid={}", 
                    request.getCurrentModelPid(), originalFieldPid, forkedField.getPid());
            } catch (Exception e) {
                log.error("Failed to replace field in binding: modelPid={}, error={}", 
                    request.getCurrentModelPid(), e.getMessage(), e);
                // Don't fail the fork operation if binding replacement fails
            }
        }

        return forkedField;
    }

    @Override
    public List<FieldForkHistory> getForkHistory(String fieldPid) {
        if (!StringUtils.hasText(fieldPid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "Field PID cannot be empty");
        }

        log.debug("Getting fork history for field: fieldPid={}", fieldPid);

        Long tenantId = MetaContext.getCurrentTenantId();
        
        // Get history where this field is either original or forked
        List<FieldForkHistory> asOriginal = forkHistoryMapper.findByOriginalFieldPid(fieldPid, tenantId);
        List<FieldForkHistory> asForked = forkHistoryMapper.findByForkedFieldPid(fieldPid, tenantId);
        
        // Combine and deduplicate
        List<FieldForkHistory> allHistory = new java.util.ArrayList<>(asOriginal);
        allHistory.addAll(asForked);
        
        return allHistory.stream()
            .distinct()
            .sorted((h1, h2) -> h2.getForkedAt().compareTo(h1.getForkedAt()))
            .collect(Collectors.toList());
    }

    @Override
    public Optional<MetaFieldDTO> getOriginalField(String forkedFieldPid) {
        if (!StringUtils.hasText(forkedFieldPid)) {
            return Optional.empty();
        }

        log.debug("Getting original field for forked field: forkedFieldPid={}", forkedFieldPid);

        Long tenantId = MetaContext.getCurrentTenantId();
        String originalFieldPid = forkHistoryMapper.getOriginalFieldPid(forkedFieldPid, tenantId);
        
        if (!StringUtils.hasText(originalFieldPid)) {
            return Optional.empty();
        }
        
        MetaFieldDTO originalField = metaFieldService.findByPid(originalFieldPid);
        return Optional.ofNullable(originalField);
    }

    @Override
    public List<MetaFieldDTO> getForkedVariants(String originalFieldPid) {
        if (!StringUtils.hasText(originalFieldPid)) {
            return java.util.Collections.emptyList();
        }

        log.debug("Getting forked variants for field: originalFieldPid={}", originalFieldPid);

        Long tenantId = MetaContext.getCurrentTenantId();
        List<String> forkedFieldPids = forkHistoryMapper.findForkedFieldPids(originalFieldPid, tenantId);
        
        return forkedFieldPids.stream()
            .map(metaFieldService::findByPid)
            .filter(field -> field != null)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void replaceFieldInBinding(String modelPid, String originalFieldPid, String forkedFieldPid) {
        if (!StringUtils.hasText(modelPid) || !StringUtils.hasText(originalFieldPid) || 
            !StringUtils.hasText(forkedFieldPid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "Model PID, original field PID, and forked field PID cannot be empty");
        }

        log.info("Replacing field in binding: modelPid={}, originalFieldPid={}, forkedFieldPid={}", 
            modelPid, originalFieldPid, forkedFieldPid);

        // 1. Verify forked field exists
        MetaFieldDTO forkedField = metaFieldService.findByPid(forkedFieldPid);
        if (forkedField == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "Forked field not found: " + forkedFieldPid);
        }

        // 2. Unbind original field
        boolean unbindSuccess = modelFieldBindingService.unbindFieldFromModel(modelPid, originalFieldPid);
        if (!unbindSuccess) {
            log.warn("Original field was not bound to model: modelPid={}, originalFieldPid={}", 
                modelPid, originalFieldPid);
        }

        // 3. Bind forked field
        modelFieldBindingService.bindFieldToModel(
            modelPid,
            forkedFieldPid,
            null,  // displayOrder - will be auto-calculated
            false, // isRequired
            false, // isReadonly
            true   // isVisible
        );

        log.info("Field replaced in binding successfully: modelPid={}, forkedFieldPid={}", 
            modelPid, forkedFieldPid);
    }
}

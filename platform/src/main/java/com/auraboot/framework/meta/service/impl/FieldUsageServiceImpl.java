package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.BindingConfiguration;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.FieldUsageCache;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.mapper.FieldUsageCacheMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.service.FieldUsageService;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.common.util.DateUtil;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.ModelFieldBindingService;
import com.auraboot.framework.meta.service.NamedQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Field usage service implementation
 * Tracks and reports field usage across models, pages, and queries
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FieldUsageServiceImpl implements FieldUsageService {

    private final MetaFieldService metaFieldService;
    private final ModelFieldBindingService modelFieldBindingService;
    private final NamedQueryService namedQueryService;
    private final PageSchemaMapper pageSchemaMapper;
    private final MetaFieldMapper metaFieldMapper;
    private final FieldUsageCacheMapper fieldUsageCacheMapper;
    private final MetaModelFieldBindingMapper modelFieldBindingMapper;

    @Override
    public FieldUsageInfo getFieldUsage(String fieldPid) {
        log.debug("Getting field usage for: {}", fieldPid);

        MetaFieldDTO field = metaFieldService.findByPid(fieldPid);
        if (field == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "Field not found: " + fieldPid);
        }

        // Try to get from cache
        FieldUsageCache cache = fieldUsageCacheMapper.findByFieldId(field.getId());
        
        if (cache == null) {
            // Cache miss - calculate and cache
            log.info("Cache miss for field {}, calculating usage", fieldPid);
            calculateUsageStatistics(fieldPid);
            cache = fieldUsageCacheMapper.findByFieldId(field.getId());
        }

        // Convert to FieldUsageInfo
        FieldUsageInfo info = new FieldUsageInfo();
        info.setFieldPid(fieldPid);
        info.setFieldCode(field.getCode());
        
        if (cache != null) {
            info.setModelCount(cache.getModelCount() != null ? cache.getModelCount() : 0);
            info.setPageCount(cache.getPageCount() != null ? cache.getPageCount() : 0);
            info.setQueryCount(cache.getQueryCount() != null ? cache.getQueryCount() : 0);
            info.setTotalReferences(cache.getTotalReferences() != null ? cache.getTotalReferences() : 0);
            info.setCoreField(cache.getIsCoreField() != null && cache.getIsCoreField());
            info.setLastUsedAt(cache.getLastUsedAt());
            info.setUsageFrequency(cache.getUsageFrequency());
        } else {
            info.setModelCount(0);
            info.setPageCount(0);
            info.setQueryCount(0);
            info.setTotalReferences(0);
            info.setCoreField(false);
        }

        return info;
    }

    @Override
    public boolean isFieldUsed(String fieldPid) {
        log.debug("Checking if field is used: {}", fieldPid);

        MetaFieldDTO field = metaFieldService.findByPid(fieldPid);
        if (field == null) {
            return false;
        }

        // Check cache first
        FieldUsageCache cache = fieldUsageCacheMapper.findByFieldId(field.getId());
        if (cache != null && cache.getTotalReferences() != null && cache.getTotalReferences() > 0) {
            return true;
        }

        // Fallback to direct query
        List<ModelFieldBinding> bindings = modelFieldBindingMapper.findByFieldId(field.getId());
        return !bindings.isEmpty();
    }

    @Override
    public List<ModelReference> getModelsUsingField(String fieldPid) {
        log.debug("Getting models using field: {}", fieldPid);

        MetaFieldDTO field = metaFieldService.findByPid(fieldPid);
        if (field == null) {
            return Collections.emptyList();
        }

        // Get all bindings for this field
        List<ModelFieldBinding> bindings = modelFieldBindingMapper.findByFieldId(field.getId());

        // Convert to ModelReference list
        return bindings.stream()
            .map(binding -> {
                ModelReference ref = new ModelReference();
                // Note: We need model info here, but to avoid circular dependency,
                // we'll just set the model ID for now
                ref.setModelPid("model_" + binding.getModelId()); // Placeholder
                ref.setModelCode("Model " + binding.getModelId());
                ref.setModelDisplayName("Model " + binding.getModelId());
                return ref;
            })
            .collect(Collectors.toList());
    }

    @Override
    public List<BindingConfiguration> getBindingConfigurations(String fieldPid) {
        log.debug("Getting binding configurations for field: {}", fieldPid);

        MetaFieldDTO field = metaFieldService.findByPid(fieldPid);
        if (field == null) {
            return Collections.emptyList();
        }

        // Get all bindings for this field
        List<ModelFieldBinding> bindings = modelFieldBindingMapper.findByFieldId(field.getId());

        // Convert to BindingConfiguration list
        return bindings.stream()
            .map(this::convertToBindingConfiguration)
            .collect(Collectors.toList());
    }

    @Override
    public FieldUsageReport exportUsageReport(String fieldPid) {
        log.debug("Exporting usage report for field: {}", fieldPid);

        FieldUsageReport report = new FieldUsageReport();
        report.setFieldPid(fieldPid);
        
        MetaFieldDTO field = metaFieldService.findByPid(fieldPid);
        if (field != null) {
            report.setFieldCode(field.getCode());
        }

        // Get usage info
        report.setUsageInfo(getFieldUsage(fieldPid));

        // Get models
        report.setModels(getModelsUsingField(fieldPid));

        // Get pages referencing this field
        if (field != null) {
            report.setPages(pageSchemaMapper.findPageNamesByFieldCodeInDsl(field.getCode()));
        } else {
            report.setPages(new ArrayList<>());
        }

        // Get queries referencing this field
        if (field != null) {
            report.setQueries(namedQueryService.getQueryCodesByFieldCode(field.getCode()));
        } else {
            report.setQueries(new ArrayList<>());
        }

        report.setGeneratedAt(Instant.now());

        log.info("Generated usage report for field {}: {} models, {} pages, {} queries",
            fieldPid, report.getModels().size(), report.getPages().size(), report.getQueries().size());

        return report;
    }

    @Override
    @Transactional
    public FieldUsageStatistics calculateUsageStatistics(String fieldPid) {
        log.debug("Calculating usage statistics for field: {}", fieldPid);

        MetaFieldDTO field = metaFieldService.findByPid(fieldPid);
        if (field == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "Field not found: " + fieldPid);
        }

        // Count model references
        List<ModelFieldBinding> bindings = modelFieldBindingMapper.findByFieldId(field.getId());
        int modelCount = bindings.size();

        // Count page references by searching DSL schemas
        int pageCount = pageSchemaMapper.countByFieldCodeInDsl(field.getCode());

        // Count query references
        int queryCount = namedQueryService.countByFieldCode(field.getCode());

        // Calculate total references
        int totalReferences = modelCount + pageCount + queryCount;

        // Calculate usage frequency (0-100 scale)
        double usageFrequency = calculateUsageFrequency(totalReferences);

        // Update cache
        FieldUsageCache cache = FieldUsageCache.builder()
            .tenantId(MetaContext.getCurrentTenantId())
            .fieldId(field.getId())
            .modelCount(modelCount)
            .pageCount(pageCount)
            .queryCount(queryCount)
            .totalReferences(totalReferences)
            .isCoreField(isSystemField(field.getCode()))
            .lastUsedAt(totalReferences > 0 ? Instant.now() : null)
            .usageFrequency(BigDecimal.valueOf(usageFrequency))
            .updatedAt(Instant.now())
            .build();

        fieldUsageCacheMapper.upsert(cache);

        // Build statistics result
        FieldUsageStatistics stats = new FieldUsageStatistics();
        stats.setFieldPid(fieldPid);
        stats.setModelCount(modelCount);
        stats.setPageCount(pageCount);
        stats.setQueryCount(queryCount);
        stats.setTotalReferences(totalReferences);
        stats.setUsageFrequency(BigDecimal.valueOf(usageFrequency));
        stats.setCalculatedAt(Instant.now());

        log.info("Calculated usage statistics for field {}: {} models, {} pages, {} queries, frequency: {}",
            fieldPid, modelCount, pageCount, queryCount, usageFrequency);

        return stats;
    }

    @Override
    @Transactional
    public void refreshUsageCache(String fieldPid) {
        log.debug("Refreshing usage cache for field: {}", fieldPid);
        calculateUsageStatistics(fieldPid);
    }

    /**
     * Scheduled via DatabaseSchedulerEngine (sys-field-usage-refresh, cron 0 0 2 * * ?).
     */
    @Override
    @Transactional
    public void refreshAllUsageCache() {
        log.info("Starting scheduled refresh of all field usage caches");

        Long tenantId = MetaContext.getCurrentTenantId();
        
        // Get all current fields
        List<Field> allFields = metaFieldMapper.findCurrentByTenant();

        int successCount = 0;
        int errorCount = 0;

        for (Field field : allFields) {
            try {
                calculateUsageStatistics(field.getPid());
                successCount++;
            } catch (Exception e) {
                log.error("Failed to refresh usage cache for field {}: {}", field.getPid(), e.getMessage());
                errorCount++;
            }
        }

        log.info("Completed scheduled refresh of field usage caches: {} succeeded, {} failed",
            successCount, errorCount);
    }

    // ==================== Private Helper Methods ====================

    /**
     * Calculate usage frequency score (0-100)
     */
    private double calculateUsageFrequency(int totalReferences) {
        if (totalReferences == 0) {
            return 0.0;
        }
        if (totalReferences >= 100) {
            return 100.0;
        }
        // Simple linear scale: 1 reference = 1 point
        return totalReferences;
    }

    private boolean isSystemField(String fieldCode) {
        return SystemFieldConstants.isSystemField(fieldCode);
    }

    /**
     * Convert ModelFieldBinding to BindingConfiguration
     */
    private BindingConfiguration convertToBindingConfiguration(ModelFieldBinding binding) {
        return BindingConfiguration.builder()
            .bindingId(binding.getId())
            .modelPid("model_" + binding.getModelId()) // Placeholder
            .modelCode("Model " + binding.getModelId())
            .fieldPid("field_" + binding.getFieldId()) // Placeholder
            .fieldCode("Field " + binding.getFieldId())
            .required(binding.getRequired())
            .visible(binding.getVisible())
            .editable(binding.getEditable())
            .defaultValue(binding.getDefaultValue())
            .validationRules(binding.getValidationRules())
            .displayConfig(binding.getDisplayConfig())
            .fieldOrder(binding.getFieldOrder())
            .remarks(binding.getRemarks())
            .createdAt(DateUtil.toUtcLocalDateTime(binding.getCreatedAt()))
            .updatedAt(DateUtil.toUtcLocalDateTime(binding.getUpdatedAt()))
            .build();
    }
}

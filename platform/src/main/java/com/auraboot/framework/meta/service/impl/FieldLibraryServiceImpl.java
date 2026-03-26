package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.meta.dto.FieldRecommendation;
import com.auraboot.framework.meta.dto.FieldSearchRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.FieldUsageCache;
import com.auraboot.framework.meta.mapper.FieldUsageCacheMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.service.FieldLibraryService;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.common.util.DateUtil;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Field library service implementation
 * Provides field library management and advanced query capabilities
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FieldLibraryServiceImpl implements FieldLibraryService {

    private final MetaFieldService metaFieldService;
    private final MetaFieldMapper metaFieldMapper;
    private final FieldUsageCacheMapper fieldUsageCacheMapper;

    // Delegate to canonical constant — intentionally broader than previous inline set
    // (now also includes pid, created_by, updated_by, version)
    private static boolean isSystemFieldCode(String code) {
        return SystemFieldConstants.isSystemField(code);
    }

    @Override
    public Map<String, List<MetaFieldDTO>> listFieldsBySemanticType() {
        log.debug("Listing fields grouped by semantic type");

        // Get all current fields from tenant
        List<MetaFieldDTO> allFields = metaFieldService.findCurrentByTenant();

        // Group by semantic type
        Map<String, List<MetaFieldDTO>> groupedFields = allFields.stream()
            .collect(Collectors.groupingBy(
                field -> extractSemanticType(field),
                LinkedHashMap::new,
                Collectors.toList()
            ));

        log.info("Grouped {} fields into {} semantic types", allFields.size(), groupedFields.size());
        return groupedFields;
    }

    @Override
    public PageResult<MetaFieldDTO> searchFields(FieldSearchRequest request) {
        log.debug("Searching fields with request: {}", request);

        int page = request.getPage() != null ? request.getPage() : 1;
        int size = request.getSize() != null ? request.getSize() : 20;
        Page<Field> pageRequest = new Page<>(page, size);
        IPage<Field> pageResult = metaFieldMapper.selectPageList(
            pageRequest,
            request.getKeyword(),
            request.getBaseType(),
            null, // status
            true  // currentOnly
        );
        List<Field> baseFields = pageResult.getRecords();

        // Apply additional filters
        List<MetaFieldDTO> filteredFields = baseFields.stream()
            .map(this::convertToDTO)
            .filter(field -> matchesSearchCriteria(field, request))
            .collect(Collectors.toList());

        // Get total count
        long total = pageResult.getTotal();

        log.info("Found {} fields matching search criteria", filteredFields.size());
        return new PageResult<>(filteredFields, total, (long) size, (long) page);
    }

    @Override
    public List<FieldRecommendation> getFieldRecommendations(String modelPid, String semanticType) {
        log.debug("Getting field recommendations for model: {}, semanticType: {}", modelPid, semanticType);

        // Get all current fields
        List<MetaFieldDTO> allFields = metaFieldService.findCurrentByTenant();

        // Filter by semantic type if provided
        List<MetaFieldDTO> candidateFields = allFields;
        if (StringUtils.hasText(semanticType)) {
            candidateFields = allFields.stream()
                .filter(field -> semanticType.equals(extractSemanticType(field)))
                .collect(Collectors.toList());
        }

        // Build recommendations with relevance scores
        List<FieldRecommendation> recommendations = candidateFields.stream()
            .map(field -> buildRecommendation(field, semanticType))
            .sorted(Comparator.comparing(FieldRecommendation::getRelevanceScore).reversed()
                .thenComparing(Comparator.comparing(FieldRecommendation::getUsageCount).reversed()))
            .collect(Collectors.toList());

        log.info("Generated {} field recommendations", recommendations.size());
        return recommendations;
    }

    @Override
    public List<MetaFieldDTO> getSystemFields() {
        log.debug("Getting system fields");

        List<MetaFieldDTO> allFields = metaFieldService.findCurrentByTenant();
        
        List<MetaFieldDTO> systemFields = allFields.stream()
            .filter(field -> isSystemFieldCode(field.getCode()))
            .collect(Collectors.toList());

        log.info("Found {} system fields", systemFields.size());
        return systemFields;
    }

    @Override
    public List<MetaFieldDTO> getCommonBusinessFields() {
        log.debug("Getting common business fields");

        Long tenantId = MetaContext.getCurrentTenantId();
        
        // Get highly used fields from cache (usage frequency >= 50)
        List<FieldUsageCache> highlyUsedCache = fieldUsageCacheMapper.findHighlyUsedFields(tenantId, 50.0);

        // Get field details
        List<Long> fieldIds = highlyUsedCache.stream()
            .map(FieldUsageCache::getFieldId)
            .collect(Collectors.toList());

        if (fieldIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Field> fields = metaFieldMapper.findByIds(fieldIds);
        List<MetaFieldDTO> result = fields.stream()
            .map(this::convertToDTO)
            .collect(Collectors.toList());

        log.info("Found {} common business fields", result.size());
        return result;
    }

    @Override
    public List<MetaFieldDTO> getUnusedFields() {
        log.debug("Getting unused fields");

        Long tenantId = MetaContext.getCurrentTenantId();
        
        // Get unused fields from cache
        List<FieldUsageCache> unusedCache = fieldUsageCacheMapper.findUnusedFields(tenantId);

        // Get field details
        List<Long> fieldIds = unusedCache.stream()
            .map(FieldUsageCache::getFieldId)
            .collect(Collectors.toList());

        if (fieldIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Field> fields = metaFieldMapper.findByIds(fieldIds);
        List<MetaFieldDTO> result = fields.stream()
            .map(this::convertToDTO)
            .collect(Collectors.toList());

        log.info("Found {} unused fields", result.size());
        return result;
    }

    // ==================== Private Helper Methods ====================

    /**
     * Extract semantic type from field
     */
    private String extractSemanticType(MetaFieldDTO field) {
        if (field.getExtension() != null) {
            Object semanticType = field.getExtension().get("semanticType");
            if (semanticType != null) {
                return semanticType.toString();
            }
        }
        return "unspecified";
    }

    /**
     * Check if field matches search criteria
     */
    private boolean matchesSearchCriteria(MetaFieldDTO field, FieldSearchRequest request) {
        // Filter by semantic type
        if (StringUtils.hasText(request.getSemanticType())) {
            String fieldSemanticType = extractSemanticType(field);
            if (!request.getSemanticType().equals(fieldSemanticType)) {
                return false;
            }
        }

        // Filter by system fields only
        if (Boolean.TRUE.equals(request.getSystemFieldsOnly())) {
            if (!isSystemFieldCode(field.getCode())) {
                return false;
            }
        }

        // Filter by usage count (requires cache lookup)
        if (request.getMinUsageCount() != null || request.getMaxUsageCount() != null) {
            FieldUsageCache cache = fieldUsageCacheMapper.findByFieldId(field.getId());
            if (cache != null) {
                int usageCount = cache.getTotalUsageCount();
                if (request.getMinUsageCount() != null && usageCount < request.getMinUsageCount()) {
                    return false;
                }
                if (request.getMaxUsageCount() != null && usageCount > request.getMaxUsageCount()) {
                    return false;
                }
            } else {
                // No cache means zero usage
                if (request.getMinUsageCount() != null && request.getMinUsageCount() > 0) {
                    return false;
                }
            }
        }

        // Filter by unused only
        if (Boolean.TRUE.equals(request.getUnusedOnly())) {
            FieldUsageCache cache = fieldUsageCacheMapper.findByFieldId(field.getId());
            if (cache == null || cache.getTotalUsageCount() > 0) {
                return false;
            }
        }

        return true;
    }

    /**
     * Build field recommendation with relevance score
     */
    private FieldRecommendation buildRecommendation(MetaFieldDTO field, String targetSemanticType) {
        // Calculate relevance score
        double relevanceScore = calculateRelevanceScore(field, targetSemanticType);

        // Get usage count from cache
        int usageCount = 0;
        List<String> usedByModels = new ArrayList<>();
        FieldUsageCache cache = fieldUsageCacheMapper.findByFieldId(field.getId());
        if (cache != null) {
            usageCount = cache.getTotalUsageCount();
        }

        // Build recommendation reason
        String reason = buildRecommendationReason(field, usageCount);

        return FieldRecommendation.builder()
            .field(field)
            .usageCount(usageCount)
            .relevanceScore(relevanceScore)
            .recommendationReason(reason)
            .usedByModels(usedByModels)
            .build();
    }

    /**
     * Calculate relevance score based on semantic type similarity
     */
    private double calculateRelevanceScore(MetaFieldDTO field, String targetSemanticType) {
        String fieldSemanticType = extractSemanticType(field);

        // System fields always have high relevance
        if (isSystemFieldCode(field.getCode())) {
            return 1.0;
        }

        // Exact match
        if (StringUtils.hasText(targetSemanticType) && targetSemanticType.equals(fieldSemanticType)) {
            return 0.9;
        }

        // Partial match (e.g., "user_status" matches "status")
        if (StringUtils.hasText(targetSemanticType) && fieldSemanticType.contains(targetSemanticType)) {
            return 0.7;
        }

        // Default relevance
        return 0.5;
    }

    /**
     * Build recommendation reason text
     */
    private String buildRecommendationReason(MetaFieldDTO field, int usageCount) {
        if (isSystemFieldCode(field.getCode())) {
            return "System field";
        }
        if (usageCount > 10) {
            return "Highly used field (" + usageCount + " references)";
        }
        if (usageCount > 0) {
            return "Used in " + usageCount + " contexts";
        }
        return "Available for use";
    }

    /**
     * Convert Field to MetaFieldDTO
     */
    private MetaFieldDTO convertToDTO(Field entity) {
        MetaFieldDTO dto = new MetaFieldDTO();
        dto.setId(entity.getId());
        dto.setPid(entity.getPid());
        dto.setCode(entity.getCode());
        dto.setDataType(entity.getDataType());
        dto.setDataSourceId(entity.getDataSourceId());
        dto.setVersion(entity.getVersion());
        dto.setIsCurrent(entity.getIsCurrent());
        dto.setStatus(entity.getStatus());
        dto.setTenantId(entity.getTenantId());
        dto.setCreatedAt(DateUtil.toUtcLocalDateTime(entity.getCreatedAt()));
        dto.setUpdatedAt(DateUtil.toUtcLocalDateTime(entity.getUpdatedAt()));

        // Convert extension bean to map
        if (entity.getExtension() != null) {
            // ExtensionBean already provides getters for individual fields
            // For now, create a simple map representation
            Map<String, Object> extensionMap = new HashMap<>();
            // Add extension fields as needed
            dto.setExtension(extensionMap);
        }

        return dto;
    }
}
